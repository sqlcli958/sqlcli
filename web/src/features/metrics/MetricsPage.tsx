import { useState } from 'react';
import { useMutation, useQuery } from '@tanstack/react-query';
import { Link, useOutletContext, useSearchParams } from 'react-router-dom';
import { columnRef, expandMetricSql, getMetrics, upsertMetric } from '../../api/metrics';
import type { MetricAdditivity, RatioMetricDto } from '../../api/metrics';
import { executeWorkbenchSql } from '../../api/workbench';
import { getAliases } from '../../api/aliases';
import { queryClient } from '../../api/queryClient';
import { navPath } from '../../app/navigation';
import { useSessionStore } from '../../state/sessionStore';
import { stashWorkbenchSql } from '../workbench/workbenchSql';
import { Button, buttonClass } from '../../ui/Button';
import { Chart } from '../../ui/Chart';
import { Select } from '../../ui/Select';
import { buildMetricTrendOption, extractMetricSeries, grainColumnName } from './charts';
import type { WorkbenchExecuteResultDto } from '../../types/api';
import '../knowledge/knowledge.css';
import './metrics.css';

/**
 * 指标页。单开一级导航，理由见 CLAUDE.md「一级导航」一节和 navigation.ts 的注释：
 * 指标是「定义口径 → 展开 SQL → 出图看走势」，跟图谱页「按 schema/表浏览图谱对象」
 * 不是同一种用法。这一页本体从图谱页第四个标签搬过来（原 `knowledge/MetricList.tsx`），
 * 并加上图表——图表才是指标真正的消费侧，只展开 SQL 让人复制走，就是这个仓库
 * 点名过多次的「写入口做完就算完、消费侧没人做」的又一例。
 *
 * <p>UI 写入的 actor 是 human，所以在这里定义的指标直接 verified；CLI 写的是候选。
 * 这不是权限大小的差别，是「人声明口径」和「Agent 猜口径」的差别。
 *
 * <p>**出图必须走 `executeWorkbenchSql`**（CLAUDE.md「一条管线，两个结尾」不因为出图
 * 破例）：拿 `expand-metric` 展开出的 SQL 直接送进工作台执行接口，不另写一条执行路径。
 */
export function MetricsPage() {
  const revision = useSessionStore((s) => s.revision);
  const [searchParams] = useSearchParams();
  const alias = searchParams.get('alias');
  const { graphAvailable } = useOutletContext<{ graphAvailable: boolean }>();
  const [editing, setEditing] = useState<RatioMetricDto | 'new' | null>(null);

  const aliases = useQuery({
    queryKey: ['aliases'],
    queryFn: ({ signal }) => getAliases(signal),
    staleTime: 30_000,
  });
  const aliasInfo = aliases.data?.aliases.find((a) => a.name === alias);

  const list = useQuery({
    queryKey: ['metrics', revision],
    queryFn: ({ signal }) => getMetrics(signal),
    staleTime: 30_000,
    enabled: graphAvailable,
  });

  if (!alias) {
    return (
      <div className="page metrics-page metrics-redesign">
        <p className="term-list-hint">请先在顶栏选择数据源。</p>
      </div>
    );
  }

  if (!graphAvailable) {
    return (
      <div className="page metrics-page metrics-redesign">
        <header className="metrics-head">
          <h1>指标</h1>
        </header>
        <p className="term-list-hint">
          还没有图谱，指标的口径引用图谱里的列——先去
          <Link className="metric-link" to={navPath('knowledge', alias)}>数据模型中导入</Link>。
        </p>
      </div>
    );
  }

  if (list.isLoading) {
    return (
      <div className="page metrics-page metrics-redesign">
        <p className="term-list-hint">加载中…</p>
      </div>
    );
  }
  if (list.isError) {
    return (
      <div className="page metrics-page metrics-redesign">
        <p className="term-list-hint">指标加载失败</p>
      </div>
    );
  }

  // MetricDto 是 types/api.ts 定义的旧形状，numerator/denominator/additivity 是这次加的
  // 字段，服务端已经在返回，类型跟着断言一下（详见 api/metrics.ts 里 RatioMetricDto 的注释）。
  const metrics = (list.data?.metrics ?? []) as RatioMetricDto[];

  return (
    <div className="page metrics-page metrics-redesign">
      <header className="metrics-head">
        <div>
          <span className="metrics-eyebrow">Semantic metrics</span>
          <h1>指标</h1>
          <p>统一业务口径、可用粒度和维度，并从同一条 SQL 执行链路验证趋势。</p>
        </div>
        <Button variant="primary" size="sm" onClick={() => setEditing(editing === 'new' ? null : 'new')}>
          {editing === 'new' ? '收起' : '新建指标'}
        </Button>
      </header>

      {editing === 'new' && (
        <MetricForm
          revision={list.data?.revision ?? revision ?? 0}
          onDone={() => {
            setEditing(null);
            queryClient.invalidateQueries({ queryKey: ['metrics'] });
          }}
        />
      )}

      {metrics.length === 0 && editing !== 'new' && (
        <div className="term-list-hint">
          <p>
            还没有指标。指标声明的是口径——算不算取消单、按哪个时间列、能按什么切。
            出现口径争议时才需要建一条：同一个业务问题被算出两个数，且必须统一成一个。
          </p>
          <p>可以在这里点「新建指标」，或者用 CLI：</p>
          <pre className="metric-sql">
            {'sql-cli <alias> schema add-metric gmv_paid \\\n' +
              '  --expression "SUM(orders.amount)" --filters "status IN (2,3)" \\\n' +
              '  --business-name "已支付GMV" --grain-column app.orders.created_at \\\n' +
              '  --grains day,week,month --dimensions app.orders.channel'}
          </pre>
        </div>
      )}

      <ul className="term-list">
        {metrics.map((metric) => (
          <MetricItem
            key={metric.id}
            metric={metric}
            alias={alias}
            aliasInfo={aliasInfo}
            editing={editing !== 'new' && editing?.id === metric.id}
            onToggleEdit={() => setEditing(editing !== 'new' && editing?.id === metric.id ? null : metric)}
            revision={list.data?.revision ?? revision ?? 0}
            onDone={() => {
              setEditing(null);
              queryClient.invalidateQueries({ queryKey: ['metrics'] });
            }}
          />
        ))}
      </ul>
    </div>
  );
}

