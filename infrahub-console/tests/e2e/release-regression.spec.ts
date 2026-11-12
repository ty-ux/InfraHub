import { expect, test, type Locator, type Page } from '@playwright/test';
import { loginAsAdmin } from './helpers/auth';
import { mockResourceApis, mockPipelineApis, mockReleaseAndRollbackApis } from './helpers/mocks';
import {
  tableRow,
  chooseFirstSelectOption,
  chooseSelectOption,
  chooseSelectOptionByIndex,
  activeDialog,
  fillNumberField,
  fillTextArea,
  openCreateDialog,
  openPage,
  fillTextField,
  saveDialog,
} from './helpers/ui';

test.describe('release regression', () => {
  test.setTimeout(90000);

  test.beforeEach(async ({ page }) => {
    await loginAsAdmin(page);
    await mockResourceApis(page);
    const pipelineState = await mockPipelineApis(page);
    await mockReleaseAndRollbackApis(page, [
      { id: 90, orderNo: 'INFRAHUB-PRECHECK-202604141338', appId: 2, serviceId: 102, envId: 20, versionNo: 'infrahub-real-deploy-20260413-1', status: 'SUCCESS', riskLevel: 'LOW' },
      { id: 100, orderNo: 'REL-SEED-001', appId: 1, serviceId: 101, envId: 20, versionNo: 'v1.0.0', status: 'CREATED', riskLevel: 'LOW' },
    ], [
      { id: 200, rollbackNo: 'INFRAHUB-RB-20260414103340', releaseOrderId: 100, appId: 2, serviceId: 102, envId: 20, fromVersionId: 1004, toVersionId: 1001, fromVersionNo: 'infrahub-real-deploy-20260413-1', toVersionNo: 'v1.0.0', status: 'SUCCESS' },
    ], pipelineState);
  });

  test('version and current version pages show business-friendly columns', async ({ page }) => {
    await openPage(page, '版本管理');
    await expect(page.getByRole('columnheader', { name: '应用名称', exact: true })).toBeVisible();
    await expect(page.getByRole('columnheader', { name: '制品名称', exact: true })).toBeVisible();
    await expect(page.getByRole('columnheader', { name: '构建记录', exact: true })).toBeVisible();
    await expect(page.getByRole('columnheader', { name: '应用 ID', exact: true })).toHaveCount(0);
    await expect(page.getByRole('columnheader', { name: '制品 ID', exact: true })).toHaveCount(0);

    await openPage(page, '当前版本');
    await expect(page.getByRole('columnheader', { name: '当前版本ID', exact: true })).toBeVisible();
    await expect(page.getByRole('columnheader', { name: '应用编码', exact: true })).toBeVisible();
    await expect(page.getByRole('columnheader', { name: '服务编码', exact: true })).toBeVisible();
    await expect(page.getByRole('columnheader', { name: '运行实例', exact: true })).toBeVisible();
    await expect(page.getByRole('columnheader', { name: '环境名称', exact: true })).toBeVisible();
    await expect(page.getByRole('columnheader', { name: '服务名称', exact: true })).toBeVisible();
    await expect(page.getByRole('columnheader', { name: '发布单号', exact: true })).toBeVisible();
    await expect(page.getByRole('columnheader', { name: '回滚单号', exact: true })).toBeVisible();
    await expect(page.getByRole('columnheader', { name: '环境 ID', exact: true })).toHaveCount(0);
    await expect(page.getByRole('columnheader', { name: '服务 ID', exact: true })).toHaveCount(0);
  });

  test('release diagnostics page shows sequence health and test data preview', async ({ page }) => {
    await openPage(page, '发布诊断');

    // Verify page title
    await expect(page.getByRole('heading', { name: '发布诊断' })).toBeVisible();

    // Verify sequence health section
    await expect(page.getByText('序列健康状态')).toBeVisible();
    await expect(page.getByRole('columnheader', { name: '表名' })).toBeVisible();
    await expect(page.getByRole('columnheader', { name: '序列名' })).toBeVisible();
    await expect(page.getByRole('columnheader', { name: '最大ID' })).toBeVisible();
    await expect(page.getByRole('columnheader', { name: '序列值' })).toBeVisible();
    await expect(page.getByRole('columnheader', { name: '健康状态' })).toBeVisible();
    await expect(page.getByRole('columnheader', { name: '建议修复SQL' })).toBeVisible();

    // Verify at least one sequence row is shown (should include release_artifact, release_version, etc.)
    await expect(page.getByText('release_artifact').first()).toBeVisible();

    // Verify test data preview section
    await expect(page.getByText('测试数据治理预览')).toBeVisible();
    await expect(page.getByText('只读预览')).toBeVisible();

    // Expand first group and verify content
    const firstGroup = page.locator('.el-collapse-item__header').first();
    await firstGroup.click();

    // Verify all known governance groups are visible.
    await expect(page.getByText('V1.4/V1.5 真实流水线部署证据链')).toBeVisible();
    await expect(page.getByText('V1.6 真实上传/发布证据链')).toBeVisible();
    await expect(page.getByText('E2E临时数据')).toBeVisible();

    // Verify protected evidence tags
    await expect(page.getByText('建议保留').first()).toBeVisible();

    // Verify no delete buttons exist (read-only page)
    await expect(page.getByRole('button', { name: '删除', exact: true })).toHaveCount(0);
    await expect(page.getByRole('button', { name: '清理', exact: true })).toHaveCount(0);
  });

  test('artifact upload and version creation entry points are visible', async ({ page }) => {
    await openPage(page, '制品管理');
    await expect(page.getByRole('button', { name: '上传制品', exact: true })).toBeVisible();
    await page.getByRole('button', { name: '上传制品', exact: true }).click();
    let dialog = activeDialog(page);
    await expect(dialog).toContainText('运行实例');
    await expect(dialog).toContainText('jar 文件');
    await expect(dialog).toContainText('仅支持 .jar，最大 200MB');
    await expect(dialog).toContainText('目标机同名文件会拒绝覆盖');
    await dialog.getByRole('button', { name: '取消', exact: true }).click();

    await openPage(page, '版本管理');
    await expect(page.getByRole('button', { name: '新建版本', exact: true })).toBeVisible();
    await page.getByRole('button', { name: '新建版本', exact: true }).click();
    dialog = activeDialog(page);
    await expect(dialog).toContainText('版本号');
    await expect(dialog).toContainText('制品');
    await dialog.getByRole('button', { name: '取消', exact: true }).click();

    await page.goto('/release/orders?create=1&appId=1&versionId=1');
    dialog = activeDialog(page);
    await expect(dialog).toContainText('发布单号');
    await expect(dialog).toContainText('版本');
    await dialog.getByRole('button', { name: '取消', exact: true }).click();
  });

  test('release and rollback forms load business-friendly dependent options', async ({ page }) => {
    const suffix = Date.now().toString().slice(-8);
    const releaseOrderNo = `REL-${suffix}`;

    const releaseDialog = await openCreateDialog(page, '发布单');
    await fillTextField(releaseDialog, '发布单号', releaseOrderNo);
    await chooseSelectOption(releaseDialog, page, '应用名称', '订单协同平台-后端');
    await chooseSelectOption(releaseDialog, page, '服务名称', '订单协同平台后端服务');
    await chooseSelectOption(releaseDialog, page, '环境名称', '测试环境');
    const versionLabel = await chooseSelectOption(releaseDialog, page, '版本', 'v1.0.0');
    await expect(versionLabel).not.toBe('');
    await activeDialog(page).getByRole('button', { name: '取消', exact: true }).click();

    // Open rollback create dialog — register response waits before clicking create
    await openPage(page, '回滚单');
    const optionsLoaded = Promise.all([
      page.waitForResponse((r) => r.url().includes('/api/releases') && r.request().method() === 'GET' && new URL(r.url()).pathname === '/api/releases'),
      page.waitForResponse((r) => r.url().includes('/api/versions') && r.request().method() === 'GET'),
    ]);
    await page.getByRole('button', { name: '新建', exact: true }).click();
    await expect(activeDialog(page)).toBeVisible();
    const rollbackDialog = activeDialog(page);
    await optionsLoaded;
    await fillTextField(rollbackDialog, '回滚单号', `RB-${suffix}`);
    const releaseOrderLabel = await chooseFirstSelectOption(rollbackDialog, page, '发布单号');
    const fromVersion = await chooseSelectOption(rollbackDialog, page, '原版本', 'v1.0.1');
    const toVersion = await chooseSelectOption(rollbackDialog, page, '目标版本', 'v1.0.0');
    await expect(releaseOrderLabel).not.toBe('');
    await expect(fromVersion).not.toBe('');
    await expect(toVersion).not.toBe('');
    await activeDialog(page).getByRole('button', { name: '取消', exact: true }).click();
  });

  test('release and rollback rows expose execution actions and deploy record entry', async ({ page }) => {
    const suffix = Date.now().toString().slice(-8);
    const releaseOrderNo = `REL-ACT-${suffix}`;
    const rollbackNo = `RB-ACT-${suffix}`;

    const releaseDialog = await openCreateDialog(page, '发布单');
    await fillTextField(releaseDialog, '发布单号', releaseOrderNo);
    await chooseSelectOption(releaseDialog, page, '应用名称', '订单协同平台-后端');
    await chooseSelectOption(releaseDialog, page, '服务名称', '订单协同平台后端服务');
    await chooseSelectOption(releaseDialog, page, '环境名称', '测试环境');
    await chooseSelectOption(releaseDialog, page, '版本', 'v1.0.0');
    await chooseSelectOption(releaseDialog, page, '风险等级', 'LOW');
    await saveDialog(page);

    const releaseRow = await findRowAcrossPages(page, releaseOrderNo);
    await expect(releaseRow).toBeVisible();
    await expect(releaseRow.getByRole('button', { name: '执行发布', exact: true })).toBeVisible();
    await expect(releaseRow.getByRole('button', { name: '取消发布', exact: true })).toBeVisible();
    await releaseRow.getByRole('button', { name: '部署记录', exact: true }).click();
    await expect(activeDialog(page)).toContainText('部署记录');
    await activeDialog(page).locator('.el-dialog__headerbtn').click();

    const rollbackDialog = await openCreateDialog(page, '回滚单');
    await fillTextField(rollbackDialog, '回滚单号', rollbackNo);
    await chooseSelectOption(rollbackDialog, page, '发布单号', releaseOrderNo);
    await chooseSelectOption(rollbackDialog, page, '原版本', 'v1.0.1');
    await chooseSelectOption(rollbackDialog, page, '目标版本', 'v1.0.0');
    await saveDialog(page);
    await findRecordByField(page, '/api/rollbacks', 'rollbackNo', rollbackNo);

    const rollbackRow = await findRowAcrossPages(page, rollbackNo);
    await expect(rollbackRow).toBeVisible();
    await expect(rollbackRow.getByRole('button', { name: '执行回滚', exact: true })).toBeVisible();
    await rollbackRow.getByRole('button', { name: '部署记录', exact: true }).click();
    await expect(activeDialog(page)).toContainText('部署记录');
  });

  test('rollback list shows business-friendly columns without raw IDs', async ({ page }) => {
    await openPage(page, '回滚单');
    // Verify business columns are visible (not raw IDs)
    await expect(page.getByRole('columnheader', { name: '发布单号', exact: true })).toBeVisible();
    await expect(page.getByRole('columnheader', { name: '应用名称', exact: true })).toBeVisible();
    await expect(page.getByRole('columnheader', { name: '服务名称', exact: true })).toBeVisible();
    await expect(page.getByRole('columnheader', { name: '环境名称', exact: true })).toBeVisible();
    await expect(page.getByRole('columnheader', { name: '原版本', exact: true })).toBeVisible();
    await expect(page.getByRole('columnheader', { name: '目标版本', exact: true })).toBeVisible();
    // V1.13: 失败提示列
    await expect(page.getByRole('columnheader', { name: '失败提示', exact: true })).toBeVisible();
    // Raw ID columns should not be there
    await expect(page.getByRole('columnheader', { name: 'releaseOrderId', exact: true })).toHaveCount(0);
    await expect(page.getByRole('columnheader', { name: 'fromVersionId', exact: true })).toHaveCount(0);
    await expect(page.getByRole('columnheader', { name: 'toVersionId', exact: true })).toHaveCount(0);

    // Verify existing rollback record has readable content
    const rollbackRow = await findRowAcrossPages(page, 'INFRAHUB-RB-20260414103340');
    await expect(rollbackRow).toBeVisible();
    // Should show version numbers (infrahub-real-deploy format), not numeric version IDs
    await expect(rollbackRow).toContainText('infrahub-real-deploy');
    // Seed rollback records may not carry app/env display names; keep this check focused on readable versions/actions.
    // Should have deploy records button (always visible regardless of status)
    await expect(rollbackRow.getByRole('button', { name: '部署记录', exact: true })).toBeVisible();
  });

  test('rollback list shows failure hint for failed orders', async ({ page }) => {
    await openPage(page, '回滚单');
    // Verify failure hint column header has tooltip
    const failureHintHeader = page.getByRole('columnheader', { name: '失败提示', exact: true });
    await expect(failureHintHeader).toBeVisible();

    // SUCCESS status rollback should NOT show failure hint content (no 【 prefix)
    const successRow = await findRowAcrossPages(page, 'INFRAHUB-RB-20260414103340');
    await expect(successRow).toBeVisible();
    // Success row should not contain failure hint text pattern (【xxx】xxx)
    await expect(successRow).not.toContainText('【');
    // Verify status cell shows translated status label (UI translates SUCCESS → 成功)
    await expect(successRow.locator('td').filter({ hasText: '成功' }).first()).toBeVisible();

    // Check a failed rollback if one exists in test data
    // Look for FAILED status rows
    const failedRow = page.locator('tbody tr').filter({ hasText: 'FAILED' }).first();
    if (await failedRow.isVisible()) {
      // Failed rollback should show failure hint with 【 prefix
      await expect(failedRow).toContainText('【');
      // Should have "优先查看部署记录" tag
      await expect(failedRow.getByText('优先查看部署记录')).toBeVisible();
    }
  });

  test('deploy records dialog shows categorized failure hint', async ({ page }) => {
    await openPage(page, '回滚单');
    const rollbackRow = await findRowAcrossPages(page, 'INFRAHUB-RB-20260414103340');
    await expect(rollbackRow).toBeVisible();
    await rollbackRow.getByRole('button', { name: '部署记录', exact: true }).click();
    const dialog = activeDialog(page);
    await expect(dialog).toContainText('部署记录');
    // Click on a SUCCESS record - should NOT show failure hint
    const successRow = dialog.locator('tbody tr').filter({ hasText: 'SUCCESS' }).first();
    if (await successRow.isVisible()) {
      await successRow.click();
      // No error alert for success records
      await expect(dialog.locator('.el-alert--error')).toHaveCount(0);
    }
    // If there's a FAILED record, click it and verify failure categorization
    const failedRow = dialog.locator('tbody tr').filter({ hasText: 'FAILED' }).first();
    if (await failedRow.isVisible()) {
      await failedRow.click();
      // Should show categorized failure hint
      const errorAlert = dialog.locator('.el-alert--error');
      await expect(errorAlert).toBeVisible();
      const alertText = await errorAlert.textContent();
      // Should have category label like 【健康检查失败】
      expect(alertText).toMatch(/【(前置检查失败|制品缺失|端口占用|SSH连接失败|启动失败|健康检查失败|未识别失败)】/);
    }
    await dialog.locator('.el-dialog__headerbtn').click();
  });

  test('release detail drawer shows context and opens deploy records', async ({ page }) => {
    await openPage(page, '发布单');
    const releaseRow = await findRowAcrossPages(page, 'INFRAHUB-PRECHECK-202604141338');
    await expect(releaseRow).toBeVisible();
    await releaseRow.getByRole('button', { name: '详情', exact: true }).click();
    const drawer = page.locator('.el-drawer');
    await expect(drawer).toBeVisible();
    // Basic info section
    await expect(drawer).toContainText('基本信息');
    await expect(drawer).toContainText('单号');
    await expect(drawer).toContainText('状态');
    // Version info section
    await expect(drawer).toContainText('版本信息');
    // Execution summary section
    await expect(drawer).toContainText('执行摘要');
    // Related records entry
    await expect(drawer).toContainText('关联记录入口');
    await expect(drawer.getByRole('button', { name: '前置检查', exact: true })).toBeVisible();
    await expect(drawer.getByRole('button', { name: '部署记录', exact: true })).toBeVisible();
    // Current version snapshot
    await expect(drawer).toContainText('当前版本快照');
    // Open deploy records from detail drawer
    await drawer.getByRole('button', { name: '部署记录', exact: true }).click();
    const dialog = activeDialog(page);
    await expect(dialog).toContainText('部署记录');
    await dialog.locator('.el-dialog__headerbtn').click();
    // Close drawer
    await drawer.getByRole('button', { name: '关闭', exact: true }).click();
  });

  test('rollback detail drawer shows version info and failure hint', async ({ page }) => {
    await openPage(page, '回滚单');
    const rollbackRow = await findRowAcrossPages(page, 'INFRAHUB-RB-20260414103340');
    await expect(rollbackRow).toBeVisible();
    await rollbackRow.getByRole('button', { name: '详情', exact: true }).click();
    const drawer = page.locator('.el-drawer');
    await expect(drawer).toBeVisible();
    // Basic info
    await expect(drawer).toContainText('基本信息');
    await expect(drawer).toContainText('单号');
    // Version info: rollback shows fromVersion -> toVersion
    await expect(drawer).toContainText('原版本');
    await expect(drawer).toContainText('目标版本');
    await expect(drawer).toContainText('发布单号');
    // Execution summary
    await expect(drawer).toContainText('执行摘要');
    // Related records entry
    await expect(drawer).toContainText('关联记录入口');
    // Current version snapshot
    await expect(drawer).toContainText('当前版本快照');
    await drawer.getByRole('button', { name: '关闭', exact: true }).click();
  });

  test('release detail drawer shows execution timeline with action links', async ({ page }) => {
    await openPage(page, '发布单');
    const releaseRow = await findRowAcrossPages(page, 'INFRAHUB-PRECHECK-202604141338');
    await expect(releaseRow).toBeVisible();
    await releaseRow.getByRole('button', { name: '详情', exact: true }).click();
    const drawer = page.locator('.el-drawer');
    await expect(drawer).toBeVisible();

    // Timeline section appears
    await expect(drawer).toContainText('执行时间线');

    // Timeline shows key nodes
    await expect(drawer).toContainText('单据创建');
    await expect(drawer).toContainText('最终结果');

    // Timeline node with deploy records action
    const deployRecordLink = drawer.getByRole('button', { name: '查看部署记录' }).first();
    if (await deployRecordLink.isVisible()) {
      await deployRecordLink.click();
      const dialog = activeDialog(page);
      await expect(dialog).toContainText('部署记录');
      await dialog.locator('.el-dialog__headerbtn').click();
    }

    // Close drawer
    await drawer.getByRole('button', { name: '关闭', exact: true }).click();
  });

  test('rollback detail drawer shows execution timeline', async ({ page }) => {
    await openPage(page, '回滚单');
    const rollbackRow = await findRowAcrossPages(page, 'INFRAHUB-RB-20260414103340');
    await expect(rollbackRow).toBeVisible();
    await rollbackRow.getByRole('button', { name: '详情', exact: true }).click();
    const drawer = page.locator('.el-drawer');
    await expect(drawer).toBeVisible();

    // Timeline section appears
    await expect(drawer).toContainText('执行时间线');

    // Timeline shows key nodes
    await expect(drawer).toContainText('单据创建');
    await expect(drawer).toContainText('最终结果');

    // Close drawer
    await drawer.getByRole('button', { name: '关闭', exact: true }).click();
  });

  test('release detail drawer shows execution history with clickable records', async ({ page }) => {
    await openPage(page, '发布单');
    const releaseRow = await findRowAcrossPages(page, 'INFRAHUB-PRECHECK-202604141338');
    await expect(releaseRow).toBeVisible();
    await releaseRow.getByRole('button', { name: '详情', exact: true }).click();
    const drawer = page.locator('.el-drawer');
    await expect(drawer).toBeVisible();

    // Execution history section appears
    await expect(drawer).toContainText('执行历史');

    // History items are clickable and open deploy records pre-selected to the matching record
    const historyItem = drawer.locator('.history-item').first();
    if (await historyItem.isVisible()) {
      const historyStatus = await historyItem.locator('.el-tag').textContent();
      await historyItem.click();
      const dialog = activeDialog(page);
      await expect(dialog).toContainText('部署记录');
      // Verify the dialog selected the matching record — the record summary should show the same status
      const selectedStatus = dialog.locator('.record-summary .el-tag').first();
      await expect(selectedStatus).toHaveText(historyStatus || '');
      await dialog.locator('.el-dialog__headerbtn').click();
    }

    // Close drawer
    await drawer.getByRole('button', { name: '关闭', exact: true }).click();
  });

  test('rollback detail drawer shows execution history', async ({ page }) => {
    await openPage(page, '回滚单');
    const rollbackRow = await findRowAcrossPages(page, 'INFRAHUB-RB-20260414103340');
    await expect(rollbackRow).toBeVisible();
    await rollbackRow.getByRole('button', { name: '详情', exact: true }).click();
    const drawer = page.locator('.el-drawer');
    await expect(drawer).toBeVisible();

    // Execution history section appears
    await expect(drawer).toContainText('执行历史');

    // Close drawer
    await drawer.getByRole('button', { name: '关闭', exact: true }).click();
  });

  test('runtime diagnostics expose deploy log, service status and app log', async ({ page }) => {
    await openPage(page, '发布单');
    const releaseRow = await findRowAcrossPages(page, 'INFRAHUB-PRECHECK-202604141338');
    await expect(releaseRow).toBeVisible();
    await releaseRow.getByRole('button', { name: '部署记录', exact: true }).click();
    let dialog = activeDialog(page);
    await dialog.locator('tbody tr').first().click();
    await expect(dialog).toContainText('执行日志');
    await expect(dialog).toContainText('前置检查通过');
    await expect(dialog).toContainText('Health check passed');
    await dialog.locator('.el-dialog__headerbtn').click();

    await openPage(page, '回滚单');
    const rollbackRow = await findRowAcrossPages(page, 'INFRAHUB-RB-20260414103340');
    await expect(rollbackRow).toBeVisible();
    await rollbackRow.getByRole('button', { name: '部署记录', exact: true }).click();
    dialog = activeDialog(page);
    // Expand the first batch group and click a row to see the log
    const rbBatchHeader = dialog.locator('.el-collapse-item__header').first();
    if (await rbBatchHeader.isVisible()) {
      await rbBatchHeader.click();
    }
    await dialog.locator('tbody tr').first().click();
    await expect(dialog).toContainText('Health check passed');
    await dialog.locator('.el-dialog__headerbtn').click();

    await openPage(page, '当前版本');
    const currentVersionRow = await findRowAcrossPages(page, 'InfraHub 后端服务');
    await expect(currentVersionRow).toBeVisible();
    await expect(currentVersionRow).toContainText('infrahub');
    await currentVersionRow.getByRole('button', { name: '运行状态', exact: true }).click();
    dialog = activeDialog(page);
    await expect(dialog).toContainText('当前版本ID');
    await expect(dialog).toContainText('运行实例ID');
    // 诊断目标必须是一个明确的实例：名称、实例 ID、主机 ID 都要落在同一个实例上
    await expect(dialog).toContainText('infrahub-web-1');
    await expect(dialog).toContainText('9001');
    await expect(dialog).toContainText('主机ID');
    await expect(dialog).toContainText('30');
    await expect(dialog).toContainText('实例版本ID');
    // 端口信息：断言端口号和监听状态，避免文案微调打断测试
    await expect(dialog).toContainText('8080');
    await expect(dialog).toContainText('监听');
    // 进程状态
    await expect(dialog).toContainText('运行中');
    // 健康检查
    await expect(dialog).toContainText('健康检查');
    await expect(dialog).toContainText('UP', { timeout: 15000 });
    await dialog.locator('.el-dialog__headerbtn').click();

    await currentVersionRow.getByRole('button', { name: '运行日志', exact: true }).click();
    dialog = activeDialog(page);
    await expect(dialog).toContainText('/opt/infrahub/infrahub-web/logs/app.log');
    await expect(dialog.getByRole('button', { name: '刷新', exact: true })).toBeVisible();
    await expect(dialog).toContainText('Started InfrahubApplication', { timeout: 15000 });
    await dialog.locator('.el-dialog__headerbtn').click();

    await clickRowMoreItem(page, currentVersionRow, '部署配置');
    dialog = activeDialog(page);
    await expect(dialog).toContainText('/opt/infrahub/infrahub-web');
    await expect(dialog).toContainText('8080');
    await expect(dialog).toContainText('actuator/health');
    await dialog.locator('.el-dialog__headerbtn').click();

    const srmRow = await findRowAcrossPages(page, '订单协同平台后端服务');
    await expect(srmRow).toBeVisible();
    // 该服务环境没有运行实例：列表列表达"无实例"，诊断类弹窗给出完整说明，
    // 都不塌成"暂无数据"、也不按数组顺序随便挑一个实例
    await expect(srmRow).toContainText('无实例');
    await clickRowMoreItem(page, srmRow, '部署配置');
    dialog = activeDialog(page);
    await expect(dialog).toContainText('该服务环境下没有运行实例');
    await dialog.locator('.el-dialog__headerbtn').click();
    await srmRow.getByRole('button', { name: '运行状态', exact: true }).click();
    dialog = activeDialog(page);
    await expect(dialog).toContainText('该服务环境下没有运行实例');
  });

  test('current version latest deploy records opens dialog with deploy log', async ({ page }) => {
    await openPage(page, '当前版本');
    const currentVersionRow = await findRowAcrossPages(page, 'InfraHub 后端服务');
    await expect(currentVersionRow).toBeVisible();
    await clickRowMoreItem(page, currentVersionRow, '最近部署记录');
    const dialog = activeDialog(page);
    await expect(dialog).toContainText('部署记录');
    // Expand the first batch group and click first record to select it
    const batchHeader = dialog.locator('.el-collapse-item__header').first();
    if (await batchHeader.isVisible()) {
      await batchHeader.click();
    }
    await dialog.locator('tbody tr').first().click();
    // Verify deploy log panel appears after selection
    await expect(dialog).toContainText('执行日志');
    await expect(dialog).toContainText('Health check passed');
    // Verify record summary fields
    await expect(dialog).toContainText('状态:');
    await dialog.locator('.el-dialog__headerbtn').click();
  });

  test('manual release precheck reports success and keeps order editable', async ({ page }) => {
    const suffix = Date.now().toString().slice(-8);
    const releaseOrderNo = `INFRAHUB-PRECHECK-UI-${suffix}`;

    const releaseDialog = await openCreateDialog(page, '发布单');
    await fillTextField(releaseDialog, '发布单号', releaseOrderNo);
    await chooseSelectOption(releaseDialog, page, '应用名称', 'InfraHub');
    await chooseSelectOption(releaseDialog, page, '服务名称', 'InfraHub 后端服务');
    await chooseSelectOption(releaseDialog, page, '环境名称', '测试环境');
    await chooseSelectOption(releaseDialog, page, '版本', 'infrahub-real-deploy-20260413-1');
    await chooseSelectOption(releaseDialog, page, '风险等级', 'LOW');
    await saveDialog(page);

    const releaseRow = await findRowAcrossPages(page, releaseOrderNo);
    await expect(releaseRow).toBeVisible();
    await releaseRow.getByRole('button', { name: '前置检查', exact: true }).click();
    const dialog = activeDialog(page);
    await expect(dialog).toContainText('前置检查通过', { timeout: 20000 });
    await expect(dialog).toContainText('通过');
    await dialog.locator('.el-dialog__headerbtn').click();
    await expect(releaseRow).toContainText('已创建');
    await expect(releaseRow.getByRole('button', { name: '执行发布', exact: true })).toBeVisible();
  });

  test('release order can trigger pipeline release and open linked run logs', async ({ page }) => {
    const suffix = Date.now().toString().slice(-8);
    const pipelineCode = `rel-pipe-${suffix}`;
    const pipelineName = `发布流水线 E2E ${suffix}`;
    const stageName = `发布阶段 E2E ${suffix}`;
    const stepName = `发布步骤 E2E ${suffix}`;
    const releaseOrderNo = `REL-PIPE-${suffix}`;
    const logMarker = `release-pipeline-success-${suffix}`;

    const pipelineDialog = await openCreateDialog(page, '流水线定义');
    await fillTextField(pipelineDialog, '流水线编码', pipelineCode);
    await fillTextField(pipelineDialog, '流水线名称', pipelineName);
    await chooseSelectOption(pipelineDialog, page, '应用名称', '订单协同平台-后端');
    await chooseSelectOption(pipelineDialog, page, '触发方式', 'MANUAL');
    await chooseSelectOption(pipelineDialog, page, '流水线类型', 'DEPLOY');
    await saveDialog(page);

    await openPipelineConfigByCode(page, pipelineCode, pipelineName);

    await page.getByRole('button', { name: '新建阶段', exact: true }).click();
    let dialog = activeDialog(page);
    await fillTextField(dialog, '阶段名称', stageName);
    await fillNumberField(dialog, '阶段顺序', '1');
    await fillNumberField(dialog, '超时(秒)', '30');
    await saveDialog(page);

    await page.getByRole('button', { name: '新建步骤', exact: true }).click();
    dialog = activeDialog(page);
    await fillTextField(dialog, '步骤名称', stepName);
    await fillTextField(dialog, '步骤类型', 'SCRIPT');
    await chooseSelectOption(dialog, page, '执行器类型', 'LOCAL_SHELL');
    await fillNumberField(dialog, '步骤顺序', '1');
    await fillTextArea(dialog, '脚本内容', `echo ${logMarker}`);
    await fillNumberField(dialog, '超时(秒)', '30');
    await fillNumberField(dialog, '重试次数', '0');
    await saveDialog(page);
    await expect(tableRow(page, stepName)).toBeVisible();

    await page.getByRole('button', { name: '运行流水线', exact: true }).click();
    dialog = activeDialog(page);
    await chooseSelectOption(dialog, page, '环境名称', '测试环境');
    await dialog.getByRole('button', { name: '运行流水线', exact: true }).click();
    await expect(page.getByRole('heading', { name: '步骤执行记录' })).toBeVisible();
    await expect(async () => {
      await page.getByRole('button', { name: '刷新', exact: true }).click();
      await expect(tableRow(page, stepName)).toContainText('SUCCESS', { timeout: 1000 });
      await expect(tableRow(page, stepName)).toContainText(logMarker, { timeout: 1000 });
    }).toPass({ timeout: 30000 });

    const releaseDialog = await openCreateDialog(page, '发布单');
    await fillTextField(releaseDialog, '发布单号', releaseOrderNo);
    await chooseSelectOption(releaseDialog, page, '应用名称', '订单协同平台-后端');
    await chooseSelectOption(releaseDialog, page, '服务名称', '订单协同平台后端服务');
    await chooseSelectOption(releaseDialog, page, '环境名称', '测试环境');
    await chooseSelectOption(releaseDialog, page, '版本', 'v1.0.0');
    await chooseSelectOption(releaseDialog, page, '风险等级', 'LOW');
    await saveDialog(page);

    const releaseRow = await findRowAcrossPages(page, releaseOrderNo);
    await expect(releaseRow).toBeVisible();
    // 第二批：流水线发布为次要动作，收进行内「更多」菜单（处理函数与确认步骤不变）
    await releaseRow.getByRole('button', { name: '更多', exact: true }).click();
    await page.getByRole('menuitem', { name: '流水线发布', exact: true }).click();
    dialog = activeDialog(page);
    await chooseSelectOption(dialog, page, '流水线', pipelineName);
    await dialog.getByRole('button', { name: '流水线发布', exact: true }).click();
    await ensurePipelineStepsOpenedByReleaseOrderNo(page, releaseOrderNo);
    const releaseRunUrl = page.url();

    await expect(async () => {
      await page.getByRole('button', { name: '刷新', exact: true }).click();
      await expect(tableRow(page, stepName)).toContainText('SUCCESS', { timeout: 1000 });
      await expect(tableRow(page, stepName)).toContainText(logMarker, { timeout: 1000 });
    }).toPass({ timeout: 30000 });

    await openPage(page, '发布单');
    await expect(async () => {
      await page.getByRole('button', { name: '刷新', exact: true }).click();
      const releaseRowAfterRun = await findRowAcrossPages(page, releaseOrderNo);
      await expect(releaseRowAfterRun).toContainText('成功', { timeout: 1000 });
    }).toPass({ timeout: 30000 });

    const releaseRowAfterRun = await findRowAcrossPages(page, releaseOrderNo);
    await releaseRowAfterRun.getByRole('button', { name: '流水线日志', exact: true }).click();
    await expect(page).toHaveURL(releaseRunUrl);
    await expect(tableRow(page, stepName)).toContainText(logMarker);
  });

  test('release pipeline dialog marks real deploy pipelines and step page opens deploy records', async ({ page }) => {
    const suffix = Date.now().toString().slice(-8);
    const pipelineCode = `real-deploy-ui-${suffix}`;
    const pipelineName = `真实部署标识 E2E ${suffix}`;
    const stageName = `真实部署标识阶段 ${suffix}`;
    const stepName = `真实部署标识步骤 ${suffix}`;
    const releaseOrderNo = `REL-REAL-UI-${suffix}`;

    await createReleaseDeployPipeline(page, {
      pipelineCode,
      pipelineName,
      stageName,
      stepName,
    });

    await openPage(page, '发布单');
    await createReleaseOrder(page, releaseOrderNo);
    const releaseRow = await findRowAcrossPages(page, releaseOrderNo);
    // 第二批：流水线发布为次要动作，收进行内「更多」菜单（处理函数与确认步骤不变）
    await releaseRow.getByRole('button', { name: '更多', exact: true }).click();
    await page.getByRole('menuitem', { name: '流水线发布', exact: true }).click();
    const dialog = activeDialog(page);
    await dialog.getByRole('combobox', { name: /流水线/ }).click({ force: true });
    await dialog.getByRole('combobox', { name: /流水线/ }).fill(pipelineName);
    const realDeployOption = page.locator('[role="option"]:visible').filter({ hasText: pipelineName }).first();
    await expect(realDeployOption).toContainText('真实部署');
    await realDeployOption.click({ force: true });
    await expect(dialog).toContainText('该流水线会执行目标机部署');
    await dialog.getByRole('button', { name: '取消', exact: true }).click();

    await page.goto('/pipeline/runs/99/steps');
    await expect(page.getByText('关联发布单 ID：89')).toBeVisible();
    await page.getByRole('button', { name: '部署记录', exact: true }).click();
    const deployDialog = activeDialog(page);
    await deployDialog.locator('tbody tr').first().click();
    await expect(deployDialog).toContainText('Health check passed');
  });

  test('release order pipeline release surfaces failed step and release status', async ({ page }) => {
    const suffix = Date.now().toString().slice(-8);
    const pipelineCode = `rel-fail-pipe-${suffix}`;
    const pipelineName = `发布失败流水线 E2E ${suffix}`;
    const stageName = `失败阶段 E2E ${suffix}`;
    const stepName = `失败步骤 E2E ${suffix}`;
    const releaseOrderNo = `REL-PIPE-FAIL-${suffix}`;

    const pipelineDialog = await openCreateDialog(page, '流水线定义');
    await fillTextField(pipelineDialog, '流水线编码', pipelineCode);
    await fillTextField(pipelineDialog, '流水线名称', pipelineName);
    await chooseSelectOption(pipelineDialog, page, '应用名称', '订单协同平台-后端');
    await chooseSelectOption(pipelineDialog, page, '触发方式', 'MANUAL');
    await chooseSelectOption(pipelineDialog, page, '流水线类型', 'DEPLOY');
    await saveDialog(page);

    await openPipelineConfigByCode(page, pipelineCode, pipelineName);

    await page.getByRole('button', { name: '新建阶段', exact: true }).click();
    let dialog = activeDialog(page);
    await fillTextField(dialog, '阶段名称', stageName);
    await fillNumberField(dialog, '阶段顺序', '1');
    await fillNumberField(dialog, '超时(秒)', '30');
    await saveDialog(page);

    await page.getByRole('button', { name: '新建步骤', exact: true }).click();
    dialog = activeDialog(page);
    await fillTextField(dialog, '步骤名称', stepName);
    await fillTextField(dialog, '步骤类型', 'SCRIPT');
    await chooseSelectOption(dialog, page, '执行器类型', 'LOCAL_SHELL');
    await fillNumberField(dialog, '步骤顺序', '1');
    await fillTextArea(dialog, '脚本内容', 'ls /nonexistent/path/that/does/not/exist');
    await fillNumberField(dialog, '超时(秒)', '30');
    await fillNumberField(dialog, '重试次数', '0');
    await saveDialog(page);
    await expect(tableRow(page, stepName)).toBeVisible();

    const releaseDialog = await openCreateDialog(page, '发布单');
    await fillTextField(releaseDialog, '发布单号', releaseOrderNo);
    await chooseSelectOption(releaseDialog, page, '应用名称', '订单协同平台-后端');
    await chooseSelectOption(releaseDialog, page, '服务名称', '订单协同平台后端服务');
    await chooseSelectOption(releaseDialog, page, '环境名称', '测试环境');
    await chooseSelectOption(releaseDialog, page, '版本', 'v1.0.0');
    await chooseSelectOption(releaseDialog, page, '风险等级', 'LOW');
    await saveDialog(page);

    const releaseRow = await findRowAcrossPages(page, releaseOrderNo);
    await expect(releaseRow).toBeVisible();
    // 第二批：流水线发布为次要动作，收进行内「更多」菜单（处理函数与确认步骤不变）
    await releaseRow.getByRole('button', { name: '更多', exact: true }).click();
    await page.getByRole('menuitem', { name: '流水线发布', exact: true }).click();
    dialog = activeDialog(page);
    await chooseSelectOption(dialog, page, '流水线', pipelineName);
    await dialog.getByRole('button', { name: '流水线发布', exact: true }).click();
    await ensurePipelineStepsOpenedByReleaseOrderNo(page, releaseOrderNo);
    const releaseRunUrl = page.url();

    await expect(async () => {
      await page.getByRole('button', { name: '刷新', exact: true }).click();
      await expect(tableRow(page, stepName)).toContainText('FAILED', { timeout: 1000 });
    }).toPass({ timeout: 30000 });

    await openPage(page, '发布单');
    await expect(async () => {
      await page.getByRole('button', { name: '刷新', exact: true }).click();
      const failedReleaseRow = await findRowAcrossPages(page, releaseOrderNo);
      await expect(failedReleaseRow).toContainText('失败', { timeout: 1000 });
    }).toPass({ timeout: 30000 });

    const failedReleaseRow = await findRowAcrossPages(page, releaseOrderNo);
    await failedReleaseRow.getByRole('button', { name: '流水线日志', exact: true }).click();
    await expect(page).toHaveURL(releaseRunUrl);
    await expect(tableRow(page, stepName)).toContainText('FAILED');
  });

  test('release pipeline rejects duplicate trigger and hides cancel while running', async ({ page }) => {
    const suffix = Date.now().toString().slice(-8);
    const pipelineCode = `rel-dupe-pipe-${suffix}`;
    const pipelineName = `发布重复触发流水线 E2E ${suffix}`;
    const stageName = `重复触发阶段 E2E ${suffix}`;
    const stepName = `重复触发步骤 E2E ${suffix}`;
    const releaseOrderNo = `REL-PIPE-DUPE-${suffix}`;
    const logMarker = `release-pipeline-duplicate-${suffix}`;

    await createPipelineWithSingleStep(page, {
      pipelineCode,
      pipelineName,
      stageName,
      stepName,
      scriptContent: `ping -n 16 127.0.0.1 > nul && echo ${logMarker}`,
    });

    await createReleaseOrder(page, releaseOrderNo);
    const pipeline = await findRecordByField(page, '/api/pipelines', 'pipelineCode', pipelineCode);
    const release = await findRecordByField(page, '/api/releases', 'orderNo', releaseOrderNo);

    const firstResponse = await postApi(page, `/api/releases/${release.id}/execute-pipeline`, {
      pipelineId: pipeline.id,
    });
    expect(firstResponse.code).toBe('SUCCESS');
    expect(firstResponse.data.pipelineRunId).toBeTruthy();

    const secondResponse = await postApi(page, `/api/releases/${release.id}/execute-pipeline`, {
      pipelineId: pipeline.id,
    });
    expect(secondResponse.code).not.toBe('SUCCESS');
    expect(secondResponse.message).toContain('当前发布单状态不允许执行');

    await openPage(page, '发布单');
    await page.getByRole('button', { name: '刷新', exact: true }).click();
    const runningRow = await findRowAcrossPages(page, releaseOrderNo);
    await expect(runningRow).toBeVisible();
    await expect(runningRow).toContainText('成功');
    await expect(runningRow.getByRole('button', { name: '取消发布', exact: true })).toHaveCount(0);

    await page.goto(`/pipeline/runs/${firstResponse.data.pipelineRunId}/steps`);
    await expect(page.getByRole('heading', { name: '步骤执行记录' })).toBeVisible();
    await expect(async () => {
      await page.getByRole('button', { name: '刷新', exact: true }).click();
      await expect(tableRow(page, stepName)).toContainText('SUCCESS', { timeout: 1000 });
      await expect(tableRow(page, stepName)).toContainText(logMarker, { timeout: 1000 });
    }).toPass({ timeout: 30000 });
  });

  test('manual pipeline run stays unlinked from release orders', async ({ page }) => {
    const suffix = Date.now().toString().slice(-8);
    const pipelineCode = `manual-unlinked-${suffix}`;
    const pipelineName = `未关联手动流水线 E2E ${suffix}`;
    const stageName = `未关联阶段 E2E ${suffix}`;
    const stepName = `未关联步骤 E2E ${suffix}`;
    const logMarker = `manual-unlinked-${suffix}`;

    await createPipelineWithSingleStep(page, {
      pipelineCode,
      pipelineName,
      stageName,
      stepName,
      scriptContent: `echo ${logMarker}`,
    });

    await page.getByRole('button', { name: '运行流水线', exact: true }).click();
    const dialog = activeDialog(page);
    await chooseSelectOption(dialog, page, '环境名称', '测试环境');
    await dialog.getByRole('button', { name: '运行流水线', exact: true }).click();
    await expect(page.getByRole('heading', { name: '步骤执行记录' })).toBeVisible();
    const runId = Number(page.url().match(/\/pipeline\/runs\/(\d+)\/steps/)?.[1]);
    expect(runId).toBeGreaterThan(0);

    await expect(async () => {
      await page.getByRole('button', { name: '刷新', exact: true }).click();
      await expect(tableRow(page, stepName)).toContainText('SUCCESS', { timeout: 1000 });
      await expect(tableRow(page, stepName)).toContainText(logMarker, { timeout: 1000 });
    }).toPass({ timeout: 30000 });
    await expect(page.getByRole('button', { name: '部署记录', exact: true })).toHaveCount(0);
  });

  test('release order filter bar shows quick views and filters list by status', async ({ page }) => {
    await openPage(page, '发布单');

    // Verify quick view buttons are visible
    const filterBar = page.locator('.order-filter-bar');
    await expect(filterBar).toBeVisible();
    await expect(filterBar.getByRole('button', { name: '全部', exact: true })).toBeVisible();
    await expect(filterBar.getByRole('button', { name: '待执行', exact: true })).toBeVisible();
    await expect(filterBar.getByRole('button', { name: '执行中', exact: true })).toBeVisible();
    await expect(filterBar.getByRole('button', { name: '失败', exact: true })).toBeVisible();
    await expect(filterBar.getByRole('button', { name: '成功', exact: true })).toBeVisible();

    // Click "成功" quick view
    await filterBar.getByRole('button', { name: '成功', exact: true }).click();

    // Active filter tag should appear
    await expect(filterBar.locator('.active-filters')).toContainText('视图：成功');

    // Click "清空筛选" to reset
    await filterBar.getByRole('button', { name: '清空筛选', exact: true }).click();

    // Filter tags should be gone
    await expect(filterBar.locator('.active-filters .el-tag')).toHaveCount(0);
  });

  test('rollback order filter bar shows keyword and release order keyword search', async ({ page }) => {
    await openPage(page, '回滚单');

    const filterBar = page.locator('.order-filter-bar');
    await expect(filterBar).toBeVisible();

    // Verify keyword input is present
    const keywordInput = filterBar.locator('input').filter({ hasText: '' }).first();
    await expect(keywordInput).toBeVisible();

    // Verify release order keyword input is present (rollback-specific)
    await expect(filterBar.getByText('发布单号')).toBeVisible();

    // Type a keyword and submit
    await keywordInput.fill('INFRAHUB-RB');
    await filterBar.getByRole('button', { name: '查询', exact: true }).click();

    // Active filter tag should show the keyword
    await expect(filterBar.locator('.active-filters')).toContainText('单号：INFRAHUB-RB');

    // Clear the filter
    await filterBar.getByRole('button', { name: '清空筛选', exact: true }).click();
  });

  test('filter bar app and environment selects are present and interactable', async ({ page }) => {
    await openPage(page, '发布单');

    const filterBar = page.locator('.order-filter-bar');

    // Verify app select exists
    const appFormItem = filterBar.locator('.el-form-item').filter({ hasText: '应用' });
    await expect(appFormItem).toBeVisible();

    // Verify environment select exists
    const envFormItem = filterBar.locator('.el-form-item').filter({ hasText: '环境' });
    await expect(envFormItem).toBeVisible();

    // Verify date picker exists
    const dateFormItem = filterBar.locator('.el-form-item').filter({ hasText: '创建时间' });
    await expect(dateFormItem).toBeVisible();

    // Verify query and reset buttons exist
    await expect(filterBar.getByRole('button', { name: '查询', exact: true })).toBeVisible();
    await expect(filterBar.getByRole('button', { name: '重置', exact: true })).toBeVisible();
  });

  test('filtering does not break detail drawer and deploy records', async ({ page }) => {
    await openPage(page, '发布单');

    // Apply "成功" filter
    const filterBar = page.locator('.order-filter-bar');
    await filterBar.getByRole('button', { name: '成功', exact: true }).click();

    // Find a visible row and open detail
    const visibleRow = page.locator('tbody tr').first();
    if (await visibleRow.isVisible()) {
      const detailButton = visibleRow.getByRole('button', { name: '详情', exact: true });
      if (await detailButton.isVisible()) {
        await detailButton.click();
        const drawer = page.locator('.el-drawer');
        await expect(drawer).toBeVisible();
        await expect(drawer).toContainText('基本信息');
        await drawer.getByRole('button', { name: '关闭', exact: true }).click();
      }
    }
  });

  test('hasFailureHint filter narrows down results correctly', async ({ page }) => {
    await openPage(page, '发布单');

    const filterBar = page.locator('.order-filter-bar');

    // Get initial total count
    const getTotalCount = async () => {
      const paginationText = await page.locator('.el-pagination__total').textContent();
      const match = paginationText?.match(/共 (\d+) 条/);
      return match ? parseInt(match[1], 10) : 0;
    };

    const initialCount = await getTotalCount();

    // Apply "有失败提示" filter
    const hasFailureHintSelect = filterBar.locator('.el-form-item').filter({ hasText: '有失败提示' }).locator('.el-select').first();
    await hasFailureHintSelect.click();
    await page.getByRole('option', { name: '有', exact: true }).click();
    await filterBar.getByRole('button', { name: '查询', exact: true }).click();

    // Wait for results to load
    await page.waitForTimeout(500);

    const withFailureHintCount = await getTotalCount();

    // Apply "无失败提示" filter
    await hasFailureHintSelect.click();
    await page.getByRole('option', { name: '无', exact: true }).click();
    await filterBar.getByRole('button', { name: '查询', exact: true }).click();

    // Wait for results to load
    await page.waitForTimeout(500);

    const withoutFailureHintCount = await getTotalCount();

    // Verify: withFailureHintCount + withoutFailureHintCount should equal initialCount
    // (assuming no records are in an indeterminate state)
    expect(withFailureHintCount + withoutFailureHintCount).toBe(initialCount);

    // Verify active filter tag is shown (actual UI shows "失败提示：无" without "有" prefix)
    await expect(filterBar.locator('.active-filters')).toContainText('失败提示：无');

    // Clear filter
    await filterBar.getByRole('button', { name: '清空筛选', exact: true }).click();
    await expect(filterBar.locator('.active-filters .el-tag')).toHaveCount(0);
  });

  test('hasPipelineRun filter narrows down results correctly', async ({ page }) => {
    await openPage(page, '发布单');

    const filterBar = page.locator('.order-filter-bar');

    // Get initial total count
    const getTotalCount = async () => {
      const paginationText = await page.locator('.el-pagination__total').textContent();
      const match = paginationText?.match(/共 (\d+) 条/);
      return match ? parseInt(match[1], 10) : 0;
    };

    const initialCount = await getTotalCount();

    // Apply "有关联流水线" filter
    const hasPipelineRunSelect = filterBar.locator('.el-form-item').filter({ hasText: '有关联流水线' }).locator('.el-select').first();
    await hasPipelineRunSelect.click();
    await page.getByRole('option', { name: '有', exact: true }).click();
    await filterBar.getByRole('button', { name: '查询', exact: true }).click();

    // Wait for results to load
    await page.waitForTimeout(500);

    const withPipelineRunCount = await getTotalCount();

    // Apply "无关联流水线" filter
    await hasPipelineRunSelect.click();
    await page.getByRole('option', { name: '无', exact: true }).click();
    await filterBar.getByRole('button', { name: '查询', exact: true }).click();

    // Wait for results to load
    await page.waitForTimeout(500);

    const withoutPipelineRunCount = await getTotalCount();

    // Verify: withPipelineRunCount + withoutPipelineRunCount should equal initialCount
    expect(withPipelineRunCount + withoutPipelineRunCount).toBe(initialCount);

    // Verify active filter tag is shown (actual UI shows "关联流水线：无" without "有" prefix)
    await expect(filterBar.locator('.active-filters')).toContainText('关联流水线：无');

    // Clear filter
    await filterBar.getByRole('button', { name: '清空筛选', exact: true }).click();
    await expect(filterBar.locator('.active-filters .el-tag')).toHaveCount(0);
  });

  test('pending quick view filters results to CREATED and APPROVED only', async ({ page }) => {
    await openPage(page, '发布单');

    const filterBar = page.locator('.order-filter-bar');

    // Intercept the API request to verify the status parameter
    const apiResponsePromise = page.waitForResponse((response) => {
      const url = response.url();
      return response.request().method() === 'GET' &&
        url.includes('/api/releases') &&
        url.includes('status=');
    });

    await filterBar.getByRole('button', { name: '待执行', exact: true }).click();

    const apiResponse = await apiResponsePromise;
    expect(apiResponse.ok()).toBeTruthy();
    const requestUrl = apiResponse.url();

    // Verify the URL contains the expected status filter (CREATED,APPROVED)
    // This is the key semantic: 待执行 should map to status=CREATED,APPROVED
    // Accept both encoded (%2C) and raw (,) formats
    const hasCorrectStatus = requestUrl.includes('status=CREATED%2CAPPROVED') ||
                             requestUrl.includes('status=CREATED,APPROVED');
    expect(hasCorrectStatus).toBe(true);

    await expect(page.locator('.content-card .el-table .el-loading-mask:visible')).toHaveCount(0);

    // Verify active filter tag shows "视图：待执行"
    await expect(filterBar.locator('.active-filters')).toContainText('视图：待执行');

    // Get all visible rows and verify no FAILED/SUCCESS/RUNNING status appears
    // We look for the status text in the row content, not by column index
    const dataTable = page.locator('.el-table__body');

    // If table has data rows (not just empty state)
    const dataRows = dataTable.locator('tbody tr');
    const rowCount = await dataRows.count();

    if (rowCount > 0 && await dataRows.first().isVisible()) {
      // Check that no row contains "失败" (FAILED), "成功" (SUCCESS), or "执行中" (RUNNING)
      // These should not appear in any row when 待执行 filter is active
      const tableText = await dataTable.textContent() || '';

      // We allow: 已创建 (CREATED), 已通过 (APPROVED)
      // We disallow: 失败 (FAILED), 成功 (SUCCESS), 执行中 (RUNNING)
      expect(tableText).not.toContain('失败');
      expect(tableText).not.toContain('成功');
      expect(tableText).not.toContain('执行中');
    }

    // Clear the filter
    await filterBar.getByRole('button', { name: '清空筛选', exact: true }).click();
    await expect(filterBar.locator('.active-filters .el-tag')).toHaveCount(0);
  });

  test('createdAt filter uses local date boundary semantics', async ({ page }) => {
    await openPage(page, '发布单');

    const filterBar = page.locator('.order-filter-bar');

    // Get yesterday's and today's date in YYYY-MM-DD format (local date)
    const today = new Date();
    const yesterday = new Date(today);
    yesterday.setDate(yesterday.getDate() - 1);

    const formatDate = (date: Date) => {
      const year = date.getFullYear();
      const month = String(date.getMonth() + 1).padStart(2, '0');
      const day = String(date.getDate()).padStart(2, '0');
      return `${year}-${month}-${day}`;
    };

    const todayStr = formatDate(today);
    const yesterdayStr = formatDate(yesterday);

    // Intercept the API request before clicking query
    const apiRequestPromise = page.waitForRequest((request) => {
      const url = request.url();
      return request.method() === 'GET' &&
        url.includes('/api/releases') &&
        url.includes('createdAtStart') &&
        url.includes('createdAtEnd');
    });

    // Fill date range with yesterday to today
    const datePicker = filterBar.locator('.el-form-item').filter({ hasText: '创建时间' }).locator('.el-date-editor--daterange').first();
    await datePicker.click();

    // Wait for the date picker dropdown to appear
    await page.waitForSelector('.el-picker-panel', { state: 'visible' });

    // Click on yesterday's date in the calendar
    const yesterdayCell = page.locator('.el-date-table td.available').filter({ hasText: String(yesterday.getDate()) }).first();
    if (await yesterdayCell.isVisible().catch(() => false)) {
      await yesterdayCell.click();
    }

    // Click on today's date in the calendar
    const todayCell = page.locator('.el-date-table td.today, .el-date-table td.current').first();
    if (await todayCell.isVisible().catch(() => false)) {
      await todayCell.click();
    }

    // Click query button
    await filterBar.getByRole('button', { name: '查询', exact: true }).click();

    // Capture the API request
    const apiRequest = await apiRequestPromise;
    const requestUrl = apiRequest.url();

    // Verify the URL contains createdAtStart and createdAtEnd parameters
    expect(requestUrl).toContain('createdAtStart');
    expect(requestUrl).toContain('createdAtEnd');

    // Verify the time boundaries (00:00:00 for start, 23:59:59 for end)
    // URL encoded: T00%3A00%3A00 and T23%3A59%3A59
    const hasStartBoundary = requestUrl.includes('T00%3A00%3A00') || requestUrl.includes('T00:00:00');
    const hasEndBoundary = requestUrl.includes('T23%3A59%3A59') || requestUrl.includes('T23:59:59');

    expect(hasStartBoundary).toBe(true);
    expect(hasEndBoundary).toBe(true);

    // Verify the filter tag shows the date range
    await expect(filterBar.locator('.active-filters')).toContainText('创建时间：');

    // Clear the filter
    await filterBar.getByRole('button', { name: '清空筛选', exact: true }).click();
    await expect(filterBar.locator('.active-filters .el-tag')).toHaveCount(0);
  });

  // V1.19 E2E tests for focus mode sorting and empty state handling

  test('default release page requests sortMode=focus', async ({ page }) => {
    // Intercept the API request before navigating
    const apiRequestPromise = page.waitForRequest((request) => {
      const url = request.url();
      return request.method() === 'GET' && url.includes('/api/releases');
    });

    await openPage(page, '发布单');

    // Capture the API request
    const apiRequest = await apiRequestPromise;
    const requestUrl = apiRequest.url();

    // Verify the default request URL contains sortMode=focus
    expect(requestUrl).toContain('sortMode=focus');
  });

  test('default rollback page requests sortMode=focus', async ({ page }) => {
    // Intercept the API request before navigating
    const apiRequestPromise = page.waitForRequest((request) => {
      const url = request.url();
      return request.method() === 'GET' && url.includes('/api/rollbacks');
    });

    await openPage(page, '回滚单');

    // Capture the API request
    const apiRequest = await apiRequestPromise;
    const requestUrl = apiRequest.url();

    // Verify the default request URL contains sortMode=focus
    expect(requestUrl).toContain('sortMode=focus');
  });

  test('filtered release page does not include sortMode=focus', async ({ page }) => {
    await openPage(page, '发布单');

    const filterBar = page.locator('.order-filter-bar');
    await expect(filterBar).toBeVisible();

    // Intercept the API request before clicking a quick view button
    const apiRequestPromise = page.waitForRequest((request) => {
      const url = request.url();
      return request.method() === 'GET' && url.includes('/api/releases');
    });

    // Click "失败" quick view to apply a filter
    await filterBar.getByRole('button', { name: '失败', exact: true }).click();

    // Capture the API request
    const apiRequest = await apiRequestPromise;
    const requestUrl = apiRequest.url();

    // Verify the request URL does NOT contain sortMode=focus (filtered view should not use focus mode)
    expect(requestUrl).not.toContain('sortMode=focus');
  });

  test('filtered rollback page does not include sortMode=focus', async ({ page }) => {
    await openPage(page, '回滚单');

    const filterBar = page.locator('.order-filter-bar');
    await expect(filterBar).toBeVisible();

    // Intercept the API request before applying a filter
    const apiRequestPromise = page.waitForRequest((request) => {
      const url = request.url();
      return request.method() === 'GET' && url.includes('/api/rollbacks');
    });

    // Click "失败" quick view to apply a filter
    const failedButton = filterBar.getByRole('button', { name: '失败', exact: true });
    if (await failedButton.isVisible()) {
      await failedButton.click();
    } else {
      // Fallback: use keyword search if quick view not available for rollbacks
      const keywordInput = filterBar.locator('input').filter({ hasText: '' }).first();
      await keywordInput.fill('NONEXISTENT-KEYWORD-FOR-FILTER');
      await filterBar.getByRole('button', { name: '查询', exact: true }).click();
    }

    // Capture the API request
    const apiRequest = await apiRequestPromise;
    const requestUrl = apiRequest.url();

    // Verify the request URL does NOT contain sortMode=focus (filtered view should not use focus mode)
    expect(requestUrl).not.toContain('sortMode=focus');
  });

  test('release page shows correct empty state for filtered view', async ({ page }) => {
    await openPage(page, '发布单');

    const filterBar = page.locator('.order-filter-bar');
    await expect(filterBar).toBeVisible();

    // Apply a keyword filter using the input field
    const keywordInput = filterBar.locator('input[placeholder="发布单号"]');
    await keywordInput.fill('NONEXISTENT-KEYWORD-XYZ');
    const emptyResponsePromise = page.waitForResponse((response) =>
      response.request().method() === 'GET' &&
      response.url().includes('/api/releases') &&
      response.url().includes('keyword=NONEXISTENT-KEYWORD-XYZ'),
    );
    await filterBar.getByRole('button', { name: '查询', exact: true }).click();
    const emptyResponse = await emptyResponsePromise;
    expect(emptyResponse.ok()).toBeTruthy();
    await expect(page.locator('.content-card .el-table .el-loading-mask:visible')).toHaveCount(0);

    // Verify the active filter tag shows the keyword
    await expect(filterBar.locator('.active-filters')).toContainText('单号：NONEXISTENT-KEYWORD-XYZ');

    // V1.19: Verify empty state shows filtered message (not default "暂无发布单")
    const table = page.locator('.content-card .el-table');
    const emptyText = table.locator('.el-table__empty-text');
    await expect(emptyText).toContainText('当前筛选条件下没有结果');

    // Verify "清空筛选" button is visible in the filter bar when filters are active
    const clearButton = filterBar.getByRole('button', { name: '清空筛选', exact: true });
    await expect(clearButton).toBeVisible();

    // Click "清空筛选" to return to default view
    await clearButton.click();

    // Verify the filter is cleared (no active filter tags)
    await expect(filterBar.locator('.active-filters .el-tag')).toHaveCount(0);
  });

  test('release page default view prioritizes RUNNING and FAILED orders', async ({ page }) => {
    await openPage(page, '发布单');

    // Wait for table to load
    const table = page.locator('.content-card .el-table');
    await expect(table).toBeVisible();

    // Wait for data to load
    await page.waitForTimeout(1000);

    // Get all status cells from the first few rows
    const statusCells = table.locator('tbody tr .el-table__cell:nth-child(5)'); // Status is typically the 5th column
    const count = await statusCells.count();

    // If there are orders, verify priority ordering
    if (count > 0) {
      // Collect statuses from first 5 rows (or all if fewer than 5)
      const maxRows = Math.min(count, 5);
      const statuses: string[] = [];
      for (let i = 0; i < maxRows; i++) {
        const status = await statusCells.nth(i).textContent();
        if (status) statuses.push(status.trim());
      }

      // V1.19: RUNNING and FAILED should appear before other statuses in default view
      // If we see non-RUNNING/FAILED statuses, there should be no RUNNING/FAILED after them
      const runningOrFailedIndex = statuses.findIndex((s) => s === 'RUNNING' || s === 'FAILED' || s === '执行中' || s === '失败');
      const otherIndex = statuses.findIndex((s) => s !== 'RUNNING' && s !== 'FAILED' && s !== '执行中' && s !== '失败');

      // If both types exist, RUNNING/FAILED should come first (lower index)
      if (runningOrFailedIndex !== -1 && otherIndex !== -1) {
        expect(runningOrFailedIndex).toBeLessThan(otherIndex);
      }
    }
  });

  test('rollback page default view prioritizes high-value orders', async ({ page }) => {
    await openPage(page, '回滚单');

    // Wait for table to load
    const table = page.locator('.content-card .el-table');
    await expect(table).toBeVisible();

    // Wait for data to load
    await page.waitForTimeout(1000);

    // Get all status cells from the first few rows
    const statusCells = table.locator('tbody tr .el-table__cell:nth-child(4)'); // Status is typically the 4th column for rollbacks
    const count = await statusCells.count();

    // If there are orders, verify priority ordering
    if (count > 0) {
      // Collect statuses from first 5 rows
      const maxRows = Math.min(count, 5);
      const statuses: string[] = [];
      for (let i = 0; i < maxRows; i++) {
        const status = await statusCells.nth(i).textContent();
        if (status) statuses.push(status.trim());
      }

      // V1.19: RUNNING and FAILED should appear before other statuses in default view
      const runningOrFailedIndex = statuses.findIndex((s) => s === 'RUNNING' || s === 'FAILED' || s === '执行中' || s === '失败');
      const otherIndex = statuses.findIndex((s) => s !== 'RUNNING' && s !== 'FAILED' && s !== '执行中' && s !== '失败');

      // If both types exist, RUNNING/FAILED should come first
      if (runningOrFailedIndex !== -1 && otherIndex !== -1) {
        expect(runningOrFailedIndex).toBeLessThan(otherIndex);
      }
    }
  });

  // V1.20 E2E tests for next step suggestion and action ordering

  test('release list shows next step suggestion column with correct text', async ({ page }) => {
    await openPage(page, '发布单');

    // Verify "下一步建议" column header is visible
    const nextStepHeader = page.getByRole('columnheader', { name: '下一步建议', exact: true });
    await expect(nextStepHeader).toBeVisible();

    // Create a new release order to verify CREATED status shows "前置检查"
    const suffix = Date.now().toString().slice(-8);
    const releaseOrderNo = `REL-NEXT-${suffix}`;

    const releaseDialog = await openCreateDialog(page, '发布单');
    await fillTextField(releaseDialog, '发布单号', releaseOrderNo);
    await chooseSelectOption(releaseDialog, page, '应用名称', '订单协同平台-后端');
    await chooseSelectOption(releaseDialog, page, '服务名称', '订单协同平台后端服务');
    await chooseSelectOption(releaseDialog, page, '环境名称', '测试环境');
    await chooseSelectOption(releaseDialog, page, '版本', 'v1.0.0');
    await saveDialog(page);

    // V1.20: Use findRowAcrossPages because sortMode=focus may place new rows beyond page 1
    const releaseRow = await findRowAcrossPages(page, releaseOrderNo);
    await expect(releaseRow).toBeVisible();

    // V1.20: CREATED status should show "前置检查" as next step
    await expect(releaseRow).toContainText('前置检查');
  });

  test('rollback list shows next step suggestion column with correct text', async ({ page }) => {
    await openPage(page, '回滚单');

    // Verify "下一步建议" column header is visible
    const nextStepHeader = page.getByRole('columnheader', { name: '下一步建议', exact: true });
    await expect(nextStepHeader).toBeVisible();

    // Find an existing rollback row to verify suggestion is displayed
    const rollbackRow = await findRowAcrossPages(page, 'INFRAHUB-RB-20260414103340');
    if (await rollbackRow.isVisible()) {
      // SUCCESS status row should have a suggestion (either "查看部署记录" or "查看详情")
      const rowText = await rollbackRow.textContent();
      expect(rowText).toMatch(/查看部署记录|查看详情/);
    }
  });

  test('release row actions follow status-based ordering', async ({ page }) => {
    const suffix = Date.now().toString().slice(-8);
    const releaseOrderNo = `REL-ACT-ORD-${suffix}`;

    // Create a new release order (CREATED status)
    const releaseDialog = await openCreateDialog(page, '发布单');
    await fillTextField(releaseDialog, '发布单号', releaseOrderNo);
    await chooseSelectOption(releaseDialog, page, '应用名称', '订单协同平台-后端');
    await chooseSelectOption(releaseDialog, page, '服务名称', '订单协同平台后端服务');
    await chooseSelectOption(releaseDialog, page, '环境名称', '测试环境');
    await chooseSelectOption(releaseDialog, page, '版本', 'v1.0.0');
    await saveDialog(page);

    // V1.20: Use findRowAcrossPages because sortMode=focus may place new rows beyond page 1
    const releaseRow = await findRowAcrossPages(page, releaseOrderNo);
    await expect(releaseRow).toBeVisible();

    // V1.21: Verify actual button ORDER, not just visibility
    // 第二批 CREATED 行内动作顺序: 详情 → 前置检查 → 执行发布 → 部署记录 → 更多
    // （流水线发布仍是次要动作，收进「更多」菜单，能力未删减，见下方菜单可达性断言）
    const actionButtons = releaseRow.locator('.el-button--text, .el-button--small').filter({ has: releaseRow });
    const allButtons = releaseRow.getByRole('button');
    const buttonTexts: string[] = [];
    const count = await allButtons.count();
    for (let i = 0; i < count; i++) {
      const text = (await allButtons.nth(i).textContent() ?? '').trim();
      if (text) buttonTexts.push(text);
    }

    // Verify key ordering: 详情 must come before 前置检查, which must come before 执行发布
    const detailIdx = buttonTexts.indexOf('详情');
    const precheckIdx = buttonTexts.indexOf('前置检查');
    const executeIdx = buttonTexts.indexOf('执行发布');
    const deployIdx = buttonTexts.indexOf('部署记录');
    const moreIdx = buttonTexts.indexOf('更多');

    expect(detailIdx).toBeGreaterThanOrEqual(0);
    expect(precheckIdx).toBeGreaterThanOrEqual(0);
    expect(executeIdx).toBeGreaterThanOrEqual(0);
    expect(deployIdx).toBeGreaterThanOrEqual(0);
    expect(detailIdx).toBeLessThan(precheckIdx);
    expect(precheckIdx).toBeLessThan(executeIdx);
    expect(executeIdx).toBeLessThan(deployIdx);
    expect(deployIdx).toBeLessThan(moreIdx);

    // 当前状态下只高亮一个主操作：CREATED 可执行时即「执行发布」
    const primaryActions = releaseRow.locator('.is-row-primary-action');
    await expect(primaryActions).toHaveCount(1);
    await expect(primaryActions).toHaveText('执行发布');

    // 次要动作未删减：流水线发布 仍可从「更多」菜单到达（原显示条件与处理函数不变）
    await releaseRow.getByRole('button', { name: '更多', exact: true }).click();
    await expect(page.getByRole('menuitem', { name: '流水线发布', exact: true })).toBeVisible();
    await page.keyboard.press('Escape');
  });

  test('rollback row actions follow status-based ordering', async ({ page }) => {
    // 正向夹具（仅本用例）：只有「明确支持的 SSH 回滚单」才呈现可执行动作；
    // 共享 mock 的种子回滚单没有 executorType / rollbackSupported，不能作为本用例的判据。
    const rollbackId = 9101;
    const rollbackNo = 'RB-ORDER-9101';
    const rollbackFixture = {
      id: rollbackId,
      rollbackNo,
      appId: 1,
      serviceId: 101,
      envId: 20,
      appName: '订单协同平台-后端',
      serviceName: '订单协同平台后端服务',
      envName: '测试环境',
      fromVersionNo: 'v2.0.0',
      toVersionNo: 'v1.0.0',
      status: 'CREATED',
      executorType: 'SSH',
      rollbackSupported: true,
    };

    await page.route('**/api/rollbacks**', async (route) => {
      const request = route.request();
      const url = new URL(request.url());
      if (request.method() === 'GET' && url.pathname === '/api/rollbacks') {
        const keyword = url.searchParams.get('keyword');
        const statusParam = url.searchParams.get('status');
        let records = [rollbackFixture];
        if (keyword) {
          records = records.filter((item) => String(item.rollbackNo).includes(keyword));
        }
        if (statusParam) {
          const allowed = statusParam.split(',').map((item) => item.trim());
          records = records.filter((item) => allowed.includes(String(item.status)));
        }
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({
            code: 'SUCCESS',
            data: { pageNum: 1, pageSize: 20, total: records.length, records },
          }),
        });
        return;
      }
      await route.fallback();
    });

    await openPage(page, '回滚单');

    // V1.21: Verify actual button ORDER for rollback CREATED status
    // Order: 详情 → 前置检查 → 执行回滚 → 部署记录
    const createdRow = page.locator('tbody tr').filter({ hasText: rollbackNo }).first();
    await expect(createdRow).toBeVisible({ timeout: 15000 });

    const allButtons = createdRow.getByRole('button');
    const buttonTexts: string[] = [];
    const count = await allButtons.count();
    for (let i = 0; i < count; i++) {
      const text = (await allButtons.nth(i).textContent() ?? '').trim();
      if (text) buttonTexts.push(text);
    }

    const detailIdx = buttonTexts.indexOf('详情');
    const precheckIdx = buttonTexts.indexOf('前置检查');
    const executeIdx = buttonTexts.indexOf('执行回滚');
    const deployIdx = buttonTexts.indexOf('部署记录');

    // 行与按钮齐备由断言保证：找不到行/按钮不再静默通过
    expect(buttonTexts).toEqual(
      expect.arrayContaining(['详情', '前置检查', '执行回滚', '部署记录']),
    );
    expect(detailIdx).toBeGreaterThanOrEqual(0);
    expect(precheckIdx).toBeGreaterThanOrEqual(0);
    expect(executeIdx).toBeGreaterThanOrEqual(0);
    // 状态相关动作严格相邻：详情 → 前置检查 → 执行回滚 → 部署记录
    expect(precheckIdx).toBe(detailIdx + 1);
    expect(executeIdx).toBe(precheckIdx + 1);
    expect(deployIdx).toBe(executeIdx + 1);
  });

  test('release detail drawer shows next step suggestion matching list', async ({ page }) => {
    const suffix = Date.now().toString().slice(-8);
    const releaseOrderNo = `REL-DETAIL-NEXT-${suffix}`;

    // Create a new release order
    const releaseDialog = await openCreateDialog(page, '发布单');
    await fillTextField(releaseDialog, '发布单号', releaseOrderNo);
    await chooseSelectOption(releaseDialog, page, '应用名称', '订单协同平台-后端');
    await chooseSelectOption(releaseDialog, page, '服务名称', '订单协同平台后端服务');
    await chooseSelectOption(releaseDialog, page, '环境名称', '测试环境');
    await chooseSelectOption(releaseDialog, page, '版本', 'v1.0.0');
    await saveDialog(page);

    // V1.20: Use findRowAcrossPages because sortMode=focus may place new rows beyond page 1
    const releaseRow = await findRowAcrossPages(page, releaseOrderNo);
    await expect(releaseRow).toBeVisible();

    // Open the detail drawer
    await releaseRow.getByRole('button', { name: '详情', exact: true }).click();

    // V1.20: Detail drawer should show "下一步建议" in execution summary section
    const drawer = page.locator('.el-drawer');
    await expect(drawer).toBeVisible();

    // V1.20: Verify "下一步建议" label is shown and contains "前置检查" for CREATED status
    await expect(drawer).toContainText('下一步建议');
    await expect(drawer).toContainText('前置检查');
  });

  test('running release shows pipeline log priority hint', async ({ page }) => {
    await openPage(page, '发布单');

    // Look for RUNNING status rows
    const runningRow = page.locator('tbody tr').filter({ hasText: 'RUNNING' }).first();
    if (await runningRow.isVisible()) {
      // V1.20: RUNNING row should have status tip
      const statusTip = runningRow.locator('.status-tip, .el-tag').filter({ hasText: /流水线日志|部署记录|执行中/ });
      // If hasPipelineRun, should show "优先查看流水线日志", otherwise "执行中，可查看部署记录"
      const tipText = await statusTip.first().textContent();
      expect(tipText).toMatch(/优先查看流水线日志|执行中，可查看部署记录/);
    }
  });

  test('failed release shows priority hint for deploy records', async ({ page }) => {
    await openPage(page, '发布单');

    // Look for FAILED status rows
    const failedRow = page.locator('tbody tr').filter({ hasText: 'FAILED' }).first();
    if (await failedRow.isVisible()) {
      // V1.20: FAILED row should have "优先查看部署记录" tag
      await expect(failedRow.locator('.status-tip, .el-tag').filter({ hasText: '优先查看部署记录' })).toBeVisible();
    }
  });

  // V1.21 E2E tests for action feedback and refresh coordination

  test('release precheck shows unified success message', async ({ page }) => {
    const suffix = Date.now().toString().slice(-8);
    const releaseOrderNo = `REL-PRECHECK-${suffix}`;

    // Create a new release order
    const releaseDialog = await openCreateDialog(page, '发布单');
    await fillTextField(releaseDialog, '发布单号', releaseOrderNo);
    await chooseSelectOption(releaseDialog, page, '应用名称', '订单协同平台-后端');
    await chooseSelectOption(releaseDialog, page, '服务名称', '订单协同平台后端服务');
    await chooseSelectOption(releaseDialog, page, '环境名称', '测试环境');
    await chooseSelectOption(releaseDialog, page, '版本', 'v1.0.0');
    await chooseSelectOption(releaseDialog, page, '风险等级', 'LOW');
    await saveDialog(page);

    // Use keyword filter to reliably locate the new row (sortMode=focus may push CREATED rows far back)
    const filterBar = page.locator('.order-filter-bar');
    const keywordInput = filterBar.locator('input').first();
    await keywordInput.fill(releaseOrderNo);
    await filterBar.getByRole('button', { name: '查询', exact: true }).click();
    await page.waitForTimeout(500);

    const releaseRow = page.locator('tbody tr').filter({ hasText: releaseOrderNo }).first();
    await expect(releaseRow).toBeVisible({ timeout: 10000 });

    // Click precheck button
    await releaseRow.getByRole('button', { name: '前置检查', exact: true }).click();

    // V1.21: Wait for precheck dialog to open and complete
    const precheckDialog = page.locator('.el-dialog').filter({ hasText: '前置检查' });
    await expect(precheckDialog).toBeVisible();

    // V1.21: Wait for precheck to complete and dialog to close
    await page.waitForTimeout(2000);

    // V1.21: Verify the success message appears
    await expect(page.locator('.el-message').filter({ hasText: /前置检查已提交/ })).toBeVisible();
  });

  test('release execute shows unified success message', async ({ page }) => {
    const suffix = Date.now().toString().slice(-8);
    const releaseOrderNo = `REL-EXEC-${suffix}`;

    // Create a new release order
    const releaseDialog = await openCreateDialog(page, '发布单');
    await fillTextField(releaseDialog, '发布单号', releaseOrderNo);
    await chooseSelectOption(releaseDialog, page, '应用名称', '订单协同平台-后端');
    await chooseSelectOption(releaseDialog, page, '服务名称', '订单协同平台后端服务');
    await chooseSelectOption(releaseDialog, page, '环境名称', '测试环境');
    await chooseSelectOption(releaseDialog, page, '版本', 'v1.0.0');
    await chooseSelectOption(releaseDialog, page, '风险等级', 'LOW');
    await saveDialog(page);

    const releaseRow = await findRowAcrossPages(page, releaseOrderNo);
    await expect(releaseRow).toBeVisible();

    // V1.21: Click execute button
    await releaseRow.getByRole('button', { name: '执行发布', exact: true }).click();

    // Confirm the action via ExecuteConfirmDialog (el-dialog, not el-message-box)
    const confirmDialog = page.locator('.el-dialog').filter({ hasText: '确认执行发布' });
    await expect(confirmDialog).toBeVisible();
    await confirmDialog.locator('.el-checkbox').click();
    await confirmDialog.getByRole('button', { name: '确认执行', exact: true }).click();

    // V1.21: Verify success message appears
    await expect(page.locator('.el-message').filter({ hasText: /已提交|发布/ })).toBeVisible();
  });

  test('release row shows recent action feedback after execute', async ({ page }) => {
    const suffix = Date.now().toString().slice(-8);
    const releaseOrderNo = `REL-FEEDBACK-${suffix}`;

    // Create a new release order
    const releaseDialog = await openCreateDialog(page, '发布单');
    await fillTextField(releaseDialog, '发布单号', releaseOrderNo);
    await chooseSelectOption(releaseDialog, page, '应用名称', '订单协同平台-后端');
    await chooseSelectOption(releaseDialog, page, '服务名称', '订单协同平台后端服务');
    await chooseSelectOption(releaseDialog, page, '环境名称', '测试环境');
    await chooseSelectOption(releaseDialog, page, '版本', 'v1.0.0');
    await chooseSelectOption(releaseDialog, page, '风险等级', 'LOW');
    await saveDialog(page);

    // Use keyword filter to reliably locate the new row (sortMode=focus may push CREATED rows far back)
    const filterBar = page.locator('.order-filter-bar');
    const keywordInput = filterBar.locator('input').first();
    await keywordInput.fill(releaseOrderNo);
    await filterBar.getByRole('button', { name: '查询', exact: true }).click();
    await page.waitForTimeout(500);

    const releaseRow = page.locator('tbody tr').filter({ hasText: releaseOrderNo }).first();
    await expect(releaseRow).toBeVisible({ timeout: 10000 });

    // Execute the release
    await releaseRow.getByRole('button', { name: '执行发布', exact: true }).click();
    const confirmDialog = page.locator('.el-dialog').filter({ hasText: '确认执行发布' });
    await expect(confirmDialog).toBeVisible();
    await confirmDialog.locator('.el-checkbox').click();
    await confirmDialog.getByRole('button', { name: '确认执行', exact: true }).click();

    // V1.21: Wait for action to complete — success message confirms the action was processed
    await expect(page.locator('.el-message').filter({ hasText: /已提交|发布/ })).toBeVisible();

    // V1.21: Row should show "刚提交发布" feedback tag
    // Re-locate the row after refresh since list may have re-rendered
    const refreshedRow = page.locator('tbody tr').filter({ hasText: releaseOrderNo }).first();
    const feedbackTag = refreshedRow.locator('.el-tag').filter({ hasText: '刚提交发布' });
    await expect(feedbackTag).toBeVisible();
  });

  test('release detail drawer shows post action guide for running status', async ({ page }) => {
    await openPage(page, '发布单');

    // Find a running row
    const runningRow = page.locator('tbody tr').filter({ hasText: 'RUNNING' }).first();
    if (await runningRow.isVisible()) {
      await runningRow.getByRole('button', { name: '详情', exact: true }).click();

      const drawer = page.locator('.el-drawer');
      await expect(drawer).toBeVisible();

      // V1.21: Should show "去向说明" section for RUNNING status
      // The guide text should mention where to check progress
      await expect(drawer).toContainText('去向说明');
    }
  });

  test('rollback execute shows unified success message', async ({ page }) => {
    // 正向夹具（仅本用例）：CREATED + SSH + rollbackSupported=true 才能进入执行确认并提交；
    // 共享 mock 的种子回滚单缺少支持判据，无法提交，因此这里为该回滚单号补一份可执行数据。
    const rollbackId = 9201;
    const rollbackNo = 'RB-EXEC-9201';
    const rollbackFixture = {
      id: rollbackId,
      rollbackNo,
      appId: 1,
      serviceId: 101,
      envId: 20,
      appName: '订单协同平台-后端',
      serviceName: '订单协同平台后端服务',
      envName: '测试环境',
      fromVersionNo: 'v2.0.0',
      toVersionNo: 'v1.0.0',
      status: 'CREATED',
      executorType: 'SSH',
      rollbackSupported: true,
    };

    // 执行请求计数：只记录，不改变共享 mock 的应答
    const executeRequests: string[] = [];
    await page.route('**/api/rollbacks**', async (route) => {
      const request = route.request();
      const url = new URL(request.url());
      if (request.method() === 'POST' && url.pathname === `/api/rollbacks/${rollbackId}/execute`) {
        executeRequests.push(url.pathname);
      }
      await route.fallback();
    });

    await page.route('**/api/rollbacks**', async (route) => {
      const request = route.request();
      const url = new URL(request.url());
      if (request.method() === 'GET' && url.pathname === '/api/rollbacks') {
        const keyword = url.searchParams.get('keyword');
        const statusParam = url.searchParams.get('status');
        let records = [rollbackFixture];
        if (keyword) {
          records = records.filter((item) => String(item.rollbackNo).includes(keyword));
        }
        if (statusParam) {
          const allowed = statusParam.split(',').map((item) => item.trim());
          records = records.filter((item) => allowed.includes(String(item.status)));
        }
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({
            code: 'SUCCESS',
            data: { pageNum: 1, pageSize: 20, total: records.length, records },
          }),
        });
        return;
      }
      await route.fallback();
    });

    // 执行确认弹窗的必要读取（Contract 3.2）：给出合法上下文，风险为 LOW
    await page.route(`**/api/rollbacks/${rollbackId}/execution-context`, async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          code: 'SUCCESS',
          data: {
            orderId: rollbackId,
            orderType: 'ROLLBACK',
            appId: 1,
            appName: '订单协同平台-后端',
            serviceName: '订单协同平台后端服务',
            envId: 20,
            envName: '测试环境',
            deployConfigComplete: true,
            precheckStatus: 'SUCCESS',
            riskLevel: 'LOW',
            risks: [],
            versionCompare: [],
            generatedAt: '2026-05-29T10:00:00',
          },
        }),
      });
    });

    await openPage(page, '回滚单');

    const createdRow = page.locator('tbody tr').filter({ hasText: rollbackNo }).first();
    await expect(createdRow).toBeVisible({ timeout: 15000 });
    await createdRow.getByRole('button', { name: '执行回滚', exact: true }).click();

    const confirmDialog = page.locator('.el-dialog').filter({ hasText: '确认执行回滚' });
    await expect(confirmDialog).toBeVisible();

    // 必要确认：上下文读取完成、提交未被阻断（不支持/无法确定支持状态与 HIGH 风险仍会阻断）
    await expect(confirmDialog.getByText('风险等级')).toBeVisible({ timeout: 10000 });
    const confirmButton = confirmDialog.getByRole('button', { name: '确认执行', exact: true });
    await expect(confirmButton).toBeEnabled();

    // 确认之前：没有任何执行请求
    expect(executeRequests).toEqual([]);

    const executeResponse = page.waitForResponse((response) => {
      const url = new URL(response.url());
      return response.request().method() === 'POST' && url.pathname === `/api/rollbacks/${rollbackId}/execute`;
    });
    await confirmButton.click();
    await executeResponse;

    // 确认之后：目标回滚单的执行请求恰一次
    expect(executeRequests).toEqual([`/api/rollbacks/${rollbackId}/execute`]);

    // V1.21: Verify success message appears（统一反馈文案与当前产品文案一致）
    const feedback = page
      .locator('.el-message')
      .filter({ hasText: '回滚已提交，可在部署记录中查看进度' })
      .first();
    await expect(feedback).toBeVisible();
    await expect(feedback).toHaveText('回滚已提交，可在部署记录中查看进度');
    expect(executeRequests).toEqual([`/api/rollbacks/${rollbackId}/execute`]);
  });

  test('action buttons remain accessible across sessions', async ({ page }) => {
    // V1.21: Verify that action entry points (部署记录, 前置检查, 详情) remain accessible
    await openPage(page, '发布单');

    // Find any row and verify key action buttons exist
    const anyRow = page.locator('tbody tr').first();
    await expect(anyRow).toBeVisible();

    // 详情 should always be visible for all rows
    await expect(anyRow.getByRole('button', { name: '详情', exact: true })).toBeVisible();

    // 部署记录 should always be visible for all rows
    await expect(anyRow.getByRole('button', { name: '部署记录', exact: true })).toBeVisible();
  });
});

