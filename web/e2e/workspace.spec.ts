import { expect, test, type Page, type TestInfo } from '@playwright/test';

const browserProblems = new WeakMap<Page, string[]>();

const aliases = {
  aliases: [
    {
      name: 'demo',
      dbType: 'postgresql',
      description: '订单与客户分析库',
      readOnly: false,
      graphAvailable: true,
      tables: 2,
      relations: 1,
      approveQuery: false,
      approveUpdate: true,
      approveGraph: true,
    },
  ],
};

const tableItems = [
  {
    id: 'table:demo:app.orders',
    schema: 'app',
    name: 'orders',
    qualifiedName: 'app.orders',
    displayName: 'orders',
    comment: '订单主表',
    description: '记录订单生命周期与金额',
    tableType: 'BASE TABLE',
    columnCount: 3,
    tags: ['core'],
  },
  {
    id: 'table:demo:app.customers',
    schema: 'app',
    name: 'customers',
    qualifiedName: 'app.customers',
    displayName: 'customers',
    comment: '客户主表',
    description: '客户基础资料',
    tableType: 'BASE TABLE',
    columnCount: 2,
    tags: [],
  },
];

const orderDetail = {
  table: {
    id: 'table:demo:app.orders',
    schema: 'app',
    name: 'orders',
    qualifiedName: 'app.orders',
    businessName: '订单',
    comment: '订单主表',
    description: '记录订单生命周期、客户和支付金额。',
    tableType: 'BASE TABLE',
    tags: ['core'],
    primaryKey: ['column:demo:app.orders.id'],
    indexes: [
      { name: 'pk_orders', unique: true, columns: ['id'] },
      { name: 'idx_orders_customer', unique: false, columns: ['customer_id'] },
    ],
  },
  columns: [
    {
      name: 'id',
      dataType: { raw: 'BIGINT', normalized: 'bigint' },
      nullable: false,
      defaultValue: '',
      ordinal: 1,
      primaryKey: true,
      unique: true,
      indexed: true,
      comment: '订单 ID',
      businessName: '订单ID',
      description: '订单唯一标识',
      semanticType: 'primary_id',
    },
    {
      name: 'customer_id',
      dataType: { raw: 'BIGINT', normalized: 'bigint' },
      nullable: false,
      defaultValue: '',
      ordinal: 2,
      primaryKey: false,
      unique: false,
      indexed: true,
      comment: '客户 ID',
      businessName: '客户ID',
      description: '关联客户',
      semanticType: 'ref_id',
    },
    {
      name: 'amount',
      dataType: { raw: 'DECIMAL(18,2)', normalized: 'decimal', precision: 18, scale: 2 },
      nullable: false,
      defaultValue: '0',
      ordinal: 3,
      primaryKey: false,
      unique: false,
      indexed: false,
      comment: '订单金额',
      businessName: '支付金额',
      description: '订单应付金额',
      semanticType: 'amount',
      valueHints: { sampleValues: ['99.00', '128.50'] },
    },
  ],
  inEdges: [],
  outEdges: [],
  validationIssues: [],
  recentChanges: [],
  lineage: {},
};

const customerDetail = {
  table: {
    id: 'table:demo:app.customers',
    schema: 'app',
    name: 'customers',
    qualifiedName: 'app.customers',
    businessName: '客户',
    comment: '客户主表',
    description: '客户基础资料。',
    tableType: 'BASE TABLE',
    tags: [],
    primaryKey: ['column:demo:app.customers.id'],
    indexes: [{ name: 'pk_customers', unique: true, columns: ['id'] }],
  },
  columns: [
    {
      name: 'id',
      dataType: { raw: 'BIGINT', normalized: 'bigint' },
      nullable: false,
      defaultValue: '',
      ordinal: 1,
      primaryKey: true,
      unique: true,
      indexed: true,
      comment: '客户 ID',
      businessName: '客户ID',
      semanticType: 'primary_id',
    },
    {
      name: 'name',
      dataType: { raw: 'VARCHAR(120)', normalized: 'varchar', length: 120 },
      nullable: false,
      defaultValue: '',
      ordinal: 2,
      primaryKey: false,
      unique: false,
      indexed: false,
      comment: '客户名称',
      businessName: '客户名称',
      semanticType: 'name',
    },
  ],
  inEdges: [],
  outEdges: [],
  validationIssues: [],
  recentChanges: [],
  lineage: {},
};

