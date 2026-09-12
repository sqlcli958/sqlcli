import { useState } from 'react';
import { useMutation, useQuery } from '@tanstack/react-query';
import { Link } from 'react-router-dom';
import {
  executeRollback,
  getExecutionHistory,
  getRecoveryPreview,
  type ExecutionFilters,
} from '../../api/executions';
import { navPath } from '../../app/navigation';
import { Button } from '../../ui/Button';
import { FilterBar } from '../../ui/FilterBar';
import { Pagination } from '../../ui/Pagination';
import { Select } from '../../ui/Select';
import { StatusDot } from '../../ui/StatusDot';
import type { SqlExecutionRecordDto } from '../../types/api';
import { RerunButton } from '../sql-result/RerunButton';
import { formatSql } from './formatSql';
import { TIME_RANGES, rangeMs, since } from './timeRange';
import './executions.css';

/** 绿=成功，黄=被规则拦下（可修改后重来），红=真的失败了。 */
const STATUS_TONE: Record<string, 'ok' | 'warn' | 'bad'> = {
  success: 'ok',
  rejected: 'warn',
  failed: 'bad',
};

const STATUS_LABEL: Record<string, string> = {
  success: '成功',
  rejected: '被拒绝',
  failed: '失败',
};

/** 语句类型筛选项。写全没有意义——历史里出现的就这些。 */
const TYPES = ['SELECT', 'INSERT', 'UPDATE', 'DELETE', 'MERGE', 'ROLLBACK', 'CREATE', 'ALTER', 'DROP'];

/** 增删改才有回滚脚本；其余语句给重放按钮。 */
export function isWrite(sqlType?: string): boolean {
  return ['INSERT', 'UPDATE', 'DELETE', 'MERGE'].includes((sqlType ?? '').toUpperCase());
}

/**
 * 执行记录。原先在工作台，挪到评审页——它和审批是同一件事的两半：
 * 审批看"要不要放行"，执行记录看"放行之后发生了什么"。
 *
 * 每条记录点开是同一个面板：完整 SQL（格式化后）、失败原因、写语句的回滚脚本。
 * 失败的语句照样在列表里，点开就能看到原因——不然只能回终端翻日志。
 */
export function ExecutionLog({ alias }: { alias: string | null }) {
  const [page, setPage] = useState(0);
  const [pageSize, setPageSize] = useState(20);
  const [schema, setSchema] = useState('');
  const [type, setType] = useState('');
  const [status, setStatus] = useState('');
  const [range, setRange] = useState('all');

  const log = useQuery({
    queryKey: ['executions', alias, page, pageSize, schema, type, status, range],
    queryFn: ({ signal }) => {
      const filters: ExecutionFilters = {
        schema: schema || undefined,
        type: type || undefined,
        status: status || undefined,
        startedAfter: since(range),
      };
      return getExecutionHistory(alias ?? '', page, pageSize, filters, signal);
    },
    placeholderData: (prev) => prev,
  });

  const records = log.data?.records ?? [];
  const schemas = log.data?.schemas ?? [];
  const filtered = Boolean(schema || type || status || rangeMs(range));

  /** 改筛选条件要回到第一页，否则会停在一个新结果集里不存在的页码上。 */
  function change(setter: (value: string) => void) {
    return (event: React.ChangeEvent<HTMLSelectElement>) => {
      setter(event.target.value);
      setPage(0);
    };
  }

  return (
    <>
      <FilterBar>
        <Select size="sm" value={schema} onChange={change(setSchema)} label="按 schema 筛选" title="按 schema 筛选">
          <option value="">全部 schema</option>
          {schemas.map((item) => (
            <option key={item} value={item}>{item}</option>
          ))}
        </Select>
        <Select size="sm" value={type} onChange={change(setType)} label="按语句类型筛选" title="按语句类型筛选">
          <option value="">全部语句类型</option>
          {TYPES.map((item) => (
            <option key={item} value={item}>{item}</option>
          ))}
        </Select>
        <Select size="sm" value={status} onChange={change(setStatus)} label="按执行状态筛选" title="按执行状态筛选">
          <option value="">全部状态</option>
          <option value="success">成功</option>
          <option value="failed">失败</option>
          <option value="rejected">被拒绝</option>
        </Select>
        <Select size="sm" value={range} onChange={change(setRange)} label="按时间范围筛选" title="按时间范围筛选">
          {TIME_RANGES.map((item) => (
            <option key={item.key} value={item.key}>{item.label}</option>
          ))}
        </Select>
        {filtered && (
          <Button
            variant="ghost"
            size="sm"
            onClick={() => {
              setSchema('');
              setType('');
              setStatus('');
              setRange('all');
              setPage(0);
            }}
          >
            清除筛选
          </Button>
        )}
        <Pagination
          page={page}
          pageSize={pageSize}
          total={log.data?.total ?? 0}
          onPage={setPage}
          onPageSize={(size) => {
            setPageSize(size);
            setPage(0);
          }}
        />
      </FilterBar>

      {log.isPending && <p className="review-hint">加载中…</p>}
      {log.isError && (
        <p className="review-alert" role="alert">加载失败：{log.error.message}</p>
      )}
      {log.isSuccess && records.length === 0 && (
        <p className="review-hint">{filtered ? '没有符合条件的记录。' : '暂无执行记录。'}</p>
      )}

      {records.length > 0 && (
        <ul className="exec-list" data-stale={log.isPlaceholderData || undefined}>
          {records.map((record) => (
            <ExecutionRow key={record.id} record={record} />
          ))}
        </ul>
      )}
    </>
  );
}

