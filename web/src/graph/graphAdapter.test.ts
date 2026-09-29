import { describe, expect, it } from 'vitest';
import type { GraphNodeDto, GraphViewDto } from '../types/api';
import { adaptGraph } from './graphAdapter';

function tableNode(id: string, relationCount = 0): GraphNodeDto {
  return {
    id,
    kind: 'table',
    label: id,
    schema: 'public',
    description: '',
    relationCount,
    columnCount: 8,
    validationSeverity: 'info',
  };
}

describe('graphAdapter grouped layout', () => {
  it('keeps related components centered and packs isolated tables to the side', () => {
    const connected = ['table:a', 'table:b', 'table:c', 'table:d'];
    const isolated = Array.from({ length: 80 }, (_, index) => `table:isolated_${index}`);
    const dto: GraphViewDto = {
      revision: 1,
      truncated: false,
      stats: {
        totalNodes: connected.length + isolated.length,
        totalEdges: 3,
        returnedNodes: connected.length + isolated.length,
        returnedEdges: 3,
      },
      nodes: [
        tableNode(connected[0]!, 1),
        tableNode(connected[1]!, 2),
        tableNode(connected[2]!, 2),
        tableNode(connected[3]!, 1),
        ...isolated.map((id) => tableNode(id)),
      ],
      edges: [
        { id: 'e1', source: connected[0]!, target: connected[1]!, type: 'foreign_key' },
        { id: 'e2', source: connected[1]!, target: connected[2]!, type: 'foreign_key' },
        { id: 'e3', source: connected[2]!, target: connected[3]!, type: 'foreign_key' },
      ],
    };

    const graph = adaptGraph(dto);
    expect(graph.getAttribute('layout')).toBe('grouped');

    const connectedXs = connected.map((id) => Number(graph.getNodeAttribute(id, 'x')));
    const connectedYs = connected.map((id) => Number(graph.getNodeAttribute(id, 'y')));
    const isolatedXs = isolated.map((id) => Number(graph.getNodeAttribute(id, 'x')));

    const connectedMinX = Math.min(...connectedXs);
    const connectedMaxX = Math.max(...connectedXs);
    const connectedMinY = Math.min(...connectedYs);
    const connectedMaxY = Math.max(...connectedYs);

    expect((connectedMinX + connectedMaxX) / 2).toBeCloseTo(0, 6);
    expect((connectedMinY + connectedMaxY) / 2).toBeCloseTo(0, 6);
    expect(Math.min(...isolatedXs)).toBeGreaterThan(connectedMaxX + 100);
  });
});
