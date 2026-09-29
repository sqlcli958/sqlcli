import { get, post } from './client';

export interface PolicyEvaluationDto {
  id: string;
  sourceAlias: string;
  workspaceRevision: number;
  ruleSetId?: string | null;
  ruleSetVersion?: string | null;
  status?: string | null;
  startedAt?: string | null;
  finishedAt?: string | null;
  evaluatedRules: number;
  violationCount: number;
  waivedCount: number;
  errorMessage?: string | null;
}

export interface PolicyViolationDto {
  id: string;
  evaluationId: string;
  fingerprint?: string | null;
  ruleId?: string | null;
  targetId?: string | null;
  field?: string | null;
  severity?: string | null;
  enforcement?: string | null;
  message: string;
  remediation?: string | null;
  status: 'open' | 'waived' | string;
  waiverId?: string | null;
  observedAt?: string | null;
}

export interface PolicyWaiverDto {
  id: string;
  ruleSetId?: string | null;
  ruleId: string;
  targetId: string;
  reason: string;
  createdBy?: string | null;
  createdAt?: string | null;
  expiresAt?: string | null;
  status: string;
  revokedBy?: string | null;
  revokedAt?: string | null;
  revokeReason?: string | null;
}

export interface PolicyRuntimeDto {
  evaluations: PolicyEvaluationDto[];
  currentEvaluations: PolicyEvaluationDto[];
  violations: PolicyViolationDto[];
  waivers: PolicyWaiverDto[];
  openViolationCount: number;
  waivedViolationCount: number;
  activeWaiverCount: number;
}

export async function getPolicyRuntime(signal?: AbortSignal): Promise<PolicyRuntimeDto> {
  return get<PolicyRuntimeDto>('/policy/runtime', undefined, signal);
}

export async function runPolicyEvaluation(): Promise<PolicyRuntimeDto & { runEvaluations: PolicyEvaluationDto[] }> {
  return post('/policy/runtime/run', {});
}

export async function createPolicyWaiver(body: {
  ruleId: string;
  targetId: string;
  reason: string;
  expiresAt: string;
}): Promise<{ waiver: PolicyWaiverDto }> {
  return post('/policy/runtime/waivers', body);
}

export async function revokePolicyWaiver(
  id: string,
  reason: string,
): Promise<{ waiver: PolicyWaiverDto }> {
  return post(`/policy/runtime/waivers/${encodeURIComponent(id)}/revoke`, { reason });
}
