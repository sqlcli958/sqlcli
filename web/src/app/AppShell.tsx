import { useEffect, useState } from 'react';
import { Link, NavLink, Outlet, useLocation, useNavigate, useSearchParams } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { getSession } from '../api/session';
import { getAliases, testAlias } from '../api/aliases';
import { getIndexStatus } from '../api/indexApi';
import { getApprovals } from '../api/approvals';
import { useSessionStore } from '../state/sessionStore';
import { useGraphStore } from '../state/graphStore';
import { NAV_ITEMS, navPath } from './navigation';
import './app-shell.css';
import { Button } from '../ui/Button';

/**
 * 应用壳层：顶部数据源上下文 + 左侧一级导航 + 主内容区。
 *
 * 数据源以 ?alias= 承载而不是放进路径，后端 /api/* 本来就从 query 读 alias
 * （GraphUiApiRouter:80），这样切数据源不需要动 api/client.ts。
 */
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

  /** 切数据源保留当前页面，只换上下文。 */
  const switchAlias = (next: string, replace = false) => {
    const page = location.pathname.split('/').pop() || 'sql';
    navigate(navPath(page, next), { replace });
  };

  // 输入框的本地值。URL 变了要跟着回填，半截输入失焦时也回退到当前数据源。
  const [draft, setDraft] = useState(alias ?? '');
  useEffect(() => setDraft(alias ?? ''), [alias]);

  // 导航折叠。存 localStorage 而不是 store：这是这台机器的显示偏好，
  // 不跟数据源走，也没必要参与任何状态同步。
  // **默认折叠**：七项一级导航常驻文字要占掉一条竖栏，而页面主体才是干活的地方；
  // 图标各不相同，认图标比读文字快，名字停留在图标上就出来（title）。
  const [collapsed, setCollapsed] = useState(
    () => localStorage.getItem('shell-nav-collapsed') !== '0',
  );
  useEffect(() => {
    localStorage.setItem('shell-nav-collapsed', collapsed ? '1' : '0');
  }, [collapsed]);

  // 没选数据源时自动选一个（优先已导入图谱的），省掉"先去目录点一下"这步。
  useEffect(() => {
    if (alias || !aliases.data?.aliases.length) return;
    const first = aliases.data.aliases.find((a) => a.graphAvailable) ?? aliases.data.aliases[0];
    switchAlias(first.name, true);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [alias, aliases.data]);

  // 会话跟随 URL 上的数据源。切换 alias 必须重新握手，否则 token 和 revision 会串。
  const session = useQuery({
    queryKey: ['session', alias],
    queryFn: ({ signal }) => getSession(signal),
    enabled: Boolean(alias) && graphAvailable,
    retry: false,
  });

  // 换库要清两样。选中的表：id 带 schema 前缀，拿到另一个库去查要么 404 要么没有工作区。
  // schema 筛选：两个库的 schema 叫法可能完全不同（erp_plush_test 的是 erp_plush_test，
  // erp_plush_prod 的是 erp-plush），带过去等于筛掉全部，画布空白而图谱好好的。
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

  // 连接状态要真的连一次数据库，所以缓存 5 分钟、不跟随窗口聚焦重查。
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

  /**
   * 待审批角标。**跨数据源**（后端返回的 `pending` 本来就不跟筛选条件走）：
   * CLI 在别的库上排的队，不该因为顶栏选中的是另一个别名就看不见。
   *
   * 没有它，队列里的东西只有主动点进评审页才知道——实测 6 条待审批从提交那天
   * 一直挂着没人裁决，而 agent 提交完只打印一句就走了，之后再没有东西提起它。
   * 轮询 30 秒：裁决是人的手速，另一个进程刚排进来的队晚半分钟看见没有代价。
   */
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
          {/* input + datalist：原生就带前缀搜索，别名多起来不用翻长下拉。
              只有输入值命中某个别名时才导航，否则打字过程中会一路乱跳。 */}
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
              if (next && next !== alias && names.includes(next)) {
                switchAlias(next);
              }
            }}
            onFocus={(e) => e.target.select()}
            onBlur={() => setDraft(alias ?? '')}
          />
          <datalist id="shell-alias-list">
            {names.map((name) => (
              <option key={name} value={name} />
            ))}
          </datalist>
        </label>

        {alias && (
          <div className="shell-status" role="status">
            {/* 三个状态灯集中在这里：图谱 / 连接 / 索引。
                文字改成 title，鼠标停留才展开——状态本身用颜色表达就够了。 */}
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

        <ul>
          {NAV_ITEMS.map((item) => {
            const disabled = item.needsAlias && !alias;
            const reason = disabled ? '请先选择数据源' : undefined;

            // 收起后只剩图标，标题必须补进 title；展开时 title 只在被禁用时才有话说
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
                {/* 收起时靠 CSS 藏起来而不是不渲染：读屏仍然读得到页面名 */}
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
                    className={({ isActive }) =>
                      `shell-nav-item${isActive ? ' is-active' : ''}`
                    }
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
      </nav>

      <main className="shell-main">
        <Outlet context={{ alias, graphAvailable }} />
      </main>
    </div>
  );
}

type Tone = 'ok' | 'warn' | 'bad';

/** 状态灯：绿=正常，黄=可用但需注意或未知，红=不可用。文字只在 title 里。 */
/**
 * 「刚才那次保存没有生效，排队等审批了」。
 *
 * 别名开了图谱审批之后，UI 里改一个表描述不会写图谱，只会建一条待审批——
 * 编辑器照常关掉，看着像存成功了。这是「先落 db、批准后才写图谱」唯一会骗到人的
 * 地方，所以要说出来，并给一条直达评审页图谱标签的路。
 *
 * 放顶栏底下而不是各个编辑器里：写操作有十个入口，提示只该有一份（「状态集中」）。
 */
function PendingApprovalNotice({ alias }: { alias: string | null }) {
  const pending = useSessionStore((state) => state.pendingApproval);
  const dismiss = useSessionStore((state) => state.dismissPendingApproval);
  if (pending == null) return null;
  return (
    <div className="shell-notice" role="status">
      <span>本次修改已提交审批 #{pending}，批准后才会写入图谱。</span>
      <Link
        to={`${navPath('reviews', alias)}${alias ? '&' : '?'}tab=graph`}
        onClick={dismiss}
      >
        去评审
      </Link>
      <button type="button" onClick={dismiss} title="知道了" aria-label="关闭提示">×</button>
    </div>
  );
}

function StatusDot({ label, tone, detail }: { label: string; tone: Tone; detail: string }) {
  // 用 data-tip 自绘气泡而不是原生 title：原生 title 要停留一秒才出来，
  // 三个 9px 的圆点本来就难瞄准，等一秒才知道是什么等于没有。
  return (
    <span className="shell-dot" data-tone={tone} data-tip={`${label}：${detail}`} tabIndex={0}>
      <span className="shell-dot-mark" aria-hidden="true" />
      <span className="sr-only">{`${label}：${detail}`}</span>
    </span>
  );
}