async function installApiMocks(page: Page) {
  await page.route('**/api/**', async (route) => {
    const request = route.request();
    const url = new URL(request.url());
    const path = url.pathname;
    const method = request.method();

    const ok = (body: unknown, status = 200) =>
      route.fulfill({
        status,
        contentType: 'application/json',
        body: JSON.stringify(body),
      });

    if (method === 'GET' && path === '/api/aliases') return ok(aliases);
    if (method === 'POST' && path === '/api/aliases/demo/test') {
      return ok({ name: 'demo', success: true, message: 'ok', hints: [], serverVersion: '16.4' });
    }
    if (method === 'GET' && path === '/api/session') {
      return ok({
        alias: 'demo',
        revision: 42,
        readOnly: false,
        capabilities: ['read', 'write'],
      });
    }
    if (method === 'GET' && path === '/api/index/status') {
      return ok({
        status: 'ready',
        sourceRevision: '42',
        tableCount: 2,
        columnCount: 5,
        builtAt: '2026-09-20T08:00:00Z',
      });
    }
    if (method === 'GET' && path === '/api/approvals') {
      return ok({ approvals: [], batches: [], total: 0, pending: 0 });
    }
    if (method === 'GET' && path === '/api/schemas/catalog') {
      return ok([
        { name: 'app', system: false, imported: true, tableCount: 2 },
        { name: 'audit', system: false, imported: false, tableCount: 4 },
      ]);
    }
    if (method === 'GET' && path === '/api/tables') {
      const schema = url.searchParams.get('schema');
      const items = schema && schema !== 'app' ? [] : tableItems;
      return ok({ total: items.length, offset: 0, limit: 100, items });
    }
    if (method === 'GET' && path === '/api/tables/app.orders') return ok(orderDetail);
    if (method === 'GET' && path === '/api/tables/app.customers') return ok(customerDetail);
    if (method === 'GET' && path === '/api/graph') {
      return ok({
        revision: 42,
        truncated: false,
        stats: { totalNodes: 2, totalEdges: 1, returnedNodes: 2, returnedEdges: 1 },
        nodes: [
          {
            id: 'table:demo:app.orders',
            kind: 'table',
            label: 'orders',
            schema: 'app',
            description: '订单主表',
            relationCount: 1,
            columnCount: 3,
            validationSeverity: 'none',
          },
          {
            id: 'table:demo:app.customers',
            kind: 'table',
            label: 'customers',
            schema: 'app',
            description: '客户主表',
            relationCount: 1,
            columnCount: 2,
            validationSeverity: 'none',
          },
        ],
        edges: [
          {
            id: 'relation:orders-customer',
            source: 'table:demo:app.orders',
            target: 'table:demo:app.customers',
            type: 'foreign_key',
            confidence: 1,
            verified: true,
          },
        ],
      });
    }
    if (method === 'GET' && path === '/api/terms') {
      return ok({
        total: 1,
        terms: [
          {
            id: 'term:demo:order-domain',
            name: 'order_domain',
            displayName: '订单场景',
            aliases: ['订单'],
            negativeAliases: [],
            description: '订单分析常用业务场景',
            status: 'verified',
            confidence: 1,
            mappedTargets: ['table:demo:app.orders'],
            primaryTarget: 'table:demo:app.orders',
            filters: [],
            scenarioTables: ['app.orders', 'app.customers'],
            bridgeTables: ['app.customers'],
          },
        ],
      });
    }
    if (method === 'GET' && path === '/api/lineage/graph') {
      return ok({
        center: '',
        depth: 0,
        truncated: false,
        nodes: [],
        edges: [],
        records: [],
        tableColumns: {},
      });
    }
    if (method === 'GET' && path === '/api/metrics') {
      return ok({
        revision: 42,
        metrics: [
          {
            id: 'metric:demo:gmv_paid',
            name: 'gmv_paid',
            businessName: '已支付 GMV',
            aliases: ['GMV'],
            expression: 'SUM(app.orders.amount)',
            filters: "status = 'paid'",
            grain: {
              timeColumn: 'column:demo:app.orders.created_at',
              grains: ['day', 'week', 'month'],
            },
            dimensions: ['column:demo:app.orders.customer_id'],
            joinPath: [],
            status: 'verified',
            verified: true,
            confidence: 1,
            updatedBy: 'human',
          },
          {
            id: 'metric:demo:order_count',
            name: 'order_count',
            businessName: '订单数',
            aliases: [],
            expression: 'COUNT(*)',
            filters: null,
            grain: null,
            dimensions: [],
            joinPath: [],
            status: 'verified',
            verified: true,
            confidence: 1,
            updatedBy: 'human',
          },
        ],
      });
    }
    if (method === 'GET' && path === '/api/workspace/stats') {
      return ok({ schemas: 1, tables: 2, columns: 5, relations: 1, validationIssues: 0, terms: 1 });
    }
    if (method === 'GET' && path === '/api/validation/issues') {
      return ok({ issues: [], total: 0 });
    }

    return ok(
      {
        code: 'E2E_UNHANDLED_API',
        message: `Unhandled E2E API: ${method} ${path}${url.search}`,
      },
      500,
    );
  });
}

async function capture(page: Page, testInfo: TestInfo, name: string) {
  await page.evaluate(async () => {
    await document.fonts.ready;
  });
  await page.waitForTimeout(120);
  const path = testInfo.outputPath(`${name}.png`);
  await page.screenshot({
    path,
    fullPage: true,
    animations: 'disabled',
  });
  await testInfo.attach(name, { path, contentType: 'image/png' });
}

