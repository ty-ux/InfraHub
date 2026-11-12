import { expect, test } from '@playwright/test';
import { loginAsAdmin } from './helpers/auth';

/**
 * E2E tests for V2/G Resource Governance Workbench
 * Covers: Pool Capacity, Host Health, Runtime Instances, Governance Records
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

/**
 * Helper to create governance ops mock data
 */
function createMockOverview() {
  return {
    totalRiskCount: 15,
    criticalRiskCount: 3,
    warningRiskCount: 5,
    openSuggestionCount: 8,
    confirmedPlanCount: 12,
    runningExecutionCount: 2,
    completedExecutionCount: 45,
    failedExecutionCount: 3,
    impactRecordedCount: 50,
    avgCloseDurationMs: 3600000,
    manualOnly: true
  };
}

function createMockSla() {
  return {
    avgSuggestionAckDurationMs: 7200000,
    avgPlanConfirmDurationMs: 3600000,
    avgExecutionCompleteDurationMs: 14400000,
    overdueSuggestionCount: 2,
    overduePlanCount: 1,
    overdueExecutionCount: 0,
    slaTargetMs: 86400000,
    slaHitRate: 0.92
  };
}

function createMockTrends() {
  return [
    { timeBucket: '2026-05-10', riskCount: 5, suggestionCount: 3, actionPlanCount: 2, executionCount: 4, impactCount: 4, successCount: 3, failedCount: 1 },
    { timeBucket: '2026-05-11', riskCount: 4, suggestionCount: 2, actionPlanCount: 1, executionCount: 3, impactCount: 3, successCount: 3, failedCount: 0 },
    { timeBucket: '2026-05-12', riskCount: 6, suggestionCount: 4, actionPlanCount: 3, executionCount: 5, impactCount: 5, successCount: 4, failedCount: 1 }
  ];
}

function createMockEffectiveness() {
  return [
    {
      impactType: 'RESOURCE_POOL',
      beforeRiskLevel: 'CRITICAL',
      afterRiskLevel: 'NORMAL',
      improvedCount: 42,
      unchangedCount: 5,
      worsenedCount: 3,
      improvementRate: 0.84,
      sampleSize: 50
    },
    {
      impactType: 'HOST',
      beforeRiskLevel: 'WARNING',
      afterRiskLevel: 'NORMAL',
      improvedCount: 15,
      unchangedCount: 8,
      worsenedCount: 2,
      improvementRate: 0.75,
      sampleSize: 25
    }
  ];
}

function createMockReviewItems() {
  return [
    {
      itemType: 'SUGGESTION',
      itemId: 1,
      itemNo: 'GS-2026-001',
      title: 'CPU使用率超过90%，建议扩容',
      riskLevel: 'CRITICAL',
      status: 'OPEN',
      reason: '系统自动检测',
      ownerName: 'admin',
      createdAt: new Date().toISOString(),
      dueAt: new Date(Date.now() + 86400000).toISOString()
    },
    {
      itemType: 'ACTION_PLAN',
      itemId: 2,
      itemNo: 'AP-2026-001',
      title: '主机检查计划',
      riskLevel: 'WARNING',
      status: 'CONFIRMED',
      reason: '主机健康检查',
      ownerName: 'operator',
      createdAt: new Date().toISOString()
    }
  ];
}

