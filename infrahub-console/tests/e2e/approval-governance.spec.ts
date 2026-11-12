import { expect, test, type Page } from '@playwright/test';
import { loginAsAdmin } from './helpers/auth';
import {
  deleteRow,
  expectRowVisible,
  fillNumberField,
  fillTextField,
  openCreateDialog,
  saveDialog,
  tableRow,
} from './helpers/ui';

async function getAuthHeaders(page: Page) {
  const token = await page.evaluate(() => window.localStorage.getItem('INFRAHUB_TOKEN'));
  expect(token).toBeTruthy();
  return { Authorization: `Bearer ${token}` };
}

test.describe('approval governance', () => {
  test.setTimeout(180000);

  test.beforeEach(async ({ page }) => {
    await loginAsAdmin(page);
  });

  test('approval center page loads and shows filters', async ({ page }) => {
    await page.goto('/system/approvals');
    await expect(page.getByRole('heading', { name: '审批中心' })).toBeVisible();
    await expect(page.locator('.filter-bar')).toBeVisible();

    // Check status dropdown options
    const statusSelect = page.locator('.filter-bar .el-select').first();
    await statusSelect.click();

    // Wait for dropdown options to appear
    await expect(page.getByRole('option', { name: '待审批' })).toBeVisible({ timeout: 5000 });
    await expect(page.getByRole('option', { name: '已通过' })).toBeVisible();
    await expect(page.getByRole('option', { name: '已拒绝' })).toBeVisible();
    await expect(page.getByRole('option', { name: '已取消' })).toBeVisible();
    await page.keyboard.press('Escape');
  });

  test('approval center shows pagination', async ({ page }) => {
    await page.goto('/system/approvals');
    await expect(page.locator('.el-pagination')).toBeVisible();
  });

  test('approval center navigation from sidebar', async ({ page }) => {
    await page.locator('aside.layout-sidebar').getByRole('link', { name: '审批中心' }).click();
    await expect(page.getByRole('heading', { name: '审批中心' })).toBeVisible();
    expect(page.url()).toContain('/system/approvals');
  });

  test('alert rule toggle creates approval and approval flow completes', async ({ page }) => {
    const suffix = Date.now().toString().slice(-8);
    const ruleName = `approval-test-${suffix}`;

    try {
      // Step 1: Enable approval policy via API
      const policyResponse = await page.request.put('/api/approvals/policies', {
        headers: await getAuthHeaders(page),
        data: {
          bizType: 'MONITOR_ALERT_RULE',
          action: 'TOGGLE_RULE',
          enabled: true
        }
      });
      console.log('Policy response status:', policyResponse.status());
      expect(policyResponse.ok()).toBeTruthy();

      // Step 2: Create an enabled alert rule
      const createDialog = await openCreateDialog(page, '告警规则');
      await fillTextField(createDialog, '规则名称', ruleName);
      await fillTextField(createDialog, '指标名', 'cpu_usage');
      await fillNumberField(createDialog, '阈值', '80');
      await fillNumberField(createDialog, '持续时间', '60');
      await saveDialog(page);
      await expectRowVisible(page, ruleName);

      // Step 3: Try to disable the rule - should create approval, not direct execute
      const row = tableRow(page, ruleName);

      // Click disable button and wait for response
      const responsePromise = page.waitForResponse(resp => resp.url().includes('/enabled') && resp.request().method() === 'PUT');
      await row.getByRole('button', { name: '禁用', exact: true }).click();
      const response = await responsePromise;
      console.log('Toggle response status:', response.status());

      // Extract approvalId from response body - MUST be present
      const responseBody = await response.json();
      const approvalId = responseBody?.data?.approvalId ?? responseBody?.approvalId;
      console.log('Extracted approvalId:', approvalId);
      expect(approvalId).toBeTruthy();

      // Should show approval submitted message
      await expect(page.locator('.el-message').getByText('审批已提交，待管理员处理')).toBeVisible({ timeout: 5000 });

      // US-002: Assert business state unchanged - rule should still be enabled (show '禁用' button)
      // This verifies approval creation does NOT directly execute the toggle
      const ruleRowAfterToggle = tableRow(page, ruleName);
      await expect(ruleRowAfterToggle.getByRole('button', { name: '禁用', exact: true })).toBeVisible({ timeout: 3000 });

      // Step 4: Approve via API directly (avoids UI pagination/fallback issues)
      const approveResponse = await page.request.post(`/api/approvals/requests/${approvalId}/approve`, {
        headers: await getAuthHeaders(page),
        data: { approveNote: 'Test approval - OK' }
      });
      console.log('Approve response status:', approveResponse.status());
      expect(approveResponse.ok()).toBeTruthy();

      // Step 5: Navigate to alert rules and verify rule is now disabled
      await page.locator('aside.layout-sidebar').getByRole('link', { name: '告警规则' }).click();
      await expect(page.getByRole('heading', { name: '告警规则' })).toBeVisible();

      // Force refresh to load updated data after approval execution
      await page.getByRole('button', { name: '刷新', exact: true }).click();
      await page.waitForTimeout(1000);

      // Verify rule is now disabled (show '启用' button)
      const ruleRowFinal = tableRow(page, ruleName);
      await expect(ruleRowFinal.getByRole('button', { name: '启用', exact: true })).toBeVisible({ timeout: 5000 });

    } finally {
      // Cleanup always runs even on failure. Disable the policy first so any
      // recovery toggle executes directly instead of creating another approval.
      try {
        await page.request.put('/api/approvals/policies', {
          headers: await getAuthHeaders(page),
          data: {
            bizType: 'MONITOR_ALERT_RULE',
            action: 'TOGGLE_RULE',
            enabled: false
          }
        });
      } catch (e) {
        console.log('Cleanup: failed to disable policy', e);
      }

      try {
        await page.goto('/monitor/alert-rules');
        await expect(page.getByRole('heading', { name: '告警规则' })).toBeVisible();

        const cleanupRow = tableRow(page, ruleName);
        if (await cleanupRow.isVisible()) {
          const enableButton = cleanupRow.getByRole('button', { name: '启用', exact: true });
          if (await enableButton.isVisible()) {
            await enableButton.click();
            await expect(cleanupRow.getByRole('button', { name: '禁用', exact: true })).toBeVisible({ timeout: 3000 });
          }

          await deleteRow(page, ruleName);
        }
      } catch (e) {
        console.log('Cleanup: failed to delete rule', e);
      }
    }
  });
});
