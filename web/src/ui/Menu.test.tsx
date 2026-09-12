import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { expect, test, vi } from 'vitest';
import { ActionIcon } from './ActionIcon';
import { Menu, MenuItem } from './Menu';

function Harness({ onDelete = vi.fn() }: { onDelete?: () => void }) {
  return (
    <>
      <Menu trigger={<ActionIcon action="more" label="更多操作" />}>
        <MenuItem onSelect={() => {}}>设置密码</MenuItem>
        <MenuItem danger onSelect={onDelete}>删除数据源</MenuItem>
      </Menu>
      <button type="button">页面上的别的东西</button>
    </>
  );
}

test('点触发按钮打开，选中项回调后自动关闭', async () => {
  const onDelete = vi.fn();
  render(<Harness onDelete={onDelete} />);

  await userEvent.click(screen.getByRole('button', { name: '更多操作' }));
  await userEvent.click(await screen.findByRole('menuitem', { name: '删除数据源' }));

  expect(onDelete).toHaveBeenCalledTimes(1);
  expect(screen.queryByRole('menuitem')).toBeNull();
});

/** 手写版本缺的就是这两条：点外面不关、ESC 不关。 */
test('Escape 关闭并把焦点还给触发按钮', async () => {
  render(<Harness />);
  const trigger = screen.getByRole('button', { name: '更多操作' });

  await userEvent.click(trigger);
  expect(await screen.findByRole('menu')).toBeTruthy();

  await userEvent.keyboard('{Escape}');
  expect(screen.queryByRole('menu')).toBeNull();
  expect(document.activeElement).toBe(trigger);
});

test('方向键在菜单项之间移动', async () => {
  render(<Harness />);
  await userEvent.click(screen.getByRole('button', { name: '更多操作' }));
  await screen.findByRole('menu');

  await userEvent.keyboard('{ArrowDown}');
  expect(screen.getByRole('menuitem', { name: '设置密码' })).toHaveAttribute('data-highlighted');
  await userEvent.keyboard('{ArrowDown}');
  expect(screen.getByRole('menuitem', { name: '删除数据源' })).toHaveAttribute('data-highlighted');
});
