import { useRef, type KeyboardEvent, type ReactNode } from 'react';

export interface TabItem<T extends string> {
  key: T;
  label: string;
  /** 角标内容，比如待办数量；为空时不渲染 */
  badge?: ReactNode;
}

/**
 * 分段控件形态的标签页。
 *
 * 自己写而不是引 Radix：一个 `role="tablist"` 加方向键就是全部行为，三十行；
 * 为它装一个包不划算。但**方向键必须有**——原来评审页写了 `role="tablist"` 却只能
 * 用鼠标点，对读屏器来说是「声称是标签页但不像标签页」，比不写这个 role 更糟。
 *
 * 用 roving tabindex：只有选中项进 Tab 键序列，其余靠方向键在组内移动。
 */
export function Tabs<T extends string>({
  items,
  value,
  onChange,
  label,
}: {
  items: readonly TabItem<T>[];
  value: T;
  onChange: (key: T) => void;
  /** 这组标签是干什么的，给读屏器 */
  label: string;
}) {
  const refs = useRef<(HTMLButtonElement | null)[]>([]);

  function move(index: number) {
    const next = items[(index + items.length) % items.length];
    onChange(next.key);
    refs.current[(index + items.length) % items.length]?.focus();
  }

  function onKeyDown(event: KeyboardEvent<HTMLButtonElement>, index: number) {
    switch (event.key) {
      case 'ArrowRight': move(index + 1); break;
      case 'ArrowLeft': move(index - 1); break;
      case 'Home': move(0); break;
      case 'End': move(items.length - 1); break;
      default: return;
    }
    event.preventDefault();
  }

  return (
    <div className="tabs" role="tablist" aria-label={label}>
      {items.map((item, index) => {
        const active = item.key === value;
        return (
          <button
            key={item.key}
            ref={(node) => { refs.current[index] = node; }}
            type="button"
            role="tab"
            aria-selected={active}
            tabIndex={active ? 0 : -1}
            className={active ? 'is-active' : ''}
            onClick={() => onChange(item.key)}
            onKeyDown={(event) => onKeyDown(event, index)}
          >
            {item.label}
            {item.badge != null && item.badge !== 0 && <span className="tabs-badge">{item.badge}</span>}
          </button>
        );
      })}
    </div>
  );
}
