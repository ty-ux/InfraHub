import { expect, test } from '@playwright/test';
import { loginAsAdmin } from './helpers/auth';
import { mockResourceApis, mockPipelineApis } from './helpers/mocks';
import {
  activeDialog,
  chooseSelectOption,
  fillNumberField,
  fillTextArea,
  fillTextField,
  openCreateDialog,
  saveDialog,
  tableRow,
} from './helpers/ui';

test.describe('pipeline stage and step configuration', () => {
  test.setTimeout(120000);

  test.beforeEach(async ({ page }) => {
    await loginAsAdmin(page);
    await mockResourceApis(page);
    await mockPipelineApis(page);
  });

  test('pipeline can be configured and run from definition detail page', async ({ page }) => {
    const suffix = Date.now().toString().slice(-8);
    const pipelineCode = `pipe-e2e-${suffix}`;
    const pipelineName = `流水线 E2E ${suffix}`;
    const stageName = `阶段 E2E ${suffix}`;
    const stepName = `步骤 E2E ${suffix}`;
    const logMarker = `e2e-pipeline-step-${suffix}`;

    const pipelineDialog = await openCreateDialog(page, '流水线定义');
    await fillTextField(pipelineDialog, '流水线编码', pipelineCode);
    await fillTextField(pipelineDialog, '流水线名称', pipelineName);
    await chooseSelectOption(pipelineDialog, page, '应用名称', '订单协同平台-后端');
    await chooseSelectOption(pipelineDialog, page, '触发方式', 'MANUAL');
    await chooseSelectOption(pipelineDialog, page, '流水线类型', 'DEPLOY');
    await saveDialog(page);

    const pipelineRow = await findRowAcrossPages(page, pipelineCode);
    await expect(pipelineRow).toBeVisible();
    await pipelineRow.getByRole('button', { name: '配置', exact: true }).click();
    await expect(page.getByRole('heading', { name: pipelineName })).toBeVisible();

    await page.getByRole('button', { name: '新建阶段', exact: true }).click();
    let dialog = activeDialog(page);
    await fillTextField(dialog, '阶段名称', stageName);
    await fillNumberField(dialog, '阶段顺序', '1');
    await fillNumberField(dialog, '超时(秒)', '30');
    await saveDialog(page);
    await expect(tableRow(page, stageName)).toBeVisible();

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
    const stepRunRow = tableRow(page, stepName);
    await expect(stepRunRow).toBeVisible();
    await expect(async () => {
      await page.getByRole('button', { name: '刷新', exact: true }).click();
      // 步骤状态列按当前语言渲染（成功），不再断言后端原始枚举 SUCCESS
      await expect(stepRunRow).toContainText('成功', { timeout: 1000 });
      await expect(stepRunRow).toContainText(logMarker, { timeout: 1000 });
    }).toPass({ timeout: 30000 });
  });

  test('release deploy step does not require script and blocks manual run', async ({ page }) => {
    const suffix = Date.now().toString().slice(-8);
    const pipelineCode = `pipe-deploy-e2e-${suffix}`;
    const pipelineName = `真实部署流水线 E2E ${suffix}`;
    const stageName = `真实部署阶段 E2E ${suffix}`;
    const stepName = `真实部署步骤 E2E ${suffix}`;

    const pipelineDialog = await openCreateDialog(page, '流水线定义');
    await fillTextField(pipelineDialog, '流水线编码', pipelineCode);
    await fillTextField(pipelineDialog, '流水线名称', pipelineName);
    await chooseSelectOption(pipelineDialog, page, '应用名称', '订单协同平台-后端');
    await chooseSelectOption(pipelineDialog, page, '触发方式', 'MANUAL');
    await chooseSelectOption(pipelineDialog, page, '流水线类型', 'DEPLOY');
    await saveDialog(page);

    const pipelineRow = await findRowAcrossPages(page, pipelineCode);
    await expect(pipelineRow).toBeVisible();
    await pipelineRow.getByRole('button', { name: '配置', exact: true }).click();
    await expect(page.getByRole('heading', { name: pipelineName })).toBeVisible();

    await page.getByRole('button', { name: '新建阶段', exact: true }).click();
    let dialog = activeDialog(page);
    await fillTextField(dialog, '阶段名称', stageName);
    await fillNumberField(dialog, '阶段顺序', '1');
    await fillNumberField(dialog, '超时(秒)', '180');
    await saveDialog(page);

    await page.getByRole('button', { name: '新建步骤', exact: true }).click();
    dialog = activeDialog(page);
    await fillTextField(dialog, '步骤名称', stepName);
    await fillTextField(dialog, '步骤类型', 'DEPLOY');
    await chooseSelectOption(dialog, page, '执行器类型', 'RELEASE_DEPLOY');
    await fillNumberField(dialog, '步骤顺序', '1');
    await expect(dialog.getByLabel('脚本内容')).toHaveCount(0);
    await fillNumberField(dialog, '超时(秒)', '180');
    await fillNumberField(dialog, '重试次数', '0');
    await saveDialog(page);
    await expect(tableRow(page, stepName)).toBeVisible();

    const runButton = page.getByRole('button', { name: '运行流水线', exact: true });
    await expect(runButton).toBeDisabled();
    await expect(page.getByText('真实部署流水线必须从发布单触发')).toBeVisible();
  });
});

async function findRowAcrossPages(page: import('@playwright/test').Page, text: string) {
  for (let attempt = 0; attempt < 20; attempt += 1) {
    const row = tableRow(page, text);
    if ((await row.count()) > 0 && await row.isVisible()) {
      return row;
    }
    const nextButton = page.getByRole('button', { name: /next page/i });
    if ((await nextButton.count()) === 0 || await nextButton.isDisabled()) {
      break;
    }
    await nextButton.click();
    await page.waitForTimeout(300);
  }
  return tableRow(page, text);
}
