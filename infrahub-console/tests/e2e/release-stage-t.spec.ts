import { expect, test } from '@playwright/test';
import { loginAsAdmin } from './helpers/auth';
import { mockResourceApis, mockReleaseList, mockRollbackList, TEST_DATA } from './helpers/mocks';
import { openPage, activeDialog, chooseSelectOption } from './helpers/ui';

// Helper: use filter bar to trigger mocked API call
async function filterByKeyword(page: import('@playwright/test').Page, keyword: string) {
  const filterBar = page.locator('.order-filter-bar');
  const keywordInput = filterBar.locator('input').first();
  await keywordInput.fill(keyword);
  await filterBar.getByRole('button', { name: '查询', exact: true }).click();
}

// Helper: mock release list with a single row + detail endpoint
async function mockReleaseWithDetail(page: import('@playwright/test').Page, orderNo: string, overrides: Record<string, unknown> = {}) {
  await mockResourceApis(page);
  await mockReleaseList(page, [
    {
      id: overrides.id ?? 8001,
      orderNo,
      appId: 10,
      serviceId: 20,
      envId: 30,
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
    const defaultTimeline = [
      { nodeType: 'CREATED', timestamp: '2026-05-20T10:00:00', operator: 'admin', description: '发布单创建' },
      { nodeType: 'PRECHECK_SUCCESS', timestamp: '2026-05-20T10:05:00', operator: 'system', description: '前置检查成功' },
    ];
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        code: 'SUCCESS',
        data: {
          id: overrides.id ?? 8001,
          orderNo,
          appName: '测试应用',
          serviceName: '测试服务',
          envName: '测试环境',
          versionNo: 'v1.0.0',
          status: 'CREATED',
          createdAt: '2026-05-20T10:00:00',
          timeline: (overrides.timeline as unknown[]) ?? defaultTimeline,
          executionHistory: [],
          ...overrides,
        },
      }),
    });
  });
}

// Helper: mock rollback list with a single row + detail endpoint
// 正向 fixture 必须显式声明执行器类型与后端回滚支持判定：
// 缺失这两个字段时页面会按 fail-closed 判为“支持状态未知”并禁用执行，属于测试夹具缺口而非产品缺陷。
async function mockRollbackWithDetail(page: import('@playwright/test').Page, rollbackNo: string, overrides: Record<string, unknown> = {}) {
  await mockResourceApis(page);
  await mockRollbackList(page, [
    {
      id: overrides.id ?? 9001,
      rollbackNo,
      releaseOrderId: 100,
      status: 'CREATED',
      currentVersionNo: 'v2.0.0',
      fromVersionNo: 'v2.0.0',
      toVersionNo: 'v1.0.0',
      hasDeployRecords: true,
      hasPrecheckRecords: true,
      executorType: 'SSH',
      rollbackSupported: true,
      ...overrides,
    },
  ]);

  // Mock detail endpoint
  await page.route('**/api/rollbacks/*/detail', async (route) => {
    const defaultTimeline = [
      { nodeType: 'CREATED', timestamp: '2026-05-20T11:00:00', operator: 'admin', description: '回滚单创建' },
      { nodeType: 'EXECUTE_START', timestamp: '2026-05-20T11:10:00', operator: 'admin', description: '开始执行回滚' },
    ];
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        code: 'SUCCESS',
        data: {
          id: overrides.id ?? 9001,
          rollbackNo,
          appName: '测试应用',
          serviceName: '测试服务',
          envName: '测试环境',
          fromVersionNo: 'v2.0.0',
          toVersionNo: 'v1.0.0',
          status: 'CREATED',
          createdAt: '2026-05-20T10:00:00',
          timeline: (overrides.timeline as unknown[]) ?? defaultTimeline,
          executionHistory: [],
          executorType: 'SSH',
          rollbackSupported: true,
          ...overrides,
        },
      }),
    });
  });
}

