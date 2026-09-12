/**
 * 评估页。
 *
 * 四块自上而下回答同一串问题：**现在怎么样**（KPI）→ **比上次好了还是坏了**（趋势）
 * → **坏在哪一类**（探针分布）→ **该先修哪条**（工作队列）。
 *
 * 页面上有「跑一次评估」。这里原来写着「只读，没有跑一次按钮，两条产出路径的结果
 * 迟早对不上」——那条担心针对的是**两套实现**，不是两个入口：按钮调
 * `POST /api/eval/run`，后端走的是 CLI 同一个 `GraphEvalRunner`，同一个评估器、
 * 同一张表、同一个 source，算不出两个结果。回归见 `RunEvalFromUiTest`。
 *
 * 评估仍然只有一个产出实现——**不许**在这里另写一段算覆盖率的代码。
 */
import { useEffect, useState } from 'react';
import { useMutation, useQuery } from '@tanstack/react-query';
import { useSearchParams } from 'react-router-dom';
import { getEvaluations, getFindings, runEval, type EvaluationDto, type FindingDto } from '../../api/eval';
import { queryClient } from '../../api/queryClient';
import { Button } from '../../ui/Button';
import { FilterBar } from '../../ui/FilterBar';
import { Pagination } from '../../ui/Pagination';
import { Select } from '../../ui/Select';
import { StatusDot } from '../../ui/StatusDot';
import { ProbeBars, Sparkline, TrendChart, comparablePairs, percent, seriesColor, type ProbeCount } from './charts';
import './eval-page.css';

/** 指标中文名。**没收录的键照原样显示**——后端还在加探针，写死一份清单会让新指标消失。 */
const METRIC_LABELS: Record<string, string> = {
  tableDescription: '表描述覆盖',
  columnDescription: '字段描述覆盖',
  columnComment: '字段注释（库自带）',
  columnValueDomain: '字段值域覆盖',
  termMapping: '术语映射覆盖',
};

/**
 * 这个指标是**从数据库注释导入的镜像**，不是有人整理出来的进度。
 * 它跟另外四条画在一起会把趋势读反——库里注释本来就多的时候，那条线一开始就很高，
 * 看着像「已经整理得差不多了」。所以它只留一个 KPI tile，不进趋势折线。
 */
const MIRROR_KEY = 'columnComment';

function labelOf(key: string): string {
  return METRIC_LABELS[key] ?? key;
}

function runLabel(run: EvaluationDto): string {
  const at = new Date(run.startedAt);
  const time = `${at.getMonth() + 1}/${at.getDate()} ${String(at.getHours()).padStart(2, '0')}:${String(at.getMinutes()).padStart(2, '0')}`;
  return `${time} · revision ${run.revision} · ${run.errorCount} 条硬错误`;
}

const COPY_ICON = 'M9 3h9a2 2 0 012 2v10h-2V5H9V3zM5 7h9a2 2 0 012 2v10a2 2 0 01-2 2H5a2 2 0 01-2-2V9a2 2 0 012-2z';

