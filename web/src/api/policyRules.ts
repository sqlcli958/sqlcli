import { del, get, post, put } from './client';
import type { PolicyRuleSetDto, PolicyRuleSetFileDto } from '../types/api';

const ROOT = '/policy/rules';

export interface PolicyChangeResult {
  fileName: string;
  approvalId: number;
  applied: boolean;
  ruleSet?: PolicyRuleSetDto;
  enabled?: boolean;
}

export async function getPolicyRuleSets(signal?: AbortSignal): Promise<{ ruleSets: PolicyRuleSetFileDto[] }> {
  return get(ROOT, undefined, signal);
}

export async function createPolicyRuleSet(
  fileName: string,
  ruleSet: PolicyRuleSetDto,
  reason?: string,
): Promise<PolicyChangeResult> {
  return post(ROOT, { fileName, ruleSet, reason });
}

export async function updatePolicyRuleSet(
  fileName: string,
  ruleSet: PolicyRuleSetDto,
  reason?: string,
): Promise<PolicyChangeResult> {
  return put(`${ROOT}/${encodeURIComponent(fileName)}`, { ruleSet, reason });
}

export async function deletePolicyRuleSet(fileName: string): Promise<PolicyChangeResult> {
  return del(`${ROOT}/${encodeURIComponent(fileName)}`);
}

export async function setPolicyRuleSetBinding(
  fileName: string,
  enabled: boolean,
  reason?: string,
): Promise<PolicyChangeResult> {
  return put(`${ROOT}/${encodeURIComponent(fileName)}/binding`, { enabled, reason });
}
