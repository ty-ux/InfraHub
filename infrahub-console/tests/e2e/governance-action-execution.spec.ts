import { expect, test } from '@playwright/test';
import { loginAsAdmin } from './helpers/auth';

/**
 * E2E tests for V2/J-C Governance Action Execution Feature
 * Covers: Execution records, start/complete/fail/cancel execution
 * Uses route mocking to avoid database dependencies
 */

function apiResponse<T>(data: T) {
  return {
    code: 'SUCCESS',
    message: '',
    traceId: `trace-${Date.now()}`,
    data
  };
}

function pageResponse<T>(records: T[], total?: number) {
  return apiResponse({
    records,
    total: total ?? records.length,
    pageNum: 1,
    pageSize: 10
  });
}

test.describe('governance action execution', () => {
  test.setTimeout(60000);

  test.beforeEach(async ({ page }) => {
    await loginAsAdmin(page);
  });

  const mockSuggestions = [
    {
      id: 1,
      suggestionNo: 'GS-2024-001',
      suggestionType: 'SCALE_OUT',
      sourceType: 'RESOURCE_POOL_RISK',
      sourceId: 100,
      resourcePoolId: 1,
      resourcePoolName: 'Critical Risk Pool',
      envId: 1,
      envName: '生产环境',
      riskLevel: 'CRITICAL',
      title: 'CPU使用率超过90%，建议扩容',
      description: '资源池CPU使用率持续高于90%',
      status: 'OPEN',
      reason: '系统自动检测',
      createdBy: 'system',
      createdAt: new Date().toISOString()
    },
    {
      id: 2,
      suggestionNo: 'GS-2024-002',
      suggestionType: 'CHECK_HOST',
      sourceType: 'RESOURCE_POOL_RISK',
      sourceId: 200,
      resourcePoolId: 1,
      resourcePoolName: 'Critical Risk Pool',
      envId: 1,
      envName: '生产环境',
      riskLevel: 'WARNING',
      title: '主机 host-02 离线超过24小时',
      description: '主机已离线超过24小时',
      status: 'ACKED',
      reason: '主机心跳超时',
      createdBy: 'system',
      createdAt: new Date().toISOString()
    }
  ];

  const mockDraftPlan = {
    id: 101,
    planNo: 'AP-2024-001',
    suggestionId: 1,
    suggestionNo: 'GS-2024-001',
    actionType: 'SCALE_OUT_PLAN',
    targetType: 'RESOURCE_POOL',
    status: 'DRAFT',
    riskLevel: 'CRITICAL',
    resourcePoolId: 1,
    resourcePoolName: 'Critical Risk Pool',
    envId: 1,
    envName: '生产环境',
    planPayload: JSON.stringify({
      targetCpuCores: 32,
      targetMemoryGb: 128
    }),
    createdBy: 'admin',
    createdAt: new Date().toISOString()
  };

  const mockConfirmedPlan = {
    id: 102,
    planNo: 'AP-2024-002',
    suggestionId: 2,
    suggestionNo: 'GS-2024-002',
    actionType: 'CHECK_HOST_PLAN',
    targetType: 'HOST',
    status: 'CONFIRMED',
    riskLevel: 'WARNING',
    resourcePoolId: 1,
    resourcePoolName: 'Critical Risk Pool',
    envId: 1,
    envName: '生产环境',
    planPayload: JSON.stringify({
      hostId: 200,
      hostname: 'host-02',
      checkType: 'CONNECTIVITY'
    }),
    createdBy: 'admin',
    createdAt: new Date().toISOString(),
    confirmedBy: 'admin',
    confirmedAt: new Date().toISOString()
  };

  const mockExecutedPlan = {
    id: 103,
    planNo: 'AP-2024-003',
    suggestionId: 3,
    suggestionNo: 'GS-2024-003',
    actionType: 'INVESTIGATE_CAPACITY_PLAN',
    targetType: 'RESOURCE_POOL',
    status: 'EXECUTED',
    riskLevel: 'NORMAL',
    resourcePoolId: 2,
    resourcePoolName: 'Normal Pool',
    envId: 2,
    envName: '测试环境',
    planPayload: JSON.stringify({
      investigateType: 'CAPACITY'
    }),
    createdBy: 'admin',
    createdAt: new Date().toISOString(),
    confirmedBy: 'admin',
    confirmedAt: new Date().toISOString()
  };

  const mockRunningExecution = {
    id: 201,
    executionNo: 'AE-2024-001',
    planId: 102,
    planNo: 'AP-2024-002',
    status: 'RUNNING',
    executionMode: 'MANUAL_ONLY',
    operator: 'admin',
    startedAt: new Date().toISOString(),
    createdBy: 'admin',
    createdAt: new Date().toISOString()
  };

  const mockSuccessExecution = {
    id: 202,
    executionNo: 'AE-2024-002',
    planId: 103,
    planNo: 'AP-2024-003',
    status: 'SUCCESS',
    executionMode: 'MANUAL_ONLY',
    operator: 'admin',
    startedAt: new Date().toISOString(),
    finishedAt: new Date().toISOString(),
    resultSummary: '容量调查完成，资源充足',
    evidencePayload: JSON.stringify({ reportUrl: 'http://example.com/report' }),
    createdBy: 'admin',
    createdAt: new Date().toISOString()
  };

  const mockEnvs = [
    { id: 1, envCode: 'prod', envName: '生产环境' },
    { id: 2, envCode: 'test', envName: '测试环境' }
  ];

  const mockResourcePools = [
    { id: 1, poolCode: 'POOL-01', poolName: 'Critical Risk Pool', poolType: 'KUBERNETES', status: 'ACTIVE' },
    { id: 2, poolCode: 'POOL-02', poolName: 'Normal Pool', poolType: 'VM', status: 'ACTIVE' }
  ];

  async function setupMocks(page: import('@playwright/test').Page) {
    await page.route('**/api/envs**', async route => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(pageResponse(mockEnvs))
      });
    });

    await page.route('**/api/assets/resource-pools**', async route => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(pageResponse(mockResourcePools))
      });
    });

    await page.route('**/api/assets/resource-pools/*/capacity', async route => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(apiResponse({
          resourcePoolId: 1,
          resourcePoolName: 'Test Pool',
          cpuUsagePercent: 50,
          memoryUsagePercent: 60,
          onlineHostCount: 10,
          offlineHostCount: 1
        }))
      });
    });

    await page.route('**/api/assets/hosts**', async route => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(pageResponse([]))
      });
    });

    await page.route('**/api/assets/resource-governance/records**', async route => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(pageResponse([]))
      });
    });

    await page.route('**/api/runtime/service-instances/page**', async route => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(pageResponse([]))
      });
    });

    await page.route('**/api/assets/resource-pools/risks**', async route => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(pageResponse([]))
      });
    });

    await page.route('**/api/assets/governance-suggestions**', async route => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(pageResponse(mockSuggestions))
      });
    });
  }

  test('CONFIRMED plan shows start execution button', async ({ page }) => {
    await setupMocks(page);

    await page.route('**/api/assets/governance-suggestions**', async route => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(pageResponse([
          { ...mockSuggestions[1], existingPlan: mockConfirmedPlan }
        ]))
      });
    });

    await page.route('**/api/assets/governance-action-plans/102', async route => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(apiResponse(mockConfirmedPlan))
      });
    });

    await page.route('**/api/assets/governance-action-plans/102/executions', async route => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(pageResponse([]))
      });
    });

    await page.goto('/resource/governance');
    await expect(page.getByRole('heading', { name: '资源治理工作台' })).toBeVisible({ timeout: 10000 });

    await page.getByRole('tab', { name: '治理建议' }).click();
    await expect(page.locator('.governance-suggestions-tab')).toBeVisible({ timeout: 10000 });

    const suggestionsTable = page.locator('.governance-suggestions-tab .el-table').first();
    await expect(suggestionsTable).toBeVisible({ timeout: 10000 });

    const row = suggestionsTable.locator('tr').filter({ hasText: 'GS-2024-002' });
    await expect(row).toBeVisible({ timeout: 5000 });
    await page.waitForTimeout(500);

    await row.getByRole('button', { name: '查看计划' }).click();

    await expect(page.locator('.el-drawer').filter({ hasText: '计划详情' })).toBeVisible({ timeout: 5000 });

    await expect(page.locator('.el-drawer').getByRole('button', { name: '开始执行' })).toBeVisible();
  });

  test('clicking start execution creates RUNNING execution record', async ({ page }) => {
    await setupMocks(page);

    await page.route('**/api/assets/governance-suggestions**', async route => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(pageResponse([
          { ...mockSuggestions[1], existingPlan: mockConfirmedPlan }
        ]))
      });
    });

    await page.route('**/api/assets/governance-action-plans/102', async route => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(apiResponse(mockConfirmedPlan))
      });
    });

    await page.route('**/api/assets/governance-action-plans/102/executions', async route => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(pageResponse([]))
      });
    });

    await page.route('**/api/assets/governance-action-plans/102/executions/start', async route => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(apiResponse(mockRunningExecution))
      });
    });

    await page.goto('/resource/governance');
    await expect(page.getByRole('heading', { name: '资源治理工作台' })).toBeVisible({ timeout: 10000 });

    await page.getByRole('tab', { name: '治理建议' }).click();
    await expect(page.locator('.governance-suggestions-tab')).toBeVisible({ timeout: 10000 });

    const suggestionsTable = page.locator('.governance-suggestions-tab .el-table').first();
    await expect(suggestionsTable).toBeVisible({ timeout: 10000 });

    const row = suggestionsTable.locator('tr').filter({ hasText: 'GS-2024-002' });
    await expect(row).toBeVisible({ timeout: 5000 });
    await page.waitForTimeout(500);

    await row.getByRole('button', { name: '查看计划' }).click();

    await expect(page.locator('.el-drawer').filter({ hasText: '计划详情' })).toBeVisible({ timeout: 5000 });

    await page.locator('.el-drawer').getByRole('button', { name: '开始执行' }).click();

    await expect(page.locator('.el-message--success')).toBeVisible({ timeout: 5000 });
  });

  test('RUNNING execution shows mark complete/fail/cancel buttons', async ({ page }) => {
    await setupMocks(page);

    await page.route('**/api/assets/governance-suggestions**', async route => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(pageResponse([
          { ...mockSuggestions[1], existingPlan: mockConfirmedPlan }
        ]))
      });
    });

    await page.route('**/api/assets/governance-action-plans/102', async route => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(apiResponse(mockConfirmedPlan))
      });
    });

    await page.route('**/api/assets/governance-action-plans/102/executions', async route => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(pageResponse([mockRunningExecution]))
      });
    });

    await page.goto('/resource/governance');
    await expect(page.getByRole('heading', { name: '资源治理工作台' })).toBeVisible({ timeout: 10000 });

    await page.getByRole('tab', { name: '治理建议' }).click();
    await expect(page.locator('.governance-suggestions-tab')).toBeVisible({ timeout: 10000 });

    const suggestionsTable = page.locator('.governance-suggestions-tab .el-table').first();
    await expect(suggestionsTable).toBeVisible({ timeout: 10000 });

    const row = suggestionsTable.locator('tr').filter({ hasText: 'GS-2024-002' });
    await expect(row).toBeVisible({ timeout: 5000 });
    await page.waitForTimeout(500);

    await row.getByRole('button', { name: '查看计划' }).click();

    await expect(page.locator('.el-drawer').filter({ hasText: '计划详情' })).toBeVisible({ timeout: 5000 });

    await expect(page.locator('.execution-records-section')).toBeVisible({ timeout: 5000 });

    const execRow = page.locator('.execution-records-section .el-table').locator('tr').filter({ hasText: 'AE-2024-001' });
    await expect(execRow).toBeVisible({ timeout: 5000 });

    await expect(execRow.getByRole('button', { name: '标记完成' })).toBeVisible();
    await expect(execRow.getByRole('button', { name: '标记失败' })).toBeVisible();
    await expect(execRow.getByRole('button', { name: '取消执行' })).toBeVisible();
  });

  test('RUNNING execution can be marked complete', async ({ page }) => {
    await setupMocks(page);

    await page.route('**/api/assets/governance-suggestions**', async route => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(pageResponse([
          { ...mockSuggestions[1], existingPlan: mockConfirmedPlan }
        ]))
      });
    });

    await page.route('**/api/assets/governance-action-plans/102', async route => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(apiResponse(mockConfirmedPlan))
      });
    });

    await page.route('**/api/assets/governance-action-plans/102/executions', async route => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(pageResponse([mockRunningExecution]))
      });
    });

    await page.route('**/api/assets/governance-action-executions/201/complete', async route => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(apiResponse({
          ...mockRunningExecution,
          status: 'SUCCESS',
          finishedAt: new Date().toISOString(),
          resultSummary: '执行完成'
        }))
      });
    });

    await page.goto('/resource/governance');
    await expect(page.getByRole('heading', { name: '资源治理工作台' })).toBeVisible({ timeout: 10000 });

    await page.getByRole('tab', { name: '治理建议' }).click();
    await expect(page.locator('.governance-suggestions-tab')).toBeVisible({ timeout: 10000 });

    const suggestionsTable = page.locator('.governance-suggestions-tab .el-table').first();
    await expect(suggestionsTable).toBeVisible({ timeout: 10000 });

    const row = suggestionsTable.locator('tr').filter({ hasText: 'GS-2024-002' });
    await expect(row).toBeVisible({ timeout: 5000 });
    await page.waitForTimeout(500);

    await row.getByRole('button', { name: '查看计划' }).click();

    await expect(page.locator('.el-drawer').filter({ hasText: '计划详情' })).toBeVisible({ timeout: 5000 });

    const execRow = page.locator('.execution-records-section .el-table').locator('tr').filter({ hasText: 'AE-2024-001' });
    await expect(execRow).toBeVisible({ timeout: 5000 });

    await execRow.getByRole('button', { name: '标记完成' }).click();

    await expect(page.locator('.el-dialog').filter({ hasText: '标记执行完成' })).toBeVisible({ timeout: 5000 });

    await page.locator('.el-dialog textarea').first().fill('执行结果正常');

    await page.locator('.el-dialog').filter({ hasText: '标记执行完成' }).getByRole('button', { name: '确认' }).click();

    await expect(page.locator('.el-message--success')).toBeVisible({ timeout: 5000 });
  });

  test('RUNNING execution can be marked failed', async ({ page }) => {
    await setupMocks(page);

    await page.route('**/api/assets/governance-suggestions**', async route => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(pageResponse([
          { ...mockSuggestions[1], existingPlan: mockConfirmedPlan }
        ]))
      });
    });

    await page.route('**/api/assets/governance-action-plans/102', async route => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(apiResponse(mockConfirmedPlan))
      });
    });

    await page.route('**/api/assets/governance-action-plans/102/executions', async route => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(pageResponse([mockRunningExecution]))
      });
    });

    await page.route('**/api/assets/governance-action-executions/201/fail', async route => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(apiResponse({
          ...mockRunningExecution,
          status: 'FAILED',
          finishedAt: new Date().toISOString(),
          failureReason: '执行失败'
        }))
      });
    });

    await page.goto('/resource/governance');
    await expect(page.getByRole('heading', { name: '资源治理工作台' })).toBeVisible({ timeout: 10000 });

    await page.getByRole('tab', { name: '治理建议' }).click();
    await expect(page.locator('.governance-suggestions-tab')).toBeVisible({ timeout: 10000 });

    const suggestionsTable = page.locator('.governance-suggestions-tab .el-table').first();
    await expect(suggestionsTable).toBeVisible({ timeout: 10000 });

    const row = suggestionsTable.locator('tr').filter({ hasText: 'GS-2024-002' });
    await expect(row).toBeVisible({ timeout: 5000 });
    await page.waitForTimeout(500);

    await row.getByRole('button', { name: '查看计划' }).click();

    await expect(page.locator('.el-drawer').filter({ hasText: '计划详情' })).toBeVisible({ timeout: 5000 });

    const execRow = page.locator('.execution-records-section .el-table').locator('tr').filter({ hasText: 'AE-2024-001' });
    await expect(execRow).toBeVisible({ timeout: 5000 });

    await execRow.getByRole('button', { name: '标记失败' }).click();

    await expect(page.locator('.el-dialog').filter({ hasText: '标记执行失败' })).toBeVisible({ timeout: 5000 });

    await page.locator('.el-dialog textarea').first().fill('执行过程中出现错误');

    await page.locator('.el-dialog').filter({ hasText: '标记执行失败' }).getByRole('button', { name: '确认' }).click();

    await expect(page.locator('.el-message--success')).toBeVisible({ timeout: 5000 });
  });

  test('RUNNING execution can be cancelled', async ({ page }) => {
    await setupMocks(page);

    await page.route('**/api/assets/governance-suggestions**', async route => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(pageResponse([
          { ...mockSuggestions[1], existingPlan: mockConfirmedPlan }
        ]))
      });
    });

    await page.route('**/api/assets/governance-action-plans/102', async route => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(apiResponse(mockConfirmedPlan))
      });
    });

    await page.route('**/api/assets/governance-action-plans/102/executions', async route => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(pageResponse([mockRunningExecution]))
      });
    });

    await page.route('**/api/assets/governance-action-executions/201/cancel', async route => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(apiResponse({
          ...mockRunningExecution,
          status: 'CANCELLED',
          finishedAt: new Date().toISOString()
        }))
      });
    });

    await page.goto('/resource/governance');
    await expect(page.getByRole('heading', { name: '资源治理工作台' })).toBeVisible({ timeout: 10000 });

    await page.getByRole('tab', { name: '治理建议' }).click();
    await expect(page.locator('.governance-suggestions-tab')).toBeVisible({ timeout: 10000 });

    const suggestionsTable = page.locator('.governance-suggestions-tab .el-table').first();
    await expect(suggestionsTable).toBeVisible({ timeout: 10000 });

    const row = suggestionsTable.locator('tr').filter({ hasText: 'GS-2024-002' });
    await expect(row).toBeVisible({ timeout: 5000 });
    await page.waitForTimeout(500);

    await row.getByRole('button', { name: '查看计划' }).click();

    await expect(page.locator('.el-drawer').filter({ hasText: '计划详情' })).toBeVisible({ timeout: 5000 });

    const execRow = page.locator('.execution-records-section .el-table').locator('tr').filter({ hasText: 'AE-2024-001' });
    await expect(execRow).toBeVisible({ timeout: 5000 });

    await execRow.getByRole('button', { name: '取消执行' }).click();

    await expect(page.locator('.el-dialog').filter({ hasText: '取消执行' })).toBeVisible({ timeout: 5000 });

    await page.locator('.el-dialog textarea').first().fill('不再需要执行');

    await page.locator('.el-dialog').filter({ hasText: '取消执行' }).getByRole('button', { name: '确认' }).click();

    await expect(page.locator('.el-message--success')).toBeVisible({ timeout: 5000 });
  });

  test('DRAFT plan does not show start execution button', async ({ page }) => {
    await setupMocks(page);

    await page.route('**/api/assets/governance-suggestions**', async route => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(pageResponse([
          { ...mockSuggestions[0], existingPlan: mockDraftPlan }
        ]))
      });
    });

    await page.route('**/api/assets/governance-action-plans/101', async route => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(apiResponse(mockDraftPlan))
      });
    });

    await page.route('**/api/assets/governance-action-plans/101/executions', async route => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(pageResponse([]))
      });
    });

    await page.goto('/resource/governance');
    await expect(page.getByRole('heading', { name: '资源治理工作台' })).toBeVisible({ timeout: 10000 });

    await page.getByRole('tab', { name: '治理建议' }).click();
    await expect(page.locator('.governance-suggestions-tab')).toBeVisible({ timeout: 10000 });

    const suggestionsTable = page.locator('.governance-suggestions-tab .el-table').first();
    await expect(suggestionsTable).toBeVisible({ timeout: 10000 });

    const row = suggestionsTable.locator('tr').filter({ hasText: 'GS-2024-001' });
    await expect(row).toBeVisible({ timeout: 5000 });
    await page.waitForTimeout(500);

    await row.getByRole('button', { name: '查看计划' }).click();

    await expect(page.locator('.el-drawer').filter({ hasText: '计划详情' })).toBeVisible({ timeout: 5000 });

    await expect(page.locator('.el-drawer').getByRole('button', { name: '开始执行' })).not.toBeVisible();
  });

  test('EXECUTED plan does not show execution action buttons', async ({ page }) => {
    await setupMocks(page);

    await page.route('**/api/assets/governance-suggestions**', async route => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(pageResponse([
          { ...mockSuggestions[1], existingPlan: mockExecutedPlan }
        ]))
      });
    });

    await page.route('**/api/assets/governance-action-plans/103', async route => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(apiResponse(mockExecutedPlan))
      });
    });

    await page.route('**/api/assets/governance-action-plans/103/executions', async route => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(pageResponse([mockSuccessExecution]))
      });
    });

    await page.goto('/resource/governance');
    await expect(page.getByRole('heading', { name: '资源治理工作台' })).toBeVisible({ timeout: 10000 });

    await page.getByRole('tab', { name: '治理建议' }).click();
    await expect(page.locator('.governance-suggestions-tab')).toBeVisible({ timeout: 10000 });

    const suggestionsTable = page.locator('.governance-suggestions-tab .el-table').first();
    await expect(suggestionsTable).toBeVisible({ timeout: 10000 });

    const row = suggestionsTable.locator('tr').filter({ hasText: 'GS-2024-002' });
    await expect(row).toBeVisible({ timeout: 5000 });
    await page.waitForTimeout(500);

    await row.getByRole('button', { name: '查看计划' }).click();

    await expect(page.locator('.el-drawer').filter({ hasText: '计划详情' })).toBeVisible({ timeout: 5000 });

    await expect(page.locator('.el-drawer').getByRole('button', { name: '开始执行' })).not.toBeVisible();

    const execRow = page.locator('.execution-records-section .el-table').locator('tr').filter({ hasText: 'AE-2024-002' });
    await expect(execRow).toBeVisible({ timeout: 5000 });

    await expect(execRow.getByRole('button', { name: '标记完成' })).not.toBeVisible();
    await expect(execRow.getByRole('button', { name: '标记失败' })).not.toBeVisible();
    await expect(execRow.getByRole('button', { name: '取消执行' })).not.toBeVisible();
  });

  test('page does not contain auto execute or auto scale text', async ({ page }) => {
    await setupMocks(page);

    await page.route('**/api/assets/governance-suggestions**', async route => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(pageResponse([
          { ...mockSuggestions[1], existingPlan: mockConfirmedPlan }
        ]))
      });
    });

    await page.route('**/api/assets/governance-action-plans/102', async route => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(apiResponse(mockConfirmedPlan))
      });
    });

    await page.route('**/api/assets/governance-action-plans/102/executions', async route => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(pageResponse([mockRunningExecution]))
      });
    });

    await page.goto('/resource/governance');
    await expect(page.getByRole('heading', { name: '资源治理工作台' })).toBeVisible({ timeout: 10000 });

    await page.getByRole('tab', { name: '治理建议' }).click();
    await expect(page.locator('.governance-suggestions-tab')).toBeVisible({ timeout: 10000 });

    const suggestionsTable = page.locator('.governance-suggestions-tab .el-table').first();
    await expect(suggestionsTable).toBeVisible({ timeout: 10000 });

    const row = suggestionsTable.locator('tr').filter({ hasText: 'GS-2024-002' });
    await expect(row).toBeVisible({ timeout: 5000 });
    await page.waitForTimeout(500);

    await row.getByRole('button', { name: '查看计划' }).click();

    await expect(page.locator('.el-drawer').filter({ hasText: '计划详情' })).toBeVisible({ timeout: 5000 });

    // Use specific drawer locator to avoid strict mode violation with multiple drawers
    const planDrawer = page.locator('.el-drawer').filter({ hasText: '计划详情' });
    await expect(planDrawer).not.toContainText('自动执行');
    await expect(planDrawer).not.toContainText('自动扩容');
    await expect(planDrawer).not.toContainText('自动清理');
    await expect(planDrawer).not.toContainText('调用执行器');
    await expect(planDrawer).not.toContainText('Auto execute');
    await expect(planDrawer).not.toContainText('Auto scale');
    await expect(planDrawer).not.toContainText('executor');
  });

  test('complete execution with JSON evidence sends object evidencePayload', async ({ page }) => {
    await setupMocks(page);

    await page.route('**/api/assets/governance-suggestions**', async route => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(pageResponse([
          { ...mockSuggestions[1], existingPlan: mockConfirmedPlan }
        ]))
      });
    });

    await page.route('**/api/assets/governance-action-plans/102', async route => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(apiResponse(mockConfirmedPlan))
      });
    });

    await page.route('**/api/assets/governance-action-plans/102/executions', async route => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(pageResponse([mockRunningExecution]))
      });
    });

    let capturedRequestBody: unknown = null;
    await page.route('**/api/assets/governance-action-executions/201/complete', async route => {
      const request = route.request();
      capturedRequestBody = request.postDataJSON();
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(apiResponse({
          ...mockRunningExecution,
          status: 'SUCCESS',
          finishedAt: new Date().toISOString(),
          resultSummary: '执行完成'
        }))
      });
    });

    await page.goto('/resource/governance');
    await expect(page.getByRole('heading', { name: '资源治理工作台' })).toBeVisible({ timeout: 10000 });

    await page.getByRole('tab', { name: '治理建议' }).click();
    await expect(page.locator('.governance-suggestions-tab')).toBeVisible({ timeout: 10000 });

    const suggestionsTable = page.locator('.governance-suggestions-tab .el-table').first();
    await expect(suggestionsTable).toBeVisible({ timeout: 10000 });

    const row = suggestionsTable.locator('tr').filter({ hasText: 'GS-2024-002' });
    await expect(row).toBeVisible({ timeout: 5000 });
    await page.waitForTimeout(500);

    await row.getByRole('button', { name: '查看计划' }).click();

    await expect(page.locator('.el-drawer').filter({ hasText: '计划详情' })).toBeVisible({ timeout: 5000 });

    const execRow = page.locator('.execution-records-section .el-table').locator('tr').filter({ hasText: 'AE-2024-001' });
    await expect(execRow).toBeVisible({ timeout: 5000 });

    await execRow.getByRole('button', { name: '标记完成' }).click();

    await expect(page.locator('.el-dialog').filter({ hasText: '标记执行完成' })).toBeVisible({ timeout: 5000 });

    await page.locator('.el-dialog textarea').first().fill('扩容完成');
    // Enter JSON object in evidence field
    await page.locator('.el-dialog textarea').nth(1).fill('{ "hostsAdded": 3, "reportUrl": "http://example.com/report" }');

    await page.locator('.el-dialog').filter({ hasText: '标记执行完成' }).getByRole('button', { name: '确认' }).click();

    await expect(page.locator('.el-message--success')).toBeVisible({ timeout: 5000 });

    // Verify evidencePayload is an object (not a string)
    expect(capturedRequestBody).toBeDefined();
    const body = capturedRequestBody as Record<string, unknown>;
    expect(body.evidencePayload).toBeDefined();
    expect(typeof body.evidencePayload).toBe('object');
    expect(body.evidencePayload).toEqual({ hostsAdded: 3, reportUrl: 'http://example.com/report' });
  });

  test('complete execution with plain text sends wrapped evidencePayload', async ({ page }) => {
    await setupMocks(page);

    await page.route('**/api/assets/governance-suggestions**', async route => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(pageResponse([
          { ...mockSuggestions[1], existingPlan: mockConfirmedPlan }
        ]))
      });
    });

    await page.route('**/api/assets/governance-action-plans/102', async route => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(apiResponse(mockConfirmedPlan))
      });
    });

    await page.route('**/api/assets/governance-action-plans/102/executions', async route => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(pageResponse([mockRunningExecution]))
      });
    });

    let capturedRequestBody: unknown = null;
    await page.route('**/api/assets/governance-action-executions/201/complete', async route => {
      const request = route.request();
      capturedRequestBody = request.postDataJSON();
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(apiResponse({
          ...mockRunningExecution,
          status: 'SUCCESS',
          finishedAt: new Date().toISOString(),
          resultSummary: '执行完成'
        }))
      });
    });

    await page.goto('/resource/governance');
    await expect(page.getByRole('heading', { name: '资源治理工作台' })).toBeVisible({ timeout: 10000 });

    await page.getByRole('tab', { name: '治理建议' }).click();
    await expect(page.locator('.governance-suggestions-tab')).toBeVisible({ timeout: 10000 });

    const suggestionsTable = page.locator('.governance-suggestions-tab .el-table').first();
    await expect(suggestionsTable).toBeVisible({ timeout: 10000 });

    const row = suggestionsTable.locator('tr').filter({ hasText: 'GS-2024-002' });
    await expect(row).toBeVisible({ timeout: 5000 });
    await page.waitForTimeout(500);

    await row.getByRole('button', { name: '查看计划' }).click();

    await expect(page.locator('.el-drawer').filter({ hasText: '计划详情' })).toBeVisible({ timeout: 5000 });

    const execRow = page.locator('.execution-records-section .el-table').locator('tr').filter({ hasText: 'AE-2024-001' });
    await expect(execRow).toBeVisible({ timeout: 5000 });

    await execRow.getByRole('button', { name: '标记完成' }).click();

    await expect(page.locator('.el-dialog').filter({ hasText: '标记执行完成' })).toBeVisible({ timeout: 5000 });

    await page.locator('.el-dialog textarea').first().fill('扩容完成');
    // Enter plain text in evidence field (not JSON)
    await page.locator('.el-dialog textarea').nth(1).fill('This is plain text evidence');

    await page.locator('.el-dialog').filter({ hasText: '标记执行完成' }).getByRole('button', { name: '确认' }).click();

    await expect(page.locator('.el-message--success')).toBeVisible({ timeout: 5000 });

    // Verify evidencePayload is wrapped as { text: "..." }
    expect(capturedRequestBody).toBeDefined();
    const body = capturedRequestBody as Record<string, unknown>;
    expect(body.evidencePayload).toBeDefined();
    expect(typeof body.evidencePayload).toBe('object');
    expect(body.evidencePayload).toEqual({ text: 'This is plain text evidence' });
  });

  test('fail execution with plain text sends wrapped evidencePayload', async ({ page }) => {
    await setupMocks(page);

    await page.route('**/api/assets/governance-suggestions**', async route => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(pageResponse([
          { ...mockSuggestions[1], existingPlan: mockConfirmedPlan }
        ]))
      });
    });

    await page.route('**/api/assets/governance-action-plans/102', async route => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(apiResponse(mockConfirmedPlan))
      });
    });

    await page.route('**/api/assets/governance-action-plans/102/executions', async route => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(pageResponse([mockRunningExecution]))
      });
    });

    let capturedRequestBody: unknown = null;
    await page.route('**/api/assets/governance-action-executions/201/fail', async route => {
      const request = route.request();
      capturedRequestBody = request.postDataJSON();
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(apiResponse({
          ...mockRunningExecution,
          status: 'FAILED',
          finishedAt: new Date().toISOString(),
          failureReason: 'SSH连接超时'
        }))
      });
    });

    await page.goto('/resource/governance');
    await expect(page.getByRole('heading', { name: '资源治理工作台' })).toBeVisible({ timeout: 10000 });

    await page.getByRole('tab', { name: '治理建议' }).click();
    await expect(page.locator('.governance-suggestions-tab')).toBeVisible({ timeout: 10000 });

    const suggestionsTable = page.locator('.governance-suggestions-tab .el-table').first();
    await expect(suggestionsTable).toBeVisible({ timeout: 10000 });

    const row = suggestionsTable.locator('tr').filter({ hasText: 'GS-2024-002' });
    await expect(row).toBeVisible({ timeout: 5000 });
    await page.waitForTimeout(500);

    await row.getByRole('button', { name: '查看计划' }).click();

    await expect(page.locator('.el-drawer').filter({ hasText: '计划详情' })).toBeVisible({ timeout: 5000 });

    const execRow = page.locator('.execution-records-section .el-table').locator('tr').filter({ hasText: 'AE-2024-001' });
    await expect(execRow).toBeVisible({ timeout: 5000 });

    await execRow.getByRole('button', { name: '标记失败' }).click();

    await expect(page.locator('.el-dialog').filter({ hasText: '标记执行失败' })).toBeVisible({ timeout: 5000 });

    await page.locator('.el-dialog textarea').first().fill('SSH连接超时');
    // Enter plain text in evidence field (not JSON)
    await page.locator('.el-dialog textarea').nth(1).fill('Connection timeout after 30s');

    await page.locator('.el-dialog').filter({ hasText: '标记执行失败' }).getByRole('button', { name: '确认' }).click();

    await expect(page.locator('.el-message--success')).toBeVisible({ timeout: 5000 });

    // Verify evidencePayload is wrapped as { text: "..." }
    expect(capturedRequestBody).toBeDefined();
    const body = capturedRequestBody as Record<string, unknown>;
    expect(body.evidencePayload).toBeDefined();
    expect(typeof body.evidencePayload).toBe('object');
    expect(body.evidencePayload).toEqual({ text: 'Connection timeout after 30s' });
  });
});
