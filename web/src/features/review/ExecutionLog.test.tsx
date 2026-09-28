import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, expect, test, vi } from 'vitest';
import { ExecutionLog, isWrite } from './ExecutionLog';
import { formatSql } from './formatSql';
import type { GraphChangeDto, SqlExecutionRecordDto } from '../../types/api';

vi.mock('../../api/executions', () => ({
  getExecutionHistory: vi.fn(),
  getRecoveryPreview: vi.fn(),
  executeRollback: vi.fn(),
}));
vi.mock('../../api/graphChanges', () => ({
  listGraphChanges: vi.fn(),
}));

const { getExecutionHistory, getRecoveryPreview, executeRollback } = await import('../../api/executions');
const { listGraphChanges } = await import('../../api/graphChanges');

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

const graphChange: GraphChangeDto = {
  id: 8,
  alias: 'demo',
  operation: 'upsert_column',
  targetId: 'column:demo:APP.ORDERS.USER_ID',
  actor: 'agent',
  revisionBefore: 5,
  revisionAfter: 6,
  createdAt: 1_700_000_100_000,
  approvalId: 7,
  reason: '补充字段业务描述',
  payload: {
    targetId: 'column:demo:APP.ORDERS.USER_ID',
    operation: 'upsert_column',
    actor: 'agent',
    baseRevision: 5,
    before: { description: '旧描述', businessName: '用户' },
    after: { description: '下单用户 ID', businessName: '用户' },
  },
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
  vi.mocked(listGraphChanges).mockResolvedValue({
    changes: [graphChange],
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

test('执行记录包含图谱变更，默认折叠，点击详情后才展开具体内容', async () => {
  renderLog();

  expect(await screen.findByText('APP.ORDERS.USER_ID')).toBeTruthy();
  expect(screen.getByText('upsert_column')).toBeTruthy();
  expect(screen.queryByText('旧描述')).toBeNull();
  expect(screen.queryByText('下单用户 ID')).toBeNull();

  await userEvent.click(screen.getByRole('button', { name: '详情' }));

  expect(await screen.findByText('旧描述')).toBeTruthy();
  expect(screen.getByText('下单用户 ID')).toBeTruthy();
  expect(screen.getByText('r5 → r6')).toBeTruthy();
  expect(screen.getByText('#7')).toBeTruthy();
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

test('Execution → Recovery 提交后链接明确回到待审批标签', async () => {
  vi.mocked(getRecoveryPreview).mockResolvedValue({
    rollback: "UPDATE sys_menu SET visible=0 WHERE id='9527'",
    backup: '[{"id":"9527","visible":0}]',
  } as never);
  vi.mocked(executeRollback).mockResolvedValue({
    executionId: 1,
    statements: 1,
    approvalId: 77,
    status: 'pending',
  });

  renderLog();
  await userEvent.click(await screen.findByRole('button', { name: '回滚' }));
  expect(await screen.findByText(/visible=0/)).toBeInTheDocument();

  await userEvent.click(screen.getByRole('button', { name: '提交回滚' }));
  await userEvent.click(screen.getByRole('button', { name: '确认提交' }));

  await waitFor(() => expect(executeRollback).toHaveBeenCalledWith(1));
  expect(await screen.findByRole('link', { name: '去待审批裁决' })).toHaveAttribute(
    'href',
    '/workspaces/local/reviews?alias=demo&tab=pending',
  );
});
