import { expect, test } from '@playwright/test';
import { loginAsAdmin } from './helpers/auth';
import { mockResourceApis, mockReleaseList } from './helpers/mocks';
import { openPage } from './helpers/ui';

test.describe('T-A deploy config check (Contract 2.1, 3.1-3.3)', () => {
  test.setTimeout(90000);

  test.beforeEach(async ({ page }) => {
    await loginAsAdmin(page);
  });

  // T-01: Verify deploy config check API path and query params
  test('config check button requests GET /api/releases/deploy-config/check with appId and envId', async ({ page }) => {
    let requestedCorrectPath = false;
    let capturedAppId: string | null = null;
    let capturedEnvId: string | null = null;
    const orderNo = 'REL-CFG-001';

    await mockResourceApis(page);
    await mockReleaseList(page, [
      { id: 5001, orderNo, appId: 10, envId: 20, serviceId: 30, status: 'CREATED', hasDeployRecords: false, hasPipelineRun: false },
    ]);

    // Mock deploy config check API
    await page.route('**/api/releases/deploy-config/check**', async (route) => {
      const url = new URL(route.request().url());
      if (url.pathname === '/api/releases/deploy-config/check') {
        requestedCorrectPath = true;
        capturedAppId = url.searchParams.get('appId');
        capturedEnvId = url.searchParams.get('envId');
      }
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          code: 'SUCCESS',
          data: {
            appId: 10,
            appName: '测试应用',
            envId: 20,
            envName: '测试环境',
            overallComplete: true,
            services: [],
            missingKeys: [],
            warnings: [],
            checkedAt: '2026-05-20T10:00:00+08:00',
          },
        }),
      });
    });

    await openPage(page, '发布单');

    // Use filter bar to trigger mocked API call
    const filterBar = page.locator('.order-filter-bar');
    const keywordInput = filterBar.locator('input').first();
    await keywordInput.fill(orderNo);
    await filterBar.getByRole('button', { name: '查询', exact: true }).click();

    // Wait for mocked row to appear
    const row = page.locator('tbody tr').filter({ hasText: orderNo }).first();
    await expect(row).toBeVisible({ timeout: 15000 });

    // 第二批：配置检查作为次要动作收进行内「更多」菜单（显示条件与处理函数不变）
    await row.getByRole('button', { name: '更多', exact: true }).click();
    await page.getByRole('menuitem', { name: '配置检查', exact: true }).click();
    await page.waitForTimeout(2000);

    expect(requestedCorrectPath).toBe(true);
    expect(capturedAppId).toBe('10');
    expect(capturedEnvId).toBe('20');
  });

  // T-02: Verify deploy config check response contains all Contract-required fields
  test('config check dialog displays all required response fields per Contract 3.1', async ({ page }) => {
    const orderNo = 'REL-CFG-002';
    const mockResponse = {
      appId: 10,
      appName: '测试应用',
      envId: 20,
      envName: '测试环境',
      overallComplete: false,
      services: [
        {
          serviceId: 30,
          serviceName: '用户服务',
          complete: false,
          missingKeys: ['deploy.start.command', 'deploy.stop.command'],
          warnings: ['推荐配置 deploy.healthcheck.url 未设置'],
          configItems: [
            { configKey: 'deploy.package.path', configValue: '/opt/app/pkg', valueType: 'PLAIN', required: true, hasValue: true, source: 'APP' },
            { configKey: 'deploy.start.command', configValue: '', valueType: 'PLAIN', required: true, hasValue: false, source: 'APP' },
          ],
        },
      ],
      missingKeys: ['deploy.start.command', 'deploy.stop.command'],
      warnings: ['推荐配置 deploy.healthcheck.url 未设置'],
      checkedAt: '2026-05-20T10:00:00+08:00',
    };

    await mockResourceApis(page);
    await mockReleaseList(page, [
      { id: 5001, orderNo, appId: 10, envId: 20, status: 'CREATED', hasDeployRecords: false, hasPipelineRun: false },
    ]);

    await page.route('**/api/releases/deploy-config/check**', async (route) => {
      await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ code: 'SUCCESS', data: mockResponse }) });
    });

    await openPage(page, '发布单');

    const filterBar = page.locator('.order-filter-bar');
    const keywordInput = filterBar.locator('input').first();
    await keywordInput.fill(orderNo);
    await filterBar.getByRole('button', { name: '查询', exact: true }).click();

    const row = page.locator('tbody tr').filter({ hasText: orderNo }).first();
    await expect(row).toBeVisible({ timeout: 15000 });
    await row.getByRole('button', { name: '更多', exact: true }).click();
    await page.getByRole('menuitem', { name: '配置检查', exact: true }).click();

    const dialog = page.getByRole('dialog').filter({ hasText: '配置完整性检查' });
    await expect(dialog).toBeVisible({ timeout: 5000 });

    await expect(dialog).toContainText('配置检查未通过');
    await expect(dialog).toContainText('测试应用');
    await expect(dialog).toContainText('测试环境');
    await expect(dialog).toContainText('deploy.start.command');
    await expect(dialog).toContainText('deploy.stop.command');
    await expect(dialog).toContainText('推荐配置 deploy.healthcheck.url 未设置');
    await expect(dialog).toContainText('用户服务');
    await expect(dialog).toContainText('不完整');
  });

  // T-03: Verify overallComplete=true and passing state
  test('config check dialog shows passing state when overallComplete is true', async ({ page }) => {
    const orderNo = 'REL-CFG-003';
    const mockResponse = {
      appId: 10,
      appName: '完整应用',
      envId: 20,
      envName: '生产环境',
      overallComplete: true,
      services: [{ serviceId: 30, serviceName: '订单服务', complete: true, missingKeys: [], warnings: [], configItems: [{ configKey: 'deploy.package.path', configValue: '/opt/app/pkg', valueType: 'PLAIN', required: true, hasValue: true, source: 'APP' }] }],
      missingKeys: [],
      warnings: [],
      checkedAt: '2026-05-20T10:00:00+08:00',
    };

    await mockResourceApis(page);
    await mockReleaseList(page, [
      { id: 5001, orderNo, appId: 10, envId: 20, status: 'CREATED', hasDeployRecords: false, hasPipelineRun: false },
    ]);

    await page.route('**/api/releases/deploy-config/check**', async (route) => {
      await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ code: 'SUCCESS', data: mockResponse }) });
    });

    await openPage(page, '发布单');

    const filterBar = page.locator('.order-filter-bar');
    const keywordInput = filterBar.locator('input').first();
    await keywordInput.fill(orderNo);
    await filterBar.getByRole('button', { name: '查询', exact: true }).click();

    const row = page.locator('tbody tr').filter({ hasText: orderNo }).first();
    await expect(row).toBeVisible({ timeout: 15000 });
    await row.getByRole('button', { name: '更多', exact: true }).click();
    await page.getByRole('menuitem', { name: '配置检查', exact: true }).click();

    const dialog = page.getByRole('dialog').filter({ hasText: '配置完整性检查' });
    await expect(dialog).toBeVisible({ timeout: 5000 });

    await expect(dialog).toContainText('配置检查通过');
    await expect(dialog).toContainText('配置完整，可正常发布');
    await expect(dialog).toContainText('完整');
  });

  // Verify config check button hidden when no appId/envId
  test('config check button is hidden when row lacks appId or envId', async ({ page }) => {
    const orderNo = 'REL-NO-APP';

    await mockResourceApis(page);
    await mockReleaseList(page, [
      { id: 5002, orderNo, status: 'CREATED', hasDeployRecords: false, hasPipelineRun: false },
    ]);

    await openPage(page, '发布单');

    const filterBar = page.locator('.order-filter-bar');
    const keywordInput = filterBar.locator('input').first();
    await keywordInput.fill(orderNo);
    await filterBar.getByRole('button', { name: '查询', exact: true }).click();

    const row = page.locator('tbody tr').filter({ hasText: orderNo }).first();
    await expect(row).toBeVisible({ timeout: 15000 });

    // 第二批：配置检查收进「更多」菜单。缺 appId/envId 时该菜单项必须不存在，
    // 因此改为在打开的菜单内断言不存在（不放宽为"按钮不可见"）
    const moreTrigger = row.getByRole('button', { name: '更多', exact: true });
    await expect(moreTrigger).toBeVisible();
    await moreTrigger.click();
    await expect(page.getByRole('menuitem', { name: '配置检查', exact: true })).toHaveCount(0);
  });
});
