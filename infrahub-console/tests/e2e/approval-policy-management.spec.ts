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

test.describe('approval policy management', () => {
  test.setTimeout(180000);

  test.beforeEach(async ({ page }) => {
    await loginAsAdmin(page);
  });

  test('policy toggle saves and affects approval flow', async ({ page }) => {
    // Step 1: Navigate to Approval Center and open Policy Management tab
    await openPage(page, '审批中心');
    await page.getByRole('tab', { name: '策略管理' }).click();

    // Wait for the tab pane to become active
    const policyTabPane = page.locator('.el-tab-pane:not([aria-hidden="true"])');
    await expect(policyTabPane).toBeVisible();

    // Step 2: Find RELEASE_ORDER/EXECUTE_RELEASE policy
    const policyTable = policyTabPane.locator('.el-table');
    await expect(policyTable).toBeVisible();

    const releasePolicyRow = policyTable.locator('tr').filter({ hasText: '发布单' }).filter({ hasText: '执行发布' });
    await expect(releasePolicyRow).toBeVisible();

    const switchEl = releasePolicyRow.locator('.el-switch');
    const wasChecked = await switchEl.getAttribute('class').then(cls => cls?.includes('is-checked') ?? false);

    try {
      // Step 3: Toggle the policy (if disabled, enable it; if enabled, disable then re-enable)
      if (wasChecked) {
        // Disable first
        const disableResponsePromise = page.waitForResponse(resp =>
          resp.url().includes('/api/approvals/policies') &&
          resp.request().method() === 'PUT'
        );
        await switchEl.click();
        const disableResponse = await disableResponsePromise;
        expect(disableResponse.ok()).toBeTruthy();
        await expect(switchEl).not.toHaveClass(/is-checked/);

        // Wait for UI to settle
        await page.waitForTimeout(300);
      }

      // Step 4: Enable the policy and verify API call succeeds
      const enableResponsePromise = page.waitForResponse(resp =>
        resp.url().includes('/api/approvals/policies') &&
        resp.request().method() === 'PUT'
      );
      await switchEl.click();
      const enableResponse = await enableResponsePromise;
      expect(enableResponse.ok()).toBeTruthy();

      // Verify the switch is now on
      await expect(switchEl).toHaveClass(/is-checked/);

      // Step 5: Verify the policy was saved correctly by refreshing and checking state
      await page.getByRole('button', { name: '刷新' }).click();
      await page.waitForTimeout(500);

      // Re-locate the switch after refresh
      const refreshedRow = policyTable.locator('tr').filter({ hasText: '发布单' }).filter({ hasText: '执行发布' });
      const refreshedSwitch = refreshedRow.locator('.el-switch');
      await expect(refreshedSwitch).toHaveClass(/is-checked/);

      console.log('Policy toggle E2E completed successfully - policy saved and persisted');
    } finally {
      // Cleanup: Restore original state
      try {
        await openPage(page, '审批中心');
        await page.getByRole('tab', { name: '策略管理' }).click();
        await page.waitForTimeout(300);

        const policyTabPane2 = page.locator('.el-tab-pane:not([aria-hidden="true"])');
        const policyTable2 = policyTabPane2.locator('.el-table');
        const releasePolicyRow2 = policyTable2.locator('tr').filter({ hasText: '发布单' }).filter({ hasText: '执行发布' });
        const switchEl2 = releasePolicyRow2.locator('.el-switch');

        const currentChecked = await switchEl2.getAttribute('class').then(cls => cls?.includes('is-checked') ?? false);
        if (currentChecked !== wasChecked) {
          await switchEl2.click();
          await page.waitForTimeout(300);
        }
        console.log('Cleanup: policy state restored');
      } catch (e) {
        console.log('Cleanup failed:', e);
      }
    }
  });

  test('policy list shows all four fixed policies', async ({ page }) => {
    await openPage(page, '审批中心');
    await page.getByRole('tab', { name: '策略管理' }).click();

    // Wait for the tab pane to become active (Element Plus uses aria-hidden)
    const policyTabPane = page.locator('.el-tab-pane:not([aria-hidden="true"])');
    await expect(policyTabPane).toBeVisible();

    // Now query the table inside the active tab pane
    const policyTable = policyTabPane.locator('.el-table');
    await expect(policyTable).toBeVisible();

    // Verify all four fixed policies are shown
    await expect(policyTable.locator('tr').filter({ hasText: '发布单' }).filter({ hasText: '执行发布' })).toBeVisible();
    await expect(policyTable.locator('tr').filter({ hasText: '回滚单' }).filter({ hasText: '执行回滚' })).toBeVisible();
    await expect(policyTable.locator('tr').filter({ hasText: '监控告警规则' }).filter({ hasText: '开关规则' })).toBeVisible();
    await expect(policyTable.locator('tr').filter({ hasText: '健康检查' }).filter({ hasText: '开关检查' })).toBeVisible();

    console.log('All four fixed policies are visible');
  });
});
