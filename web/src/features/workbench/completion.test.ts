import { expect, test } from 'vitest';
import {
  collectTableRefs,
  rank,
  readContext,
  readContextForced,
  resolveQualifier,
  type CompletionItem,
} from './completion';

const at = (sql: string) => readContext(sql, sql.length);

test('限定符和前缀分开读', () => {
  expect(at('SELECT * FROM qm_pct.ord')).toMatchObject({ qualifier: 'qm_pct', prefix: 'ord' });
  expect(at('SELECT * FROM ord')).toMatchObject({ qualifier: null, prefix: 'ord' });
});

/** 老实现看到点就放弃，而"刚打完点"恰恰是最需要提示的时刻。 */
test('刚打完点也要补全，前缀为空', () => {
  expect(at('SELECT o. FROM qm_pct.orders o'.slice(0, 9))).toMatchObject({
    qualifier: 'o',
    prefix: '',
  });
});

test('字符串字面量里不补全', () => {
  expect(at("SELECT * FROM t WHERE name = 'ord")).toBeNull();
  // 引号成对之后又能补了
  expect(at("SELECT * FROM t WHERE name = 'x' AND code = ab")).not.toBeNull();
});

test('按最近的关键字决定补表还是补列', () => {
  expect(at('SELECT * FROM ord')?.expect).toBe('table');
  expect(at('SELECT id, na')?.expect).toBe('column');
  expect(at('SELECT * FROM t WHERE sta')?.expect).toBe('column');
  expect(at('SELECT * FROM a JOIN b ON a.id = b.a_')?.expect).toBe('column');
  expect(at('UPDATE qm_pct.men')?.expect).toBe('table');
});

test('光标前没内容时自动不弹，手动触发才给上下文', () => {
  expect(readContext('SELECT * FROM ', 14)).toBeNull();
  expect(readContextForced('SELECT * FROM ', 14)).toMatchObject({ prefix: '', expect: 'table' });
});

test('认出语句里的表和别名', () => {
  expect(collectTableRefs('SELECT * FROM qm_pct.orders o JOIN qm_user.users AS u ON o.uid = u.id'))
    .toEqual([
      { table: 'qm_pct.orders', alias: 'o' },
      { table: 'qm_user.users', alias: 'u' },
    ]);
  // WHERE 不是别名
  expect(collectTableRefs('SELECT * FROM orders WHERE id = 1'))
    .toEqual([{ table: 'orders', alias: null }]);
});

test('限定符先当别名解析，再当表名', () => {
  const refs = collectTableRefs('SELECT * FROM qm_pct.orders o');
  expect(resolveQualifier('o', refs)).toBe('qm_pct.orders');
  expect(resolveQualifier('orders', refs)).toBe('qm_pct.orders');
  expect(resolveQualifier('qm_user', refs)).toBeNull();
});

const items = (...names: string[]): CompletionItem[] =>
  names.map((text) => ({ kind: 'table', text, label: text }));

test('排序：前缀 > 词边界 > 包含 > 子序列', () => {
  const pool = items('sys_menu', 'menu_role', 'qm.sys_menu_item', 'my_enum_table');
  expect(rank(pool, 'menu').map((i) => i.text)).toEqual([
    'menu_role',        // 名字前缀
    'sys_menu',         // 词边界（下划线后）
    'qm.sys_menu_item', // 词边界但更长
    'my_enum_table',    // 子序列 m-e-n-u
  ]);
});

test('子序列能命中首字母缩写', () => {
  expect(rank(items('sys_menu', 'orders'), 'sm').map((i) => i.text)).toEqual(['sys_menu']);
});

test('查询为空时原样给前 N 条', () => {
  expect(rank(items('a', 'b', 'c'), '', 2)).toHaveLength(2);
});
