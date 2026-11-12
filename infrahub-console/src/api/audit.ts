import type { PageResponse } from '@/types/api';
import { get } from '@/utils/http';

export interface SysAuditLog {
  id: number;
  operatorId: number;
  bizType: string;
  bizId: number;
  action: string;
  beforeData: string | null;
  afterData: string | null;
  result: string;
  ip: string | null;
  createdAt: string;
}

export interface AuditLogQuery {
  pageNum?: number;
  pageSize?: number;
  bizType?: string;
  bizId?: number;
  action?: string;
  result?: string;
  operatorId?: number;
  startTime?: string;
  endTime?: string;
}

export const pageAuditLogs = (params: AuditLogQuery) =>
  get<PageResponse<SysAuditLog>>('/api/audit/logs', params);
