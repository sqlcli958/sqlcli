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

const NAV_GROUPS: NavGroup[] = ['work', 'knowledge', 'governance', 'system'];

/** 应用壳层：顶部数据源上下文 + 左侧一级导航 + 主内容区。 */
export function AppShell() {
  const [searchParams] = useSearchParams();
  const alias = searchParams.get('alias');
  const navigate = useNavigate();
  const location = useLocation();

  const setSession = useSessionStore((s) => s.setSession);
  const clearSession = useSessionStore((s) => s.clearSession);
  const readOnly = useSessionStore((s) => s.readOnly);
  const setWorkspaceRevision = useGraphStore((s) => s.setWorkspaceRevision);
  const switchGraphAlias = useGraphStore((s) => s.switchAlias);
  const revision = useGraphStore((s) => s.workspaceRevision);

  const aliases = useQuery({
    queryKey: ['aliases'],
    queryFn: ({ signal }) => getAliases(signal),
    staleTime: 30_000,
  });

  const current = aliases.data?.aliases.find((a) => a.name === alias);
  const graphAvailable = current?.graphAvailable ?? false;
  const names = aliases.data?.aliases.map((a) => a.name) ?? [];

  const switchAlias = (next: string, replace = false) => {
    const page = location.pathname.split('/').pop() || 'sql';
    navigate(navPath(page, next), { replace });
  };

  const [draft, setDraft] = useState(alias ?? '');
  useEffect(() => setDraft(alias ?? ''), [alias]);

  const [collapsed, setCollapsed] = useState(
    () => localStorage.getItem('shell-nav-collapsed') !== '0',
  );
  useEffect(() => {
    localStorage.setItem('shell-nav-collapsed', collapsed ? '1' : '0');
  }, [collapsed]);

  useEffect(() => {
    if (alias || !aliases.data?.aliases.length) return;
    const first = aliases.data.aliases.find((a) => a.graphAvailable) ?? aliases.data.aliases[0];
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
    if (!alias) {
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
  }, [alias, session.data, setSession, clearSession, setWorkspaceRevision]);

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
  });

  const approvals = useQuery({
    queryKey: ['approvals', 'pending-count'],
    queryFn: ({ signal }) => getApprovals({ status: 'pending' }, 0, 1, signal),
    refetchInterval: 30_000,
    staleTime: 15_000,
  });
  const pendingCount = approvals.data?.pending ?? 0;

  const graphTone: Tone = graphAvailable ? 'ok' : 'bad';
  const graphDetail = graphAvailable ? '图谱已导入' : '图谱未导入，到工作台导入';

  const connectionTone: Tone = connection.isPending
    ? 'warn'
    : connection.data?.success
      ? 'ok'
      : 'bad';
  const connectionDetail = connection.isPending
    ? '正在测试连接'
    : connection.data?.success
      ? `连接正常${connection.data.serverVersion ? ` · ${connection.data.serverVersion}` : ''}`
      : `连接失败${connection.data?.message ? `：${connection.data.message}` : ''}`;

  const indexState = index.data?.status ?? 'unknown';
  const indexTone: Tone = !graphAvailable || indexState === 'unknown'
    ? 'warn'
    : indexState === 'ready'
      ? 'ok'
      : indexState === 'stale'
        ? 'warn'
        : 'bad';
  const indexDetail = !graphAvailable
    ? '图谱未导入，索引不可用'
    : indexState === 'ready'
      ? '搜索索引已就绪'
      : indexState === 'stale'
        ? '图谱已变更，索引待重建（图谱页可重建）'
        : indexState === 'missing'
          ? '索引缺失，搜索不可用'
          : '索引状态未知';

  return (
    <div className="shell" data-nav-collapsed={collapsed || undefined}>
      <header className="shell-header">
        <Link className="shell-brand" to={navPath('sql', alias)}>
          sql-cli
        </Link>

        <label className="shell-source">
          <input
            className="shell-source-input"
            list="shell-alias-list"
            value={draft}
            placeholder="搜索数据源…"
            autoComplete="off"
            aria-label="数据源"
            onChange={(e) => {
              const next = e.target.value;
              setDraft(next);
              if (next && next !== alias && names.includes(next)) switchAlias(next);
            }}
            onFocus={(e) => e.target.select()}
            onBlur={() => setDraft(alias ?? '')}
          />
          <datalist id="shell-alias-list">
            {names.map((name) => <option key={name} value={name} />)}
          </datalist>
        </label>

        {alias && (
          <div className="shell-status" role="status">
            <StatusDot label="图谱" tone={graphTone} detail={graphDetail} />
            <StatusDot label="连接" tone={connectionTone} detail={connectionDetail} />
            <StatusDot label="索引" tone={indexTone} detail={indexDetail} />
            {readOnly && (
              <span className="shell-lock" title="只读数据源，不能执行写操作" aria-label="只读">
                <svg width="12" height="12" viewBox="0 0 24 24" fill="currentColor" aria-hidden="true">
                  <path d="M18 8h-1V6c0-2.76-2.24-5-5-5S7 3.24 7 6v2H6c-1.1 0-2 .9-2 2v10c0 1.1.9 2 2 2h12c1.1 0 2-.9 2-2V10c0-1.1-.9-2-2-2zm-6 9c-1.1 0-2-.9-2-2s.9-2 2-2 2 .9 2 2-.9 2-2 2zm3.1-9H8.9V6c0-1.71 1.39-3.1 3.1-3.1s3.1 1.39 3.1 3.1v2z" />
                </svg>
              </span>
            )}
            {graphAvailable && <span className="shell-meta">rev {revision}</span>}
          </div>
        )}
      </header>

      <PendingApprovalNotice alias={alias} />

      <nav className="shell-nav" aria-label="一级导航">
        <Button
          title={collapsed ? '展开导航' : '收起导航'}
          aria-label={collapsed ? '展开导航' : '收起导航'}
          aria-expanded={!collapsed}
          onClick={() => setCollapsed(!collapsed)}
          size="sm"
          icon
          className="shell-nav-toggle"
        >
          <svg width="11" height="11" viewBox="0 0 24 24" fill="currentColor" aria-hidden="true">
            <path d="M15.4 7.4L14 6l-6 6 6 6 1.4-1.4-4.6-4.6 4.6-4.6z" />
          </svg>
        </Button>

        <div className="shell-nav-groups">
          {NAV_GROUPS.map((group) => {
            const items = NAV_ITEMS.filter((item) => item.group === group);
            if (items.length === 0) return null;
            return (
              <section className={`shell-nav-group shell-nav-group-${group}`} key={group} aria-label={NAV_GROUP_LABELS[group]}>
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
                          width="17"
                          height="17"
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
                        {item.key === 'reviews' && pendingCount > 0 && (
                          <span className="shell-nav-badge" title={`${pendingCount} 件等着裁决`}>
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

type Tone = 'ok' | 'warn' | 'bad';

function PendingApprovalNotice({ alias }: { alias: string | null }) {
  const pending = useSessionStore((state) => state.pendingApproval);
  const dismiss = useSessionStore((state) => state.dismissPendingApproval);
  if (pending == null) return null;
  return (
    <div className="shell-notice" role="status">
      <span>本次修改已提交审批 #{pending}，批准后才会写入图谱。</span>
      <Link to={`${navPath('reviews', alias)}${alias ? '&' : '?'}tab=graph`} onClick={dismiss}>
        去评审
      </Link>
      <button type="button" onClick={dismiss} title="知道了" aria-label="关闭提示">×</button>
    </div>
  );
}

function StatusDot({ label, tone, detail }: { label: string; tone: Tone; detail: string }) {
  return (
    <span className="shell-dot" data-tone={tone} data-tip={`${label}：${detail}`} tabIndex={0}>
      <span className="shell-dot-mark" aria-hidden="true" />
      <span className="sr-only">{`${label}：${detail}`}</span>
    </span>
  );
}
