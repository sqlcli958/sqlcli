import js from '@eslint/js'
import globals from 'globals'
import reactHooks from 'eslint-plugin-react-hooks'
import reactRefresh from 'eslint-plugin-react-refresh'
import tseslint from 'typescript-eslint'

/**
 * 基础设施收口规则。
 *
 * 按钮、状态灯、分页、工具条这些只在 src/ui/ 定义一次，feature 目录只组装不定义。
 * 靠文档约束过一轮，结果是分页被写了两遍、筛选条被写了两遍、`.exec-pager` 和
 * `.wb-pager` 并存——所以改成 lint：不走 ui/ 就过不了检查。
 *
 * src/ui/ 自己豁免（它就是定义的地方），src/app/ 的外壳也豁免（顶栏状态灯是另一套
 * `.shell-dot`，语义和用法都和表格里的灯不同）。
 */
const infraClasses = [
  { cls: 'btn', use: '<Button>（或 buttonClass()，给 <Link> 用）' },
  { cls: 'cell-dot', use: '<StatusDot>' },
  { cls: 'pager', use: '<Pagination>' },
  { cls: 'filter-bar', use: '<FilterBar>' },
  { cls: 'tabs', use: '<Tabs>' },
  { cls: 'menu', use: '<Menu>/<MenuItem>' },
]

// 两个坑，都是试出来的：
//   1. esquery 的属性正则是**整串匹配**不是 search，所以要把前后的其余类名一起写进来，
//      否则 className="btn is-sm" 会漏掉，只命中光秃秃的 "btn"。
//   2. 正则里不能用 `.`——它会被 esquery 的选择器语法当成字段访问符吃掉，
//      整条规则静默失效。所以用 [-a-z0-9]+ 显式枚举类名的字符集。
// 改这一行之后务必真写一个违规的 className 验一遍：这种规则失效是不报错的。
const restrictedClassName = infraClasses.map(({ cls, use }) => ({
  selector: `JSXAttribute[name.name='className'] > Literal[value=/^([-a-z0-9]+ )*${cls}( [-a-z0-9]+)*$/]`,
  message: `基础设施类 .${cls} 只在 src/ui/ 里定义，这里请用 ${use}。`,
}))

// 裸 <select> 同理：原生 select 不继承页面字体，样式已经收进 ui/Select.tsx 一次，
// feature 里再写一个就是重新长出「17 个没样式 + 9 个各写各的」那批问题。
const restrictedSelect = {
  selector: `JSXOpeningElement[name.name='select']`,
  message: '下拉框请用 <Select>（src/ui/Select.tsx）。',
}

export default tseslint.config(
  { ignores: ['dist', 'node_modules'] },
  js.configs.recommended,
  ...tseslint.configs.recommended,
  {
    files: ['**/*.{ts,tsx}'],
    languageOptions: {
      ecmaVersion: 2020,
      globals: globals.browser,
    },
    plugins: {
      'react-hooks': reactHooks,
      'react-refresh': reactRefresh,
    },
    rules: {
      ...reactHooks.configs.recommended.rules,
      'react-hooks/refs': 'off',
      'react-hooks/set-state-in-effect': 'off',
      'react-refresh/only-export-components': ['warn', { allowConstantExport: true }],
    },
  },
  {
    files: ['src/features/**/*.tsx', 'src/graph/**/*.tsx'],
    rules: {
      'no-restricted-syntax': ['error', ...restrictedClassName, restrictedSelect],
      // 图表库同理：只在 src/ui/Chart.tsx 里包一次，feature 只组装 option。
      // 不拦的话每个页面各起一套 echarts.init / resize / dispose，
      // 而漏掉 dispose 是切页面就泄漏一个实例，看不出来。
      'no-restricted-imports': ['error', {
        patterns: [{
          group: ['echarts', 'echarts/*'],
          message: '图表只能通过 src/ui/Chart.tsx 用，不要在 feature 里直接 import echarts。',
        }, {
          group: ['@xyflow/react', '@xyflow/react/*', '@dagrejs/dagre'],
          message: '节点图只能通过 src/ui/FlowCanvas.tsx 用，不要在 feature 里直接 import React Flow / dagre。',
        }],
      }],
    },
  },
)