test.describe('resource governance workbench', () => {
  test.setTimeout(60000);

  test.beforeEach(async ({ page }) => {
    await loginAsAdmin(page);
  });

  /**
   * Mock data for consistent test results
   */
  const mockResourcePools = [
    {
      id: 1,
      poolCode: 'POOL-E2E-01',
      poolName: 'E2E Test Pool 01',
      poolType: 'KUBERNETES',
      region: 'cn-east',
      status: 'ACTIVE',
      totalCpu: 100,
      totalMemoryGb: 500,
      totalDiskGb: 2000,
      usedCpu: 45,
      usedMemoryGb: 200,
      usedDiskGb: 800,
      createdAt: '2024-01-01T00:00:00Z'
    },
    {
      id: 2,
      poolCode: 'POOL-E2E-02',
      poolName: 'E2E Test Pool 02',
      poolType: 'VM',
      region: 'cn-west',
      status: 'ACTIVE',
      totalCpu: 200,
      totalMemoryGb: 1000,
      totalDiskGb: 5000,
      usedCpu: 180,
      usedMemoryGb: 850,
      usedDiskGb: 4500,
      createdAt: '2024-01-01T00:00:00Z'
    }
  ];

  const mockPoolCapacity = {
    resourcePoolId: 1,
    resourcePoolName: 'E2E Test Pool 01',
    poolCode: 'POOL-E2E-01',
    envId: 1,
    envName: '测试环境',
    hostCount: 10,
    onlineHostCount: 7,
    offlineHostCount: 2,
    unknownHostCount: 1,
    runtimeInstanceCount: 25,
    cpuTotal: 100,
    cpuUsed: 45,
    cpuUsagePercent: 45,
    memoryTotalGb: 500,
    memoryUsedGb: 200,
    memoryUsagePercent: 40,
    diskTotalGb: 2000,
    diskUsedGb: 800,
    diskUsagePercent: 40
  };

  const mockHosts = [
    {
      id: 1,
      hostname: 'e2e-host-01',
      ip: '192.0.2.17',
      osType: 'Linux',
      cpuCores: 8,
      memoryGb: 32,
      diskGb: 500,
      hostType: 'VM',
      hostStatus: 'ACTIVE',
      envId: 1,
      resourcePoolId: 1,
      agentStatus: 'ONLINE',
      lastHeartbeat: new Date().toISOString(),
      createdAt: '2024-01-01T00:00:00Z',
      updatedAt: new Date().toISOString()
    },
    {
      id: 2,
      hostname: 'e2e-host-02',
      ip: '192.0.2.18',
      osType: 'Linux',
      cpuCores: 16,
      memoryGb: 64,
      diskGb: 1000,
      hostType: 'VM',
      hostStatus: 'ACTIVE',
      envId: 1,
      resourcePoolId: 1,
      agentStatus: 'OFFLINE',
      lastHeartbeat: '2024-01-01T00:00:00Z',
      createdAt: '2024-01-01T00:00:00Z',
      updatedAt: '2024-01-01T00:00:00Z'
    },
    {
      id: 3,
      hostname: 'e2e-host-03',
      ip: '192.0.2.19',
      osType: 'Linux',
      cpuCores: 8,
      memoryGb: 32,
      diskGb: 500,
      hostType: 'VM',
      hostStatus: 'ACTIVE',
      envId: 1,
      resourcePoolId: 1,
      agentStatus: 'ONLINE',
      lastHeartbeat: new Date().toISOString(),
      createdAt: '2024-01-01T00:00:00Z',
      updatedAt: new Date().toISOString()
    }
  ];

  const mockHostProfiles: Record<number, object> = {
    1: {
      hostId: 1,
      hostname: 'e2e-host-01',
      ip: '192.0.2.17',
      envId: 1,
      envName: '测试环境',
      resourcePoolId: 1,
      resourcePoolName: 'E2E Test Pool 01',
      runtimeInstanceCount: 5,
      latestDeployAt: new Date().toISOString(),
      latestDeployStatus: 'SUCCESS',
      healthStatus: 'ONLINE',
      healthReason: 'Agent heartbeat normal'
    },
    2: {
      hostId: 2,
      hostname: 'e2e-host-02',
      ip: '192.0.2.18',
      envId: 1,
      envName: '测试环境',
      resourcePoolId: 1,
      resourcePoolName: 'E2E Test Pool 01',
      runtimeInstanceCount: 3,
      latestDeployAt: '2024-01-01T00:00:00Z',
      latestDeployStatus: 'FAILED',
      healthStatus: 'OFFLINE',
      healthReason: 'Agent heartbeat timeout'
    },
    3: {
      hostId: 3,
      hostname: 'e2e-host-03',
      ip: '192.0.2.19',
      envId: 1,
      envName: '测试环境',
      resourcePoolId: 1,
      resourcePoolName: 'E2E Test Pool 01',
      runtimeInstanceCount: 8,
      latestDeployAt: new Date().toISOString(),
      latestDeployStatus: 'SUCCESS',
      healthStatus: 'DEGRADED',
      healthReason: 'High memory usage detected'
    }
  };

  const mockRuntimeInstances = [
    {
      id: 1,
      serviceId: 1,
      envId: 1,
      hostId: 1,
      versionId: 10,
      instanceName: 'e2e-instance-01',
      instanceType: 'SERVICE',
      instanceStatus: 'RUNNING',
      healthStatus: 'UP',
      startTime: new Date().toISOString(),
      lastHeartbeat: new Date().toISOString(),
      createdAt: '2024-01-01T00:00:00Z',
      updatedAt: new Date().toISOString()
    },
    {
      id: 2,
      serviceId: 2,
      envId: 1,
      hostId: 1,
      versionId: 20,
      instanceName: 'e2e-instance-02',
      instanceType: 'SERVICE',
      instanceStatus: 'RUNNING',
      healthStatus: 'UP',
      startTime: new Date().toISOString(),
      lastHeartbeat: new Date().toISOString(),
      createdAt: '2024-01-01T00:00:00Z',
      updatedAt: new Date().toISOString()
    },
    {
      id: 3,
      serviceId: 1,
      envId: 1,
      hostId: 2,
      versionId: 11,
      instanceName: 'e2e-instance-03',
      instanceType: 'SERVICE',
      instanceStatus: 'STOPPED',
      healthStatus: 'DOWN',
      startTime: '2024-01-01T00:00:00Z',
      lastHeartbeat: '2024-01-01T00:00:00Z',
      createdAt: '2024-01-01T00:00:00Z',
      updatedAt: '2024-01-01T00:00:00Z'
    }
  ];

  const mockInstanceResourceViews: Record<number, object> = {
    1: {
      instanceId: 1,
      instanceName: 'e2e-instance-01',
      instanceStatus: 'RUNNING',
      healthStatus: 'UP',
      serviceId: 1,
      serviceName: 'E2E Test Service A',
      envId: 1,
      envName: '测试环境',
      hostId: 1,
      hostname: 'e2e-host-01',
      ip: '192.0.2.17',
      resourcePoolId: 1,
      resourcePoolName: 'E2E Test Pool 01',
      currentVersionId: 10,
      currentVersionNo: 'v1.0.0-e2e',
      latestDeployRecordId: 100,
      latestDeployStatus: 'SUCCESS',
      latestDeployAt: new Date().toISOString(),
      monitorStatus: 'UP'
    },
    2: {
      instanceId: 2,
      instanceName: 'e2e-instance-02',
      instanceStatus: 'RUNNING',
      healthStatus: 'UP',
      serviceId: 2,
      serviceName: 'E2E Test Service B',
      envId: 1,
      envName: '测试环境',
      hostId: 1,
      hostname: 'e2e-host-01',
      ip: '192.0.2.17',
      resourcePoolId: 1,
      resourcePoolName: 'E2E Test Pool 01',
      currentVersionId: 20,
      currentVersionNo: 'v2.0.0-e2e',
      latestDeployRecordId: 200,
      latestDeployStatus: 'SUCCESS',
      latestDeployAt: new Date().toISOString(),
      monitorStatus: 'UP'
    },
    3: {
      instanceId: 3,
      instanceName: 'e2e-instance-03',
      instanceStatus: 'STOPPED',
      healthStatus: 'DOWN',
      serviceId: 1,
      serviceName: 'E2E Test Service A',
      envId: 1,
      envName: '测试环境',
      hostId: 2,
      hostname: 'e2e-host-02',
      ip: '192.0.2.18',
      resourcePoolId: 1,
      resourcePoolName: 'E2E Test Pool 01',
      currentVersionId: 11,
      currentVersionNo: 'v1.1.0-e2e',
      latestDeployRecordId: 150,
      latestDeployStatus: 'FAILED',
      latestDeployAt: '2024-01-01T00:00:00Z',
      monitorStatus: 'DOWN'
    }
  };

  const mockGovernanceRecords = [
    {
      id: 1,
      eventType: 'RELEASE_RESOURCE_ALLOCATED',
      resourcePoolId: 1,
      resourcePoolName: 'E2E Test Pool 01',
      hostId: 1,
      hostname: 'e2e-host-01',
      runtimeInstanceId: 1,
      instanceName: 'e2e-instance-01',
      releaseOrderId: 1000,
      executorType: 'SSH_EXECUTOR',
      envId: 1,
      envName: '测试环境',
      serviceId: 1,
      serviceName: 'E2E Test Service A',
      versionId: 10,
      resultStatus: 'SUCCESS',
      reason: 'Instance started successfully',
      createdBy: 'admin',
      createdAt: new Date().toISOString()
    },
    {
      id: 2,
      eventType: 'RELEASE_RESOURCE_DEALLOCATED',
      resourcePoolId: 1,
      resourcePoolName: 'E2E Test Pool 01',
      hostId: 2,
      hostname: 'e2e-host-02',
      runtimeInstanceId: 3,
      instanceName: 'e2e-instance-03',
      rollbackOrderId: 500,
      executorType: 'SSH_EXECUTOR',
      envId: 1,
      envName: '测试环境',
      serviceId: 1,
      serviceName: 'E2E Test Service A',
      resultStatus: 'SUCCESS',
      reason: 'Instance stopped for rollback',
      createdBy: 'admin',
      createdAt: new Date().toISOString()
    },
    {
      id: 3,
      eventType: 'HOST_HEALTH_DEGRADED',
      resourcePoolId: 1,
      resourcePoolName: 'E2E Test Pool 01',
      hostId: 3,
      hostname: 'e2e-host-03',
      executorType: 'MONITOR',
      envId: 1,
      envName: '测试环境',
      resultStatus: 'SUCCESS',
      reason: 'High memory usage alert triggered',
      createdBy: 'system',
      createdAt: new Date().toISOString()
    }
  ];

  const mockEnvs = [
    { id: 1, envCode: 'test', envName: '测试环境' },
    { id: 2, envCode: 'prod', envName: '生产环境' }
  ];

  const mockServices = [
    { id: 1, serviceCode: 'svc-a', serviceName: 'E2E Test Service A' },
    { id: 2, serviceCode: 'svc-b', serviceName: 'E2E Test Service B' }
  ];

  const mockSuggestions = [
    {
      id: 1,
      suggestionNo: 'GS-2026-001',
      suggestionType: 'SCALE_OUT',
      sourceType: 'RESOURCE_POOL_RISK',
      sourceId: 100,
      resourcePoolId: 1,
      resourcePoolName: 'E2E Test Pool 01',
      envId: 1,
      envName: '测试环境',
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
      suggestionNo: 'GS-2026-002',
      suggestionType: 'CHECK_HOST',
      sourceType: 'RESOURCE_POOL_RISK',
      sourceId: 200,
      resourcePoolId: 1,
      resourcePoolName: 'E2E Test Pool 01',
      envId: 1,
      envName: '测试环境',
      riskLevel: 'WARNING',
      title: '主机 host-02 离线超过24小时',
      description: '主机已离线超过24小时',
      status: 'ACKED',
      reason: '主机心跳超时',
      createdBy: 'system',
      createdAt: new Date().toISOString()
    },
    {
      id: 3,
      suggestionNo: 'GS-2026-003',
      suggestionType: 'INVESTIGATE_CAPACITY',
      sourceType: 'RESOURCE_POOL_RISK',
      sourceId: 300,
      resourcePoolId: 2,
      resourcePoolName: 'Normal Pool',
      envId: 2,
      envName: '测试环境',
      riskLevel: 'NORMAL',
      title: '容量调查建议',
      description: '建议进行容量调查',
      status: 'RESOLVED',
      reason: '已解决',
      createdBy: 'system',
      createdAt: new Date().toISOString()
    }
  ];

  /**
   * Setup all route mocks for resource governance page
   */
  async function setupMocks(page: import('@playwright/test').Page) {
    // Mock envs lookup - wrapped in ApiResponse
    await page.route('**/api/envs**', async route => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(pageResponse(mockEnvs))
      });
    });

    // Mock services lookup - wrapped in ApiResponse
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

    // Mock resource pools list - wrapped in ApiResponse
    await page.route('**/api/assets/resource-pools**', async route => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(pageResponse(mockResourcePools))
      });
    });

    // Mock resource pool capacity - wrapped in ApiResponse
    await page.route('**/api/assets/resource-pools/*/capacity', async route => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(apiResponse(mockPoolCapacity))
      });
    });

    // Mock hosts list - wrapped in ApiResponse
    await page.route('**/api/assets/hosts**', async route => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(pageResponse(mockHosts))
      });
    });

    // Mock host profile - wrapped in ApiResponse
    await page.route(/\/api\/assets\/hosts\/(\d+)\/profile/, async route => {
      const match = route.request().url().match(/\/hosts\/(\d+)\/profile/);
      const hostId = match ? parseInt(match[1], 10) : 1;
      const profile = mockHostProfiles[hostId] || mockHostProfiles[1];
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(apiResponse(profile))
      });
    });

    // Mock runtime instances page - wrapped in ApiResponse
    await page.route('**/api/runtime/service-instances/page**', async route => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(pageResponse(mockRuntimeInstances))
      });
    });

    // Mock instance resource view - wrapped in ApiResponse
    await page.route(/\/api\/runtime\/service-instances\/(\d+)\/resource-view/, async route => {
      const match = route.request().url().match(/\/service-instances\/(\d+)\/resource-view/);
      const instanceId = match ? parseInt(match[1], 10) : 1;
      const view = mockInstanceResourceViews[instanceId] || mockInstanceResourceViews[1];
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(apiResponse(view))
      });
    });

    // Mock governance records - wrapped in ApiResponse
    await page.route('**/api/assets/resource-governance/records**', async route => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(pageResponse(mockGovernanceRecords))
      });
    });

    // Mock governance suggestions - wrapped in ApiResponse
    await page.route('**/api/assets/governance-suggestions**', async route => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(pageResponse([]))
      });
    });

    // Mock capacity risks - wrapped in ApiResponse
    await page.route('**/api/assets/resource-pools/risks**', async route => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(pageResponse([]))
      });
    });
  }

  test('resource governance workbench page opens and shows core blocks', async ({ page }) => {
    await setupMocks(page);

    // Navigate to resource governance page
    await page.goto('/resource/governance');

    // Verify page title
    await expect(page.getByRole('heading', { name: '资源治理工作台' })).toBeVisible({ timeout: 10000 });

    // Verify filter bar is visible
    await expect(page.locator('.filter-bar')).toBeVisible();

    // Verify overview metrics card is visible
    await expect(page.locator('.metrics-card')).toBeVisible();

    // Verify tabs are visible
    await expect(page.getByRole('tab', { name: '资源池容量' })).toBeVisible();
    await expect(page.getByRole('tab', { name: '主机健康' })).toBeVisible();
    await expect(page.getByRole('tab', { name: '运行实例' })).toBeVisible();
    await expect(page.getByRole('tab', { name: '治理记录' })).toBeVisible();
  });

  test('pool capacity tab shows online/offline/unknown counts', async ({ page }) => {
    await setupMocks(page);

    await page.goto('/resource/governance');
    await expect(page.getByRole('heading', { name: '资源治理工作台' })).toBeVisible({ timeout: 10000 });

    // Pool capacity tab should be active by default
    await expect(page.getByRole('tab', { name: '资源池容量' })).toHaveAttribute('aria-selected', 'true');

    // Wait for table to load
    await expect(page.locator('.pool-capacity-tab .el-table').first()).toBeVisible({ timeout: 10000 });

    // Verify column headers for pool capacity (using correct i18n keys)
    await expect(page.locator('.pool-capacity-tab .el-table__header').getByText('在线主机')).toBeVisible();
    await expect(page.locator('.pool-capacity-tab .el-table__header').getByText('离线主机')).toBeVisible();
    await expect(page.locator('.pool-capacity-tab .el-table__header').getByText('未知状态')).toBeVisible();

    // Verify metrics are displayed in table cells (success/danger/warning tags)
    const successTag = page.locator('.pool-capacity-tab .el-table .el-tag--success').first();
    const dangerTag = page.locator('.pool-capacity-tab .el-table .el-tag--danger').first();
    const warningTag = page.locator('.pool-capacity-tab .el-table .el-tag--warning').first();

    await expect(successTag).toBeVisible({ timeout: 5000 });
    await expect(dangerTag).toBeVisible({ timeout: 5000 });
    await expect(warningTag).toBeVisible({ timeout: 5000 });

    // Verify resource usage progress bars
    await expect(page.locator('.pool-capacity-tab .el-progress').first()).toBeVisible();
  });

  test('host health tab shows ONLINE/DEGRADED/OFFLINE status', async ({ page }) => {
    await setupMocks(page);

    await page.goto('/resource/governance');
    await expect(page.getByRole('heading', { name: '资源治理工作台' })).toBeVisible({ timeout: 10000 });

    // Click on host health tab
    await page.getByRole('tab', { name: '主机健康' }).click();

    // Wait for tab panel content
    await expect(page.locator('.host-health-tab')).toBeVisible({ timeout: 10000 });

    // Wait for table to load within the tab
    const hostHealthTable = page.locator('.host-health-tab .el-table').first();
    await expect(hostHealthTable).toBeVisible({ timeout: 10000 });

    // Verify column headers
    await expect(hostHealthTable.locator('.el-table__header').getByText('健康状态')).toBeVisible();
    await expect(hostHealthTable.locator('.el-table__header').getByText('健康原因')).toBeVisible();

    // Verify health status tags are displayed
    // ONLINE status (success)
    await expect(hostHealthTable.locator('.el-tag--success').first()).toBeVisible({ timeout: 5000 });

    // OFFLINE status (danger)
    await expect(hostHealthTable.locator('.el-tag--danger').first()).toBeVisible({ timeout: 5000 });

    // DEGRADED status (warning)
    await expect(hostHealthTable.locator('.el-tag--warning').first()).toBeVisible({ timeout: 5000 });

    // Verify host actions column
    await expect(hostHealthTable.locator('.el-table__header').getByText('操作')).toBeVisible();
  });

  test('runtime instances tab shows resource view with pool, version, deploy status, monitor status', async ({ page }) => {
    await setupMocks(page);

    await page.goto('/resource/governance');
    await expect(page.getByRole('heading', { name: '资源治理工作台' })).toBeVisible({ timeout: 10000 });

    // Click on runtime instances tab
    await page.getByRole('tab', { name: '运行实例' }).click();

    // Wait for tab panel content
    await expect(page.locator('.runtime-instances-tab')).toBeVisible({ timeout: 10000 });

    // Wait for table to load within the tab
    const runtimeTable = page.locator('.runtime-instances-tab .el-table').first();
    await expect(runtimeTable).toBeVisible({ timeout: 10000 });

    // Verify column headers for resource view
    await expect(runtimeTable.locator('.el-table__header').getByText('实例名称')).toBeVisible();
    await expect(runtimeTable.locator('.el-table__header').getByText('资源池')).toBeVisible();
    await expect(runtimeTable.locator('.el-table__header').getByText('当前版本')).toBeVisible();
    await expect(runtimeTable.locator('.el-table__header').getByText('部署状态')).toBeVisible();
    await expect(runtimeTable.locator('.el-table__header').getByText('监控状态')).toBeVisible();

    // Verify resource pool name is displayed (use first() to avoid strict mode)
    await expect(runtimeTable.getByText('E2E Test Pool 01').first()).toBeVisible({ timeout: 5000 });

    // Verify version number is displayed
    await expect(runtimeTable.getByText('v1.0.0-e2e').first()).toBeVisible({ timeout: 5000 });

    // Verify deploy status tags
    await expect(runtimeTable.locator('.el-tag').getByText('SUCCESS').first()).toBeVisible({ timeout: 5000 });

    // Verify monitor status tags
    await expect(runtimeTable.locator('.el-tag').getByText('UP').first()).toBeVisible({ timeout: 5000 });

    // Verify pagination
    await expect(page.locator('.runtime-instances-tab .el-pagination')).toBeVisible();
  });

  test('governance records tab shows RELEASE_RESOURCE_ALLOCATED event', async ({ page }) => {
    await setupMocks(page);

    await page.goto('/resource/governance');
    await expect(page.getByRole('heading', { name: '资源治理工作台' })).toBeVisible({ timeout: 10000 });

    // Click on governance records tab
    await page.getByRole('tab', { name: '治理记录' }).click();

    // Wait for tab panel content
    await expect(page.locator('.governance-records-tab')).toBeVisible({ timeout: 10000 });

    // Wait for table to load within the tab
    const recordsTable = page.locator('.governance-records-tab .el-table').first();
    await expect(recordsTable).toBeVisible({ timeout: 10000 });

    // Verify column headers (using correct i18n keys)
    await expect(recordsTable.locator('.el-table__header').getByText('事件类型')).toBeVisible();
    await expect(recordsTable.locator('.el-table__header').getByText('资源池')).toBeVisible();
    await expect(recordsTable.locator('.el-table__header').getByText('主机')).toBeVisible();
    await expect(recordsTable.locator('.el-table__header').getByText('实例')).toBeVisible();
    await expect(recordsTable.locator('.el-table__header').getByText('结果')).toBeVisible();

    // Verify event type is displayed (formatted from RELEASE_RESOURCE_ALLOCATED)
    await expect(recordsTable.getByText(/Release Resource Allocated/i)).toBeVisible({ timeout: 5000 });

    // Verify SUCCESS result status tag
    await expect(recordsTable.locator('.el-tag--success').getByText('成功').first()).toBeVisible({ timeout: 5000 });

    // Verify pagination
    await expect(page.locator('.governance-records-tab .el-pagination')).toBeVisible();
  });

  test('metrics overview shows pool and host counts', async ({ page }) => {
    await setupMocks(page);

    await page.goto('/resource/governance');
    await expect(page.getByRole('heading', { name: '资源治理工作台' })).toBeVisible({ timeout: 10000 });

    // Wait for metrics to load
    await expect(page.locator('.metrics-card')).toBeVisible({ timeout: 10000 });

    // Verify metric items are displayed
    const metricsGrid = page.locator('.metrics-grid');
    await expect(metricsGrid).toBeVisible();

    // Check for metric labels (using correct i18n keys from resourceGovernance.metrics)
    await expect(metricsGrid.getByText('资源池数量')).toBeVisible();
    await expect(metricsGrid.getByText('主机总数')).toBeVisible();
    await expect(metricsGrid.getByText('在线主机')).toBeVisible();
    await expect(metricsGrid.getByText('离线主机')).toBeVisible();
    await expect(metricsGrid.getByText('未知状态')).toBeVisible();
    await expect(metricsGrid.getByText('运行实例数')).toBeVisible();
  });

  test('filter bar has all required dropdowns', async ({ page }) => {
    await setupMocks(page);

    await page.goto('/resource/governance');
    await expect(page.getByRole('heading', { name: '资源治理工作台' })).toBeVisible({ timeout: 10000 });

    // Verify filter bar
    const filterBar = page.locator('.filter-bar');
    await expect(filterBar).toBeVisible();

    // Verify select dropdowns exist (env, pool, host, service, status = 5)
    const selects = filterBar.locator('.el-select');
    await expect(selects).toHaveCount(5, { timeout: 5000 });

    // Verify refresh button
    await expect(filterBar.getByRole('button', { name: '刷新' })).toBeVisible();
  });

  test('refresh button reloads current tab', async ({ page }) => {
    await setupMocks(page);

    await page.goto('/resource/governance');
    await expect(page.getByRole('heading', { name: '资源治理工作台' })).toBeVisible({ timeout: 10000 });

    // Wait for initial data load
    await expect(page.locator('.pool-capacity-tab .el-table').first()).toBeVisible({ timeout: 10000 });

    // Click refresh button
    await page.getByRole('button', { name: '刷新' }).click();

    // Table should still be visible after refresh
    await expect(page.locator('.pool-capacity-tab .el-table').first()).toBeVisible({ timeout: 5000 });
  });

  test('governance records tab shows action execution evidence chain fields', async ({ page }) => {
    // Mock governance records with action execution record
    const actionExecutionRecord = {
      id: 100,
      eventType: 'RESOURCE_GOVERNANCE_ACTION_EXECUTION_COMPLETED',
      resourcePoolId: 1,
      resourcePoolName: 'E2E Test Pool 01',
      envId: 1,
      envName: '测试环境',
      subjectType: 'GOVERNANCE_ACTION_EXECUTION',
      subjectId: 1001,
      actionPlanNo: 'AP-2026-001',
      actionExecutionNo: 'AE-2026-001',
      suggestionNo: 'GS-2026-001',
      afterPayload: JSON.stringify({
        status: 'SUCCESS',
        evidencePayload: { text: 'manual execution evidence' }
      }),
      resultStatus: 'SUCCESS',
      reason: 'Action execution completed successfully',
      createdBy: 'admin',
      createdAt: new Date().toISOString()
    };

    const normalRecord = {
      id: 101,
      eventType: 'HOST_HEALTH_DEGRADED',
      resourcePoolId: 1,
      resourcePoolName: 'E2E Test Pool 01',
      hostId: 3,
      hostname: 'e2e-host-03',
      executorType: 'MONITOR',
      envId: 1,
      envName: '测试环境',
      resultStatus: 'SUCCESS',
      reason: 'High memory usage alert triggered',
      createdBy: 'system',
      createdAt: new Date().toISOString()
    };

    await setupMocks(page);

    // Override governance records mock with action execution records
    await page.route('**/api/assets/resource-governance/records**', async route => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(pageResponse([actionExecutionRecord, normalRecord]))
      });
    });

    await page.goto('/resource/governance');
    await expect(page.getByRole('heading', { name: '资源治理工作台' })).toBeVisible({ timeout: 10000 });

    // Click on governance records tab
    await page.getByRole('tab', { name: '治理记录' }).click();

    // Wait for tab panel content
    await expect(page.locator('.governance-records-tab')).toBeVisible({ timeout: 10000 });

    const recordsTable = page.locator('.governance-records-tab .el-table').first();
    await expect(recordsTable).toBeVisible({ timeout: 10000 });

    // Verify action execution record shows actionPlanNo (AP-2026-001)
    const actionRow = recordsTable.locator('tr').filter({ hasText: 'AP-2026-001' });
    await expect(actionRow).toBeVisible({ timeout: 5000 });

    // Verify actionExecutionNo (AE-2026-001) is visible
    await expect(recordsTable.getByText('AE-2026-001')).toBeVisible({ timeout: 5000 });

    // Verify suggestionNo (GS-2026-001) is visible
    await expect(recordsTable.getByText('GS-2026-001')).toBeVisible({ timeout: 5000 });

    // Click view evidence button to open drawer
    await actionRow.getByRole('button', { name: '查看证据' }).click();

    // Verify drawer opens and shows evidence payload
    const drawer = page.locator('.el-drawer').filter({ hasText: /证据载荷|Evidence Payload/ });
    await expect(drawer).toBeVisible({ timeout: 5000 });

    // Verify evidencePayload content is visible in drawer
    await expect(drawer.getByText('manual execution evidence')).toBeVisible({ timeout: 5000 });

    // Close drawer using the close icon
    await drawer.locator('.el-drawer__close-btn').click();
    await expect(drawer).not.toBeVisible({ timeout: 3000 });

    // Verify normal record (without action execution fields) shows '-' for empty fields
    // Use formatted event type name 'Host Health Degraded' instead of raw 'HOST_HEALTH_DEGRADED'
    const normalRow = recordsTable.locator('tr').filter({ hasText: /Host Health Degraded/i });
    await expect(normalRow).toBeVisible({ timeout: 5000 });

    // Verify no page errors occur (use first() to avoid strict mode)
    await expect(page.locator('.el-message--error').first()).not.toBeVisible();
  });

  test('timeline entry button visible for CONFIRMED plan', async ({ page }) => {
    await setupMocks(page);

    const mockConfirmedPlan = {
      id: 102,
      planNo: 'AP-2026-002',
      suggestionId: 2,
      suggestionNo: 'GS-2026-002',
      actionType: 'CHECK_HOST_PLAN',
      targetType: 'HOST',
      status: 'CONFIRMED',
      riskLevel: 'WARNING',
      resourcePoolId: 1,
      resourcePoolName: 'E2E Test Pool 01',
      envId: 1,
      envName: '测试环境',
      planPayload: JSON.stringify({ hostId: 200, hostname: 'host-02', checkType: 'CONNECTIVITY' }),
      createdBy: 'admin',
      createdAt: new Date().toISOString(),
      confirmedBy: 'admin',
      confirmedAt: new Date().toISOString()
    };

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

    const row = suggestionsTable.locator('tr').filter({ hasText: 'GS-2026-002' });
    await expect(row).toBeVisible({ timeout: 5000 });
    await page.waitForTimeout(500);

    await row.getByRole('button', { name: '查看计划' }).click();

    await expect(page.locator('.el-drawer').filter({ hasText: '计划详情' })).toBeVisible({ timeout: 5000 });

    // Verify timeline entry button is visible for CONFIRMED plan
    await expect(page.locator('.timeline-entry-section').getByRole('button', { name: '查看时间线' })).toBeVisible({ timeout: 5000 });
  });

  test('timeline shows association chain fields', async ({ page }) => {
    await setupMocks(page);

    const mockConfirmedPlan = {
      id: 102,
      planNo: 'AP-2026-002',
      suggestionId: 2,
      suggestionNo: 'GS-2026-002',
      actionType: 'CHECK_HOST_PLAN',
      targetType: 'HOST',
      status: 'CONFIRMED',
      riskLevel: 'WARNING',
      resourcePoolId: 1,
      resourcePoolName: 'E2E Test Pool 01',
      envId: 1,
      envName: '测试环境',
      planPayload: JSON.stringify({ hostId: 200, hostname: 'host-02', checkType: 'CONNECTIVITY' }),
      createdBy: 'admin',
      createdAt: new Date().toISOString(),
      confirmedBy: 'admin',
      confirmedAt: new Date().toISOString()
    };

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

    // Mock timeline API
    await page.route('**/api/assets/governance-action-plans/102/timeline', async route => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(apiResponse({
          subjectType: 'ACTION_PLAN',
          subjectId: 102,
          subjectNo: 'AP-2026-002',
          nodes: [
            { key: 'node-1', title: '计划创建', status: 'success', time: new Date().toISOString(), summary: '计划已创建' },
            { key: 'node-2', title: '计划确认', status: 'success', time: new Date().toISOString(), summary: '计划已确认' }
          ],
          totalCount: 2
        }))
      });
    });

    await page.goto('/resource/governance');
    await expect(page.getByRole('heading', { name: '资源治理工作台' })).toBeVisible({ timeout: 10000 });

    await page.getByRole('tab', { name: '治理建议' }).click();
    await expect(page.locator('.governance-suggestions-tab')).toBeVisible({ timeout: 10000 });

    const suggestionsTable = page.locator('.governance-suggestions-tab .el-table').first();
    await expect(suggestionsTable).toBeVisible({ timeout: 10000 });

    const row = suggestionsTable.locator('tr').filter({ hasText: 'GS-2026-002' });
    await expect(row).toBeVisible({ timeout: 5000 });
    await page.waitForTimeout(500);

    await row.getByRole('button', { name: '查看计划' }).click();

    await expect(page.locator('.el-drawer').filter({ hasText: '计划详情' })).toBeVisible({ timeout: 5000 });

    // Click timeline button
    await page.locator('.timeline-entry-section').getByRole('button', { name: '查看时间线' }).click();

    // Verify timeline drawer opens
    const timelineDrawer = page.locator('.el-drawer').filter({ hasText: '执行时间线' });
    await expect(timelineDrawer).toBeVisible({ timeout: 5000 });

    // Verify actionPlanNo is visible
    await expect(timelineDrawer.getByText('AP-2026-002')).toBeVisible({ timeout: 5000 });

    // Close drawer using aria-label
    await timelineDrawer.getByRole('button', { name: 'Close this dialog' }).first().click();
    await expect(timelineDrawer).not.toBeVisible({ timeout: 3000 });
  });

  test('timeline shows impact records with payload detail drawers', async ({ page }) => {
    await setupMocks(page);

    const mockExecutedPlan = {
      id: 103,
      planNo: 'AP-2026-003',
      suggestionId: 3,
      suggestionNo: 'GS-2026-003',
      actionType: 'SCALE_OUT_PLAN',
      targetType: 'RESOURCE_POOL',
      status: 'EXECUTED',
      riskLevel: 'NORMAL',
      resourcePoolId: 2,
      resourcePoolName: 'Normal Pool',
      envId: 2,
      envName: '测试环境',
      planPayload: JSON.stringify({ investigateType: 'CAPACITY' }),
      createdBy: 'admin',
      createdAt: new Date().toISOString(),
      confirmedBy: 'admin',
      confirmedAt: new Date().toISOString()
    };

    await page.route('**/api/assets/governance-suggestions**', async route => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(pageResponse([
          { ...mockSuggestions[2], existingPlan: mockExecutedPlan }
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
        body: JSON.stringify(pageResponse([{
          id: 201,
          executionNo: 'AE-2026-001',
          planId: 103,
          planNo: 'AP-2026-003',
          status: 'SUCCESS',
          executionMode: 'MANUAL_ONLY',
          operator: 'admin',
          startedAt: new Date().toISOString(),
          finishedAt: new Date().toISOString(),
          resultSummary: '扩容完成',
          evidencePayload: JSON.stringify({ reportUrl: 'http://example.com/report' }),
          createdBy: 'admin',
          createdAt: new Date().toISOString()
        }]))
      });
    });

    // Mock timeline API with impact record (per Contract 3.1)
    await page.route('**/api/assets/governance-action-plans/103/timeline', async route => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(apiResponse({
          subjectType: 'ACTION_PLAN',
          subjectId: 103,
          subjectNo: 'AP-2026-003',
          nodes: [
            { key: 'node-1', title: '执行开始', status: 'success', time: new Date().toISOString(), summary: '执行已开始', actionType: 'RESOURCE_GOVERNANCE_ACTION_EXECUTION_STARTED' },
            { key: 'node-2', title: '执行完成', status: 'success', time: new Date().toISOString(), summary: '扩容完成', actionType: 'RESOURCE_GOVERNANCE_ACTION_EXECUTION_COMPLETED' },
            {
              key: 'node-3',
              title: '影响已记录',
              status: 'success',
              time: new Date().toISOString(),
              summary: '成功扩容到 8 个实例',
              actionType: 'RESOURCE_GOVERNANCE_ACTION_IMPACT_RECORDED',
              impactType: 'RESOURCE_POOL',
              impactSummary: '成功扩容到 8 个实例',
              resultStatus: 'SUCCESS',
              beforePayload: { instanceCount: 4, cpuUsage: 85.5 },
              afterPayload: { instanceCount: 8, cpuUsage: 42.3 },
              impactPayload: { addedInstances: 4, strategy: 'MANUAL' },
              evidencePayload: { monitorSnapshot: 'https://monitor.example.com/snapshot/abc123' }
            }
          ],
          totalCount: 3
        }))
      });
    });

    await page.goto('/resource/governance');
    await expect(page.getByRole('heading', { name: '资源治理工作台' })).toBeVisible({ timeout: 10000 });

    await page.getByRole('tab', { name: '治理建议' }).click();
    await expect(page.locator('.governance-suggestions-tab')).toBeVisible({ timeout: 10000 });

    const suggestionsTable = page.locator('.governance-suggestions-tab .el-table').first();
    await expect(suggestionsTable).toBeVisible({ timeout: 10000 });

    const row = suggestionsTable.locator('tr').filter({ hasText: 'GS-2026-003' });
    await expect(row).toBeVisible({ timeout: 5000 });
    await page.waitForTimeout(500);

    await row.getByRole('button', { name: '查看计划' }).click();

    await expect(page.locator('.el-drawer').filter({ hasText: '计划详情' })).toBeVisible({ timeout: 5000 });

    // Click timeline button
    await page.locator('.timeline-entry-section').getByRole('button', { name: '查看时间线' }).click();

    // Verify timeline drawer opens
    const timelineDrawer = page.locator('.el-drawer').filter({ hasText: '执行时间线' });
    await expect(timelineDrawer).toBeVisible({ timeout: 5000 });

    // Verify impact summary is visible (use first() to avoid strict mode)
    await expect(timelineDrawer.getByText('成功扩容到 8 个实例').first()).toBeVisible({ timeout: 5000 });

    // Verify impact type RESOURCE_POOL is displayed
    await expect(timelineDrawer.getByText('资源池影响').first()).toBeVisible({ timeout: 5000 });

    // Click view payload button
    await timelineDrawer.getByRole('button', { name: '查看详情' }).first().click();

    // Verify nested drawer opens with payload content using aria-label
    const payloadDrawer = page.getByRole('dialog', { name: '证据详情' });
    await expect(payloadDrawer).toBeVisible({ timeout: 5000 });

    // Close nested drawer using aria-label
    await payloadDrawer.getByRole('button', { name: 'Close this dialog' }).first().click();
    await page.waitForTimeout(500);
    await expect(payloadDrawer).not.toBeVisible({ timeout: 3000 });

    // Close main drawer using aria-label
    await page.getByRole('dialog', { name: '执行时间线' }).getByRole('button', { name: 'Close this dialog' }).first().click();
    await page.waitForTimeout(500);
  });

  test('timeline API failure shows local error without affecting other tabs', async ({ page }) => {
    await setupMocks(page);

    const mockConfirmedPlan = {
      id: 102,
      planNo: 'AP-2026-002',
      suggestionId: 2,
      suggestionNo: 'GS-2026-002',
      actionType: 'CHECK_HOST_PLAN',
      targetType: 'HOST',
      status: 'CONFIRMED',
      riskLevel: 'WARNING',
      resourcePoolId: 1,
      resourcePoolName: 'E2E Test Pool 01',
      envId: 1,
      envName: '测试环境',
      planPayload: JSON.stringify({ hostId: 200, hostname: 'host-02', checkType: 'CONNECTIVITY' }),
      createdBy: 'admin',
      createdAt: new Date().toISOString(),
      confirmedBy: 'admin',
      confirmedAt: new Date().toISOString()
    };

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

    // Mock timeline API to fail
    await page.route('**/api/assets/governance-action-plans/102/timeline', async route => {
      await route.fulfill({
        status: 500,
        contentType: 'application/json',
        body: JSON.stringify({ code: 'INTERNAL_ERROR', message: 'Server error' })
      });
    });

    await page.goto('/resource/governance');
    await expect(page.getByRole('heading', { name: '资源治理工作台' })).toBeVisible({ timeout: 10000 });

    await page.getByRole('tab', { name: '治理建议' }).click();
    await expect(page.locator('.governance-suggestions-tab')).toBeVisible({ timeout: 10000 });

    const suggestionsTable = page.locator('.governance-suggestions-tab .el-table').first();
    await expect(suggestionsTable).toBeVisible({ timeout: 10000 });

    const row = suggestionsTable.locator('tr').filter({ hasText: 'GS-2026-002' });
    await expect(row).toBeVisible({ timeout: 5000 });
    await page.waitForTimeout(500);

    await row.getByRole('button', { name: '查看计划' }).click();

    await expect(page.locator('.el-drawer').filter({ hasText: '计划详情' })).toBeVisible({ timeout: 5000 });

    // Click timeline button
    await page.locator('.timeline-entry-section').getByRole('button', { name: '查看时间线' }).click();

    // Verify timeline drawer opens
    const timelineDrawer = page.locator('.el-drawer').filter({ hasText: '执行时间线' });
    await expect(timelineDrawer).toBeVisible({ timeout: 5000 });

    // Verify error message is shown in the drawer
    await expect(timelineDrawer.locator('.el-alert--error')).toBeVisible({ timeout: 5000 });

    // Close drawer using keyboard escape to ensure overlay is dismissed
    await page.keyboard.press('Escape');
    await page.waitForTimeout(500);
    await expect(timelineDrawer).not.toBeVisible({ timeout: 5000 });

    // Verify no global error message appears on the page
    await expect(page.locator('.el-message--error')).not.toBeVisible();
  });

  test('timeline drawer does not contain auto execute or auto scale text', async ({ page }) => {
    await setupMocks(page);

    const mockConfirmedPlan = {
      id: 102,
      planNo: 'AP-2026-002',
      suggestionId: 2,
      suggestionNo: 'GS-2026-002',
      actionType: 'CHECK_HOST_PLAN',
      targetType: 'HOST',
      status: 'CONFIRMED',
      riskLevel: 'WARNING',
      resourcePoolId: 1,
      resourcePoolName: 'E2E Test Pool 01',
      envId: 1,
      envName: '测试环境',
      planPayload: JSON.stringify({ hostId: 200, hostname: 'host-02', checkType: 'CONNECTIVITY' }),
      createdBy: 'admin',
      createdAt: new Date().toISOString(),
      confirmedBy: 'admin',
      confirmedAt: new Date().toISOString()
    };

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

    await page.route('**/api/assets/governance-action-plans/102/timeline', async route => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(apiResponse({
          subjectType: 'ACTION_PLAN',
          subjectId: 102,
          subjectNo: 'AP-2026-002',
          nodes: [
            { key: 'node-1', title: '计划创建', status: 'success', time: new Date().toISOString(), summary: '计划已创建' }
          ],
          totalCount: 1
        }))
      });
    });

    await page.goto('/resource/governance');
    await expect(page.getByRole('heading', { name: '资源治理工作台' })).toBeVisible({ timeout: 10000 });

    await page.getByRole('tab', { name: '治理建议' }).click();
    await expect(page.locator('.governance-suggestions-tab')).toBeVisible({ timeout: 10000 });

    const suggestionsTable = page.locator('.governance-suggestions-tab .el-table').first();
    await expect(suggestionsTable).toBeVisible({ timeout: 10000 });

    const row = suggestionsTable.locator('tr').filter({ hasText: 'GS-2026-002' });
    await expect(row).toBeVisible({ timeout: 5000 });
    await page.waitForTimeout(500);

    await row.getByRole('button', { name: '查看计划' }).click();

    await expect(page.locator('.el-drawer').filter({ hasText: '计划详情' })).toBeVisible({ timeout: 5000 });

    // Click timeline button
    await page.locator('.timeline-entry-section').getByRole('button', { name: '查看时间线' }).click();

    // Verify timeline drawer opens
    const timelineDrawer = page.locator('.el-drawer').filter({ hasText: '执行时间线' });
    await expect(timelineDrawer).toBeVisible({ timeout: 5000 });

    // Verify no auto execute / auto scale text
    await expect(timelineDrawer).not.toContainText('自动执行');
    await expect(timelineDrawer).not.toContainText('自动扩容');
    await expect(timelineDrawer).not.toContainText('调用执行器');
    await expect(timelineDrawer).not.toContainText('Auto execute');
    await expect(timelineDrawer).not.toContainText('Auto scale');
    await expect(timelineDrawer).not.toContainText('executor');

    // Close drawer using aria-label
    await timelineDrawer.getByRole('button', { name: 'Close this dialog' }).first().click();
  });

  test('timeline shows multiple IMPACT_RECORDED records', async ({ page }) => {
    await setupMocks(page);

    const mockExecutedPlan = {
      id: 103,
      planNo: 'AP-2026-003',
      suggestionId: 3,
      suggestionNo: 'GS-2026-003',
      actionType: 'SCALE_OUT_PLAN',
      targetType: 'RESOURCE_POOL',
      status: 'EXECUTED',
      riskLevel: 'CRITICAL',
      resourcePoolId: 1,
      resourcePoolName: 'E2E Test Pool 01',
      envId: 1,
      envName: '测试环境',
      planPayload: JSON.stringify({ scaleOutCount: 4 }),
      createdBy: 'admin',
      createdAt: new Date().toISOString(),
      confirmedBy: 'admin',
      confirmedAt: new Date().toISOString(),
      executedBy: 'admin',
      executedAt: new Date().toISOString()
    };

    await page.route('**/api/assets/governance-suggestions**', async route => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(pageResponse([
          { ...mockSuggestions[2], existingPlan: mockExecutedPlan }
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
        body: JSON.stringify(pageResponse([
          {
            id: 201,
            executionNo: 'EXE-2026-003-001',
            planId: 103,
            planNo: 'AP-2026-003',
            status: 'SUCCESS',
            executionMode: 'MANUAL_ONLY',
            operator: 'admin',
            startedAt: new Date().toISOString(),
            finishedAt: new Date().toISOString(),
            resultSummary: '扩容完成',
            createdBy: 'admin',
            createdAt: new Date().toISOString()
          }
        ]))
      });
    });

    // Mock timeline API with multiple IMPACT_RECORDED nodes (per Contract 3.1)
    await page.route('**/api/assets/governance-action-plans/103/timeline', async route => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(apiResponse({
          subjectType: 'ACTION_PLAN',
          subjectId: 103,
          subjectNo: 'AP-2026-003',
          nodes: [
            { key: 'node-1', title: '执行开始', status: 'success', time: new Date().toISOString(), summary: '执行已开始', actionType: 'RESOURCE_GOVERNANCE_ACTION_EXECUTION_STARTED' },
            { key: 'node-2', title: '执行完成', status: 'success', time: new Date().toISOString(), summary: '扩容完成', actionType: 'RESOURCE_GOVERNANCE_ACTION_EXECUTION_COMPLETED' },
            {
              key: 'node-3',
              title: '资源池影响已记录',
              status: 'success',
              time: new Date().toISOString(),
              summary: '实例数量从 4 增加到 8',
              actionType: 'RESOURCE_GOVERNANCE_ACTION_IMPACT_RECORDED',
              impactType: 'RESOURCE_POOL',
              impactSummary: '实例数量从 4 增加到 8',
              resultStatus: 'SUCCESS',
              beforePayload: { instanceCount: 4 },
              afterPayload: { instanceCount: 8 },
              impactPayload: { addedInstances: 4 },
              evidencePayload: { capacityReport: 'report-001' }
            },
            {
              key: 'node-4',
              title: '主机影响已记录',
              status: 'success',
              time: new Date().toISOString(),
              summary: '新增 2 台主机上线',
              actionType: 'RESOURCE_GOVERNANCE_ACTION_IMPACT_RECORDED',
              impactType: 'HOST',
              impactSummary: '新增 2 台主机上线',
              resultStatus: 'SUCCESS',
              beforePayload: { hostCount: 10 },
              afterPayload: { hostCount: 12 },
              impactPayload: { addedHosts: 2 },
              evidencePayload: { hostList: ['host-11', 'host-12'] }
            }
          ],
          totalCount: 4
        }))
      });
    });

    await page.goto('/resource/governance');
    await expect(page.getByRole('heading', { name: '资源治理工作台' })).toBeVisible({ timeout: 10000 });

    await page.getByRole('tab', { name: '治理建议' }).click();
    await expect(page.locator('.governance-suggestions-tab')).toBeVisible({ timeout: 10000 });

    const suggestionsTable = page.locator('.governance-suggestions-tab .el-table').first();
    await expect(suggestionsTable).toBeVisible({ timeout: 10000 });

    // Use mockSuggestions[2] which has suggestionNo GS-2026-003
    const row = suggestionsTable.locator('tr').filter({ hasText: mockSuggestions[2].suggestionNo });
    await expect(row).toBeVisible({ timeout: 5000 });
    await page.waitForTimeout(500);

    await row.getByRole('button', { name: '查看计划' }).click();

    await expect(page.locator('.el-drawer').filter({ hasText: '计划详情' })).toBeVisible({ timeout: 5000 });

    // Click timeline button
    await page.locator('.timeline-entry-section').getByRole('button', { name: '查看时间线' }).click();

    // Verify timeline drawer opens
    const timelineDrawer = page.locator('.el-drawer').filter({ hasText: '执行时间线' });
    await expect(timelineDrawer).toBeVisible({ timeout: 5000 });

    // Verify both impact records are displayed in the impact table
    const impactTable = timelineDrawer.locator('.impact-section .el-table');
    await expect(impactTable).toBeVisible({ timeout: 5000 });

    // Should have 2 rows in the impact table
    const impactRows = impactTable.locator('tbody tr');
    await expect(impactRows).toHaveCount(2, { timeout: 5000 });

    // Verify both impact summaries are visible
    await expect(timelineDrawer.getByText('实例数量从 4 增加到 8').first()).toBeVisible({ timeout: 5000 });
    await expect(timelineDrawer.getByText('新增 2 台主机上线').first()).toBeVisible({ timeout: 5000 });

    // Verify both impact types are visible
    await expect(timelineDrawer.getByText('资源池影响').first()).toBeVisible({ timeout: 5000 });
    await expect(timelineDrawer.getByText('主机影响').first()).toBeVisible({ timeout: 5000 });

    // Close drawer using aria-label
    await timelineDrawer.getByRole('button', { name: 'Close this dialog' }).first().click();
  });

  // ========== Governance Ops Tests (L-C) ==========

  /**
   * Setup governance ops route mocks
   */
  async function setupOpsMocks(page: import('@playwright/test').Page) {
    await page.route('**/api/assets/governance-ops/overview**', async route => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(apiResponse(createMockOverview()))
      });
    });

    await page.route('**/api/assets/governance-ops/trends**', async route => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(apiResponse(createMockTrends()))
      });
    });

    await page.route('**/api/assets/governance-ops/sla**', async route => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(apiResponse(createMockSla()))
      });
    });

    await page.route('**/api/assets/governance-ops/effectiveness**', async route => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(apiResponse(createMockEffectiveness()))
      });
    });

    await page.route('**/api/assets/governance-ops/review-items**', async route => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(pageResponse(createMockReviewItems()))
      });
    });
  }

  test('governance ops tab is visible', async ({ page }) => {
    await setupMocks(page);
    await setupOpsMocks(page);

    await page.goto('/resource/governance');
    await expect(page.getByRole('heading', { name: '资源治理工作台' })).toBeVisible({ timeout: 10000 });

    // Verify governance ops tab is visible
    await expect(page.getByRole('tab', { name: '治理运营' })).toBeVisible();
  });

  test('governance ops overview shows metrics', async ({ page }) => {
    await setupMocks(page);
    await setupOpsMocks(page);

    await page.goto('/resource/governance');
    await expect(page.getByRole('heading', { name: '资源治理工作台' })).toBeVisible({ timeout: 10000 });

    // Click on governance ops tab
    await page.getByRole('tab', { name: '治理运营' }).click();

    // Wait for tab panel content
    await expect(page.locator('.governance-ops-tab')).toBeVisible({ timeout: 10000 });

    // Wait for overview section
    await expect(page.locator('.overview-grid')).toBeVisible({ timeout: 10000 });

    // Verify overview metrics labels
    const overviewSection = page.locator('.governance-ops-tab');
    await expect(overviewSection.getByText('总风险数')).toBeVisible({ timeout: 5000 });
    await expect(overviewSection.getByText('严重风险数')).toBeVisible();
    await expect(overviewSection.getByText('警告风险数')).toBeVisible();
    await expect(overviewSection.getByText('待处理建议单')).toBeVisible();
    await expect(overviewSection.getByText('已确认计划')).toBeVisible();
    await expect(overviewSection.getByText('执行中记录')).toBeVisible();
    await expect(overviewSection.getByText('已完成执行')).toBeVisible();
    await expect(overviewSection.getByText('失败执行')).toBeVisible();
    await expect(overviewSection.getByText('已记录影响')).toBeVisible();
    await expect(overviewSection.getByText('平均闭环耗时')).toBeVisible();
  });

  test('governance ops sla shows efficiency metrics', async ({ page }) => {
    await setupMocks(page);
    await setupOpsMocks(page);

    await page.goto('/resource/governance');
    await expect(page.getByRole('heading', { name: '资源治理工作台' })).toBeVisible({ timeout: 10000 });

    await page.getByRole('tab', { name: '治理运营' }).click();
    await expect(page.locator('.governance-ops-tab')).toBeVisible({ timeout: 10000 });

    // Verify SLA section header
    const slaSection = page.locator('.governance-ops-tab');
    await expect(slaSection.getByText('SLA 闭环效率')).toBeVisible({ timeout: 5000 });

    // Wait for SLA grid
    await expect(page.locator('.sla-grid')).toBeVisible({ timeout: 10000 });

    // Verify SLA metrics labels
    await expect(slaSection.getByText('平均建议确认耗时')).toBeVisible();
    await expect(slaSection.getByText('平均计划确认耗时')).toBeVisible();
    await expect(slaSection.getByText('平均执行完成耗时')).toBeVisible();
    await expect(slaSection.getByText('超时建议单')).toBeVisible();
    await expect(slaSection.getByText('超时计划')).toBeVisible();
    await expect(slaSection.getByText('超时执行')).toBeVisible();
    await expect(slaSection.getByText('SLA 目标')).toBeVisible();
    await expect(slaSection.getByText('SLA 达标率')).toBeVisible();
  });

  test('governance ops trend shows data table', async ({ page }) => {
    await setupMocks(page);
    await setupOpsMocks(page);

    await page.goto('/resource/governance');
    await expect(page.getByRole('heading', { name: '资源治理工作台' })).toBeVisible({ timeout: 10000 });

    await page.getByRole('tab', { name: '治理运营' }).click();
    await expect(page.locator('.governance-ops-tab')).toBeVisible({ timeout: 10000 });

    // Verify trend section header
    const trendSection = page.locator('.governance-ops-tab');
    await expect(trendSection.getByText('治理趋势')).toBeVisible({ timeout: 5000 });

    // Wait for trend table
    await expect(page.locator('.governance-ops-tab .el-table').first()).toBeVisible({ timeout: 10000 });

    // Verify trend table headers
    const trendTable = page.locator('.governance-ops-tab .el-table').first();
    await expect(trendTable.locator('.el-table__header').getByText('时间')).toBeVisible();
    await expect(trendTable.locator('.el-table__header').getByText('风险数')).toBeVisible();
    await expect(trendTable.locator('.el-table__header').getByText('建议单数')).toBeVisible();
    await expect(trendTable.locator('.el-table__header').getByText('计划数')).toBeVisible();
    await expect(trendTable.locator('.el-table__header').getByText('执行数')).toBeVisible();
    await expect(trendTable.locator('.el-table__header').getByText('影响数')).toBeVisible();
    await expect(trendTable.locator('.el-table__header').getByText('成功/失败')).toBeVisible();
  });

  test('governance ops effectiveness shows statistics', async ({ page }) => {
    await setupMocks(page);
    await setupOpsMocks(page);

    await page.goto('/resource/governance');
    await expect(page.getByRole('heading', { name: '资源治理工作台' })).toBeVisible({ timeout: 10000 });

    await page.getByRole('tab', { name: '治理运营' }).click();
    await expect(page.locator('.governance-ops-tab')).toBeVisible({ timeout: 10000 });

    // Verify effectiveness section header
    const effectivenessSection = page.locator('.governance-ops-tab');
    await expect(effectivenessSection.locator('.section-title').getByText('效果评估')).toBeVisible({ timeout: 5000 });

    // Wait for effectiveness sections to load (array response)
    await expect(page.locator('.effectiveness-section').first()).toBeVisible({ timeout: 10000 });

    // Verify multiple effectiveness items are displayed (array of 2 items)
    const effectivenessItems = page.locator('.effectiveness-section');
    await expect(effectivenessItems).toHaveCount(2, { timeout: 5000 });

    // Verify effectiveness statistics labels
    await expect(effectivenessSection.getByText('改善数').first()).toBeVisible({ timeout: 5000 });
    await expect(effectivenessSection.getByText('未变化数').first()).toBeVisible();
    await expect(effectivenessSection.getByText('恶化数').first()).toBeVisible();
    await expect(effectivenessSection.getByText('改善率').first()).toBeVisible();
    await expect(effectivenessSection.getByText('样本量').first()).toBeVisible();

    // Verify both impact types are visible
    await expect(effectivenessSection.getByText('RESOURCE_POOL')).toBeVisible({ timeout: 5000 });
    await expect(effectivenessSection.getByText('HOST')).toBeVisible({ timeout: 5000 });
  });

  test('governance ops review items shows list', async ({ page }) => {
    await setupMocks(page);
    await setupOpsMocks(page);

    await page.goto('/resource/governance');
    await expect(page.getByRole('heading', { name: '资源治理工作台' })).toBeVisible({ timeout: 10000 });

    await page.getByRole('tab', { name: '治理运营' }).click();
    await expect(page.locator('.governance-ops-tab')).toBeVisible({ timeout: 10000 });

    // Verify review items section header
    const reviewSection = page.locator('.governance-ops-tab');
    await expect(reviewSection.locator('.section-title').getByText('待复盘事项')).toBeVisible({ timeout: 5000 });

    // Wait for review items table (the second table in the tab)
    const reviewTables = page.locator('.governance-ops-tab .el-table');
    await expect(reviewTables.nth(1)).toBeVisible({ timeout: 10000 });

    // Verify review items table headers
    const reviewTable = reviewTables.nth(1);
    await expect(reviewTable.locator('.el-table__header').getByText('条目类型')).toBeVisible();
    await expect(reviewTable.locator('.el-table__header').getByText('条目编号')).toBeVisible();
    await expect(reviewTable.locator('.el-table__header').getByText('风险等级')).toBeVisible();
    await expect(reviewTable.locator('.el-table__header').getByText('负责人')).toBeVisible();

    // Verify pagination
    await expect(page.locator('.governance-ops-tab .el-pagination')).toBeVisible();
  });

  test('governance ops API failure shows local error without affecting other tabs', async ({ page }) => {
    await setupMocks(page);

    // Mock overview API to fail
    await page.route('**/api/assets/governance-ops/overview**', async route => {
      await route.fulfill({
        status: 500,
        contentType: 'application/json',
        body: JSON.stringify({ code: 'INTERNAL_ERROR', message: 'Server error' })
      });
    });

    // Mock other ops APIs to succeed
    await page.route('**/api/assets/governance-ops/trends**', async route => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(apiResponse(createMockTrends()))
      });
    });

    await page.route('**/api/assets/governance-ops/sla**', async route => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(apiResponse(createMockSla()))
      });
    });

    await page.goto('/resource/governance');
    await expect(page.getByRole('heading', { name: '资源治理工作台' })).toBeVisible({ timeout: 10000 });

    await page.getByRole('tab', { name: '治理运营' }).click();
    await expect(page.locator('.governance-ops-tab')).toBeVisible({ timeout: 10000 });

    // Verify overview section shows error alert
    const overviewAlert = page.locator('.governance-ops-tab .el-alert--error').first();
    await expect(overviewAlert).toBeVisible({ timeout: 5000 });

    // Verify SLA section still loads successfully (local degradation)
    await expect(page.locator('.sla-grid')).toBeVisible({ timeout: 10000 });

    // Verify no global error message appears on the page (use first() to avoid strict mode)
    await expect(page.locator('.el-message--error').first()).not.toBeVisible();

    // Switch to another tab to verify other tabs still work
    await page.getByRole('tab', { name: '资源池容量' }).click();
    await expect(page.locator('.pool-capacity-tab .el-table').first()).toBeVisible({ timeout: 10000 });
  });

  test('governance ops page does not contain auto execute or auto scale text', async ({ page }) => {
    await setupMocks(page);
    await setupOpsMocks(page);

    await page.goto('/resource/governance');
    await expect(page.getByRole('heading', { name: '资源治理工作台' })).toBeVisible({ timeout: 10000 });

    await page.getByRole('tab', { name: '治理运营' }).click();
    await expect(page.locator('.governance-ops-tab')).toBeVisible({ timeout: 10000 });

    // Wait for all sections to load
    await expect(page.locator('.overview-grid')).toBeVisible({ timeout: 10000 });

    // Verify no auto execute / auto scale / executor text (MANUAL_ONLY constraint)
    const opsTab = page.locator('.governance-ops-tab');
    await expect(opsTab).not.toContainText('自动执行');
    await expect(opsTab).not.toContainText('自动扩容');
    await expect(opsTab).not.toContainText('调用执行器');
    await expect(opsTab).not.toContainText('Auto execute');
    await expect(opsTab).not.toContainText('Auto scale');

    // Verify manual only notice is shown
    await expect(opsTab.getByText('人工执行模式')).toBeVisible({ timeout: 5000 });
  });
});
