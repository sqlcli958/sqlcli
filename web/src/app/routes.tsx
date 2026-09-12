import { Navigate, createBrowserRouter, useLocation, useSearchParams } from 'react-router-dom';
import { AppShell } from './AppShell';
import { WORKSPACE_BASE } from './navigation';
import { WorkbenchPage } from '../features/workbench/WorkbenchPage';
import { KnowledgePage } from '../features/knowledge/KnowledgePage';
import { MetricsPage } from '../features/metrics/MetricsPage';
import { RulesPage } from '../features/policy/RulesPage';
import { EvalPage } from '../features/eval/EvalPage';
import { SettingsPage } from '../features/settings/SettingsPage';
import { ReviewsPage } from '../features/review/ReviewsPage';

/** 保留当前 query（数据源上下文）跳转到同一 workspace 下的另一页。 */
function KeepQuery({ to }: { to: string }) {
  const { search } = useLocation();
  return <Navigate to={`${WORKSPACE_BASE}/${to}${search}`} replace />;
}

/**
 * 旧链接兼容。
 *
 * 老 UI 用 `?alias=` + `?view=` 在一个路径上分派页面。这里只做一次性跳转，
 * 不保留两套状态——P0 清单 3.3 明确要求不长期并存。
 */
function LegacyRedirect() {
  const [params] = useSearchParams();
  const alias = params.get('alias');
  const view = params.get('view');

  const page =
    view === 'rules'
      ? 'rules'
      : view === 'add-alias'
        ? 'settings'
        : alias && view !== 'executions'
          ? 'knowledge'
          : 'sql';

  const search = alias ? `?alias=${encodeURIComponent(alias)}` : '';
  return <Navigate to={`${WORKSPACE_BASE}/${page}${search}`} replace />;
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
      { path: 'rules', element: <RulesPage /> },
      { path: 'eval', element: <EvalPage /> },
      { path: 'reviews', element: <ReviewsPage /> },
      // 并入工作台前的两个页面，旧书签一次性跳转
      { path: 'overview', element: <KeepQuery to="sql" /> },
      { path: 'operations', element: <KeepQuery to="sql" /> },
      { path: 'settings', element: <SettingsPage /> },
      { path: '*', element: <NotFound /> },
    ],
  },
  { path: '*', element: <NotFound /> },
]);
