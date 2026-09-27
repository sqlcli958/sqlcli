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


test('light theme keeps SQL and code text readable', async ({ page }) => {
  await page.goto('/');

  const checks = await page.evaluate(() => {
    document.documentElement.dataset.theme = 'light';

    const fixture = document.createElement('div');
    fixture.setAttribute('data-readability-fixture', 'true');
    fixture.innerHTML = `
      <section class="review-stage" style="padding:16px">
        <article class="review-card">
          <code class="review-summary">UPDATE users SET status = 'active' WHERE id = 1</code>
          <button class="exec-sql" type="button"><code>SELECT * FROM users</code></button>
          <pre class="review-detail">DELETE FROM users WHERE id = 1</pre>
          <div class="exec-detail"><pre>UPDATE users SET name = 'demo'</pre></div>
        </article>
      </section>
    `;
    document.body.appendChild(fixture);

    const parseColor = (value: string) => {
      const match = value.match(/rgba?\((\d+),\s*(\d+),\s*(\d+)(?:,\s*([\d.]+))?\)/);
      if (!match) throw new Error(`Unsupported color: ${value}`);
      return {
        r: Number(match[1]),
        g: Number(match[2]),
        b: Number(match[3]),
        a: match[4] === undefined ? 1 : Number(match[4]),
      };
    };

    const luminance = ({ r, g, b }: { r: number; g: number; b: number }) => {
      const convert = (channel: number) => {
        const value = channel / 255;
        return value <= 0.04045
          ? value / 12.92
          : Math.pow((value + 0.055) / 1.055, 2.4);
      };
      return 0.2126 * convert(r) + 0.7152 * convert(g) + 0.0722 * convert(b);
    };

    const effectiveBackground = (element: Element) => {
      let current: Element | null = element;
      while (current) {
        const color = parseColor(getComputedStyle(current).backgroundColor);
        if (color.a > 0) return color;
        current = current.parentElement;
      }
      return { r: 255, g: 255, b: 255, a: 1 };
    };

    const contrast = (foreground: ReturnType<typeof parseColor>, background: ReturnType<typeof parseColor>) => {
      const front = luminance(foreground);
      const back = luminance(background);
      return (Math.max(front, back) + 0.05) / (Math.min(front, back) + 0.05);
    };

    return [
      '.review-summary',
      '.exec-sql code',
      '.review-detail',
      '.exec-detail pre',
    ].map((selector) => {
      const element = fixture.querySelector(selector);
      if (!element) throw new Error(`Missing readability fixture: ${selector}`);
      const foreground = parseColor(getComputedStyle(element).color);
      const background = effectiveBackground(element);
      return {
        selector,
        foreground: getComputedStyle(element).color,
        background: `rgb(${background.r}, ${background.g}, ${background.b})`,
        ratio: contrast(foreground, background),
      };
    });
  });

  for (const check of checks) {
    expect(check.ratio, `${check.selector}: ${check.foreground} on ${check.background}`).toBeGreaterThanOrEqual(4.5);
  }
});

test('light theme keeps Workbench Graph Review Rules Metrics Eval Settings surfaces neutral', async ({ page }) => {
  await page.goto('/');

  const surfaces = await page.evaluate(() => {
    document.documentElement.dataset.theme = 'light';

    const fixture = document.createElement('div');
    fixture.setAttribute('data-neutral-theme-fixture', 'true');
    fixture.innerHTML = `
      <div class="wb"><header class="wb-head">Workbench</header><section class="wb-editor"></section></div>
      <section class="knowledge-center">Graph</section>
      <section class="review-stage"><article class="review-card">Review</article></section>
      <section class="rules-page"><article class="rule-card">Rules</article></section>
      <section class="metrics-page"><article class="metric-form">Metrics</article></section>
      <section class="eval-page"><article class="eval-kpi">Eval</article></section>
      <section class="settings"><header class="settings-head">Settings</header></section>
    `;
    document.body.appendChild(fixture);

    const selectors = {
      Workbench: '.wb > .wb-head',
      Graph: '.knowledge-center',
      Review: '.review-stage .review-card',
      Rules: '.rules-page .rule-card',
      Metrics: '.metrics-page .metric-form',
      Eval: '.eval-page .eval-kpi',
      Settings: '.settings .settings-head',
    };

    return Object.entries(selectors).map(([module, selector]) => {
      const element = fixture.querySelector(selector);
      if (!element) throw new Error(`Missing theme fixture: ${selector}`);
      const style = getComputedStyle(element);
      return {
        module,
        color: style.color,
        background: style.backgroundColor,
        border: style.borderTopColor,
      };
    });
  });

  const chroma = (value: string) => {
    const match = value.match(/rgba?\((\d+),\s*(\d+),\s*(\d+)/);
    if (!match) throw new Error(`Unsupported color: ${value}`);
    const channels = [Number(match[1]), Number(match[2]), Number(match[3])];
    return Math.max(...channels) - Math.min(...channels);
  };

  for (const surface of surfaces) {
    expect(chroma(surface.color), `${surface.module} text: ${surface.color}`).toBeLessThanOrEqual(28);
    expect(chroma(surface.background), `${surface.module} background: ${surface.background}`).toBeLessThanOrEqual(28);
    expect(chroma(surface.border), `${surface.module} border: ${surface.border}`).toBeLessThanOrEqual(28);
  }
});
