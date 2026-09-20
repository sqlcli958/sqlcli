import { useMemo, useRef, useState } from 'react';
import { useMutation, useQueries, useQuery } from '@tanstack/react-query';
import { Link } from 'react-router-dom';
import { cancelWorkbenchSql, executeWorkbenchSql } from '../../api/workbench';
import { getTableDetail, getTables } from '../../api/workspace';
import { navPath } from '../../app/navigation';
import type { AliasSummaryDto, PrecheckDto, WorkbenchExecuteResultDto } from '../../types/api';
import { ResultTable } from '../sql-result/ResultTable';
import { isWriteStatement } from './workbenchSql';
import { caretCoords, type CaretPoint } from './caretCoords';
import {
  collectTableRefs,
  rank,
  readContext,
  readContextForced,
  resolveQualifier,
  type CompletionContext,
  type CompletionItem,
} from './completion';
import { Button } from '../../ui/Button';
import { StatusDot } from '../../ui/StatusDot';

/**
 * SQL 编辑器。纯 textarea，不引 Monaco（清单「明确不做」）。
 *
 * **写语句的两步走**：点按钮先发 `dryRun` 拿预检（目标表、预估影响行数、能不能生成
 * 恢复 SQL），把它摆成确认面板，用户再点一次才真跑。这一步不是仪式——预检信息就是
 * 决策依据，没有它「确认执行」和直接执行没有区别。
 *
 * 读写判断在客户端只决定按钮文案和走不走确认面板；**真正的强制全在服务端**
 * （readonly 别名、Yearning 只读、UPDATE/DELETE 必须带 WHERE、审批闸门），
 * 判错了最坏是文案不准。
 */
