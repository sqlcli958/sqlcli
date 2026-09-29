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
  it('distributes isolated tables around the related graph in every direction', () => {
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

    const connectedPoints = connected.map((id) => ({
      x: Number(graph.getNodeAttribute(id, 'x')),
      y: Number(graph.getNodeAttribute(id, 'y')),
    }));
    const isolatedPoints = isolated.map((id) => ({
      x: Number(graph.getNodeAttribute(id, 'x')),
      y: Number(graph.getNodeAttribute(id, 'y')),
    }));

    const connectedMinX = Math.min(...connectedPoints.map((point) => point.x));
    const connectedMaxX = Math.max(...connectedPoints.map((point) => point.x));
    const connectedMinY = Math.min(...connectedPoints.map((point) => point.y));
    const connectedMaxY = Math.max(...connectedPoints.map((point) => point.y));

    expect((connectedMinX + connectedMaxX) / 2).toBeCloseTo(0, 6);
    expect((connectedMinY + connectedMaxY) / 2).toBeCloseTo(0, 6);

    expect(isolatedPoints.some((point) => point.x < connectedMinX - 100)).toBe(true);
    expect(isolatedPoints.some((point) => point.x > connectedMaxX + 100)).toBe(true);
    expect(isolatedPoints.some((point) => point.y < connectedMinY - 100)).toBe(true);
    expect(isolatedPoints.some((point) => point.y > connectedMaxY + 100)).toBe(true);

    const isolatedCenterX =
      isolatedPoints.reduce((sum, point) => sum + point.x, 0) / isolatedPoints.length;
    const isolatedCenterY =
      isolatedPoints.reduce((sum, point) => sum + point.y, 0) / isolatedPoints.length;
    const isolatedSpanX =
      Math.max(...isolatedPoints.map((point) => point.x)) -
      Math.min(...isolatedPoints.map((point) => point.x));
    const isolatedSpanY =
      Math.max(...isolatedPoints.map((point) => point.y)) -
      Math.min(...isolatedPoints.map((point) => point.y));

    expect(Math.abs(isolatedCenterX)).toBeLessThan(isolatedSpanX * 0.08);
    expect(Math.abs(isolatedCenterY)).toBeLessThan(isolatedSpanY * 0.08);

    let minimumDistance = Infinity;
    for (let left = 0; left < isolatedPoints.length; left++) {
      for (let right = left + 1; right < isolatedPoints.length; right++) {
        const a = isolatedPoints[left]!;
        const b = isolatedPoints[right]!;
        minimumDistance = Math.min(minimumDistance, Math.hypot(a.x - b.x, a.y - b.y));
      }
    }
    expect(minimumDistance).toBeGreaterThan(70);
  });
});
