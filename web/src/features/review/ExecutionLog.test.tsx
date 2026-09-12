import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, expect, test, vi } from 'vitest';
import { ExecutionLog, isWrite } from './ExecutionLog';
import { formatSql } from './formatSql';
import type { SqlExecutionRecordDto } from '../../types/api';

vi.mock('../../api/executions', () => ({
  getExecutionHistory: vi.fn(),
  getRecoveryPreview: vi.fn(),
  executeRollback: vi.fn(),
}));

const { getExecutionHistory } = await import('../../api/executions');

const failed: SqlExecutionRecordDto = {
  id: 1,
  alias: 'demo',
  sqlType: 'UPDATE',
  sql: "UPDATE sys_menu SET visible=1 WHERE id='<redacted>'",
  rawSql: "UPDATE sys_menu SET visible=1 WHERE id='9527'",
  status: 'failed',
  errorSummary: 'Unknown column visible',
  startedAt: 1_700_000_000_000,
  elapsedMs: 12,
  targetSchema: 'qm_pct',
};

function renderLog() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <MemoryRouter>
      <QueryClientProvider client={client}>
        <ExecutionLog alias="demo" />
      </QueryClientProvider>
    </MemoryRouter>,
  );
}

beforeEach(() => {
  vi.mocked(getExecutionHistory).mockResolvedValue({
    records: [failed],
    schemas: ['qm_pct'],
    total: 1,
  });
});

test('失败的语句也在列表里，点开能看到失败原因和完整 SQL', async () => {
  renderLog();

  const sqlButton = await screen.findByTitle('展开完整 SQL');
  await userEvent.click(sqlButton);

  expect(screen.getByText('失败原因')).toBeTruthy();
  expect(screen.getByText('Unknown column visible')).toBeTruthy();
  // 列表里是脱敏文本，展开后是原文——否则看不出改的是哪一行
  expect(screen.getByText(/id='9527'/)).toBeTruthy();
});

test('四个筛选条件都会带进请求', async () => {
  renderLog();
  await screen.findByTitle('展开完整 SQL');

  await userEvent.selectOptions(screen.getByLabelText('按 schema 筛选'), 'qm_pct');
  await userEvent.selectOptions(screen.getByLabelText('按语句类型筛选'), 'UPDATE');
  await userEvent.selectOptions(screen.getByLabelText('按执行状态筛选'), 'failed');
  await userEvent.selectOptions(screen.getByLabelText('按时间范围筛选'), '24h');

  await waitFor(() => {
    const calls = vi.mocked(getExecutionHistory).mock.calls;
    const filters = calls[calls.length - 1]?.[3];
    expect(filters?.schema).toBe('qm_pct');
    expect(filters?.type).toBe('UPDATE');
    expect(filters?.status).toBe('failed');
    expect(filters?.startedAfter).toBeGreaterThan(0);
  });
});

test('增删改才给回滚按钮', () => {
  expect(isWrite('UPDATE')).toBe(true);
  expect(isWrite('delete')).toBe(true);
  expect(isWrite('SELECT')).toBe(false);
  expect(isWrite(undefined)).toBe(false);
});

test('格式化不切开字符串字面量里的关键字', () => {
  expect(formatSql("SELECT * FROM t WHERE name = 'from a join b' AND id = 1")).toBe(
    ["SELECT *", "FROM t", "WHERE name = 'from a join b'", '  AND id = 1'].join('\n'),
  );
});
