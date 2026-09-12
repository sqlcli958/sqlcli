import { get, post } from './client';

/**
 * 图谱评估结果。
 *
 * <p>这里原来写着「只读，没有跑一次按钮，否则就有两条产出路径」。那条担心针对的是
 * **两套实现**：现在 `POST /api/eval/run` 和 `sql-cli <alias> schema eval` 调的是
 * 同一个 `GraphEvalRunner`，同一个评估器、同一张表、同一个 source，算不出两个结果。
 */
export interface EvaluationDto {
  id: string;
  alias: string;
  /** `eval`=全探针（带 metrics）；`value-domain`=只落 value.unused，metrics 为 null */
  source: string;
  status: string;
  revision: number;
  startedAt: number;
  elapsedMs: number | null;
  errorCount: number;
  warningCount: number;
  /** 指标名 → 0~1 的覆盖率。键会增减，按键名动态渲染，不要写死 */
  metrics: Record<string, number> | null;
}

export interface FindingDto {
  seq: number;
  probe: string;
  severity: 'error' | 'warning';
  targetId: string | null;
  message: string;
  remediation: string | null;
}

export interface FindingPageDto {
  findings: FindingDto[];
  total: number;
  page: number;
  pageSize: number;
}

/** 最近的评估，时间倒序。 */
export async function getEvaluations(
  alias: string,
  limit = 20,
  signal?: AbortSignal,
): Promise<EvaluationDto[]> {
  const body = await get<{ evaluations: EvaluationDto[] }>('/eval/evaluations', { alias, limit }, signal);
  return body.evaluations ?? [];
}

/** 一次评估的 finding，**page 从 1 起**（后端契约，不是 0 基）。 */
export async function getFindings(
  evaluationId: string,
  page = 1,
  pageSize = 50,
  signal?: AbortSignal,
): Promise<FindingPageDto> {
  return get(
    `/eval/evaluations/${encodeURIComponent(evaluationId)}/findings`,
    { page, pageSize },
    signal,
  );
}


export interface RunEvalResultDto {
  id: string;
  alias: string;
  revision: number;
  status: string;
  errorCount: number;
  warningCount: number;
  elapsedMs: number;
}

/**
 * 跑一次评估。评估是纯计算不连库，一份图谱毫秒级跑完，所以同步返回、不进任务队列。
 *
 * <p>后端调的是 CLI 同一个 `GraphEvalRunner.runAndSave`——这是页面上能有这个按钮的前提。
 */
export async function runEval(alias: string, signal?: AbortSignal): Promise<RunEvalResultDto> {
  return post<RunEvalResultDto>(`/eval/run?alias=${encodeURIComponent(alias)}`, undefined, signal);
}
