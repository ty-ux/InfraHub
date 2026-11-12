import { expect, test, type Page } from '@playwright/test';
import { loginAsAdmin } from './helpers/auth';
import {
  tableRow,
  chooseSelectOption,
  activeDialog,
  fillTextField,
  fillNumberField,
  openCreateDialog,
  openPage,
  saveDialog,
} from './helpers/ui';

test.describe('E1 canary release', () => {
  test.setTimeout(90000);

  test.beforeEach(async ({ page }) => {
    await loginAsAdmin(page);
  });

  test('CANARY release order shows canary plan button in list', async ({ page }) => {
    const suffix = Date.now().toString().slice(-8);
    const releaseOrderNo = `CANARY-UI-${suffix}`;

    // Create a CANARY type release order
    const releaseDialog = await openCreateDialog(page, '发布单');
    await fillTextField(releaseDialog, '发布单号', releaseOrderNo);
    await chooseSelectOption(releaseDialog, page, '应用名称', '订单协同平台-后端');
    await chooseSelectOption(releaseDialog, page, '服务名称', '订单协同平台后端服务');
    await chooseSelectOption(releaseDialog, page, '环境名称', '测试环境');
    await chooseSelectOption(releaseDialog, page, '版本', 'v1.0.0');
    await chooseSelectOption(releaseDialog, page, '风险等级', 'LOW');
    // E1: Select CANARY release type
    await chooseSelectOption(releaseDialog, page, '发布类型', 'CANARY');
    await saveDialog(page);

    // Locate the row using keyword filter (sortMode=focus may push new rows back)
    const filterBar = page.locator('.order-filter-bar');
    const keywordInput = filterBar.locator('input').first();
    await keywordInput.fill(releaseOrderNo);
    await filterBar.getByRole('button', { name: '查询', exact: true }).click();
    await page.waitForTimeout(500);

    const releaseRow = page.locator('tbody tr').filter({ hasText: releaseOrderNo }).first();
    await expect(releaseRow).toBeVisible({ timeout: 10000 });

    // E1: Verify releaseType='CANARY' shows "灰度计划" button
    await expect(releaseRow.getByRole('button', { name: '灰度计划', exact: true })).toBeVisible();
  });

  test('canary plan dialog shows batch count and auto rollback options', async ({ page }) => {
    const suffix = Date.now().toString().slice(-8);
    const releaseOrderNo = `CANARY-PLAN-${suffix}`;

    // Create a CANARY type release order
    const releaseDialog = await openCreateDialog(page, '发布单');
    await fillTextField(releaseDialog, '发布单号', releaseOrderNo);
    await chooseSelectOption(releaseDialog, page, '应用名称', '订单协同平台-后端');
    await chooseSelectOption(releaseDialog, page, '服务名称', '订单协同平台后端服务');
    await chooseSelectOption(releaseDialog, page, '环境名称', '测试环境');
    await chooseSelectOption(releaseDialog, page, '版本', 'v1.0.0');
    await chooseSelectOption(releaseDialog, page, '风险等级', 'LOW');
    await chooseSelectOption(releaseDialog, page, '发布类型', 'CANARY');
    await saveDialog(page);

    // Locate the row
    const filterBar = page.locator('.order-filter-bar');
    const keywordInput = filterBar.locator('input').first();
    await keywordInput.fill(releaseOrderNo);
    await filterBar.getByRole('button', { name: '查询', exact: true }).click();
    await page.waitForTimeout(500);

    const releaseRow = page.locator('tbody tr').filter({ hasText: releaseOrderNo }).first();
    await expect(releaseRow).toBeVisible({ timeout: 10000 });

    // Click "灰度计划" button
    await releaseRow.getByRole('button', { name: '灰度计划', exact: true }).click();

    // Verify canary plan dialog opens
    const canaryDialog = activeDialog(page);
    await expect(canaryDialog).toContainText('创建灰度计划');

    // Verify form fields exist
    await expect(canaryDialog).toContainText('批次数');
    await expect(canaryDialog).toContainText('批次间隔(秒)');
    await expect(canaryDialog).toContainText('失败自动回滚');

    // Cancel the dialog
    await canaryDialog.getByRole('button', { name: '取消', exact: true }).click();
  });

  test('FULL release order does not show canary plan button', async ({ page }) => {
    const suffix = Date.now().toString().slice(-8);
    const releaseOrderNo = `FULL-UI-${suffix}`;

    // Create a FULL type release order (default)
    const releaseDialog = await openCreateDialog(page, '发布单');
    await fillTextField(releaseDialog, '发布单号', releaseOrderNo);
    await chooseSelectOption(releaseDialog, page, '应用名称', '订单协同平台-后端');
    await chooseSelectOption(releaseDialog, page, '服务名称', '订单协同平台后端服务');
    await chooseSelectOption(releaseDialog, page, '环境名称', '测试环境');
    await chooseSelectOption(releaseDialog, page, '版本', 'v1.0.0');
    await chooseSelectOption(releaseDialog, page, '风险等级', 'LOW');
    // Keep default FULL release type
    await saveDialog(page);

    // Locate the row
    const filterBar = page.locator('.order-filter-bar');
    const keywordInput = filterBar.locator('input').first();
    await keywordInput.fill(releaseOrderNo);
    await filterBar.getByRole('button', { name: '查询', exact: true }).click();
    await page.waitForTimeout(500);

    const releaseRow = page.locator('tbody tr').filter({ hasText: releaseOrderNo }).first();
    await expect(releaseRow).toBeVisible({ timeout: 10000 });

    // E1: FULL type should NOT show canary plan button
    await expect(releaseRow.getByRole('button', { name: '灰度计划', exact: true })).toHaveCount(0);
  });

  test('WAITING_NEXT status shows continue button and API call succeeds', async ({ page }) => {
    const releaseId = 987654321;
    const releaseOrderNo = `CANARY-WAIT-${Date.now().toString().slice(-8)}`;
    let continueCalled = false;

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
            traceId: 'e2e-canary',
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
                  createdBy: 1,
                  createdAt: '2026-05-08T12:00:00',
                  hasDeployRecords: true,
                  hasPipelineRun: false,
                  canaryPlanStatus: 'WAITING_NEXT',
                },
              ],
            },
          }),
        });
        return;
      }
      if (request.method() === 'POST' && url.pathname === `/api/releases/${releaseId}/canary/continue`) {
        continueCalled = true;
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({
            code: 'SUCCESS',
            message: 'success',
            traceId: 'e2e-canary-continue',
            data: {
              id: 1001,
              releaseOrderId: releaseId,
              batchCount: 2,
              batchIntervalSeconds: 0,
              autoRollbackEnabled: true,
              status: 'RUNNING',
              batches: [
                { id: 2001, planId: 1001, releaseOrderId: releaseId, batchNo: 1, instanceIds: [11], status: 'SUCCESS' },
                { id: 2002, planId: 1001, releaseOrderId: releaseId, batchNo: 2, instanceIds: [12], status: 'RUNNING' },
              ],
            },
          }),
        });
        return;
      }
      await route.fallback();
    });

    await openPage(page, '发布单');
    const filterBar = page.locator('.order-filter-bar');
    const keywordInput = filterBar.locator('input').first();
    await keywordInput.fill(releaseOrderNo);
    await filterBar.getByRole('button', { name: '查询', exact: true }).click();

    const releaseRow = page.locator('tbody tr').filter({ hasText: releaseOrderNo }).first();
    await expect(releaseRow).toBeVisible({ timeout: 10000 });
    await expect(releaseRow.getByRole('button', { name: '继续下一批', exact: true })).toBeVisible();

    await releaseRow.getByRole('button', { name: '继续下一批', exact: true }).click();
    const confirmDialog = page.locator('.el-message-box');
    await expect(confirmDialog).toBeVisible();
    await confirmDialog.getByRole('button', { name: 'OK', exact: true }).click();

    await expect.poll(() => continueCalled).toBe(true);
  });
});

