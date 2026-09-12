import type { ButtonHTMLAttributes } from 'react';

export type ButtonVariant = 'default' | 'primary' | 'danger' | 'ghost' | 'dashed';
export type ButtonSize = 'md' | 'sm';

const VARIANT: Record<ButtonVariant, string> = {
  default: '',
  primary: 'is-primary',
  danger: 'is-danger',
  ghost: 'is-ghost',
  dashed: 'is-dashed',
};

/**
 * 全站按钮的类名。样式仍然是 App.css 里那一套 `.btn`，这里只负责把变体拼对。
 *
 * 手写 `className="btn ..."` 的问题不是难写，是**没有唯一写法**：同一个小号主按钮
 * 仓库里出现过 `btn is-sm is-primary` 和 `btn is-primary is-sm` 两种，改样式时
 * grep 一次只能找到一半。收成函数之后拼接只有一处。
 *
 * 导出这个函数是给 `<Link>` 用的——它需要按钮长相但必须渲染成 `<a>`。
 */
export function buttonClass(
  variant: ButtonVariant = 'default',
  size: ButtonSize = 'md',
  options: { icon?: boolean; className?: string } = {},
): string {
  return ['btn', VARIANT[variant], size === 'sm' && 'is-sm', options.icon && 'is-icon', options.className]
    .filter(Boolean)
    .join(' ');
}

export interface ButtonProps extends ButtonHTMLAttributes<HTMLButtonElement> {
  variant?: ButtonVariant;
  size?: ButtonSize;
  /** 纯图标按钮：正方形内边距。**必须同时给 title 和 aria-label**（CLAUDE.md 图标优先） */
  icon?: boolean;
}

export function Button({
  variant = 'default',
  size = 'md',
  icon = false,
  className,
  type = 'button',
  ...rest
}: ButtonProps) {
  // type 默认 button 而不是 submit：这个应用里绝大多数按钮不在表单里，
  // 漏写 type 会让表单里的按钮意外提交，是个只在特定页面才复现的坑。
  return <button type={type} className={buttonClass(variant, size, { icon, className })} {...rest} />;
}
