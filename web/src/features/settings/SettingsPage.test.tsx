import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { expect, test, vi } from 'vitest';
import { SettingsPage } from './SettingsPage';

const updateAlias = vi.hoisted(() => vi.fn(async () => ({ name: 'demo', status: 'updated' })));
vi.mock('../../api/aliases', async () => ({
  ...await vi.importActual<typeof import('../../api/aliases')>('../../api/aliases'),
  getAliases: vi.fn(async () => ({ aliases: [{ name: 'demo', readOnly: false, graphAvailable: true,
    approveQuery: false, approveUpdate: true, approveGraph: true }] })),
  updateAlias,
}));

test('关闭审批先确认，取消不发请求，确认后才保存', async () => {
  render(<QueryClientProvider client={new QueryClient()}><SettingsPage /></QueryClientProvider>);
  const checkbox = await screen.findByRole('checkbox', { name: '写入' });
  await userEvent.click(checkbox);
  expect(screen.getByRole('dialog')).toHaveTextContent('数据源「demo」');
  expect(updateAlias).not.toHaveBeenCalled();
  await userEvent.click(screen.getByRole('button', { name: '保留保护' }));
  expect(checkbox).toBeChecked();
  expect(updateAlias).not.toHaveBeenCalled();
  await userEvent.click(checkbox);
  await userEvent.click(screen.getByRole('button', { name: '确认关闭保护' }));
  expect(updateAlias).toHaveBeenCalledWith('demo', { approveUpdate: false });
  expect(await screen.findByText('已保存')).toBeInTheDocument();
});