// ============================================================
// T-C: Release execute confirmation (Contract 8.5)
// ============================================================
test.describe('T-C release execute confirmation (Contract 8.5)', () => {
  test.setTimeout(90000);

  test.beforeEach(async ({ page }) => {
    await loginAsAdmin(page);
  });

  test('clicking execute release opens confirmation dialog first', async ({ page }) => {
    const orderNo = 'REL-EXEC-001';
    await mockReleaseWithDetail(page, orderNo);

    await openPage(page, '发布单');
    await filterByKeyword(page, orderNo);

    const row = page.locator('tbody tr').filter({ hasText: orderNo }).first();
    await expect(row).toBeVisible({ timeout: 15000 });
    await row.getByRole('button', { name: '执行发布', exact: true }).click();

    const dialog = page.getByRole('dialog').filter({ hasText: '确认执行发布' });
    await expect(dialog).toBeVisible({ timeout: 5000 });
  });

  test('execute confirmation dialog contains warning about actual deployment', async ({ page }) => {
    const orderNo = 'REL-EXEC-002';
    await mockReleaseWithDetail(page, orderNo);

    await openPage(page, '发布单');
    await filterByKeyword(page, orderNo);

    const row = page.locator('tbody tr').filter({ hasText: orderNo }).first();
    await expect(row).toBeVisible({ timeout: 15000 });
    await row.getByRole('button', { name: '执行发布', exact: true }).click();

    const dialog = page.getByRole('dialog').filter({ hasText: '确认执行发布' });
    await expect(dialog).toBeVisible({ timeout: 5000 });

    await expect(dialog).toContainText('此操作将实际执行部署脚本');
    await expect(dialog).toContainText('请确认已通过预检');
  });

  test('execute confirmation dialog displays release order summary', async ({ page }) => {
    const orderNo = 'REL-EXEC-003';
    await mockReleaseWithDetail(page, orderNo);

    await openPage(page, '发布单');
    await filterByKeyword(page, orderNo);

    const row = page.locator('tbody tr').filter({ hasText: orderNo }).first();
    await expect(row).toBeVisible({ timeout: 15000 });
    await row.getByRole('button', { name: '执行发布', exact: true }).click();

    const dialog = page.getByRole('dialog').filter({ hasText: '确认执行发布' });
    await expect(dialog).toBeVisible({ timeout: 5000 });

    await expect(dialog).toContainText(orderNo);
  });

  test('execute API is NOT called before clicking confirm', async ({ page }) => {
    const orderNo = 'REL-EXEC-004';
    const releaseId = 8004;
    let executeCalled = false;

    await mockReleaseWithDetail(page, orderNo, { id: releaseId });
    await page.route(`**/api/releases/${releaseId}/execute`, async (route) => {
      executeCalled = true;
      await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ code: 'SUCCESS', data: true }) });
    });

    await openPage(page, '发布单');
    await filterByKeyword(page, orderNo);

    const row = page.locator('tbody tr').filter({ hasText: orderNo }).first();
    await expect(row).toBeVisible({ timeout: 15000 });
    await row.getByRole('button', { name: '执行发布', exact: true }).click();

    const dialog = page.getByRole('dialog').filter({ hasText: '确认执行发布' });
    await expect(dialog).toBeVisible({ timeout: 5000 });

    await page.waitForTimeout(1000);
    expect(executeCalled).toBe(false);
  });

  test('execute API is called only after clicking confirm button', async ({ page }) => {
    const orderNo = 'REL-EXEC-005';
    const releaseId = 8005;
    let executeCalled = false;

    await mockReleaseWithDetail(page, orderNo, { id: releaseId });
    await page.route(`**/api/releases/${releaseId}/execute`, async (route) => {
      executeCalled = true;
      await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ code: 'SUCCESS', data: true }) });
    });

    await openPage(page, '发布单');
    await filterByKeyword(page, orderNo);

    const row = page.locator('tbody tr').filter({ hasText: orderNo }).first();
    await expect(row).toBeVisible({ timeout: 15000 });
    await row.getByRole('button', { name: '执行发布', exact: true }).click();

    const dialog = page.getByRole('dialog').filter({ hasText: '确认执行发布' });
    await expect(dialog).toBeVisible({ timeout: 5000 });

    // ExecuteConfirmDialog requires checkbox to be checked before confirm button is enabled
    await dialog.locator('.el-checkbox').click();
    await dialog.getByRole('button', { name: '确认执行', exact: true }).click();
    await page.waitForTimeout(1000);

    expect(executeCalled).toBe(true);
  });

  // T-15: ExecuteConfirmDialog shows warning about actual deployment (no explicit MANUAL_ONLY text)
  test('execute confirmation dialog shows deployment warning and checkbox requirement', async ({ page }) => {
    const orderNo = 'REL-EXEC-006';
    await mockReleaseWithDetail(page, orderNo);

    await openPage(page, '发布单');
    await filterByKeyword(page, orderNo);

    const row = page.locator('tbody tr').filter({ hasText: orderNo }).first();
    await expect(row).toBeVisible({ timeout: 15000 });
    await row.getByRole('button', { name: '执行发布', exact: true }).click();

    const dialog = page.getByRole('dialog').filter({ hasText: '确认执行发布' });
    await expect(dialog).toBeVisible({ timeout: 5000 });

    // ExecuteConfirmDialog uses checkbox pattern for MANUAL_ONLY enforcement
    await expect(dialog).toContainText('我已确认将通过前置检查');
    await expect(dialog).toContainText('此操作将执行真实部署');

    // Confirm button is disabled until checkbox is checked
    const confirmBtn = dialog.getByRole('button', { name: '确认执行', exact: true });
    await expect(confirmBtn).toBeDisabled();
  });
});

