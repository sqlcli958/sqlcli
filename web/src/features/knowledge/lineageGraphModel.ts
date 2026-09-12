import type { LineageGraphDto } from '../../types/api';

/** 一个表节点里的一行：一个有血缘的列 */
export interface ColumnRow {
  /** schema.table.column */
  id: string;
  name: string;
  /** 由本表别的列算出来的（contract_basic 的 no ← classify + create_time）：画布上不画表内边，行尾标一个记号 */
  internalSources: string[];
}

/** 一张表在大图上的内容：有血缘的列 + 没参与的列数 */
export interface TableGroup {
  id: string;
  schema: string;
  table: string;
  columns: ColumnRow[];
  /** 表里没参与血缘的列数，折成一行「其余 N 列」 */
  otherColumns: number;
}

/** schema.table.column → schema.table */
export function tableOf(columnRef: string): string {
  const lastDot = columnRef.lastIndexOf('.');
  return lastDot < 0 ? columnRef : columnRef.slice(0, lastDot);
}

/** 选中列的上下游闭包：两个方向都走到底，路径上的列都算（含自己） */
export function reachable(graph: Pick<LineageGraphDto, 'edges'>, columnId: string): Set<string> {
  const lit = new Set<string>([columnId]);
  const walk = (start: string, dir: 'up' | 'down') => {
    const stack = [start];
    while (stack.length > 0) {
      const current = stack.pop()!;
      for (const edge of graph.edges) {
        const next = dir === 'up' ? (edge.to === current ? edge.from : null) : edge.from === current ? edge.to : null;
        if (next && !lit.has(next)) {
          lit.add(next);
          stack.push(next);
        }
      }
    }
  };
  walk(columnId, 'up');
  walk(columnId, 'down');
  return lit;
}

/**
 * 把列级的点按表聚成容器：列按名字排，表内推导记到目标行上而不画边，
 * 「其余 N 列」= 表的总列数减去参与的列数。
 */
export function groupByTable(graph: Pick<LineageGraphDto, 'nodes' | 'edges' | 'tableColumns'>): TableGroup[] {
  const byTable = new Map<string, TableGroup>();
  for (const column of graph.nodes) {
    const tableId = `${column.schema}.${column.table}`;
    const entry = byTable.get(tableId) ?? {
      id: tableId,
      table: column.table ?? tableId,
      schema: column.schema ?? '',
      columns: [],
      otherColumns: graph.tableColumns?.[tableId] ?? 0,
    };
    entry.columns.push({ id: column.id, name: column.column ?? column.id, internalSources: [] });
    byTable.set(tableId, entry);
  }
  // 表内推导不画成边：从右端口绕回左端口那根线要么被节点盖住、要么横穿节点，两种都是噪音。
  // 目标行尾标一个记号，源列在悬浮和右侧详情里
  for (const edge of graph.edges) {
    if (tableOf(edge.from) !== tableOf(edge.to)) continue;
    const row = byTable.get(tableOf(edge.to))?.columns.find((c) => c.id === edge.to);
    if (row && !row.internalSources.includes(edge.from)) row.internalSources.push(edge.from);
  }
  for (const entry of byTable.values()) {
    entry.columns.sort((a, b) => a.name.localeCompare(b.name));
    entry.otherColumns = Math.max(0, entry.otherColumns - entry.columns.length);
  }
  return Array.from(byTable.values());
}
