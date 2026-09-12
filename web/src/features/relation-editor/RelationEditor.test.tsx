import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { expect, test, vi } from 'vitest';
import { RelationEditor } from './RelationEditor';

test('revision conflict is explained and the user can retry saving', async () => {
  const fetchMock = vi.fn()
    .mockResolvedValueOnce(new Response(JSON.stringify({
      code: 'REVISION_CONFLICT',
      message: 'workspace revision mismatch',
    }), {
      status: 409,
      headers: { 'Content-Type': 'application/json' },
    }))
    .mockResolvedValueOnce(new Response(JSON.stringify({
      revision: 8,
      changeId: 'change-8',
      validationIssues: [],
    }), {
      status: 200,
      headers: { 'Content-Type': 'application/json' },
    }));
  vi.stubGlobal('fetch', fetchMock);
  const onSaved = vi.fn();
  const user = userEvent.setup();
  render(
    <RelationEditor
      mode="create"
      defaultFrom="column:demo:app.orders.customer_id"
      defaultTo="column:demo:app.customers.id"
      lockEndpoints
      revision={7}
      onSaved={onSaved}
      onCancel={() => undefined}
    />,
  );
  await user.type(screen.getByLabelText(/变更原因/), 'confirm relation');

  await user.click(screen.getByRole('button', { name: '保存' }));
  expect(await screen.findByText(/工作区已被其他会话修改/)).toBeVisible();

  await user.click(screen.getByRole('button', { name: '保存' }));
  expect(onSaved).toHaveBeenCalledWith(expect.objectContaining({ revision: 8 }));
});

test('lock conflict keeps the draft and allows retrying', async () => {
  const fetchMock = vi.fn()
    .mockResolvedValueOnce(new Response(JSON.stringify({
      code: 'WORKSPACE_LOCKED',
      message: 'write lock busy',
    }), {
      status: 423,
      headers: { 'Content-Type': 'application/json' },
    }))
    .mockResolvedValueOnce(new Response(JSON.stringify({
      revision: 9,
      changeId: 'change-9',
      validationIssues: [],
    }), {
      status: 200,
      headers: { 'Content-Type': 'application/json' },
    }));
  vi.stubGlobal('fetch', fetchMock);
  const onSaved = vi.fn();
  const user = userEvent.setup();
  render(
    <RelationEditor
      mode="create"
      defaultFrom="column:demo:app.orders.customer_id"
      defaultTo="column:demo:app.customers.id"
      lockEndpoints
      revision={8}
      onSaved={onSaved}
      onCancel={() => undefined}
    />,
  );
  await user.type(screen.getByLabelText(/变更原因/), 'keep this draft');

  await user.click(screen.getByRole('button', { name: '保存' }));
  expect(await screen.findByText(/工作区正在被修改/)).toBeVisible();
  expect(screen.getByLabelText(/变更原因/)).toHaveValue('keep this draft');

  await user.click(screen.getByRole('button', { name: '保存' }));
  expect(onSaved).toHaveBeenCalledWith(expect.objectContaining({ revision: 9 }));
});

test('network failure keeps the draft and allows retrying', async () => {
  const fetchMock = vi.fn()
    .mockRejectedValueOnce(new TypeError('Failed to fetch'))
    .mockResolvedValueOnce(new Response(JSON.stringify({
      revision: 10,
      changeId: 'change-10',
      validationIssues: [],
    }), {
      status: 200,
      headers: { 'Content-Type': 'application/json' },
    }));
  vi.stubGlobal('fetch', fetchMock);
  const onSaved = vi.fn();
  const user = userEvent.setup();
  render(
    <RelationEditor
      mode="create"
      defaultFrom="column:demo:app.orders.customer_id"
      defaultTo="column:demo:app.customers.id"
      lockEndpoints
      revision={9}
      onSaved={onSaved}
      onCancel={() => undefined}
    />,
  );
  await user.type(screen.getByLabelText(/变更原因/), 'retry after network');

  await user.click(screen.getByRole('button', { name: '保存' }));
  expect(await screen.findByText(/网络错误：无法保存/)).toBeVisible();
  expect(screen.getByLabelText(/变更原因/)).toHaveValue('retry after network');

  await user.click(screen.getByRole('button', { name: '保存' }));
  expect(onSaved).toHaveBeenCalledWith(expect.objectContaining({ revision: 10 }));
});
