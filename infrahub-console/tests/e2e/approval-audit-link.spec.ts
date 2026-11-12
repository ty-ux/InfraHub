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

test.describe('approval audit link', () => {
  test.setTimeout(180000);

  test.beforeEach(async ({ page }) => {
    await loginAsAdmin(page);
  });

  test('approval audit link verifies audit chain', async ({ page }) => {
    const suffix = Date.now().toString().slice(-8);
    const releaseOrderNo = `REL-AUDIT-${suffix}`;

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

      // Wait for the table to refresh after creating the order
      await page.waitForTimeout(1500);
      await page.getByRole('button', { name: '刷新' }).click();
      await page.waitForTimeout(1000);

      const releaseRow = await findRowAcrossPages(page, releaseOrderNo);
      await expect(releaseRow).toBeVisible();
      await page.waitForTimeout(500);

      // Step 3: Execute the release order to create an approval
      const responsePromise = page.waitForResponse(resp =>
        resp.url().includes('/api/releases/') &&
        resp.url().includes('/execute') &&
        resp.request().method() === 'POST'
      );
      await releaseRow.getByRole('button', { name: '执行发布', exact: true }).click({ force: true });

      // Handle confirmation dialog if present
      const confirmDialog = page.locator('.el-message-box');
      if (await confirmDialog.isVisible()) {
        await confirmDialog.getByRole('button', { name: 'OK', exact: true }).click();
      }

      const response = await responsePromise;
      console.log('Execute response status:', response.status());

      // Extract approvalId from response body
      const responseBody = await response.json();
      const approvalId = responseBody?.data?.approvalId ?? responseBody?.approvalId;
      console.log('Extracted approvalId:', approvalId);
      expect(approvalId).toBeTruthy();

      // Should show approval submitted message
      await expect(page.locator('.el-message').getByText('审批已提交，待管理员处理')).toBeVisible({ timeout: 5000 });

      // Step 4: Approve via API
      const approveResponse = await page.request.post(`/api/approvals/requests/${approvalId}/approve`, {
        headers: await getAuthHeaders(page),
        data: { approveNote: 'E2E test approval' }
      });
      console.log('Approve response status:', approveResponse.status());
      expect(approveResponse.ok()).toBeTruthy();

      // Step 5: Navigate to approval center, find the approval row by orderNo
      await openPage(page, '审批中心');
      await page.waitForTimeout(1000);

      // Click search to load all records
      await page.getByRole('button', { name: '搜索', exact: true }).click();
      await page.waitForTimeout(2000);

      // Wait for table to load
      await page.waitForSelector('tbody tr', { timeout: 10000 });
      await page.waitForTimeout(1000);

      const approvalRow = await findRowAcrossPages(page, releaseOrderNo);
      await expect(approvalRow).toBeVisible();
      await expect(approvalRow).toContainText(releaseOrderNo);

      // Step 6: Open detail dialog
      await approvalRow.getByRole('button', { name: '查看详情' }).click();
      await expect(activeDialog(page)).toBeVisible();

      // Step 7: Click "查看审计" button
      const auditButton = activeDialog(page).getByRole('button', { name: '查看审计' });
      await expect(auditButton).toBeVisible();
      await auditButton.click();

      // Step 8: Wait for navigation, verify URL has query params
      await page.waitForURL(url =>
        url.pathname.includes('/audit-logs') &&
        url.searchParams.get('bizType') === 'APPROVAL' &&
        url.searchParams.get('bizId') === String(approvalId) &&
        url.searchParams.get('action') === 'APPROVAL_APPROVE'
      );

      // Step 9: Verify audit table shows the row
      await page.waitForTimeout(1000);
      const auditTable = page.locator('.el-table');
      const auditRow = auditTable.locator('tbody tr').first();
      await expect(auditRow).toBeVisible();

      // Step 10: Verify the row contains APPROVAL_APPROVE and SUCCESS
      await expect(auditRow).toContainText('APPROVAL_APPROVE');
      await expect(auditRow).toContainText('SUCCESS');
      // Verify bizId matches approvalId
      await expect(auditRow).toContainText(String(approvalId));

      // Step 11: Open detail, verify full audit info
      await auditRow.getByRole('button', { name: '详情' }).click();
      const auditDetail = page.getByRole('dialog');
      await expect(auditDetail).toBeVisible();
      await expect(auditDetail).toContainText('APPROVAL_APPROVE');
      await expect(auditDetail).toContainText('SUCCESS');
      await expect(auditDetail.getByText(String(approvalId))).toBeVisible();

      console.log('Test completed successfully for approval:', approvalId);

    } finally {
      // CRITICAL: Restore policy to disabled
      try {
        await page.request.put('/api/approvals/policies', {
          headers: await getAuthHeaders(page),
          data: {
            bizType: 'RELEASE_ORDER',
            action: 'EXECUTE_RELEASE',
            enabled: false
          }
        });
        console.log('Cleanup: policy disabled');
      } catch (e) {
        console.warn('Cleanup failed:', e);
      }
    }
  });
});