interface PipelineFixture {
  pipelineCode: string;
  pipelineName: string;
  stageName: string;
  stepName: string;
  scriptContent: string;
}

async function createPipelineWithSingleStep(page: Page, fixture: PipelineFixture) {
  const pipelineDialog = await openCreateDialog(page, '流水线定义');
  await fillTextField(pipelineDialog, '流水线编码', fixture.pipelineCode);
  await fillTextField(pipelineDialog, '流水线名称', fixture.pipelineName);
  await chooseSelectOption(pipelineDialog, page, '应用名称', '订单协同平台-后端');
  await chooseSelectOption(pipelineDialog, page, '触发方式', 'MANUAL');
  await chooseSelectOption(pipelineDialog, page, '流水线类型', 'DEPLOY');
  await saveDialog(page);

  await openPipelineConfigByCode(page, fixture.pipelineCode, fixture.pipelineName);

  await page.getByRole('button', { name: '新建阶段', exact: true }).click();
  let dialog = activeDialog(page);
  await fillTextField(dialog, '阶段名称', fixture.stageName);
  await fillNumberField(dialog, '阶段顺序', '1');
  await fillNumberField(dialog, '超时(秒)', '30');
  await saveDialog(page);

  await page.getByRole('button', { name: '新建步骤', exact: true }).click();
  dialog = activeDialog(page);
  await fillTextField(dialog, '步骤名称', fixture.stepName);
  await fillTextField(dialog, '步骤类型', 'SCRIPT');
  await chooseSelectOption(dialog, page, '执行器类型', 'LOCAL_SHELL');
  await fillNumberField(dialog, '步骤顺序', '1');
  await fillTextArea(dialog, '脚本内容', fixture.scriptContent);
  await fillNumberField(dialog, '超时(秒)', '30');
  await fillNumberField(dialog, '重试次数', '0');
  await saveDialog(page);
  await expect(tableRow(page, fixture.stepName)).toBeVisible();
}