export function EvalPage() {
  const [params] = useSearchParams();
  const alias = params.get('alias');

  const [runId, setRunId] = useState<string | null>(null);
  const [page, setPage] = useState(0);
  const [pageSize, setPageSize] = useState(20);
  const [copied, setCopied] = useState<{ seq: number; ok: boolean } | null>(null);

  const runsQuery = useQuery({
    queryKey: ['eval-runs', alias],
    queryFn: ({ signal }) => getEvaluations(alias!, 20, signal),
    enabled: !!alias,
  });

  // 跑完把列表和 findings 都置为过期：新评估是最近一次，`current` 会自动切到它
  const run = useMutation({
    mutationFn: () => runEval(alias!),
    onSuccess: (result) => {
      setRunId(result.id);
      queryClient.invalidateQueries({ queryKey: ['eval-runs', alias] });
    },
  });

  // **只取 source=eval 的点**：value-domain 那一类只落 value.unused，
  // 硬错误数会凭空掉到 0、metrics 是 null，混进趋势里就是一条假的「修好了」。
  const runs = (runsQuery.data ?? []).filter((run) => run.source === 'eval');
  const current = runs.find((run) => run.id === runId) ?? runs[0] ?? null;

  useEffect(() => setPage(0), [current?.id]);

  const findings = useQuery({
    queryKey: ['eval-findings', current?.id, page, pageSize],
    queryFn: ({ signal }) => getFindings(current!.id, page + 1, pageSize, signal),
    enabled: !!current,
    placeholderData: (previous) => previous,
  });

  // 探针分布要的是整次评估的构成，不是当前这一页。
  // ponytail: 一次拉 500 条封顶，超出的不进分布图；真出现上千条 finding 的评估
  // 再让后端加一个 group-by-probe 的聚合端点。
  const all = useQuery({
    queryKey: ['eval-findings-all', current?.id],
    queryFn: ({ signal }) => getFindings(current!.id, 1, 500, signal),
    enabled: !!current,
  });

  if (!alias) return <div className="eval-page"><p className="eval-empty">请先在顶栏选择数据源。</p></div>;

  if (runsQuery.isError) {
    return <div className="eval-page">
      <p className="eval-empty" role="alert">无法读取评估结果：{runsQuery.error.message}</p>
    </div>;
  }
  if (runsQuery.isLoading) return <div className="eval-page"><p className="eval-empty">加载中…</p></div>;

  if (!current) {
    return <div className="eval-page">
      <header className="eval-head">
        <h1>评估</h1>
        <Button size="sm" onClick={() => run.mutate()} disabled={run.isPending}>
          {run.isPending ? '评估中…' : '跑一次评估'}
        </Button>
      </header>
      <p className="eval-empty">
        这个数据源还没跑过评估。点上面的按钮，或在命令行里跑：
        <code className="eval-cmd">sql-cli {alias} schema eval</code>
      </p>
      {run.isError && <p className="eval-empty" role="alert">评估失败：{run.error.message}</p>}
    </div>;
  }

  // 趋势按时间正序；后端给的是倒序
  const trend = runs.filter((run) => run.metrics).reverse()
    .map((run) => ({ at: run.startedAt, values: run.metrics! }));
  // 指标键动态取：以最近一次为准，历史上出现过、现在没了的排在后面
  const keys = [
    ...Object.keys(current.metrics ?? {}),
    ...trend.flatMap((point) => Object.keys(point.values)),
  ].filter((key, i, list) => list.indexOf(key) === i);
  const trendKeys = keys.filter((key) => key !== MIRROR_KEY);
  // 同一个指标在 KPI sparkline 和趋势线里必须同色，否则两块图对不上
  const colorOf = (key: string) =>
    (key === MIRROR_KEY ? 'var(--color-text-dim)' : seriesColor(trendKeys.indexOf(key)));
  const errorTrend = runs.map((run) => run.errorCount).reverse();

  const probes = Object.values(
    (all.data?.findings ?? []).reduce<Record<string, ProbeCount>>((acc, finding) => {
      const row = acc[finding.probe]
        ?? (acc[finding.probe] = { probe: finding.probe, severity: finding.severity, count: 0 });
      row.count += 1;
      return acc;
    }, {}),
  ).sort((a, b) => (a.severity === b.severity ? b.count - a.count : a.severity === 'error' ? -1 : 1));

  /**
   * 复制失败要说出来。`writeText` 在文档没有焦点、或浏览器拒了剪贴板权限时会 reject，
   * 不接住的话按钮点下去什么都不发生——人会以为复制成功了，粘出来是上一次的东西。
   */
  async function copyRemediation(finding: FindingDto) {
    if (!finding.remediation) return;
    const ok = await navigator.clipboard.writeText(finding.remediation).then(() => true, () => false);
    setCopied({ seq: finding.seq, ok });
    setTimeout(() => setCopied((current) => (current?.seq === finding.seq ? null : current)), 1500);
  }

  function copyLabel(finding: FindingDto): string {
    if (copied?.seq !== finding.seq) return '复制修复命令';
    return copied.ok ? '已复制' : '复制失败，请手动选中命令';
  }

  return (
    <div className="eval-page">
      <header className="eval-head">
        <h1>评估</h1>
        <Select
          size="sm"
          value={current.id}
          label="选择评估批次"
          title="切换到历史上的某一次评估"
          onChange={(event) => setRunId(event.target.value)}
        >
          {runs.map((run) => <option key={run.id} value={run.id}>{runLabel(run)}</option>)}
        </Select>
        {current.elapsedMs != null && <span className="eval-meta">耗时 {current.elapsedMs} ms</span>}
        <Button size="sm" variant="ghost" onClick={() => run.mutate()} disabled={run.isPending}
          title="按当前图谱再算一次；跟 sql-cli <alias> schema eval 是同一个评估器">
          {run.isPending ? '评估中…' : '跑一次评估'}
        </Button>
        {run.isError && <span className="eval-meta" role="alert">评估失败：{run.error.message}</span>}
      </header>

      <ul className="eval-kpis">
        <li className="eval-kpi">
          <span className="eval-kpi-name">
            <StatusDot tone={current.errorCount > 0 ? 'bad' : 'ok'}
              label={current.errorCount > 0 ? '评估未通过' : '评估通过'} />
            硬错误数
          </span>
          <strong className="eval-kpi-value">{current.errorCount}</strong>
          <Sparkline values={errorTrend} color={current.errorCount > 0 ? seriesColor(1) : seriesColor(2)} />
        </li>
        {keys.map((key) => (
          <li className="eval-kpi" key={key}>
            <span className="eval-kpi-name"
              title={key === MIRROR_KEY ? '数据库自带的字段注释，导入即有，不代表整理进度，所以不进趋势图' : undefined}>
              {labelOf(key)}
            </span>
            <strong className="eval-kpi-value">
              {current.metrics?.[key] != null ? percent(current.metrics[key]) : '—'}
            </strong>
            <Sparkline
              values={trend.map((point) => point.values[key]).filter((value) => typeof value === 'number')}
              color={colorOf(key)}
            />
          </li>
        ))}
      </ul>

      <section className="eval-block">
        <h2>覆盖率趋势</h2>
        {trend.length === 0 && <p className="eval-empty">这次评估没有指标数据。</p>}
        {trend.length > 0 && comparablePairs(trend) === 0 && (
          <p className="eval-empty">
            {trend.length === 1
              ? '只有一次评估，再跑一次才有趋势可比。'
              : `已有 ${trend.length} 次评估，但每两次之间都改过指标口径，没有可比的相邻区间。再跑一次就有了。`}
          </p>
        )}
        {comparablePairs(trend) > 0 && <TrendChart points={trend} keys={trendKeys} labelOf={labelOf} />}
      </section>

      <section className="eval-block">
        <h2>探针分布</h2>
        {all.isLoading && <p className="eval-empty">加载中…</p>}
        {!all.isLoading && (probes.length === 0
          ? <p className="eval-empty">这次评估没有发现问题。</p>
          : <ProbeBars rows={probes} />)}
      </section>

      <section className="eval-block">
        <h2>工作队列</h2>
        <FilterBar>
          <span className="eval-meta">硬错误在前，改完重跑一次评估复评</span>
          <Pagination
            page={page}
            pageSize={pageSize}
            total={findings.data?.total ?? 0}
            onPage={setPage}
            onPageSize={(size) => { setPageSize(size); setPage(0); }}
          />
        </FilterBar>
        {findings.isLoading
          ? <p className="eval-empty">加载中…</p>
          : (findings.data?.findings.length ?? 0) === 0
            ? <p className="eval-empty">没有待修的问题。</p>
            : <table className="eval-table">
              <thead>
                <tr>
                  <th scope="col">探针</th>
                  <th scope="col">对象</th>
                  <th scope="col">说明</th>
                  <th scope="col">修复命令</th>
                </tr>
              </thead>
              <tbody>
                {findings.data!.findings.map((finding) => (
                  <tr key={finding.seq}>
                    <td>
                      <StatusDot tone={finding.severity === 'error' ? 'bad' : 'warn'}
                        label={finding.severity === 'error' ? '硬错误' : '改进项'} />
                      <code>{finding.probe}</code>
                    </td>
                    <td><code className="eval-target">{finding.targetId ?? '—'}</code></td>
                    <td>{finding.message}</td>
                    <td>
                      {finding.remediation ? <span className="eval-fix">
                        <code>{finding.remediation}</code>
                        <Button
                          size="sm"
                          icon
                          title={copyLabel(finding)}
                          aria-label={copyLabel(finding)}
                          onClick={() => copyRemediation(finding)}
                        >
                          <svg width="14" height="14" viewBox="0 0 24 24" fill="currentColor" aria-hidden="true">
                            <path d={COPY_ICON} />
                          </svg>
                        </Button>
                      </span> : '—'}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>}
      </section>
    </div>
  );
}
