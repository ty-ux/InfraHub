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

test.describe('governance evidence chain', () => {
  test.setTimeout(180000);

  test.beforeEach(async ({ page }) => {
    await loginAsAdmin(page);
  });

  test('governance evidence chain shows complete chain from approval', async ({ page }) => {
    const suffix = Date.now().toString().slice(-8);
    const releaseOrderNo = `REL-CHAIN-${suffix}`;

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
        data: { approveNote: 'E2E test approval for governance chain' }
      });
      console.log('Approve response status:', approveResponse.status());
      expect(approveResponse.ok()).toBeTruthy();

      // Step 5: Navigate to approval center
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

      // Step 6: Open detail dialog
      await approvalRow.getByRole('button', { name: '查看详情' }).click();
      await expect(activeDialog(page)).toBeVisible();

      // Step 7: Click "查看证据链" button
      const chainButton = activeDialog(page).getByRole('button', { name: '查看证据链' });
      await expect(chainButton).toBeVisible();
      await chainButton.click();

      // Step 8: Wait for navigation to governance chain page
      await page.waitForURL(url => url.pathname.includes('/governance-chain'));
      await page.waitForTimeout(1000);

      // Step 9: Verify the page shows business summary
      const businessSummaryCard = page.locator('.el-card').filter({ hasText: '业务摘要' });
      await expect(businessSummaryCard).toBeVisible();
      await expect(businessSummaryCard).toContainText('RELEASE_ORDER');
      await expect(businessSummaryCard).toContainText(releaseOrderNo);

      // Step 10: Verify approval records section
      const approvalCard = page.locator('.el-card').filter({ hasText: '审批记录' });
      await expect(approvalCard).toBeVisible();
      // Check for status value element (not i18n dependent)
      await expect(approvalCard.locator('.el-tag').filter({ hasText: '已通过' }).or(
        approvalCard.locator('.el-tag').filter({ hasText: 'Approved' })
      )).toBeVisible();

      console.log('Governance evidence chain test completed successfully');

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

  test('governance evidence chain shows chain from audit log', async ({ page }) => {
    const suffix = Date.now().toString().slice(-8);
    const releaseOrderNo = `REL-AUDIT-CHAIN-${suffix}`;

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
      expect(policyResponse.ok()).toBeTruthy();

      // Step 2: Create and execute release order with approval
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

      // Click execute and wait for response
      const responsePromise = page.waitForResponse(resp =>
        resp.url().includes('/api/releases/') &&
        resp.url().includes('/execute') &&
        resp.request().method() === 'POST'
      );
      await releaseRow.getByRole('button', { name: '执行发布', exact: true }).click();

      const confirmDialog = page.locator('.el-message-box');
      if (await confirmDialog.isVisible()) {
        await confirmDialog.getByRole('button', { name: 'OK', exact: true }).click();
      }

      const response = await responsePromise;
      const responseBody = await response.json();
      const approvalId = responseBody?.data?.approvalId ?? responseBody?.approvalId;
      expect(approvalId).toBeTruthy();

      await page.request.post(`/api/approvals/requests/${approvalId}/approve`, {
        headers: await getAuthHeaders(page),
        data: { approveNote: 'E2E test' }
      });

      // Step 3: Navigate to audit logs
      await openPage(page, '审计日志');
      await page.waitForTimeout(1000);

      // Filter by APPROVAL - use specific selector for the dropdown
      const bizTypeSelect = page.locator('.filter-bar').locator('.el-select').first();
      await bizTypeSelect.click();
      await page.waitForTimeout(500);
      // Use .first() to handle multiple matching elements
      await page.locator('.el-select-dropdown__item').filter({ hasText: 'APPROVAL' }).first().click();
      await page.getByRole('button', { name: '搜索', exact: true }).click();
      await page.waitForTimeout(1500);

      // Find the audit row for this approval
      const auditRow = await findRowAcrossPages(page, 'APPROVAL_APPROVE');
      await expect(auditRow).toBeVisible();

      // Step 4: Open detail dialog
      await auditRow.getByRole('button', { name: '详情' }).click();
      await expect(activeDialog(page)).toBeVisible();

      // Step 5: Click "查看证据链" button
      const chainButton = activeDialog(page).getByRole('button', { name: '查看证据链' });
      await expect(chainButton).toBeVisible();
      await chainButton.click();

      // Step 6: Verify navigation to governance chain page
      await page.waitForURL(url => url.pathname.includes('/governance-chain'));
      await page.waitForTimeout(1000);

      // Step 7: Verify the page shows relevant data
      const businessSummaryCard = page.locator('.el-card').filter({ hasText: '业务摘要' });
      await expect(businessSummaryCard).toBeVisible();

      console.log('Governance evidence chain from audit test completed successfully');

    } finally {
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
        console.warn('Cleanup failed:', e);
      }
    }
  });
});