async function createReleaseDeployPipeline(page: Page, fixture: Omit<PipelineFixture, 'scriptContent'>) {
  const pipelineDialog = await openCreateDialog(page, '流水线定义');
  await fillTextField(pipelineDialog, '流水线编码', fixture.pipelineCode);
  await fillTextField(pipelineDialog, '流水线名称', fixture.pipelineName);
  await chooseSelectOption(pipelineDialog, page, '应用名称', '订单协同平台-后端');
  await chooseSelectOption(pipelineDialog, page, '触发方式', 'MANUAL');
  await chooseSelectOption(pipelineDialog, page, '流水线类型', 'DEPLOY');
  await saveDialog(page);

  await openPipelineConfigByCode(page, fixture.pipelineCode, fixture.pipelineName);

  await page.getByRole('button', { name: '新建阶段', exact: true }).click();
  let dialog = activeDialog(page);
  await fillTextField(dialog, '阶段名称', fixture.stageName);
  await fillNumberField(dialog, '阶段顺序', '1');
  await fillNumberField(dialog, '超时(秒)', '180');
  await saveDialog(page);

  await page.getByRole('button', { name: '新建步骤', exact: true }).click();
  dialog = activeDialog(page);
  await fillTextField(dialog, '步骤名称', fixture.stepName);
  await fillTextField(dialog, '步骤类型', 'DEPLOY');
  await chooseSelectOption(dialog, page, '执行器类型', 'RELEASE_DEPLOY');
  await fillNumberField(dialog, '步骤顺序', '1');
  await fillNumberField(dialog, '超时(秒)', '180');
  await fillNumberField(dialog, '重试次数', '0');
  await saveDialog(page);
  await expect(tableRow(page, fixture.stepName)).toBeVisible();
}