// Helper function to find row across pages (reuse from release-regression.spec.ts)
async function findRowAcrossPages(page: Page, text: string) {
  const waitForTableIdle = async (timeout = 3000) => {
    await expect(page.locator('.content-card .el-table .el-loading-mask:visible')).toHaveCount(0, { timeout }).catch(() => undefined);
  };
  const orderFilterBar = page.locator('.order-filter-bar').first();
  if (await orderFilterBar.isVisible().catch(() => false)) {
    const currentPath = new URL(page.url()).pathname;
    const listApiPath = currentPath.includes('/rollbacks') ? '/api/rollbacks' : '/api/releases';
    const keywordInput = orderFilterBar
      .locator('input[placeholder="发布单号"], input[placeholder="回滚单号"]')
      .first();
    if ((await keywordInput.count()) > 0) {
      await keywordInput.fill(text);
      const keywordResponse = page.waitForResponse((response) => {
        const url = new URL(response.url());
        return url.pathname === listApiPath && url.searchParams.get('keyword') === text;
      }, { timeout: 10000 }).catch(() => null);
      await orderFilterBar.getByRole('button', { name: '查询', exact: true }).click();
      await keywordResponse;
      await waitForTableIdle();
      try {
        await expect(async () => {
          await expect(tableRow(page, text)).toBeVisible({ timeout: 1000 });
        }).toPass({ timeout: 10000 });
        return tableRow(page, text);
      } catch {
        const routeKeywordResponse = page.waitForResponse((response) => {
          const url = new URL(response.url());
          return url.pathname === listApiPath && url.searchParams.get('keyword') === text;
        }, { timeout: 10000 }).catch(() => null);
        await page.goto(`${currentPath}?keyword=${encodeURIComponent(text)}`);
        await routeKeywordResponse;
        await waitForTableIdle();
        if (await tableRow(page, text).isVisible().catch(() => false)) {
          return tableRow(page, text);
        }
        const clearButton = orderFilterBar.getByRole('button', { name: '清空筛选', exact: true });
        if (await clearButton.isVisible().catch(() => false)) {
          await clearButton.click();
          await page.waitForTimeout(300);
        }
      }
    }
  }

  await waitForTableIdle();
  for (let pageNum = 1; pageNum <= 100; pageNum += 1) {
    const pageInput = page.getByRole('spinbutton', { name: 'Page' }).first();
    if ((await pageInput.count()) > 0) {
      await pageInput.fill(String(pageNum));
      await pageInput.press('Enter');
      await waitForTableIdle();
    }
    const row = tableRow(page, text);
    if ((await row.count()) > 0 && await row.isVisible()) {
      return row;
    }
    const activePage = page.locator('.el-pager li.is-active').first();
    const activeText = ((await activePage.textContent().catch(() => '')) || '').trim();
    if (activeText && Number(activeText) < pageNum) {
      break;
    }
  }
  const refreshButton = page.getByRole('button', { name: '刷新', exact: true }).first();
  if (await refreshButton.isVisible().catch(() => false)) {
    await refreshButton.click();
    await waitForTableIdle();
    if (await tableRow(page, text).isVisible().catch(() => false)) {
      return tableRow(page, text);
    }
  }
  return tableRow(page, text);
}

