import { useState, useCallback, useMemo, useEffect, useRef } from 'react';
import { useInfiniteQuery, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useSearchParams } from 'react-router-dom';
import { getTables, getSchemaCatalog, type SchemaCatalogItemDto } from '../../api/workspace';
import { useScenario } from '../../state/useScenario';
import type { TableListItemDto } from '../../types/api';
import { importAliasGraph } from '../../api/aliases';
import { useGraphStore } from '../../state/graphStore';
import { getSchemaColor } from '../../graph/graphStyles';
import { Button } from '../../ui/Button';

interface SchemaExplorerProps {
  onSelectTable: (schema: string, table: string) => void;
}

/**
 * 表目录。
 *
 * 列的是数据源里的**全部 schema**，不只是图谱里已有的——未导入的也要露出来，
 * 否则用户不知道还有什么可以导。每个 schema 后面带导入按钮，按 schema 逐个导，
 * 而不是一次全导：一个连接下十几个 schema、几千张表，全导会让图谱渲染和索引重建都变慢。
 */
export function SchemaExplorer({ onSelectTable }: SchemaExplorerProps) {
  const [searchParams, setSearchParams] = useSearchParams();
  const alias = searchParams.get('alias');
  const [expandedSchemas, setExpandedSchemas] = useState<Set<string>>(new Set());
  const [showSystem, setShowSystem] = useState(false);
  const schemaFilters = useGraphStore((s) => s.schemaFilters);
  const toggleSchemaFilter = useGraphStore((s) => s.toggleSchemaFilter);
  const queryClient = useQueryClient();

  // 术语在这一页不是第二份目录，是**表目录上的一个筛选维度**：选中一条场景术语，
  // 表目录只剩这个场景的表。跟 CLI 侧同构——search 命中术语是把它的表提上来。
  const { term: activeTerm, tables: scenarioTables, bridges: bridgeTables } = useScenario();

  const catalog = useQuery({
    queryKey: ['schemas', 'catalog', alias],
    queryFn: ({ signal }) => getSchemaCatalog(alias!, signal),
    enabled: Boolean(alias),
    staleTime: 60_000,
  });

  const importSchema = useMutation({
    mutationFn: (schema: string) => importAliasGraph(alias!, { schema }),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['schemas'] });
      queryClient.invalidateQueries({ queryKey: ['graph'] });
      queryClient.invalidateQueries({ queryKey: ['tables'] });
      queryClient.invalidateQueries({ queryKey: ['workspace'] });
      queryClient.invalidateQueries({ queryKey: ['index', 'status'] });
      // 第一次导入要让壳层知道图谱有了：graphAvailable 来自 aliases，
      // 不刷它的话画布、搜索和会话握手会一直停在"还没有图谱"。
      queryClient.invalidateQueries({ queryKey: ['aliases'] });
    },
  });

  const toggleSchema = useCallback((schemaName: string) => {
    setExpandedSchemas((prev) => {
      const next = new Set(prev);
      if (next.has(schemaName)) {
        next.delete(schemaName);
      } else {
        next.add(schemaName);
      }
      return next;
    });
  }, []);

  const allSchemas = catalog.data ?? [];
  // 筛到一个场景之后必须自动展开：schema 行默认是收起的，不展开的话筛完看到的
  // 只是一排收起的 schema，跟没筛一样——真正要看的表还藏在里面
  const scenarioSchemas = useMemo(
    () => new Set([...(scenarioTables ?? [])].map((name) => name.split('.')[0])),
    [scenarioTables],
  );
  const inScenario = (schemaName: string) => !scenarioTables
    || [...scenarioTables].some((name) => name.startsWith(schemaName.toLowerCase() + '.'));
  const all = allSchemas.filter((s) => inScenario(s.name));
  const visible = showSystem ? all : all.filter((s) => !s.system);
  const systemCount = all.filter((s) => s.system).length;

  return (
    <div className="schema-explorer">
      {activeTerm && (
        <div className="scenario-banner">
          <div className="scenario-banner-head">
            <span className="scenario-banner-name">
              场景「{activeTerm.displayName || activeTerm.name}」
            </span>
            <span className="scenario-banner-count">{activeTerm.scenarioTables.length} 张表</span>
            <Button
              variant="ghost"
              size="sm"
              onClick={() => setSearchParams((params) => {
                params.delete('term');
                return params;
              })}
            >
              取消筛选
            </Button>
          </div>
          {/* 过滤单独一行：它是要被写进 WHERE 的，挤在元信息里就会被忽略 */}
          {activeTerm.filters.length > 0 && (
            <p className="scenario-banner-filters">过滤：{activeTerm.filters.join('  AND  ')}</p>
          )}
        </div>
      )}
      {catalog.isLoading && <div className="table-list-loading">加载中…</div>}
      {catalog.isError && (
        <div className="explorer-alert" role="alert">
          无法读取 schema 列表：{catalog.error.message}
        </div>
      )}
      {importSchema.isError && (
        <div className="explorer-alert" role="alert">
          导入失败：{importSchema.error.message}
        </div>
      )}

      <ul className="schema-list">
        {visible.map((schema) => (
          <SchemaRow
            key={schema.name}
            schema={schema}
            expanded={expandedSchemas.has(schema.name)
              || scenarioSchemas.has(schema.name.toLowerCase())}
            countOverride={scenarioTables
              ? [...scenarioTables].filter((name) =>
                  name.startsWith(schema.name.toLowerCase() + '.')).length
              : null}
            filtered={schemaFilters.includes(schema.name)}
            scenarioTables={scenarioTables}
            bridgeTables={bridgeTables}
            importing={importSchema.isPending && importSchema.variables === schema.name}
            disabled={importSchema.isPending}
            onToggle={() => toggleSchema(schema.name)}
            onToggleFilter={() => toggleSchemaFilter(schema.name)}
            onImport={() => importSchema.mutate(schema.name)}
            onSelectTable={onSelectTable}
          />
        ))}
      </ul>

      {systemCount > 0 && (
        <button
          type="button"
          className="explorer-system-toggle"
          onClick={() => setShowSystem((value) => !value)}
        >
          {showSystem ? '隐藏' : '显示'}系统 schema（{systemCount}）
        </button>
      )}
    </div>
  );
}