function MetricItem({
  metric,
  alias,
  aliasInfo,
  editing,
  onToggleEdit,
  revision,
  onDone,
}: {
  metric: RatioMetricDto;
  alias: string | null;
  aliasInfo?: { approveQuery: boolean };
  editing: boolean;
  onToggleEdit: () => void;
  revision: number;
  onDone: () => void;
}) {
  const [grain, setGrain] = useState('');
  const [showTrend, setShowTrend] = useState(false);
  const expand = useMutation({
    mutationFn: () => expandMetricSql(metric.name, { grain: grain || undefined }),
  });
  const grains = metric.grain?.grains ?? [];
  const canTrend = Boolean(metric.grain?.timeColumn && grains.length > 0);
  const isRatio = !!(metric.numerator && metric.denominator);
  // 比率结构由展开器强制推定成 non_additive，不看 metric.additivity 填的是什么；
  // 跟 MetricSqlExpander#effectiveAdditivity 保持同一个判断规则。
  const additivity = isRatio ? 'non_additive' : metric.additivity;

  return (
    <li className="term-list-item">
      <div className="term-list-head">
        <span className="term-list-name">{metric.businessName || metric.name}</span>
        {metric.status && <span className="term-list-status">{metric.status}</span>}
        {additivity && <span className="term-list-status">{additivity}</span>}
      </div>
      {isRatio ? (
        <>
          <p className="term-list-line">
            分子：{metric.numerator?.expression}
            {metric.numerator?.filters && `（过滤：${metric.numerator.filters}）`}
          </p>
          <p className="term-list-line">
            分母：{metric.denominator?.expression}
            {metric.denominator?.filters && `（过滤：${metric.denominator.filters}）`}
          </p>
        </>
      ) : (
        <>
          {metric.expression && <p className="term-list-line">口径：{metric.expression}</p>}
          {metric.filters && <p className="term-list-line">过滤：{metric.filters}</p>}
        </>
      )}
      {metric.grain?.timeColumn && (
        <p className="term-list-line">
          时间列：{columnRef(metric.grain.timeColumn)}
          {grains.length > 0 && `（${grains.join('、')}）`}
        </p>
      )}
      {metric.dimensions.length > 0 && (
        <p className="term-list-line">维度：{metric.dimensions.map(columnRef).join('、')}</p>
      )}
      {metric.joinPath.length > 0 && (
        <p className="term-list-line">
          JOIN：{metric.joinPath.map((step) => step.joinType).join(' / ')}（共 {metric.joinPath.length} 步）
        </p>
      )}

      <div className="metric-item-actions">
        {grains.length > 0 && (
          <Select
            size="sm"
            label="展开粒度"
            value={grain}
            onChange={(e) => setGrain(e.target.value)}
          >
            <option value="">不分粒度</option>
            {grains.map((g) => (
              <option key={g} value={g}>
                按{g}
              </option>
            ))}
          </Select>
        )}
        <Button size="sm" onClick={() => expand.mutate()} disabled={expand.isPending}>
          {expand.isPending ? '展开中…' : '展开 SQL'}
        </Button>
        {canTrend ? (
          <Button size="sm" onClick={() => setShowTrend((open) => !open)}>
            {showTrend ? '收起趋势' : '趋势'}
          </Button>
        ) : (
          <span className="metric-trend-unavailable">未配置趋势粒度</span>
        )}
        <Button size="sm" onClick={onToggleEdit}>
          {editing ? '收起' : '编辑'}
        </Button>
      </div>

      {expand.isError && (
        <p className="metric-error" role="alert">
          {expand.error.message}
        </p>
      )}
      {expand.data && (
        <div className="metric-sql">
          <pre>{expand.data.sql}</pre>
          <Link
            className={buttonClass('default', 'sm')}
            to={navPath('sql', alias)}
            onClick={() => stashWorkbenchSql(expand.data.sql)}
          >
            去工作台执行
          </Link>
        </div>
      )}

      {showTrend && canTrend && (
        <MetricChartSection metric={metric} alias={alias} aliasInfo={aliasInfo} />
      )}

      {editing && <MetricForm metric={metric} revision={revision} onDone={onDone} />}
    </li>
  );
}

