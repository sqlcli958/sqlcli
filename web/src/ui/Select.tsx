import type { SelectHTMLAttributes } from 'react';

export type SelectSize = 'md' | 'sm';

/**
 * `size` 要 Omit 掉：原生 `<select size>` 是「可见几行」的数字属性，
 * 跟这里的 `md`/`sm` 撞名。这个应用里没有用到多行 select 的地方，
 * 让名字跟 `<Button size>` 保持一致比保留一个没人用的原生属性划算。
 */
export interface SelectProps extends Omit<SelectHTMLAttributes<HTMLSelectElement>, 'size'> {
  size?: SelectSize;
  /**
   * 无障碍名称。下拉框旁边常常只有一个图标或什么都没有（「20 条/页」自己就是选项文本），
   * 读屏读到的就是个「组合框」。**没有可见 label 时必须给这个**。
   */
  label?: string;
}

/**
 * 全站下拉框。**包的是原生 `<select>`，不是自己搭的 listbox。**
 *
 * <h2>为什么不自己搭</h2>
 * 按 CLAUDE.md 那条「长什么样 vs 怎么动」：自搭 listbox 要管键盘上下、首字母跳转、
 * ESC 关、点外面关、焦点归还、贴边翻转、渲染到 portal 躲开表格裁剪、还有触屏上的
 * 原生选择器——手写六条里必漏三条，而原生 `<select>` 这些全是白拿的。
 * 这跟 `ui/Menu.tsx` 引 radix 是同一道判据得出的相反结论：
 * 菜单没有原生元素可用，下拉框有。
 *
 * <h2>那修的是什么</h2>
 * 修的正是「长什么样」那一半——原生 select **不继承页面字体**，
 * 不写样式就用操作系统默认字体，跟旁边的按钮、表格差一截。
 * 收之前全仓 26 个 `<select>` 里有 17 个完全没样式，剩下 9 个各写各的：
 * 字号 11 / 12 / 12.5 / 13 / inherit，圆角 3 / 5 / 6，
 * 而 `.relation-editor-select` 在 `App.css` 和 `relation-editor.css` 里**各定义了一遍**。
 *
 * <h2>尺寸跟 Button 对齐，不是另定一套</h2>
 * `md` / `sm` 的字号、内边距、圆角、边框、hover、focus 全部照抄 `.btn` 与 `.btn.is-sm`。
 * 判据很实际：下拉框旁边八成站着一个按钮（「近30天 [出图]」「20 条/页」），
 * 两者高度差一像素就看得出来。改按钮尺寸时这里要跟着改，所以 CSS 挨着 `.btn` 放。
 */
export function Select({ size = 'md', label, className, ...rest }: SelectProps) {
  return (
    <span className={['select', size === 'sm' && 'is-sm', className].filter(Boolean).join(' ')}>
      <select aria-label={label ?? rest['aria-label']} {...rest} />
      {/* 箭头自己画：原生箭头各浏览器长得不一样，而 appearance:none 之后就得补一个。
          aria-hidden 是因为它纯装饰，选择框的语义由 <select> 自己表达。 */}
      <svg className="select-caret" viewBox="0 0 24 24" width="12" height="12" aria-hidden="true">
        <path d="M7 10l5 5 5-5" fill="none" stroke="currentColor" strokeWidth="2"
          strokeLinecap="round" strokeLinejoin="round" />
      </svg>
    </span>
  );
}
