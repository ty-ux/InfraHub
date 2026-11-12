import { get } from '@/utils/http';

/**
 * 数据库序列健康状态
 */
export interface DatabaseSequenceHealth {
  tableName: string;
  sequenceName: string;
  maxId: number;
  sequenceValue: number;
  healthy: boolean;
  suggestedFixSql?: string;
}

/**
 * 测试数据预览记录
 * 与后端 TestDataPreviewRecord 对应
 */
export interface TestDataPreviewRecord {
  id: number;
  name: string;
  type: string;
  createdAt?: string;
  description?: string;
}

/**
 * 测试数据预览分组
 * 与后端 TestDataPreviewGroup 对应
 */
export interface TestDataPreviewGroup {
  groupName: string;
  description: string;
  records: TestDataPreviewRecord[];
  recommendedAction: string;
  protectedEvidence: boolean;
}

/**
 * 获取数据库序列健康状态
 */
export function getReleaseSequenceHealth() {
  return get<DatabaseSequenceHealth[]>('/api/release-diagnostics/sequences');
}

/**
 * 获取测试数据治理预览
 * V1.8: 展示V1.4/V1.5/V1.6真实证据链和E2E临时数据
 */
export function getReleaseTestDataPreview() {
  return get<TestDataPreviewGroup[]>('/api/release-diagnostics/test-data-preview');
}

/**
 * 测试数据清理SQL响应
 * V1.9: 安全清理SQL脚本
 */
export interface TestDataCleanupSqlResponse {
  fileName: string;
  generatedAt: string;
  protectedEvidenceExcluded: boolean;
  sql: string;
}

/**
 * 生成测试数据清理SQL脚本
 * V1.9: 生成安全的清理SQL，包含ROLLBACK，不自动执行
 */
export function generateTestDataCleanupSql() {
  return get<TestDataCleanupSqlResponse>('/api/release-diagnostics/test-data-cleanup-sql');
}
