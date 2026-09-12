import { expect, test } from '@playwright/test';

test('user opens a schema table and sees its inspector', async ({ page }) => {
  await page.route('**/api/aliases**', (route) => route.fulfill({
    json: { aliases: [] },
  }));
  await page.route('**/api/session**', (route) => route.fulfill({
    json: {
      alias: 'demo',
      revision: 3,
      readOnly: true,
      capabilities: ['read'],
    },
  }));
  await page.route('**/api/index/status**', (route) => route.fulfill({
    json: { status: 'ready', sourceRevision: 3, tableCount: 1, columnCount: 1 },
  }));
  await page.route('**/api/graph?**', (route) => route.fulfill({
    json: {
      revision: 3,
      truncated: false,
      stats: {
        totalNodes: 0,
        totalEdges: 0,
        returnedNodes: 0,
        returnedEdges: 0,
      },
      nodes: [],
      edges: [],
    },
  }));
  await page.route('**/api/schemas**', (route) => route.fulfill({
    json: [{
      id: 'schema:demo:app',
      name: 'app',
      displayName: 'app',
      description: '',
      tableCount: 1,
    }],
  }));
  await page.route('**/api/tables?**', (route) => route.fulfill({
    json: {
      total: 1,
      offset: 0,
      limit: 200,
      items: [{
        id: 'table:demo:app.orders',
        schema: 'app',
        name: 'orders',
        qualifiedName: 'app.orders',
        displayName: 'orders',
        comment: 'Orders',
        tableType: 'table',
        columnCount: 1,
        tags: [],
      }],
    },
  }));
  await page.route('**/api/tables/app.orders**', (route) => route.fulfill({
    json: {
      table: {
        id: 'table:demo:app.orders',
        schema: 'app',
        name: 'orders',
        qualifiedName: 'app.orders',
        displayName: 'orders',
        businessName: '',
        comment: 'Orders',
        tableType: 'table',
        tags: [],
        primaryKey: ['column:demo:app.orders.id'],
        rowEstimate: 1,
        owner: 'app',
      },
      columns: [{
        name: 'id',
        dataType: { raw: 'BIGINT', normalized: 'bigint' },
        nullable: false,
        defaultValue: '',
        ordinal: 1,
        primaryKey: true,
        unique: true,
        indexed: true,
        comment: '',
        businessName: '',
        displayName: '',
        semanticType: '',
      }],
      inEdges: [],
      outEdges: [],
      validationIssues: [],
      recentChanges: [],
    },
  }));

  await page.goto('/?alias=demo');
  await expect(page.getByText('demo', { exact: true })).toBeVisible();
  await expect(page.getByText('Rev: 3')).toBeVisible();
  await page.getByRole('button', { name: /app/ }).click();
  await page.getByRole('button', { name: /orders/ }).click();

  await expect(page.getByRole('heading', { name: 'app.orders' })).toBeVisible();
  await expect(page.getByRole('cell', { name: 'id' })).toBeVisible();

  await page.getByRole('button', { name: '返回首页' }).click();
  await expect(page).toHaveURL(/\/$/);
  await expect(page.getByRole('heading', { name: 'SQL CLI 图谱' })).toBeVisible();
});

test('homepage lists graph aliases and opens the selected workspace', async ({ page }) => {
  await page.route('**/api/aliases**', (route) => route.request().method() === 'POST'
    ? route.fulfill({ status: 201, json: { name: 'new-db', status: 'created' } })
    : route.fulfill({
      json: {
        aliases: [
          { name: 'pct-mysql', dbType: 'mysql', readOnly: false, graphAvailable: true, tables: 12, relations: 8 },
          { name: 'pending', dbType: 'mysql', readOnly: true, graphAvailable: false },
        ],
      },
    }));
  await page.route('**/api/aliases/pending/import**', (route) => route.fulfill({
    status: 201,
    json: { alias: 'pending', status: 'completed', message: 'Import completed' },
  }));
  await page.route('**/api/executions**', (route) => route.fulfill({
    json: { records: [{ alias: 'pct-mysql', sql: "SELECT * FROM users WHERE phone = '<redacted>'", startedAt: 1_700_000_000_000, elapsedMs: 12 }] },
  }));

  await page.goto('/');
  await expect(page.getByRole('heading', { name: 'SQL CLI 图谱' })).toBeVisible();
  await page.getByRole('button', { name: '+ 添加别名' }).click();
  await expect(page).toHaveURL(/\?view=add-alias$/);
  await expect(page.getByRole('heading', { name: '添加数据库别名' })).toBeVisible();
  await page.getByLabel('别名名称').fill('new-db');
  await page.getByLabel('驱动引用').fill('mysql8');
  await page.getByLabel('JDBC URL').fill('jdbc:mysql://127.0.0.1:3306/app');
  await page.getByLabel('用户名').fill('root');
  const createRequest = page.waitForRequest((request) => request.url().endsWith('/api/aliases') && request.method() === 'POST');
  await page.getByRole('button', { name: '保存别名' }).click();
  await createRequest;
  await expect(page).toHaveURL(/\/$/);
  await page.getByRole('button', { name: '执行记录' }).first().click();
  await expect(page.getByRole('heading', { name: 'SQL 执行记录' })).toBeVisible();
  await expect(page.getByText("SELECT * FROM users WHERE phone = '<redacted>'")).toBeVisible();
  await page.goto('/');
  await expect(page.getByRole('button', { name: /pct-mysql/ })).toBeEnabled();
  await expect(page.getByRole('button', { name: /pending/ })).toBeDisabled();

  const importRequest = page.waitForRequest('**/api/aliases/pending/import**');
  await page.getByRole('button', { name: '导入图谱' }).click();
  await importRequest;

  await page.getByRole('button', { name: /pct-mysql/ }).click();
  await expect(page).toHaveURL(/\?alias=pct-mysql$/);
});

test('rule manager opens for the selected alias', async ({ page }) => {
  await page.route('**/api/session**', (route) => route.fulfill({
    json: { alias: 'pct-mysql', token: 'token', revision: 1, readOnly: false, capabilities: ['read', 'write'] },
  }));
  await page.route('**/api/policy/rules**', (route) => route.fulfill({
    json: { ruleSets: [{
      fileName: 'common.yaml', enabled: true,
      ruleSet: { kind: 'PolicyRuleSet', id: 'common', title: '公共字段', version: '1', dbType: 'mysql', match: {}, defaults: {}, sources: [], rules: [{ id: 'pk', title: '主键', category: 'primary_key_required', severity: 'error', enforcement: 'required', when: {}, statement: {}, tags: [], sources: [] }], },
    }] },
  }));

  await page.goto('/?alias=pct-mysql&view=rules');
  await expect(page.getByRole('heading', { name: '规则管理' })).toBeVisible();
  await expect(page.getByRole('button', { name: '新建规则集' })).toBeVisible();
  await expect(page.locator('input[value="公共字段"]')).toBeVisible();
  await page.getByRole('button', { name: 'MySQL 8' }).click();
  await expect(page.getByRole('textbox', { name: /JSON（支持 dbTypeAny/ })).toHaveValue(/productVersionRegex/);
});
