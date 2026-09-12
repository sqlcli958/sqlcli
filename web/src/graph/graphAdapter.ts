import Graph from 'graphology';
import type { GraphViewDto } from '../types/api';
import { getSchemaColor, calculateNodeSize, nodeWeight, getRelationColor, getRelationLineStyle, NODE_SIZE } from './graphStyles';

/**
 * Give related tables a stable, readable starting point. High-degree tables
 * are alternated across the circle instead of being placed next to each other,
 * while isolated tables are kept on separate outer rings.
 */
function initializeNodePositions(graph: Graph): void {
  const connected = graph.nodes()
    .filter((node) => graph.degree(node) > 0)
    .sort((left, right) => graph.degree(right) - graph.degree(left) || left.localeCompare(right));
  const isolated = graph.nodes()
    .filter((node) => graph.degree(node) === 0)
    .sort();

  const connectedRadius = Math.max(420, connected.length * 46);
  const half = Math.ceil(connected.length / 2);
  connected.forEach((node, index) => {
    const slot = index % 2 === 0
      ? index / 2
      : half + (index - 1) / 2;
    const angle = -Math.PI / 2 + (Math.PI * 2 * slot) / Math.max(connected.length, 1);
    graph.mergeNodeAttributes(node, {
      x: Math.cos(angle) * connectedRadius,
      y: Math.sin(angle) * connectedRadius,
    });
  });

  // 孤立表按环排布，每环容量由周长算出而不是写死 80——写死时内环挤成一圈相连的珠子、
  // 外环又空着。ISOLATED_SPACING 是相邻两点的最小弧长（图坐标）。
  const ISOLATED_SPACING = 150;
  const RING_GAP = 190;
  let placed = 0;
  let ringRadius = connectedRadius + 460;
  while (placed < isolated.length) {
    const capacity = Math.max(24, Math.floor((Math.PI * 2 * ringRadius) / ISOLATED_SPACING));
    const count = Math.min(capacity, isolated.length - placed);
    for (let i = 0; i < count; i++) {
      const angle = -Math.PI / 2 + (Math.PI * 2 * i) / count;
      graph.mergeNodeAttributes(isolated[placed + i], {
        x: Math.cos(angle) * ringRadius,
        y: Math.sin(angle) * ringRadius,
      });
    }
    placed += count;
    ringRadius += RING_GAP;
  }
}

/** Convert API graph DTO to Graphology graph */
export function adaptGraph(dto: GraphViewDto): Graph {
  const graph = new Graph({ type: 'directed', multi: true });

  // 归一化基准取当前视图里的最大权重，同一张图内部才可比。
  const maxWeight = dto.nodes.reduce(
    (max, node) => Math.max(max, nodeWeight(node.columnCount ?? 0, node.relationCount)),
    0,
  );

  // Add nodes
  dto.nodes.forEach((node) => {
    const relationCount = node.relationCount;
    const columnCount = node.columnCount ?? 0;
    const size = calculateNodeSize(nodeWeight(columnCount, relationCount), maxWeight);
    const color = getSchemaColor(node.schema);

    graph.addNode(node.id, {
      label: node.label,
      schema: node.schema,
      kind: node.kind,
      description: node.description,
      relationCount,
      columnCount,
      validationSeverity: node.validationSeverity,
      x: 0,
      y: 0,
      size,
      color,
      originalColor: color,
      originalSize: size,
      emphasized: false,
    });
  });

  // Add edges
  dto.edges.forEach((edge) => {
    const edgeType = edge.type as import('../types/api').RelationType;
    const lineStyle = getRelationLineStyle(edgeType);
    const color = getRelationColor(edgeType);
    const renderSize = Math.max(1.2, lineStyle.width * 0.8);

    // Use the id from the DTO directly (already a unique key like "edge:source->target:type")
    const edgeKey = edge.id;

    try {
      graph.addDirectedEdgeWithKey(edgeKey, edge.source, edge.target, {
        id: edgeKey,
        label: edge.type,
        type: 'arrow',
        relationType: edge.type,
        fieldPairs: edge.fieldPairs,
        confidence: edge.confidence,
        verified: edge.verified,
        relationIds: edge.relationIds,
        color,
        size: renderSize,
        dash: lineStyle.dash,
        originalColor: color,
        originalSize: renderSize,
        emphasized: false,
      });
    } catch (e) {
      // Edge might already exist or nodes missing, skip silently
      console.warn('Failed to add edge:', edgeKey, e);
    }
  });

  initializeNodePositions(graph);

  return graph;
}

/** Highlight node and its neighbors */
export function highlightNodeNeighbors(graph: Graph, nodeId: string): void {
  // Reset all nodes to original color
  graph.forEachNode((_n, attrs) => {
    graph.setNodeAttribute(attrs.id || _n, 'color', attrs.originalColor);
    graph.setNodeAttribute(attrs.id || _n, 'size', attrs.originalSize);
    graph.setNodeAttribute(attrs.id || _n, 'emphasized', false);
  });

  // Reset all edges
  graph.forEachEdge((_e, attrs) => {
    graph.setEdgeAttribute(attrs.id || _e, 'color', attrs.originalColor);
    graph.setEdgeAttribute(attrs.id || _e, 'size', attrs.originalSize);
    graph.setEdgeAttribute(attrs.id || _e, 'emphasized', false);
  });

  // Highlight selected node
  if (graph.hasNode(nodeId)) {
    const nodeAttrs = graph.getNodeAttributes(nodeId);
    graph.setNodeAttribute(nodeId, 'color', '#ff6b6b');
    graph.setNodeAttribute(nodeId, 'size', (nodeAttrs.size as number) + NODE_SIZE.SELECTED_BONUS);
    graph.setNodeAttribute(nodeId, 'emphasized', true);

    // Highlight neighbors
    graph.forEachNeighbor(nodeId, (neighbor) => {
      const neighborAttrs = graph.getNodeAttributes(neighbor);
      graph.setNodeAttribute(neighbor, 'color', '#ffd93d');
      graph.setNodeAttribute(neighbor, 'size', (neighborAttrs.size as number) + NODE_SIZE.HOVER_BONUS);
      graph.setNodeAttribute(neighbor, 'emphasized', true);
    });

    // Highlight connected edges
    graph.forEachEdge(nodeId, (edge) => {
      graph.setEdgeAttribute(edge, 'color', '#ff6b6b');
      graph.setEdgeAttribute(edge, 'size', 3);
      graph.setEdgeAttribute(edge, 'emphasized', true);
    });
  }
}

/** Reset graph highlighting */
export function resetHighlight(graph: Graph): void {
  graph.forEachNode((node, attrs) => {
    graph.setNodeAttribute(node, 'color', attrs.originalColor);
    graph.setNodeAttribute(node, 'size', attrs.originalSize);
    graph.setNodeAttribute(node, 'emphasized', false);
  });

  graph.forEachEdge((edge, attrs) => {
    graph.setEdgeAttribute(edge, 'color', attrs.originalColor);
    graph.setEdgeAttribute(edge, 'size', attrs.originalSize);
    graph.setEdgeAttribute(edge, 'emphasized', false);
  });
}
