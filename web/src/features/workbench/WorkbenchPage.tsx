import { useRef, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Link, useSearchParams } from 'react-router-dom';
import { getAliases } from '../../api/aliases';
import { navPath } from '../../app/navigation';
import { SqlEditor } from './SqlEditor';
import { takeWorkbenchSql } from './workbenchSql';
import './workbench.css';
import { buttonClass } from '../../ui/Button';
import { StatusDot, type Tone } from '../../ui/StatusDot';
import type { WorkspaceCompletenessDto } from '../../types/api';

/**
 * SQL 工作台。
 *
 * 新版不再承担“工作区健康 Dashboard”：索引、校验、审批和完整性都归治理。
 * 这个页面只围绕一个主任务——写 SQL、执行、看结果。
 */
export function WorkbenchPage() {
  const [searchParams] = useSearchParams();
  const alias = searchParams.get('alias');
  return alias ? <WorkspaceBoard alias={alias} /> : <SourceDirectory />;
}

function SourceDirectory() {
  const { data, isLoading, isError } = useQuery({
    queryKey: ['aliases'],
    queryFn: ({ signal }) => getAliases(signal),
    staleTime: 30_000,
  });

  return (
    <div className="page wb wb-source-directory">
      <header className="wb-head wb-directory-head">
        <span className="wb-eyebrow">SQL Workspace</span>
        <h1>选择数据源</h1>
        <p>打开一个连接后直接开始查询；数据模型和治理能力都围绕当前数据源工作。</p>
      </header>

      {isLoading && <p className="wb-hint">正在读取数据源…</p>}
      {isError && (
        <p className="wb-alert" role="alert">
          无法读取数据源列表。确认 <code>sql-cli ui</code> 仍在运行。
        </p>
      )}
      {data?.aliases.length === 0 && (
        <div className="wb-empty">
          <strong>还没有数据源</strong>
          <p className="wb-hint">
            用 <code>sql-cli alias add</code> 添加，或到设置页新建一个连接。
          </p>
          <Link className={buttonClass('primary')} to={navPath('settings', null)}>
            打开设置
          </Link>
        </div>
      )}

      <div className="wb-sources wb-source-grid">
        {data?.aliases.map((item) => (
          <article className="wb-source wb-source-card" key={item.name}>
            <div className="wb-source-top">
              <div className="wb-source-title">
                <span className="wb-source-dot" data-ready={item.graphAvailable || undefined} aria-hidden="true" />
                <h2>{item.name}</h2>
              </div>
              <span className="wb-tag">{item.dbType || 'unknown'}</span>
              {item.readOnly && <span className="wb-tag is-lock">只读</span>}
            </div>
            <p className="wb-source-desc">{item.description || '未填写说明'}</p>
            <p className="wb-source-stat">
              {item.graphAvailable ? (
                <>{item.tables ?? 0} 张表 · {item.relations ?? 0} 条关系</>
              ) : (
                <span className="wb-muted">数据模型未导入</span>
              )}
            </p>
            <div className="wb-source-actions">
              <Link className={buttonClass('primary')} to={navPath('sql', item.name)}>
                打开工作台
              </Link>
              <Link className={buttonClass('ghost')} to={navPath('knowledge', item.name)}>
                数据模型
              </Link>
            </div>
          </article>
        ))}
      </div>
    </div>
  );
}

function WorkspaceBoard({ alias }: { alias: string }) {
  const [sql, setSql] = useState(() => takeWorkbenchSql() ?? '');
  const editorRef = useRef<HTMLTextAreaElement>(null);

  const aliases = useQuery({
    queryKey: ['aliases'],
    queryFn: ({ signal }) => getAliases(signal),
    staleTime: 30_000,
  });
  const current = aliases.data?.aliases.find((item) => item.name === alias);

  return (
    <div className="page wb wb-workspace-redesign">
      <header className="wb-head wb-workspace-head">
        <div className="wb-workspace-title">
          <span className="wb-eyebrow">SQL Workspace</span>
          <div className="wb-title-line">
            <h1>{alias}</h1>
            {current?.readOnly && <span className="wb-tag is-lock">只读</span>}
          </div>
          <p>{current?.description || '编写 SQL、执行并查看结果。'}</p>
        </div>

        <div className="wb-head-actions">
          <Link className={buttonClass('ghost')} to={navPath('knowledge', alias)}>
            数据模型
          </Link>
          <Link
            className={buttonClass('ghost')}
            to={navPath('governance', alias, { section: 'reviews' })}
          >
            审批与审计
          </Link>
        </div>
      </header>

      {aliases.isError && (
        <p className="wb-alert" role="alert">无法读取数据源信息。</p>
      )}

      {current && !current.graphAvailable && (
        <div className="wb-context-notice">
          <div>
            <strong>SQL 可以直接使用</strong>
            <span>当前还没有数据模型，因此表/字段补全和语义上下文会比较有限。</span>
          </div>
          <Link className={buttonClass()} to={navPath('knowledge', alias)}>
            导入数据模型
          </Link>
        </div>
      )}

      <SqlEditor
        alias={alias}
        aliasInfo={current}
        sql={sql}
        onSqlChange={setSql}
        textareaRef={editorRef}
      />

      <footer className="wb-workspace-foot">
        <span><kbd>Ctrl</kbd> + <kbd>Enter</kbd> 执行</span>
        <span><kbd>Ctrl</kbd> + <kbd>Space</kbd> 补全</span>
        <Link to={navPath('governance', alias)}>查看工作区健康度</Link>
      </footer>
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
