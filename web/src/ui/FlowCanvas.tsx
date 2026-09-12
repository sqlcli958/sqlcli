/**
 * 节点图画布：React Flow 的唯一包装层。
 *
 * 为什么引它而不是继续手写 SVG（CLAUDE.md「长什么样还是怎么动」）：拖拽平移、滚轮缩放、
 * 小地图、fit view、节点上的端口和边的路由——全是「怎么动」，手写版做了一轮，
 * 结果是十一张各自为政的小图。sigma 不合适：它把节点画成点，血缘要的是
 * 「表是容器、列是容器里的行、边从行画到行」，这是 DataHub / OpenMetadata / Atlan 共同的形状。
 *
 * feature 只组装节点和边，不直接 import `@xyflow/react`（eslint 拦着）。
 * 布局用 dagre 从左到右分层，也只在这里调。
 */
import { useEffect, type ReactNode } from 'react';
import {
  Background,
  Controls,
  MiniMap,
  ReactFlow,
  ReactFlowProvider,
  useReactFlow,
  type Edge,
  type Node,
  type NodeTypes,
} from '@xyflow/react';
import dagre from '@dagrejs/dagre';
import '@xyflow/react/dist/style.css';

export { Handle, Panel, Position } from '@xyflow/react';
export type { Edge, Node, NodeProps } from '@xyflow/react';

/** 从左到右分层。节点要先知道自己的宽高（`width` / `height`），布局按它们排。 */
export function layoutLeftToRight<N extends Node, E extends Edge>(
  nodes: N[],
  edges: E[],
  options: { nodeSep?: number; rankSep?: number } = {},
): N[] {
  const g = new dagre.graphlib.Graph();
  g.setDefaultEdgeLabel(() => ({}));
  g.setGraph({ rankdir: 'LR', nodesep: options.nodeSep ?? 28, ranksep: options.rankSep ?? 90 });
  for (const node of nodes) {
    g.setNode(node.id, { width: node.width ?? 200, height: node.height ?? 60 });
  }
  for (const edge of edges) {
    g.setEdge(edge.source, edge.target);
  }
  dagre.layout(g);
  return nodes.map((node) => {
    const placed = g.node(node.id);
    // dagre 给的是中心点，React Flow 要左上角
    return {
      ...node,
      position: { x: placed.x - (node.width ?? 200) / 2, y: placed.y - (node.height ?? 60) / 2 },
    };
  });
}

interface FlowCanvasProps {
  nodes: Node[];
  edges: Edge[];
  nodeTypes: NodeTypes;
  /** 变了就把视野移到这个节点上（左栏索引点一下 → 画布定位过去） */
  focusNodeId?: string | null;
  /** 画布空白处点一下 */
  onPaneClick?: () => void;
  children?: ReactNode;
}

export function FlowCanvas(props: FlowCanvasProps) {
  return (
    <ReactFlowProvider>
      <Inner {...props} />
    </ReactFlowProvider>
  );
}

function Inner({ nodes, edges, nodeTypes, focusNodeId, onPaneClick, children }: FlowCanvasProps) {
  const flow = useReactFlow();
  useEffect(() => {
    if (!focusNodeId) return;
    void flow.fitView({ nodes: [{ id: focusNodeId }], duration: 300, maxZoom: 1.1, padding: 0.4 });
  }, [focusNodeId, flow]);

  return (
    <ReactFlow
      nodes={nodes}
      edges={edges}
      nodeTypes={nodeTypes}
      fitView
      fitViewOptions={{ padding: 0.15, maxZoom: 1 }}
      minZoom={0.15}
      nodesDraggable
      nodesConnectable={false}
      elementsSelectable={false}
      proOptions={{ hideAttribution: true }}
      onPaneClick={onPaneClick}
    >
      <Background gap={20} />
      <Controls showInteractive={false} />
      <MiniMap pannable zoomable />
      {children}
    </ReactFlow>
  );
}
