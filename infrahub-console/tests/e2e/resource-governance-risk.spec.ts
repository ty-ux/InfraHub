import { expect, test } from '@playwright/test';
import { loginAsAdmin } from './helpers/auth';

/**
 * E2E tests for V2/H-D Capacity Risk Feature
 * Covers: Capacity Risk Tab with risk level display, filtering, and error handling
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

test.describe('resource governance capacity risk', () => {
  test.setTimeout(60000);

  test.beforeEach(async ({ page }) => {
    await loginAsAdmin(page);
  });

  /**
   * Mock data for capacity risk tests
   */
  const mockRisks = [
    {
      resourcePoolId: 1,
      resourcePoolName: 'Critical Risk Pool',
      poolCode: 'POOL-CRITICAL-01',
      envId: 1,
      envName: '生产环境',
      riskLevel: 'CRITICAL',
      riskScore: 95,
      riskReasons: ['CPU使用率超过90%', '内存使用率超过85%', '离线主机数超过阈值'],
      cpuUsagePercent: 92,
      memoryUsagePercent: 88,
      diskUsagePercent: 75,
      onlineHostCount: 5,
      offlineHostCount: 3,
      unknownHostCount: 1,
      evaluatedAt: new Date().toISOString()
    },
    {
      resourcePoolId: 2,
      resourcePoolName: 'Warning Risk Pool',
      poolCode: 'POOL-WARNING-01',
      envId: 1,
      envName: '生产环境',
      riskLevel: 'WARNING',
      riskScore: 65,
      riskReasons: ['磁盘使用率超过70%'],
      cpuUsagePercent: 45,
      memoryUsagePercent: 55,
      diskUsagePercent: 72,
      onlineHostCount: 10,
      offlineHostCount: 1,
      unknownHostCount: 0,
      evaluatedAt: new Date().toISOString()
    },
    {
      resourcePoolId: 3,
      resourcePoolName: 'Normal Pool',
      poolCode: 'POOL-NORMAL-01',
      envId: 2,
      envName: '测试环境',
      riskLevel: 'NORMAL',
      riskScore: 20,
      riskReasons: [],
      cpuUsagePercent: 30,
      memoryUsagePercent: 40,
      diskUsagePercent: 35,
      onlineHostCount: 8,
      offlineHostCount: 0,
      unknownHostCount: 0,
      evaluatedAt: new Date().toISOString()
    },
    {
      resourcePoolId: 4,
      resourcePoolName: 'Unknown Status Pool',
      poolCode: 'POOL-UNKNOWN-01',
      envId: 2,
      envName: '测试环境',
      riskLevel: 'UNKNOWN',
      riskScore: 0,
      riskReasons: ['无法获取监控数据'],
      cpuUsagePercent: 0,
      memoryUsagePercent: 0,
      diskUsagePercent: 0,
      onlineHostCount: 0,
      offlineHostCount: 0,
      unknownHostCount: 5,
      evaluatedAt: new Date().toISOString()
    }
  ];

  const mockEnvs = [
    { id: 1, envCode: 'prod', envName: '生产环境' },
    { id: 2, envCode: 'test', envName: '测试环境' }
  ];

  const mockResourcePools = [
    { id: 1, poolCode: 'POOL-CRITICAL-01', poolName: 'Critical Risk Pool', poolType: 'KUBERNETES', status: 'ACTIVE' },
    { id: 2, poolCode: 'POOL-WARNING-01', poolName: 'Warning Risk Pool', poolType: 'VM', status: 'ACTIVE' },
    { id: 3, poolCode: 'POOL-NORMAL-01', poolName: 'Normal Pool', poolType: 'KUBERNETES', status: 'ACTIVE' },
    { id: 4, poolCode: 'POOL-UNKNOWN-01', poolName: 'Unknown Status Pool', poolType: 'VM', status: 'ACTIVE' }
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
   * Setup all route mocks for capacity risk page
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

    // Capacity Risk API mock
    await page.route('**/api/assets/resource-pools/risks**', async route => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(pageResponse(mockRisks))
      });
    });
  }

  test('capacity risk tab is visible and clickable', async ({ page }) => {
    await setupMocks(page);

    await page.goto('/resource/governance');
    await expect(page.getByRole('heading', { name: '资源治理工作台' })).toBeVisible({ timeout: 10000 });

    // Verify capacity risk tab is visible
    await expect(page.getByRole('tab', { name: '容量风险' })).toBeVisible();
  });

  test('risk level tags display with correct colors', async ({ page }) => {
    await setupMocks(page);

    await page.goto('/resource/governance');
    await expect(page.getByRole('heading', { name: '资源治理工作台' })).toBeVisible({ timeout: 10000 });

    // Click on capacity risk tab
    await page.getByRole('tab', { name: '容量风险' }).click();

    // Wait for tab panel content
    await expect(page.locator('.capacity-risk-tab')).toBeVisible({ timeout: 10000 });

    // Wait for table to load
    const riskTable = page.locator('.capacity-risk-tab .el-table').first();
    await expect(riskTable).toBeVisible({ timeout: 10000 });

    // Verify CRITICAL tag (danger - red)
    await expect(riskTable.locator('.el-tag--danger').getByText('严重').first()).toBeVisible({ timeout: 5000 });

    // Verify WARNING tag (warning - orange)
    await expect(riskTable.locator('.el-tag--warning').getByText('警告').first()).toBeVisible({ timeout: 5000 });

    // Verify NORMAL tag (success - green)
    await expect(riskTable.locator('.el-tag--success').getByText('正常').first()).toBeVisible({ timeout: 5000 });

    // Verify UNKNOWN tag (info - gray)
    await expect(riskTable.locator('.el-tag--info').getByText('未知').first()).toBeVisible({ timeout: 5000 });
  });

  test('risk reasons display in table', async ({ page }) => {
    await setupMocks(page);

    await page.goto('/resource/governance');
    await expect(page.getByRole('heading', { name: '资源治理工作台' })).toBeVisible({ timeout: 10000 });

    await page.getByRole('tab', { name: '容量风险' }).click();
    await expect(page.locator('.capacity-risk-tab')).toBeVisible({ timeout: 10000 });

    const riskTable = page.locator('.capacity-risk-tab .el-table').first();
    await expect(riskTable).toBeVisible({ timeout: 10000 });

    // Verify risk reasons are displayed
    await expect(riskTable.getByText('CPU使用率超过90%')).toBeVisible({ timeout: 5000 });
    await expect(riskTable.getByText('磁盘使用率超过70%')).toBeVisible({ timeout: 5000 });
  });

  test('risk level filter triggers API request', async ({ page }) => {
    await setupMocks(page);

    let requestCount = 0;
    await page.route('**/api/assets/resource-pools/risks**', async route => {
      requestCount++;
      const url = new URL(route.request().url());
      const riskLevel = url.searchParams.get('riskLevel');

      // Filter data based on riskLevel if provided
      const filtered = riskLevel
        ? mockRisks.filter(r => r.riskLevel === riskLevel)
        : mockRisks;

      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(pageResponse(filtered))
      });
    });

    await page.goto('/resource/governance');
    await expect(page.getByRole('heading', { name: '资源治理工作台' })).toBeVisible({ timeout: 10000 });

    // Click on capacity risk tab to activate the risk level filter
    await page.getByRole('tab', { name: '容量风险' }).click();
    await expect(page.locator('.capacity-risk-tab')).toBeVisible({ timeout: 10000 });

    const initialCount = requestCount;

    // Select CRITICAL from risk level filter
    const riskLevelSelect = page.locator('.filter-bar .el-select').last();
    await riskLevelSelect.click();
    // Use more specific selector to avoid matching metric label
    await page.locator('.el-select-dropdown:visible').getByText('严重').click();

    // Wait a bit for the request to fire
    await page.waitForTimeout(500);

    // Verify that a new request was made
    expect(requestCount).toBeGreaterThan(initialCount);
  });

  test('API failure shows local error without affecting other tabs', async ({ page }) => {
    // Setup mocks for other tabs but fail the risk API
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

    // Make risk API fail
    await page.route('**/api/assets/resource-pools/risks**', async route => {
      await route.fulfill({
        status: 500,
        contentType: 'application/json',
        body: JSON.stringify({
          code: 'INTERNAL_ERROR',
          message: 'Internal server error',
          traceId: 'trace-error'
        })
      });
    });

    await page.goto('/resource/governance');
    await expect(page.getByRole('heading', { name: '资源治理工作台' })).toBeVisible({ timeout: 10000 });

    // Verify pool capacity tab still works
    await expect(page.getByRole('tab', { name: '资源池容量' })).toHaveAttribute('aria-selected', 'true');
    await expect(page.locator('.pool-capacity-tab')).toBeVisible({ timeout: 10000 });

    // Click on capacity risk tab
    await page.getByRole('tab', { name: '容量风险' }).click();
    await expect(page.locator('.capacity-risk-tab')).toBeVisible({ timeout: 10000 });

    // Wait for error message to appear
    await expect(page.locator('.capacity-risk-tab .el-alert--error')).toBeVisible({ timeout: 10000 });

    // Go back to pool capacity tab - should still work
    await page.getByRole('tab', { name: '资源池容量' }).click();
    await expect(page.locator('.pool-capacity-tab')).toBeVisible({ timeout: 10000 });
  });

  test('metrics show critical and warning risk pool counts', async ({ page }) => {
    await setupMocks(page);

    await page.goto('/resource/governance');
    await expect(page.getByRole('heading', { name: '资源治理工作台' })).toBeVisible({ timeout: 10000 });

    // Wait for metrics to load
    await expect(page.locator('.metrics-card')).toBeVisible({ timeout: 10000 });

    // Verify critical risk pool count metric is displayed
    await expect(page.getByText('严重风险资源池')).toBeVisible();

    // Verify warning risk pool count metric is displayed
    await expect(page.getByText('警告风险资源池')).toBeVisible();
  });
});
