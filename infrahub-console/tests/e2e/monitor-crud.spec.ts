import { expect, test } from '@playwright/test';
import { loginAsAdmin } from './helpers/auth';
import {
  deleteRow,
  expectRowVisible,
  fillNumberField,
  fillTextField,
  openCreateDialog,
  openEditDialog,
  saveDialog,
  tableRow,
} from './helpers/ui';

test.describe('monitor CRUD', () => {
  test.setTimeout(90000);

  test.beforeEach(async ({ page }) => {
    await loginAsAdmin(page);
  });

  test('alert rule can be created, toggled, updated and deleted', async ({ page }) => {
    const suffix = Date.now().toString().slice(-8);
    const ruleName = `e2e-alert-${suffix}`;
    const updatedRuleName = `${ruleName}-updated`;

    const createDialog = await openCreateDialog(page, '告警规则');
    await fillTextField(createDialog, '规则名称', ruleName);
    await fillTextField(createDialog, '指标名', 'cpu_usage');
    await fillNumberField(createDialog, '阈值', '80');
    await fillNumberField(createDialog, '持续时间', '60');
    await saveDialog(page);

    await expectRowVisible(page, ruleName);

    await tableRow(page, ruleName).getByRole('button', { name: '禁用', exact: true }).click();
    await expect(tableRow(page, ruleName).getByRole('button', { name: '启用', exact: true })).toBeVisible();

    await tableRow(page, ruleName).getByRole('button', { name: '启用', exact: true }).click();
    await expect(tableRow(page, ruleName).getByRole('button', { name: '禁用', exact: true })).toBeVisible();

    const editDialog = await openEditDialog(page, ruleName);
    await fillTextField(editDialog, '规则名称', updatedRuleName);
    await saveDialog(page);

    await expectRowVisible(page, updatedRuleName);
    await deleteRow(page, updatedRuleName);
  });

  test('health check can be created, toggled, updated and deleted', async ({ page }) => {
    const suffix = Date.now().toString().slice(-8);
    const endpoint = `http://e2e-health-${suffix}.local/health`;
    const updatedEndpoint = `http://e2e-health-${suffix}.local/ready`;

    const createDialog = await openCreateDialog(page, '健康检查');
    await fillNumberField(createDialog, '目标ID', '1');
    await fillTextField(createDialog, '检查地址', endpoint);
    await fillTextField(createDialog, '期望结果', '200');
    await fillNumberField(createDialog, '超时', '30');
    await saveDialog(page);

    await expectRowVisible(page, endpoint);

    await tableRow(page, endpoint).getByRole('button', { name: '禁用', exact: true }).click();
    await expect(tableRow(page, endpoint).getByRole('button', { name: '启用', exact: true })).toBeVisible();

    await tableRow(page, endpoint).getByRole('button', { name: '启用', exact: true }).click();
    await expect(tableRow(page, endpoint).getByRole('button', { name: '禁用', exact: true })).toBeVisible();

    const editDialog = await openEditDialog(page, endpoint);
    await fillTextField(editDialog, '检查地址', updatedEndpoint);
    await saveDialog(page);

    await expectRowVisible(page, updatedEndpoint);
    await deleteRow(page, updatedEndpoint);
  });

  // B5: Manual Snapshot → Rule Evaluation → Alert Event Flow
  test('B5 main flow: create snapshot, evaluate rules, and verify alert events', async ({ page }) => {
    const suffix = Date.now().toString().slice(-8);
    const ruleName = `e2e-eval-rule-${suffix}`;

    // Step 1: Create an alert rule for evaluation
    const createRuleDialog = await openCreateDialog(page, '告警规则');
    await fillTextField(createRuleDialog, '规则名称', ruleName);
    await fillTextField(createRuleDialog, '指标名', 'cpu_usage');
    await fillNumberField(createRuleDialog, '阈值', '80');
    await fillNumberField(createRuleDialog, '持续时间', '60');
    await saveDialog(page);
    await expectRowVisible(page, ruleName);

    // Step 2: Navigate to Snapshots page and create a manual snapshot
    await page.getByRole('link', { name: '监控快照' }).click();
    await expect(page.getByRole('heading', { name: '监控快照' })).toBeVisible();

    // Click "创建手动快照" button
    await page.getByRole('button', { name: '创建手动快照' }).click();
    await expect(page.getByRole('dialog')).toBeVisible();

    // Fill the manual snapshot form - Element Plus uses nested inputs
    const snapshotDialog = page.getByRole('dialog');
    // Target ID is the first input-number
    await snapshotDialog.locator('.el-input-number input').fill('1');
    // Metric payload textarea
    await snapshotDialog.locator('.el-textarea__inner').fill('{"cpu_usage": 85.5}');
    await snapshotDialog.getByRole('button', { name: '保存' }).click();

    // Wait for success toast
    await expect(page.locator('.el-message--success').first()).toBeVisible({ timeout: 10000 });
    // Close dialog by pressing Escape if still open
    await page.keyboard.press('Escape');
    await page.waitForTimeout(500);

    // Step 3: Navigate to Alert Rules page and evaluate rules
    await page.getByRole('link', { name: '告警规则' }).click();
    await expect(page.getByRole('heading', { name: '告警规则' })).toBeVisible();

    // Click "评估规则" button
    await page.getByRole('button', { name: '评估规则' }).click();
    await expect(page.getByRole('dialog')).toBeVisible();

    // Fill the evaluation form - Element Plus uses nested inputs
    const evalDialog = page.getByRole('dialog');
    // Target ID is the first input-number
    await evalDialog.locator('.el-input-number input').fill('1');
    // Metric payload textarea
    await evalDialog.locator('.el-textarea__inner').fill('{"cpu_usage": 85.5}');
    await evalDialog.getByRole('button', { name: '评估规则' }).click();

    // Wait for result dialog showing hit rules
    await expect(page.getByRole('dialog').filter({ hasText: '评估结果' })).toBeVisible();
    await expect(page.getByRole('dialog').getByText('命中数量')).toBeVisible();

    // Close result dialog
    await page.getByRole('dialog').getByRole('button', { name: '关闭' }).click();
    await expect(page.getByRole('dialog')).toBeHidden();

    // Step 4: Navigate to Alert Events page to verify auto-generated events
    await page.getByRole('link', { name: '告警事件' }).click();
    await expect(page.getByRole('heading', { name: '告警事件' })).toBeVisible();

    // Verify an alert event was created (check for FIRING status)
    await expect(page.getByRole('cell', { name: 'FIRING' }).first()).toBeVisible({ timeout: 10000 });

    // Cleanup: Delete the alert rule - wait for page load first
    await page.getByRole('link', { name: '告警规则' }).click();
    await expect(page.getByRole('heading', { name: '告警规则' })).toBeVisible();
    await page.waitForTimeout(1000);
    await deleteRow(page, ruleName);
  });
});
