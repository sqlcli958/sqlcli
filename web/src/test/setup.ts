import '@testing-library/jest-dom/vitest';
import { cleanup } from '@testing-library/react';
import { afterEach } from 'vitest';

afterEach(() => cleanup());

// jsdom 没有 IntersectionObserver，而滚动加载用它做哨兵。
// 桩成「永不相交」：测试要验的是分页参数和首屏内容，不是滚动行为本身。
if (typeof globalThis.IntersectionObserver === 'undefined') {
  class NoopIntersectionObserver implements IntersectionObserver {
    readonly root = null;
    readonly rootMargin = '';
    readonly thresholds: ReadonlyArray<number> = [];
    observe(): void {}
    unobserve(): void {}
    disconnect(): void {}
    takeRecords(): IntersectionObserverEntry[] { return []; }
  }
  globalThis.IntersectionObserver =
    NoopIntersectionObserver as unknown as typeof IntersectionObserver;
}

// jsdom 也没有 ResizeObserver，而 ui/Chart.tsx 用它在容器宽度变化时重算图表。
// 桩成「什么都不做」：jsdom 里本来就没有布局（clientWidth 恒为 0），
// 图的尺寸不可测，测试要验的是页面结构和数据流，不是渲染出来的像素。
if (typeof globalThis.ResizeObserver === 'undefined') {
  class NoopResizeObserver implements ResizeObserver {
    observe(): void {}
    unobserve(): void {}
    disconnect(): void {}
  }
  globalThis.ResizeObserver = NoopResizeObserver as unknown as typeof ResizeObserver;
}
