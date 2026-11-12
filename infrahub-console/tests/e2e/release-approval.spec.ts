import { expect, test, type Page } from '@playwright/test';
import { loginAsAdmin } from './helpers/auth';
import {
  activeDialog,
  chooseFirstSelectOption,
  chooseSelectOption,
  fillTextField,
  openCreateDialog,
  openPage,
  saveDialog,
  tableRow,
} from './helpers/ui';

async function getAuthHeaders(page: Page) {
  const token = await page.evaluate(() => window.localStorage.getItem('INFRAHUB_TOKEN'));
  expect(token).toBeTruthy();
  return { Authorization: `Bearer ${token}` };
  }

async function findRowAcrossPages(page: Page, text: string) {
  for (let pageNum = 1; pageNum <= 100; pageNum += 1) {
    const pageInput = page.getByRole('spinbutton', { name: 'Page' }).first();
    if ((await pageInput.count()) > 0) {
      await pageInput.fill(String(pageNum));
      await pageInput.press('Enter');
      await page.waitForTimeout(300);
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
  return tableRow(page, text);
}

test.describe('release approval', () => {
  test.setTimeout(120000);

  test.beforeEach(async ({ page }) => {
    await loginAsAdmin(page);
  });

  test('release execute creates approval and approval flow completes', async ({ page }) => {
    const suffix = Date.now().toString().slice(-8);
    const releaseOrderNo = `REL-APPROVAL-${suffix}`;

    try {
      // Step 1: Enable approval policy via API
      const policyResponse = await page.request.put('/api/approvals/policies', {
        headers: await getAuthHeaders(page),
        data: {
          bizType: 'RELEASE_ORDER',
          action: 'EXECUTE_RELEASE',
          enabled: true
        }
      });
      console.log('Policy response status:', policyResponse.status());
      expect(policyResponse.ok()).toBeTruthy();

      // Step 2: Create an executable release order
      const releaseDialog = await openCreateDialog(page, '发布单');
      await fillTextField(releaseDialog, '发布单号', releaseOrderNo);
      await chooseSelectOption(releaseDialog, page, '应用名称', 'InfraHub');
      await chooseSelectOption(releaseDialog, page, '服务名称', 'InfraHub 后端服务');
      await chooseSelectOption(releaseDialog, page, '环境名称', '测试环境');
      await chooseFirstSelectOption(releaseDialog, page, '版本');
      await chooseSelectOption(releaseDialog, page, '风险等级', 'LOW');
      await saveDialog(page);

      const releaseRow = await findRowAcrossPages(page, releaseOrderNo);
      await expect(releaseRow).toBeVisible();

      // Step 3: Click execute and wait for response
      const responsePromise = page.waitForResponse(resp =>
        resp.url().includes('/api/releases/') &&
        resp.url().includes('/execute') &&
        resp.request().method() === 'POST'
      );
      await releaseRow.getByRole('button', { name: '执行发布', exact: true }).click();

      // Handle confirmation dialog if present
      const confirmDialog = page.locator('.el-message-box');
      if (await confirmDialog.isVisible()) {
        await confirmDialog.getByRole('button', { name: 'OK', exact: true }).click();
      }

      const response = await responsePromise;
      console.log('Execute response status:', response.status());

      // Step 4: Extract approvalId from response body - MUST be present
      const responseBody = await response.json();
      const approvalId = responseBody?.data?.approvalId ?? responseBody?.approvalId;
      console.log('Extracted approvalId:', approvalId);
      expect(approvalId).toBeTruthy();

      // Should show approval submitted message
      await expect(page.locator('.el-message').getByText('审批已提交，待管理员处理')).toBeVisible({ timeout: 5000 });

      // Step 5: Assert release order status is still CREATED (not RUNNING)
      // This verifies approval creation does NOT directly execute the release
      await openPage(page, '发布单');
      const releaseRowAfterExecute = await findRowAcrossPages(page, releaseOrderNo);
      await expect(releaseRowAfterExecute).toBeVisible();
      // Row should still show CREATED status and have "执行发布" button
      await expect(releaseRowAfterExecute).toContainText('CREATED');

      // Step 6: Approve via API directly
      const approveResponse = await page.request.post(`/api/approvals/requests/${approvalId}/approve`, {
        headers: await getAuthHeaders(page),
        data: { approveNote: 'Test approval - OK' }
      });
      console.log('Approve response status:', approveResponse.status());
      expect(approveResponse.ok()).toBeTruthy();

      // Step 7: Navigate back to release orders and verify execution started
      await openPage(page, '发布单');
      await page.getByRole('button', { name: '刷新', exact: true }).click();
      await page.waitForTimeout(1000);

      const releaseRowFinal = await findRowAcrossPages(page, releaseOrderNo);
      await expect(releaseRowFinal).toBeVisible();
      // After approval, status should change from CREATED to RUNNING or beyond
      const rowText = await releaseRowFinal.textContent();
      expect(rowText).not.toContain('CREATED');
      // Should show RUNNING, SUCCESS, or FAILED (any execution state)
      expect(rowText).toMatch(/RUNNING|SUCCESS|FAILED|执行中|成功|失败/);

    } finally {
      // Cleanup: Disable the approval policy first
      try {
        await page.request.put('/api/approvals/policies', {
          headers: await getAuthHeaders(page),
          data: {
            bizType: 'RELEASE_ORDER',
            action: 'EXECUTE_RELEASE',
            enabled: false
          }
        });
      } catch (e) {
        console.log('Cleanup: failed to disable policy', e);
      }

      // Note: We don't delete the release order as it may have already executed
      // The test data can be cleaned up separately
    }
  });

  test('rollback execute creates approval and approval flow completes', async ({ page }) => {
    const suffix = Date.now().toString().slice(-8);
    const rollbackNo = `RB-APPROVAL-${suffix}`;

    // Fixed test data parameters (matching release approval test)
    const serviceId = 5;
    const envId = 2;

    let rollbackOrderId: number | null = null;

    try {
      // Step 1: Enable rollback approval policy via API
      const policyResponse = await page.request.put('/api/approvals/policies', {
        headers: await getAuthHeaders(page),
        data: {
          bizType: 'ROLLBACK_ORDER',
          action: 'EXECUTE_ROLLBACK',
          enabled: true
        }
      });
      console.log('Rollback policy response status:', policyResponse.status());
      expect(policyResponse.ok()).toBeTruthy();

      // Step 2: Find a SUCCESS release order for service_id=5/env_id=2
      // This determines the app_id we'll use for versions
      const releasesResponse = await page.request.get('/api/releases', {
        headers: await getAuthHeaders(page),
        params: { serviceId: serviceId, envId: envId, status: 'SUCCESS', pageSize: 10 }
      });
      expect(releasesResponse.ok()).toBeTruthy();
      const releasesData = await releasesResponse.json();
      const releases = releasesData?.data?.records ?? releasesData?.data ?? [];

      let releaseOrderId: number | null = null;
      let appId: number | null = null;
      if (releases.length > 0) {
        releaseOrderId = releases[0].id ?? releases[0].releaseOrderId;
        appId = releases[0].appId;
      }

      if (!releaseOrderId || !appId) {
        throw new Error(`Missing fixture: no SUCCESS release order found for service_id=${serviceId}/env_id=${envId}. ` +
          `Create a SUCCESS release order first.`);
      }
      console.log('Release order ID:', releaseOrderId, 'App ID:', appId);

      // Step 3: Query available versions for the same app as the release order
      const versionsResponse = await page.request.get('/api/versions', {
        headers: await getAuthHeaders(page),
        params: { appId, pageSize: 100 }
      });
      expect(versionsResponse.ok()).toBeTruthy();
      const versionsData = await versionsResponse.json();
      const versions = versionsData?.data?.records ?? versionsData?.data ?? [];
      console.log('Found versions:', versions.length, 'for app_id:', appId);

      // Filter versions that belong to the same app and have artifacts
      const validVersions = versions.filter((v: { appId: number; artifactId: number; isDeleted: boolean }) =>
        v.appId === appId && v.artifactId && !v.isDeleted
      );
      console.log('Valid versions for app_id', appId, ':', validVersions.length);

      if (validVersions.length < 2) {
        throw new Error(`Missing fixture: need at least 2 versions for app_id=${appId} with artifacts. Found: ${validVersions.length}`);
      }

      // Use first two valid versions for the rollback
      const fromVersionId = validVersions[0].id ?? validVersions[0].versionId;
      const targetVersionId = validVersions[1].id ?? validVersions[1].versionId;

      console.log('From version ID:', fromVersionId, 'Target version ID:', targetVersionId);

      // Step 4: Create rollback order via API
      const createRollbackResponse = await page.request.post('/api/rollbacks', {
        headers: await getAuthHeaders(page),
        data: {
          rollbackNo: rollbackNo,
          releaseOrderId: releaseOrderId,
          fromVersionId: fromVersionId,
          toVersionId: targetVersionId,
          reason: 'E2E rollback approval test',
          status: 'CREATED'
        }
      });
      console.log('Create rollback response status:', createRollbackResponse.status());
      const rollbackErrorBody = await createRollbackResponse.text();
      console.log('Create rollback response body:', rollbackErrorBody);
      if (!createRollbackResponse.ok()) {
        throw new Error(`Create rollback failed (${createRollbackResponse.status()}): ${rollbackErrorBody}`);
      }

      // Query the rollback order by rollbackNo to get its ID
      const rollbackQueryResponse = await page.request.get('/api/rollbacks', {
        headers: await getAuthHeaders(page),
        params: { keyword: rollbackNo, pageSize: 1 }
      });
      expect(rollbackQueryResponse.ok()).toBeTruthy();
      const rollbackQueryData = await rollbackQueryResponse.json();
      const rollbackRecords = rollbackQueryData?.data?.records ?? rollbackQueryData?.data ?? [];
      if (rollbackRecords.length > 0) {
        rollbackOrderId = rollbackRecords[0].id ?? rollbackRecords[0].rollbackOrderId;
      }
      console.log('Created rollback order ID:', rollbackOrderId);
      expect(rollbackOrderId).toBeTruthy();

      // Step 5: Execute rollback via API - should create approval
      const executeResponse = await page.request.post(`/api/rollbacks/${rollbackOrderId}/execute`, {
        headers: await getAuthHeaders(page)
      });
      console.log('Rollback execute response status:', executeResponse.status());
      const executeBody = await executeResponse.json();
      console.log('Rollback execute response body:', JSON.stringify(executeBody, null, 2));

      // Extract approvalId from response - MUST be present
      const approvalId = executeBody?.data?.approvalId ?? executeBody?.approvalId;
      console.log('Extracted rollback approvalId:', approvalId);
      expect(approvalId).toBeTruthy();

      // Step 6: Verify rollback status is still CREATED (not directly executed)
      const rollbackCheckResponse = await page.request.get(`/api/rollbacks/${rollbackOrderId}/detail`, {
        headers: await getAuthHeaders(page)
      });
      expect(rollbackCheckResponse.ok()).toBeTruthy();
      const rollbackDetail = await rollbackCheckResponse.json();
      const rollbackStatus = rollbackDetail?.data?.status ?? rollbackDetail?.status;
      console.log('Rollback status before approval:', rollbackStatus);
      expect(rollbackStatus).toBe('CREATED');

      // Step 7: Approve via API
      const approveResponse = await page.request.post(`/api/approvals/requests/${approvalId}/approve`, {
        headers: await getAuthHeaders(page),
        data: { approveNote: 'E2E rollback approval test - OK' }
      });
      console.log('Rollback approve response status:', approveResponse.status());
      if (!approveResponse.ok()) {
        const approveError = await approveResponse.text();
        console.log('Rollback approve error:', approveError);
      }
      expect(approveResponse.ok()).toBeTruthy();

      // Step 8: Query rollback status after approval - should have changed from CREATED
      const rollbackFinalResponse = await page.request.get(`/api/rollbacks/${rollbackOrderId}/detail`, {
        headers: await getAuthHeaders(page)
      });
      expect(rollbackFinalResponse.ok()).toBeTruthy();
      const rollbackFinalDetail = await rollbackFinalResponse.json();
      const finalStatus = rollbackFinalDetail?.data?.status ?? rollbackFinalDetail?.status;
      console.log('Rollback status after approval:', finalStatus);
      console.log('Rollback detail after approval:', JSON.stringify(rollbackFinalDetail?.data ?? rollbackFinalDetail, null, 2));

      // Status should have left CREATED - allow RUNNING, SUCCESS, or FAILED
      expect(finalStatus).not.toBe('CREATED');
      expect(['RUNNING', 'SUCCESS', 'FAILED']).toContain(finalStatus);

    } finally {
      // Cleanup: Disable the rollback approval policy
      try {
        await page.request.put('/api/approvals/policies', {
          headers: await getAuthHeaders(page),
          data: {
            bizType: 'ROLLBACK_ORDER',
            action: 'EXECUTE_ROLLBACK',
            enabled: false
          }
        });
      } catch (e) {
        console.log('Cleanup: failed to disable rollback policy', e);
      }
      // Note: We don't delete the rollback order as it may have already executed
      // The test data can be cleaned up separately
    }
  });
});
