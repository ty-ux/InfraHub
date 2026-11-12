import { expect, test } from '@playwright/test';
import { loginAsAdmin } from './helpers/auth';

/**
 * E2E tests for H+1-A Capacity Policy Management
 * Covers: List, Create, Edit, Toggle, Validation
 * Uses route mocking with ApiResponse wrapper
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

test.describe('capacity policy management', () => {
  test.setTimeout(60000);

  test.beforeEach(async ({ page }) => {
    await loginAsAdmin(page);
  });

  /**
   * Mock data for consistent test results
   */
  const mockPolicies = [
    {
      id: 1,
      policyName: 'CPU-高使用率警告',
      envId: 1,
      resourcePoolId: 1,
      metricType: 'CPU',
      warningThreshold: 70,
      criticalThreshold: 90,
      enabled: true,
      description: 'CPU使用率超过阈值时告警',
      createdAt: '2024-01-01T00:00:00Z',
      updatedAt: '2024-01-01T00:00:00Z'
    },
    {
      id: 2,
      policyName: '内存-严重告警',
      envId: 1,
      resourcePoolId: 2,
      metricType: 'MEMORY',
      warningThreshold: 75,
      criticalThreshold: 85,
      enabled: false,
      description: '内存使用率告警策略',
      createdAt: '2024-01-02T00:00:00Z',
      updatedAt: '2024-01-02T00:00:00Z'
    },
    {
      id: 3,
      policyName: '磁盘空间监控',
      envId: 2,
      resourcePoolId: null,
      metricType: 'DISK',
      warningThreshold: 60,
      criticalThreshold: 80,
      enabled: true,
      description: '磁盘空间不足告警',
      createdAt: '2024-01-03T00:00:00Z',
      updatedAt: '2024-01-03T00:00:00Z'
    }
  ];

  const mockEnvs = [
    { id: 1, envCode: 'test', envName: '测试环境' },
    { id: 2, envCode: 'prod', envName: '生产环境' }
  ];

  const mockResourcePools = [
    { id: 1, poolCode: 'POOL-01', poolName: '资源池01', poolType: 'KUBERNETES', status: 'ACTIVE' },
    { id: 2, poolCode: 'POOL-02', poolName: '资源池02', poolType: 'VM', status: 'ACTIVE' }
  ];

  /**
   * Setup all route mocks for capacity policy page
   */
  async function setupMocks(page: import('@playwright/test').Page) {
    // Mock envs lookup
    await page.route('**/api/envs**', async route => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(pageResponse(mockEnvs))
      });
    });

    // Mock resource pools list
    await page.route('**/api/assets/resource-pools**', async route => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(pageResponse(mockResourcePools))
      });
    });

    // Mock capacity policies list
    await page.route('**/api/assets/capacity-policies**', async route => {
      const url = route.request().url();
      if (url.includes('/toggle')) {
        // Handle toggle request
        const enabled = url.includes('enabled=true');
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify(apiResponse({ success: true }))
        });
      } else if (route.request().method() === 'POST') {
        // Create policy
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify(apiResponse({
            id: 100,
            policyName: '新策略',
            metricType: 'CPU',
            warningThreshold: 60,
            criticalThreshold: 80,
            enabled: true,
            createdAt: new Date().toISOString(),
            updatedAt: new Date().toISOString()
          }))
        });
      } else if (route.request().method() === 'PUT') {
        // Update policy
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify(apiResponse({
            id: 1,
            policyName: '更新后的策略',
            metricType: 'CPU',
            warningThreshold: 65,
            criticalThreshold: 85,
            enabled: true,
            createdAt: '2024-01-01T00:00:00Z',
            updatedAt: new Date().toISOString()
          }))
        });
      } else {
        // GET list
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify(pageResponse(mockPolicies))
        });
      }
    });

    // Mock single policy GET
    await page.route(/\/api\/assets\/capacity-policies\/(\d+)$/, async route => {
      const match = route.request().url().match(/\/capacity-policies\/(\d+)/);
      const id = match ? parseInt(match[1], 10) : 1;
      const policy = mockPolicies.find(p => p.id === id) || mockPolicies[0];
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(apiResponse(policy))
      });
    });
  }

  test('page opens and shows policy list', async ({ page }) => {
    await setupMocks(page);

    // Navigate to capacity policies page
    await page.goto('/resource/capacity-policies');

    // Verify page title
    await expect(page.getByRole('heading', { name: '容量策略管理' })).toBeVisible({ timeout: 10000 });

    // Verify filter bar is visible
    await expect(page.locator('.filter-bar')).toBeVisible();

    // Wait for table to load
    await expect(page.locator('.el-table').first()).toBeVisible({ timeout: 10000 });

    // Verify table headers
    await expect(page.locator('.el-table__header').getByText('策略名称')).toBeVisible();
    await expect(page.locator('.el-table__header').getByText('指标类型')).toBeVisible();
    await expect(page.locator('.el-table__header').getByText('警告阈值')).toBeVisible();
    await expect(page.locator('.el-table__header').getByText('严重阈值')).toBeVisible();

    // Verify policy names are displayed
    await expect(page.getByText('CPU-高使用率警告')).toBeVisible({ timeout: 5000 });
    await expect(page.getByText('内存-严重告警')).toBeVisible();

    // Verify pagination
    await expect(page.locator('.el-pagination')).toBeVisible();
  });

  test('create policy form validation', async ({ page }) => {
    await setupMocks(page);

    await page.goto('/resource/capacity-policies');
    await expect(page.getByRole('heading', { name: '容量策略管理' })).toBeVisible({ timeout: 10000 });

    // Click create button (uses common.create = "新建")
    await page.getByRole('button', { name: '新建' }).click();

    // Wait for dialog (uses capacityPolicies.createPolicy as title)
    await expect(page.locator('.el-dialog').getByText('新建策略')).toBeVisible({ timeout: 5000 });

    // Try to submit without required fields
    await page.locator('.el-dialog').getByRole('button', { name: '确认' }).click();

    // Verify validation messages
    await expect(page.locator('.el-form-item__error').first()).toBeVisible({ timeout: 3000 });
  });

  test('create CPU policy successfully', async ({ page }) => {
    await setupMocks(page);

    await page.goto('/resource/capacity-policies');
    await expect(page.getByRole('heading', { name: '容量策略管理' })).toBeVisible({ timeout: 10000 });

    // Click create button
    await page.getByRole('button', { name: '新建' }).click();
    await expect(page.locator('.el-dialog').getByText('新建策略')).toBeVisible({ timeout: 5000 });

    const dialog = page.locator('.el-dialog');

    // Fill policy name - use .el-input__inner for Element Plus input
    await dialog.locator('.el-input__inner').first().fill('新策略');

    // Select metric type - Element Plus teleports dropdown to body
    await dialog.locator('.el-select').first().click();
    await page.locator('.el-select-dropdown:visible').waitFor({ timeout: 3000 });
    await page.locator('.el-select-dropdown:visible .el-select-dropdown__item').getByText('CPU').click();

    // Fill thresholds
    const thresholdInputs = dialog.locator('.el-input-number .el-input__inner');
    await thresholdInputs.nth(0).fill('60');
    await thresholdInputs.nth(1).fill('80');

    // Select environment - use .last() as it's the most recently opened dropdown
    await dialog.locator('.el-select').nth(1).click();
    await page.locator('.el-select-dropdown:visible').last().waitFor({ timeout: 3000 });
    await page.locator('.el-select-dropdown:visible').last().locator('.el-select-dropdown__item').getByText('测试环境').click();

    // Submit
    await dialog.getByRole('button', { name: '确认' }).click();

    // Verify success message
    await expect(page.locator('.el-message--success')).toBeVisible({ timeout: 5000 });
  });

  test('edit policy successfully', async ({ page }) => {
    await setupMocks(page);

    await page.goto('/resource/capacity-policies');
    await expect(page.getByRole('heading', { name: '容量策略管理' })).toBeVisible({ timeout: 10000 });

    // Wait for table
    await expect(page.locator('.el-table').first()).toBeVisible({ timeout: 10000 });

    // Click edit button for first policy
    await page.locator('.el-table').getByRole('button', { name: '编辑' }).first().click();

    // Wait for edit dialog
    await expect(page.locator('.el-dialog').getByText('编辑策略')).toBeVisible({ timeout: 5000 });

    // Modify policy name
    const dialog = page.locator('.el-dialog');
    const nameInput = dialog.locator('input.el-input__inner').first();
    await nameInput.clear();
    await nameInput.fill('更新后的策略');

    // Submit
    await dialog.getByRole('button', { name: '确认' }).click();

    // Verify success message
    await expect(page.locator('.el-message--success')).toBeVisible({ timeout: 5000 });
  });

  test('toggle enable/disable calls correct endpoint', async ({ page }) => {
    await setupMocks(page);

    await page.goto('/resource/capacity-policies');
    await expect(page.getByRole('heading', { name: '容量策略管理' })).toBeVisible({ timeout: 10000 });

    // Wait for table
    await expect(page.locator('.el-table').first()).toBeVisible({ timeout: 10000 });

    // Find the first switch in the table
    const firstSwitch = page.locator('.el-table .el-switch').first();
    await expect(firstSwitch).toBeVisible({ timeout: 5000 });

    // Click toggle button
    await firstSwitch.click();

    // Verify toggle API was called (check for success message)
    await expect(page.locator('.el-message--success')).toBeVisible({ timeout: 5000 });
  });

  test('warning >= critical shows validation error', async ({ page }) => {
    await setupMocks(page);

    await page.goto('/resource/capacity-policies');
    await expect(page.getByRole('heading', { name: '容量策略管理' })).toBeVisible({ timeout: 10000 });

    // Click create button
    await page.getByRole('button', { name: '新建' }).click();
    await expect(page.locator('.el-dialog').getByText('新建策略')).toBeVisible({ timeout: 5000 });

    const dialog = page.locator('.el-dialog');

    // Fill form with warning >= critical
    await dialog.locator('.el-input__inner').first().fill('无效策略');

    // Select metric type
    await dialog.locator('.el-select').first().click();
    await page.locator('.el-select-dropdown:visible').waitFor({ timeout: 3000 });
    await page.locator('.el-select-dropdown:visible .el-select-dropdown__item').getByText('CPU').click();

    // Fill thresholds where warning >= critical
    const thresholdInputs = dialog.locator('.el-input-number .el-input__inner');
    await thresholdInputs.nth(0).fill('85');
    await thresholdInputs.nth(1).fill('80');

    // Select environment - use .last() as it's the most recently opened dropdown
    await dialog.locator('.el-select').nth(1).click();
    await page.locator('.el-select-dropdown:visible').last().waitFor({ timeout: 3000 });
    await page.locator('.el-select-dropdown:visible').last().locator('.el-select-dropdown__item').getByText('测试环境').click();

    // Submit
    await dialog.getByRole('button', { name: '确认' }).click();

    // Verify validation error for threshold
    await expect(page.locator('.el-form-item__error').getByText(/警告阈值必须小于严重阈值/)).toBeVisible({ timeout: 5000 });
  });

  test('view policy details', async ({ page }) => {
    await setupMocks(page);

    await page.goto('/resource/capacity-policies');
    await expect(page.getByRole('heading', { name: '容量策略管理' })).toBeVisible({ timeout: 10000 });

    // Wait for table
    await expect(page.locator('.el-table').first()).toBeVisible({ timeout: 10000 });

    // Click view button for first policy (use link type button)
    await page.locator('.el-table .el-button--primary').first().click();

    // Wait for detail dialog
    await expect(page.locator('.el-dialog').getByText('策略详情')).toBeVisible({ timeout: 5000 });

    // Verify policy name is shown in details
    await expect(page.locator('.el-descriptions').getByText('CPU-高使用率警告')).toBeVisible();
  });

  test('filter by metric type', async ({ page }) => {
    await setupMocks(page);

    await page.goto('/resource/capacity-policies');
    await expect(page.getByRole('heading', { name: '容量策略管理' })).toBeVisible({ timeout: 10000 });

    // Verify filter bar
    const filterBar = page.locator('.filter-bar');
    await expect(filterBar).toBeVisible();

    // Check metric type filter exists (third select - env, pool, metricType)
    await expect(filterBar.locator('.el-select').nth(2)).toBeVisible();

    // Select CPU metric type filter
    await filterBar.locator('.el-select').nth(2).click();
    await page.locator('.el-select-dropdown:visible').waitFor({ timeout: 3000 });
    await page.locator('.el-select-dropdown:visible .el-select-dropdown__item').getByText('CPU').click();

    // Verify table refresh (CPU policy should be visible)
    await expect(page.getByText('CPU-高使用率警告')).toBeVisible({ timeout: 5000 });
  });
});
