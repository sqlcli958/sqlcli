import { describe, expect, test } from 'vitest';
import {
  buildCsv,
  firstKeyword,
  isWriteStatement,
  selectSampleSql,
  stashWorkbenchSql,
  takeWorkbenchSql,
} from './workbenchSql';

describe('写语句判断', () => {
  test('十个写关键字都认得，只读语句放行', () => {
    for (const sql of [
      'INSERT INTO t VALUES (1)',
      'update t set a = 1 where id = 1',
      'DELETE FROM t WHERE id = 1',
      'MERGE INTO t USING s ON (1=1)',
      'CREATE TABLE t (id int)',
      'ALTER TABLE t ADD c int',
      'DROP TABLE t',
      'TRUNCATE TABLE t',
      'GRANT SELECT ON t TO u',
      'REVOKE SELECT ON t FROM u',
    ]) {
      expect(isWriteStatement(sql), sql).toBe(true);
    }
    for (const sql of ['SELECT 1', 'with t as (select 1) select * from t', 'SHOW TABLES', 'EXPLAIN SELECT 1', '']) {
      expect(isWriteStatement(sql), sql).toBe(false);
    }
  });

  test('注释和空白不影响首关键字', () => {
    expect(isWriteStatement('  \n  DELETE FROM t WHERE id = 1')).toBe(true);
    expect(isWriteStatement('-- 清理旧数据\nDELETE FROM t WHERE id = 1')).toBe(true);
    expect(isWriteStatement('/* 批量 */ UPDATE t SET a = 1 WHERE id = 1')).toBe(true);
    expect(firstKeyword('-- 只有注释')).toBe('');
  });

  test('UPDATE 出现在别处不算写语句', () => {
    expect(isWriteStatement("SELECT * FROM t WHERE op = 'UPDATE'")).toBe(false);
  });
});

describe('CSV 导出', () => {
  const bom = '﻿';

  test('BOM、表头、NULL 与转义', () => {
    const csv = buildCsv(
      [{ name: 'id', type: 'BIGINT' }, { name: '备注', type: 'VARCHAR' }],
      [
        [1, null],
        [2, '含"引号",和逗号'],
        [3, '换\n行'],
      ],
    );
    expect(csv.startsWith(bom)).toBe(true);
    const lines = csv.slice(bom.length).split('\r\n');
    expect(lines[0]).toBe('id,备注');
    expect(lines[1]).toBe('1,');
    expect(lines[2]).toBe('2,"含""引号"",和逗号"');
    // 内嵌换行被引号包住，不会被当成行分隔（行分隔是 CRLF）
    expect(lines[3]).toBe('3,"换\n行"');
    expect(lines).toHaveLength(5);
    expect(csv.endsWith('\r\n')).toBe(true);
  });

  test('没有行时只有表头', () => {
    expect(buildCsv([{ name: 'a', type: 'INT' }], [])).toBe(`${bom}a\r\n`);
  });
});

describe('跨页传递 SQL', () => {
  test('取一次就清掉', () => {
    expect(takeWorkbenchSql()).toBeNull();
    stashWorkbenchSql(selectSampleSql('public', 'orders'));
    expect(takeWorkbenchSql()).toBe('SELECT * FROM public.orders LIMIT 100');
    expect(takeWorkbenchSql()).toBeNull();
  });
});