const RANGE_PRESETS = [
  { key: '7', label: '近7天' },
  { key: '30', label: '近30天' },
  { key: '90', label: '近90天' },
] as const;

/**
 * 图表消费侧：选粒度 + 时间范围 → `expandMetricSql` → `executeWorkbenchSql` → 画折线。
 *
 * 没有声明时间列（或没有声明任何粒度，无法按时间分桶）的指标画不出趋势——
 * 这时不画空图，直接说明原因并给出补时间列的 CLI 命令。
 */
function MetricChartSection({
  metric,
  alias,
  aliasInfo,
}: {
  metric: RatioMetricDto;
  alias: string | null;
  aliasInfo?: { approveQuery: boolean };
}) {
  const grains = metric.grain?.grains ?? [];
  const [grain, setGrain] = useState(grains[0] ?? '');
  const [range, setRange] = useState<(typeof RANGE_PRESETS)[number]['key']>('30');

  const chart = useMutation({
    mutationFn: async () => {
      const to = new Date();
      const from = new Date(to.getTime() - Number(range) * 86_400_000);
      const { sql } = await expandMetricSql(metric.name, {
        grain,
        timeFrom: from.toISOString().slice(0, 10),
        timeTo: to.toISOString().slice(0, 10),
      });
      // 铁律：执行走 executeWorkbenchSql，不另写一条执行路径（CLAUDE.md「一条管线，两个结尾」）。
      return executeWorkbenchSql(sql);
    },
  });

  if (!metric.grain?.timeColumn || grains.length === 0) {
    return (
      <p className="term-list-hint">
        这条指标没有声明时间列（或没有声明可展开的粒度），画不出趋势。补一条：
        <pre className="metric-sql">
          {`sql-cli <alias> schema add-metric ${metric.name} \\\n` +
            '  --grain-column schema.table.column --grains day,week,month'}
        </pre>
      </p>
    );
  }

  return (
    <div className="metric-chart-block">
      <div className="metric-item-actions">
        <Select
          size="sm"
          label="出图粒度"
          value={grain}
          onChange={(e) => setGrain(e.target.value)}
        >
          {grains.map((g) => (
            <option key={g} value={g}>
              按{g}
            </option>
          ))}
        </Select>
        <div className="metric-range-group" role="group" aria-label="时间范围">
          {RANGE_PRESETS.map((preset) => (
            <Button
              key={preset.key}
              size="sm"
              variant={range === preset.key ? 'primary' : 'default'}
              onClick={() => setRange(preset.key)}
            >
              {preset.label}
            </Button>
          ))}
        </div>
        <Button size="sm" onClick={() => chart.mutate()} disabled={chart.isPending}>
          {chart.isPending ? '出图中…' : '出图'}
        </Button>
      </div>

      {/* 别名开了查询审批时这次执行会挂到有人在评审页裁决为止，跟工作台同一个文案 */}
      {chart.isPending && aliasInfo?.approveQuery && (
        <p className="metric-approval" role="status">
          等待审批中，请到评审页放行。
          <Link className="metric-link" to={navPath('governance', alias, { section: 'reviews' })}>去评审</Link>
        </p>
      )}
      {chart.isError && (
        <p className="metric-error" role="alert">
          {chart.error.message}
        </p>
      )}
      {chart.data && <MetricChart result={chart.data} grain={grain} metric={metric} />}
    </div>
  );
}

