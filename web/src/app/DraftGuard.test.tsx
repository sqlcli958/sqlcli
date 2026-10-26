import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Link, Outlet, RouterProvider, createMemoryRouter } from 'react-router-dom';
import { expect, test, vi } from 'vitest';
import { AliasAddPage } from '../features/alias-home/AliasAddPage';
import { DraftGuard } from './DraftGuard';

vi.mock('../api/drivers', () => ({ getDrivers: vi.fn(async () => []) }));

test('真实数据源表单阻止未保存的页面跳转，取消保留输入，确认才离开', async () => {
  const router = createMemoryRouter([{ element: <DraftGuard><Outlet /></DraftGuard>, children: [
    { path: '/', element: <><AliasAddPage onDone={() => {}} /><Link to="/next">工作台</Link></> },
    { path: '/next', element: <h1>已到工作台</h1> },
  ] }]);
  render(<QueryClientProvider client={new QueryClient()}><RouterProvider router={router} /></QueryClientProvider>);
  await userEvent.type(screen.getByLabelText('名称'), 'draft-demo');
  await userEvent.click(screen.getByRole('link', { name: '工作台' }));
  expect(await screen.findByRole('dialog')).toHaveTextContent('未保存');
  await userEvent.click(screen.getByRole('button', { name: '继续编辑' }));
  expect(screen.getByLabelText('名称')).toHaveValue('draft-demo');
  await userEvent.click(screen.getByRole('link', { name: '工作台' }));
  await userEvent.click(await screen.findByRole('button', { name: '放弃修改' }));
  expect(await screen.findByRole('heading', { name: '已到工作台' })).toBeInTheDocument();
});