// API helper functions
async function getAuthHeaders(page: Page) {
  const token = await page.evaluate(() => window.localStorage.getItem('INFRAHUB_TOKEN'));
  expect(token).toBeTruthy();
  return { Authorization: `Bearer ${token}` };
}

async function apiData<T>(page: Page, url: string): Promise<T> {
  const response = await page.request.get(url, { headers: await getAuthHeaders(page) });
  expect(response.ok()).toBeTruthy();
  const body = await response.json();
  expect(body.code).toBe('SUCCESS');
  return body.data as T;
}

async function postApi(page: Page, url: string, data: Record<string, unknown>) {
  const response = await page.request.post(url, { headers: await getAuthHeaders(page), data });
  const body = await response.json();
  return body as { code: string; message: string; data: Record<string, unknown> };
}

async function patchApi(page: Page, url: string, data: Record<string, unknown>) {
  const response = await page.request.patch(url, { headers: await getAuthHeaders(page), data });
  expect(response.ok()).toBeTruthy();
  return response.json();
}

async function findRecordByField(page: Page, url: string, field: string, value: string) {
  for (let pageNum = 1; pageNum <= 20; pageNum += 1) {
    const data = await apiData<{ records: Record<string, unknown>[]; total: number }>(
      page,
      `${url}?pageNum=${pageNum}&pageSize=200`,
    );
    const record = data.records.find((item) => item[field] === value);
    if (record) {
      return record;
    }
    if (pageNum * 200 >= data.total) {
      break;
    }
  }
  throw new Error(`${field}=${value} should exist`);
}

// Type definitions
interface CanaryBatch {
  id: number;
  planId: number;
  batchNo: number;
  status: string;
  startedAt?: string;
  endedAt?: string;
}

interface CanaryPlanResponse {
  id: number;
  releaseOrderId: number;
  batchCount: number;
  batchIntervalSeconds: number;
  autoRollbackEnabled: boolean;
  status: string;
  batches: CanaryBatch[];
}