function MetricChart({
  result,
  grain,
  metric,
}: {
  result: WorkbenchExecuteResultDto;
  grain: string;
  metric: RatioMetricDto;
}) {
  if (result.status !== 'SUCCEEDED') {
    return (
      <p className="metric-error" role="alert">
        {result.status === 'REJECTED' ? '已拒绝' : '执行失败'}：{result.errorSummary || '未说明原因'}
      </p>
    );
  }
  const timeColumn = grainColumnName(grain);
  const points = extractMetricSeries(result, timeColumn, metric.name);
  if (points.length === 0) {
    // **「查出来是空的」和「认不出列」必须分开说。** 前者是事实，后者是这个页面坏了——
    // 混成一句「没有数据」就是这个项目最不能接受的那种静默错误：照跑、有结果、话是假的。
    // 列名靠约定对上（MetricSqlExpander 里 `grain_<粒度>` 与指标名），约定改了这里会先说出来。
    if (result.rows.length > 0) {
      return (
        <p className="term-list-hint" role="alert">
          查到 {result.rows.length} 行，但认不出该画哪两列：期望 <code>{timeColumn}</code> 和{' '}
          <code>{metric.name}</code>，实际是 {result.columns.map((c) => c.name).join('、')}。
          用「展开 SQL」看这段 SQL 是怎么起别名的。
        </p>
      );
    }
    return <p className="term-list-hint">这个时间范围内没有数据。</p>;
  }
  const label = metric.businessName || metric.name;
  return (
    <div className="metric-chart-wrap">
      <Chart option={buildMetricTrendOption(points, label)} height={220} label={`${label} 趋势`} />
    </div>
  );
}

/**
 * 定义/修改一条指标。
 *
 * 列引用收 `schema.table.column` 明文，和 CLI 的 `--dimensions` 完全一样——
 * 服务端走同一份 `GraphWorkspace.resolveColumnId`，拼错了当场 400 说清哪个字段哪个值。
 *
 * ponytail: joinPath 这一版只展示不编辑，跨表口径先用
 * `sql-cli <alias> schema add-metric --join-path relId|inner`。要在这里编辑得先有个
 * 关系选择器（关系是几百上千条，不能平铺），那是独立一块，等真有人在 UI 里定义跨表指标再做。
 */
