import { expect, test } from '@playwright/test';
import { loginAsAdmin } from './helpers/auth';
import { mockResourceApis, mockReleaseAndRollbackApis } from './helpers/mocks';
import { openPage } from './helpers/ui';

async function filterByKeyword(page: import('@playwright/test').Page, keyword: string) {
  const filterBar = page.locator('.order-filter-bar');
  const keywordInput = filterBar.locator('input').first();
  await keywordInput.fill(keyword);
  await filterBar.getByRole('button', { name: '查询', exact: true }).click();
}

// V-C: Mock rollback execution-context API per Contract 3.2
async function mockRollbackExecutionContext(
  page: import('@playwright/test').Page,
  rollbackId: number,
  data: Record<string, unknown>,
) {
  await page.route(`**/api/rollbacks/${rollbackId}/execution-context`, async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ code: 'SUCCESS', data }),
    });
  });
}

// V-C: Mock rollback precheck-summary API per Contract 3.2
async function mockRollbackPrecheckSummary(
  page: import('@playwright/test').Page,
  rollbackId: number,
  data: Record<string, unknown>,
) {
  await page.route(`**/api/rollbacks/${rollbackId}/precheck-summary`, async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ code: 'SUCCESS', data }),
    });
  });
}

test.describe('V-C rollback execution context (Contract 3.2, 7)', () => {
  test.setTimeout(90000);

  test.beforeEach(async ({ page }) => {
    await loginAsAdmin(page);
    await mockResourceApis(page);
  });

  // V-C-01: Verify execution-context API path
  test('execution confirm requests GET /api/rollbacks/{id}/execution-context', async ({ page }) => {
    let requestedCorrectPath = false;
    const rollbackId = 8001;
    const rollbackNo = 'RB-VCTX-001';

    await mockReleaseAndRollbackApis(page, [], [
      { id: rollbackId, rollbackNo, status: 'APPROVED', appId: 1, envId: 20, appName: '测试应用', envName: '测试环境', fromVersionNo: 'v2.0.0', toVersionNo: 'v1.0.0', executorType: 'SSH', rollbackSupported: true },
    ]);

    await page.route(`**/api/rollbacks/${rollbackId}/execution-context`, async (route) => {
      const url = new URL(route.request().url());
      if (url.pathname === `/api/rollbacks/${rollbackId}/execution-context`) {
        requestedCorrectPath = true;
      }
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          code: 'SUCCESS',
          data: {
            orderId: rollbackId,
            orderType: 'ROLLBACK',
            appId: 1,
            appName: '测试应用',
            envId: 20,
            envName: '测试环境',
            deployConfigComplete: true,
            precheckStatus: 'SUCCESS',
            riskLevel: 'LOW',
            risks: [],
            versionCompare: [],
            generatedAt: '2026-05-29T10:00:00',
          },
        }),
      });
    });

    await openPage(page, '回滚单');
    await filterByKeyword(page, rollbackNo);

    const row = page.locator('tbody tr').filter({ hasText: rollbackNo }).first();
    await expect(row).toBeVisible({ timeout: 15000 });

    await row.getByRole('button', { name: '执行回滚', exact: true }).click();

    const dialog = page.getByRole('dialog').filter({ hasText: '确认执行回滚' });
    await expect(dialog).toBeVisible({ timeout: 5000 });

    // Wait for context to load
    await page.waitForTimeout(2000);
    expect(requestedCorrectPath).toBe(true);
  });

  // V-C-02: Execution dialog shows config completeness, precheck status, risk level
  test('execution dialog displays config completeness, precheck status, and risk level', async ({ page }) => {
    const rollbackId = 8002;
    const rollbackNo = 'RB-VCTX-002';

    await mockReleaseAndRollbackApis(page, [], [
      { id: rollbackId, rollbackNo, status: 'APPROVED', appId: 1, envId: 20, appName: '测试应用', envName: '测试环境', fromVersionNo: 'v2.0.0', toVersionNo: 'v1.0.0', executorType: 'SSH', rollbackSupported: true },
    ]);

    await mockRollbackExecutionContext(page, rollbackId, {
      orderId: rollbackId,
      orderType: 'ROLLBACK',
      appId: 1,
      appName: '测试应用',
      envId: 20,
      envName: '测试环境',
      deployConfigComplete: true,
      precheckStatus: 'PARTIAL',
      riskLevel: 'MEDIUM',
      risks: [
        { riskType: 'PRECHECK_FAILED', level: 'MEDIUM', message: '2 个实例预检未通过', suggestion: '建议重新执行预检' },
      ],
      versionCompare: [
        { fieldName: 'deploy.package.path', currentValue: '/opt/v2/pkg.tar.gz', targetValue: '/opt/v1/pkg.tar.gz', changed: true },
        { fieldName: 'deploy.deploy.path', currentValue: '/opt/app', targetValue: '/opt/app', changed: false },
      ],
      generatedAt: '2026-05-29T10:00:00',
    });

    await openPage(page, '回滚单');
    await filterByKeyword(page, rollbackNo);

    const row = page.locator('tbody tr').filter({ hasText: rollbackNo }).first();
    await expect(row).toBeVisible({ timeout: 15000 });
    await row.getByRole('button', { name: '执行回滚', exact: true }).click();

    const dialog = page.getByRole('dialog').filter({ hasText: '确认执行回滚' });
    await expect(dialog).toBeVisible({ timeout: 5000 });

    // Wait for execution context to load
    await expect(dialog.getByText('配置完整性')).toBeVisible({ timeout: 10000 });
    await expect(dialog.getByText('完整', { exact: true })).toBeVisible();
    await expect(dialog.getByText('预检状态')).toBeVisible();
    await expect(dialog.getByText('部分通过', { exact: true })).toBeVisible();
    await expect(dialog.getByText('风险等级')).toBeVisible();
    await expect(dialog.getByText('中风险', { exact: true })).toBeVisible();
  });

  // V-C-03: Execution dialog shows risk items and version comparison
  test('execution dialog displays risk items and version comparison table', async ({ page }) => {
    const rollbackId = 8003;
    const rollbackNo = 'RB-VCTX-003';

    await mockReleaseAndRollbackApis(page, [], [
      { id: rollbackId, rollbackNo, status: 'APPROVED', appId: 1, envId: 20, appName: '测试应用', envName: '测试环境', fromVersionNo: 'v2.0.0', toVersionNo: 'v1.0.0', executorType: 'SSH', rollbackSupported: true },
    ]);

    await mockRollbackExecutionContext(page, rollbackId, {
      orderId: rollbackId,
      orderType: 'ROLLBACK',
      appId: 1,
      appName: '测试应用',
      envId: 20,
      envName: '测试环境',
      deployConfigComplete: false,
      precheckStatus: 'FAILED',
      riskLevel: 'HIGH',
      risks: [
        { riskType: 'CONFIG_MISSING', level: 'HIGH', message: 'deploy.package.path 未配置', suggestion: '请先完善部署配置' },
        { riskType: 'PRECHECK_FAILED', level: 'MEDIUM', message: '3 个实例预检失败', suggestion: '检查实例状态' },
      ],
      versionCompare: [
        { fieldName: 'deploy.package.path', currentValue: '/opt/v2/pkg.tar.gz', targetValue: '/opt/v1/pkg.tar.gz', changed: true },
        { fieldName: 'deploy.deploy.path', currentValue: '/opt/app', targetValue: '/opt/app', changed: false },
      ],
      generatedAt: '2026-05-29T10:00:00',
    });

    await openPage(page, '回滚单');
    await filterByKeyword(page, rollbackNo);

    const row = page.locator('tbody tr').filter({ hasText: rollbackNo }).first();
    await expect(row).toBeVisible({ timeout: 15000 });
    await row.getByRole('button', { name: '执行回滚', exact: true }).click();

    const dialog = page.getByRole('dialog').filter({ hasText: '确认执行回滚' });
    await expect(dialog).toBeVisible({ timeout: 5000 });

    // Wait for context data
    await expect(dialog.getByText('风险提示')).toBeVisible({ timeout: 10000 });
    await expect(dialog.getByText('deploy.package.path 未配置')).toBeVisible();
    await expect(dialog.getByText('请先完善部署配置')).toBeVisible();

    // Version comparison table
    await expect(dialog.getByText('版本配置对比')).toBeVisible();
    await expect(dialog.getByText('/opt/v2/pkg.tar.gz')).toBeVisible();
    await expect(dialog.getByText('/opt/v1/pkg.tar.gz')).toBeVisible();
    await expect(dialog.getByText('变更').nth(1)).toBeVisible(); // tag, not column header
  });

  // V-C-04: HIGH risk disables confirm button
  test('execution dialog disables confirm when risk level is HIGH', async ({ page }) => {
    const rollbackId = 8004;
    const rollbackNo = 'RB-VCTX-004';

    await mockReleaseAndRollbackApis(page, [], [
      { id: rollbackId, rollbackNo, status: 'APPROVED', appId: 1, envId: 20, appName: '测试应用', envName: '测试环境', fromVersionNo: 'v2.0.0', toVersionNo: 'v1.0.0', executorType: 'SSH', rollbackSupported: true },
    ]);

    await mockRollbackExecutionContext(page, rollbackId, {
      orderId: rollbackId,
      orderType: 'ROLLBACK',
      appId: 1,
      appName: '测试应用',
      envId: 20,
      envName: '测试环境',
      deployConfigComplete: false,
      precheckStatus: 'FAILED',
      riskLevel: 'HIGH',
      risks: [
        { riskType: 'CONFIG_MISSING', level: 'HIGH', message: '部署配置缺失', suggestion: '请先完善部署配置' },
      ],
      versionCompare: [],
      generatedAt: '2026-05-29T10:00:00',
    });

    await openPage(page, '回滚单');
    await filterByKeyword(page, rollbackNo);

    const row = page.locator('tbody tr').filter({ hasText: rollbackNo }).first();
    await expect(row).toBeVisible({ timeout: 15000 });
    await row.getByRole('button', { name: '执行回滚', exact: true }).click();

    const dialog = page.getByRole('dialog').filter({ hasText: '确认执行回滚' });
    await expect(dialog).toBeVisible({ timeout: 5000 });

    // Wait for context to load and button to become disabled
    await expect(dialog.getByText('风险等级')).toBeVisible({ timeout: 10000 });
    const confirmBtn = dialog.getByRole('button', { name: '确认执行' });
    await expect(confirmBtn).toBeDisabled();
  });
});

