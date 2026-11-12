import type { PageResponse } from '@/types/api';
import { get, post, put, del } from '@/utils/http';

export interface ApprovalRequest {
  id: number;
  bizType: string;
  bizId: number;
  action: string;
  status: 'PENDING' | 'APPROVED' | 'REJECTED' | 'CANCELLED';
  applicantId: number;
  applicantName?: string;
  approverId?: number;
  approverName?: string;
  applyNote?: string;
  approveNote?: string;
  requestPayload?: string;
  createdAt: string;
  approvedAt?: string;
}

export interface ApprovalRequestQuery {
  pageNum: number;
  pageSize: number;
  status?: string;
  bizType?: string;
  applicantId?: number;
}

export interface CreateApprovalRequest {
  bizType: string;
  bizId: number;
  action: string;
  applyNote?: string;
  requestPayload?: string;
}

export interface ApprovalPolicy {
  id: number;
  bizType: string;
  action: string;
  enabled: boolean;
  createdAt: string;
  updatedAt: string;
}

export interface UpsertApprovalPolicy {
  bizType: string;
  action: string;
  enabled: boolean;
}

export const listApprovalRequests = (params: ApprovalRequestQuery) =>
  get<PageResponse<ApprovalRequest>>('/api/approvals/requests', params);

export const approveRequest = (id: number, note?: string) =>
  post<void>(`/api/approvals/requests/${id}/approve`, { approveNote: note });

export const rejectRequest = (id: number, note: string) =>
  post<void>(`/api/approvals/requests/${id}/reject`, { approveNote: note });

export const cancelRequest = (id: number) =>
  post<void>(`/api/approvals/requests/${id}/cancel`);

export const createApprovalRequest = (data: CreateApprovalRequest) =>
  post<number>('/api/approvals/requests', data);

export const upsertApprovalPolicy = (data: UpsertApprovalPolicy) =>
  put<ApprovalPolicy>('/api/approvals/policies', data);

export const listApprovalPolicies = () =>
  get<ApprovalPolicy[]>('/api/approvals/policies');