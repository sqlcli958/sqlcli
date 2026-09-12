import * as RadixMenu from '@radix-ui/react-dropdown-menu';
import type { ReactNode } from 'react';

/**
 * 行操作下拉菜单。全站唯一一处，样式走 App.css 的 `.menu`。
 *
 * 这是全仓库唯一用第三方行为库的地方，理由是手写版本缺的东西太多而且都容易写错：
 * 点外面不关、ESC 不关、关掉后焦点不回到触发按钮、没有方向键、贴到视口底部不翻转。
 * 还有一个更隐蔽的——菜单原来在 `<td>` 里绝对定位，表格一旦有 overflow 就会被裁掉；
 * Radix 渲染到 portal，天然没有这个问题。
 *
 * 按钮/状态灯那些"长什么样"的组件不引库（扒掉样式就只剩一个 div），
 * 只有这种"怎么动"的才值得。
 */
export function Menu({ trigger, children }: { trigger: ReactNode; children: ReactNode }) {
  return (
    <RadixMenu.Root>
      <RadixMenu.Trigger asChild>{trigger}</RadixMenu.Trigger>
      <RadixMenu.Portal>
        <RadixMenu.Content className="menu" align="end" sideOffset={4} collisionPadding={8}>
          {children}
        </RadixMenu.Content>
      </RadixMenu.Portal>
    </RadixMenu.Root>
  );
}

export function MenuItem({
  children,
  danger,
  disabled,
  onSelect,
}: {
  children: ReactNode;
  danger?: boolean;
  disabled?: boolean;
  /** Radix 在这里已经帮你关好菜单并把焦点还给触发按钮了 */
  onSelect: () => void;
}) {
  return (
    <RadixMenu.Item
      className={danger ? 'menu-item is-danger' : 'menu-item'}
      disabled={disabled}
      onSelect={onSelect}
    >
      {children}
    </RadixMenu.Item>
  );
}