test.beforeEach(async ({ page }) => {
  const problems: string[] = [];
  browserProblems.set(page, problems);

  page.on('pageerror', (error) => problems.push(`pageerror: ${error.message}`));
  page.on('console', (message) => {
    if (message.type() === 'error') problems.push(`console.error: ${message.text()}`);
  });
  page.on('response', (response) => {
    if (response.url().includes('/api/') && response.status() >= 500) {
      problems.push(`api ${response.status()}: ${response.request().method()} ${response.url()}`);
    }
  });

  await installApiMocks(page);
});

test.afterEach(async ({ page }) => {
  expect(browserProblems.get(page) ?? [], 'browser/runtime problems').toEqual([]);
});

test('workbench renders the SQL-first workspace and product navigation', async ({ page }, testInfo) => {
  await page.goto('/workspaces/local/sql?alias=demo');

  await expect(page.getByRole('heading', { name: 'demo' })).toBeVisible();
  await expect(page.getByText('SQL', { exact: true })).toBeVisible();
  await expect(page.getByRole('button', { name: '执行' })).toBeVisible();

  for (const item of ['工作台', '数据模型', '指标', '治理', '设置']) {
    await expect(page.getByRole('link', { name: item })).toBeVisible();
  }

  await expect(page.getByLabel('数据源')).toHaveValue('demo');
  await expect(page.getByText('已连接')).toBeVisible();

  await capture(page, testInfo, 'workbench');
});

test('data model supports catalog, relation, lineage and term views', async ({ page }, testInfo) => {
  await page.goto('/workspaces/local/knowledge?alias=demo');

  await expect(page.getByRole('heading', { name: '数据模型' })).toBeVisible();
  await expect(page.getByRole('button', { name: '表与字段' })).toHaveClass(/is-active/);

  await page.locator('.schema-toggle').filter({ hasText: 'app' }).click();
  await page.locator('.table-item').filter({ hasText: 'orders' }).click();

  await expect(page.locator('.inspector-title')).toHaveText('app.orders');
  await expect(page.getByRole('cell', { name: 'id', exact: true })).toBeVisible();
  await expect(page.getByRole('cell', { name: 'amount', exact: true })).toBeVisible();
  await capture(page, testInfo, 'data-model-tables');

  await page.getByRole('button', { name: '关系', exact: true }).click();
  await expect(page).toHaveURL(/mode=relations/);
  await expect(page.locator('.graph-canvas-container')).toBeVisible();
  await expect(page.getByText('2/2 表')).toBeVisible();
  await capture(page, testInfo, 'data-model-relations');

  await page.getByRole('button', { name: '血缘', exact: true }).click();
  await expect(page).toHaveURL(/mode=lineage/);
  await expect(page.getByText(/还没有血缘/)).toBeVisible();

  await page.getByRole('button', { name: '术语', exact: true }).click();
  await expect(page).toHaveURL(/mode=terms/);
  await expect(page.getByText('订单场景')).toBeVisible();
});

test('metrics renders a compact semantic metric catalog', async ({ page }, testInfo) => {
  await page.goto('/workspaces/local/metrics?alias=demo');

  await expect(page.getByRole('heading', { name: '指标' })).toBeVisible();
  await expect(page.getByText('已支付 GMV')).toBeVisible();
  await expect(page.getByText('订单数')).toBeVisible();
  await expect(page.getByRole('button', { name: '新建指标' })).toBeVisible();

  await capture(page, testInfo, 'metrics');
});

test('governance renders health summary and opens review control plane', async ({ page }, testInfo) => {
  await page.goto('/workspaces/local/governance?alias=demo');

  await expect(page.getByRole('heading', { name: '治理' })).toBeVisible();
  await expect(page.getByText('需要处理')).toBeVisible();
  await expect(page.getByText('搜索索引')).toBeVisible();
  await expect(page.getByText('模型质量')).toBeVisible();

  await capture(page, testInfo, 'governance');

  await page.getByRole('button', { name: '审批与审计' }).click();
  await expect(page).toHaveURL(/section=reviews/);
  await expect(page.getByText('待审批', { exact: true })).toBeVisible();
});

test('settings renders data source management in the same product shell', async ({ page }, testInfo) => {
  await page.goto('/workspaces/local/settings?alias=demo');

  await expect(page.getByRole('heading', { name: '设置' })).toBeVisible();
  await expect(page.getByText('数据源控制台')).toBeVisible();
  await expect(page.getByText('demo', { exact: true })).toBeVisible();
  await expect(page.getByText('PostgreSQL', { exact: false })).toBeVisible();
  await expect(page.getByLabel('新建数据源')).toBeVisible();

  await capture(page, testInfo, 'settings');
});
