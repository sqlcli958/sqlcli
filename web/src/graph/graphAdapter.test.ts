import { expect, test } from 'vitest';
import type { GraphViewDto } from '../types/api';
import { adaptGraph } from './graphAdapter';

test('large sparse graphs pack relation groups by their bounds and isolate tables on a grid', () => {
  const related = Array.from({ length: 12 }, (_, index) => `table:large:${index}`);
  const pair = ['table:small:0', 'table:small:1'];
  const isolated = Array.from({ length: 510 }, (_, index) => `table:isolated:${String(index).padStart(3, '0')}`);
  const edges = [
    ...related.slice(1).map((target, index) => ({
      id: `edge:large:${index}`, source: related[0]!, target, type: 'foreign_key',
    })),
    { id: 'edge:small', source: pair[0]!, target: pair[1]!, type: 'foreign_key' },
  ];
  const dto: GraphViewDto = {
    revision: 1, truncated: false,
    stats: { totalNodes: 524, returnedNodes: 524, totalEdges: edges.length, returnedEdges: edges.length },
    nodes: [...related, ...pair, ...isolated].map((id) => ({
      id, kind: 'table', label: id, schema: 'test', description: '',
      relationCount: 0, validationSeverity: '',
    })),
    edges,
  };
  const graph = adaptGraph(dto);
  const bounds = (nodes: string[]) => {
    const points = nodes.map((node) => graph.getNodeAttributes(node));
    return {
      left: Math.min(...points.map((point) => Number(point.x))),
      right: Math.max(...points.map((point) => Number(point.x))),
      top: Math.min(...points.map((point) => Number(point.y))),
      bottom: Math.max(...points.map((point) => Number(point.y))),
    };
  };
  const large = bounds(related);
  const small = bounds(pair);
  const free = bounds(isolated);

  expect(large.right < small.left || small.right < large.left ||
    large.bottom < small.top || small.bottom < large.top).toBe(true);
  expect(free.top).toBeGreaterThan(Math.max(large.bottom, small.bottom));
  expect(Number(graph.getNodeAttribute(isolated[1]!, 'x')) -
    Number(graph.getNodeAttribute(isolated[0]!, 'x'))).toBe(120);
  expect(Number(graph.getNodeAttribute(isolated[23]!, 'y')) -
    Number(graph.getNodeAttribute(isolated[0]!, 'y'))).toBe(120);
});
