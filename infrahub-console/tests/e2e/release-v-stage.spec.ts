import { expect, test } from '@playwright/test';
import { loginAsAdmin } from './helpers/auth';
import { mockResourceApis, mockReleaseList, TEST_DATA } from './helpers/mocks';
import { openPage, activeDialog } from './helpers/ui';

// Helper: use filter bar to trigger mocked API call
async function filterByKeyword(page: import('@playwright/test').Page, keyword: string) {
  const filterBar = page.locator('.order-filter-bar');
  const keywordInput = filterBar.locator('input').first();
  await keywordInput.fill(keyword);
  await filterBar.getByRole('button', { name: '查询', exact: true }).click();
}

// Helper: mock release list + detail endpoint + canary plan
async function mockReleaseWithDetail(page: import('@playwright/test').Page, orderNo: string, overrides: Record<string, unknown> = {}) {
  await mockResourceApis(page);
  await mockReleaseList(page, [
    {
      id: overrides.id ?? 8101,
      orderNo,
      appId: 10,
      serviceId: 30,
      envId: 20,
      versionId: 1,
      releaseType: 'FULL',
      status: 'CREATED',
      hasDeployRecords: true,
      hasPrecheckRecords: true,
      hasPipelineRun: false,
      ...overrides,
    },
  ]);

  // Mock detail endpoint
  await page.route('**/api/releases/*/detail', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        code: 'SUCCESS',
        data: {
          id: overrides.id ?? 8101,
          orderNo,
          appId: 10,
          envId: 20,
          serviceId: 30,
          appName: '测试应用',
          serviceName: '测试服务',
          envName: '测试环境',
          versionNo: 'v1.0.0',
          status: 'CREATED',
          createdAt: '2026-05-29T10:00:00',
          timeline: [],
          executionHistory: [],
          ...overrides,
        },
      }),
    });
  });

  // Mock canary plan (return null for non-canary)
  await page.route('**/api/releases/*/canary-plan', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ code: 'SUCCESS', data: null }),
    });
  });
}

