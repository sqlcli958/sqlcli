import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { expect, test, vi } from 'vitest';
import { ColumnsEditor, RuleForm } from './RuleForm';
import { newRule } from './ruleSchema';

// NOT NULL 勾选框写回的是 nullable:false；取消勾选是「不检查」，键直接删掉——
// 写成 nullable:true 会变成「必须可空」，评估器会把 NOT NULL 的字段报成违规。
test('勾选 NOT NULL 写 nullable:false，取消则删掉该键', async () => {
  const onChange = vi.fn();
  render(<ColumnsEditor value={[{ name: 'created_at' }]} onChange={onChange} />);

  await userEvent.click(screen.getByRole('checkbox', { name: '第 1 行 NOT NULL' }));
  expect(onChange).toHaveBeenLastCalledWith([{ name: 'created_at', nullable: false }]);

  onChange.mockClear();
  render(<ColumnsEditor value={[{ name: 'created_at', nullable: false }]} onChange={onChange} />);
  await userEvent.click(screen.getAllByRole('checkbox', { name: '第 1 行 NOT NULL' })[1]);
  expect(onChange).toHaveBeenLastCalledWith([{ name: 'created_at' }]);
});

// 表格里没有的字段（onUpdate / length）编辑一次不能被抹掉——它们在 YAML 里照常生效。
test('清空单元格删掉该键，表格外的字段原样保留', async () => {
  const onChange = vi.fn();
  render(<ColumnsEditor
    value={[{ name: 'updated_at', type: 'datetime', onUpdate: 'CURRENT_TIMESTAMP', length: 6 }]}
    onChange={onChange}
  />);

  await userEvent.clear(screen.getByRole('textbox', { name: '第 1 行 type' }));
  expect(onChange).toHaveBeenLastCalledWith([{ name: 'updated_at', onUpdate: 'CURRENT_TIMESTAMP', length: 6 }]);
});

test('固定业务唯一键不展示无效的字段筛选，编辑时保留已有高级配置', async () => {
  const rule = {
    ...newRule('business_unique_index', []),
    when: { columnNameRegex: '^legacy_no$', tableTagsAny: ['orders'] },
    statement: { columns: ['tenant_id', 'order_no'] },
  };
  const onChange = vi.fn();
  render(<RuleForm rule={rule} onChange={onChange} />);

  expect(screen.queryByText('字段名匹配')).not.toBeInTheDocument();
  expect(screen.queryByText('字段语义类型')).not.toBeInTheDocument();
  await userEvent.clear(screen.getByRole('textbox', { name: /不能重复的字段/ }));
  expect(onChange).toHaveBeenLastCalledWith({ ...rule, statement: {} });
  await userEvent.click(screen.getByText('高级设置（规则信息、检查强度和表筛选）'));
  expect(screen.getByDisplayValue('orders')).toBeVisible();
});

test('业务唯一键缺少配置时就地提示，已有字段条件自动展开', () => {
  const rule = newRule('business_unique_index', []);
  const { rerender } = render(<RuleForm rule={rule} onChange={vi.fn()} />);
  expect(screen.getByRole('alert')).toHaveTextContent('两者都留空会导致检查无法完成');

  rerender(<RuleForm rule={{ ...rule, when: { columnNameRegex: '^order_no$' } }} onChange={vi.fn()} />);
  expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  expect(screen.getByDisplayValue('^order_no$')).toBeVisible();
});