// ============================================================
// T-D: Rollback execute confirmation (Contract 8.5)
// ============================================================
test.describe('T-D rollback execute confirmation (Contract 8.5)', () => {
  test.setTimeout(90000);

  test.beforeEach(async ({ page }) => {
    await loginAsAdmin(page);
  });

  test('clicking execute rollback opens confirmation dialog first', async ({ page }) => {
    const rollbackNo = 'RB-EXEC-001';
    await mockRollbackWithDetail(page, rollbackNo);

    await openPage(page, '回滚单');
    await filterByKeyword(page, rollbackNo);

    const row = page.locator('tbody tr').filter({ hasText: rollbackNo }).first();
    await expect(row).toBeVisible({ timeout: 15000 });
    await row.getByRole('button', { name: '执行回滚', exact: true }).click();

    const dialog = page.getByRole('dialog').filter({ hasText: '确认执行回滚' });
    await expect(dialog).toBeVisible({ timeout: 5000 });
  });

  test('rollback confirmation dialog contains warning about actual deployment', async ({ page }) => {
    const rollbackNo = 'RB-EXEC-002';
    await mockRollbackWithDetail(page, rollbackNo);

    await openPage(page, '回滚单');
    await filterByKeyword(page, rollbackNo);

    const row = page.locator('tbody tr').filter({ hasText: rollbackNo }).first();
    await expect(row).toBeVisible({ timeout: 15000 });
    await row.getByRole('button', { name: '执行回滚', exact: true }).click();

    const dialog = page.getByRole('dialog').filter({ hasText: '确认执行回滚' });
    await expect(dialog).toBeVisible({ timeout: 5000 });

    await expect(dialog).toContainText('此操作将实际执行部署脚本');
  });

  test('rollback confirmation dialog displays rollback order summary', async ({ page }) => {
    const rollbackNo = 'RB-EXEC-003';
    await mockRollbackWithDetail(page, rollbackNo);

    await openPage(page, '回滚单');
    await filterByKeyword(page, rollbackNo);

    const row = page.locator('tbody tr').filter({ hasText: rollbackNo }).first();
    await expect(row).toBeVisible({ timeout: 15000 });
    await row.getByRole('button', { name: '执行回滚', exact: true }).click();

    const dialog = page.getByRole('dialog').filter({ hasText: '确认执行回滚' });
    await expect(dialog).toBeVisible({ timeout: 5000 });

    await expect(dialog).toContainText(rollbackNo);
  });

  test('MANUAL_ONLY hint is visible in rollback confirmation dialog', async ({ page }) => {
    const rollbackNo = 'RB-EXEC-004';
    await mockRollbackWithDetail(page, rollbackNo);

    await openPage(page, '回滚单');
    await filterByKeyword(page, rollbackNo);

    const row = page.locator('tbody tr').filter({ hasText: rollbackNo }).first();
    await expect(row).toBeVisible({ timeout: 15000 });
    await row.getByRole('button', { name: '执行回滚', exact: true }).click();

    const dialog = page.getByRole('dialog').filter({ hasText: '确认执行回滚' });
    await expect(dialog).toBeVisible({ timeout: 5000 });

    await expect(dialog).toContainText('回滚必须由人工点击触发');
    await expect(dialog).toContainText('不会自动执行');
  });

  test('rollback execute API is NOT called before clicking confirm', async ({ page }) => {
    const rollbackNo = 'RB-EXEC-005';
    const rollbackId = 9005;
    let executeCalled = false;

    await mockRollbackWithDetail(page, rollbackNo, { id: rollbackId });
    await page.route(`**/api/rollbacks/${rollbackId}/execute`, async (route) => {
      executeCalled = true;
      await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ code: 'SUCCESS', data: true }) });
    });

    await openPage(page, '回滚单');
    await filterByKeyword(page, rollbackNo);

    const row = page.locator('tbody tr').filter({ hasText: rollbackNo }).first();
    await expect(row).toBeVisible({ timeout: 15000 });
    await row.getByRole('button', { name: '执行回滚', exact: true }).click();

    const dialog = page.getByRole('dialog').filter({ hasText: '确认执行回滚' });
    await expect(dialog).toBeVisible({ timeout: 5000 });

    await page.waitForTimeout(1000);
    expect(executeCalled).toBe(false);
  });

  test('rollback execute API is called after clicking confirm button', async ({ page }) => {
    const rollbackNo = 'RB-EXEC-006';
    const rollbackId = 9006;
    let executeCalled = false;

    await mockRollbackWithDetail(page, rollbackNo, { id: rollbackId });
    await page.route(`**/api/rollbacks/${rollbackId}/execute`, async (route) => {
      executeCalled = true;
      await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ code: 'SUCCESS', data: true }) });
    });

    await openPage(page, '回滚单');
    await filterByKeyword(page, rollbackNo);

    const row = page.locator('tbody tr').filter({ hasText: rollbackNo }).first();
    await expect(row).toBeVisible({ timeout: 15000 });
    await row.getByRole('button', { name: '执行回滚', exact: true }).click();

    const dialog = page.getByRole('dialog').filter({ hasText: '确认执行回滚' });
    await expect(dialog).toBeVisible({ timeout: 5000 });

    // RollbackExecutionConfirmDialog button is "确认执行" (no checkbox required)
    await dialog.getByRole('button', { name: '确认执行', exact: true }).click();
    await page.waitForTimeout(1000);

    expect(executeCalled).toBe(true);
  });
});

