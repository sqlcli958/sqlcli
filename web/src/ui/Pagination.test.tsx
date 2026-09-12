import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { expect, test, vi } from 'vitest';
import { Pagination } from './Pagination';

// 只有一页也要显示：位置固定的控件突然消失，比一直摆在那儿更让人找不着。
test('一页也显示，两个按钮都禁用', () => {
  render(<Pagination page={0} pageSize={20} total={12} onPage={vi.fn()} />);
  expect(screen.getByText('第 1 / 1 页')).toBeTruthy();
  expect(screen.getByRole('button', { name: '上一页' })).toHaveProperty('disabled', true);
  expect(screen.getByRole('button', { name: '下一页' })).toHaveProperty('disabled', true);
});

test('一条都没有时也不塌成 0 页', () => {
  render(<Pagination page={0} pageSize={20} total={0} onPage={vi.fn()} />);
  expect(screen.getByText('第 1 / 1 页')).toBeTruthy();
});

test('总页数向上取整，末页的下一页禁用', async () => {
  const onPage = vi.fn();
  render(<Pagination page={2} pageSize={20} total={41} onPage={onPage} />);

  // 41 条 / 每页 20 = 3 页；总条数放在 title 里，不占正文
  expect(screen.getByText('第 3 / 3 页')).toBeTruthy();
  expect(screen.getByTitle('共 41 条')).toBeTruthy();
  expect(screen.getByRole('button', { name: '下一页' })).toHaveProperty('disabled', true);

  await userEvent.click(screen.getByRole('button', { name: '上一页' }));
  expect(onPage).toHaveBeenCalledWith(1);
});

test('给了 onPageSize 才出现每页条数选择', async () => {
  const onPageSize = vi.fn();
  const { rerender } = render(<Pagination page={0} pageSize={20} total={99} onPage={vi.fn()} />);
  expect(screen.queryByLabelText('每页条数')).toBeNull();

  rerender(
    <Pagination page={0} pageSize={20} total={99} onPage={vi.fn()} onPageSize={onPageSize} />,
  );
  await userEvent.selectOptions(screen.getByLabelText('每页条数'), '50');
  expect(onPageSize).toHaveBeenCalledWith(50);
});

test('首页的上一页禁用', () => {
  render(<Pagination page={0} pageSize={20} total={41} onPage={vi.fn()} />);
  expect(screen.getByRole('button', { name: '上一页' })).toHaveProperty('disabled', true);
  expect(screen.getByRole('button', { name: '下一页' })).toHaveProperty('disabled', false);
});