async function createReleaseOrder(page: Page, releaseOrderNo: string) {
  const releaseDialog = await openCreateDialog(page, '发布单');
  await fillTextField(releaseDialog, '发布单号', releaseOrderNo);
  await chooseSelectOption(releaseDialog, page, '应用名称', '订单协同平台-后端');
  await chooseSelectOption(releaseDialog, page, '服务名称', '订单协同平台后端服务');
  await chooseSelectOption(releaseDialog, page, '环境名称', '测试环境');
  await chooseSelectOption(releaseDialog, page, '版本', 'v1.0.0');
  await chooseSelectOption(releaseDialog, page, '风险等级', 'LOW');
  await saveDialog(page);

  const releaseRow = await findRowAcrossPages(page, releaseOrderNo);
  await expect(releaseRow).toBeVisible();
}

async function apiData<T>(page: Page, url: string): Promise<T> {
  const result = await page.evaluate(async ({ url, token }: { url: string; token: string }) => {
    const res = await fetch(url, { headers: { Authorization: `Bearer ${token}` } });
    return res.json();
  }, { url, token: await getToken(page) });
  expect(result.code).toBe('SUCCESS');
  return result.data as T;
}

async function postApi(page: Page, url: string, data: Record<string, unknown>) {
  return page.evaluate(async ({ url, token, data }: { url: string; token: string; data: Record<string, unknown> }) => {
    const res = await fetch(url, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` },
      body: JSON.stringify(data),
    });
    return res.json();
  }, { url, token: await getToken(page), data });
}

async function getToken(page: Page): Promise<string> {
  const token = await page.evaluate(() => window.localStorage.getItem('INFRAHUB_TOKEN'));
  expect(token).toBeTruthy();
  return token!;
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

async function openPipelineConfigByCode(page: Page, pipelineCode: string, pipelineName: string) {
  const pipeline = await findRecordByField(page, '/api/pipelines', 'pipelineCode', pipelineCode);
  await page.goto(`/pipeline/defs/${pipeline.id}`);
  await expect(page.getByRole('heading', { name: pipelineName })).toBeVisible();
}

async function ensurePipelineStepsOpenedByReleaseOrderNo(page: Page, releaseOrderNo: string) {
  const urlPattern = /\/pipeline\/runs\/\d+\/steps/;
  try {
    await page.waitForURL(urlPattern, { timeout: 20000 });
  } catch {
    const release = await findRecordByField(page, '/api/releases', 'orderNo', releaseOrderNo);
    const runPage = await apiData<{ records: Record<string, unknown>[] }>(
      page,
      `/api/pipeline-runs?pageNum=1&pageSize=1&releaseId=${release.id}`,
    );
    const run = runPage.records[0];
    expect(run?.id).toBeTruthy();
    await page.goto(`/pipeline/runs/${run.id}/steps`);
  }
  await expect(page.getByRole('heading', { name: '步骤执行记录' })).toBeVisible();
}

/**
 * 点开当前版本行上的「更多」，再按文案点中其中的菜单项。
 * 部署配置 / 最近部署记录 / 发布单 / 运行实例是同一行上原有的入口，只是从平铺按钮收进了「更多」，
 * 这里仅补上"先展开下拉"这一步，各入口自身的行为断言保持不变。
 */
async function clickRowMoreItem(page: Page, row: Locator, itemText: string) {
  await row.getByRole('button', { name: '更多', exact: true }).click();
  // 下拉层挂在 body 上，只能从页面级定位；已关闭的旧下拉层不可见，不会参与匹配
  const item = page.getByRole('menuitem', { name: itemText, exact: true });
  await expect(item).toBeVisible();
  await item.click();
}

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
