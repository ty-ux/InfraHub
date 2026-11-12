import type { PageResponse } from '@/types/api';
import { get } from '@/utils/http';

export interface GovernanceEvidenceChain {
  businessSummary: BusinessSummary;
  approvals: ApprovalRecord[];
  auditLogs: AuditRecord[];
  notifications: NotificationRecord[];
}

export interface BusinessSummary {
  bizType: string;
  bizId: number;
  displayId: string;
  status: string;
  details: Record<string, unknown>;
}

export interface ApprovalRecord {
  id: number;
  action: string;
  status: string;
  applicantId: number;
  applicantName?: string;
  applyNote: string;
  approverId: number;
  approverName?: string;
  approveNote: string;
  createdAt: string;
  approvedAt: string;
}

export interface AuditRecord {
  id: number;
  action: string;
  result: string;
  occurredAt: string;
  beforeData: Record<string, unknown>;
  afterData: Record<string, unknown>;
}

export interface NotificationRecord {
  id: number;
  eventType: string;
  status: string;
  targetUrl: string;
  errorMessage: string;
  createdAt: string;
}

export function getEvidenceChain(bizType: string, bizId: number) {
  return get<GovernanceEvidenceChain>('/api/governance/evidence-chain', {
    bizType,
    bizId
  });
}