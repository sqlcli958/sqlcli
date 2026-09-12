import { useRef, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Link, useSearchParams } from 'react-router-dom';
import { getAliases } from '../../api/aliases';
import { getWorkspaceStats, getWorkspaceCompleteness } from '../../api/workspace';
import { getValidationIssues } from '../../api/validation';
import { getIndexStatus } from '../../api/indexApi';
import { navPath } from '../../app/navigation';
import { SqlEditor } from './SqlEditor';
import { takeWorkbenchSql } from './workbenchSql';
import './workbench.css';
import { buttonClass } from '../../ui/Button';
import { StatusDot, type Tone } from '../../ui/StatusDot';
import type { WorkspaceCompletenessDto } from '../../types/api';

/**
 * 工作台。原「概览」并入此页，导航因此从六项收敛为五项。
 *
 * 没有选中数据源时它就是数据源目录；选中之后是这个数据源的健康视图加 SQL 编辑器。
 * 一个页面两种状态，而不是两个页面——没有上下文时本来要回答的问题就是"我该看哪个库"。
 *
 * **执行记录不在这里**，在评审页——审批看「要不要放行」，执行记录看「放行之后
 * 发生了什么」，它们是同一件事的两半，同一个功能只留一个入口。
 *
 * **这里没有导入图谱的入口**，只有跳到图谱页的链接。导入的粒度是「一个 schema」，
 * 而挑哪个 schema 要看表目录，那是图谱页的东西；在这里再放一个整库导入按钮，
 * 就成了同一件事的第二个入口，还是更粗的那个。
 *
 * SQL 编辑器在「图谱规模」之前，**不要求图谱已导入**——跑 SQL 只需要连接，
 * 把它护在图谱后面等于新数据源连一条 SELECT 都跑不了。
 */
export function WorkbenchPage() {
  const [searchParams] = useSearchParams();
  const alias = searchParams.get('alias');
  return alias ? <WorkspaceBoard alias={alias} /> : <SourceDirectory />;
}

/* ────────────────── 未选数据源：目录 ────────────────── */

function SourceDirectory() {
  const { data, isLoading, isError } = useQuery({
    queryKey: ['aliases'],
    queryFn: ({ signal }) => getAliases(signal),
    staleTime: 30_000,
  });

  return (
    <div className="page wb">
      <header className="wb-head">
        <h1>数据源</h1>
      </header>

      {isLoading && <p className="wb-hint">加载中…</p>}
      {isError && (
        <p className="wb-alert" role="alert">
          无法读取数据源列表。确认 <code>sql-cli ui</code> 仍在运行。
        </p>
      )}
      {data?.aliases.length === 0 && (
        <p className="wb-hint">
          尚未配置数据源。用 <code>sql-cli alias add</code> 添加，或到设置页新建。
        </p>
      )}

      <div className="wb-sources">
        {data?.aliases.map((a) => (
          <article className="wb-source" key={a.name}>
            <div className="wb-source-top">
              <h2>{a.name}</h2>
              <span className="wb-tag">{a.dbType || 'unknown'}</span>
              {a.readOnly && <span className="wb-tag is-lock">只读</span>}
            </div>
            {a.description && <p className="wb-source-desc">{a.description}</p>}
            <p className="wb-source-stat">
              {a.graphAvailable ? (
                <>
                  <b>{a.tables ?? 0}</b> 张表 · <b>{a.relations ?? 0}</b> 条关系
                </>
              ) : (
                <span className="wb-muted">图谱未导入</span>
              )}
            </p>
            <div className="wb-source-actions">
              <Link className={buttonClass('primary')} to={navPath('sql', a.name)}>
                打开
              </Link>
              <Link className={buttonClass()} to={navPath('knowledge', a.name)}>
                {a.graphAvailable ? '图谱' : '去导入'}
              </Link>
            </div>
          </article>
        ))}
      </div>
    </div>
  );
}

/* ────────────────── 已选数据源：健康视图 ────────────────── */

