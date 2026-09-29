import { Suspense, useEffect, useId, useRef, useState } from 'react';
import { Link, NavLink, Outlet, useLocation, useNavigate, useSearchParams } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { getSession } from '../api/session';
import { getAliases, testAlias } from '../api/aliases';
import { getIndexStatus } from '../api/indexApi';
import { getApprovals } from '../api/approvals';
import { useSessionStore } from '../state/sessionStore';
import { useGraphStore } from '../state/graphStore';
import { NAV_ITEMS, type NavGroup, navPath } from './navigation';
import './app-shell.css';
import { Button } from '../ui/Button';

const NAV_GROUPS: NavGroup[] = ['work', 'knowledge', 'governance', 'system'];

/** 应用壳层：顶部数据源上下文 + 左侧一级导航 + 主内容区。 */
export function AppShell() {
  const [searchParams] = useSearchParams();
  const alias = searchParams.get('alias');
  const navigate = useNavigate();
  const location = useLocation();
  const mainRef = useRef<HTMLElement>(null);
  const previousPath = useRef(location.pathname);

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
  const graphStatusError = aliases.isError && !aliases.data;
  const names = aliases.data?.aliases.map((a) => a.name) ?? [];

  const switchAlias = (next: string, replace = false) => {
    const page = location.pathname.split('/').pop() || 'sql';
    navigate(navPath(page, next), { replace });
  };

  const [draft, setDraft] = useState(alias ?? '');
  useEffect(() => setDraft(alias ?? ''), [alias]);

  const [collapsed, setCollapsed] = useState(
    () => localStorage.getItem('shell-nav-collapsed') === '1',
  );
  useEffect(() => {
    localStorage.setItem('shell-nav-collapsed', collapsed ? '1' : '0');
  }, [collapsed]);

  useEffect(() => {
    if (previousPath.current === location.pathname) return;
    previousPath.current = location.pathname;
    if (mainRef.current) {
      mainRef.current.scrollTop = 0;
      mainRef.current.focus();
    }
  }, [location.pathname]);

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

  const graphTone: Tone = aliases.isPending ? 'warn' : graphAvailable ? 'ok' : 'bad';
  const graphStatus = aliases.isPending
    ? '加载中'
    : graphStatusError
      ? '读取失败'
      : graphAvailable
        ? '已导入'
        : '未导入';
  const graphDetail = aliases.isPending
    ? '正在读取图谱状态'
    : graphStatusError
      ? '无法读取数据源状态，请重试'
      : graphAvailable
        ? '图谱已导入'
        : '图谱未导入，到工作台导入';

  const connectionTone: Tone = connection.isPending
    ? 'warn'
    : connection.data?.success
      ? 'ok'
      : 'bad';
  const connectionStatus = connection.isPending
    ? '连接中'
    : connection.data?.success
      ? '正常'
      : '失败';
  const connectionDetail = connection.isPending
    ? '正在测试连接'
    : connection.data?.success
      ? `连接正常${connection.data.serverVersion ? ` · ${connection.data.serverVersion}` : ''}`
      : `连接失败${connection.data?.message ? `：${connection.data.message}` : ''}`;

  const indexState = index.data?.status ?? 'unknown';
  const indexLoading = aliases.isPending || (graphAvailable && index.isPending);
  const indexTone: Tone = indexLoading
    ? 'warn'
    : graphStatusError || !graphAvailable
      ? 'bad'
      : indexState === 'ready'
        ? 'ok'
        : indexState === 'stale' || indexState === 'unknown'
          ? 'warn'
          : 'bad';
  const indexDetail = indexLoading
    ? '正在读取索引状态'
    : graphStatusError
      ? '无法读取数据源状态，请重试'
      : !graphAvailable
        ? '图谱未导入，索引不可用'
        : indexState === 'ready'
          ? '搜索索引已就绪'
          : indexState === 'stale'
            ? '图谱已变更，索引待重建（图谱页可重建）'
            : indexState === 'missing'
              ? '索引缺失，搜索不可用'
              : '索引状态未知';
  const indexStatus = indexLoading
    ? '加载中'
    : graphStatusError
      ? '读取失败'
      : !graphAvailable
        ? '不可用'
        : indexState === 'ready'
          ? '就绪'
          : indexState === 'stale'
            ? '待重建'
            : indexState === 'missing'
              ? '缺失'
              : '未知';

  return (
    <div className="shell" data-nav-collapsed={collapsed || undefined}>
      <a className="shell-skip-link" href="#main-content">跳到主内容</a>
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
          <div className="shell-status" role="group" aria-label="数据源状态">
            <StatusDot label="图谱" status={graphStatus} tone={graphTone} detail={graphDetail} />
            <StatusDot label="连接" status={connectionStatus} tone={connectionTone} detail={connectionDetail} />
            <StatusDot label="索引" status={indexStatus} tone={indexTone} detail={indexDetail} />
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
              <section className={`shell-nav-group shell-nav-group-${group}`} key={group}>
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

      <main id="main-content" ref={mainRef} tabIndex={-1} className="shell-main">
        <Suspense fallback={<p className="shell-route-loading" role="status">正在加载页面…</p>}>
          <Outlet context={{
            alias,
            graphAvailable,
            graphStatusLoading: aliases.isPending,
            graphStatusError,
          }} />
        </Suspense>
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
      <Link to={navPath('reviews', alias)} onClick={dismiss}>
        去评审
      </Link>
      <button type="button" onClick={dismiss} title="知道了" aria-label="关闭提示">×</button>
    </div>
  );
}

function StatusDot({ label, status, tone, detail }: {
  label: string;
  status: string;
  tone: Tone;
  detail: string;
}) {
  const detailId = useId();
  return (
    <span
      className="shell-dot"
      data-tone={tone}
      tabIndex={0}
      role="group"
      aria-label={`${label}：${status}`}
      aria-describedby={detailId}
    >
      <span className="shell-dot-mark" aria-hidden="true" />
      <span className="shell-dot-label" aria-hidden="true">{label}</span>
      <span className="shell-dot-status" aria-hidden="true">{status}</span>
      <span className="shell-dot-tooltip" aria-hidden="true">{detail}</span>
      <span id={detailId} className="sr-only">{detail}</span>
    </span>
  );
}
