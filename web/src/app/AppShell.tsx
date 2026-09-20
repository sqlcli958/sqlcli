import { useEffect, useState } from 'react';
import { Link, NavLink, Outlet, useLocation, useNavigate, useSearchParams } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { getSession } from '../api/session';
import { getAliases, testAlias } from '../api/aliases';
import { getIndexStatus } from '../api/indexApi';
import { getApprovals } from '../api/approvals';
import { useSessionStore } from '../state/sessionStore';
import { useGraphStore } from '../state/graphStore';
import { NAV_GROUP_LABELS, NAV_ITEMS, type NavGroup, navPath } from './navigation';
import './app-shell.css';
import { Button } from '../ui/Button';

const NAV_GROUPS: NavGroup[] = ['primary', 'system'];

/**
 * 产品壳层。
 *
 * 顶部只承担“当前数据源 + 当前工作区健康度”，左侧只放产品级任务。
 * 具体功能（关系/血缘、质量/规则/审批）进入页面后再用二级导航展开，
 * 避免一级导航变成功能清单。
 */
export function AppShell() {
  const [searchParams] = useSearchParams();
  const alias = searchParams.get('alias');
  const navigate = useNavigate();
  const location = useLocation();

  const setSession = useSessionStore((s) => s.setSession);
  const clearSession = useSessionStore((s) => s.clearSession);
  const setWorkspaceRevision = useGraphStore((s) => s.setWorkspaceRevision);
  const switchGraphAlias = useGraphStore((s) => s.switchAlias);
  const revision = useGraphStore((s) => s.workspaceRevision);

  const aliases = useQuery({
    queryKey: ['aliases'],
    queryFn: ({ signal }) => getAliases(signal),
    staleTime: 30_000,
  });

  const current = aliases.data?.aliases.find((item) => item.name === alias);
  const graphAvailable = current?.graphAvailable ?? false;

  const switchAlias = (next: string, replace = false) => {
    const params = new URLSearchParams(location.search);
    if (next) params.set('alias', next);
    else params.delete('alias');
    const suffix = params.toString();
    navigate(`${location.pathname}${suffix ? `?${suffix}` : ''}`, { replace });
  };

  // 新用户默认展开；只有明确收起过才恢复折叠状态。
  const [collapsed, setCollapsed] = useState(
    () => localStorage.getItem('shell-nav-collapsed') === '1',
  );
  useEffect(() => {
    localStorage.setItem('shell-nav-collapsed', collapsed ? '1' : '0');
  }, [collapsed]);

  useEffect(() => {
    if (alias || !aliases.data?.aliases.length) return;
    const first = aliases.data.aliases.find((item) => item.graphAvailable) ?? aliases.data.aliases[0];
    switchAlias(first.name, true);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [alias, aliases.data]);

  const session = useQuery({
    queryKey: ['session', alias],
    queryFn: ({ signal }) => getSession(signal),
    enabled: Boolean(alias) && graphAvailable,
    retry: false,
  });

  useEffect(() => switchGraphAlias(alias), [alias, switchGraphAlias]);

  useEffect(() => {
    if (!alias || !graphAvailable) {
      clearSession();
      return;
    }
    if (session.data) {
      setSession(
        session.data.alias,
        session.data.token ?? null,
        session.data.revision,
        session.data.readOnly,
        session.data.capabilities,
      );
      setWorkspaceRevision(session.data.revision);
    }
  }, [alias, graphAvailable, session.data, setSession, clearSession, setWorkspaceRevision]);

  const connection = useQuery({
    queryKey: ['alias', 'test', alias],
    queryFn: () => testAlias(alias!),
    enabled: Boolean(alias),
    staleTime: 5 * 60_000,
    refetchOnWindowFocus: false,
    retry: false,
  });

  const index = useQuery({
    queryKey: ['index', 'status', alias],
    queryFn: ({ signal }) => getIndexStatus(signal),
    enabled: Boolean(alias) && graphAvailable,
    staleTime: 30_000,
    refetchOnWindowFocus: false,
    retry: false,
  });

  const approvals = useQuery({
    queryKey: ['approvals', 'pending-count'],
    queryFn: ({ signal }) => getApprovals({ status: 'pending' }, 0, 1, signal),
    refetchInterval: 30_000,
    staleTime: 15_000,
  });
  const pendingCount = approvals.data?.pending ?? 0;

  const connectionTone = connection.isPending
    ? 'pending'
    : connection.data?.success
      ? 'ok'
      : 'bad';
  const connectionLabel = connection.isPending
    ? '连接中'
    : connection.data?.success
      ? '已连接'
      : '连接异常';
  const connectionTitle = connection.data?.success
    ? `连接正常${connection.data.serverVersion ? ` · ${connection.data.serverVersion}` : ''}`
    : connection.data?.message || '正在检查数据库连接';

  const indexState = index.data?.status ?? 'unknown';
  const indexNeedsAttention = graphAvailable && indexState !== 'ready' && indexState !== 'unknown';

  return (
    <div
      className="shell"
      data-product-shell="true"
      data-nav-collapsed={collapsed || undefined}
    >
      <header className="shell-header">
        <Link className="shell-brand" to={navPath('sql', alias)} aria-label="SQLCLI 工作台">
          SQLCLI
        </Link>

        <div className="shell-context">
          <span className="shell-context-label">数据源</span>
          <select
            className="shell-source-select"
            value={alias ?? ''}
            aria-label="数据源"
            disabled={aliases.isLoading || !aliases.data?.aliases.length}
            onChange={(event) => switchAlias(event.target.value)}
          >
            {!alias && <option value="">选择数据源</option>}
            {aliases.data?.aliases.map((item) => (
              <option key={item.name} value={item.name}>
                {item.name}
              </option>
            ))}
          </select>
          {current?.dbType && <span className="shell-context-type">{current.dbType}</span>}
        </div>

        <div className="shell-health" aria-label="工作区状态">
          {alias && (
            <span className="shell-health-chip" data-tone={connectionTone} title={connectionTitle}>
              <span className="shell-health-dot" aria-hidden="true" />
              {connectionLabel}
            </span>
          )}

          {alias && !graphAvailable && (
            <Link
              className="shell-health-chip"
              data-tone="warn"
              to={navPath('knowledge', alias)}
              title="数据模型尚未导入"
            >
              模型未导入
            </Link>
          )}

          {alias && indexNeedsAttention && (
            <Link
              className="shell-health-chip"
              data-tone="warn"
              to={navPath('knowledge', alias, { mode: 'relations' })}
              title={indexState === 'stale' ? '搜索索引落后于当前 revision' : '搜索索引不可用'}
            >
              索引{indexState === 'stale' ? '待更新' : '异常'}
            </Link>
          )}

          {current?.readOnly && (
            <span className="shell-health-chip" data-tone="muted" title="只读数据源，不能执行写操作">
              只读
            </span>
          )}

          {graphAvailable && <span className="shell-revision">rev {revision}</span>}
        </div>
      </header>

      <PendingApprovalNotice alias={alias} />

      <nav className="shell-nav" aria-label="一级导航">
        <div className="shell-nav-head">
          <span className="shell-nav-product">Workspace</span>
          <Button
            title={collapsed ? '展开导航' : '收起导航'}
            aria-label={collapsed ? '展开导航' : '收起导航'}
            aria-expanded={!collapsed}
            onClick={() => setCollapsed(!collapsed)}
            size="sm"
            icon
            className="shell-nav-toggle"
          >
            <svg width="13" height="13" viewBox="0 0 24 24" fill="currentColor" aria-hidden="true">
              <path d="M15.4 7.4L14 6l-6 6 6 6 1.4-1.4-4.6-4.6 4.6-4.6z" />
            </svg>
          </Button>
        </div>

        <div className="shell-nav-groups">
          {NAV_GROUPS.map((group) => {
            const items = NAV_ITEMS.filter((item) => item.group === group);
            if (items.length === 0) return null;
            return (
              <section
                className={`shell-nav-group shell-nav-group-${group}`}
                key={group}
                aria-label={NAV_GROUP_LABELS[group]}
              >
                <div className="shell-nav-group-label" aria-hidden="true">{NAV_GROUP_LABELS[group]}</div>
                <ul>
                  {items.map((item) => {
                    const disabled = item.needsAlias && !alias;
                    const reason = disabled ? '请先选择数据源' : undefined;
                    const tip = collapsed ? [item.label, reason].filter(Boolean).join(' · ') : reason;
                    const body = (
                      <>
                        <svg
                          className="shell-nav-icon"
                          width="18"
                          height="18"
                          viewBox="0 0 24 24"
                          fill="none"
                          stroke="currentColor"
                          strokeWidth="1.7"
                          strokeLinecap="round"
                          strokeLinejoin="round"
                          aria-hidden="true"
                        >
                          <path d={item.icon} />
                        </svg>
                        <span className="shell-nav-label">{item.label}</span>
                        {item.key === 'governance' && pendingCount > 0 && (
                          <span className="shell-nav-badge" title={`${pendingCount} 件等待处理`}>
                            {pendingCount > 99 ? '99+' : pendingCount}
                          </span>
                        )}
                      </>
                    );
                    return (
                      <li key={item.key}>
                        {disabled ? (
                          <span className="shell-nav-item is-disabled" title={tip} aria-disabled="true">
                            {body}
                          </span>
                        ) : (
                          <NavLink
                            className={({ isActive }) => `shell-nav-item${isActive ? ' is-active' : ''}`}
                            title={tip}
                            to={navPath(item.key, alias)}
                          >
                            {body}
                          </NavLink>
                        )}
                      </li>
                    );
                  })}
                </ul>
              </section>
            );
          })}
        </div>
      </nav>

      <main className="shell-main">
        <Outlet context={{ alias, graphAvailable }} />
      </main>
    </div>
  );
}

function PendingApprovalNotice({ alias }: { alias: string | null }) {
  const pending = useSessionStore((state) => state.pendingApproval);
  const dismiss = useSessionStore((state) => state.dismissPendingApproval);
  if (pending == null) return null;
  return (
    <div className="shell-notice" role="status">
      <span>本次修改已提交审批 #{pending}，批准后才会写入数据模型。</span>
      <Link
        to={navPath('governance', alias, { section: 'reviews', tab: 'graph' })}
        onClick={dismiss}
      >
        去处理
      </Link>
      <button type="button" onClick={dismiss} title="知道了" aria-label="关闭提示">×</button>
    </div>
  );
}
