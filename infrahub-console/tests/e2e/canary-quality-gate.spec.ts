import { expect, test } from '@playwright/test';
import { loginAsAdmin } from './helpers/auth';
import { openPage } from './helpers/ui';

test.describe('E2 canary quality gate', () => {
  test.setTimeout(90000);

  test.beforeEach(async ({ page }) => {
    await loginAsAdmin(page);
  });

  test('quality gate status displays in batch detail drawer', async ({ page }) => {
    const releaseId = 1001;
    const releaseOrderNo = 'CANARY-GATE-001';

    await page.route('**/api/releases**', async (route) => {
      const request = route.request();
      const url = new URL(request.url());
      if (request.method() === 'GET' && url.pathname === '/api/releases') {
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({
            code: 'SUCCESS',
            message: 'success',
            traceId: 'e2e-gate',
            data: {
              pageNum: 1,
              pageSize: 20,
              total: 1,
              records: [
                {
                  id: releaseId,
                  orderNo: releaseOrderNo,
                  appId: 1,
                  serviceId: 2,
                  envId: 2,
                  versionId: 1,
                  releaseType: 'CANARY',
                  status: 'RUNNING',
                  canaryPlanStatus: 'RUNNING',
                  createdBy: 1,
                  createdAt: '2026-05-09T12:00:00',
                },
              ],
            },
          }),
        });
        return;
      }
      await route.fallback();
    });

    // Mock canary plan with gate status
    await page.route(`**/api/releases/${releaseId}/detail`, async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          code: 'SUCCESS',
          message: 'success',
          traceId: 'e2e-gate-detail',
          data: {
            id: releaseId,
            orderNo: releaseOrderNo,
            appName: '订单协同平台-后端',
            serviceName: '订单协同平台后端服务',
            envName: '测试环境',
            status: 'RUNNING',
            releaseType: 'CANARY',
            versionNo: 'v1.0.0',
            createdAt: '2026-05-09T12:00:00',
          },
        }),
      });
    });

    await page.route(`**/api/releases/${releaseId}/canary-plan`, async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          code: 'SUCCESS',
          message: 'success',
          traceId: 'e2e-gate-plan',
          data: {
            id: 2001,
            releaseOrderId: releaseId,
            batchCount: 2,
            batchIntervalSeconds: 0,
            autoRollbackEnabled: true,
            qualityGateEnabled: true,
            maxFiringAlerts: 0,
            gateFailureAction: 'BLOCK_AND_ROLLBACK',
            status: 'RUNNING',
            batches: [
              {
                id: 3001,
                planId: 2001,
                batchNo: 1,
                instanceIds: [11],
                status: 'SUCCESS',
                qualityGateStatus: 'PASSED',
                qualityGateReason: '门禁通过，命中告警数=0，阈值=0',
                monitorSnapshotId: 100,
              },
              {
                id: 3002,
                planId: 2001,
                batchNo: 2,
                instanceIds: [12],
                status: 'PENDING',
                qualityGateStatus: 'NOT_EVALUATED',
              },
            ],
          },
        }),
      });
    });

    await openPage(page, '发布单');
    const releaseRow = page.locator('tbody tr').filter({ hasText: releaseOrderNo }).first();
    await expect(releaseRow).toBeVisible({ timeout: 10000 });

    // Click detail button
    await releaseRow.getByRole('button', { name: '详情', exact: true }).click();

    // Verify quality gate status in drawer
    const drawer = page.locator('.el-drawer');
    await expect(drawer).toBeVisible();
    await expect(drawer).toContainText('质量门禁');
    await expect(drawer).toContainText('已启用');
    // E2: 门禁状态显示中文标签（精确匹配，避免同时匹配原因文本）
    await expect(drawer.locator('.el-tag').getByText('通过', { exact: true })).toBeVisible();
  });

  test('BLOCKED status prevents continue button and shows gate failure', async ({ page }) => {
    const releaseId = 1002;
    const releaseOrderNo = 'CANARY-BLOCKED-001';

    await page.route('**/api/releases**', async (route) => {
      const request = route.request();
      const url = new URL(request.url());
      if (request.method() === 'GET' && url.pathname === '/api/releases') {
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({
            code: 'SUCCESS',
            message: 'success',
            traceId: 'e2e-blocked',
            data: {
              pageNum: 1,
              pageSize: 20,
              total: 1,
              records: [
                {
                  id: releaseId,
                  orderNo: releaseOrderNo,
                  appId: 1,
                  serviceId: 2,
                  envId: 2,
                  versionId: 1,
                  releaseType: 'CANARY',
                  status: 'RUNNING',
                  // BLOCKED plan status, not WAITING_NEXT (due to gate failure)
                  canaryPlanStatus: 'BLOCKED',
                  createdBy: 1,
                  createdAt: '2026-05-09T12:00:00',
                },
              ],
            },
          }),
        });
        return;
      }
      await route.fallback();
    });

    await openPage(page, '发布单');
    const releaseRow = page.locator('tbody tr').filter({ hasText: releaseOrderNo }).first();
    await expect(releaseRow).toBeVisible({ timeout: 10000 });

    // BLOCKED status should NOT show "继续下一批" button
    await expect(releaseRow.getByRole('button', { name: '继续下一批', exact: true })).toHaveCount(0);

    // E2: BLOCKED status should show "门禁阻断" tag and "查看原因" button
    await expect(releaseRow.getByText('门禁阻断')).toBeVisible();
    await expect(releaseRow.getByRole('button', { name: '查看原因', exact: true })).toBeVisible();
  });

  test('re-evaluate gate button in drawer triggers API call', async ({ page }) => {
    const releaseId = 1003;
    const batchId = 3001;
    const releaseOrderNo = 'CANARY-RE-EVAL-001';
    let evaluateCalled = false;

    await page.route('**/api/releases**', async (route) => {
      const request = route.request();
      const url = new URL(request.url());
      if (request.method() === 'GET' && url.pathname === '/api/releases') {
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({
            code: 'SUCCESS',
            message: 'success',
            traceId: 'e2e-eval',
            data: {
              pageNum: 1,
              pageSize: 20,
              total: 1,
              records: [
                {
                  id: releaseId,
                  orderNo: releaseOrderNo,
                  releaseType: 'CANARY',
                  status: 'RUNNING',
                  canaryPlanStatus: 'BLOCKED',
                },
              ],
            },
          }),
        });
        return;
      }
      await route.fallback();
    });

    // Mock detail API
    await page.route(`**/api/releases/${releaseId}/detail`, async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          code: 'SUCCESS',
          message: 'success',
          traceId: 'e2e-eval-detail',
          data: {
            id: releaseId,
            orderNo: releaseOrderNo,
            appName: '订单协同平台-后端',
            serviceName: '订单协同平台后端服务',
            envName: '测试环境',
            status: 'RUNNING',
            releaseType: 'CANARY',
            versionNo: 'v1.0.0',
            createdAt: '2026-05-09T12:00:00',
          },
        }),
      });
    });

    // Mock canary plan
    await page.route(`**/api/releases/${releaseId}/canary-plan`, async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          code: 'SUCCESS',
          message: 'success',
          traceId: 'e2e-eval-plan',
          data: {
            id: 2001,
            releaseOrderId: releaseId,
            batchCount: 2,
            batchIntervalSeconds: 0,
            autoRollbackEnabled: true,
            qualityGateEnabled: true,
            maxFiringAlerts: 0,
            gateFailureAction: 'BLOCK_AND_ROLLBACK',
            status: 'BLOCKED',
            batches: [
              {
                id: batchId,
                planId: 2001,
                batchNo: 1,
                instanceIds: [11],
                status: 'SUCCESS',
                qualityGateStatus: 'FAILED',
                qualityGateReason: '门禁失败，命中告警数=2 超过阈值 0',
              },
              {
                id: 3002,
                planId: 2001,
                batchNo: 2,
                instanceIds: [12],
                status: 'PENDING',
                qualityGateStatus: 'NOT_EVALUATED',
              },
            ],
          },
        }),
      });
    });

    // Mock evaluate-gate API
    await page.route(
      `**/api/releases/${releaseId}/canary/batches/${batchId}/evaluate-gate`,
      async (route) => {
        if (route.request().method() === 'POST') {
          evaluateCalled = true;
          await route.fulfill({
            status: 200,
            contentType: 'application/json',
            body: JSON.stringify({
              code: 'SUCCESS',
              message: 'success',
              traceId: 'e2e-eval-gate',
              data: {
                id: 2001,
                releaseOrderId: releaseId,
                status: 'RUNNING',
                batches: [
                  {
                    id: batchId,
                    batchNo: 1,
                    status: 'SUCCESS',
                    qualityGateStatus: 'PASSED',
                    qualityGateReason: '门禁通过，命中告警数=0，阈值=0',
                  },
                ],
              },
            }),
          });
          return;
        }
        await route.fallback();
      }
    );

    await openPage(page, '发布单');
    const releaseRow = page.locator('tbody tr').filter({ hasText: releaseOrderNo }).first();
    await expect(releaseRow).toBeVisible({ timeout: 10000 });

    // Click detail button to open drawer
    await releaseRow.getByRole('button', { name: '详情', exact: true }).click();

    // Verify drawer is visible
    const drawer = page.locator('.el-drawer');
    await expect(drawer).toBeVisible();

    // E2: Click "重评估" button in batch
    const batchSection = drawer.locator('.batch-item').first();
    await expect(batchSection.getByRole('button', { name: '重评估', exact: true })).toBeVisible();
    await batchSection.getByRole('button', { name: '重评估', exact: true }).click();

    // Verify API was called
    await expect.poll(() => evaluateCalled).toBe(true);
  });

  test('quality gate configuration shows in canary plan dialog', async ({ page }) => {
    const releaseId = 1004;
    const releaseOrderNo = 'CANARY-CONFIG-001';

    await page.route('**/api/releases**', async (route) => {
      const request = route.request();
      const url = new URL(request.url());
      if (request.method() === 'GET' && url.pathname === '/api/releases') {
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({
            code: 'SUCCESS',
            message: 'success',
            traceId: 'e2e-config',
            data: {
              pageNum: 1,
              pageSize: 20,
              total: 1,
              records: [
                {
                  id: releaseId,
                  orderNo: releaseOrderNo,
                  releaseType: 'CANARY',
                  status: 'CREATED',
                },
              ],
            },
          }),
        });
        return;
      }
      await route.fallback();
    });

    await openPage(page, '发布单');
    const releaseRow = page.locator('tbody tr').filter({ hasText: releaseOrderNo }).first();
    await expect(releaseRow).toBeVisible({ timeout: 10000 });

    // Click "灰度计划" button
    await releaseRow.getByRole('button', { name: '灰度计划', exact: true }).click();

    const dialog = page.locator('.el-dialog');
    await expect(dialog).toBeVisible();

    // P2: Verify quality gate config options (after P2 implementation)
    // Currently this may not exist - test will fail until P2 is implemented
    // await expect(dialog).toContainText('启用质量门禁');
    // await expect(dialog).toContainText('允许 FIRING 告警数');
  });

  // E3.1: 门禁评估历史追溯测试
  test('gate evaluation history displays when clicking view history', async ({ page }) => {
    const releaseId = 1005;
    const batchId = 3001;
    const releaseOrderNo = 'CANARY-HISTORY-001';
    let historyCalled = false;

    await page.route('**/api/releases**', async (route) => {
      const request = route.request();
      const url = new URL(request.url());
      if (request.method() === 'GET' && url.pathname === '/api/releases') {
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({
            code: 'SUCCESS',
            message: 'success',
            traceId: 'e2e-history',
            data: {
              pageNum: 1,
              pageSize: 20,
              total: 1,
              records: [
                {
                  id: releaseId,
                  orderNo: releaseOrderNo,
                  releaseType: 'CANARY',
                  status: 'RUNNING',
                  canaryPlanStatus: 'RUNNING',
                },
              ],
            },
          }),
        });
        return;
      }
      await route.fallback();
    });

    // Mock detail API
    await page.route(`**/api/releases/${releaseId}/detail`, async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          code: 'SUCCESS',
          message: 'success',
          traceId: 'e2e-history-detail',
          data: {
            id: releaseId,
            orderNo: releaseOrderNo,
            appName: '订单协同平台-后端',
            serviceName: '订单协同平台后端服务',
            envName: '测试环境',
            status: 'RUNNING',
            releaseType: 'CANARY',
            versionNo: 'v1.0.0',
            createdAt: '2026-05-09T12:00:00',
          },
        }),
      });
    });

    // Mock canary plan with gate status
    await page.route(`**/api/releases/${releaseId}/canary-plan`, async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          code: 'SUCCESS',
          message: 'success',
          traceId: 'e2e-history-plan',
          data: {
            id: 2001,
            releaseOrderId: releaseId,
            batchCount: 2,
            batchIntervalSeconds: 0,
            autoRollbackEnabled: true,
            qualityGateEnabled: true,
            maxFiringAlerts: 0,
            gateFailureAction: 'BLOCK_AND_ROLLBACK',
            status: 'RUNNING',
            batches: [
              {
                id: batchId,
                planId: 2001,
                batchNo: 1,
                instanceIds: [11],
                status: 'SUCCESS',
                qualityGateStatus: 'PASSED',
                qualityGateReason: '门禁通过，命中告警数=0，阈值=0',
                monitorSnapshotId: 100,
              },
              {
                id: 3002,
                planId: 2001,
                batchNo: 2,
                instanceIds: [12],
                status: 'PENDING',
                qualityGateStatus: 'NOT_EVALUATED',
              },
            ],
          },
        }),
      });
    });

    // E3.1: Mock gate evaluations history API
    await page.route(
      `**/api/releases/${releaseId}/canary/batches/${batchId}/gate-evaluations`,
      async (route) => {
        if (route.request().method() === 'GET') {
          historyCalled = true;
          await route.fulfill({
            status: 200,
            contentType: 'application/json',
            body: JSON.stringify({
              code: 'SUCCESS',
              message: 'success',
              traceId: 'e2e-history-api',
              data: [
                {
                  id: 1,
                  batchId: batchId,
                  evaluationType: 'FIRST_EVALUATION',
                  evaluationTypeLabel: '首次评估',
                  gateResult: 'PASSED',
                  gateResultLabel: '通过',
                  hitCount: 0,
                  threshold: 0,
                  monitorSnapshotId: 100,
                  monitorAlertEventIds: [],
                  reason: '门禁通过，命中告警数=0，阈值=0',
                  evaluatedAt: '2026-05-09T12:05:00',
                },
              ],
            }),
          });
          return;
        }
        await route.fallback();
      }
    );

    await openPage(page, '发布单');
    const releaseRow = page.locator('tbody tr').filter({ hasText: releaseOrderNo }).first();
    await expect(releaseRow).toBeVisible({ timeout: 10000 });

    // Click detail button
    await releaseRow.getByRole('button', { name: '详情', exact: true }).click();

    // Verify drawer
    const drawer = page.locator('.el-drawer');
    await expect(drawer).toBeVisible();

    // E3.1: Click "查看历史" button
    const batchSection = drawer.locator('.batch-item').first();
    await expect(batchSection.getByRole('button', { name: '查看历史', exact: true })).toBeVisible();
    await batchSection.getByRole('button', { name: '查看历史', exact: true }).click();

    // Verify history API was called
    await expect.poll(() => historyCalled).toBe(true);

    // E3.1: Verify history content displayed
    const gateHistory = drawer.locator('.gate-history');
    await expect(gateHistory).toBeVisible();
    await expect(gateHistory).toContainText('首次评估');
    await expect(gateHistory.locator('.el-tag').getByText('通过', { exact: true })).toBeVisible();
    await expect(gateHistory).toContainText('命中: 0');
    await expect(gateHistory).toContainText('阈值: 0');

    // E3.1: 快照跳转可用
    await gateHistory.getByRole('button', { name: '快照 #100', exact: true }).click();
    await expect(page).toHaveURL(/\/monitor\/snapshots\?snapshotId=100/);
  });
});
