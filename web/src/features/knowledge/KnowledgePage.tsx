import { useCallback, useEffect, useMemo, useState } from 'react';
import { useMutation, useQuery } from '@tanstack/react-query';
import { useOutletContext, useSearchParams } from 'react-router-dom';
import { SchemaExplorer } from '../explorer/SchemaExplorer';
import { SearchBar } from '../search/SearchBar';
import { TableInspector } from '../table-inspector/TableInspector';
import { TermList } from './TermList';
import { LineageList } from './LineageList';
import { LineageBigGraph } from './LineageBigGraph';
import { GraphCanvas } from '../../graph/GraphCanvas';
import { useScenario, qualifiedNameOf } from '../../state/useScenario';
import { getGraph } from '../../api/graph';
import { getIndexStatus, rebuildIndex } from '../../api/indexApi';
import { queryClient } from '../../api/queryClient';
import { useGraphStore } from '../../state/graphStore';
import { useSessionStore } from '../../state/sessionStore';
import './knowledge.css';
import { Button } from '../../ui/Button';

/**
 * 图谱页。
 *
 * 原来这是整个应用的首页（App.tsx 的 AppContent），三栏布局同时承担了导航。
 * 现在它降级为一个一级页面：alias、revision、索引状态和跨页导航都归 AppShell，
 * 这里只保留搜索、表目录、关系图和表详情。
 *
 * **图谱未导入时这一页照样要能用**：导入的唯一入口是左侧表目录里按 schema 的导入按钮，
 * 而表目录读的是数据库的 schema 清单（`/api/schemas/catalog`），不依赖工作区。
 * 所以没有图谱时只降级掉画布、搜索和索引这三块——它们才是真的需要工作区的。
 */