// ============================================================
// T-C/T-D: Precheck and Deploy Records
// ============================================================
test.describe('T-C/T-D precheck and deploy records', () => {
  test.setTimeout(90000);

  test.beforeEach(async ({ page }) => {
    await loginAsAdmin(page);
  });

  test('release precheck calls POST /api/releases/{id}/precheck', async ({ page }) => {
    const orderNo = 'REL-PRE-001';
    const releaseId = 8101;
    let precheckCalled = false;

    await mockReleaseWithDetail(page, orderNo, { id: releaseId });
    await page.route(`**/api/releases/${releaseId}/precheck`, async (route) => {
      precheckCalled = true;
      await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ code: 'SUCCESS', data: [{ hostId: 1, instanceId: 1, success: true, stdout: '', stderr: '', errorMessage: '' }] }) });
    });

    await openPage(page, '发布单');
    await filterByKeyword(page, orderNo);

    const row = page.locator('tbody tr').filter({ hasText: orderNo }).first();
    await expect(row).toBeVisible({ timeout: 15000 });
    await row.getByRole('button', { name: '前置检查', exact: true }).click();

    await page.waitForTimeout(2000);
    expect(precheckCalled).toBe(true);
  });

  test('release precheck dialog displays results grouped by host', async ({ page }) => {
    const orderNo = 'REL-PRE-002';
    const releaseId = 8102;

    await mockReleaseWithDetail(page, orderNo, { id: releaseId });
    await page.route(`**/api/releases/${releaseId}/precheck`, async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          code: 'SUCCESS',
          data: [
            { hostId: 1, instanceId: 1, success: true, stdout: '', stderr: '', errorMessage: '磁盘空间充足', artifactUri: '' },
            { hostId: 2, instanceId: 2, success: false, stdout: '', stderr: '', errorMessage: '端口被占用', artifactUri: '' },
          ],
        }),
      });
    });

    await openPage(page, '发布单');
    await filterByKeyword(page, orderNo);

    const row = page.locator('tbody tr').filter({ hasText: orderNo }).first();
    await expect(row).toBeVisible({ timeout: 15000 });
    await row.getByRole('button', { name: '前置检查', exact: true }).click();

    const dialog = page.getByRole('dialog').filter({ hasText: '前置检查' });
    await expect(dialog).toBeVisible({ timeout: 5000 });

    // PrecheckDialog displays errorMessage column in the table
    await expect(dialog).toContainText('磁盘空间充足');
    await expect(dialog).toContainText('端口被占用');
  });

  test('release deploy records calls GET /api/releases/{id}/deploy-records', async ({ page }) => {
    const orderNo = 'REL-DEPLOY-001';
    const releaseId = 8103;
    let deployRecordsCalled = false;

    await mockReleaseWithDetail(page, orderNo, { id: releaseId });
    await page.route(`**/api/releases/${releaseId}/deploy-records`, async (route) => {
      deployRecordsCalled = true;
      await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ code: 'SUCCESS', data: [{ batchNo: 1, status: 'SUCCESS', logs: '' }] }) });
    });

    await openPage(page, '发布单');
    await filterByKeyword(page, orderNo);

    const row = page.locator('tbody tr').filter({ hasText: orderNo }).first();
    await expect(row).toBeVisible({ timeout: 15000 });
    await row.getByRole('button', { name: '部署记录', exact: true }).click();

    await page.waitForTimeout(2000);
    expect(deployRecordsCalled).toBe(true);
  });

  test('release deploy records dialog displays batch groups', async ({ page }) => {
    const orderNo = 'REL-DEPLOY-002';
    const releaseId = 8104;

    await mockReleaseWithDetail(page, orderNo, { id: releaseId });
    await page.route(`**/api/releases/${releaseId}/deploy-records`, async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          code: 'SUCCESS',
          data: [
            { id: 1, batchNo: 1, status: 'SUCCESS', host: 'host1', logs: 'Deploy completed' },
            { id: 2, batchNo: 2, status: 'RUNNING', host: 'host2', logs: 'Deploying...' },
          ],
        }),
      });
    });

    await openPage(page, '发布单');
    await filterByKeyword(page, orderNo);

    const row = page.locator('tbody tr').filter({ hasText: orderNo }).first();
    await expect(row).toBeVisible({ timeout: 15000 });
    await row.getByRole('button', { name: '部署记录', exact: true }).click();

    const dialog = page.getByRole('dialog').filter({ hasText: '部署记录' });
    await expect(dialog).toBeVisible({ timeout: 5000 });

    await expect(dialog).toContainText('批次');
  });

  test('rollback precheck calls POST /api/rollbacks/{id}/precheck', async ({ page }) => {
    const rollbackNo = 'RB-PRE-001';
    const rollbackId = 9101;
    let precheckCalled = false;

    await mockRollbackWithDetail(page, rollbackNo, { id: rollbackId });
    await page.route(`**/api/rollbacks/${rollbackId}/precheck`, async (route) => {
      precheckCalled = true;
      await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ code: 'SUCCESS', data: [{ hostId: 1, instanceId: 1, success: true, stdout: '', stderr: '', errorMessage: '' }] }) });
    });

    await openPage(page, '回滚单');
    await filterByKeyword(page, rollbackNo);

    const row = page.locator('tbody tr').filter({ hasText: rollbackNo }).first();
    await expect(row).toBeVisible({ timeout: 15000 });
    await row.getByRole('button', { name: '前置检查', exact: true }).click();

    await page.waitForTimeout(2000);
    expect(precheckCalled).toBe(true);
  });

  test('rollback deploy records calls GET /api/rollbacks/{id}/deploy-records', async ({ page }) => {
    const rollbackNo = 'RB-DEPLOY-001';
    const rollbackId = 9102;
    let deployRecordsCalled = false;

    await mockRollbackWithDetail(page, rollbackNo, { id: rollbackId });
    await page.route(`**/api/rollbacks/${rollbackId}/deploy-records`, async (route) => {
      deployRecordsCalled = true;
      await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ code: 'SUCCESS', data: [{ batchNo: 1, status: 'SUCCESS', logs: '' }] }) });
    });

    await openPage(page, '回滚单');
    await filterByKeyword(page, rollbackNo);

    const row = page.locator('tbody tr').filter({ hasText: rollbackNo }).first();
    await expect(row).toBeVisible({ timeout: 15000 });
    await row.getByRole('button', { name: '部署记录', exact: true }).click();

    await page.waitForTimeout(2000);
    expect(deployRecordsCalled).toBe(true);
  });
});