export function SqlEditor({
  alias,
  aliasInfo,
  sql,
  onSqlChange,
  textareaRef,
}: {
  alias: string;
  aliasInfo?: AliasSummaryDto;
  sql: string;
  onSqlChange: (sql: string) => void;
  textareaRef: React.RefObject<HTMLTextAreaElement>;
}) {
  // 每次真执行生成一个新令牌随请求带上，服务端把「怎么中止」注册在它名下；
  // 「中止」按钮拿同一个令牌去调 /workbench/cancel。dry-run 是一次快查，不给中止。
  const cancelTokenRef = useRef<string>();
  const [aborted, setAborted] = useState(false);

  const dry = useMutation({ mutationFn: (text: string) => executeWorkbenchSql(text, true) });
  const run = useMutation({
    mutationFn: (text: string) => executeWorkbenchSql(text, false, cancelTokenRef.current),
  });

  // 表名补全：图谱没导入时 getTables 会 404，query 静默失败即可——补全就是没有候选。
  const tablesQuery = useQuery({
    queryKey: ['workbench-tables', alias],
    queryFn: () => getTables(undefined, 0, 500),
    staleTime: 5 * 60_000,
    retry: false,
  });
  const tables = useMemo<CompletionItem[]>(
    () => (tablesQuery.data?.items ?? []).map((t) => ({
      kind: 'table' as const,
      text: t.qualifiedName,
      label: t.qualifiedName,
      detail: t.description || t.comment || undefined,
    })),
    [tablesQuery.data],
  );

  // 语句里引用到的表，列补全只查这几张——整库拉字段既慢又没必要。
  const refs = useMemo(() => collectTableRefs(sql), [sql]);
  const refNames = useMemo(
    () => [...new Set(refs.map((ref) => ref.table))].slice(0, 6),
    [refs],
  );
  const details = useQueries({
    queries: refNames.map((name) => ({
      queryKey: ['workbench-columns', alias, name],
      queryFn: () => getTableDetail(name),
      staleTime: 5 * 60_000,
      retry: false,
    })),
  });

  /** 表限定名 → 该表的列候选。查不到（表名写错、图谱没这张表）就是空。 */
  const columnsByTable = useMemo(() => {
    const map = new Map<string, CompletionItem[]>();
    details.forEach((detail, index) => {
      const columns = detail.data?.columns;
      if (!columns) return;
      map.set(refNames[index], columns.map((column) => ({
        kind: 'column' as const,
        text: column.name,
        label: column.name,
        // 业务名比类型有用得多，有就先给它——图谱里存的这层含义是 IDEA 给不了的；
        // 没业务名退到业务描述，都没有才用数据库注释兜底
        detail: [column.businessName || column.description || column.comment, column.dataType?.raw]
          .filter(Boolean).join(' · ') || undefined,
      })));
    });
    return map;
  }, [details, refNames]);

  const [suggestions, setSuggestions] = useState<CompletionItem[]>([]);
  const [activeIndex, setActiveIndex] = useState(0);
  const [caret, setCaret] = useState<CaretPoint | null>(null);
  const contextRef = useRef<CompletionContext | null>(null);

  /** 候选池：限定符优先（`o.` 只给那张表的列），否则按上下文给表或列。 */
  function candidatesFor(context: CompletionContext): CompletionItem[] {
    if (context.qualifier) {
      const target = resolveQualifier(context.qualifier, refs);
      if (target) return columnsByTable.get(target) ?? [];
      // 限定符不是别名也不是引用过的表 → 当 schema 前缀，补这个 schema 下的表
      const schema = `${context.qualifier.toLowerCase()}.`;
      return tables.filter((item) => item.text.toLowerCase().startsWith(schema));
    }
    const inScope = refNames.flatMap((name) => columnsByTable.get(name) ?? []);
    if (context.expect === 'table') return tables;
    if (context.expect === 'column') return inScope.length > 0 ? [...inScope, ...tables] : tables;
    return [...inScope, ...tables];
  }

  function updateSuggestions(forced = false) {
    const el = textareaRef.current;
    if (!el) return;
    const cursor = el.selectionStart;
    const context = forced ? readContextForced(el.value, cursor) : readContext(el.value, cursor);
    if (!context) {
      setSuggestions([]);
      return;
    }
    const matches = rank(candidatesFor(context), context.prefix);
    // 唯一候选且已经打完整了就别挡着——这时候提示没有信息量
    const settled = matches.length === 1 && matches[0].text.toLowerCase() === context.prefix.toLowerCase();
    contextRef.current = context;
    setActiveIndex(0);
    setCaret(matches.length > 0 && !settled ? caretCoords(el, context.start) : null);
    setSuggestions(matches.length > 0 && !settled ? matches : []);
  }

  function applySuggestion(item: CompletionItem) {
    const context = contextRef.current;
    const el = textareaRef.current;
    if (!context || !el) return;
    // 限定符原样保留，只替换点后面那截：`o.na` → `o.name`，不是 `name`
    const inserted = context.qualifier ? `${context.qualifier}.${item.text}` : item.text;
    const next = sql.slice(0, context.start) + inserted + sql.slice(context.end);
    onSqlChange(next);
    setSuggestions([]);
    requestAnimationFrame(() => {
      const pos = context.start + inserted.length;
      el.focus();
      el.setSelectionRange(pos, pos);
    });
  }

  const write = isWriteStatement(sql);
  const busy = dry.isPending || run.isPending;
  const empty = sql.trim().length === 0;
  // 审批开着时这次执行会挂在评审页等裁决，按钮文案要在点之前就说清楚。
  const needsApproval = write ? aliasInfo?.approveUpdate : aliasInfo?.approveQuery;
  const confirming = dry.data != null && dry.data.status === 'SUCCEEDED';

  function reset() {
    dry.reset();
    run.reset();
    setAborted(false);
  }

  function execute(text: string) {
    cancelTokenRef.current = crypto.randomUUID();
    setAborted(false);
    run.mutate(text);
  }

  function submit() {
    if (empty || busy) return;
    reset();
    if (write) dry.mutate(sql);
    else execute(sql);
  }

  /** cancelled=false 表示执行其实已经结束，等结果照常展示即可，不标「已中止」。 */
  async function abort() {
    const token = cancelTokenRef.current;
    if (!token) return;
    try {
      const { cancelled } = await cancelWorkbenchSql(token);
      if (cancelled) setAborted(true);
    } catch {
      // 中止请求本身失败：什么都不改，执行结果稍后自己回来。
    }
  }

  const label = busy ? '执行中…' : needsApproval ? '提交审批' : '执行';

  return (
    <section className="wb-section wb-editor">
      <div className="wb-section-head">
        <h2 className="wb-section-title">SQL</h2>
        <span className="wb-muted">
          {aliasInfo?.readOnly ? '只读数据源，写语句会被拒绝' : 'Ctrl + Enter 执行'}
        </span>
      </div>

      <div className="wb-sql-wrap">
        <textarea
          ref={textareaRef}
          className="wb-sql"
          spellCheck={false}
          rows={7}
          value={sql}
          placeholder={`SELECT * FROM ...\n单条语句，Ctrl + Enter 执行`}
          onChange={(e) => {
            onSqlChange(e.target.value);
            if (confirming) reset();
            updateSuggestions();
          }}
          onKeyUp={(e) => {
            // 方向键/回车已经在 onKeyDown 里处理过补全导航，这里只处理移动光标的按键
            if (['ArrowLeft', 'ArrowRight', 'Home', 'End'].includes(e.key)) updateSuggestions();
          }}
          onBlur={() => setSuggestions([])}
          aria-autocomplete="list"
          aria-expanded={suggestions.length > 0}
          aria-controls="wb-autocomplete"
          aria-activedescendant={suggestions.length > 0 ? `wb-suggestion-${activeIndex}` : undefined}
          onKeyDown={(e) => {
            // Ctrl/Cmd + Space 手动唤出——光标停在空白处时自动触发不了，
            // 而"我想看看这里能填什么"恰恰是最需要提示的时刻（IDEA 的 Ctrl+Space）
            if ((e.ctrlKey || e.metaKey) && e.key === ' ') {
              e.preventDefault();
              updateSuggestions(true);
              return;
            }
            if (suggestions.length > 0) {
              if (e.key === 'ArrowDown') {
                e.preventDefault();
                setActiveIndex((i) => (i + 1) % suggestions.length);
                return;
              }
              if (e.key === 'ArrowUp') {
                e.preventDefault();
                setActiveIndex((i) => (i - 1 + suggestions.length) % suggestions.length);
                return;
              }
              if (e.key === 'Enter' || e.key === 'Tab') {
                e.preventDefault();
                applySuggestion(suggestions[activeIndex]);
                return;
              }
              if (e.key === 'Escape') {
                setSuggestions([]);
                return;
              }
            }
            if ((e.ctrlKey || e.metaKey) && e.key === 'Enter') {
              e.preventDefault();
              submit();
            }
          }}
        />
        {suggestions.length > 0 && caret && (
          <ul
            className="wb-autocomplete"
            role="listbox"
            id="wb-autocomplete"
            aria-label="补全候选"
            /* 跟着插入点走，不是钉在编辑器下沿——差别就是"像个补全"和"像个下拉框" */
            style={{ left: caret.left, top: caret.top + caret.lineHeight }}
          >
            {suggestions.map((item, i) => (
              <li
                key={`${item.kind}:${item.text}`}
                id={`wb-suggestion-${i}`}
                role="option"
                aria-selected={i === activeIndex}
                className={i === activeIndex ? 'is-active' : undefined}
                onMouseDown={(e) => {
                  e.preventDefault();
                  applySuggestion(item);
                }}
              >
                <span className="wb-ac-kind" aria-hidden="true">{item.kind === 'table' ? '表' : '列'}</span>
                <span className="wb-ac-name">{item.label}</span>
                {item.detail && <span className="wb-ac-detail">{item.detail}</span>}
              </li>
            ))}
          </ul>
        )}
      </div>

      <div className="wb-editor-bar">
        <Button
          disabled={empty || busy}
          title={
            write
              ? '写语句先做预检，确认后才真正执行'
              : needsApproval ? '这个数据源的查询已开审批，执行前需要放行' : undefined
          }
          onClick={submit}
          variant="primary"
        >
          {label}
        </Button>
        {run.isPending && (
          <Button
            title="中止本次执行：语句执行中调 Statement.cancel()，审批等待中会作废该审批"
            onClick={abort}
            variant="danger"
            size="sm"
          >
            中止
          </Button>
        )}
        {sql.length > 0 && (
          <Button
            disabled={busy}
            onClick={() => {
              onSqlChange('');
              reset();
            }}
            variant="ghost"
            size="sm"
          >
            清空
          </Button>
        )}
        {write && <span className="wb-muted">写语句</span>}
      </div>

      {run.isPending && needsApproval && (
        <p className="wb-approval" role="status">
          等待审批中，请到评审页放行。
          <Link className="wb-link" to={navPath('governance', alias, { section: 'reviews' })}>去评审</Link>
        </p>
      )}

      {confirming && !run.isPending && !run.data && (
        <ConfirmPanel
          precheck={dry.data!.precheck}
          sqlType={dry.data!.sqlType}
          submitting={run.isPending}
          onCancel={reset}
          onConfirm={() => execute(sql)}
        />
      )}

      {dry.isError && <p className="wb-alert" role="alert">预检失败：{dry.error.message}</p>}
      {run.isError && !aborted && <p className="wb-alert" role="alert">执行失败：{run.error.message}</p>}
      {dry.data && dry.data.status !== 'SUCCEEDED' && <Outcome result={dry.data} />}
      {/* 用户主动中止后，被打断的执行回来是 FAILED/REJECTED——那是中止的结果，不再当失败展示 */}
      {aborted && !run.isPending && (run.data != null || run.isError) && (
        <p className="wb-hint" role="status">已中止。</p>
      )}
      {run.data && !aborted && <Outcome result={run.data} />}
    </section>
  );
}

