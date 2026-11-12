import { expect, test } from '@playwright/test';
import { loginAsAdmin } from './helpers/auth';
import {
  activeDialog,
  deleteRow,
  expectRowVisible,
  fillNumberField,
  fillTextArea,
  fillTextField,
  openCreateDialog,
  openPage,
  saveDialog,
  tableRow,
} from './helpers/ui';

test.describe('monitor evaluation', () => {
  test.setTimeout(90000);

  test.beforeEach(async ({ page }) => {
    await loginAsAdmin(page);
  });

  test('manual snapshot and rule evaluation auto-create alert event', async ({ page }) => {
    const suffix = Date.now().toString().slice(-8);
    const ruleName = `e2e-b5-alert-${suffix}`;
    const metricName = `b5_metric_${suffix}`;
    const source = `B5-E2E-${suffix}`;
    const payload = `{"${metricName}":85.5}`;

    const ruleDialog = await openCreateDialog(page, '告警规则');
    await fillTextField(ruleDialog, '规则名称', ruleName);
    await fillTextField(ruleDialog, '指标名', metricName);
    await fillNumberField(ruleDialog, '阈值', '80');
    await fillNumberField(ruleDialog, '持续时间', '60');
    await saveDialog(page);
    await expectRowVisible(page, ruleName);

    await openPage(page, '监控快照');
    await page.getByRole('button', { name: '创建手动快照', exact: true }).click();
    let dialog = activeDialog(page);
    await expect(dialog).toBeVisible();
    await fillNumberField(dialog, '目标ID', '1');
    await fillTextArea(dialog, '指标数据', payload);
    await fillTextField(dialog, '来源', source);
    await saveDialog(page);
    await expectRowVisible(page, source);

    await openPage(page, '告警规则');
    await page.getByRole('button', { name: '评估规则', exact: true }).click();
    dialog = activeDialog(page);
    await expect(dialog).toBeVisible();
    await fillNumberField(dialog, '目标ID', '1');
    await fillTextArea(dialog, '指标数据', payload);
    await dialog.getByRole('button', { name: '评估规则', exact: true }).click();

    const resultDialog = activeDialog(page);
    await expect(resultDialog).toBeVisible();
    await expect(resultDialog).toContainText('命中数量');
    await expect(resultDialog).toContainText(ruleName);
    await resultDialog.getByRole('button', { name: 'Close this dialog' }).click();

    await openPage(page, '告警事件');
    await expectRowVisible(page, ruleName);
  });

  // B6: Alert Lifecycle - FIRING to RESOLVED auto-recovery
  test('B6 lifecycle: create alert, auto-resolve when recovered', async ({ page }) => {
    const suffix = Date.now().toString().slice(-8);
    const ruleName = `e2e-b6-lifecycle-${suffix}`;
    const metricName = `b6_lifecycle_${suffix}`;

    // Step 1: Create an alert rule
    const ruleDialog = await openCreateDialog(page, '告警规则');
    await fillTextField(ruleDialog, '规则名称', ruleName);
    await fillTextField(ruleDialog, '指标名', metricName);
    await fillNumberField(ruleDialog, '阈值', '80');
    await fillNumberField(ruleDialog, '持续时间', '60');
    await saveDialog(page);
    await expectRowVisible(page, ruleName);

    // Step 2: Evaluate with threshold-exceeding payload -> creates FIRING event
    const firingPayload = `{"${metricName}": 95.5}`;
    await page.getByRole('button', { name: '评估规则', exact: true }).click();
    let dialog = activeDialog(page);
    await expect(dialog).toBeVisible();
    await fillNumberField(dialog, '目标ID', '1');
    await fillTextArea(dialog, '指标数据', firingPayload);
    await dialog.getByRole('button', { name: '评估规则', exact: true }).click();

    // Verify hit in result dialog
    const resultDialog = activeDialog(page);
    await expect(resultDialog).toBeVisible();
    await expect(resultDialog).toContainText('命中数量');
    await expect(resultDialog).toContainText(ruleName);
    await resultDialog.getByRole('button', { name: 'Close this dialog' }).click();

    // Step 3: Verify FIRING event in Alert Events page
    await openPage(page, '告警事件');
    await expectRowVisible(page, ruleName);
    // Check FIRING status cell
    const firingRow = tableRow(page, ruleName);
    await expect(firingRow.getByRole('cell', { name: 'FIRING' })).toBeVisible();

    // Step 4: Evaluate with recovered payload -> should resolve the event
    await openPage(page, '告警规则');
    const recoveredPayload = `{"${metricName}": 50.0}`;
    await page.getByRole('button', { name: '评估规则', exact: true }).click();
    dialog = activeDialog(page);
    await expect(dialog).toBeVisible();
    await fillNumberField(dialog, '目标ID', '1');
    await fillTextArea(dialog, '指标数据', recoveredPayload);
    await dialog.getByRole('button', { name: '评估规则', exact: true }).click();

    // Verify recovery info in result dialog
    const recoveryDialog = activeDialog(page);
    await expect(recoveryDialog).toBeVisible();
    await expect(recoveryDialog).toContainText('恢复数量');
    await expect(recoveryDialog.getByText(/已恢复\s*1\s*条告警事件/)).toBeVisible();
    await recoveryDialog.getByRole('button', { name: 'Close this dialog' }).click();

    // Step 5: Verify RESOLVED status in Alert Events page
    await openPage(page, '告警事件');
    await expectRowVisible(page, ruleName);
    const resolvedRow = tableRow(page, ruleName);
    await expect(resolvedRow.getByRole('cell', { name: 'RESOLVED' })).toBeVisible({ timeout: 10000 });

    // Cleanup
    await openPage(page, '告警规则');
    await deleteRow(page, ruleName);
  });

  // B6: Duplicate prevention - same rule+target should not create duplicate FIRING
  test('B6 no duplicate: re-evaluate while FIRING should not create new event', async ({ page }) => {
    const suffix = Date.now().toString().slice(-8);
    const ruleName = `e2e-b6-nodup-${suffix}`;
    const metricName = `b6_nodup_${suffix}`;

    // Step 1: Create an alert rule
    const ruleDialog = await openCreateDialog(page, '告警规则');
    await fillTextField(ruleDialog, '规则名称', ruleName);
    await fillTextField(ruleDialog, '指标名', metricName);
    await fillNumberField(ruleDialog, '阈值', '80');
    await fillNumberField(ruleDialog, '持续时间', '60');
    await saveDialog(page);
    await expectRowVisible(page, ruleName);

    // Step 2: First evaluation -> creates FIRING event
    const payload = `{"${metricName}": 90.0}`;
    await page.getByRole('button', { name: '评估规则', exact: true }).click();
    let dialog = activeDialog(page);
    await expect(dialog).toBeVisible();
    await fillNumberField(dialog, '目标ID', '2');
    await fillTextArea(dialog, '指标数据', payload);
    await dialog.getByRole('button', { name: '评估规则', exact: true }).click();
    await expect(activeDialog(page)).toContainText('命中数量');
    await activeDialog(page).getByRole('button', { name: 'Close this dialog' }).click();
    await expect(page.getByRole('dialog')).toHaveCount(0, { timeout: 5000 });

    // Step 3: Verify FIRING event exists in Alert Events
    await openPage(page, '告警事件');
    await expect(tableRow(page, ruleName)).toBeVisible({ timeout: 10000 });
    const firingRow = tableRow(page, ruleName);
    await expect(firingRow.getByRole('cell', { name: 'FIRING' })).toBeVisible();

    // Step 4: Second evaluation with same payload
    await openPage(page, '告警规则');
    await page.getByRole('button', { name: '评估规则', exact: true }).click();
    dialog = activeDialog(page);
    await expect(dialog).toBeVisible();
    await fillNumberField(dialog, '目标ID', '2');
    await fillTextArea(dialog, '指标数据', payload);
    await dialog.getByRole('button', { name: '评估规则', exact: true }).click();
    await expect(activeDialog(page)).toContainText('命中数量');
    await activeDialog(page).getByRole('button', { name: 'Close this dialog' }).click();
    await expect(page.getByRole('dialog')).toHaveCount(0, { timeout: 5000 });

    // Step 5: Verify still only ONE FIRING event (no duplicate)
    await openPage(page, '告警事件');
    await page.waitForTimeout(1000); // Wait for data load
    await page.getByRole('button', { name: '刷新', exact: true }).click();
    await page.waitForTimeout(500);
    const eventCount = await page.locator('tbody tr').filter({ hasText: ruleName }).count();
    // B6 requirement: same rule+target should not create duplicate FIRING events
    expect(eventCount).toBe(1);

    // Cleanup: resolve the event then delete rule
    await openPage(page, '告警规则');
    const recoveredPayload = `{"${metricName}": 50.0}`;
    await page.getByRole('button', { name: '评估规则', exact: true }).click();
    dialog = activeDialog(page);
    await fillNumberField(dialog, '目标ID', '2');
    await fillTextArea(dialog, '指标数据', recoveredPayload);
    await dialog.getByRole('button', { name: '评估规则', exact: true }).click();
    // Close dialogs by pressing Escape twice
    await page.keyboard.press('Escape');
    await page.waitForTimeout(300);
    await page.keyboard.press('Escape');
    await page.waitForTimeout(500);
    // Ensure dialogs are closed
    while ((await page.getByRole('dialog').count()) > 0) {
      await page.keyboard.press('Escape');
      await page.waitForTimeout(300);
    }
    await deleteRow(page, ruleName);
  });

  // B9: Manual resolve - click button to resolve FIRING alert with note
  test('B9 manual resolve: create FIRING alert and manually resolve with note', async ({ page }) => {
    const suffix = Date.now().toString().slice(-8);
    const ruleName = `e2e-b9-manual-${suffix}`;
    const metricName = `b9_manual_${suffix}`;
    const handleNote = '人工排查后已重启服务恢复正常';

    // Step 1: Create an alert rule
    const ruleDialog = await openCreateDialog(page, '告警规则');
    await fillTextField(ruleDialog, '规则名称', ruleName);
    await fillTextField(ruleDialog, '指标名', metricName);
    await fillNumberField(ruleDialog, '阈值', '80');
    await fillNumberField(ruleDialog, '持续时间', '60');
    await saveDialog(page);
    await expectRowVisible(page, ruleName);

    // Step 2: Evaluate with threshold-exceeding payload -> creates FIRING event
    const firingPayload = `{"${metricName}": 95.5}`;
    await page.getByRole('button', { name: '评估规则', exact: true }).click();
    let dialog = activeDialog(page);
    await expect(dialog).toBeVisible();
    await fillNumberField(dialog, '目标ID', '3');
    await fillTextArea(dialog, '指标数据', firingPayload);
    await dialog.getByRole('button', { name: '评估规则', exact: true }).click();

    // Verify hit in result dialog
    const resultDialog = activeDialog(page);
    await expect(resultDialog).toBeVisible();
    await expect(resultDialog).toContainText('命中数量');
    await resultDialog.getByRole('button', { name: 'Close this dialog' }).click();

    // Step 3: Open Alert Events page and verify FIRING status
    await openPage(page, '告警事件');
    await expectRowVisible(page, ruleName);
    const firingRow = tableRow(page, ruleName);
    await expect(firingRow.getByRole('cell', { name: 'FIRING' })).toBeVisible();

    // Step 4: Click manual resolve button on the FIRING row
    await expect(firingRow.getByRole('button', { name: '手动恢复', exact: true })).toBeEnabled();
    await firingRow.getByRole('button', { name: '手动恢复', exact: true }).click();

    // Step 5: Fill handle note in dialog and submit
    const resolveDialog = activeDialog(page);
    await expect(resolveDialog).toBeVisible();
    await expect(resolveDialog).toContainText('手动恢复');
    await fillTextArea(resolveDialog, '处理备注', handleNote);
    await resolveDialog.getByRole('button', { name: '确认恢复', exact: true }).click();

    // Step 6: Wait for success message and dialog close
    await expect(page.getByRole('dialog')).toHaveCount(0, { timeout: 5000 });
    await expect(page.getByText('告警已恢复')).toBeVisible({ timeout: 5000 });

    // Step 7: Verify RESOLVED status and handle note visible in the row
    await page.getByRole('button', { name: '刷新', exact: true }).click();
    await page.waitForTimeout(500);
    const resolvedRow = tableRow(page, ruleName);
    await expect(resolvedRow.getByRole('cell', { name: 'RESOLVED' })).toBeVisible({ timeout: 10000 });

    // Step 8: Verify handle note visible in the row's handleNote column
    await expect(resolvedRow.getByRole('cell', { name: handleNote })).toBeVisible({ timeout: 5000 });

    // Cleanup: delete rule
    await openPage(page, '告警规则');
    while ((await page.getByRole('dialog').count()) > 0) {
      await page.keyboard.press('Escape');
      await page.waitForTimeout(300);
    }
    await deleteRow(page, ruleName);
  });
});