// ============================================================
// T-E: Status tags and empty state (Contract 8.2, 8.8)
// ============================================================
test.describe('T-E status tags and empty state (Contract 8.2, 8.8)', () => {
  test.setTimeout(90000);

  test.beforeEach(async ({ page }) => {
    await loginAsAdmin(page);
  });

  // T-07: Verify status tag colors per Contract 8.2
  test('release status tags have correct type attribute per Contract 8.2', async ({ page }) => {
    await mockResourceApis(page);
    await mockReleaseList(page, [
      { id: 1, orderNo: 'STATUS-CREATED', status: 'CREATED', releaseType: 'FULL' },
      { id: 2, orderNo: 'STATUS-SUCCESS', status: 'SUCCESS', releaseType: 'FULL' },
      { id: 3, orderNo: 'STATUS-FAILED', status: 'FAILED', releaseType: 'FULL' },
    ], 3);

    await openPage(page, '发布单');
    await page.waitForSelector('tbody tr', { timeout: 15000 });

    const createdRow = page.locator('tbody tr').filter({ hasText: 'STATUS-CREATED' }).first();
    await expect(createdRow).toBeVisible();
    await expect(createdRow.locator('.el-tag').filter({ hasText: '已创建' })).toBeVisible();

    const successRow = page.locator('tbody tr').filter({ hasText: 'STATUS-SUCCESS' }).first();
    await expect(successRow).toBeVisible();
    await expect(successRow.locator('.el-tag--success').filter({ hasText: '成功' })).toBeVisible();

    const failedRow = page.locator('tbody tr').filter({ hasText: 'STATUS-FAILED' }).first();
    await expect(failedRow).toBeVisible();
    await expect(failedRow.locator('.el-tag--danger').filter({ hasText: '失败' })).toBeVisible();
  });

  // T-13: Empty state for release list
  test('release list shows correct empty state text', async ({ page }) => {
    await mockResourceApis(page);
    await mockReleaseList(page, [], 0);

    await openPage(page, '发布单');

    const emptyText = page.locator('.el-table__empty-text, .el-empty__description').first();
    await expect(emptyText).toContainText('暂无发布单');
  });

  // T-13: Empty state for rollback list
  test('rollback list shows correct empty state text', async ({ page }) => {
    await mockResourceApis(page);
    await mockRollbackList(page, [], 0);

    await openPage(page, '回滚单');

    const emptyText = page.locator('.el-table__empty-text, .el-empty__description').first();
    await expect(emptyText).toContainText('暂无回滚单');
  });

  // T-13: Empty state for deploy records
  test('deploy records dialog shows empty state when no records', async ({ page }) => {
    const orderNo = 'REL-EMPTY-DEPLOY';
    const releaseId = 8201;

    await mockReleaseWithDetail(page, orderNo, { id: releaseId });
    await page.route(`**/api/releases/${releaseId}/deploy-records`, async (route) => {
      await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ code: 'SUCCESS', data: [] }) });
    });

    await openPage(page, '发布单');
    await filterByKeyword(page, orderNo);

    const row = page.locator('tbody tr').filter({ hasText: orderNo }).first();
    await expect(row).toBeVisible({ timeout: 15000 });
    await row.getByRole('button', { name: '部署记录', exact: true }).click();

    const dialog = page.getByRole('dialog').filter({ hasText: '部署记录' });
    await expect(dialog).toBeVisible({ timeout: 5000 });

    await expect(dialog).toContainText('暂无部署记录');
  });

  // T-13: Empty state for precheck records
  test('precheck dialog shows empty state when no records', async ({ page }) => {
    const orderNo = 'REL-EMPTY-PRECHECK';
    const releaseId = 8202;

    await mockReleaseWithDetail(page, orderNo, { id: releaseId });
    await page.route(`**/api/releases/${releaseId}/precheck`, async (route) => {
      await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ code: 'SUCCESS', data: [] }) });
    });

    await openPage(page, '发布单');
    await filterByKeyword(page, orderNo);

    const row = page.locator('tbody tr').filter({ hasText: orderNo }).first();
    await expect(row).toBeVisible({ timeout: 15000 });
    await row.getByRole('button', { name: '前置检查', exact: true }).click();

    const dialog = page.getByRole('dialog').filter({ hasText: '前置检查' });
    await expect(dialog).toBeVisible({ timeout: 5000 });

    await expect(dialog).toContainText('暂无预检记录');
  });

  // T-14: Error message display
  test('config check shows error message on API failure', async ({ page }) => {
    const orderNo = 'REL-ERROR-CFG';
    const releaseId = 8203;

    await mockReleaseWithDetail(page, orderNo, { id: releaseId, appId: 10, envId: 20 });
    await page.route('**/api/releases/deploy-config/check**', async (route) => {
      await route.fulfill({ status: 500, contentType: 'application/json', body: JSON.stringify({ code: 'INTERNAL_ERROR', message: '服务器内部错误' }) });
    });

    await openPage(page, '发布单');
    await filterByKeyword(page, orderNo);

    const row = page.locator('tbody tr').filter({ hasText: orderNo }).first();
    await expect(row).toBeVisible({ timeout: 15000 });
    // 第二批：配置检查收进行内「更多」菜单（显示条件与处理函数不变）
    await row.getByRole('button', { name: '更多', exact: true }).click();
    await page.getByRole('menuitem', { name: '配置检查', exact: true }).click();

    await page.waitForTimeout(2000);
    const dialog = page.getByRole('dialog').filter({ hasText: '配置完整性检查' });
    await expect(dialog).toBeVisible({ timeout: 5000 });
  });
});

