import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { expect, test, vi } from 'vitest';
import { ColumnsEditor } from './RuleForm';

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
