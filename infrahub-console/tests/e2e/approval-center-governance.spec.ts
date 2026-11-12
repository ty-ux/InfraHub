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

test.describe('approval center governance', () => {
  test.setTimeout(180000);

  test.beforeEach(async ({ page }) => {
    await loginAsAdmin(page);
  });

  test('release approval record display and navigation', async ({ page }) => {
    const suffix = Date.now().toString().slice(-8);
    const releaseOrderNo = `REL-GOV-${suffix}`;

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

      // Step 4: Navigate to Approval Center (审批中心)
      await openPage(page, '审批中心');
      await page.waitForTimeout(1000);

      // Step 5: Just click search to load all records (no filter needed)
      await page.getByRole('button', { name: '搜索', exact: true }).click();
      await page.waitForTimeout(2000);

      // Step 6: Assert business summary shows the orderNo
      // Wait for table to load
      await page.waitForSelector('tbody tr', { timeout: 10000 });
      await page.waitForTimeout(1000);

      // Step 6: Assert business summary (业务摘要) shows the orderNo
      // The business summary column should display the release order number
      const approvalRow = await findRowAcrossPages(page, releaseOrderNo);
      await expect(approvalRow).toBeVisible();
      await expect(approvalRow).toContainText(releaseOrderNo);

      // Step 7: Open detail, assert requestPayload pretty print contains orderNo and EXECUTE_RELEASE
      await approvalRow.getByRole('button', { name: '查看详情' }).click();
      await expect(activeDialog(page)).toBeVisible();

      // Verify requestPayload contains orderNo (in .json-pre element - it's the last one in detail dialog)
      const requestPayloadSection = activeDialog(page).locator('.json-pre').last();
      await expect(requestPayloadSection).toContainText(releaseOrderNo);
      // Verify contains EXECUTE_RELEASE action
      await expect(requestPayloadSection).toContainText('EXECUTE_RELEASE');

      // Step 8: Click "查看发布单" button in the detail dialog (in .business-nav section)
      const businessNavButton = activeDialog(page).locator('.business-nav button');
      await expect(businessNavButton).toBeVisible();
      await businessNavButton.click();

      // Wait for navigation and verify URL
      await page.waitForURL(url => {
        const urlStr = url.toString();
        return urlStr.includes('/release/orders') &&
               urlStr.includes(`keyword=${releaseOrderNo}`) &&
               urlStr.includes(`focus=${releaseOrderNo}`);
      });

      // Verify we're on the release orders page with the correct order
      await expect(page.getByRole('heading', { name: '发布单' })).toBeVisible();
      const targetReleaseRow = tableRow(page, releaseOrderNo);
      await expect(targetReleaseRow).toBeVisible();

      console.log('Test completed successfully for release order:', releaseOrderNo);

    } finally {
      // Cleanup: Disable the approval policy
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
        console.log('Cleanup: failed to disable policy', e);
      }
    }
  });
});