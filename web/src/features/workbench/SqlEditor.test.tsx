import { createRef } from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, expect, test, vi } from 'vitest';
import { SqlEditor } from './SqlEditor';
import type { AliasSummaryDto, WorkbenchExecuteResultDto } from '../../types/api';

const executeWorkbenchSql = vi.hoisted(() => vi.fn());
const cancelWorkbenchSql = vi.hoisted(() => vi.fn());
vi.mock('../../api/workbench', () => ({ executeWorkbenchSql, cancelWorkbenchSql }));

const alias: AliasSummaryDto = {
  name: 'demo',
  readOnly: false,
  graphAvailable: true,
  approveQuery: false,
  approveUpdate: true,
  approveGraph: false,
};

function ok(patch: Partial<WorkbenchExecuteResultDto> = {}): WorkbenchExecuteResultDto {
  return {
    status: 'SUCCEEDED',
    sqlType: 'UPDATE',
    columns: [],
    rows: [],
    rowCount: 0,
    truncated: false,
    elapsedMs: 4,
    affectedRows: null,
    recoveryId: null,
    taskId: null,
    precheck: null,
    errorSummary: null,
    ...patch,
  };
}

function renderEditor(sql: string) {
  render(
    <MemoryRouter>
      <QueryClientProvider client={new QueryClient({ defaultOptions: { mutations: { retry: false } } })}>
        <SqlEditor
          alias="demo"
          aliasInfo={alias}
          sql={sql}
          onSqlChange={() => {}}
          textareaRef={createRef<HTMLTextAreaElement>()}
        />
      </QueryClientProvider>
    </MemoryRouter>,
  );
}

beforeEach(() => {
  executeWorkbenchSql.mockReset();
  cancelWorkbenchSql.mockReset();
});

test('写语句先预检再确认，第二次请求才是真执行', async () => {
  executeWorkbenchSql
    .mockResolvedValueOnce(ok({
      precheck: { table: 'orders', recoverySupported: true, primaryKey: ['id'], estimatedRows: 3, skippedReason: null },
    }))
    .mockResolvedValueOnce(ok({ affectedRows: 3, recoveryId: 12 }));

  renderEditor("UPDATE orders SET state = 1 WHERE id = 7");
  // 别名开了 approveUpdate，按钮就该说「提交审批」而不是「执行」
  await userEvent.click(screen.getByRole('button', { name: '提交审批' }));

  await screen.findByText('确认执行 UPDATE');
  expect(executeWorkbenchSql).toHaveBeenCalledWith(expect.stringContaining('UPDATE'), true);
  expect(screen.getByText('3 行')).toBeTruthy();
  expect(screen.getByText('会生成恢复 SQL')).toBeTruthy();

  await userEvent.click(screen.getByRole('button', { name: '确认执行' }));
  await waitFor(() => expect(executeWorkbenchSql).toHaveBeenCalledTimes(2));
  // 真执行带上中止令牌，预检不带
  expect(executeWorkbenchSql).toHaveBeenLastCalledWith(
    expect.stringContaining('UPDATE'), false, expect.any(String));
  expect(await screen.findByText(/影响 3 行/)).toBeTruthy();
});

test('只读语句一次直达，没有确认面板', async () => {
  executeWorkbenchSql.mockResolvedValueOnce(ok({
    sqlType: 'SELECT',
    columns: [{ name: 'id', type: 'BIGINT' }],
    rows: [[1]],
    rowCount: 1,
  }));

  renderEditor('SELECT id FROM orders');
  await userEvent.click(screen.getByRole('button', { name: '执行' }));

  await waitFor(() =>
    expect(executeWorkbenchSql).toHaveBeenCalledWith('SELECT id FROM orders', false, expect.any(String)));
  expect(await screen.findByRole('table')).toBeTruthy();
  expect(screen.queryByText(/确认执行/)).toBeNull();
});

test('执行中出现「中止」按钮，中止成功后被打断的失败显示为已中止', async () => {
  let rejectRun!: (e: Error) => void;
  executeWorkbenchSql.mockImplementationOnce(
    () => new Promise((_, reject) => { rejectRun = reject; }));
  cancelWorkbenchSql.mockResolvedValueOnce({ cancelled: true });

  renderEditor('SELECT * FROM big_table');
  await userEvent.click(screen.getByRole('button', { name: '执行' }));

  await userEvent.click(await screen.findByRole('button', { name: '中止' }));
  expect(cancelWorkbenchSql).toHaveBeenCalledWith(expect.any(String));

  // 被 Statement.cancel() 打断的执行以失败收场——但这是用户要的中止，不是错误
  rejectRun(new Error('Query execution was interrupted'));
  expect(await screen.findByText('已中止。')).toBeTruthy();
  expect(screen.queryByText(/执行失败/)).toBeNull();
});

test('REJECTED 是 200 的业务结果，要把原因展示出来', async () => {
  executeWorkbenchSql.mockResolvedValueOnce(ok({
    status: 'REJECTED',
    sqlType: 'SELECT',
    errorSummary: "Alias 'demo' is configured as readonly.",
  }));

  renderEditor('SELECT 1');
  await userEvent.click(screen.getByRole('button', { name: '执行' }));
  expect(await screen.findByRole('alert')).toHaveTextContent('已拒绝');
});