function ExecutionRow({ record }: { record: SqlExecutionRecordDto }) {
  const [open, setOpen] = useState(false);
  const tone = STATUS_TONE[record.status ?? ''] ?? 'warn';
  const raw = record.rawSql ?? null;

  return (
    <li className="exec-row" data-open={open || undefined}>
      <StatusDot tone={tone} />
      <time dateTime={new Date(record.startedAt).toISOString()}>
        {new Date(record.startedAt).toLocaleString()}
      </time>
      <span className="exec-type">{record.sqlType ?? '—'}</span>
      {/* SQL 本身就是展开开关：一行放不下的语句点开看全貌 */}
      <button
        type="button"
        className="exec-sql"
        aria-expanded={open}
        title={open ? '收起' : '展开完整 SQL'}
        onClick={() => setOpen(!open)}
      >
        <code>{record.sql}</code>
      </button>
      <span className="exec-rows">
        {record.affectedRows == null ? '' : `${record.affectedRows} 行`}
      </span>
      <span className="exec-ms">{record.elapsedMs} ms</span>
      {isWrite(record.sqlType) ? <RollbackButton record={record} /> : <RerunButton record={record} />}

      {open && (
        <div className="exec-detail">
          <h4>
            完整 SQL
            {!raw && <small>历史未保存原文，下面是脱敏文本</small>}
          </h4>
          <pre>{formatSql(raw ?? record.sql)}</pre>

          {record.errorSummary && (
            <>
              <h4>
                {record.status === 'rejected' ? '拒绝原因' : '失败原因'}
              </h4>
              <pre className="exec-error">{record.errorSummary}</pre>
            </>
          )}

          <dl className="exec-facts">
            {/* 回滚预览里写的是「运行库 #id」，不显示编号就对不上号 */}
            <dt>记录编号</dt>
            <dd>#{record.id}</dd>
            <dt>数据源</dt>
            <dd>{record.alias}</dd>
            <dt>schema</dt>
            <dd>{record.targetSchema ?? '未标注'}</dd>
            <dt>状态</dt>
            <dd>{STATUS_LABEL[record.status ?? ''] ?? record.status ?? '未知'}</dd>
            <dt>影响行数</dt>
            <dd>{record.affectedRows ?? '—'}</dd>
          </dl>
        </div>
      )}
    </li>
  );
}

/**
 * 回滚与备份预览。
 *
 * 展开只看不执行——回滚本身也是写操作，误点一下按钮很容易。真要执行点「提交回滚」，
 * 需要再点一次「确认」才发请求。
 *
 * **提交不等于执行**：请求只是排一条 kind=recovery 待审批（别名没有对应开关，
 * 审批无条件触发），立刻返回；真正执行发生在有人在待审批标签批准的那一刻。
 * 原来是挂在这里等——按钮一直转，而人得切到另一个标签去批准自己刚点的东西。
 */
function RollbackButton({ record }: { record: SqlExecutionRecordDto }) {
  const [open, setOpen] = useState(false);
  const [confirming, setConfirming] = useState(false);
  const preview = useQuery({
    queryKey: ['recovery', record.id],
    queryFn: ({ signal }) => getRecoveryPreview(record.id, signal),
    enabled: open,
    retry: false,
  });
  const run = useMutation({ mutationFn: () => executeRollback(record.id) });

  return (
    <>
      <Button
        size="sm"
        aria-expanded={open}
        title="查看这条写操作的回滚 SQL 与执行前的原始行"
        onClick={() => setOpen(!open)}
      >
        {open ? '收起' : '回滚'}
      </Button>
      {open && (
        <div className="exec-recovery">
          {preview.isPending && <p className="review-hint">读取回滚脚本…</p>}
          {preview.isError && (
            <p className="review-alert" role="alert">
              没有可用的回滚脚本：{preview.error.message}
            </p>
          )}
          {preview.isSuccess && (
            <>
              <h4>回滚 SQL</h4>
              <pre>{preview.data.rollback || '（这条语句没有生成回滚 SQL）'}</pre>
              <h4>原始记录<small>执行前的那几行</small></h4>
              <pre>{preview.data.backup || '（没有记录原始行）'}</pre>

              {run.isSuccess ? (
                <p className="review-hint" role="status">
                  已提交审批 #{run.data.approvalId}，共 {run.data.statements} 条语句；
                  批准后才执行。
                  <Link className="exec-link" to={navPath('reviews', record.alias)}>
                    去待审批裁决
                  </Link>
                </p>
              ) : run.isError ? (
                <p className="review-alert" role="alert">提交失败：{run.error.message}</p>
              ) : confirming ? (
                <div className="exec-confirm">
                  <Button
                    variant="danger"
                    size="sm"
                    disabled={run.isPending}
                    onClick={() => run.mutate()}
                  >
                    {run.isPending ? '提交中…' : '确认提交'}
                  </Button>
                  <Button
                    variant="ghost"
                    size="sm"
                    disabled={run.isPending}
                    onClick={() => setConfirming(false)}
                  >
                    取消
                  </Button>
                </div>
              ) : (
                <Button
                  variant="danger"
                  size="sm"
                  title="提交到审批中心，批准后才真正执行"
                  onClick={() => setConfirming(true)}
                >
                  提交回滚
                </Button>
              )}
            </>
          )}
        </div>
      )}
    </>
  );
}
