/**
 * textarea 里插入点的像素位置。
 *
 * 浏览器不提供这个（`selectionStart` 只有字符下标），标准做法是**镜像 div**：
 * 造一个和 textarea 字体、内边距、换行规则完全一样的隐藏 div，把光标前的文本放进去，
 * 在末尾插一个标记 span，量它的位置。IDEA 那种「浮层跟着光标走」全靠这个。
 *
 * 只在要弹浮层的那一刻算一次，不进渲染循环。
 */
const MIRRORED: (keyof CSSStyleDeclaration)[] = [
  'boxSizing', 'width', 'paddingTop', 'paddingRight', 'paddingBottom', 'paddingLeft',
  'borderTopWidth', 'borderRightWidth', 'borderBottomWidth', 'borderLeftWidth',
  'fontFamily', 'fontSize', 'fontWeight', 'fontStyle', 'letterSpacing',
  'lineHeight', 'textTransform', 'wordSpacing', 'tabSize', 'whiteSpace', 'wordWrap',
];

export interface CaretPoint {
  /** 相对 textarea 左上角，已经减掉滚动量 */
  left: number;
  top: number;
  /** 一行的高度，调用方用它把浮层放到光标下一行 */
  lineHeight: number;
}

export function caretCoords(textarea: HTMLTextAreaElement, position: number): CaretPoint {
  const style = window.getComputedStyle(textarea);
  const mirror = document.createElement('div');
  for (const key of MIRRORED) {
    mirror.style[key as never] = style[key as never];
  }
  // textarea 的换行规则是 pre-wrap，镜像必须一致，否则长行的折行位置对不上
  mirror.style.whiteSpace = 'pre-wrap';
  mirror.style.wordWrap = 'break-word';
  mirror.style.position = 'absolute';
  mirror.style.visibility = 'hidden';
  mirror.style.height = 'auto';
  mirror.style.overflow = 'hidden';

  mirror.textContent = textarea.value.slice(0, position);
  const marker = document.createElement('span');
  // 放个零宽字符而不是空内容：空 span 量不出位置
  marker.textContent = '​';
  mirror.appendChild(marker);
  document.body.appendChild(mirror);

  const lineHeight = parseFloat(style.lineHeight) || parseFloat(style.fontSize) * 1.4;
  const point = {
    left: marker.offsetLeft - textarea.scrollLeft,
    top: marker.offsetTop - textarea.scrollTop,
    lineHeight,
  };
  document.body.removeChild(mirror);
  return point;
}
