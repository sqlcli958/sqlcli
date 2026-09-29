import { useEffect, useRef, useCallback, useMemo, useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import Sigma from 'sigma';
import Graph from 'graphology';
import FA2Layout from 'graphology-layout-forceatlas2/worker';
import { getTableDetail } from '../api/workspace';
import { onMutationSuccess } from '../api/mutationHelpers';
import { getRelation } from '../api/relations';
import { RelationEditor } from '../features/relation-editor/RelationEditor';
import { RelationTypeBadge } from '../features/relation-editor/RelationTypeBadge';
import type { GraphViewDto, MutationResultDto, RelationEdgeDto } from '../types/api';
import { adaptGraph, highlightNodeNeighbors, resetHighlight, SMALL_GRAPH } from './graphAdapter';
import { useGraphStore } from '../state/graphStore';

interface GraphCanvasProps {
  graphData: GraphViewDto | undefined;
  isLoading: boolean;
}

interface DisplayPosition {
  x: number;
  y: number;
  size: number;
}

interface Point {
  x: number;
  y: number;
}

interface ConnectionDrag {
  from: string;
  start: Point;
  current: Point;
}

interface RadialField {
  id: string;
  name: string;
  primaryKey: boolean;
  center: Point;
}

const GRAPH_THEME = {
  dark: {
    label: '#e8f7ff',
    mutedEdge: '#6f8ea8',
    overviewEdge: '#475569',
    edges: {
      foreign_key: '#65d8ff',
      join_observed: '#ffc266',
      term_mapping: '#ff82cb',
    },
  },
  light: {
    label: '#173047',
    mutedEdge: '#94a3b8',
    overviewEdge: '#c4ced8',
    edges: {
      foreign_key: '#087ea4',
      join_observed: '#b45309',
      term_mapping: '#be185d',
    },
  },
} as const;

function getGraphTheme() {
  return document.documentElement.dataset.theme === 'light' ? GRAPH_THEME.light : GRAPH_THEME.dark;
}

function getLayoutSettings(nodeCount: number) {
  const small = nodeCount <= SMALL_GRAPH;
  return {
    adjustSizes: true,
    // 小图（多半是按场景筛出来的十来张表）要往一起收：默认那组参数是给几百个点调的，
    // 十来个点用同样的斥力会摊得满屏都是，点与点之间全是空白
    gravity: small ? 0.3 : 0.06,
    scalingRatio: small ? 28 : Math.min(160, Math.max(45, 35 + Math.sqrt(nodeCount) * 4)),
    strongGravityMode: false,
    edgeWeightInfluence: 0.5,
    barnesHutOptimize: nodeCount > 100,
    barnesHutTheta: 0.6,
    slowDown: Math.max(4, 1 + Math.log(Math.max(nodeCount, 2))),
  };
}

/** 小图看全图；稀疏图先看关联区，孤立表仍可缩远查看。 */
function fitToNodes(sigma: Sigma, graph: Graph): void {
  const focusConnected = graph.order > SMALL_GRAPH &&
    graph.getAttribute('layout') === 'grouped' && graph.size > 0;
  if (graph.order === 0 || (graph.order > SMALL_GRAPH && !focusConnected)) return;
  let minX = Infinity;
  let maxX = -Infinity;
  let minY = Infinity;
  let maxY = -Infinity;
  graph.forEachNode((node, attrs) => {
    if (focusConnected && graph.degree(node) === 0) return;
    const x = Number(attrs.x);
    const y = Number(attrs.y);
    if (!Number.isFinite(x) || !Number.isFinite(y)) return;
    minX = Math.min(minX, x);
    maxX = Math.max(maxX, x);
    minY = Math.min(minY, y);
    maxY = Math.max(maxY, y);
  });
  if (!Number.isFinite(minX) || !Number.isFinite(minY)) return;
  // 不用 setCustomBBox：Sigma 每次 refresh 会按当前节点重算归一化范围，
  // 自定义包围盒会让小图的标签整层消失。
  // **必须在动画回调里 refresh。** 相机动画期间 Sigma 不画标签，动画结束时也不会自己
  // 补一帧——结果是一屏没有名字的圆点，而节点数据里标签、尺寸、可见性全都是对的
  // （实测：refresh 之前标签 0 像素，调一次就有 1712）。
  if (focusConnected) {
    const a = sigma.viewportToFramedGraph(sigma.graphToViewport({ x: minX, y: minY }));
    const b = sigma.viewportToFramedGraph(sigma.graphToViewport({ x: maxX, y: maxY }));
    const { width, height } = sigma.getDimensions();
    // 关联区占画布约七成，避免全库孤立表把关系缩成一个点。
    const ratio = Math.max(0.03,
      Math.max(Math.abs(b.x - a.x), Math.abs(b.y - a.y) * width / height) * 0.7);
    sigma.getCamera().animate({ x: (a.x + b.x) / 2, y: (a.y + b.y) / 2, ratio },
      { duration: 320 }, () => sigma.refresh());
    return;
  }
  sigma.getCamera().animate({ x: 0.5, y: 0.5, ratio: 1.18 }, { duration: 320 }, () => sigma.refresh());
}

function getLayoutDuration(nodeCount: number): number {
  // 十来个点半秒就收敛了，让它抖 7 秒纯属白等——而 fit 要等布局停了才做，
  // 等于筛完之后还要盯着一张乱图看 7 秒
  if (nodeCount <= SMALL_GRAPH) return 1_200;
  return Math.min(15_000, Math.max(7_000, 5_000 + nodeCount * 20));
}

function shouldRunForceLayout(graph: Graph): boolean {
  // 小图用稳定环形布局：力导会把相连表吸成几对，关系线和标签就会互相遮挡。
  if (graph.order <= SMALL_GRAPH || graph.order < 2 || graph.size === 0) {
    return false;
  }
  if (graph.getAttribute('layout') === 'grouped') return false;
  const connectedNodeCount = graph.nodes().filter((node) => graph.degree(node) > 0).length;
  return connectedNodeCount / graph.order >= 0.5;
}

function isTableNodeId(id: string | null): id is string {
  return Boolean(id && id.startsWith('table:'));
}

function parseTableNodeId(tableId: string): { alias: string; qualifiedName: string } | null {
  const parts = tableId.split(':');
  if (parts.length < 3) {
    return null;
  }
  return {
    alias: parts[1] ?? '',
    qualifiedName: parts.slice(2).join(':'),
  };
}

function buildColumnId(tableId: string, columnName: string): string {
  const parsed = parseTableNodeId(tableId);
  if (!parsed) {
    return `${tableId}.${columnName}`;
  }
  return `column:${parsed.alias}:${parsed.qualifiedName}.${columnName}`;
}

function endpointQualifiedName(id: string): string {
  const parts = id.split(':');
  return parts[parts.length - 1] ?? id;
}

function buildRadialFields(
  tableId: string,
  columns: Array<{ name: string; primaryKey: boolean }>,
  nodePosition: DisplayPosition | null,
): RadialField[] {
  if (!nodePosition) {
    return [];
  }

  const fieldsPerRing = 10;
  return columns.map((column, index) => {
    const ring = Math.floor(index / fieldsPerRing);
    const ringStart = ring * fieldsPerRing;
    const ringCount = Math.min(fieldsPerRing, columns.length - ringStart);
    const indexInRing = index - ringStart;
    const angle = -Math.PI / 2 + (Math.PI * 2 * indexInRing) / ringCount;
    const radius = Math.max(76, nodePosition.size + 58) + ring * 58;

    return {
      id: buildColumnId(tableId, column.name),
      name: column.name,
      primaryKey: column.primaryKey,
      center: {
        x: nodePosition.x + Math.cos(angle) * radius,
        y: nodePosition.y + Math.sin(angle) * radius,
      },
    };
  });
}

export function GraphCanvas({ graphData, isLoading }: GraphCanvasProps) {
  const containerRef = useRef<HTMLDivElement>(null);
  /**
   * sigma 专属挂载点，React 不在里面渲染任何东西。
   *
   * 两边共用一个容器时，sigma.kill() 清掉自己插的 canvas 会打乱 React 的子节点记账，
   * React 随后 unmount 就抛 "removeChild: The node to be removed is not a child of this node"。
   * 各管各的 DOM 是唯一稳的做法。
   */
  const sigmaHostRef = useRef<HTMLDivElement>(null);
  const sigmaRef = useRef<Sigma | null>(null);
  const graphRef = useRef<Graph | null>(null);
  const fa2Ref = useRef<FA2Layout | null>(null);
  const sourceTableIdRef = useRef<string | null>(null);
  const targetTableIdRef = useRef<string | null>(null);
  const renderFrameRef = useRef<number | null>(null);
  const cameraRatioRef = useRef(1);
  const queryClient = useQueryClient();

  const { selectedNodeId, selectNode, selectEdge, hoverNode, setLayoutRunning, setZoom } = useGraphStore();
  const [sourceTableId, setSourceTableId] = useState<string | null>(null);
  const [targetTableId, setTargetTableId] = useState<string | null>(null);
  const [connectionDrag, setConnectionDrag] = useState<ConnectionDrag | null>(null);
  const [pendingRelation, setPendingRelation] = useState<{ from: string; to: string } | null>(null);
  const [selectedRelationIds, setSelectedRelationIds] = useState<string[]>([]);
  const [editingRelation, setEditingRelation] = useState<RelationEdgeDto | null>(null);
  const [viewportVersion, setViewportVersion] = useState(0);

  useEffect(() => {
    sourceTableIdRef.current = sourceTableId;
  }, [sourceTableId]);

  useEffect(() => {
    targetTableIdRef.current = targetTableId;
  }, [targetTableId]);

  const sourceTableQuery = useQuery({
    queryKey: ['table', sourceTableId],
    queryFn: ({ signal }) => getTableDetail(sourceTableId!, signal),
    enabled: isTableNodeId(sourceTableId),
    staleTime: 60_000,
  });

  const targetTableQuery = useQuery({
    queryKey: ['table', targetTableId],
    queryFn: ({ signal }) => getTableDetail(targetTableId!, signal),
    enabled: isTableNodeId(targetTableId),
    staleTime: 60_000,
  });

  const selectedRelationsQuery = useQuery({
    queryKey: ['relations', selectedRelationIds],
    queryFn: ({ signal }) => Promise.all(selectedRelationIds.map((id) => getRelation(id, signal))),
    enabled: selectedRelationIds.length > 0,
    staleTime: 0,
  });

  useEffect(() => {
    const relations = selectedRelationsQuery.data;
    if (relations?.length === 1 && relations[0].type !== 'foreign_key') {
      setEditingRelation(relations[0]);
    }
  }, [selectedRelationsQuery.data]);

  const resetRelationComposer = useCallback(() => {
    setTargetTableId(null);
    setConnectionDrag(null);
    setPendingRelation(null);
  }, []);

  const clearOverlayState = useCallback(() => {
    sourceTableIdRef.current = null;
    targetTableIdRef.current = null;
    setSourceTableId(null);
    resetRelationComposer();
  }, [resetRelationComposer]);

  // Initialize / update graph when data changes
  useEffect(() => {
    if (!graphData || !sigmaHostRef.current) return;

    // Clean up previous sigma instance
    if (sigmaRef.current) {
      sigmaRef.current.kill();
      sigmaRef.current = null;
    }

    // Stop previous FA2 layout
    if (fa2Ref.current) {
      fa2Ref.current.stop();
      fa2Ref.current = null;
    }

    // Build graphology graph from API data
    const graph = adaptGraph(graphData);
    graphRef.current = graph;
    const grouped = graph.getAttribute('layout') === 'grouped';

    // Create sigma renderer
    const sigma = new Sigma(graph, sigmaHostRef.current, {
      enableEdgeEvents: true,
      renderEdgeLabels: false,
      defaultEdgeType: 'arrow',
      hideEdgesOnMove: false,
      hideLabelsOnMove: true,
      minEdgeThickness: 0.8,
      // 这三个阈值是给几百个点防标签互相打架用的。小图（按场景筛出来的十来张表）
      // 不存在这个问题，照搬只会得到一屏没有名字的圆点——点变少了反而更难看
      // 这三个阈值是给几百个点防标签互相打架用的；小图（按场景筛出来的十来张表）
      // 没这个问题，放开能让每张表都带上名字
      labelDensity: graph.order <= SMALL_GRAPH ? 1.5 : grouped ? 0.35 : 0.6,
      labelGridCellSize: graph.order <= SMALL_GRAPH ? 60 : grouped ? 130 : graph.order > 500 ? 180 : 120,
      labelRenderedSizeThreshold: graph.order <= SMALL_GRAPH ? 1 : grouped ? 4 : graph.order > 500 ? 7 : 5,
      labelFont: 'Inter, -apple-system, sans-serif',
      labelSize: 12,
      labelWeight: '600',
      labelColor: { color: getGraphTheme().label },
      minCameraRatio: 0.01,
      maxCameraRatio: 10,
      zIndex: true,
      nodeReducer: (_node, data) => {
        const ratio = cameraRatioRef.current;
        const emphasized = Boolean(data.emphasized);
        const relationCount = Number(data.relationCount ?? 0);
        const overviewIsolated = graph.order > 500 && ratio >= 0.9 && relationCount === 0;
        const hideOverviewLabel = graph.order > SMALL_GRAPH && ratio >= 0.9 &&
          (relationCount === 0 || (graph.order > 500 && relationCount < 5)) && !emphasized;
        const hideGroupedLabel = grouped && graph.order > 500 && ratio >= 0.06 &&
          graph.degree(_node) <= 1 && !emphasized;

        return {
          ...data,
          size: grouped && graph.order > 500
            ? Math.min(Number(data.size ?? 2), graph.degree(_node) === 0 ? 3 : 5)
            : overviewIsolated ? Math.min(Number(data.size ?? 2), 3) : data.size,
          label: hideOverviewLabel || hideGroupedLabel || _node === sourceTableIdRef.current ? null : data.label,
          forceLabel: graph.order <= SMALL_GRAPH || emphasized,
          zIndex: emphasized ? 2 : 0,
        };
      },
      edgeReducer: (_edge, data) => {
        const ratio = cameraRatioRef.current;
        const emphasized = Boolean(data.emphasized);

        if (emphasized) {
          return { ...data, hidden: false, size: 3, zIndex: 2 };
        }
        // 大图上把线宽夹窄是为了不糊成一片；小图上要让 2 / 1.5 / 1 的差别真的显出来——
        // 它是颜色之外的第二条区分通道，只靠颜色的图对色觉障碍的人不可读
        const raw = Number(data.size ?? 1.2);
        const normalSize = graph.order <= SMALL_GRAPH
          ? Math.max(1.2, raw)
          : Math.max(1.2, Math.min(raw, 1.6));
        if (graph.order > 500 && ratio >= 0.9) {
          return { ...data, hidden: false, color: getGraphTheme().overviewEdge, size: 0.8, zIndex: 0 };
        }
        if (ratio >= 1.8) {
          return { ...data, hidden: false, color: getGraphTheme().mutedEdge, size: 1, zIndex: 0 };
        }
        const theme = getGraphTheme();
        const edgeColor = theme.edges[String(data.relationType ?? '') as keyof typeof theme.edges] ?? theme.mutedEdge;
        return { ...data, hidden: false, color: edgeColor, size: normalSize, zIndex: 0 };
      },
    });
    sigmaRef.current = sigma;
    cameraRatioRef.current = sigma.getCamera().ratio;

    // Sigma draws labels and edges on canvas, so CSS theme overrides cannot reach them.
    const themeObserver = new MutationObserver(() => {
      sigma.setSettings({ labelColor: { color: getGraphTheme().label } });
    });
    themeObserver.observe(document.documentElement, { attributes: true, attributeFilter: ['data-theme'] });

    // Sparse schema graphs are dominated by isolated tables. Running a global
    // force layout would push those tables far away and make Sigma shrink the
    // related component into a dot. Keep the deterministic ring layout in that
    // case; use ForceAtlas2 only when most nodes actually participate in edges.
    let fa2: FA2Layout | null = null;
    let stopTimer: ReturnType<typeof setTimeout> | null = null;
    if (shouldRunForceLayout(graph)) {
      fa2 = new FA2Layout(graph, {
        settings: getLayoutSettings(graph.order),
      });
      fa2Ref.current = fa2;
      fa2.start();
      setLayoutRunning(true);
      stopTimer = setTimeout(() => {
        fa2?.stop();
        setLayoutRunning(false);
        // 布局停了才 fit：跑的过程中点一直在动，中途对焦会看到镜头来回拉
        fitToNodes(sigma, graph);
      }, getLayoutDuration(graph.order));
    } else {
      fa2Ref.current = null;
      setLayoutRunning(false);
      fitToNodes(sigma, graph);
    }

    // Event handlers
    const onClickNode = ({ node }: { node: string }) => {
      selectNode(node);
      setSelectedRelationIds([]);
      setEditingRelation(null);
      setPendingRelation(null);
      setConnectionDrag(null);

      if (!isTableNodeId(node)) {
        clearOverlayState();
        return;
      }

      sourceTableIdRef.current = node;
      const displayPosition = sigma.getNodeDisplayData(node);
      if (displayPosition) {
        const nodeViewportPosition = sigma.framedGraphToViewport(displayPosition);
        const nodeGraphPosition = sigma.viewportToFramedGraph(nodeViewportPosition);
        sigma.getCamera().animate(
          { x: nodeGraphPosition.x, y: nodeGraphPosition.y },
          { duration: 220 },
          () => sigma.refresh(),
        );
      }
      setSourceTableId(node);
      setTargetTableId(null);
    };

    const onClickEdge = ({ edge, preventSigmaDefault }: { edge: string; preventSigmaDefault: () => void }) => {
      preventSigmaDefault();
      selectEdge(edge);
      clearOverlayState();
      const relationIds = graph.getEdgeAttribute(edge, 'relationIds') as string[] | undefined;
      setSelectedRelationIds(relationIds ?? []);
      setEditingRelation(null);
    };

    const onEnterEdge = ({ edge }: { edge: string }) => {
      containerRef.current?.style.setProperty('cursor', 'pointer');
      graph.setEdgeAttribute(edge, 'color', '#4f46e5');
      graph.setEdgeAttribute(edge, 'size', 5);
    };

    const onLeaveEdge = ({ edge }: { edge: string }) => {
      containerRef.current?.style.removeProperty('cursor');
      const attrs = graph.getEdgeAttributes(edge);
      graph.setEdgeAttribute(edge, 'color', attrs.originalColor);
      graph.setEdgeAttribute(edge, 'size', attrs.originalSize);
    };

    const onEnterNode = ({ node }: { node: string }) => {
      hoverNode(node);
      if (graphRef.current) {
        highlightNodeNeighbors(graphRef.current, node);
      }
    };

    const onLeaveNode = () => {
      hoverNode(null);
      if (graphRef.current) {
        resetHighlight(graphRef.current);
      }
    };

    const onZoom = () => {
      if (sigmaRef.current) {
        cameraRatioRef.current = sigmaRef.current.getCamera().ratio;
        setZoom(cameraRatioRef.current);
        sigmaRef.current.refresh();
        setViewportVersion((prev) => prev + 1);
      }
    };

    const onAfterRender = () => {
      if (renderFrameRef.current != null) {
        return;
      }
      renderFrameRef.current = window.requestAnimationFrame(() => {
        renderFrameRef.current = null;
        setViewportVersion((prev) => prev + 1);
      });
    };

    sigma.on('clickNode', onClickNode);
    sigma.on('clickEdge', onClickEdge);
    sigma.on('enterEdge', onEnterEdge);
    sigma.on('leaveEdge', onLeaveEdge);
    sigma.on('enterNode', onEnterNode);
    sigma.on('leaveNode', onLeaveNode);
    sigma.on('afterRender', onAfterRender);
    sigma.getCamera().on('updated', onZoom);

    // Click on background to deselect
    const onClickStage = () => {
      selectNode(null);
      clearOverlayState();
      setSelectedRelationIds([]);
      setEditingRelation(null);
    };
    sigma.on('clickStage', onClickStage);

    return () => {
      themeObserver.disconnect();
      if (stopTimer) {
        clearTimeout(stopTimer);
      }
      fa2?.stop();
      setLayoutRunning(false);
      sigma.off('clickNode', onClickNode);
      sigma.off('clickEdge', onClickEdge);
      sigma.off('enterEdge', onEnterEdge);
      sigma.off('leaveEdge', onLeaveEdge);
      sigma.off('enterNode', onEnterNode);
      sigma.off('leaveNode', onLeaveNode);
      sigma.off('afterRender', onAfterRender);
      sigma.getCamera().off('updated', onZoom);
      sigma.off('clickStage', onClickStage);
      if (renderFrameRef.current != null) {
        window.cancelAnimationFrame(renderFrameRef.current);
        renderFrameRef.current = null;
      }
      sigma.kill();
      sigmaRef.current = null;
      graphRef.current = null;
      fa2Ref.current = null;
    };
  }, [graphData, clearOverlayState, selectEdge, selectNode, hoverNode, setLayoutRunning, setZoom]);

  // Handle node selection highlighting
  useEffect(() => {
    const graph = graphRef.current;
    if (!graph) return;

    if (selectedNodeId) {
      highlightNodeNeighbors(graph, selectedNodeId);
    } else {
      resetHighlight(graph);
    }
  }, [selectedNodeId]);

  // Handle keyboard events
  const handleKeyDown = useCallback(
    (e: React.KeyboardEvent) => {
      if (e.key === 'Escape') {
        selectNode(null);
        clearOverlayState();
      }
    },
    [clearOverlayState, selectNode],
  );

  const getNodeDisplayPosition = useCallback((nodeId: string | null): DisplayPosition | null => {
    if (!nodeId || !sigmaRef.current) {
      return null;
    }
    const displayData = sigmaRef.current.getNodeDisplayData(nodeId);
    if (!displayData) {
      return null;
    }
    const viewport = sigmaRef.current.framedGraphToViewport(displayData);
    return {
      x: viewport.x,
      y: viewport.y,
      size: sigmaRef.current.scaleSize(displayData.size),
    };
  }, []);

  // viewportVersion deliberately makes these ref-backed display positions refresh.
  void viewportVersion;
  const sourcePosition = getNodeDisplayPosition(sourceTableId);
  const targetPosition = getNodeDisplayPosition(targetTableId);

  const sourceFields = useMemo(
    () => buildRadialFields(
      sourceTableId ?? '',
      sourceTableQuery.data?.columns ?? [],
      sourcePosition,
    ),
    [sourcePosition, sourceTableId, sourceTableQuery.data?.columns],
  );

  const targetFields = useMemo(
    () => buildRadialFields(
      targetTableId ?? '',
      targetTableQuery.data?.columns ?? [],
      targetPosition,
    ),
    [targetPosition, targetTableId, targetTableQuery.data?.columns],
  );

  const visibleSourceFields = useMemo(
    () => connectionDrag
      ? sourceFields.filter((field) => field.id === connectionDrag.from)
      : sourceFields,
    [connectionDrag, sourceFields],
  );

  const findTableAtPoint = useCallback((point: Point): string | null => {
    const sigma = sigmaRef.current;
    const graph = graphRef.current;
    if (!sigma || !graph) {
      return null;
    }

    let matchedNode: string | null = null;
    let matchedDistance = Number.POSITIVE_INFINITY;
    graph.forEachNode((node) => {
      if (node === sourceTableIdRef.current || !isTableNodeId(node)) {
        return;
      }
      const data = sigma.getNodeDisplayData(node);
      if (!data) {
        return;
      }
      const viewport = sigma.framedGraphToViewport(data);
      const radius = Math.max(16, sigma.scaleSize(data.size) + 10);
      const distance = Math.hypot(point.x - viewport.x, point.y - viewport.y);
      if (distance <= radius && distance < matchedDistance) {
        matchedNode = node;
        matchedDistance = distance;
      }
    });
    return matchedNode;
  }, []);

  const startConnectionDrag = useCallback((
    event: React.PointerEvent<HTMLButtonElement>,
    columnId: string,
  ) => {
    event.preventDefault();
    event.stopPropagation();
    const container = containerRef.current;
    if (!container) {
      return;
    }
    const containerRect = container.getBoundingClientRect();
    const fieldRect = event.currentTarget.getBoundingClientRect();
    const start = {
      x: fieldRect.left + fieldRect.width / 2 - containerRect.left,
      y: fieldRect.top + fieldRect.height / 2 - containerRect.top,
    };
    setTargetTableId(null);
    setPendingRelation(null);
    setConnectionDrag({ from: columnId, start, current: start });
  }, []);

  const connectionSource = connectionDrag?.from;
  useEffect(() => {
    if (!connectionSource) {
      return;
    }

    const handlePointerMove = (event: PointerEvent) => {
      const container = containerRef.current;
      if (!container) {
        return;
      }
      const rect = container.getBoundingClientRect();
      const current = {
        x: event.clientX - rect.left,
        y: event.clientY - rect.top,
      };
      setConnectionDrag((drag) => drag ? { ...drag, current } : null);

      const targetNode = findTableAtPoint(current);
      if (targetNode && targetNode !== targetTableIdRef.current) {
        setTargetTableId(targetNode);
      }
    };

    const handlePointerUp = (event: PointerEvent) => {
      const element = document.elementFromPoint(event.clientX, event.clientY);
      const target = element?.closest<HTMLElement>('[data-relation-column-id]');
      const targetColumnId = target?.dataset.relationColumnId;
      const targetOwnerId = target?.dataset.relationTableId;

      if (
        targetColumnId &&
        targetOwnerId &&
        targetOwnerId === targetTableIdRef.current &&
        targetColumnId !== connectionSource
      ) {
        setPendingRelation({ from: connectionSource, to: targetColumnId });
      }
      setConnectionDrag(null);
    };

    window.addEventListener('pointermove', handlePointerMove);
    window.addEventListener('pointerup', handlePointerUp, { once: true });
    return () => {
      window.removeEventListener('pointermove', handlePointerMove);
      window.removeEventListener('pointerup', handlePointerUp);
    };
  }, [connectionSource, findTableAtPoint]);

  const handleRelationSaved = useCallback((result: MutationResultDto) => {
    onMutationSuccess(result);
    queryClient.invalidateQueries({ queryKey: ['graph'] });
    if (sourceTableIdRef.current) {
      queryClient.invalidateQueries({ queryKey: ['table', sourceTableIdRef.current] });
    }
    if (targetTableIdRef.current) {
      queryClient.invalidateQueries({ queryKey: ['table', targetTableIdRef.current] });
    }
    resetRelationComposer();
  }, [queryClient, resetRelationComposer]);

  const handleEditedRelationSaved = useCallback((result: MutationResultDto) => {
    onMutationSuccess(result);
    setEditingRelation(null);
    setSelectedRelationIds([]);
    queryClient.invalidateQueries({ queryKey: ['graph'] });
    queryClient.invalidateQueries({ queryKey: ['table'] });
  }, [queryClient]);

  const relationJoinExpression = pendingRelation
    ? `${endpointQualifiedName(pendingRelation.from)} = ${endpointQualifiedName(pendingRelation.to)}`
    : '';

  return (
    <div
      className="graph-canvas-container"
      ref={containerRef}
      onKeyDown={handleKeyDown}
      tabIndex={0}
      style={{ width: '100%', height: '100%', position: 'relative' }}
    >
      <div ref={sigmaHostRef} className="graph-canvas-surface" />
      {isLoading && (
        <div className="graph-canvas-loading">
          <div className="spinner" />
          <span>正在加载图谱…</span>
        </div>
      )}
      {!isLoading && graphData && graphData.nodes.length > 500 && (
        <div className="graph-canvas-hint">
          关联表优先 · 无关系表单独排列 · 悬停或放大查看表名
        </div>
      )}
      {!isLoading && !graphData && (
        <div className="graph-canvas-empty">
          <span>暂无图谱数据</span>
        </div>
      )}
      <svg className="graph-relation-drawing-layer" aria-hidden="true">
        {sourcePosition && visibleSourceFields.map((field) => (
          <line
            key={`source-spoke-${field.id}`}
            className="graph-field-spoke graph-field-spoke-source"
            x1={sourcePosition.x}
            y1={sourcePosition.y}
            x2={field.center.x}
            y2={field.center.y}
          />
        ))}
        {targetPosition && targetFields.map((field) => (
          <line
            key={`target-spoke-${field.id}`}
            className="graph-field-spoke graph-field-spoke-target"
            x1={targetPosition.x}
            y1={targetPosition.y}
            x2={field.center.x}
            y2={field.center.y}
          />
        ))}
        {connectionDrag && (
          <line
            className="graph-relation-draft-line"
            x1={connectionDrag.start.x}
            y1={connectionDrag.start.y}
            x2={connectionDrag.current.x}
            y2={connectionDrag.current.y}
          />
        )}
      </svg>
      {sourceTableId && visibleSourceFields.map((field) => (
        <button
          key={field.id}
          className={`graph-radial-field graph-radial-field-source ${connectionDrag?.from === field.id ? 'is-dragging' : ''}`}
          style={{ left: field.center.x, top: field.center.y }}
          type="button"
          data-relation-column-id={field.id}
          data-relation-table-id={sourceTableId}
          onPointerDown={(event) => startConnectionDrag(event, field.id)}
          title={`把 ${field.name} 拖到另一张表的字段上建立关系`}
        >
          <span className="graph-radial-field-port" />
          <span className="graph-radial-field-name">{field.name}</span>
          {field.primaryKey && <span className="graph-field-chip-badge">PK</span>}
        </button>
      ))}
      {targetTableId && targetFields.map((field) => (
        <button
          key={field.id}
          className="graph-radial-field graph-radial-field-target"
          style={{ left: field.center.x, top: field.center.y }}
          type="button"
          data-relation-column-id={field.id}
          data-relation-table-id={targetTableId}
          title={`在 ${field.name} 上松开以建立关系`}
        >
          <span className="graph-radial-field-port" />
          <span className="graph-radial-field-name">{field.name}</span>
          {field.primaryKey && <span className="graph-field-chip-badge">PK</span>}
        </button>
      ))}
      {sourceTableId && sourcePosition && sourceTableQuery.data && (
        <div className="graph-radial-field-caption">
          拖动字段到另一张表建立关系
        </div>
      )}
      {(sourceTableQuery.isLoading || targetTableQuery.isLoading) && (
        <div className="graph-field-overlay-status">
          <div className="spinner" />
          <span>正在加载字段…</span>
        </div>
      )}
      {pendingRelation && (
        <div className="graph-relation-composer">
          <RelationEditor
            key={`${pendingRelation.from}->${pendingRelation.to}`}
            mode="create"
            title="新建字段关系"
            revision={graphData?.revision ?? 0}
            defaultType="join_observed"
            defaultFrom={pendingRelation.from}
            defaultTo={pendingRelation.to}
            defaultJoinExpression={relationJoinExpression}
            lockEndpoints
            onSaved={handleRelationSaved}
            onCancel={() => setPendingRelation(null)}
          />
        </div>
      )}
      {selectedRelationIds.length > 0 && !editingRelation && (
        <div className="graph-relation-composer">
          <div className="graph-edge-editor">
            <div className="graph-edge-editor-header">
              <strong>
                {selectedRelationIds.length === 1 ? '编辑关系' : '这条连线上的关系'}
              </strong>
              <button
                type="button"
                className="graph-field-overlay-action"
                onClick={() => setSelectedRelationIds([])}
                title="关闭"
              >
                ×
              </button>
            </div>
            {selectedRelationsQuery.isLoading && (
              <div className="graph-edge-editor-status">
                <span className="spinner" /> 正在加载关系…
              </div>
            )}
            {selectedRelationsQuery.isError && (
              <div className="relation-editor-error">
                无法加载关系详情。
              </div>
            )}
            {selectedRelationsQuery.data?.map((relation) => (
              <button
                key={relation.id}
                type="button"
                className="graph-edge-relation-option"
                onClick={() => {
                  if (relation.type !== 'foreign_key') {
                    setEditingRelation(relation);
                  }
                }}
                disabled={relation.type === 'foreign_key'}
                title={relation.type === 'foreign_key'
                  ? '数据库声明的外键由导入维护，不可编辑'
                  : '编辑这条关系'}
              >
                <RelationTypeBadge type={relation.type} size="md" />
                <span className="graph-edge-relation-endpoints">
                  <code>{endpointQualifiedName(relation.from)}</code>
                  <span>→</span>
                  <code>{endpointQualifiedName(relation.to)}</code>
                </span>
              </button>
            ))}
          </div>
        </div>
      )}
      {editingRelation && (
        <div className="graph-relation-composer">
          <RelationEditor
            key={editingRelation.id}
            mode="edit"
            relation={editingRelation}
            revision={graphData?.revision ?? 0}
            onSaved={handleEditedRelationSaved}
            onCancel={() => setEditingRelation(null)}
          />
        </div>
      )}
    </div>
  );
}