export function KnowledgePage() {
  const [searchParams] = useSearchParams();
  const alias = searchParams.get('alias');
  const { graphAvailable } = useOutletContext<{ graphAvailable: boolean }>();

  const isConnected = useSessionStore((s) => s.isConnected);
  const schemaFilters = useGraphStore((s) => s.schemaFilters);
  const setSchemaFilters = useGraphStore((s) => s.setSchemaFilters);
  const selectNode = useGraphStore((s) => s.selectNode);
  const selectedNodeId = useGraphStore((s) => s.selectedNodeId);
  const setWorkspaceRevision = useGraphStore((s) => s.setWorkspaceRevision);

  const scenario = useScenario();

  const { data: rawGraphData, isLoading } = useQuery({
    queryKey: ['graph', alias, schemaFilters],
    queryFn: ({ signal }) =>
      getGraph(
        {
          schema: schemaFilters.length === 1 ? schemaFilters[0] : undefined,
          includeIsolated: true,
          // 不再截断。500 是「怕画不动」定的，而实测 531 个点很流畅；
          // 截断的代价反而更高——少掉的那几十张表没有任何提示说少了哪些。
          // 服务端对这两个值没有钳制，给一个够大的数即可；真超了顶栏仍会显示「已截断」。
          maxNodes: 20_000,
          maxEdges: 40_000,
        },
        signal,
      ),
    enabled: isConnected,
    staleTime: 60_000,
  });

  // 表目录筛到 11 张、中间的图还铺着 499 个点，那张图就没法看了——
  // 筛选是一个动作，两边必须一起收窄
  const graphData = useMemo(() => {
    if (!rawGraphData || !scenario.tables) return rawGraphData;
    const keep = scenario.tables;
    const nodes = rawGraphData.nodes.filter((node) => keep.has(qualifiedNameOf(node.id)));
    const ids = new Set(nodes.map((node) => node.id));
    const edges = rawGraphData.edges.filter(
      (edge) => ids.has(edge.source) && ids.has(edge.target));
    return {
      ...rawGraphData,
      nodes,
      edges,
      stats: rawGraphData.stats
        ? { ...rawGraphData.stats, returnedNodes: nodes.length, returnedEdges: edges.length }
        : rawGraphData.stats,
    };
  }, [rawGraphData, scenario.tables]);

  useEffect(() => {
    if (graphData?.revision != null) {
      setWorkspaceRevision(graphData.revision);
    }
  }, [graphData?.revision, setWorkspaceRevision]);

  // 表目录默认收起：图和右侧详情才是主要工作区，目录只在找表时需要。
  // 但还没有图谱时它是唯一能做事的地方，所以默认展开。
  const [explorerOpen, setExplorerOpen] = useState(!graphAvailable);
  // 左侧栏是表目录/术语/血缘共用的一个位置（入口唯一），用 tab 切换而不是各开一块。
  // 指标不在这里：它是「定义口径→展开 SQL→出图」的独立用法，单开一级页面（见 navigation.ts）。
  const [leftTab, setLeftTab] = useState<'tables' | 'terms' | 'lineage'>('tables');
  // 左栏点了哪张表：主画布的大图把视野移过去。只在血缘标签下有意义
  const [lineageFocus, setLineageFocus] = useState<string | null>(null);

  const handleSelectTable = useCallback(
    (schema: string, table: string) => selectNode(`${schema}.${table}`),
    [selectNode],
  );

  if (graphAvailable && !isConnected) {
    return (
      <div className="page">
        <p>正在连接…</p>
      </div>
    );
  }

  return (
    <div className="knowledge">
      <div className="knowledge-bar">
        <Button
          aria-expanded={explorerOpen}
          aria-label={explorerOpen ? '收起表目录' : '展开表目录'}
          title={explorerOpen ? '收起表目录' : '展开表目录'}
          onClick={() => setExplorerOpen((open) => !open)}
          size="sm"
          icon
        >
          <svg width="14" height="14" viewBox="0 0 24 24" fill="currentColor" aria-hidden="true">
            <path d="M3 5h18v2H3V5zm0 6h18v2H3v-2zm0 6h12v2H3v-2z" />
          </svg>
        </Button>
        <div className="knowledge-search">
          {graphAvailable ? (
            <SearchBar onSelectTable={handleSelectTable} onSelectColumn={handleSelectTable} />
          ) : (
            <span className="knowledge-stats">还没有图谱，从左侧表目录按 schema 导入</span>
          )}
        </div>
        {graphAvailable && <IndexControl alias={alias} />}
        {/* 筛选把图筛空了要说出来：0 个点的画布和「还没有图谱」长得一模一样，
            而这两件事该做的动作完全相反 */}
        {schemaFilters.length > 0 && graphData?.stats?.totalNodes === 0 && (
          <div className="knowledge-stats">
            schema 筛选「{schemaFilters.join('、')}」在这个数据源里没有匹配的表
            <Button size="sm" variant="ghost" onClick={() => setSchemaFilters([])}>
              清除筛选
            </Button>
          </div>
        )}
        {graphData?.stats && (
          <div className="knowledge-stats">
            {graphData.stats.returnedNodes}/{graphData.stats.totalNodes} 表 ·{' '}
            {graphData.stats.returnedEdges}/{graphData.stats.totalEdges} 关系
            {graphData.truncated && ' · 已截断'}
          </div>
        )}
      </div>

      <div className={`knowledge-body${explorerOpen ? '' : ' is-explorer-hidden'}`}>
        {explorerOpen && (
          <aside className="knowledge-left">
            {graphAvailable && (
              <div className="knowledge-left-tabs" role="tablist">
                <button
                  type="button"
                  role="tab"
                  aria-selected={leftTab === 'tables'}
                  className={`knowledge-left-tab${leftTab === 'tables' ? ' is-active' : ''}`}
                  onClick={() => setLeftTab('tables')}
                >
                  表目录
                </button>
                <button
                  type="button"
                  role="tab"
                  aria-selected={leftTab === 'terms'}
                  className={`knowledge-left-tab${leftTab === 'terms' ? ' is-active' : ''}`}
                  onClick={() => setLeftTab('terms')}
                >
                  术语
                </button>
                <button
                  type="button"
                  role="tab"
                  aria-selected={leftTab === 'lineage'}
                  className={`knowledge-left-tab${leftTab === 'lineage' ? ' is-active' : ''}`}
                  onClick={() => setLeftTab('lineage')}
                  title="哪些字段是推导来的，各自有几个上游"
                >
                  血缘
                </button>
              </div>
            )}
            {leftTab === 'terms' && graphAvailable ? (
              <TermList onPick={() => setLeftTab('tables')} />
            ) : leftTab === 'lineage' && graphAvailable ? (
              <LineageList onFocus={setLineageFocus} />
            ) : (
              <SchemaExplorer onSelectTable={handleSelectTable} />
            )}
          </aside>
        )}
        <main className="knowledge-center">
          {/* 血缘标签下主画布换成全库血缘大图：关系图答「怎么连」，血缘图答「值从哪来」，
              两张图叠在一个画布上谁也看不清。切回表目录 / 术语时关系图重新挂载 */}
          {leftTab === 'lineage' && graphAvailable ? (
            <LineageBigGraph focusTableId={lineageFocus} />
          ) : (
            <GraphCanvas graphData={graphData} isLoading={isLoading} />
          )}
        </main>
        {selectedNodeId && (
          <aside className="knowledge-right">
            <TableInspector />
          </aside>
        )}
      </div>
    </div>
  );
}

/**
 * 索引重建。
 *
 * 状态本身由顶栏状态灯统一展示，这里只留操作——同一个状态不在两处渲染。
 * 索引是工作区级别的，重建入口只有这一个（见 CLAUDE.md「入口唯一」）。
 */
function IndexControl({ alias }: { alias: string | null }) {
  const status = useQuery({
    queryKey: ['index', 'status', alias],
    queryFn: ({ signal }) => getIndexStatus(signal),
    staleTime: 30_000,
    refetchOnWindowFocus: false,
  });

  const rebuild = useMutation({
    mutationFn: () => rebuildIndex(),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['index', 'status'] });
      queryClient.invalidateQueries({ queryKey: ['search'] });
    },
  });

  const state = status.data?.status ?? 'unknown';
  if (state === 'ready') return null;

  return (
    <div className="knowledge-index">
      <Button
        title={state === 'stale' ? '图谱已变更，索引落后于当前 revision' : '索引缺失，搜索不可用'}
        disabled={rebuild.isPending}
        onClick={() => rebuild.mutate()}
        size="sm"
      >
        {rebuild.isPending ? '重建中…' : '重建索引'}
      </Button>
      {rebuild.isError && (
        <span className="knowledge-index-error" role="alert">
          重建失败：{rebuild.error.message}
        </span>
      )}
    </div>
  );
}

