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
  openPage,
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

  test('creates a Compose pipeline from form values and selected checks', async ({ page }) => {
    const success = (data: unknown) => ({ json: { code: 'SUCCESS', data } });
    await page.route('**/api/rollbacks/application/options', (route) => route.fulfill(success({
      apps: [{ id: 1, name: '示例应用' }], envs: [{ id: 20, name: '测试环境' }],
      services: [{ id: 30, name: '前后端服务', appId: 1 }], versions: [],
    })));
    await page.route('**/api/rollbacks/application/instances?*', (route) => route.fulfill(success([
      { id: 40, hostId: 50, instanceName: 'example-compose', instanceType: 'DOCKER_COMPOSE' },
    ])));
    await page.route('**/api/secret-refs?*', (route) => route.fulfill(success({ records: [], total: 0 })));
    await page.route('**/api/pipelines/templates/compose/build-contexts', (route) => route.fulfill(success(['desktop-linux'])));
    await page.route('**/api/pipelines/templates/compose', (route) => route.fulfill(success({ pipelineId: 77 })));
    await page.route('**/api/pipelines/77', (route) => route.fulfill(success({
      id: 77, appId: 1, pipelineCode: 'example-compose', pipelineName: '示例联合交付', enabled: true,
    })));

    await openPage(page, '流水线定义');
    await page.getByRole('button', { name: '从模板新建', exact: true }).click();
    const dialog = page.getByRole('dialog', { name: 'Docker Compose 联合交付' });
    await fillTextField(dialog, '流水线名称', '示例联合交付');
    await fillTextField(dialog, '流水线编码', 'example-compose');
    await chooseSelectOption(dialog, page, '环境名称', '测试环境');
    await chooseSelectOption(dialog, page, '服务名称', '前后端服务');
    await expect(dialog.getByText('example-compose', { exact: false })).toBeVisible();
    await fillTextField(dialog, 'Git 仓库地址', 'https://git.example.com/project.git');
    await fillTextField(dialog, '镜像仓库地址', 'registry.example.com');
    const images = dialog.locator('.image-fields');
    for (const [index, service] of ['backend', 'frontend'].entries()) {
      await fillTextField(images.nth(index), '镜像名称', `team/project-${service}`);
      await fillTextField(images.nth(index), 'Dockerfile', `${service}/Dockerfile`);
    }
    await dialog.getByText('部署后执行 HTTP 健康检查', { exact: true }).click();
    await fillTextField(dialog, '从目标服务器访问的检查地址', 'http://127.0.0.1:8080/health');
    await expect(dialog.getByRole('complementary', { name: '阶段预览' })).toContainText('部署与健康检查');
    const request = page.waitForRequest((candidate) => candidate.method() === 'POST'
      && candidate.url().endsWith('/api/pipelines/templates/compose'));
    await dialog.getByRole('button', { name: '创建流水线', exact: true }).click();
    expect((await request).postDataJSON()).toMatchObject({
      appId: 1, envId: 20, serviceId: 30, pipelineCode: 'example-compose',
      deployRoot: '/opt/infrahub/example-compose', preflight: true, healthUrl: 'http://127.0.0.1:8080/health',
      backend: { dockerfile: 'backend/Dockerfile' }, frontend: { dockerfile: 'frontend/Dockerfile' },
    });
    await expect(page).toHaveURL(/\/pipeline\/defs\/77$/);
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