test.describe('V-B: Release order page interaction enhancement (Contract 3.2, 4.2, 7, 8)', () => {
  test.setTimeout(60000);

  test.beforeEach(async ({ page }) => {
    await loginAsAdmin(page);
  });

  // V-B-01: Precheck dialog shows structured summary with host summaries
  test('precheck dialog displays host summaries from precheck-summary API', async ({ page }) => {
    const orderNo = 'REL-VB-001';
    const orderId = 8101;

    await mockReleaseWithDetail(page, orderNo, { id: orderId });

    // Mock precheck API
    await page.route('**/api/releases/*/precheck', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          code: 'SUCCESS',
          data: [
            { success: true, hostId: 1, instanceId: 101, artifactUri: 's3://bucket/app-v1.jar', stdout: 'OK', stderr: '', errorMessage: '' },
            { success: false, hostId: 2, instanceId: 102, artifactUri: 's3://bucket/app-v1.jar', stdout: '', stderr: 'ERR', errorMessage: 'Connection refused' },
          ],
        }),
      });
    });

    // Mock precheck-summary API (Contract 3.2)
    await page.route('**/api/releases/*/precheck-summary', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          code: 'SUCCESS',
          data: {
            orderId,
            orderType: 'RELEASE',
            overallStatus: 'PARTIAL',
            totalHosts: 2,
            successHosts: 1,
            failedHosts: 1,
            totalSteps: 4,
            failedSteps: 1,
            hostSummaries: [
              { hostId: 1, hostName: 'host-01', status: 'SUCCESS', successSteps: 2, failedSteps: 0 },
              { hostId: 2, hostName: 'host-02', status: 'FAILED', successSteps: 1, failedSteps: 1, lastErrorMessage: 'Connection refused' },
            ],
            failureCategories: [
              { category: 'CONNECTIVITY', count: 1, message: '无法连接到目标主机' },
            ],
            suggestions: [
              { suggestionType: 'RETRY_PRECHECK', title: '重试预检', description: '网络恢复后重新执行预检' },
              { suggestionType: 'CONTACT_OWNER', title: '联系管理员', description: '请联系运维管理员检查网络连通性' },
            ],
            generatedAt: '2026-05-29T10:05:00',
          },
        }),
      });
    });

    await openPage(page, '发布单');
    await filterByKeyword(page, orderNo);

    const row = page.locator('tbody tr').filter({ hasText: orderNo }).first();
    await expect(row).toBeVisible({ timeout: 15000 });

    // Click precheck button
    await row.getByRole('button', { name: '前置检查', exact: true }).click();

    // Wait for dialog
    const dialog = activeDialog(page);
    await expect(dialog).toBeVisible({ timeout: 10000 });

    // Verify structured summary section exists
    await expect(dialog.locator('.structured-summary')).toBeVisible({ timeout: 10000 });

    // Verify host summaries tab
    await expect(dialog.getByText('主机摘要')).toBeVisible();
    await expect(dialog.getByText('host-01')).toBeVisible();
    await expect(dialog.getByText('host-02')).toBeVisible();

    // Switch to failure categories tab
    await dialog.getByText('失败分类').click();
    await expect(dialog.getByText('连通性问题')).toBeVisible();

    // Switch to suggestions tab
    await dialog.getByText('建议操作').click();
    await expect(dialog.getByText('重试预检').first()).toBeVisible();
    await expect(dialog.getByText('联系管理员').first()).toBeVisible();
  });

  // V-B-02: Execute confirm dialog shows execution context
  test('execute confirm dialog displays execution context with risk level and version compare', async ({ page }) => {
    const orderNo = 'REL-VB-002';
    const orderId = 8102;

    await mockReleaseWithDetail(page, orderNo, { id: orderId, status: 'APPROVED' });

    // Mock execution-context API (Contract 3.2)
    await page.route('**/api/releases/*/execution-context', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          code: 'SUCCESS',
          data: {
            orderId,
            orderType: 'RELEASE',
            appId: 10,
            appName: '测试应用',
            envId: 20,
            envName: '测试环境',
            serviceId: 30,
            serviceName: '测试服务',
            currentVersionId: 1001,
            currentVersionNo: 'v1.0.0',
            targetVersionId: 1002,
            targetVersionNo: 'v1.0.1',
            deployConfigComplete: true,
            precheckStatus: 'SUCCESS',
            riskLevel: 'MEDIUM',
            risks: [
              { riskType: 'VERSION_MISMATCH', level: 'MEDIUM', message: '版本跨度较大', suggestion: '建议逐步发布' },
            ],
            versionCompare: [
              { fieldName: 'deploy.package.path', currentValue: '/opt/old', targetValue: '/opt/new', changed: true },
              { fieldName: 'deploy.log.path', currentValue: '/var/log/app', targetValue: '/var/log/app', changed: false },
            ],
            generatedAt: '2026-05-29T10:10:00',
          },
        }),
      });
    });

    await openPage(page, '发布单');
    await filterByKeyword(page, orderNo);

    const row = page.locator('tbody tr').filter({ hasText: orderNo }).first();
    await expect(row).toBeVisible({ timeout: 15000 });

    // Click execute button
    await row.getByRole('button', { name: '执行发布', exact: true }).click();

    const dialog = activeDialog(page);
    await expect(dialog).toBeVisible({ timeout: 10000 });

    // Verify execution context section
    await expect(dialog.locator('.execution-context-section')).toBeVisible({ timeout: 10000 });

    // Verify status indicators
    await expect(dialog.getByText('配置完整性')).toBeVisible();
    await expect(dialog.getByText('完整', { exact: true })).toBeVisible();
    await expect(dialog.getByText('预检状态')).toBeVisible();
    await expect(dialog.getByText('通过', { exact: true })).toBeVisible();
    await expect(dialog.getByText('风险等级')).toBeVisible();
    await expect(dialog.getByText('中风险')).toBeVisible();

    // Verify risk items
    await expect(dialog.getByText('版本跨度较大')).toBeVisible();
    await expect(dialog.getByText('建议逐步发布')).toBeVisible();

    // Verify version comparison table
    await expect(dialog.getByText('版本对比')).toBeVisible();
    await expect(dialog.getByText('deploy.package.path')).toBeVisible();
    await expect(dialog.getByText('/opt/old')).toBeVisible();
    await expect(dialog.getByText('/opt/new')).toBeVisible();
    await expect(dialog.getByText('1 项配置未变更')).toBeVisible();
  });

  // V-B-03: Deploy config check dialog shows missing item guidance
  test('deploy config check dialog displays missing item guidance with jump entry', async ({ page }) => {
    const orderNo = 'REL-VB-003';
    const orderId = 8103;

    await mockReleaseWithDetail(page, orderNo, { id: orderId });

    // Mock deploy config check API with incomplete config
    await page.route('**/api/releases/deploy-config/check**', async (route) => {
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
            overallComplete: false,
            services: [
              {
                serviceId: 30,
                serviceName: '测试服务',
                complete: false,
                missingKeys: ['deploy.deploy.path'],
                warnings: [],
                configItems: [
                  { configKey: 'deploy.package.path', configValue: '/opt/packages', valueType: 'PLAIN', required: true, hasValue: true, source: 'APP' },
                  { configKey: 'deploy.deploy.path', configValue: '', valueType: 'PLAIN', required: true, hasValue: false, source: 'SERVICE' },
                ],
              },
            ],
            missingKeys: ['deploy.restart.command'],
            warnings: [],
            checkedAt: '2026-05-29T10:00:00+08:00',
          },
        }),
      });
    });

    await openPage(page, '发布单');
    await filterByKeyword(page, orderNo);

    const row = page.locator('tbody tr').filter({ hasText: orderNo }).first();
    await expect(row).toBeVisible({ timeout: 15000 });

    // 第二批：配置检查收进行内「更多」菜单（显示条件与处理函数不变）
    await row.getByRole('button', { name: '更多', exact: true }).click();
    await page.getByRole('menuitem', { name: '配置检查', exact: true }).click();

    const dialog = activeDialog(page);
    await expect(dialog).toBeVisible({ timeout: 10000 });

    // Verify missing guide section exists
    await expect(dialog.locator('.missing-guide-section')).toBeVisible({ timeout: 10000 });

    // Verify guidance text for known config keys
    await expect(dialog.getByText('应用重启命令，在部署完成后执行的服务重启操作')).toBeVisible();
    await expect(dialog.getByText('实际部署路径')).toBeVisible();

    // Verify "前往配置" button exists
    await expect(dialog.getByRole('button', { name: '前往配置' }).first()).toBeVisible();
  });

  // V-B-04: Release detail drawer shows cross-navigation entries
  test('release detail drawer displays cross-navigation buttons for runtime instances and current versions', async ({ page }) => {
    const orderNo = 'REL-VB-004';
    const orderId = 8104;

    await mockReleaseWithDetail(page, orderNo, { id: orderId });

    await openPage(page, '发布单');
    await filterByKeyword(page, orderNo);

    const row = page.locator('tbody tr').filter({ hasText: orderNo }).first();
    await expect(row).toBeVisible({ timeout: 15000 });

    // Click detail button
    await row.getByRole('button', { name: '详情' }).click();

    const drawer = page.locator('.el-drawer');
    await expect(drawer).toBeVisible({ timeout: 10000 });

    // Verify cross-navigation buttons exist
    await expect(drawer.getByRole('button', { name: '运行实例' })).toBeVisible();
    await expect(drawer.getByRole('button', { name: '当前版本' })).toBeVisible();

    // Verify existing buttons still present
    await expect(drawer.getByRole('button', { name: '前置检查' })).toBeVisible();
    await expect(drawer.getByRole('button', { name: '部署记录' })).toBeVisible();
    await expect(drawer.getByRole('button', { name: '配置检查' })).toBeVisible();
  });

  // V-B-05: Navigate to runtime instances from detail drawer
  test('clicking runtime instances button navigates with correct query params', async ({ page }) => {
    const orderNo = 'REL-VB-005';
    const orderId = 8105;

    await mockReleaseWithDetail(page, orderNo, { id: orderId });

    await openPage(page, '发布单');
    await filterByKeyword(page, orderNo);

    const row = page.locator('tbody tr').filter({ hasText: orderNo }).first();
    await expect(row).toBeVisible({ timeout: 15000 });

    await row.getByRole('button', { name: '详情' }).click();

    const drawer = page.locator('.el-drawer');
    await expect(drawer).toBeVisible({ timeout: 10000 });

    // Click runtime instances navigation
    await drawer.getByRole('button', { name: '运行实例' }).click();

    // Verify URL contains expected query params
    await page.waitForURL('**/resource/runtime-instances**', { timeout: 10000 });
    const url = page.url();
    expect(url).toContain('appId=10');
    expect(url).toContain('envId=20');
  });

  // V-B-06: Navigate to current versions from detail drawer
  test('clicking current versions button navigates with correct query params', async ({ page }) => {
    const orderNo = 'REL-VB-006';
    const orderId = 8106;

    await mockReleaseWithDetail(page, orderNo, { id: orderId });

    await openPage(page, '发布单');
    await filterByKeyword(page, orderNo);

    const row = page.locator('tbody tr').filter({ hasText: orderNo }).first();
    await expect(row).toBeVisible({ timeout: 15000 });

    await row.getByRole('button', { name: '详情' }).click();

    const drawer = page.locator('.el-drawer');
    await expect(drawer).toBeVisible({ timeout: 10000 });

    // Click current versions navigation
    await drawer.getByRole('button', { name: '当前版本' }).click();

    // Verify URL contains expected query params
    await page.waitForURL('**/release/current-versions**', { timeout: 10000 });
    const url = page.url();
    expect(url).toContain('appId=10');
    expect(url).toContain('envId=20');
  });

  // V-B-07: Execute confirm dialog gracefully handles missing execution context
  test('execute confirm dialog shows basic info when execution context API fails', async ({ page }) => {
    const orderNo = 'REL-VB-007';
    const orderId = 8107;

    await mockReleaseWithDetail(page, orderNo, { id: orderId, status: 'APPROVED' });

    // Mock execution-context API to fail
    await page.route('**/api/releases/*/execution-context', async (route) => {
      await route.fulfill({
        status: 500,
        contentType: 'application/json',
        body: JSON.stringify({ code: 'ERROR', message: 'Internal error' }),
      });
    });

    await openPage(page, '发布单');
    await filterByKeyword(page, orderNo);

    const row = page.locator('tbody tr').filter({ hasText: orderNo }).first();
    await expect(row).toBeVisible({ timeout: 15000 });

    // Click execute button - should not crash
    await row.getByRole('button', { name: '执行发布', exact: true }).click();

    const dialog = activeDialog(page);
    await expect(dialog).toBeVisible({ timeout: 10000 });

    // Basic summary should still be visible
    await expect(dialog.getByText('发布单汇总')).toBeVisible();
    await expect(dialog.getByText('确认执行发布')).toBeVisible();
  });
});