test.describe('V-C rollback precheck-summary (Contract 3.2, 7)', () => {
  test.setTimeout(90000);

  test.beforeEach(async ({ page }) => {
    await loginAsAdmin(page);
    await mockResourceApis(page);
  });

  // V-C-05: Verify precheck-summary API path
  test('precheck-summary requests GET /api/rollbacks/{id}/precheck-summary', async ({ page }) => {
    let requestedCorrectPath = false;
    const rollbackId = 8101;
    const rollbackNo = 'RB-VPS-001';

    await mockReleaseAndRollbackApis(page, [], [
      { id: rollbackId, rollbackNo, status: 'APPROVED', appId: 1, envId: 20, executorType: 'SSH', rollbackSupported: true },
    ]);

    await page.route(`**/api/rollbacks/${rollbackId}/precheck-summary`, async (route) => {
      const url = new URL(route.request().url());
      if (url.pathname === `/api/rollbacks/${rollbackId}/precheck-summary`) {
        requestedCorrectPath = true;
      }
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          code: 'SUCCESS',
          data: {
            orderId: rollbackId,
            orderType: 'ROLLBACK',
            overallStatus: 'PARTIAL',
            totalHosts: 2,
            successHosts: 1,
            failedHosts: 1,
            totalSteps: 6,
            failedSteps: 2,
            hostSummaries: [
              { hostId: 1, hostName: 'host-1', instanceId: 101, instanceName: 'inst-1', status: 'SUCCESS', successSteps: 3, failedSteps: 0 },
              { hostId: 2, hostName: 'host-2', instanceId: 102, instanceName: 'inst-2', status: 'FAILED', successSteps: 1, failedSteps: 2, lastErrorMessage: '连接超时' },
            ],
            failureCategories: [
              { category: 'CONNECTIVITY', count: 1, message: 'SSH 连接失败' },
              { category: 'RESOURCE', count: 1, message: '磁盘空间不足' },
            ],
            suggestions: [
              { suggestionType: 'RETRY_PRECHECK', title: '重新执行预检', description: '建议检查网络后重试' },
              { suggestionType: 'CHECK_INSTANCE', title: '检查实例状态', targetRoute: '/resource/runtime-instances', targetParams: { appId: '1', envId: '20' } },
            ],
            generatedAt: '2026-05-29T10:00:00',
          },
        }),
      });
    });

    await openPage(page, '回滚单');
    await filterByKeyword(page, rollbackNo);

    const row = page.locator('tbody tr').filter({ hasText: rollbackNo }).first();
    await expect(row).toBeVisible({ timeout: 15000 });
    await row.getByRole('button', { name: '前置检查', exact: true }).click();

    const dialog = page.getByRole('dialog').filter({ hasText: '前置检查' });
    await expect(dialog).toBeVisible({ timeout: 5000 });

    await page.waitForTimeout(2000);
    expect(requestedCorrectPath).toBe(true);
  });
});

