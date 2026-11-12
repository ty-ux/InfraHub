import { expect, test } from '@playwright/test';
import { loginAsAdmin } from './helpers/auth';

function sidebarLink(page: import('@playwright/test').Page, name: string) {
  return page.locator('aside.layout-sidebar').getByRole('link', { name, exact: true });
}

test.beforeEach(async ({ page }) => {
  await loginAsAdmin(page);
});

test('release and rollback create dialogs expose business-friendly fields', async ({ page }) => {
  await sidebarLink(page, '发布单').click();
  await page.getByRole('button', { name: '新建' }).click();
  const releaseDialog = page.getByRole('dialog');
  await expect(releaseDialog).toContainText('新建 发布单');
  await expect(releaseDialog.getByText('应用名称', { exact: true })).toBeVisible();
  await expect(releaseDialog.getByText('服务名称', { exact: true })).toBeVisible();
  await expect(releaseDialog.getByText('环境名称', { exact: true })).toBeVisible();
  await expect(releaseDialog.getByText('版本', { exact: true })).toBeVisible();
  await releaseDialog.getByRole('button', { name: '取消', exact: true }).click();

  await sidebarLink(page, '回滚单').click();
  await page.getByRole('button', { name: '新建' }).click();
  const rollbackDialog = page.getByRole('dialog');
  await expect(rollbackDialog).toContainText('新建 回滚单');
  await expect(rollbackDialog.getByText('发布单号', { exact: true })).toBeVisible();
  await expect(rollbackDialog.getByText('原版本', { exact: true })).toBeVisible();
  await expect(rollbackDialog.getByText('目标版本', { exact: true })).toBeVisible();
  await rollbackDialog.getByRole('button', { name: '取消', exact: true }).click();
});

test('env config helper dialogs can be opened', async ({ page }) => {
  await sidebarLink(page, '环境配置').click();

  await page.getByRole('button', { name: '批量导入' }).click();
  await expect(page.getByRole('dialog')).toContainText('批量导入');
  await page.getByRole('button', { name: '取消' }).click();

  await page.getByRole('button', { name: '从模板生成' }).click();
  await expect(page.getByRole('dialog')).toContainText('模板');
  await page.getByRole('button', { name: '取消' }).click();
});
