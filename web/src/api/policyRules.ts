import { del, get, post, put } from './client';
import type { PolicyRuleSetDto, PolicyRuleSetFileDto } from '../types/api';

const ROOT = '/policy/rules';

export async function getPolicyRuleSets(signal?: AbortSignal): Promise<{ ruleSets: PolicyRuleSetFileDto[] }> {
  return get(ROOT, undefined, signal);
}

export async function createPolicyRuleSet(fileName: string, ruleSet: PolicyRuleSetDto): Promise<PolicyRuleSetFileDto> {
  return post(ROOT, { fileName, ruleSet });
}

export async function updatePolicyRuleSet(fileName: string, ruleSet: PolicyRuleSetDto): Promise<PolicyRuleSetFileDto> {
  return put(`${ROOT}/${encodeURIComponent(fileName)}`, ruleSet);
}

export async function deletePolicyRuleSet(fileName: string): Promise<void> {
  await del(`${ROOT}/${encodeURIComponent(fileName)}`);
}

export async function setPolicyRuleSetBinding(fileName: string, enabled: boolean): Promise<void> {
  await put(`${ROOT}/${encodeURIComponent(fileName)}/binding`, { enabled });
}