function WorkspaceBoard({ alias }: { alias: string }) {
  // 跨页送进来的 SQL（表详情的「在工作台查询」）只在首次渲染时取一次，取完就清。
  const [sql, setSql] = useState(() => takeWorkbenchSql() ?? '');
  const editorRef = useRef<HTMLTextAreaElement>(null);

  const aliases = useQuery({
    queryKey: ['aliases'],
    queryFn: ({ signal }) => getAliases(signal),
    staleTime: 30_000,
  });
  const current = aliases.data?.aliases.find((a) => a.name === alias);
  const graphAvailable = current?.graphAvailable ?? false;

  const stats = useQuery({
    queryKey: ['workspace', 'stats', alias],
    queryFn: ({ signal }) => getWorkspaceStats(signal),
    enabled: graphAvailable,
  });

  // 只问 error 级别：warning 的量级对"是否需要现在处理"没有决定作用。
  const issues = useQuery({
    queryKey: ['validation', 'issues', alias, 'error'],
    queryFn: ({ signal }) => getValidationIssues(undefined, 'error', signal),
    enabled: graphAvailable,
  });

  const index = useQuery({
    queryKey: ['index', 'status', alias],
    queryFn: ({ signal }) => getIndexStatus(signal),
    enabled: graphAvailable,
  });

  const completeness = useQuery({
    queryKey: ['workspace', 'completeness', alias],
    queryFn: ({ signal }) => getWorkspaceCompleteness(signal),
    enabled: graphAvailable,
  });

  if (!graphAvailable) {
    return (
      <div className="page wb">
        <header className="wb-head">
          <h1>{alias}</h1>
          <p>还没有图谱，导入后才能搜索表和维护关系。</p>
        </header>
        <div className="wb-empty">
          <Link className={buttonClass('primary')} to={navPath('knowledge', alias)}>
            去图谱页导入
          </Link>
          <p className="wb-hint">
            图谱页的表目录会列出这个数据源的全部 schema，按需要逐个导入。
            命令行等价写法：
            <code>sql-cli {alias} schema import --from-db --schema &lt;schema&gt;</code>
          </p>
        </div>

        {/* 没图谱也能跑 SQL：执行只靠连接，和图谱无关 */}
        <SqlEditor
          alias={alias}
          aliasInfo={current}
          sql={sql}
          onSqlChange={setSql}
          textareaRef={editorRef}
        />
      </div>
    );
  }

  const s = stats.data;
  const errorCount = issues.data?.total ?? 0;
  const indexStatus = index.data?.status ?? 'unknown';
  const approvalOn = Boolean(
    current?.approveQuery || current?.approveUpdate || current?.approveGraph,
  );

  return (
    <div className="page wb">
      <header className="wb-head">
        <h1>{alias}</h1>
        <p>
          {current?.description || '数据源概览'}
          {current?.readOnly && <span className="wb-tag is-lock">只读</span>}
        </p>
      </header>

      <SqlEditor
        alias={alias}
        aliasInfo={current}
        sql={sql}
        onSqlChange={setSql}
        textareaRef={editorRef}
      />

      {/* 图谱规模 */}
      <section className="wb-section">
        <h2 className="wb-section-title">图谱规模</h2>
        <div className="wb-metrics">
          <Metric label="Schema" value={s?.schemas} />
          <Metric label="表" value={s?.tables} />
          <Metric label="字段" value={s?.columns} />
          <Metric label="关系" value={s?.relations} />
          <Metric label="业务术语" value={s?.terms} />
        </div>
      </section>

      {/* 需要关注 */}
      <section className="wb-section">
        <h2 className="wb-section-title">需要关注</h2>
        <div className="wb-cards">
          <Attention
            tone={indexStatus === 'ready' ? 'ok' : indexStatus === 'stale' ? 'warn' : 'bad'}
            title="搜索索引"
            body={
              indexStatus === 'ready'
                ? `已就绪，覆盖 ${index.data?.tableCount ?? 0} 张表`
                : indexStatus === 'stale'
                  ? '图谱已变更，索引落后于当前 revision，搜索结果可能不完整'
                  : '索引缺失，schema search 无法使用'
            }
            action={
              indexStatus !== 'ready' && (
                <Link className={buttonClass()} to={navPath('knowledge', alias)}>
                  去重建
                </Link>
              )
            }
          />

          <Attention
            tone={errorCount > 0 ? 'bad' : (s?.validationIssues ?? 0) > 0 ? 'warn' : 'ok'}
            title="图谱校验"
            body={
              (s?.validationIssues ?? 0) === 0
                ? '没有未处理的校验问题'
                : `${s?.validationIssues} 个问题${errorCount > 0 ? `，其中 ${errorCount} 个为错误` : ''}`
            }
            action={
              (s?.validationIssues ?? 0) > 0 && (
                <Link className={buttonClass()} to={navPath('knowledge', alias)}>
                  去处理
                </Link>
              )
            }
          />

          <Attention
            tone={approvalOn ? 'warn' : 'info'}
            title="审批"
            body={
              approvalOn
                ? `已开启：${[
                    current?.approveQuery && '查询',
                    current?.approveUpdate && '增删改',
                    current?.approveGraph && '图谱变更',
                  ]
                    .filter(Boolean)
                    .join(' / ')}，执行前会停在评审页等放行。`
                : '未开启，操作直接执行。到设置页可以按数据源打开；执行记录在评审页。'
            }
            action={
              <Link className={buttonClass()} to={navPath(approvalOn ? 'reviews' : 'settings', alias)}>
                {approvalOn ? '去评审' : '去设置'}
              </Link>
            }
          />
        </div>
      </section>

      {/* 图谱完整性：数的是图谱自己的健康度，不是业务数据 */}
      <CompletenessSection alias={alias} data={completeness.data} />
    </div>
  );
}

/**
 * 图谱完整性看板：覆盖率（表/字段的注释、业务名、语义类型、值域）、待办量
 * （候选待评审、已忽略、未处理校验问题的严重度分布）、关系健康（孤立表、只有外键没有
 * 人工确认业务关联的表）。三处都用同一套 ok/warn/bad 三态色，阈值见 {@link coverageTone}。
 */
