import { beforeEach, expect, test } from 'vitest';
import { useGraphStore } from './graphStore';

/**
 * schema 筛选按数据源分开存。
 *
 * 原来是一个全局 localStorage key，切数据源时筛选跟着串过去——而两个库的 schema
 * 叫法可以完全不同（`erp_plush_test` 的 schema 是 `erp_plush_test`，
 * `erp_plush_prod` 的是 `erp-plush`），于是页面发 `?schema=erp_plush_test` 查 prod，
 * 服务端老实返回 0 个节点：画布空白，而图谱本身好好的 511 张表。
 */

beforeEach(() => {
  localStorage.clear();
  useGraphStore.getState().switchAlias(null);
});

test('切数据源不带走上一个库的 schema 筛选', () => {
  const store = useGraphStore.getState();

  store.switchAlias('erp_plush_test');
  useGraphStore.getState().setSchemaFilters(['erp_plush_test']);

  useGraphStore.getState().switchAlias('erp_plush_prod');

  expect(useGraphStore.getState().schemaFilters).toEqual([]);
});

test('切回来时上一个库的筛选还在', () => {
  useGraphStore.getState().switchAlias('erp_plush_test');
  useGraphStore.getState().setSchemaFilters(['erp_plush_test']);
  useGraphStore.getState().switchAlias('erp_plush_prod');
  useGraphStore.getState().toggleSchemaFilter('erp-plush');

  useGraphStore.getState().switchAlias('erp_plush_test');
  expect(useGraphStore.getState().schemaFilters).toEqual(['erp_plush_test']);

  useGraphStore.getState().switchAlias('erp_plush_prod');
  expect(useGraphStore.getState().schemaFilters).toEqual(['erp-plush']);
});

/** 老的全局 key 对除一个数据源之外的所有别名都是错的，留着只会继续骗人。 */
test('读的时候把老的全局 key 清掉', () => {
  localStorage.setItem('sqlcli.graph.schemaFilters', JSON.stringify(['erp_plush_test']));

  useGraphStore.getState().switchAlias('erp_plush_prod');

  expect(localStorage.getItem('sqlcli.graph.schemaFilters')).toBeNull();
  expect(useGraphStore.getState().schemaFilters).toEqual([]);
});

test('切数据源同时清掉选中的表', () => {
  useGraphStore.getState().switchAlias('erp_plush_test');
  useGraphStore.getState().selectNode('erp_plush_test.erp_prop_report');

  useGraphStore.getState().switchAlias('erp_plush_prod');

  // 表 id 带 schema 前缀，拿到另一个库去查要么 404 要么根本没有工作区
  expect(useGraphStore.getState().selectedNodeId).toBeNull();
});
