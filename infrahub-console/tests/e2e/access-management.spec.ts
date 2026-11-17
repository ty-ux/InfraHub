import { expect, test } from '@playwright/test';
import { loginAsAdmin } from './helpers/auth';

test('admins grant an app-scoped role and assign it to a user', async ({ page }) => {
  const role = { id: 8, roleCode: 'PROJECT_OPERATOR', roleName: '应用操作员', status: 'ENABLED' };
  const catalog = {
    roles: [role],
    permissions: [
      { id: 21, permCode: 'PIPELINE_VIEW', permName: '查看流水线及执行记录', resourceType: 'PIPELINE', action: 'VIEW' },
      { id: 22, permCode: 'PIPELINE_EXECUTE', permName: '运行已配置的流水线', resourceType: 'PIPELINE', action: 'EXECUTE' },
    ],
    apps: [{ id: 34, appCode: 'infrahub', appName: 'InfraHub' }],
    scopedPermissions: ['PIPELINE_VIEW', 'PIPELINE_EXECUTE'],
  };
  await page.route(/\/api\/(roles|users)\?/, (route) => {
    const row = route.request().url().includes('/roles?') ? role : { id: 11, username: 'operator', displayName: '运维人员' };
    return route.fulfill({ json: { code: 'SUCCESS', data: { records: [row], total: 1 } } });
  });
  await page.route('**/api/roles/access-catalog', (route) => route.fulfill({ json: { code: 'SUCCESS', data: catalog } }));
  await page.route('**/api/roles/8/grants', (route) => {
    const data = route.request().method() === 'PUT' ? true : { appScope: 'SELECTED', permissionIds: [], appIds: [] };
    return route.fulfill({ json: { code: 'SUCCESS', data } });
  });
  await page.route('**/api/users/11/roles', (route) => route.fulfill({
    json: { code: 'SUCCESS', data: route.request().method() === 'PUT' ? true : [] },
  }));
  await loginAsAdmin(page);

  const sidebar = page.locator('aside.layout-sidebar');
  await sidebar.getByRole('link', { name: '角色管理' }).click();
  await page.getByRole('button', { name: '配置权限' }).click();
  const dialog = page.getByRole('dialog');
  await dialog.getByRole('checkbox', { name: /查看流水线及执行记录/ }).press('Space');
  await dialog.getByRole('tab', { name: '操作权限' }).click();
  await dialog.getByRole('checkbox', { name: '运行已配置的流水线' }).press('Space');
  await dialog.getByRole('tab', { name: '应用范围' }).click();
  await dialog.getByRole('combobox', { name: '选择允许访问的应用' }).press('Enter');
  await page.getByRole('option', { name: 'InfraHub (infrahub)' }).click();
  await dialog.getByRole('combobox', { name: '选择允许访问的应用' }).press('Escape');
  const saved = page.waitForRequest((request) => request.url().endsWith('/api/roles/8/grants') && request.method() === 'PUT');
  await dialog.getByRole('button', { name: '保存' }).click();
  expect((await saved).postDataJSON()).toEqual({ appScope: 'SELECTED', permissionIds: [21, 22], appIds: [34] });
  await expect(dialog).toBeHidden();

  await sidebar.getByRole('link', { name: '用户管理' }).click();
  await page.getByRole('button', { name: '分配角色' }).click();
  await dialog.getByRole('checkbox', { name: /应用操作员/ }).press('Space');
  const assigned = page.waitForRequest((request) => request.url().endsWith('/api/users/11/roles') && request.method() === 'PUT');
  await dialog.getByRole('button', { name: '保存' }).click();
  expect((await assigned).postDataJSON()).toEqual({ roleIds: [8] });
});

test('pipeline actions use permissions for the row application', async ({ page }) => {
  await loginAsAdmin(page);
  await page.route('**/api/auth/me', (route) => route.fulfill({
    json: { code: 'SUCCESS', data: {
      userId: 11, username: 'operator', authorities: ['PIPELINE_VIEW', 'PIPELINE_EXECUTE'],
      allAppPermissions: [], applicationPermissions: { PIPELINE_VIEW: [35], PIPELINE_EXECUTE: [34] },
    } },
  }));
  await page.route(/\/api\/(pipelines|apps)\?/, (route) => {
    const records = route.request().url().includes('/pipelines?') ? [
      { id: 1, appId: 34, pipelineName: '可运行流水线', enabled: true },
      { id: 2, appId: 35, pipelineName: '只读流水线', enabled: true },
    ] : [];
    return route.fulfill({ json: { code: 'SUCCESS', data: { records, total: records.length } } });
  });
  await page.goto('/pipeline/defs');
  await expect(page.getByRole('row', { name: /可运行流水线/ }).getByRole('button', { name: '运行流水线' })).toBeVisible();
  const readOnly = page.getByRole('row', { name: /只读流水线/ });
  await expect(readOnly.getByRole('button', { name: '查看' })).toBeVisible();
  await expect(readOnly.getByRole('button', { name: '运行流水线' })).toHaveCount(0);
  await expect(page.getByRole('button', { name: '新建', exact: true })).toHaveCount(0);
});