test.describe('V-C rollback candidate detail panel (Contract 9.2)', () => {
  test.setTimeout(90000);

  test.beforeEach(async ({ page }) => {
    await loginAsAdmin(page);
    await mockResourceApis(page);
  });

  // V-C-06: Candidate dialog shows detail panel with recommendation reason
  test('candidate dialog shows detail panel when candidate selected', async ({ page }) => {
    const releaseOrderId = 8201;
    const rollbackId = 8202;
    const rollbackNo = 'RB-VCAND-001';

    await mockReleaseAndRollbackApis(page, [], [
      { id: rollbackId, rollbackNo, releaseOrderId, status: 'CREATED', currentVersionNo: 'v2.0.0', fromVersionNo: 'v2.0.0', executorType: 'SSH', rollbackSupported: true },
    ]);

    await page.route(`**/api/releases/${releaseOrderId}/rollback-candidates**`, async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          code: 'SUCCESS',
          data: {
            records: [
              { versionId: 501, versionNo: 'v1.5.0', deployedAt: '2026-05-10T10:00:00+08:00', rollbackCount: 0, isCurrent: false, recommendationScore: 85, recommendationReason: '最近一次成功部署版本，运行稳定' },
              { versionId: 502, versionNo: 'v2.0.0', deployedAt: '2026-05-18T10:00:00+08:00', rollbackCount: 0, isCurrent: true, recommendationScore: 0, recommendationReason: '当前版本' },
            ],
            total: 2,
            pageNum: 1,
            pageSize: 20,
            currentVersionId: 502,
            currentVersionNo: 'v2.0.0',
          },
        }),
      });
    });

    await openPage(page, '回滚单');
    await filterByKeyword(page, rollbackNo);

    const row = page.locator('tbody tr').filter({ hasText: rollbackNo }).first();
    await expect(row).toBeVisible({ timeout: 15000 });
    await row.getByRole('button', { name: '候选版本', exact: true }).click();

    const dialog = page.getByRole('dialog').filter({ hasText: '选择回滚候选版本' });
    await expect(dialog).toBeVisible({ timeout: 5000 });

    // Select the non-current candidate
    const candidateRow = dialog.locator('tbody tr').filter({ hasText: 'v1.5.0' }).first();
    await candidateRow.click();

    // V-C: Detail panel should appear with full recommendation reason
    await expect(dialog.getByText('候选版本详情')).toBeVisible({ timeout: 5000 });
    await expect(dialog.getByText('推荐理由').nth(1)).toBeVisible(); // detail panel, not column header
    await expect(dialog.getByText('最近一次成功部署版本，运行稳定').nth(1)).toBeVisible(); // detail panel, not table cell
    await expect(dialog.getByText('推荐', { exact: true })).toBeVisible(); // score >= 80 tag
    await expect(dialog.getByText('85', { exact: true })).toBeVisible();
  });

  // V-C-07: Candidate dialog shows current→target comparison
  test('candidate dialog shows current-to-target version comparison', async ({ page }) => {
    const releaseOrderId = 8203;
    const rollbackId = 8204;
    const rollbackNo = 'RB-VCAND-002';

    await mockReleaseAndRollbackApis(page, [], [
      { id: rollbackId, rollbackNo, releaseOrderId, status: 'CREATED', currentVersionNo: 'v3.0.0', fromVersionNo: 'v3.0.0', executorType: 'SSH', rollbackSupported: true },
    ]);

    await page.route(`**/api/releases/${releaseOrderId}/rollback-candidates**`, async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          code: 'SUCCESS',
          data: {
            records: [
              { versionId: 601, versionNo: 'v1.0.0', deployedAt: '2026-04-01T10:00:00+08:00', rollbackCount: 2, isCurrent: false, recommendationScore: 45, recommendationReason: '历史回滚较多' },
              { versionId: 602, versionNo: 'v3.0.0', deployedAt: '2026-05-20T10:00:00+08:00', rollbackCount: 0, isCurrent: true, recommendationScore: 0, recommendationReason: '当前版本' },
            ],
            total: 2,
            pageNum: 1,
            pageSize: 20,
            currentVersionId: 602,
            currentVersionNo: 'v3.0.0',
          },
        }),
      });
    });

    await openPage(page, '回滚单');
    await filterByKeyword(page, rollbackNo);

    const row = page.locator('tbody tr').filter({ hasText: rollbackNo }).first();
    await expect(row).toBeVisible({ timeout: 15000 });
    await row.getByRole('button', { name: '候选版本', exact: true }).click();

    const dialog = page.getByRole('dialog').filter({ hasText: '选择回滚候选版本' });
    await expect(dialog).toBeVisible({ timeout: 5000 });

    // Select the low-score candidate
    const candidateRow = dialog.locator('tbody tr').filter({ hasText: 'v1.0.0' }).first();
    await candidateRow.click();

    // Detail panel shows comparison
    await expect(dialog.getByText('候选版本详情')).toBeVisible({ timeout: 5000 });
    await expect(dialog.locator('.compare-label').filter({ hasText: '当前版本' })).toBeVisible();
    await expect(dialog.getByText('回滚目标')).toBeVisible();
    await expect(dialog.getByText('不推荐', { exact: true })).toBeVisible(); // score < 50
    await expect(dialog.getByText('2 次')).toBeVisible(); // rollbackCount display
  });
});

