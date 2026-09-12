import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, expect, test, vi } from 'vitest';
import { EvalPage } from './EvalPage';
import type { EvaluationDto, FindingDto } from '../../api/eval';

vi.mock('../../api/eval', () => ({
  getEvaluations: vi.fn(),
  getFindings: vi.fn(),
  runEval: vi.fn(),
}));

const { getEvaluations, getFindings } = await import('../../api/eval');

/** 趋势图里的端点标签文本（`<指标名> <百分比>`），轴刻度和日期不算。 */
function endLabels(): string[] {
  return [...document.querySelectorAll('.eval-trend text')]
    .map((node) => node.textContent ?? '')
    .filter((text) => /\S\s\d+%$/.test(text));
}

const older: EvaluationDto = {
  id: 'eval-1', alias: 'demo', source: 'eval', status: 'fail', revision: 6,
  startedAt: 1_756_800_000_000, elapsedMs: 20, errorCount: 9, warningCount: 3,
  metrics: {
    tableDescription: 0.5, columnDescription: 0.3, columnComment: 0.8,
    columnValueDomain: 0.01, termMapping: 0.1,
  },
};
const latest: EvaluationDto = {
  id: 'eval-2', alias: 'demo', source: 'eval', status: 'fail', revision: 7,
  startedAt: 1_756_900_000_000, elapsedMs: 12, errorCount: 6, warningCount: 19,
  metrics: {
    tableDescription: 0.62, columnDescription: 0.41, columnComment: 0.85,
    columnValueDomain: 0.03, termMapping: 0.2,
  },
};
/** 只落 value.unused、没有 metrics：混进趋势会把硬错误数拉成 0 */
const valueDomain: EvaluationDto = {
  id: 'eval-3', alias: 'demo', source: 'value-domain', status: 'pass', revision: 7,
  startedAt: 1_756_950_000_000, elapsedMs: null, errorCount: 0, warningCount: 2, metrics: null,
};

function finding(seq: number, probe: string, severity: 'error' | 'warning'): FindingDto {
  return {
    seq, probe, severity,
    targetId: `term:demo:工单${seq}`,
    message: `第 ${seq} 条问题`,
    remediation: `sql-cli demo schema add-term 工单${seq}`,
  };
}
/** 6 条硬错误 + 19 条改进项，够翻到第二页（默认每页 20） */
const ALL_FINDINGS = [
  finding(0, 'rel.endpoint-mismatch', 'error'),
  ...[1, 2, 3, 4, 5].map((i) => finding(i, 'desc.dead-ref', 'error')),
  ...Array.from({ length: 19 }, (_, i) => finding(6 + i, 'cover.used-table', 'warning')),
];

function renderPage(alias = 'demo') {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <MemoryRouter initialEntries={[`/workspaces/local/eval?alias=${alias}`]}>
      <QueryClientProvider client={client}>
        <EvalPage />
      </QueryClientProvider>
    </MemoryRouter>,
  );
}

beforeEach(() => {
  vi.mocked(getEvaluations).mockResolvedValue([valueDomain, latest, older]);
  vi.mocked(getFindings).mockImplementation(async (_id, page = 1, pageSize = 50) => ({
    findings: ALL_FINDINGS.slice((page - 1) * pageSize, page * pageSize),
    total: ALL_FINDINGS.length,
    page,
    pageSize,
  }));
});

test('KPI 取最近一次评估：硬错误数与每个覆盖率', async () => {
  renderPage();

  expect(await screen.findByText('硬错误数')).toBeTruthy();
  const kpis = document.querySelectorAll('.eval-kpi');
  // 硬错误 + metrics 里的每个键；键是动态渲染的，没写死几个
  expect(kpis.length).toBe(6);
  expect(kpis[0].querySelector('.eval-kpi-value')!.textContent).toBe('6');
  expect(screen.getByText('62%')).toBeTruthy();
  expect(screen.getByText('41%')).toBeTruthy();
  expect(screen.getByText('20%')).toBeTruthy();
});

test('columnComment 是库自带注释的镜像：留 tile，不进趋势折线', async () => {
  renderPage();
  await screen.findByText('硬错误数');

  expect(screen.getByText('85%')).toBeTruthy();
  // 四条整理进度的线，注释镜像不在其中——混进去会让趋势看着一开始就很高。
  // 断言端点标签而不是线的数量：标签一条线一个，且带着指标名，说明的是「画了哪几条」。
  // 线的元素构成属于渲染器实现（换 ECharts 之后是 <path> 不是 <polyline>），
  // 更细的判断在 charts.test.tsx 里直接测 option。
  expect(endLabels().length).toBe(4);
  expect(endLabels().some((text) => text.includes('库自带'))).toBe(false);
});

test('source=value-domain 的点不进批次列表，也不进趋势', async () => {
  renderPage();
  await screen.findByText('硬错误数');

  // 批次下拉里只有两次 source=eval 的评估
  const options = screen.getByLabelText('选择评估批次').querySelectorAll('option');
  expect(options.length).toBe(2);
  expect([...options].some((option) => option.textContent?.includes('0 条硬错误'))).toBe(false);

  // 趋势只有两次 eval：value-domain 那次没有 metrics，进来就是一条假的「修好了」。
  // 端点标签取的是最后一个点，落在 latest（62%）而不是 value-domain 那次。
  expect(endLabels()).toContain('表描述覆盖 62%');
});

test('探针分布按探针聚合，硬错误排在前面', async () => {
  renderPage();
  await screen.findByText('硬错误数');

  const rows = document.querySelectorAll('.eval-bars li');
  expect(rows.length).toBe(3);
  // 硬错误在前，同级按条数降序；改进项 19 条更多，但排在硬错误后面
  expect([...rows].map((row) => row.querySelector('code')!.textContent))
    .toEqual(['desc.dead-ref', 'rel.endpoint-mismatch', 'cover.used-table']);
  expect(rows[0].querySelector('b')!.textContent).toBe('5');
});

test('工作队列分页，修复命令能一键复制', async () => {
  const writeText = vi.fn().mockResolvedValue(undefined);
  Object.assign(navigator, { clipboard: { writeText } });
  renderPage();

  expect(await screen.findByText('第 1 条问题')).toBeTruthy();
  expect(screen.queryByText('第 20 条问题')).toBeNull();

  await userEvent.click(screen.getAllByTitle('复制修复命令')[0]);
  expect(writeText).toHaveBeenCalledWith('sql-cli demo schema add-term 工单0');
  expect(await screen.findByTitle('已复制')).toBeTruthy();

  await userEvent.click(screen.getByRole('button', { name: '下一页' }));
  expect(await screen.findByText('第 20 条问题')).toBeTruthy();
  expect(vi.mocked(getFindings).mock.calls.some(([, page]) => page === 2)).toBe(true);
});

test('没跑过评估时给一句话和 CLI 命令', async () => {
  vi.mocked(getEvaluations).mockResolvedValue([]);
  renderPage('erp_other');

  expect(await screen.findByText(/还没跑过评估/)).toBeTruthy();
  expect(screen.getByText('sql-cli erp_other schema eval')).toBeTruthy();
});
