import { useQuery } from '@tanstack/react-query';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { getAliases } from '../../api/aliases';
import { getApprovals } from '../../api/approvals';
import { getIndexStatus } from '../../api/indexApi';
import { getValidationIssues } from '../../api/validation';
import { getWorkspaceStats } from '../../api/workspace';
import { EvalPage } from '../eval/EvalPage';
import { RulesPage } from '../policy/RulesPage';
import { ReviewsPage } from '../review/ReviewsPage';
import { Button } from '../../ui/Button';
import { navPath } from '../../app/navigation';
import './governance.css';

const SECTIONS = [
  { key: 'overview', label: '概览' },
  { key: 'quality', label: '质量' },
  { key: 'rules', label: '规则' },
  { key: 'reviews', label: '审批与审计' },
] as const;

type Section = (typeof SECTIONS)[number]['key'];

function isSection(value: string | null): value is Section {
  return SECTIONS.some((item) => item.key === value);
}

export function GovernancePage() {
  const [params, setParams] = useSearchParams();
  const alias = params.get('alias');
  const rawSection = params.get('section');
  const section: Section = isSection(rawSection) ? rawSection : 'overview';

  const setSection = (next: Section) => {
    setParams((current) => {
      const copy = new URLSearchParams(current);
      if (next === 'overview') copy.delete('section');
      else copy.set('section', next);
      if (next !== 'reviews') copy.delete('tab');
      return copy;
    });
  };

  return (
    <div className="governance-page">
      <header className="governance-header">
        <div>
          <span className="governance-eyebrow">Governance</span>
          <h1>治理</h1>
          <p>把数据模型质量、规则、审批和执行审计放在一个控制面里。</p>
        </div>
      </header>

      <nav className="governance-tabs" aria-label="治理视图">
        {SECTIONS.map((item) => (
          <button
            type="button"
            key={item.key}
            className={section === item.key ? 'is-active' : undefined}
            aria-current={section === item.key ? 'page' : undefined}
            onClick={() => setSection(item.key)}
          >
            {item.label}
          </button>
        ))}
      </nav>

      <div className="governance-content">
        {section === 'overview' && <GovernanceOverview alias={alias} onOpen={setSection} />}
        {section === 'quality' && <EvalPage />}
        {section === 'rules' && <RulesPage />}
        {section === 'reviews' && <ReviewsPage />}
      </div>
    </div>
  );
}