/** 写操作的确认面板。预检拿不到的部分照实说「未知」，不编一个数字。 */
function ConfirmPanel({
  precheck,
  sqlType,
  submitting,
  onCancel,
  onConfirm,
}: {
  precheck: PrecheckDto | null;
  sqlType: string | null;
  submitting: boolean;
  onCancel: () => void;
  onConfirm: () => void;
}) {
  const recoverable = precheck?.recoverySupported ?? false;
  return (
    <div className="wb-confirm">
      <h3>确认执行 {sqlType}</h3>
      <dl className="wb-confirm-facts">
        <div>
          <dt>目标表</dt>
          <dd>{precheck?.table || '未知'}</dd>
        </div>
        <div>
          <dt>预估影响</dt>
          <dd>{precheck?.estimatedRows == null ? '未知' : `${precheck.estimatedRows} 行`}</dd>
        </div>
        <div>
          <dt>可恢复</dt>
          <dd>
            <StatusDot
              tone={recoverable ? 'ok' : 'warn'}
              label={recoverable ? '执行前会自动生成恢复 SQL' : '不会生成恢复 SQL，执行后无法一键回滚'}
            />
            {recoverable ? '会生成恢复 SQL' : '不会生成恢复 SQL'}
          </dd>
        </div>
        <div>
          <dt>主键</dt>
          <dd>{precheck?.primaryKey?.length ? precheck.primaryKey.join(', ') : '无'}</dd>
        </div>
      </dl>
      {precheck?.skippedReason && (
        <p className="wb-muted">预检未完成：{precheck.skippedReason}</p>
      )}
      <div className="wb-editor-bar">
        <Button disabled={submitting} onClick={onConfirm} variant="danger">
          确认执行
        </Button>
        <Button disabled={submitting} onClick={onCancel} variant="ghost">
          取消
        </Button>
      </div>
    </div>
  );
}