// ============================================================
// T-08: Form field linking (Contract 8.3)
// ============================================================
test.describe('T-08 form field linking (Contract 8.3)', () => {
  test.setTimeout(90000);

  test.beforeEach(async ({ page }) => {
    await loginAsAdmin(page);
    await mockResourceApis(page);
  });

  test('release create form shows app, service, version dropdowns with options', async ({ page }) => {
    await openPage(page, '发布单');
    await page.getByRole('button', { name: '新建', exact: true }).click();

    const dialog = activeDialog(page);
    await expect(dialog).toBeVisible();

    await chooseSelectOption(dialog, page, '应用名称', '订单协同平台-后端');
    await chooseSelectOption(dialog, page, '服务名称', '订单协同平台后端服务');

    await dialog.getByRole('button', { name: '取消', exact: true }).click();
  });

  test('release create form shows version options after selecting app', async ({ page }) => {
    await openPage(page, '发布单');
    await page.getByRole('button', { name: '新建', exact: true }).click();

    const dialog = activeDialog(page);
    await expect(dialog).toBeVisible();

    await chooseSelectOption(dialog, page, '应用名称', '订单协同平台-后端');
    await chooseSelectOption(dialog, page, '版本', 'v1.0.0');

    await dialog.getByRole('button', { name: '取消', exact: true }).click();
  });
});

