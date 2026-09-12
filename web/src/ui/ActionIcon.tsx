/**
 * 行内动作的统一图标按钮。
 *
 * 全站「新建 / 添加 / 编辑 / 删除 / 测试」只用图标不写文字（CLAUDE.md「图标优先」）：
 * 同一个动作在数据源、驱动、规则、关系四个地方长得一样，扫一眼就知道点哪个。
 * 说明文字放 title，鼠标停留才出现，不占版面。
 */
import { forwardRef, type ButtonHTMLAttributes } from 'react';
import { buttonClass } from './Button';

export type ActionName = 'add' | 'edit' | 'delete' | 'more' | 'test';

const PATHS: Record<ActionName, string> = {
  add: 'M11 5h2v6h6v2h-6v6h-2v-6H5v-2h6V5z',
  edit: 'M3 17.25V21h3.75L17.81 9.94l-3.75-3.75L3 17.25zM20.71 7.04a1 1 0 000-1.41l-2.34-2.34a1 1 0 00-1.41 0l-1.83 1.83 3.75 3.75 1.83-1.83z',
  delete: 'M9 3h6l1 2h4v2H4V5h4l1-2zM6 9h12l-1 12H7L6 9z',
  more: 'M6 10a2 2 0 110 4 2 2 0 010-4zm6 0a2 2 0 110 4 2 2 0 010-4zm6 0a2 2 0 110 4 2 2 0 010-4z',
  // 闪电 = 「跑一下试试」。插头形状更贴「连接」，但缩到 14px 就糊成一团了
  test: 'M13 2L4.09 12.97h6.16L11 22l8.91-10.97h-6.16L13 2z',
};

/** 默认文案，调用方给了 title 就用调用方的（比如「删除数据源」比「删除」更清楚）。 */
const LABELS: Record<ActionName, string> = {
  add: '新建',
  edit: '编辑',
  delete: '删除',
  more: '更多操作',
  test: '测试连接',
};

interface Props extends ButtonHTMLAttributes<HTMLButtonElement> {
  action: ActionName;
  /** 说明文字，同时用作 title 和 aria-label */
  label?: string;
  /** 主按钮外观，用于「新建」这类页面主操作 */
  primary?: boolean;
  size?: 'sm' | 'md';
}

/**
 * forwardRef 是必需的：`<Menu trigger={<ActionIcon .../>}>` 走 Radix 的 asChild，
 * 它要拿到这个 DOM 节点来定位菜单、并在关闭后把焦点还回来。不转发的话
 * React 只会打一句 warning，然后菜单飘到左上角、焦点丢在 body 上。
 */
export const ActionIcon = forwardRef<HTMLButtonElement, Props>(function ActionIcon(
  { action, label, primary, size = 'sm', className, ...rest },
  ref,
) {
  const text = label ?? LABELS[action];
  const variant = primary ? 'primary' : action === 'delete' ? 'danger' : 'default';
  return (
    <button
      ref={ref}
      type="button"
      className={buttonClass(variant, size === 'sm' ? 'sm' : 'md', { icon: true, className })}
      title={text}
      aria-label={text}
      {...rest}
    >
      <svg width="14" height="14" viewBox="0 0 24 24" fill="currentColor" aria-hidden="true">
        <path d={PATHS[action]} />
      </svg>
    </button>
  );
});
