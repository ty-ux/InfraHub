import { expect, test } from '@playwright/test';
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

test.describe('audit evidence chain', () => {
  test.setTimeout(90000);

  test.beforeEach(async ({ page }) => {
    await loginAsAdmin(page);
  });

  test('alert rule toggle creates audit record with before/after data', async ({ page }) => {
    const suffix = Date.now().toString().slice(-8);
    const ruleName = `audit-chain-${suffix}`;

    try {
      // Step 1: Create an alert rule
      const createDialog = await openCreateDialog(page, '告警规则');
      await fillTextField(createDialog, '规则名称', ruleName);
      await fillTextField(createDialog, '指标名', 'cpu_usage');
      await fillNumberField(createDialog, '阈值', '80');
      await fillNumberField(createDialog, '持续时间', '60');
      await saveDialog(page);
      await expectRowVisible(page, ruleName);

      // Step 2: Disable the rule (toggle from enabled to disabled)
      await tableRow(page, ruleName).getByRole('button', { name: '禁用', exact: true }).click();
      await expect(tableRow(page, ruleName).getByRole('button', { name: '启用', exact: true })).toBeVisible();

      // Step 3: Navigate to audit logs page
      await page.locator('aside.layout-sidebar').getByRole('link', { name: '审计日志' }).click();
      await expect(page.getByRole('heading', { name: '审计日志' })).toBeVisible();

      // Wait for data to load
      await page.waitForTimeout(1500);

      // Step 4: Apply filters - MONITOR_ALERT_RULE + TOGGLE_RULE + SUCCESS
      const selects = page.locator('.filter-bar .el-select');

      // Select bizType = MONITOR_ALERT_RULE
      await selects.nth(0).click();
      await page.getByRole('option', { name: 'MONITOR_ALERT_RULE' }).click();
      await page.waitForTimeout(300);

      // Select action = TOGGLE_RULE
      await selects.nth(1).click();
      await page.getByRole('option', { name: 'TOGGLE_RULE' }).click();
      await page.waitForTimeout(300);

      // Select result = SUCCESS (displays as "成功" in Chinese)
      await selects.nth(2).click();
      await page.getByRole('option', { name: '成功' }).click();
      await page.waitForTimeout(1000);

      // Step 5: Verify audit record exists
      const auditTable = page.locator('.table-card .el-table');
      await expect(auditTable).toBeVisible();

      const toggleRow = auditTable.locator('tbody tr').filter({ hasText: 'TOGGLE_RULE' }).first();
      await expect(toggleRow).toBeVisible({ timeout: 10000 });

      // Step 6: Open detail dialog and verify before/after data
      await toggleRow.getByRole('button', { name: '详情' }).click();
      const detailDialog = page.getByRole('dialog');
      await expect(detailDialog).toBeVisible();

      // Verify beforeData contains ruleName and enabled: true
      const beforeDataSection = detailDialog.locator('.data-section').filter({ hasText: '变更前' });
      await expect(beforeDataSection).toBeVisible();
      const beforeJsonText = await beforeDataSection.locator('.json-pre').textContent() || '';
      expect(beforeJsonText).toContain('ruleName');
      expect(beforeJsonText).toContain(ruleName);
      expect(beforeJsonText).toContain('enabled');
      expect(beforeJsonText).toContain('true');

      // Verify afterData contains ruleName and enabled: false
      const afterDataSection = detailDialog.locator('.data-section').filter({ hasText: '变更后' });
      await expect(afterDataSection).toBeVisible();
      const afterJsonText = await afterDataSection.locator('.json-pre').textContent() || '';
      expect(afterJsonText).toContain('ruleName');
      expect(afterJsonText).toContain(ruleName);
      expect(afterJsonText).toContain('enabled');
      expect(afterJsonText).toContain('false');

      // Close detail dialog
      await page.keyboard.press('Escape');
      await expect(detailDialog).toBeHidden();

    } finally {
      // Cleanup: Re-enable and delete the rule (even on test failure)
      try {
        await page.locator('aside.layout-sidebar').getByRole('link', { name: '告警规则' }).click();
        await expect(page.getByRole('heading', { name: '告警规则' })).toBeVisible();
        await page.waitForTimeout(500);

        const cleanupRow = tableRow(page, ruleName);
        if (await cleanupRow.isVisible()) {
          // If showing "启用" button, click to re-enable first (can only delete when enabled)
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