// ============================================================
// T-10: Timeline rendering (Contract 8.4)
// ============================================================
test.describe('T-10 timeline rendering (Contract 8.4)', () => {
  test.setTimeout(90000);

  test.beforeEach(async ({ page }) => {
    await loginAsAdmin(page);
  });

  test('release detail drawer shows timeline nodes', async ({ page }) => {
    const orderNo = 'REL-TIME-001';
    const releaseId = 8501;

    // Mock release with timeline data matching component expected format
    await mockReleaseWithDetail(page, orderNo, {
      id: releaseId,
      timeline: [
        { key: 'node-1', status: 'SUCCESS', time: '2026-05-20T10:00:00', title: '发布单创建', summary: '发布单创建成功', actionType: 'NONE' },
        { key: 'node-2', status: 'SUCCESS', time: '2026-05-20T10:05:00', title: '前置检查成功', summary: '前置检查通过', actionType: 'VIEW_PRECHECK' },
        { key: 'node-3', status: 'PENDING', time: '2026-05-20T10:10:00', title: '开始执行', summary: '等待执行', actionType: 'EXECUTE' },
      ],
    });

    await openPage(page, '发布单');
    await filterByKeyword(page, orderNo);

    const row = page.locator('tbody tr').filter({ hasText: orderNo }).first();
    await expect(row).toBeVisible({ timeout: 15000 });
    await row.getByRole('button', { name: '详情', exact: true }).click();

    const drawer = page.locator('.el-drawer');
    await expect(drawer).toBeVisible({ timeout: 5000 });

    // Verify timeline section is visible
    await expect(drawer).toContainText('时间线');

    // Verify timeline nodes are rendered
    await expect(drawer).toContainText('发布单创建');
    await expect(drawer).toContainText('前置检查成功');
    await expect(drawer).toContainText('开始执行');

    await drawer.getByRole('button', { name: '关闭', exact: true }).click();
  });

  test('rollback detail drawer shows timeline nodes', async ({ page }) => {
    const rollbackNo = 'RB-TIME-001';
    const rollbackId = 8502;

    // Mock rollback with timeline data matching component expected format
    await mockRollbackWithDetail(page, rollbackNo, {
      id: rollbackId,
      timeline: [
        { key: 'node-1', status: 'SUCCESS', time: '2026-05-20T11:00:00', title: '回滚单创建', summary: '回滚单创建成功', actionType: 'NONE' },
        { key: 'node-2', status: 'PENDING', time: '2026-05-20T11:10:00', title: '开始执行回滚', summary: '等待执行回滚', actionType: 'EXECUTE' },
      ],
    });

    await openPage(page, '回滚单');
    await filterByKeyword(page, rollbackNo);

    const row = page.locator('tbody tr').filter({ hasText: rollbackNo }).first();
    await expect(row).toBeVisible({ timeout: 15000 });
    await row.getByRole('button', { name: '详情', exact: true }).click();

    const drawer = page.locator('.el-drawer');
    await expect(drawer).toBeVisible({ timeout: 5000 });

    // Verify timeline section is visible
    await expect(drawer).toContainText('时间线');

    // Verify timeline nodes are rendered
    await expect(drawer).toContainText('回滚单创建');
    await expect(drawer).toContainText('开始执行回滚');

    await drawer.getByRole('button', { name: '关闭', exact: true }).click();
  });
});

