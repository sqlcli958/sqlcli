import { useMemo } from 'react';
import { useQuery } from '@tanstack/react-query';
import { useSearchParams } from 'react-router-dom';
import { getTerms } from '../api/workspace';
import type { TermDto } from '../types/api';

/**
 * 当前选中的场景（`?term=` 指的那条术语）。
 *
 * <p>术语在界面上不是第二份目录，是**图谱的一个筛选维度**：表目录和中间那张图
 * 都按它收窄。两处共用这一个 hook，query key 也共用——它们要的是同一份数据，
 * 各查一次只会多一次请求，还可能一边已刷新一边还是旧的。
 *
 * <p>`tables` 为 null 表示没在筛（不是「筛出了 0 张」），调用方据此走全量分支。
 */
export function useScenario() {
  const [searchParams] = useSearchParams();
  const termId = searchParams.get('term');

  const { data } = useQuery({
    queryKey: ['terms', 'scenario'],
    queryFn: ({ signal }) => getTerms(signal),
    staleTime: 30_000,
    enabled: Boolean(termId),
  });

  const term: TermDto | null = data?.terms.find((one) => one.id === termId) ?? null;

  const tables = useMemo(
    () => (term ? new Set(term.scenarioTables.map((name) => name.toLowerCase())) : null),
    [term],
  );
  const bridges = useMemo(
    () => new Set((term?.bridgeTables ?? []).map((name) => name.toLowerCase())),
    [term],
  );

  return { termId, term, tables, bridges };
}

/** 图谱节点 id（`table:alias:schema.table`）里的限定名，小写。 */
export function qualifiedNameOf(nodeId: string): string {
  const parts = nodeId.split(':');
  return (parts.length > 2 ? parts.slice(2).join(':') : nodeId).toLowerCase();
}