function MetricForm({
  metric,
  revision,
  onDone,
}: {
  metric?: RatioMetricDto;
  revision: number;
  onDone: () => void;
}) {
  const [name, setName] = useState(metric?.name ?? '');
  const [businessName, setBusinessName] = useState(metric?.businessName ?? '');
  const [isRatio, setIsRatio] = useState(!!(metric?.numerator && metric?.denominator));
  const [expression, setExpression] = useState(metric?.expression ?? '');
  const [filters, setFilters] = useState(metric?.filters ?? '');
  const [additivity, setAdditivity] = useState<MetricAdditivity | ''>(metric?.additivity ?? '');
  const [numeratorExpr, setNumeratorExpr] = useState(metric?.numerator?.expression ?? '');
  const [numeratorFilters, setNumeratorFilters] = useState(metric?.numerator?.filters ?? '');
  const [denominatorExpr, setDenominatorExpr] = useState(metric?.denominator?.expression ?? '');
  const [denominatorFilters, setDenominatorFilters] = useState(metric?.denominator?.filters ?? '');
  const [grainColumn, setGrainColumn] = useState(
    metric?.grain?.timeColumn ? columnRef(metric.grain.timeColumn) : '',
  );
  const [grains, setGrains] = useState((metric?.grain?.grains ?? []).join(','));
  const [dimensions, setDimensions] = useState((metric?.dimensions ?? []).map(columnRef).join(','));

  const save = useMutation({
    mutationFn: () =>
      upsertMetric({
        name: name.trim(),
        businessName: businessName.trim() || undefined,
        ...(isRatio
          ? {
              numerator: { expression: numeratorExpr.trim(), filters: numeratorFilters.trim() || undefined },
              denominator: { expression: denominatorExpr.trim(), filters: denominatorFilters.trim() || undefined },
            }
          : {
              expression: expression.trim(),
              filters: filters.trim() || undefined,
              additivity: additivity || undefined,
            }),
        grainColumn: grainColumn.trim() || undefined,
        grains: splitCsv(grains),
        dimensions: splitCsv(dimensions),
        joinPath: metric?.joinPath,
        expectedRevision: revision,
        reason: metric ? '修改指标口径' : '定义指标',
      }),
    onSuccess: onDone,
  });

  const canSave =
    name.trim().length > 0 &&
    !save.isPending &&
    (isRatio ? numeratorExpr.trim().length > 0 && denominatorExpr.trim().length > 0 : expression.trim().length > 0);

  return (
    <form
      className="metric-form"
      onSubmit={(e) => {
        e.preventDefault();
        if (canSave) save.mutate();
      }}
    >
      <label className="metric-field">
        <span>名称</span>
        <input value={name} onChange={(e) => setName(e.target.value)} disabled={!!metric} required />
      </label>
      <label className="metric-field">
        <span>业务名</span>
        <input value={businessName} onChange={(e) => setBusinessName(e.target.value)} />
      </label>
      <label className="metric-field" title="复购率、转化率这类「部分/整体」口径选这个——分子分母各自的过滤条件不一样">
        <span>比率指标</span>
        <input type="checkbox" checked={isRatio} onChange={(e) => setIsRatio(e.target.checked)} />
      </label>
      {isRatio ? (
        <>
          <label className="metric-field">
            <span>分子表达式</span>
            <input
              value={numeratorExpr}
              onChange={(e) => setNumeratorExpr(e.target.value)}
              placeholder="COUNT(DISTINCT app.orders.user_id)"
              required
            />
          </label>
          <label className="metric-field" title="只卡分子，不影响分母，例如复购率的「下单≥2次」">
            <span>分子过滤</span>
            <input
              value={numeratorFilters}
              onChange={(e) => setNumeratorFilters(e.target.value)}
              placeholder="order_count >= 2"
            />
          </label>
          <label className="metric-field">
            <span>分母表达式</span>
            <input
              value={denominatorExpr}
              onChange={(e) => setDenominatorExpr(e.target.value)}
              placeholder="COUNT(DISTINCT app.orders.user_id)"
              required
            />
          </label>
          <label className="metric-field" title="通常留空，表示分母是全量口径，不额外限定">
            <span>分母过滤</span>
            <input value={denominatorFilters} onChange={(e) => setDenominatorFilters(e.target.value)} />
          </label>
          <p className="term-list-hint" title="怎么加都不对，只能从分子分母重新算，展开器自动推定，不用填">
            可加性：non_additive（比率指标自动推定）
          </p>
        </>
      ) : (
        <>
          <label className="metric-field">
            <span>口径表达式</span>
            <input
              value={expression}
              onChange={(e) => setExpression(e.target.value)}
              placeholder="SUM(app.orders.amount)"
              required
            />
          </label>
          <label className="metric-field" title="算不算取消单、算不算退款单——大部分口径争议都在这一行">
            <span>过滤条件</span>
            <input value={filters} onChange={(e) => setFilters(e.target.value)} placeholder="status IN (2,3)" />
          </label>
          <label
            className="metric-field"
            title="跨时间不可加的快照（如库存余额）标 semi_additive；expand-metric 请求按时间分桶展开会拒绝，避免把快照值错误相加"
          >
            <span>可加性</span>
            <Select value={additivity} onChange={(e) => setAdditivity(e.target.value as MetricAdditivity | '')}>
              <option value="">未标注</option>
              <option value="additive">additive</option>
              <option value="semi_additive">semi_additive</option>
              <option value="non_additive">non_additive</option>
            </Select>
          </label>
        </>
      )}
      <label className="metric-field">
        <span>时间列</span>
        <input
          value={grainColumn}
          onChange={(e) => setGrainColumn(e.target.value)}
          placeholder="app.orders.created_at"
        />
      </label>
      <label className="metric-field">
        <span>粒度</span>
        <input value={grains} onChange={(e) => setGrains(e.target.value)} placeholder="day,week,month" />
      </label>
      <label className="metric-field">
        <span>维度</span>
        <input
          value={dimensions}
          onChange={(e) => setDimensions(e.target.value)}
          placeholder="app.orders.channel"
        />
      </label>

      {save.isError && (
        <p className="metric-error" role="alert">
          {save.error.message}
        </p>
      )}

      <div className="metric-form-actions">
        <Button type="submit" size="sm" variant="primary" disabled={!canSave}>
          {save.isPending ? '保存中…' : '保存'}
        </Button>
      </div>
    </form>
  );
}

function splitCsv(raw: string): string[] {
  return raw
    .split(',')
    .map((item) => item.trim())
    .filter((item) => item.length > 0);
}