function GovernanceOverview({
  alias,
  onOpen,
}: {
  alias: string | null;
  onOpen: (section: Section) => void;
}) {
  const navigate = useNavigate();

  const aliases = useQuery({
    queryKey: ['aliases'],
    queryFn: ({ signal }) => getAliases(signal),
    staleTime: 30_000,
  });
  const current = aliases.data?.aliases.find((item) => item.name === alias);
  const graphAvailable = current?.graphAvailable ?? false;

  const stats = useQuery({
    queryKey: ['workspace', 'stats', alias],
    queryFn: ({ signal }) => getWorkspaceStats(signal),
    enabled: Boolean(alias) && graphAvailable,
  });

  const issues = useQuery({
    queryKey: ['validation', 'issues', alias, 'error'],
    queryFn: ({ signal }) => getValidationIssues(undefined, 'error', signal),
    enabled: Boolean(alias) && graphAvailable,
  });

  const index = useQuery({
    queryKey: ['index', 'status', alias],
    queryFn: ({ signal }) => getIndexStatus(signal),
    enabled: Boolean(alias) && graphAvailable,
    retry: false,
  });

  const approvals = useQuery({
    queryKey: ['approvals', 'pending-count'],
    queryFn: ({ signal }) => getApprovals({ status: 'pending' }, 0, 1, signal),
    staleTime: 15_000,
  });

  if (!alias) {
    return (
      <section className="governance-empty">
        <strong>先选择一个数据源</strong>
        <p>质量和规则依赖当前数据源；审批与审计可以跨数据源查看。</p>
        <Button onClick={() => onOpen('reviews')}>查看审批与审计</Button>
      </section>
    );
  }

  const s = stats.data;
  const errorCount = issues.data?.total ?? 0;
  const pendingCount = approvals.data?.pending ?? 0;
  const indexState = index.data?.status ?? 'unknown';
  const approvalOn = Boolean(
    current?.approveQuery || current?.approveUpdate || current?.approveGraph,
  );

  return (
    <div className="governance-overview">
      <section className="governance-panel">
        <div className="governance-panel-head">
          <div>
            <h2>需要处理</h2>
            <p>这里只放会影响后续工作、值得现在处理的事项。</p>
          </div>
        </div>

        <div className="governance-actions">
          <GovernanceAction
            tone={!graphAvailable ? 'warn' : indexState === 'ready' ? 'ok' : indexState === 'stale' ? 'warn' : 'bad'}
            title="搜索索引"
            detail={
              !graphAvailable
                ? '数据模型尚未导入'
                : indexState === 'ready'
                  ? `已就绪，覆盖 ${index.data?.tableCount ?? 0} 张表`
                  : indexState === 'stale'
                    ? '模型已经变化，索引落后于当前 revision'
                    : '索引缺失或不可用'
            }
            actionLabel={graphAvailable && indexState !== 'ready' ? '去数据模型处理' : undefined}
            onAction={graphAvailable && indexState !== 'ready'
              ? () => navigate(navPath('knowledge', alias, { mode: 'relations' }))
              : undefined}
          />

          <GovernanceAction
            tone={!graphAvailable ? 'warn' : errorCount > 0 ? 'bad' : 'ok'}
            title="模型质量"
            detail={
              !graphAvailable
                ? '导入数据模型后才能运行质量检查'
                : errorCount > 0
                  ? `${errorCount} 个错误需要处理`
                  : '没有未处理的 error 级问题'
            }
            actionLabel={graphAvailable ? '打开质量' : undefined}
            onAction={graphAvailable ? () => onOpen('quality') : undefined}
          />

          <GovernanceAction
            tone={pendingCount > 0 ? 'warn' : 'ok'}
            title="审批队列"
            detail={pendingCount > 0 ? `${pendingCount} 件操作等待处理` : '当前没有待审批操作'}
            actionLabel="打开审批与审计"
            onAction={() => onOpen('reviews')}
          />

          <GovernanceAction
            tone={approvalOn ? 'info' : 'muted'}
            title="执行保护"
            detail={
              approvalOn
                ? `已开启：${[
                    current?.approveQuery && '查询',
                    current?.approveUpdate && '写操作',
                    current?.approveGraph && '模型变更',
                  ].filter(Boolean).join(' / ')}`
                : '当前数据源没有开启审批闸门'
            }
          />
        </div>
      </section>

      <section className="governance-summary" aria-label="治理概览">
        <SummaryMetric label="表" value={graphAvailable ? s?.tables : undefined} />
        <SummaryMetric label="关系" value={graphAvailable ? s?.relations : undefined} />
        <SummaryMetric label="校验错误" value={graphAvailable ? errorCount : undefined} tone={errorCount > 0 ? 'bad' : 'ok'} />
        <SummaryMetric label="待审批" value={pendingCount} tone={pendingCount > 0 ? 'warn' : 'ok'} />
      </section>
    </div>
  );
}

function SummaryMetric({
  label,
  value,
  tone = 'neutral',
}: {
  label: string;
  value?: number;
  tone?: 'neutral' | 'ok' | 'warn' | 'bad';
}) {
  return (
    <div className="governance-metric" data-tone={tone}>
      <strong>{value ?? '—'}</strong>
      <span>{label}</span>
    </div>
  );
}

function GovernanceAction({
  tone,
  title,
  detail,
  actionLabel,
  onAction,
}: {
  tone: 'ok' | 'warn' | 'bad' | 'info' | 'muted';
  title: string;
  detail: string;
  actionLabel?: string;
  onAction?: () => void;
}) {
  return (
    <article className="governance-action" data-tone={tone}>
      <span className="governance-action-dot" aria-hidden="true" />
      <div className="governance-action-copy">
        <strong>{title}</strong>
        <span>{detail}</span>
      </div>
      {actionLabel && onAction && (
        <Button size="sm" variant="ghost" onClick={onAction}>{actionLabel}</Button>
      )}
    </article>
  );
}