// ============================================================
// MANUAL_ONLY boundary checks (Contract 1.1, T-16)
// ============================================================
test.describe('MANUAL_ONLY boundary (Contract 1.1)', () => {
  test.setTimeout(90000);

  test.beforeEach(async ({ page }) => {
    await loginAsAdmin(page);
  });

  test('release execute dialog does NOT contain auto-publish misleading text', async ({ page }) => {
    const orderNo = 'REL-MANUAL-001';
    await mockReleaseWithDetail(page, orderNo);

    await openPage(page, '发布单');
    await filterByKeyword(page, orderNo);

    const row = page.locator('tbody tr').filter({ hasText: orderNo }).first();
    await expect(row).toBeVisible({ timeout: 15000 });
    await row.getByRole('button', { name: '执行发布', exact: true }).click();

    const dialog = page.getByRole('dialog').filter({ hasText: '确认执行发布' });
    await expect(dialog).toBeVisible({ timeout: 5000 });

    const dialogText = await dialog.textContent();
    expect(dialogText).not.toContain('自动发布');
    expect(dialogText).not.toContain('自动执行发布');
    expect(dialogText).not.toContain('系统将自动');
  });

  test('rollback execute dialog does NOT contain auto-rollback misleading text', async ({ page }) => {
    const rollbackNo = 'RB-MANUAL-001';
    await mockRollbackWithDetail(page, rollbackNo);

    await openPage(page, '回滚单');
    await filterByKeyword(page, rollbackNo);

    const row = page.locator('tbody tr').filter({ hasText: rollbackNo }).first();
    await expect(row).toBeVisible({ timeout: 15000 });
    await row.getByRole('button', { name: '执行回滚', exact: true }).click();

    const dialog = page.getByRole('dialog').filter({ hasText: '确认执行回滚' });
    await expect(dialog).toBeVisible({ timeout: 5000 });

    const dialogText = await dialog.textContent();
    expect(dialogText).not.toContain('自动回滚');
    expect(dialogText).not.toContain('自动执行回滚');
    expect(dialogText).not.toContain('系统将自动');

    expect(dialogText).toContain('不会自动执行');
  });

  test('release page does NOT show auto-publish text in row actions', async ({ page }) => {
    const orderNo = 'REL-MANUAL-002';
    await mockReleaseWithDetail(page, orderNo);

    await openPage(page, '发布单');
    await filterByKeyword(page, orderNo);

    const row = page.locator('tbody tr').filter({ hasText: orderNo }).first();
    await expect(row).toBeVisible({ timeout: 15000 });

    const rowText = await row.textContent();
    expect(rowText).not.toContain('自动发布');
    expect(rowText).not.toContain('自动执行发布');
  });

  test('rollback page does NOT show auto-rollback text in row actions', async ({ page }) => {
    const rollbackNo = 'RB-MANUAL-002';
    await mockRollbackWithDetail(page, rollbackNo);

    await openPage(page, '回滚单');
    await filterByKeyword(page, rollbackNo);

    const row = page.locator('tbody tr').filter({ hasText: rollbackNo }).first();
    await expect(row).toBeVisible({ timeout: 15000 });

    const rowText = await row.textContent();
    expect(rowText).not.toContain('自动回滚');
    expect(rowText).not.toContain('自动执行回滚');
  });

  test('canary autoRollbackEnabled is an E-stage feature, not T-stage auto-rollback', async ({ page }) => {
    const orderNo = 'REL-CANARY-MANUAL';
    await mockReleaseWithDetail(page, orderNo, { releaseType: 'CANARY' });

    await openPage(page, '发布单');
    await filterByKeyword(page, orderNo);

    const row = page.locator('tbody tr').filter({ hasText: orderNo }).first();
    await expect(row).toBeVisible({ timeout: 15000 });

    const rowText = await row.textContent();
    expect(rowText).not.toContain('自动执行回滚');
  });
});