export function CompletenessSection({ alias, data }: { alias: string; data?: WorkspaceCompletenessDto }) {
  const backlog = data?.backlog;
  const relations = data?.relations;
  const candidateTotal =
    (backlog?.candidateTables ?? 0) + (backlog?.candidateRelations ?? 0) + (backlog?.candidateTerms ?? 0);
  const ignoredTotal =
    (backlog?.ignoredTables ?? 0) + (backlog?.ignoredRelations ?? 0) + (backlog?.ignoredTerms ?? 0);
  const isolatedTables = relations?.isolatedTables ?? 0;
  const fkOnlyTables = relations?.fkOnlyTables ?? 0;

  return (
    <section className="wb-section">
      <h2 className="wb-section-title">图谱完整性</h2>

      <div className="wb-coverage">
        <CoverageRow label="表注释" covered={data?.tables.withComment} total={data?.tables.total} />
        <CoverageRow label="表业务名" covered={data?.tables.withBusinessName} total={data?.tables.total} />
        <CoverageRow label="字段注释" covered={data?.columns.withComment} total={data?.columns.total} />
        <CoverageRow label="字段业务名" covered={data?.columns.withBusinessName} total={data?.columns.total} />
        <CoverageRow label="字段语义类型" covered={data?.columns.withSemanticType} total={data?.columns.total} />
        <CoverageRow label="字段值域" covered={data?.columns.withValueHints} total={data?.columns.total} />
      </div>

      <div className="wb-metrics">
        <Metric label="候选待评审" value={data && candidateTotal} />
        <Metric label="字段语义待确认" value={data && (backlog?.unverifiedColumnSemantics ?? 0)} />
        <Metric label="已忽略" value={data && ignoredTotal} />
        <Metric label="校验-错误" value={data && (backlog?.openIssuesBySeverity.error ?? 0)} />
        <Metric label="校验-警告" value={data && (backlog?.openIssuesBySeverity.warning ?? 0)} />
        <Metric label="校验-提示" value={data && (backlog?.openIssuesBySeverity.info ?? 0)} />
      </div>

      <div className="wb-cards">
        <Attention
          tone={isolatedTables > 0 ? 'warn' : 'ok'}
          title="孤立表"
          body={
            isolatedTables > 0
              ? `${isolatedTables} 张表没有任何关系（foreign_key / join_observed）`
              : '每张表至少有一条关系'
          }
          action={
            isolatedTables > 0 && (
              <Link className={buttonClass()} to={navPath('knowledge', alias)}>
                去补关系
              </Link>
            )
          }
        />
        <Attention
          tone={fkOnlyTables > 0 ? 'warn' : 'ok'}
          title="业务关联"
          body={
            fkOnlyTables > 0
              ? `${fkOnlyTables} 张表只有数据库外键，没有人工确认过的业务关联`
              : '外键之外的业务关联都补过了'
          }
          action={
            fkOnlyTables > 0 && (
              <Link className={buttonClass()} to={navPath('knowledge', alias)}>
                去补关系
              </Link>
            )
          }
        />
      </div>
    </section>
  );
}

/** 覆盖率三态阈值：≥80% 正常，≥40% 需注意，否则不足；没有分母时状态未知算黄。 */
export function coverageTone(covered?: number, total?: number): Tone {
  if (!total) return 'warn';
  const ratio = (covered ?? 0) / total;
  if (ratio >= 0.8) return 'ok';
  if (ratio >= 0.4) return 'warn';
  return 'bad';
}

function CoverageRow({ label, covered, total }: { label: string; covered?: number; total?: number }) {
  const pct = total ? Math.round(((covered ?? 0) / total) * 100) : 0;
  const tone = coverageTone(covered, total);
  return (
    <div className="wb-coverage-row">
      <StatusDot tone={tone} label={`${label} 覆盖率 ${pct}%`} />
      <span className="wb-coverage-label">{label}</span>
      <span className="wb-coverage-bar">
        <span className="wb-coverage-bar-fill" data-tone={tone} style={{ width: `${pct}%` }} />
      </span>
      <span className="wb-coverage-count">
        {covered ?? 0}/{total ?? 0}（{pct}%）
      </span>
    </div>
  );
}

function Metric({ label, value }: { label: string; value?: number }) {
  return (
    <div className="wb-metric">
      <span className="wb-metric-v">{value ?? '—'}</span>
      <span className="wb-metric-k">{label}</span>
    </div>
  );
}

function Attention({
  tone,
  title,
  body,
  note,
  action,
}: {
  tone: 'ok' | 'warn' | 'bad' | 'info';
  title: string;
  body: string;
  note?: string;
  action?: React.ReactNode;
}) {
  return (
    <article className="wb-card" data-tone={tone}>
      <h3>{title}</h3>
      <p>{body}</p>
      {note && <p className="wb-card-note">{note}</p>}
      {action && <div className="wb-card-action">{action}</div>}
    </article>
  );
}
