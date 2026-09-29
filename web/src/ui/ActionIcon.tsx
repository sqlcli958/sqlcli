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

const PATHS: Record<Exclude<ActionName, 'more'>, string> = {
  add: 'M12 5v14M5 12h14',
  edit: 'M12 20h9M16.5 3.5a2.12 2.12 0 013 3L7 19l-4 1 1-4L16.5 3.5z',
  delete: 'M3 6h18M8 6V4h8v2m2 0-1 14H7L6 6m4 5v5m4-5v5',
  test: 'M13 2 3 14h7l-1 8 12-14h-7l1-6z',
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
      <svg
        width="16"
        height="16"
        viewBox="0 0 24 24"
        fill="none"
        stroke="currentColor"
        strokeWidth="1.8"
        strokeLinecap="round"
        strokeLinejoin="round"
        aria-hidden="true"
      >
        {action === 'more' ? (
          <>
            <circle cx="5" cy="12" r="1" fill="currentColor" stroke="none" />
            <circle cx="12" cy="12" r="1" fill="currentColor" stroke="none" />
            <circle cx="19" cy="12" r="1" fill="currentColor" stroke="none" />
          </>
        ) : (
          <path d={PATHS[action]} />
        )}
      </svg>
    </button>
  );
});
