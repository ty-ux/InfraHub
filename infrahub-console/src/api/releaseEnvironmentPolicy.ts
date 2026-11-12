import { get, put } from '@/utils/http';

export interface ReleaseEnvironmentPolicy {
  id?: number;
  envId: number;
  action: string;
  riskLevel: string;
  enabled: boolean;
  approvalRequired: boolean;
  precheckRequired: boolean;
  blocked: boolean;
  freezeWindowEnabled: boolean;
  freezeWindowStart?: string;
  freezeWindowEnd?: string;
  freezeWindowDays?: string;
  description?: string;
}

export const releaseEnvironmentPolicyApi = {
  list: () =>
    get<ReleaseEnvironmentPolicy[]>('/api/release-environment-policies'),

  /**
   * The endpoint answers with a successful envelope that carries no data, so the contract is a write
   * with nothing to read back: a request that resolves has saved, and no caller reads a value from it.
   */
  saveAll: (policies: ReleaseEnvironmentPolicy[]) =>
    put<void>('/api/release-environment-policies', policies),

  getEffective: (envId: number, action: string, riskLevel: string) =>
    get<ReleaseEnvironmentPolicy>('/api/release-environment-policies/effective', {
      envId,
      action,
      riskLevel
    })
};
