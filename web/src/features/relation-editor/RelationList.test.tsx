import { MemoryRouter } from 'react-router-dom';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { expect, test } from 'vitest';
import { RelationList } from './RelationList';
import type { RelationEdgeDto } from '../../types/api';

function renderList(relations: RelationEdgeDto[]) {
  return render(
    <MemoryRouter>
      <RelationList
        title="下游"
        relations={relations}
        direction="out"
        revision={5}
        onRefresh={() => undefined}
      />
    </MemoryRouter>,
  );
}

const IGNORED_ID = 'relation:demo:join_observed:column:demo:app.orders.shop_id->column:demo:app.shops.id';

function ignoredRelation(): RelationEdgeDto {
  return {
    id: IGNORED_ID,
    kind: 'relation',
    status: 'ignored',
    type: 'join_observed',
    from: 'column:demo:app.orders.shop_id',
    to: 'column:demo:app.shops.id',
    confidence: 0.4,
    attributes: { rejectionReason: '字段命中率过低，误判' },
  };
}

function candidateRelation(): RelationEdgeDto {
  return {
    id: 'relation:demo:join_observed:column:demo:app.orders.user_id->column:demo:app.users.id',
    kind: 'relation',
    status: 'candidate',
    type: 'join_observed',
    from: 'column:demo:app.orders.user_id',
    to: 'column:demo:app.users.id',
    confidence: 0.7,
  };
}

function publishedRelation(): RelationEdgeDto {
  return {
    id: 'relation:demo:join_observed:column:demo:app.orders.user_id->column:demo:app.users.id',
    kind: 'relation',
    status: 'partial',
    type: 'join_observed',
    from: 'column:demo:app.orders.user_id',
    to: 'column:demo:app.users.id',
    confidence: 0.7,
  };
}

test('已忽略的关系单独分组、默认折叠，展开行后能读到拒绝原因', async () => {
  const user = userEvent.setup();
  renderList([publishedRelation(), ignoredRelation()]);

  const ignoredHeader = screen.getByRole('button', { name: /下游 · 已忽略/ });
  // 默认折叠：拒绝原因这时不在文档里
  expect(screen.queryByText('字段命中率过低，误判')).not.toBeInTheDocument();

  await user.click(ignoredHeader);
  // 展开分组后能看到这条边（direction=out 按 rel.to 取短名）
  const endpoint = await screen.findByText('shops.id');
  await user.click(endpoint.closest('.relation-list-row')!);

  expect(screen.getByText('字段命中率过低，误判')).toBeVisible();
});

// 评审动作（发布 / 拒绝 / 撤销）全在评审页的图谱标签里——图谱页一次只知道一张表的
// 关系，把动作留在这里等于让人挨张表翻才能清空候选队列。这里守的是「入口唯一」。
test('图谱页不再提供评审动作，只留一条去评审页的链接', async () => {
  const user = userEvent.setup();
  renderList([candidateRelation(), ignoredRelation()]);

  await user.click(screen.getByRole('button', { name: /下游 · 已忽略/ }));

  for (const label of ['发布', '拒绝', '撤销', '批量发布', '批量拒绝', '全选']) {
    expect(screen.queryByRole('button', { name: label })).toBeNull();
  }
  expect(screen.queryByLabelText('选中该候选关系')).toBeNull();

  const links = screen.getAllByRole('link', { name: '评审页' });
  expect(links).toHaveLength(2);
  expect(links[0]).toHaveAttribute('href', expect.stringContaining('tab=graph'));
});