function SchemaRow({
  schema,
  expanded,
  countOverride,
  filtered,
  scenarioTables,
  bridgeTables,
  importing,
  disabled,
  onToggle,
  onToggleFilter,
  onImport,
  onSelectTable,
}: {
  schema: SchemaCatalogItemDto;
  expanded: boolean;
  /** 筛场景时表数显示这个场景的，不是全库的——旁边写着 11 张表、这里写 499 会让人以为筛坏了 */
  countOverride: number | null;
  filtered: boolean;
  scenarioTables: Set<string> | null;
  bridgeTables: Set<string>;
  importing: boolean;
  disabled: boolean;
  onToggle: () => void;
  onToggleFilter: () => void;
  onImport: () => void;
  onSelectTable: (schema: string, table: string) => void;
}) {
  const color = getSchemaColor(schema.name);

  return (
    <li className={`schema-item${schema.imported ? '' : ' is-not-imported'}`}>
      <div className="schema-row">
        <button
          className={`schema-filter-dot ${filtered ? 'active' : ''}`}
          style={{ backgroundColor: schema.imported ? color : 'transparent', borderColor: color }}
          onClick={onToggleFilter}
          disabled={!schema.imported}
          title={
            !schema.imported
              ? '未导入，无法过滤'
              : filtered
                ? '取消过滤'
                : '只看这个 schema'
          }
        />
        <button
          className="schema-toggle"
          onClick={onToggle}
          disabled={!schema.imported}
          title={schema.imported ? undefined : '导入后可展开查看表'}
        >
          <span className={`chevron ${expanded ? 'expanded' : ''}`}>&#9654;</span>
          <span className="schema-name">{schema.name}</span>
          {schema.imported ? (
            <span className="schema-count">{countOverride ?? schema.tableCount}</span>
          ) : (
            <span className="schema-count is-muted">未导入</span>
          )}
        </button>
        <button
          type="button"
          className="schema-import"
          disabled={disabled}
          aria-label={schema.imported ? `重新导入 ${schema.name}` : `导入 ${schema.name}`}
          title={
            schema.missingInDatabase
              ? '该 schema 在数据库里已不存在'
              : schema.imported
                ? '重新导入：同步表结构变化和数据库注释，业务名、业务描述与自建关系不会被覆盖'
                : '导入这个 schema 的表结构到图谱'
          }
          onClick={onImport}
        >
          {importing ? (
            <span className="spinner" />
          ) : schema.imported ? (
            <svg width="12" height="12" viewBox="0 0 24 24" fill="currentColor" aria-hidden="true">
              <path d="M17.65 6.35A8 8 0 1 0 19.73 14h-2.08A6 6 0 1 1 12 6c1.66 0 3.14.69 4.22 1.78L13 11h7V4l-2.35 2.35z" />
            </svg>
          ) : (
            <svg width="12" height="12" viewBox="0 0 24 24" fill="currentColor" aria-hidden="true">
              <path d="M19 13h-6v6h-2v-6H5v-2h6V5h2v6h6v2z" />
            </svg>
          )}
        </button>
      </div>
      {expanded && schema.imported && (
        <SchemaTableList
          schemaName={schema.name}
          onSelectTable={onSelectTable}
          scenarioTables={scenarioTables}
          bridgeTables={bridgeTables}
        />
      )}
    </li>
  );
}

