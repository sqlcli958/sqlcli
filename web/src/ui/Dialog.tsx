import * as RadixDialog from '@radix-ui/react-dialog';
import type { ReactNode } from 'react';

/**
 * 带遮罩的模态弹窗。样式走 App.css 的 `.dialog` 段。
 *
 * 这是全仓库第二处用第三方行为库的地方，判据跟 `Menu.tsx` 是同一条：**「长什么样」自己写，
 * 「怎么动」才引库**。弹窗要管的行为手写版本几乎必漏——焦点陷阱（Tab 不能跑到弹窗外面）、
 * ESC 关闭、点遮罩关闭、关掉后焦点回到触发按钮、打开时锁住背景滚动、
 * 渲染到 portal 躲开父级的 overflow 裁剪、`aria-modal` 让读屏器把背景当不存在。
 *
 * <p>抽出来的触发条件是代码库自己定的：`AliasAddPage` 的 sqlite 路径选择器当初留了
 * 「目前只有这一个调用点…真出现第二个需要弹层的场景时再抽」。血缘图就是那第二个，
 * 而且跟路径选择器不同——上面那七件事它全都需要（路径选择器是内联展开，一件都不需要）。
 * 所以那一处**不动**，它本来就不该是模态。
 */
export function Dialog({
  open,
  onOpenChange,
  title,
  description,
  children,
  wide,
}: {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  title: string;
  /** 副标题；也用作读屏器的 aria-describedby，省略时显式关掉 Radix 的告警 */
  description?: ReactNode;
  children: ReactNode;
  /** 内容宽的场景（血缘图这类）撑到接近视口宽 */
  wide?: boolean;
}) {
  return (
    <RadixDialog.Root open={open} onOpenChange={onOpenChange}>
      <RadixDialog.Portal>
        <RadixDialog.Overlay className="dialog-overlay" />
        <RadixDialog.Content className={`dialog${wide ? ' is-wide' : ''}`}>
          <div className="dialog-head">
            <RadixDialog.Title className="dialog-title">{title}</RadixDialog.Title>
            <RadixDialog.Close className="btn is-icon" title="关闭" aria-label="关闭">
              <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor"
                   strokeWidth="2" aria-hidden="true">
                <path d="M6 6l12 12M18 6L6 18" />
              </svg>
            </RadixDialog.Close>
          </div>
          {description && (
            <RadixDialog.Description className="dialog-desc">{description}</RadixDialog.Description>
          )}
          <div className="dialog-body">{children}</div>
        </RadixDialog.Content>
      </RadixDialog.Portal>
    </RadixDialog.Root>
  );
}
