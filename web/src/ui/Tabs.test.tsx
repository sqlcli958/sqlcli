import { useState } from 'react';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { expect, test } from 'vitest';
import { Tabs } from './Tabs';

const ITEMS = [
  { key: 'a', label: '待审批', badge: 3 },
  { key: 'b', label: '审批记录' },
  { key: 'c', label: '执行记录' },
] as const;

function Harness() {
  const [value, setValue] = useState<'a' | 'b' | 'c'>('a');
  return <Tabs label="测试" items={ITEMS} value={value} onChange={setValue} />;
}

test('方向键在标签之间移动并循环', async () => {
  render(<Harness />);
  const tabs = screen.getAllByRole('tab');
  tabs[0].focus();

  await userEvent.keyboard('{ArrowRight}');
  expect(tabs[1].getAttribute('aria-selected')).toBe('true');

  // 末尾再按一次回到第一个——不循环的话最后一项右键就是死路
  await userEvent.keyboard('{ArrowRight}{ArrowRight}');
  expect(tabs[0].getAttribute('aria-selected')).toBe('true');

  await userEvent.keyboard('{End}');
  expect(tabs[2].getAttribute('aria-selected')).toBe('true');
  await userEvent.keyboard('{Home}');
  expect(tabs[0].getAttribute('aria-selected')).toBe('true');
});

/** roving tabindex：整组标签在 Tab 键序列里只占一个位置，组内靠方向键走。 */
test('只有选中项进 Tab 键序列', async () => {
  render(<Harness />);
  const tabs = screen.getAllByRole('tab');
  expect(tabs.map((tab) => tab.getAttribute('tabindex'))).toEqual(['0', '-1', '-1']);

  await userEvent.click(tabs[2]);
  expect(tabs.map((tab) => tab.getAttribute('tabindex'))).toEqual(['-1', '-1', '0']);
});

test('角标为 0 时不渲染', () => {
  render(
    <Tabs
      label="测试"
      items={[{ key: 'a', label: '待审批', badge: 0 }]}
      value="a"
      onChange={() => {}}
    />,
  );
  expect(screen.queryByText('0')).toBeNull();
});