function SchemaTableList({
  schemaName,
  onSelectTable,
  scenarioTables,
  bridgeTables,
}: {
  schemaName: string;
  onSelectTable: (schema: string, table: string) => void;
  /** null 表示没在筛场景，展示全部 */
  scenarioTables: Set<string> | null;
  bridgeTables: Set<string>;
}) {
  // 筛场景时不查分页列表：列表是分页的（一页 200），而这个库有 499 张表，
  // 场景那十来张多半落在取回的那一页之外——在切片上做客户端过滤会一张都匹配不到
  // （实际就是这么坏的）。场景本身已经给出完整的表名清单，直接用它，还省一次请求。
  const scenarioRows = useMemo(() => {
    if (!scenarioTables) return null;
    const prefix = schemaName.toLowerCase() + '.';
    return [...scenarioTables]
      .filter((name) => name.startsWith(prefix))
      .map((name) => name.slice(prefix.length))
      .sort();
  }, [scenarioTables, schemaName]);

  // 滚动加载：一次只取一页，滚到底再取下一页。原来是一把梭 200 张，
  // 剩下的 299 张在侧栏里根本不存在——没有翻页也没有滚动，只能靠搜索找。
  const PAGE = 100;
  const { data, isLoading, fetchNextPage, hasNextPage, isFetchingNextPage } = useInfiniteQuery({
    queryKey: ['tables', schemaName],
    queryFn: ({ pageParam, signal }) => getTables(schemaName, pageParam, PAGE, undefined, signal),
    initialPageParam: 0,
    getNextPageParam: (last, all) => {
      const loaded = all.reduce((sum, page) => sum + page.items.length, 0);
      return loaded < last.total ? loaded : undefined;
    },
    staleTime: 120_000,
    enabled: !scenarioRows,
  });

  const sentinel = useRef<HTMLLIElement | null>(null);
  useEffect(() => {
    const node = sentinel.current;
    if (!node || !hasNextPage || isFetchingNextPage) return;
    // 用 IntersectionObserver 而不是 onScroll：侧栏是外层滚动容器，
    // 监听滚动事件要自己算距底距离，还得跟着容器高度变化重算
    const observer = new IntersectionObserver((entries) => {
      if (entries.some((entry) => entry.isIntersecting)) fetchNextPage();
    }, { rootMargin: '120px' });
    observer.observe(node);
    return () => observer.disconnect();
  }, [hasNextPage, isFetchingNextPage, fetchNextPage]);

  if (isLoading && !scenarioRows) {
    return <div className="table-list-loading">加载中…</div>;
  }

  // 场景行拿不到列数、标签这些目录字段（它们只在分页列表里），补空值占位——
  // 少几个徽标可以接受，把 499 张表全拉下来只为显示 11 张不行
  const tables: TableListItemDto[] = scenarioRows
      ? scenarioRows.map((name) => ({
          id: schemaName + '.' + name,
          schema: schemaName,
          name,
          qualifiedName: schemaName + '.' + name,
          comment: '',
          description: null,
          tableType: 'base_table',
          columnCount: 0,
          tags: [],
        }))
      : (data?.pages.flatMap((page) => page.items) ?? []);

  return (
    <ul className="table-list">
      {tables.map((table) => (
        <li key={table.id}>
          <button
            className="table-item"
            onClick={() => onSelectTable(schemaName, table.name)}
            title={table.comment}
          >
            <svg viewBox="0 0 24 24" width="14" height="14" className="table-icon">
              <path fill="currentColor" d="M3 3h18v18H3V3zm2 2v14h14V5H5zm2 2h10v2H7V7zm0 4h10v2H7v-2zm0 4h7v2H7v-2z" />
            </svg>
            <span className="table-name">{table.name}</span>
            {bridgeTables.has((schemaName + '.' + table.name).toLowerCase()) && (
              // 桥接表术语并没有映射它，是为了把子图连起来才补进来的——
              // 不标的话人会以为它也属于这个场景
              <span className="table-type-badge" title="为连通子图补进来的，术语并未映射它">桥接</span>
            )}
            {/* 场景行只有表名（目录字段在分页列表里，而筛场景时不查那个列表）。
                显示占位的「BASE TABLE / 0 字段」是拿假值冒充真值，不如不显示 */}
            {!scenarioRows && table.tableType && (
              <span className="table-type-badge">{table.tableType}</span>
            )}
            {!scenarioRows && (
              <span className="table-col-count">{table.columnCount} 字段</span>
            )}
            {!scenarioRows && table.tags.length > 0 && (
              <span className="table-has-tags" title={table.tags.join(', ')}>
                #
              </span>
            )}
          </button>
        </li>
      ))}
      {/* 哨兵：滚进视野就取下一页。放在列表末尾而不是容器上，
          这样它跟着列表一起被外层滚动容器带动 */}
      {!scenarioRows && hasNextPage && (
        <li ref={sentinel} className="table-list-loading">
          {isFetchingNextPage ? '加载中…' : ''}
        </li>
      )}
    </ul>
  );
}
