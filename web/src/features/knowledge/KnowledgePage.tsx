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

const MODES = [
  { key: 'tables', label: '表与字段' },
  { key: 'relations', label: '关系' },
  { key: 'lineage', label: '血缘' },
  { key: 'terms', label: '术语' },
] as const;

type ModelMode = (typeof MODES)[number]['key'];

function isModelMode(value: string | null): value is ModelMode {
  return MODES.some((item) => item.key === value);
}

/**
 * 数据模型工作区。
 *
 * 四种任务不再挤在一个三栏页面里：
 * - 表与字段：目录 + 详情；
 * - 关系：关系图 + 按需打开的详情抽屉；
 * - 血缘：血缘索引 + 血缘画布；
 * - 术语：术语目录。
 */
export function KnowledgePage() {
  const [searchParams, setSearchParams] = useSearchParams();
  const alias = searchParams.get('alias');
  const { graphAvailable } = useOutletContext<{ graphAvailable: boolean }>();

  const isConnected = useSessionStore((s) => s.isConnected);
  const schemaFilters = useGraphStore((s) => s.schemaFilters);
  const setSchemaFilters = useGraphStore((s) => s.setSchemaFilters);
  const selectNode = useGraphStore((s) => s.selectNode);
  const selectedNodeId = useGraphStore((s) => s.selectedNodeId);
  const setWorkspaceRevision = useGraphStore((s) => s.setWorkspaceRevision);

  const requestedMode = searchParams.get('mode');
  const mode: ModelMode = graphAvailable && isModelMode(requestedMode)
    ? requestedMode
    : 'tables';

  const setMode = useCallback((next: ModelMode) => {
    setSearchParams((current) => {
      const copy = new URLSearchParams(current);
      if (next === 'tables') copy.delete('mode');
      else copy.set('mode', next);
      return copy;
    });
    selectNode(null);
  }, [selectNode, setSearchParams]);

  const scenario = useScenario();

  const { data: rawGraphData, isLoading } = useQuery({
    queryKey: ['graph', alias, schemaFilters],
    queryFn: ({ signal }) =>
      getGraph(
        {
          schema: schemaFilters.length === 1 ? schemaFilters[0] : undefined,
          includeIsolated: true,
          maxNodes: 20_000,
          maxEdges: 40_000,
        },
        signal,
      ),
    enabled: isConnected && graphAvailable && mode === 'relations',
    staleTime: 60_000,
  });

  const graphData = useMemo(() => {
    if (!rawGraphData || !scenario.tables) return rawGraphData;
    const keep = scenario.tables;
    const nodes = rawGraphData.nodes.filter((node) => keep.has(qualifiedNameOf(node.id)));
    const ids = new Set(nodes.map((node) => node.id));
    const edges = rawGraphData.edges.filter(
      (edge) => ids.has(edge.source) && ids.has(edge.target),
    );
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
    if (graphData?.revision != null) setWorkspaceRevision(graphData.revision);
  }, [graphData?.revision, setWorkspaceRevision]);

  const [explorerOpen, setExplorerOpen] = useState(true);
  const [lineageFocus, setLineageFocus] = useState<string | null>(null);

  const handleSelectTable = useCallback(
    (schema: string, table: string) => selectNode(`${schema}.${table}`),
    [selectNode],
  );

  if (graphAvailable && !isConnected) {
    return (
      <div className="knowledge knowledge-redesign">
        <div className="model-loading">正在连接数据模型…</div>
      </div>
    );
  }

  return (
    <div className="knowledge knowledge-redesign">
      <header className="model-header">
        <div className="model-title">
          <span className="model-eyebrow">Data model</span>
          <h1>数据模型</h1>
          <p>
            {graphAvailable
              ? '浏览表结构、关系、字段血缘和业务术语。'
              : '先从表目录按 schema 导入；SQL 工作台不受影响。'}
          </p>
        </div>

        <nav className="model-tabs" aria-label="数据模型视图">
          {MODES.map((item) => {
            const disabled = !graphAvailable && item.key !== 'tables';
            return (
              <button
                type="button"
                key={item.key}
                disabled={disabled}
                className={mode === item.key ? 'is-active' : undefined}
                aria-current={mode === item.key ? 'page' : undefined}
                title={disabled ? '导入数据模型后可用' : undefined}
                onClick={() => setMode(item.key)}
              >
                {item.label}
              </button>
            );
          })}
        </nav>
      </header>

      {(mode === 'tables' || mode === 'relations') && (
        <div className="model-toolbar">
          {mode === 'relations' && (
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
          )}

          <div className="model-search">
            {graphAvailable ? (
              <SearchBar onSelectTable={handleSelectTable} onSelectColumn={handleSelectTable} />
            ) : (
              <span className="model-toolbar-hint">从左侧 schema 目录开始导入</span>
            )}
          </div>

          {mode === 'relations' && graphAvailable && <IndexControl alias={alias} />}

          {mode === 'relations' && schemaFilters.length > 0 && graphData?.stats?.totalNodes === 0 && (
            <div className="model-toolbar-hint">
              当前 schema 筛选没有匹配表
              <Button size="sm" variant="ghost" onClick={() => setSchemaFilters([])}>
                清除筛选
              </Button>
            </div>
          )}

          {mode === 'relations' && graphData?.stats && (
            <div className="model-toolbar-stats">
              {graphData.stats.returnedNodes}/{graphData.stats.totalNodes} 表 ·{' '}
              {graphData.stats.returnedEdges}/{graphData.stats.totalEdges} 关系
              {graphData.truncated && ' · 已截断'}
            </div>
          )}
        </div>
      )}

      <div className="model-body">
        {mode === 'tables' && (
          <div className="model-catalog">
            <aside className="model-sidebar">
              <div className="model-pane-title">表目录</div>
              <SchemaExplorer onSelectTable={handleSelectTable} />
            </aside>
            <main className="model-detail">
              {selectedNodeId ? (
                <TableInspector />
              ) : (
                <ModelEmpty
                  title={graphAvailable ? '选择一张表查看详情' : '从左侧导入一个 schema'}
                  detail={
                    graphAvailable
                      ? '字段、索引、关系和字段语义会集中显示在这里。'
                      : '导入后会得到表结构、关系搜索和语义上下文；不会影响数据库本身。'
                  }
                />
              )}
            </main>
          </div>
        )}

        {mode === 'relations' && (
          <div className={`model-relations${explorerOpen ? '' : ' is-sidebar-hidden'}`}>
            {explorerOpen && (
              <aside className="model-sidebar model-relation-sidebar">
                <div className="model-pane-title">表目录</div>
                <SchemaExplorer onSelectTable={handleSelectTable} />
              </aside>
            )}
            <main className="model-graph">
              <GraphCanvas graphData={graphData} isLoading={isLoading} />
              {selectedNodeId && (
                <aside className="model-inspector-drawer" aria-label="表详情">
                  <div className="model-drawer-head">
                    <strong>表详情</strong>
                    <Button
                      size="sm"
                      variant="ghost"
                      icon
                      title="关闭详情"
                      aria-label="关闭详情"
                      onClick={() => selectNode(null)}
                    >
                      ×
                    </Button>
                  </div>
                  <div className="model-drawer-body">
                    <TableInspector />
                  </div>
                </aside>
              )}
            </main>
          </div>
        )}

        {mode === 'lineage' && (
          <div className="model-lineage">
            <aside className="model-sidebar">
              <div className="model-pane-title">字段血缘</div>
              <LineageList onFocus={setLineageFocus} />
            </aside>
            <main className="model-lineage-canvas">
              <LineageBigGraph focusTableId={lineageFocus} />
            </main>
          </div>
        )}

        {mode === 'terms' && (
          <div className="model-terms">
            <div className="model-pane-title">业务术语</div>
            <TermList onPick={() => setMode('tables')} />
          </div>
        )}
      </div>
    </div>
  );
}

function ModelEmpty({ title, detail }: { title: string; detail: string }) {
  return (
    <div className="model-empty">
      <strong>{title}</strong>
      <p>{detail}</p>
    </div>
  );
}

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
        title={state === 'stale' ? '数据模型已变更，索引落后于当前 revision' : '索引缺失，搜索不可用'}
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
