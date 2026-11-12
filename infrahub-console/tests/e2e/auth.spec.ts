import { expect, test } from '@playwright/test';
import { loginAsAdmin } from './helpers/auth';

test('admin can login and logout', async ({ page }) => {
  await loginAsAdmin(page);

  await expect(page.getByText('欢迎回来，平台管理员')).toBeVisible();
  await page.getByRole('button', { name: '退出登录' }).click();

  await expect(page).toHaveURL(/\/login/);
  await expect(page.getByRole('heading', { name: '登录' })).toBeVisible();
});
