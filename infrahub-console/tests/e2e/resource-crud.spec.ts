import { expect, test } from '@playwright/test';
import { loginAsAdmin } from './helpers/auth';
import {
  chooseFirstSelectOption,
  chooseSelectOption,
  deleteRow,
  expectRowVisible,
  fillTextArea,
  fillTextField,
  openCreateDialog,
  openEditDialog,
  openPage,
  saveDialog,
} from './helpers/ui';

test.describe('resource and config CRUD', () => {
  test.setTimeout(90000);

  test.beforeEach(async ({ page }) => {
    await loginAsAdmin(page);
  });

  test('app, service and environment can be created, updated and deleted', async ({ page }) => {
    const suffix = Date.now().toString().slice(-8);
    const appCode = `e2e-app-${suffix}`;
    const appName = `E2E 应用 ${suffix}`;
    const appNameUpdated = `${appName}-已更新`;
    const serviceCode = `e2e-svc-${suffix}`;
    const serviceName = `E2E 服务 ${suffix}`;
    const envCode = `e2e-env-${suffix}`;
    const envName = `E2E 环境 ${suffix}`;

    const appDialog = await openCreateDialog(page, '应用管理');
    await fillTextField(appDialog, '应用编码', appCode);
    await fillTextField(appDialog, '应用名称', appName);
    await fillTextField(appDialog, '仓库地址', `https://example.com/${appCode}.git`);
    await fillTextField(appDialog, '默认分支', 'main');
    await saveDialog(page);
    await expectRowVisible(page, appCode);

    const appEditDialog = await openEditDialog(page, appCode);
    await fillTextField(appEditDialog, '应用名称', appNameUpdated);
    await saveDialog(page);
    await expectRowVisible(page, appNameUpdated);

    const serviceDialog = await openCreateDialog(page, '服务管理');
    await chooseSelectOption(serviceDialog, page, '应用名称', appNameUpdated);
    await fillTextField(serviceDialog, '服务编码', serviceCode);
    await fillTextField(serviceDialog, '服务名称', serviceName);
    await fillTextField(serviceDialog, '服务类型', 'WEB');
    await fillTextField(serviceDialog, '运行时', 'SPRING_BOOT');
    await fillTextField(serviceDialog, '健康检查路径', '/actuator/health');
    await saveDialog(page);
    await expectRowVisible(page, serviceCode);

    const envDialog = await openCreateDialog(page, '环境管理');
    await fillTextField(envDialog, '环境编码', envCode);
    await fillTextField(envDialog, '环境名称', envName);
    await chooseSelectOption(envDialog, page, '环境类型', 'TEST');
    await fillTextField(envDialog, '地域', 'local');
    await fillTextField(envDialog, '命名空间', `ns-${suffix}`);
    await saveDialog(page);
    await expectRowVisible(page, envCode);

    await openPage(page, '服务管理');
    await deleteRow(page, serviceCode);
    await openPageAndAssert(page, '环境管理', envName);
    await deleteRow(page, envCode);
    await openPageAndAssert(page, '应用管理', appNameUpdated);
    await deleteRow(page, appCode);
  });

  test('config group, env config and secret ref can be created and deleted', async ({ page }) => {
    const suffix = Date.now().toString().slice(-8);
    const appCode = `cfg-app-${suffix}`;
    const appName = `配置应用 ${suffix}`;
    const envCode = `cfg-env-${suffix}`;
    const envName = `配置环境 ${suffix}`;
    const groupCode = `cfg-group-${suffix}`;
    const groupName = `配置分组 ${suffix}`;
    const secretKey = `secret.key.${suffix}`;
    const configKey = `demo.config.${suffix}`;

    const appDialog = await openCreateDialog(page, '应用管理');
    await fillTextField(appDialog, '应用编码', appCode);
    await fillTextField(appDialog, '应用名称', appName);
    await saveDialog(page);
    await expectRowVisible(page, appCode);

    const envDialog = await openCreateDialog(page, '环境管理');
    await fillTextField(envDialog, '环境编码', envCode);
    await fillTextField(envDialog, '环境名称', envName);
    await chooseSelectOption(envDialog, page, '环境类型', 'TEST');
    await saveDialog(page);
    await expectRowVisible(page, envCode);

    const groupDialog = await openCreateDialog(page, '配置分组');
    await fillTextField(groupDialog, '分组编码', groupCode);
    await fillTextField(groupDialog, '分组名称', groupName);
    await chooseSelectOption(groupDialog, page, '环境名称', envName);
    await chooseSelectOption(groupDialog, page, '应用名称', appName);
    await fillTextArea(groupDialog, '描述', 'E2E 配置分组');
    await saveDialog(page);
    await expectRowVisible(page, groupCode);

    const secretDialog = await openCreateDialog(page, '密钥引用');
    await fillTextField(secretDialog, '密钥键', secretKey);
    await fillTextField(secretDialog, '密钥源', 'vault');
    await fillTextField(secretDialog, '密钥路径', `/demo/${suffix}`);
    await saveDialog(page);
    await expectRowVisible(page, secretKey);

    const configDialog = await openCreateDialog(page, '环境配置');
    await chooseSelectOption(configDialog, page, '环境名称', envName);
    await chooseSelectOption(configDialog, page, '应用名称', appName);
    await chooseSelectOption(configDialog, page, '分组名称', groupName);
    await fillTextField(configDialog, '配置键', configKey);
    await fillTextArea(configDialog, '配置值', 'enabled');
    await chooseSelectOption(configDialog, page, '值类型', 'STRING');
    await saveDialog(page);
    await expectRowVisible(page, configKey);
    await expect(page.locator('tbody tr').filter({ hasText: configKey }).first()).toContainText(envName);
    await expect(page.locator('tbody tr').filter({ hasText: configKey }).first()).toContainText(appName);
    await expect(page.locator('tbody tr').filter({ hasText: configKey }).first()).toContainText(groupName);

    await deleteRow(page, configKey);
    await openPageAndAssert(page, '密钥引用', secretKey);
    await deleteRow(page, secretKey);
    await openPageAndAssert(page, '配置分组', groupCode);
    await deleteRow(page, groupCode);
    await openPageAndAssert(page, '环境管理', envCode);
    await deleteRow(page, envCode);
    await openPageAndAssert(page, '应用管理', appCode);
    await deleteRow(page, appCode);
  });
});

async function openPageAndAssert(page: import('@playwright/test').Page, pageName: string, rowText: string) {
  const { openPage } = await import('./helpers/ui');
  await openPage(page, pageName);
  await expectRowVisible(page, rowText);
}