/** 一次执行的结果：拒绝、失败、写操作回执或结果表格。规则提示（advisory）挂在最上面，不改变结果本身。 */
function Outcome({ result }: { result: WorkbenchExecuteResultDto }) {
  const notices = result.notices ?? [];
  return (
    <>
      {notices.length > 0 && (
        <ul className="wb-notices" role="status" aria-label="规则提示">
          {notices.map((notice) => <li key={notice}>{notice}</li>)}
        </ul>
      )}
      <OutcomeBody result={result} />
    </>
  );
}

function OutcomeBody({ result }: { result: WorkbenchExecuteResultDto }) {
  if (result.status === 'REJECTED') {
    return (
      <p className="wb-alert" role="alert">已拒绝：{result.errorSummary || '未说明原因'}</p>
    );
  }
  if (result.status === 'FAILED') {
    return (
      <p className="wb-alert" role="alert">执行失败：{result.errorSummary || '未说明原因'}</p>
    );
  }
  if (result.columns.length === 0 && result.affectedRows != null) {
    return (
      <p className="wb-hint" role="status">
        {result.sqlType} 完成，影响 {result.affectedRows} 行 · {result.elapsedMs} ms
        {result.recoveryId != null && <> · 已生成回滚 SQL，在评审页的执行记录里可查看</>}
      </p>
    );
  }
  return <ResultTable result={result} exportName={`${(result.sqlType || 'result').toLowerCase()}.csv`} />;
}