test.describe('V-C navigateToCurrentVersion (Contract 7, 8)', () => {
  test.setTimeout(90000);

  test.beforeEach(async ({ page }) => {
    await loginAsAdmin(page);
    await mockResourceApis(page);
  });

  // V-C-08: "当前版本" button navigates to current-versions page
  test('rollback list shows "当前版本" navigation button and navigates correctly', async ({ page }) => {
    const rollbackNo = 'RB-VNAV-001';

    await mockReleaseAndRollbackApis(page, [], [
      { id: 8301, rollbackNo, status: 'SUCCESS', appId: 1, envId: 20, appName: '测试应用', fromVersionNo: 'v2.0.0', toVersionNo: 'v1.0.0' },
    ]);

    await openPage(page, '回滚单');
    await filterByKeyword(page, rollbackNo);

    const row = page.locator('tbody tr').filter({ hasText: rollbackNo }).first();
    await expect(row).toBeVisible({ timeout: 15000 });

    // V-C: "当前版本" button should be visible
    const currentVersionBtn = row.getByRole('button', { name: '当前版本', exact: true });
    await expect(currentVersionBtn).toBeVisible();

    // Click and verify navigation
    await currentVersionBtn.click();
    await page.waitForTimeout(1000);

    // Verify URL contains current-versions path with appId and envId
    const url = page.url();
    expect(url).toContain('current-versions');
    expect(url).toContain('appId=1');
    expect(url).toContain('envId=20');
  });
});
