/**
 * 三态状态灯。绿=正常，黄=可用但需注意或状态未知，红=不可用或受限。
 * 全站唯一一处定义这三个颜色和这个语义（CLAUDE.md「状态色」）。
 */
export type Tone = 'ok' | 'warn' | 'bad';

export function StatusDot({ tone, label }: { tone: Tone; label?: string }) {
  // 旁边有文字说明时（绝大多数情况）灯本身对读屏器是冗余的，隐藏掉；
  // 只有灯没有文字时才需要 label 把状态说出来。
  return (
    <span className="cell-dot" data-tone={tone} title={label} aria-hidden={label ? undefined : true}>
      <span className="cell-dot-mark" />
      {label && <span className="sr-only">{label}</span>}
    </span>
  );
}
