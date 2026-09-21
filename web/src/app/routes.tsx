import { Navigate, createBrowserRouter, useLocation, useSearchParams } from 'react-router-dom';
import { AppShell } from './AppShell';
import { WORKSPACE_BASE, navPath } from './navigation';
import { WorkbenchPage } from '../features/workbench/WorkbenchPage';
import { KnowledgePage } from '../features/knowledge/KnowledgePage';
import { MetricsPage } from '../features/metrics/MetricsPage';
import { SettingsPage } from '../features/settings/SettingsPage';
import { GovernancePage } from '../features/governance/GovernancePage';

/** 保留当前 query（数据源上下文）跳转到同一 workspace 下的另一页。 */
function KeepQuery({ to }: { to: string }) {
  const { search } = useLocation();
  return <Navigate to={`${WORKSPACE_BASE}/${to}${search}`} replace />;
}

/** 旧的一层功能路由收口到治理页，同时保留 alias 和评审子标签。 */
function GovernanceRedirect({ section }: { section: 'quality' | 'rules' | 'reviews' }) {
  const [params] = useSearchParams();
  const copy = new URLSearchParams(params);
  copy.set('section', section);
  const suffix = copy.toString();
  return <Navigate to={`${WORKSPACE_BASE}/governance${suffix ? `?${suffix}` : ''}`} replace />;
}

/**
 * 更早版本的 query 驱动入口兼容。
 * 新版只把“产品级任务”放一级路由，旧 view 会一次性落到新结构。
 */
function LegacyRedirect() {
  const [params] = useSearchParams();
  const alias = params.get('alias');
  const view = params.get('view');

  if (view === 'rules') {
    return <Navigate to={navPath('governance', alias, { section: 'rules' })} replace />;
  }
  if (view === 'executions') {
    return <Navigate to={navPath('governance', alias, { section: 'reviews', tab: 'executions' })} replace />;
  }
  if (view === 'add-alias') {
    return <Navigate to={navPath('settings', alias)} replace />;
  }
  if (alias) {
    return <Navigate to={navPath('knowledge', alias)} replace />;
  }
  return <Navigate to={navPath('sql', null)} replace />;
}

function NotFound() {
  return (
    <div className="page">
      <h1>页面不存在</h1>
      <p>
        <a href={`${WORKSPACE_BASE}/sql`}>返回工作台</a>
      </p>
    </div>
  );
}

export const router = createBrowserRouter([
  { path: '/', element: <LegacyRedirect /> },
  {
    path: WORKSPACE_BASE,
    element: <AppShell />,
    children: [
      { index: true, element: <KeepQuery to="sql" /> },
      { path: 'sql', element: <WorkbenchPage /> },
      { path: 'knowledge', element: <KnowledgePage /> },
      { path: 'metrics', element: <MetricsPage /> },
      { path: 'governance', element: <GovernancePage /> },
      { path: 'settings', element: <SettingsPage /> },

      // 旧书签兼容：功能仍然存在，只是归到治理的二级导航。
      { path: 'rules', element: <GovernanceRedirect section="rules" /> },
      { path: 'eval', element: <GovernanceRedirect section="quality" /> },
      { path: 'reviews', element: <GovernanceRedirect section="reviews" /> },

      // 并入工作台前的两个页面。
      { path: 'overview', element: <KeepQuery to="sql" /> },
      { path: 'operations', element: <KeepQuery to="sql" /> },
      { path: '*', element: <NotFound /> },
    ],
  },
  { path: '*', element: <NotFound /> },
]);
