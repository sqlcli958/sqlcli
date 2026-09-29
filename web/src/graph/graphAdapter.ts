import Graph from 'graphology';
import forceAtlas2 from 'graphology-layout-forceatlas2';
import type { GraphViewDto } from '../types/api';
import { getSchemaColor, calculateNodeSize, nodeWeight, getRelationColor, getRelationLineStyle, NODE_SIZE } from './graphStyles';

/** Below this count, stable rings keep every table easy to locate. */
export const SMALL_GRAPH = 60;

/**
 * Keep small or dense graphs on stable rings; pack sparse graphs by component.
 */
function initializeNodePositions(graph: Graph): void {
  const connected = graph.nodes()
    .filter((node) => graph.degree(node) > 0)
    .sort((left, right) => graph.degree(right) - graph.degree(left) || left.localeCompare(right));
  const isolated = graph.nodes()
    .filter((node) => graph.degree(node) === 0)
    .sort();

  if (graph.order > SMALL_GRAPH) {
    const components = getConnectedComponents(graph);
    const relatedCount = components.reduce((count, component) => count + component.length, 0);
    // ponytail: cap synchronous per-component layout at 500 related nodes; denser graphs use the existing worker.
    if (relatedCount <= 500 &&
        (graph.order > 500 || relatedCount / graph.order < 0.5)) {
      initializeGroupedPositions(graph, components, isolated);
      graph.setAttribute('layout', 'grouped');
      return;
    }
  }

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

function getConnectedComponents(graph: Graph): string[][] {
  const adjacency = new Map(graph.nodes().map((node) => [node, new Set<string>()]));
  graph.forEachEdge((_edge, _attrs, source, target) => {
    adjacency.get(source)?.add(target);
    adjacency.get(target)?.add(source);
  });

  const remaining = new Set(
    [...adjacency.keys()].filter((node) => adjacency.get(node)!.size > 0).sort(),
  );
  const components: string[][] = [];
  while (remaining.size > 0) {
    const seed = remaining.values().next().value as string;
    remaining.delete(seed);
    const component = [seed];
    for (let index = 0; index < component.length; index++) {
      for (const neighbor of adjacency.get(component[index]!) ?? []) {
        if (!remaining.delete(neighbor)) continue;
        component.push(neighbor);
      }
    }
    components.push(component.sort());
  }
  return components.sort((left, right) => right.length - left.length || left[0]!.localeCompare(right[0]!));
}

interface LayoutPoint {
  node: string;
  x: number;
  y: number;
  targetX: number;
  targetY: number;
}

function distributeIsolatedNodes(
  graph: Graph,
  isolated: string[],
  connectedWidth: number,
  connectedHeight: number,
): void {
  if (isolated.length === 0) return;

  // Vogel / golden-angle seeding distributes points in every direction without
  // creating the rigid concentric rings of a classic radial layout.
  const GOLDEN_ANGLE = Math.PI * (3 - Math.sqrt(5));
  const MIN_SPACING = 82;
  const CONNECTED_GAP = 180;
  const hasConnectedRegion = connectedWidth > 0 && connectedHeight > 0;
  const innerRadiusX = Math.max(180, connectedWidth / 2 + CONNECTED_GAP);
  const innerRadiusY = Math.max(180, connectedHeight / 2 + CONNECTED_GAP);
  const targetAreaPerNode = MIN_SPACING * MIN_SPACING * 1.22;
  const innerEllipseArea = Math.PI * innerRadiusX * innerRadiusY;

  const points: LayoutPoint[] = isolated.map((node, index) => {
    const angle = (index + 0.5) * GOLDEN_ANGLE - Math.PI / 2;
    let targetX: number;
    let targetY: number;

    if (hasConnectedRegion) {
      // Fill an annulus around the connected graph by area, not by fixed rings.
      // As nodes are added, the ellipse expands continuously and keeps density
      // approximately constant in every direction.
      const scale = Math.sqrt(
        1 + ((index + 0.75) * targetAreaPerNode) / innerEllipseArea,
      );
      targetX = Math.cos(angle) * innerRadiusX * scale;
      targetY = Math.sin(angle) * innerRadiusY * scale;
    } else {
      // With no related tables at all, use a regular sunflower disk from center.
      const radius = (MIN_SPACING / Math.sqrt(Math.PI)) * Math.sqrt(index + 0.75);
      targetX = Math.cos(angle) * radius;
      targetY = Math.sin(angle) * radius;
    }

    return { node, x: targetX, y: targetY, targetX, targetY };
  });

  // Light deterministic relaxation: collision repulsion removes local overlaps,
  // while a weak spring back to each golden-angle target preserves the overall
  // 360-degree distribution. A spatial hash avoids O(n²) work on large schemas.
  const iterations = 14;
  const cellSize = MIN_SPACING;
  for (let iteration = 0; iteration < iterations; iteration++) {
    const grid = new Map<string, number[]>();
    points.forEach((point, pointIndex) => {
      const cellX = Math.floor(point.x / cellSize);
      const cellY = Math.floor(point.y / cellSize);
      const key = `${cellX}:${cellY}`;
      const bucket = grid.get(key);
      if (bucket) bucket.push(pointIndex);
      else grid.set(key, [pointIndex]);
    });

    const deltaX = new Array<number>(points.length).fill(0);
    const deltaY = new Array<number>(points.length).fill(0);
    const springStrength = 0.08;

    points.forEach((point, pointIndex) => {
      deltaX[pointIndex] += (point.targetX - point.x) * springStrength;
      deltaY[pointIndex] += (point.targetY - point.y) * springStrength;

      const cellX = Math.floor(point.x / cellSize);
      const cellY = Math.floor(point.y / cellSize);
      for (let xOffset = -1; xOffset <= 1; xOffset++) {
        for (let yOffset = -1; yOffset <= 1; yOffset++) {
          const neighbors = grid.get(`${cellX + xOffset}:${cellY + yOffset}`);
          if (!neighbors) continue;

          for (const neighborIndex of neighbors) {
            if (neighborIndex <= pointIndex) continue;
            const neighbor = points[neighborIndex]!;
            let dx = neighbor.x - point.x;
            let dy = neighbor.y - point.y;
            let distance = Math.hypot(dx, dy);
            if (distance >= MIN_SPACING) continue;

            if (distance < 0.001) {
              const fallbackAngle = ((pointIndex + neighborIndex + 1) * GOLDEN_ANGLE) % (Math.PI * 2);
              dx = Math.cos(fallbackAngle);
              dy = Math.sin(fallbackAngle);
              distance = 1;
            }

            const push = (MIN_SPACING - distance) * 0.38;
            const unitX = dx / distance;
            const unitY = dy / distance;
            deltaX[pointIndex] -= unitX * push;
            deltaY[pointIndex] -= unitY * push;
            deltaX[neighborIndex] += unitX * push;
            deltaY[neighborIndex] += unitY * push;
          }
        }
      }
    });

    points.forEach((point, pointIndex) => {
      point.x += deltaX[pointIndex]! * 0.72;
      point.y += deltaY[pointIndex]! * 0.72;

      if (!hasConnectedRegion) return;

      // Never let an isolated table drift into the relationship graph's core.
      const normalized =
        (point.x * point.x) / (innerRadiusX * innerRadiusX) +
        (point.y * point.y) / (innerRadiusY * innerRadiusY);
      const minScale = 1.035;
      if (normalized < minScale * minScale) {
        const projection = minScale / Math.sqrt(Math.max(normalized, 0.000001));
        point.x *= projection;
        point.y *= projection;
      }
    });
  }

  points.forEach((point) => {
    graph.mergeNodeAttributes(point.node, { x: point.x, y: point.y });
  });
}

function initializeGroupedPositions(graph: Graph, components: string[][], isolated: string[]): void {
  const componentPadding = 110;
  const groups = components.map((nodes) => {
    const component = new Graph({ type: 'directed', multi: true });
    const nodeSet = new Set(nodes);
    const initialRadius = Math.max(55, Math.sqrt(nodes.length) * 45);

    nodes.forEach((node, nodeIndex) => {
      const angle = -Math.PI / 2 + (Math.PI * 2 * nodeIndex) / nodes.length;
      component.addNode(node, {
        ...graph.getNodeAttributes(node),
        x: Math.cos(angle) * initialRadius,
        y: Math.sin(angle) * initialRadius,
      });
    });
    graph.forEachEdge((edge, attrs, source, target) => {
      if (nodeSet.has(source) && nodeSet.has(target)) {
        component.addDirectedEdgeWithKey(edge, source, target, { ...attrs });
      }
    });

    if (nodes.length > 3) {
      forceAtlas2.assign(component, {
        iterations: 140,
        settings: {
          adjustSizes: true,
          edgeWeightInfluence: 0.5,
          gravity: 0.2,
          scalingRatio: 18,
          slowDown: 2,
        },
      });
    }

    let minX = Infinity;
    let maxX = -Infinity;
    let minY = Infinity;
    let maxY = -Infinity;
    component.forEachNode((_node, attrs) => {
      minX = Math.min(minX, Number(attrs.x));
      maxX = Math.max(maxX, Number(attrs.x));
      minY = Math.min(minY, Number(attrs.y));
      maxY = Math.max(maxY, Number(attrs.y));
    });

    return {
      component,
      minX,
      minY,
      width: maxX - minX + componentPadding,
      height: maxY - minY + componentPadding,
    };
  });

  // Layout each connected component independently, then pack measured bounds.
  // Mature graph engines use the same two-stage idea for disconnected graphs:
  // preserve each component's internal shape first, then allocate global space.
  const targetWidth = Math.max(
    0,
    ...groups.map((group) => group.width),
    Math.sqrt(groups.reduce((area, group) => area + group.width * group.height, 0)) * 1.3,
  );
  let x = 0;
  let y = 0;
  let rowHeight = 0;
  let connectedWidth = 0;
  for (const group of groups) {
    if (x > 0 && x + group.width > targetWidth) {
      x = 0;
      y += rowHeight;
      rowHeight = 0;
    }
    group.component.forEachNode((node, attrs) => {
      graph.mergeNodeAttributes(node, {
        x: x + Number(attrs.x) - group.minX + componentPadding / 2,
        y: y + Number(attrs.y) - group.minY + componentPadding / 2,
      });
    });
    x += group.width;
    connectedWidth = Math.max(connectedWidth, x);
    rowHeight = Math.max(rowHeight, group.height);
  }
  const connectedHeight = groups.length > 0 ? y + rowHeight : 0;

  // Center the relationship structure so it remains the visual anchor instead
  // of inheriting the top-left origin of the packing pass.
  if (connectedWidth > 0 && connectedHeight > 0) {
    graph.forEachNode((node, attrs) => {
      if (graph.degree(node) === 0) return;
      graph.mergeNodeAttributes(node, {
        x: Number(attrs.x) - connectedWidth / 2,
        y: Number(attrs.y) - connectedHeight / 2,
      });
    });
  }

  distributeIsolatedNodes(graph, isolated, connectedWidth, connectedHeight);

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
