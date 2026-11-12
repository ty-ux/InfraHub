import { expect, test } from '@playwright/test';
import { loginAsAdmin } from './helpers/auth';

/**
 * E2E tests for V2/I-C Governance Action Plan Feature
 * Covers: Action Plan generation, confirmation, cancellation, and display
 * Uses route mocking to avoid database dependencies
 */

/**
 * Helper to wrap response in ApiResponse structure
 */
function apiResponse<T>(data: T) {
  return {
    code: 'SUCCESS',
    message: '',
    traceId: `trace-${Date.now()}`,
    data
  };
}

/**
 * Helper to wrap paginated response
 */
function pageResponse<T>(records: T[], total?: number) {
  return apiResponse({
    records,
    total: total ?? records.length,
    pageNum: 1,
    pageSize: 10
  });
}

test.describe('governance action plan', () => {
  test.setTimeout(60000);

  test.beforeEach(async ({ page }) => {
    await loginAsAdmin(page);
  });

  /**
   * Mock data for governance suggestions
   */
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
      description: '资源池CPU使用率持续高于90%，建议增加主机或优化资源分配',
      status: 'OPEN',
      reason: '系统自动检测到CPU使用率持续高位运行',
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
      description: '主机 host-02 已离线超过24小时，建议检查网络或重启服务',
      status: 'ACKED',
      reason: '主机心跳超时',
      createdBy: 'system',
      createdAt: new Date().toISOString()
    },
    {
      id: 3,
      suggestionNo: 'GS-2024-003',
      suggestionType: 'CLEANUP_INSTANCE',
      sourceType: 'RESOURCE_POOL_RISK',
      sourceId: null,
      resourcePoolId: 2,
      resourcePoolName: 'Normal Pool',
      envId: 2,
      envName: '测试环境',
      riskLevel: 'NORMAL',
      title: '测试实例过多，建议清理',
      description: '测试环境中存在大量已停止的实例，建议定期清理',
      status: 'IGNORED',
      reason: '资源池风险评估',
      createdBy: 'system',
      createdAt: new Date().toISOString()
    },
    {
      id: 4,
      suggestionNo: 'GS-2024-004',
      suggestionType: 'INVESTIGATE_CAPACITY',
      sourceType: 'RESOURCE_POOL_RISK',
      sourceId: null,
      resourcePoolId: 3,
      resourcePoolName: 'Stable Pool',
      envId: 1,
      envName: '生产环境',
      riskLevel: 'WARNING',
      title: '磁盘使用率超过70%',
      description: '资源池磁盘使用率较高，建议清理或扩容',
      status: 'RESOLVED',
      reason: '系统自动检测',
      createdBy: 'system',
      createdAt: new Date().toISOString()
    }
  ];

  /**
   * Mock data for action plans
   */
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
      targetMemoryGb: 128,
      estimatedCost: 5000,
      reason: 'CPU使用率持续超过90%'
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

  const mockCancelledPlan = {
    id: 103,
    planNo: 'AP-2024-003',
    suggestionId: 5,
    suggestionNo: 'GS-2024-005',
    actionType: 'INVESTIGATE_CAPACITY_PLAN',
    targetType: 'RESOURCE_POOL',
    status: 'CANCELLED',
    riskLevel: 'NORMAL',
    resourcePoolId: 2,
    resourcePoolName: 'Normal Pool',
    envId: 2,
    envName: '测试环境',
    planPayload: JSON.stringify({
      investigateType: 'CAPACITY',
      reason: '测试环境容量调查'
    }),
    cancelReason: '业务需求变更，暂不执行',
    createdBy: 'admin',
    createdAt: new Date().toISOString(),
    cancelledBy: 'admin',
    cancelledAt: new Date().toISOString()
  };

  const mockEnvs = [
    { id: 1, envCode: 'prod', envName: '生产环境' },
    { id: 2, envCode: 'test', envName: '测试环境' }
  ];

  const mockResourcePools = [
    { id: 1, poolCode: 'POOL-CRITICAL-01', poolName: 'Critical Risk Pool', poolType: 'KUBERNETES', status: 'ACTIVE' },
    { id: 2, poolCode: 'POOL-NORMAL-01', poolName: 'Normal Pool', poolType: 'VM', status: 'ACTIVE' },
    { id: 3, poolCode: 'POOL-STABLE-01', poolName: 'Stable Pool', poolType: 'KUBERNETES', status: 'ACTIVE' }
  ];

  const mockHosts = [
    { id: 1, hostname: 'host-01', ip: '192.0.2.17', envId: 1, resourcePoolId: 1, agentStatus: 'ONLINE' },
    { id: 2, hostname: 'host-02', ip: '192.0.2.18', envId: 1, resourcePoolId: 1, agentStatus: 'OFFLINE' }
  ];

  const mockServices = [
    { id: 1, serviceCode: 'svc-a', serviceName: 'Service A' },
    { id: 2, serviceCode: 'svc-b', serviceName: 'Service B' }
  ];

  /**
   * Setup all route mocks for governance suggestions page
   */
  async function setupMocks(page: import('@playwright/test').Page) {
    await page.route('**/api/envs**', async route => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(pageResponse(mockEnvs))
      });
    });

    await page.route('**/api/services**', async route => {
      if (route.request().url().includes('/page')) {
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify(pageResponse(mockServices))
        });
      } else {
        await route.continue();
      }
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
        body: JSON.stringify(pageResponse(mockHosts))
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

    // Governance Suggestions API mock
    await page.route('**/api/assets/governance-suggestions**', async route => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(pageResponse(mockSuggestions))
      });
    });
  }

  test('OPEN suggestion shows generate plan button', async ({ page }) => {
    await setupMocks(page);

    await page.goto('/resource/governance');
    await expect(page.getByRole('heading', { name: '资源治理工作台' })).toBeVisible({ timeout: 10000 });

    await page.getByRole('tab', { name: '治理建议' }).click();
    await expect(page.locator('.governance-suggestions-tab')).toBeVisible({ timeout: 10000 });

    const suggestionsTable = page.locator('.governance-suggestions-tab .el-table').first();
    await expect(suggestionsTable).toBeVisible({ timeout: 10000 });

    // Find the first row with OPEN status
    const openRow = suggestionsTable.locator('tr').filter({ hasText: 'GS-2024-001' });
    await expect(openRow).toBeVisible({ timeout: 5000 });

    // Verify generate plan button is visible for OPEN status
    await expect(openRow.getByRole('button', { name: '生成计划' })).toBeVisible();
  });

  test('ACKED suggestion shows generate plan button', async ({ page }) => {
    await setupMocks(page);

    await page.goto('/resource/governance');
    await expect(page.getByRole('heading', { name: '资源治理工作台' })).toBeVisible({ timeout: 10000 });

    await page.getByRole('tab', { name: '治理建议' }).click();
    await expect(page.locator('.governance-suggestions-tab')).toBeVisible({ timeout: 10000 });

    const suggestionsTable = page.locator('.governance-suggestions-tab .el-table').first();
    await expect(suggestionsTable).toBeVisible({ timeout: 10000 });

    // Find the row with ACKED status
    const ackedRow = suggestionsTable.locator('tr').filter({ hasText: 'GS-2024-002' });
    await expect(ackedRow).toBeVisible({ timeout: 5000 });

    // Verify generate plan button is visible for ACKED status
    await expect(ackedRow.getByRole('button', { name: '生成计划' })).toBeVisible();
  });

  test('RESOLVED suggestion does not show generate plan button', async ({ page }) => {
    await setupMocks(page);

    await page.goto('/resource/governance');
    await expect(page.getByRole('heading', { name: '资源治理工作台' })).toBeVisible({ timeout: 10000 });

    await page.getByRole('tab', { name: '治理建议' }).click();
    await expect(page.locator('.governance-suggestions-tab')).toBeVisible({ timeout: 10000 });

    const suggestionsTable = page.locator('.governance-suggestions-tab .el-table').first();
    await expect(suggestionsTable).toBeVisible({ timeout: 10000 });

    // Find the row with RESOLVED status
    const resolvedRow = suggestionsTable.locator('tr').filter({ hasText: 'GS-2024-004' });
    await expect(resolvedRow).toBeVisible({ timeout: 5000 });

    // Verify generate plan button is NOT visible for RESOLVED status
    await expect(resolvedRow.getByRole('button', { name: '生成计划' })).not.toBeVisible();
  });

  test('IGNORED suggestion does not show generate plan button', async ({ page }) => {
    await setupMocks(page);

    await page.goto('/resource/governance');
    await expect(page.getByRole('heading', { name: '资源治理工作台' })).toBeVisible({ timeout: 10000 });

    await page.getByRole('tab', { name: '治理建议' }).click();
    await expect(page.locator('.governance-suggestions-tab')).toBeVisible({ timeout: 10000 });

    const suggestionsTable = page.locator('.governance-suggestions-tab .el-table').first();
    await expect(suggestionsTable).toBeVisible({ timeout: 10000 });

    // Find the row with IGNORED status
    const ignoredRow = suggestionsTable.locator('tr').filter({ hasText: 'GS-2024-003' });
    await expect(ignoredRow).toBeVisible({ timeout: 5000 });

    // Verify generate plan button is NOT visible for IGNORED status
    await expect(ignoredRow.getByRole('button', { name: '生成计划' })).not.toBeVisible();
  });

  test('clicking generate plan opens plan detail drawer', async ({ page }) => {
    await setupMocks(page);

    // Mock the action plan creation API
    await page.route('**/api/assets/governance-suggestions/1/action-plan', async route => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(apiResponse(mockDraftPlan))
      });
    });

    await page.goto('/resource/governance');
    await expect(page.getByRole('heading', { name: '资源治理工作台' })).toBeVisible({ timeout: 10000 });

    await page.getByRole('tab', { name: '治理建议' }).click();
    await expect(page.locator('.governance-suggestions-tab')).toBeVisible({ timeout: 10000 });

    const suggestionsTable = page.locator('.governance-suggestions-tab .el-table').first();
    await expect(suggestionsTable).toBeVisible({ timeout: 10000 });

    const openRow = suggestionsTable.locator('tr').filter({ hasText: 'GS-2024-001' });
    await expect(openRow).toBeVisible({ timeout: 5000 });

    // Click generate plan button
    await openRow.getByRole('button', { name: '生成计划' }).click();

    // Verify drawer opens
    await expect(page.locator('.el-drawer').filter({ hasText: '计划详情' })).toBeVisible({ timeout: 5000 });
  });

  test('DRAFT plan can be confirmed', async ({ page }) => {
    await setupMocks(page);

    // Override suggestions mock with existing plan
    await page.route('**/api/assets/governance-suggestions**', async route => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(pageResponse([
          { ...mockSuggestions[0], existingPlan: mockDraftPlan }
        ]))
      });
    });

    // Mock get plan detail
    await page.route('**/api/assets/governance-action-plans/101', async route => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(apiResponse(mockDraftPlan))
      });
    });

    // Mock confirm plan
    await page.route('**/api/assets/governance-action-plans/101/confirm', async route => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(apiResponse({ ...mockDraftPlan, status: 'CONFIRMED' }))
      });
    });

    await page.goto('/resource/governance');
    await expect(page.getByRole('heading', { name: '资源治理工作台' })).toBeVisible({ timeout: 10000 });

    await page.getByRole('tab', { name: '治理建议' }).click();
    await expect(page.locator('.governance-suggestions-tab')).toBeVisible({ timeout: 10000 });

    // Wait for table to load
    const suggestionsTable = page.locator('.governance-suggestions-tab .el-table').first();
    await expect(suggestionsTable).toBeVisible({ timeout: 10000 });

    // Wait for the row with the view plan button
    const row = suggestionsTable.locator('tr').filter({ hasText: 'GS-2024-001' });
    await expect(row).toBeVisible({ timeout: 10000 });

    // Wait a bit for Vue reactivity
    await page.waitForTimeout(500);

    // Click view plan button
    await row.getByRole('button', { name: '查看计划' }).click();

    // Verify drawer opens
    await expect(page.locator('.el-drawer').filter({ hasText: '计划详情' })).toBeVisible({ timeout: 5000 });

    // Verify confirm button is visible for DRAFT plan
    await expect(page.locator('.el-drawer').getByRole('button', { name: '确认计划' })).toBeVisible();

    // Click confirm button
    await page.locator('.el-drawer').getByRole('button', { name: '确认计划' }).click();

    // Verify confirm dialog appears
    await expect(page.locator('.el-dialog').filter({ hasText: '确认计划' })).toBeVisible({ timeout: 5000 });

    // Click confirm in dialog
    await page.locator('.el-dialog').filter({ hasText: '确认计划' }).getByRole('button', { name: '确认' }).click();

    // Wait for success
    await expect(page.locator('.el-message--success')).toBeVisible({ timeout: 5000 });
  });

  test('DRAFT plan can be cancelled', async ({ page }) => {
    await setupMocks(page);

    // Override suggestions mock with existing plan
    await page.route('**/api/assets/governance-suggestions**', async route => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(pageResponse([
          { ...mockSuggestions[0], existingPlan: mockDraftPlan }
        ]))
      });
    });

    // Mock get plan detail
    await page.route('**/api/assets/governance-action-plans/101', async route => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(apiResponse(mockDraftPlan))
      });
    });

    // Mock cancel plan
    await page.route('**/api/assets/governance-action-plans/101/cancel', async route => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(apiResponse({ ...mockDraftPlan, status: 'CANCELLED', cancelReason: '测试取消' }))
      });
    });

    await page.goto('/resource/governance');
    await expect(page.getByRole('heading', { name: '资源治理工作台' })).toBeVisible({ timeout: 10000 });

    await page.getByRole('tab', { name: '治理建议' }).click();
    await expect(page.locator('.governance-suggestions-tab')).toBeVisible({ timeout: 10000 });

    const suggestionsTable = page.locator('.governance-suggestions-tab .el-table').first();
    await expect(suggestionsTable).toBeVisible({ timeout: 10000 });

    const row = suggestionsTable.locator('tr').filter({ hasText: 'GS-2024-001' });
    await expect(row).toBeVisible({ timeout: 10000 });

    await page.waitForTimeout(500);

    // Click view plan button
    await row.getByRole('button', { name: '查看计划' }).click();

    // Verify drawer opens
    await expect(page.locator('.el-drawer').filter({ hasText: '计划详情' })).toBeVisible({ timeout: 5000 });

    // Verify cancel button is visible for DRAFT plan
    await expect(page.locator('.el-drawer').getByRole('button', { name: '取消计划' })).toBeVisible();

    // Click cancel button
    await page.locator('.el-drawer').getByRole('button', { name: '取消计划' }).click();

    // Verify cancel dialog appears
    await expect(page.locator('.el-dialog').filter({ hasText: '取消计划' })).toBeVisible({ timeout: 5000 });

    // Enter cancel reason
    await page.locator('.el-dialog textarea').fill('测试取消原因');

    // Click confirm in dialog
    await page.locator('.el-dialog').filter({ hasText: '取消计划' }).getByRole('button', { name: '确认' }).click();

    // Wait for success
    await expect(page.locator('.el-message--success')).toBeVisible({ timeout: 5000 });
  });

  test('CONFIRMED plan can be cancelled', async ({ page }) => {
    await setupMocks(page);

    // Override suggestions mock with existing confirmed plan
    await page.route('**/api/assets/governance-suggestions**', async route => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(pageResponse([
          { ...mockSuggestions[1], existingPlan: mockConfirmedPlan }
        ]))
      });
    });

    // Mock get plan detail
    await page.route('**/api/assets/governance-action-plans/102', async route => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(apiResponse(mockConfirmedPlan))
      });
    });

    // Mock cancel plan
    await page.route('**/api/assets/governance-action-plans/102/cancel', async route => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(apiResponse({ ...mockConfirmedPlan, status: 'CANCELLED', cancelReason: '测试取消' }))
      });
    });

    await page.goto('/resource/governance');
    await expect(page.getByRole('heading', { name: '资源治理工作台' })).toBeVisible({ timeout: 10000 });

    await page.getByRole('tab', { name: '治理建议' }).click();
    await expect(page.locator('.governance-suggestions-tab')).toBeVisible({ timeout: 10000 });

    const suggestionsTable = page.locator('.governance-suggestions-tab .el-table').first();
    await expect(suggestionsTable).toBeVisible({ timeout: 10000 });

    const row = suggestionsTable.locator('tr').filter({ hasText: 'GS-2024-002' });
    await expect(row).toBeVisible({ timeout: 10000 });

    await page.waitForTimeout(500);

    // Click view plan button
    await row.getByRole('button', { name: '查看计划' }).click();

    // Verify drawer opens
    await expect(page.locator('.el-drawer').filter({ hasText: '计划详情' })).toBeVisible({ timeout: 5000 });

    // Verify confirm button is NOT visible for CONFIRMED plan
    await expect(page.locator('.el-drawer').getByRole('button', { name: '确认计划' })).not.toBeVisible();

    // Verify cancel button is visible for CONFIRMED plan
    await expect(page.locator('.el-drawer').getByRole('button', { name: '取消计划' })).toBeVisible();
  });

  test('CANCELLED plan shows no action buttons', async ({ page }) => {
    // Mock suggestions with existing cancelled plan - set up BEFORE navigation
    await page.route('**/api/envs**', async route => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(pageResponse(mockEnvs))
      });
    });

    await page.route('**/api/services**', async route => {
      if (route.request().url().includes('/page')) {
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify(pageResponse(mockServices))
        });
      } else {
        await route.continue();
      }
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
        body: JSON.stringify(pageResponse(mockHosts))
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

    // Override suggestions mock with cancelled plan
    await page.route('**/api/assets/governance-suggestions**', async route => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(pageResponse([
          { ...mockSuggestions[0], existingPlan: mockCancelledPlan }
        ]))
      });
    });

    // Mock get plan detail
    await page.route('**/api/assets/governance-action-plans/103', async route => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(apiResponse(mockCancelledPlan))
      });
    });

    await page.goto('/resource/governance');
    await expect(page.getByRole('heading', { name: '资源治理工作台' })).toBeVisible({ timeout: 10000 });

    await page.getByRole('tab', { name: '治理建议' }).click();
    await expect(page.locator('.governance-suggestions-tab')).toBeVisible({ timeout: 10000 });

    const suggestionsTable = page.locator('.governance-suggestions-tab .el-table').first();
    await expect(suggestionsTable).toBeVisible({ timeout: 10000 });

    const row = suggestionsTable.locator('tr').filter({ hasText: 'GS-2024-001' });
    await expect(row).toBeVisible({ timeout: 10000 });

    await page.waitForTimeout(500);

    // Click view plan button
    await row.getByRole('button', { name: '查看计划' }).click();

    // Verify drawer opens
    await expect(page.locator('.el-drawer').filter({ hasText: '计划详情' })).toBeVisible({ timeout: 5000 });

    // Verify no action buttons are visible for CANCELLED plan
    const planDrawer = page.locator('.el-drawer').filter({ hasText: '计划详情' });
    await expect(planDrawer.getByRole('button', { name: '确认计划' })).not.toBeVisible();
    await expect(planDrawer.getByRole('button', { name: '取消计划' })).not.toBeVisible();

    // Verify INVESTIGATE_CAPACITY_PLAN uses RESOURCE_POOL, not RUNTIME_INSTANCE
    await expect(planDrawer.getByText('容量调查计划')).toBeVisible({ timeout: 5000 });
    await expect(planDrawer).not.toContainText('RUNTIME_INSTANCE');
  });

  test('plan detail displays planPayload', async ({ page }) => {
    await setupMocks(page);

    // Override suggestions mock with existing plan
    await page.route('**/api/assets/governance-suggestions**', async route => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(pageResponse([
          { ...mockSuggestions[0], existingPlan: mockDraftPlan }
        ]))
      });
    });

    // Mock get plan detail
    await page.route('**/api/assets/governance-action-plans/101', async route => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(apiResponse(mockDraftPlan))
      });
    });

    await page.goto('/resource/governance');
    await expect(page.getByRole('heading', { name: '资源治理工作台' })).toBeVisible({ timeout: 10000 });

    await page.getByRole('tab', { name: '治理建议' }).click();
    await expect(page.locator('.governance-suggestions-tab')).toBeVisible({ timeout: 10000 });

    const suggestionsTable = page.locator('.governance-suggestions-tab .el-table').first();
    await expect(suggestionsTable).toBeVisible({ timeout: 10000 });

    const row = suggestionsTable.locator('tr').filter({ hasText: 'GS-2024-001' });
    await expect(row).toBeVisible({ timeout: 10000 });

    await page.waitForTimeout(500);

    // Click view plan button
    await row.getByRole('button', { name: '查看计划' }).click();

    // Verify drawer opens
    await expect(page.locator('.el-drawer').filter({ hasText: '计划详情' })).toBeVisible({ timeout: 5000 });

    // Verify plan payload section is visible
    await expect(page.locator('.el-drawer').getByText('计划内容')).toBeVisible();

    // Verify plan payload content is displayed
    await expect(page.locator('.plan-payload-content')).toBeVisible();
    await expect(page.locator('.plan-payload-content')).toContainText('targetCpuCores');
  });

  test('no execute plan button exists', async ({ page }) => {
    await setupMocks(page);

    // Override suggestions mock with existing plan
    await page.route('**/api/assets/governance-suggestions**', async route => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(pageResponse([
          { ...mockSuggestions[0], existingPlan: mockDraftPlan }
        ]))
      });
    });

    // Mock get plan detail
    await page.route('**/api/assets/governance-action-plans/101', async route => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(apiResponse(mockDraftPlan))
      });
    });

    await page.goto('/resource/governance');
    await expect(page.getByRole('heading', { name: '资源治理工作台' })).toBeVisible({ timeout: 10000 });

    await page.getByRole('tab', { name: '治理建议' }).click();
    await expect(page.locator('.governance-suggestions-tab')).toBeVisible({ timeout: 10000 });

    const suggestionsTable = page.locator('.governance-suggestions-tab .el-table').first();
    await expect(suggestionsTable).toBeVisible({ timeout: 10000 });

    const row = suggestionsTable.locator('tr').filter({ hasText: 'GS-2024-001' });
    await expect(row).toBeVisible({ timeout: 10000 });

    await page.waitForTimeout(500);

    // Click view plan button
    await row.getByRole('button', { name: '查看计划' }).click();

    // Verify drawer opens
    await expect(page.locator('.el-drawer').filter({ hasText: '计划详情' })).toBeVisible({ timeout: 5000 });

    // Verify no execute button exists anywhere in the drawer
    await expect(page.locator('.el-drawer').getByRole('button', { name: /执行计划|执行/ })).not.toBeVisible();
  });

  test('no auto execute or auto scale text exists', async ({ page }) => {
    await setupMocks(page);

    // Override suggestions mock with existing plan
    await page.route('**/api/assets/governance-suggestions**', async route => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(pageResponse([
          { ...mockSuggestions[0], existingPlan: mockDraftPlan }
        ]))
      });
    });

    // Mock get plan detail
    await page.route('**/api/assets/governance-action-plans/101', async route => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(apiResponse(mockDraftPlan))
      });
    });

    await page.goto('/resource/governance');
    await expect(page.getByRole('heading', { name: '资源治理工作台' })).toBeVisible({ timeout: 10000 });

    await page.getByRole('tab', { name: '治理建议' }).click();
    await expect(page.locator('.governance-suggestions-tab')).toBeVisible({ timeout: 10000 });

    const suggestionsTable = page.locator('.governance-suggestions-tab .el-table').first();
    await expect(suggestionsTable).toBeVisible({ timeout: 10000 });

    const row = suggestionsTable.locator('tr').filter({ hasText: 'GS-2024-001' });
    await expect(row).toBeVisible({ timeout: 10000 });

    await page.waitForTimeout(500);

    // Click view plan button
    await row.getByRole('button', { name: '查看计划' }).click();

    // Verify drawer opens
    await expect(page.locator('.el-drawer').filter({ hasText: '计划详情' })).toBeVisible({ timeout: 5000 });

    // Verify no auto execute or auto scale text
    const planDrawer = page.locator('.el-drawer').filter({ hasText: '计划详情' });
    await expect(planDrawer).not.toContainText('自动执行');
    await expect(planDrawer).not.toContainText('自动扩容');
  });

  test('cancel plan requires reason', async ({ page }) => {
    await setupMocks(page);

    // Override suggestions mock with existing plan
    await page.route('**/api/assets/governance-suggestions**', async route => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(pageResponse([
          { ...mockSuggestions[0], existingPlan: mockDraftPlan }
        ]))
      });
    });

    // Mock get plan detail
    await page.route('**/api/assets/governance-action-plans/101', async route => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(apiResponse(mockDraftPlan))
      });
    });

    await page.goto('/resource/governance');
    await expect(page.getByRole('heading', { name: '资源治理工作台' })).toBeVisible({ timeout: 10000 });

    await page.getByRole('tab', { name: '治理建议' }).click();
    await expect(page.locator('.governance-suggestions-tab')).toBeVisible({ timeout: 10000 });

    const suggestionsTable = page.locator('.governance-suggestions-tab .el-table').first();
    await expect(suggestionsTable).toBeVisible({ timeout: 10000 });

    const row = suggestionsTable.locator('tr').filter({ hasText: 'GS-2024-001' });
    await expect(row).toBeVisible({ timeout: 10000 });

    await page.waitForTimeout(500);

    // Click view plan button
    await row.getByRole('button', { name: '查看计划' }).click();

    // Verify drawer opens
    await expect(page.locator('.el-drawer').filter({ hasText: '计划详情' })).toBeVisible({ timeout: 5000 });

    // Click cancel button
    await page.locator('.el-drawer').getByRole('button', { name: '取消计划' }).click();

    // Verify cancel dialog appears
    await expect(page.locator('.el-dialog').filter({ hasText: '取消计划' })).toBeVisible({ timeout: 5000 });

    // Click confirm without entering reason
    await page.locator('.el-dialog').filter({ hasText: '取消计划' }).getByRole('button', { name: '确认' }).click();

    // Verify warning message appears
    await expect(page.locator('.el-message--warning')).toBeVisible({ timeout: 5000 });
  });

  test('confirm plan shows confirmation dialog', async ({ page }) => {
    await setupMocks(page);

    // Override suggestions mock with existing plan
    await page.route('**/api/assets/governance-suggestions**', async route => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(pageResponse([
          { ...mockSuggestions[0], existingPlan: mockDraftPlan }
        ]))
      });
    });

    // Mock get plan detail
    await page.route('**/api/assets/governance-action-plans/101', async route => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(apiResponse(mockDraftPlan))
      });
    });

    await page.goto('/resource/governance');
    await expect(page.getByRole('heading', { name: '资源治理工作台' })).toBeVisible({ timeout: 10000 });

    await page.getByRole('tab', { name: '治理建议' }).click();
    await expect(page.locator('.governance-suggestions-tab')).toBeVisible({ timeout: 10000 });

    const suggestionsTable = page.locator('.governance-suggestions-tab .el-table').first();
    await expect(suggestionsTable).toBeVisible({ timeout: 10000 });

    const row = suggestionsTable.locator('tr').filter({ hasText: 'GS-2024-001' });
    await expect(row).toBeVisible({ timeout: 10000 });

    await page.waitForTimeout(500);

    // Click view plan button
    await row.getByRole('button', { name: '查看计划' }).click();

    // Verify drawer opens
    await expect(page.locator('.el-drawer').filter({ hasText: '计划详情' })).toBeVisible({ timeout: 5000 });

    // Click confirm button
    await page.locator('.el-drawer').getByRole('button', { name: '确认计划' }).click();

    // Verify confirmation dialog appears with appropriate message
    await expect(page.locator('.el-dialog').filter({ hasText: '确认计划' })).toBeVisible({ timeout: 5000 });
    await expect(page.locator('.el-dialog').getByText(/确定要确认此计划/)).toBeVisible();
  });

  test('plan displays correct actionType labels from backend values', async ({ page }) => {
    await setupMocks(page);

    // Override suggestions mock with existing plans using backend values
    await page.route('**/api/assets/governance-suggestions**', async route => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(pageResponse([
          { ...mockSuggestions[0], existingPlan: mockDraftPlan }
        ]))
      });
    });

    // Mock get plan detail
    await page.route('**/api/assets/governance-action-plans/101', async route => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(apiResponse(mockDraftPlan))
      });
    });

    await page.goto('/resource/governance');
    await expect(page.getByRole('heading', { name: '资源治理工作台' })).toBeVisible({ timeout: 10000 });

    await page.getByRole('tab', { name: '治理建议' }).click();
    await expect(page.locator('.governance-suggestions-tab')).toBeVisible({ timeout: 10000 });

    const suggestionsTable = page.locator('.governance-suggestions-tab .el-table').first();
    await expect(suggestionsTable).toBeVisible({ timeout: 10000 });

    const row = suggestionsTable.locator('tr').filter({ hasText: 'GS-2024-001' });
    await expect(row).toBeVisible({ timeout: 10000 });

    await page.waitForTimeout(500);

    // Click view plan button
    await row.getByRole('button', { name: '查看计划' }).click();

    // Verify drawer opens
    await expect(page.locator('.el-drawer').filter({ hasText: '计划详情' })).toBeVisible({ timeout: 5000 });

    // Verify correct action type label is displayed for SCALE_OUT_PLAN
    await expect(page.locator('.el-drawer').getByText('扩容计划')).toBeVisible({ timeout: 5000 });

    // Verify old action type values don't appear anywhere in the drawer
    // These are the OLD values that should NOT be displayed:
    const planDrawer = page.locator('.el-drawer').filter({ hasText: '计划详情' });
    await expect(planDrawer).not.toContainText('SCALE_OUT');
    await expect(planDrawer).not.toContainText('HOST_CHECK');
    await expect(planDrawer).not.toContainText('SCALE_IN');
    await expect(planDrawer).not.toContainText('CAPACITY_INVESTIGATION');
  });

  test('plan displays CHECK_HOST_PLAN action type correctly', async ({ page }) => {
    await setupMocks(page);

    // Override suggestions mock with confirmed plan (CHECK_HOST_PLAN)
    await page.route('**/api/assets/governance-suggestions**', async route => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(pageResponse([
          { ...mockSuggestions[1], existingPlan: mockConfirmedPlan }
        ]))
      });
    });

    // Mock get plan detail
    await page.route('**/api/assets/governance-action-plans/102', async route => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(apiResponse(mockConfirmedPlan))
      });
    });

    await page.goto('/resource/governance');
    await expect(page.getByRole('heading', { name: '资源治理工作台' })).toBeVisible({ timeout: 10000 });

    await page.getByRole('tab', { name: '治理建议' }).click();
    await expect(page.locator('.governance-suggestions-tab')).toBeVisible({ timeout: 10000 });

    const suggestionsTable = page.locator('.governance-suggestions-tab .el-table').first();
    await expect(suggestionsTable).toBeVisible({ timeout: 10000 });

    const row = suggestionsTable.locator('tr').filter({ hasText: 'GS-2024-002' });
    await expect(row).toBeVisible({ timeout: 10000 });

    await page.waitForTimeout(500);

    // Click view plan button
    await row.getByRole('button', { name: '查看计划' }).click();

    // Verify drawer opens
    await expect(page.locator('.el-drawer').filter({ hasText: '计划详情' })).toBeVisible({ timeout: 5000 });

    // Verify correct action type label is displayed for CHECK_HOST_PLAN
    await expect(page.locator('.el-drawer').getByText('主机检查计划')).toBeVisible({ timeout: 5000 });
  });

  test('planPayload as JSON string is displayed correctly', async ({ page }) => {
    await setupMocks(page);

    // Override suggestions mock with existing plan (planPayload is JSON string)
    await page.route('**/api/assets/governance-suggestions**', async route => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(pageResponse([
          { ...mockSuggestions[0], existingPlan: mockDraftPlan }
        ]))
      });
    });

    // Mock get plan detail
    await page.route('**/api/assets/governance-action-plans/101', async route => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(apiResponse(mockDraftPlan))
      });
    });

    await page.goto('/resource/governance');
    await expect(page.getByRole('heading', { name: '资源治理工作台' })).toBeVisible({ timeout: 10000 });

    await page.getByRole('tab', { name: '治理建议' }).click();
    await expect(page.locator('.governance-suggestions-tab')).toBeVisible({ timeout: 10000 });

    const suggestionsTable = page.locator('.governance-suggestions-tab .el-table').first();
    await expect(suggestionsTable).toBeVisible({ timeout: 10000 });

    const row = suggestionsTable.locator('tr').filter({ hasText: 'GS-2024-001' });
    await expect(row).toBeVisible({ timeout: 10000 });

    await page.waitForTimeout(500);

    // Click view plan button
    await row.getByRole('button', { name: '查看计划' }).click();

    // Verify drawer opens
    await expect(page.locator('.el-drawer').filter({ hasText: '计划详情' })).toBeVisible({ timeout: 5000 });

    // Verify planPayload JSON string is parsed and displayed correctly
    await expect(page.locator('.plan-payload-content')).toBeVisible();
    await expect(page.locator('.plan-payload-content')).toContainText('targetCpuCores');
    await expect(page.locator('.plan-payload-content')).toContainText('32');
  });
});
