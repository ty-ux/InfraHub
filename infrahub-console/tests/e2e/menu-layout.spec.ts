import { expect, test } from '@playwright/test';
import type { MenuLayoutState } from '../../src/api/menuLayout';
import { loginAsAdmin } from './helpers/auth';

test('menu settings apply only after saving, persist on reload, and can be restored', async ({ page }) => {
  let state: MenuLayoutState = { revision: 0, layout: { groupOrder: [], entries: [] } };
  await page.route('**/api/system/menu-layout', async (route) => {
    if (route.request().method() === 'PUT') {
      const incoming = route.request().postDataJSON() as MenuLayoutState;
      expect(incoming.revision).toBe(state.revision);
      state = { ...incoming, revision: state.revision + 1 };
    }
    await route.fulfill({ json: { code: 'SUCCESS', data: state } });
  });
  await loginAsAdmin(page);

  const sidebar = page.locator('aside.layout-sidebar');
  const editor = page.getByRole('main');
  const delivery = sidebar.locator('.group').filter({ has: page.locator('.group-title', { hasText: '发布与回滚' }) });
  const save = editor.getByRole('button', { name: '保存', exact: true });
  await sidebar.getByRole('link', { name: '菜单管理' }).click();
  await editor.getByRole('button', { name: '发布与回滚', exact: true }).click();
  await editor.getByRole('button', { name: '上移分组' }).click();
  await editor.getByRole('button', { name: '下移流水线定义' }).click();
  await editor.getByRole('switch', { name: '在侧栏显示版本管理' }).press('Space');
  await editor.getByRole('combobox', { name: '制品管理的分组' }).press('Enter');
  await page.getByRole('option', { name: '部署配置', exact: true }).click();

  await expect(delivery.getByRole('link').first()).toHaveText('流水线定义');
  await expect(delivery.getByRole('link', { name: '版本管理' })).toBeVisible();
  await save.click();
  await expect(delivery.getByRole('link')).toHaveText(['执行记录', '流水线定义', '当前版本', '应用回滚']);
  await expect(sidebar.locator('.group-title').nth(2)).toHaveText('发布与回滚');
  const deployment = sidebar.locator('.group').filter({ has: page.locator('.group-title', { hasText: '部署配置' }) });
  await expect(deployment.getByRole('link').last()).toHaveText('制品管理');

  await page.reload();
  await expect(delivery.getByRole('link').first()).toHaveText('执行记录');
  await expect(sidebar.getByRole('link', { name: '版本管理' })).toHaveCount(0);
  await editor.getByRole('button', { name: '恢复默认布局' }).click();
  await save.click();
  await expect(delivery.getByRole('link').first()).toHaveText('流水线定义');
  await expect(delivery.getByRole('link', { name: '版本管理' })).toBeVisible();
  await expect(delivery.getByRole('link', { name: '制品管理' })).toBeVisible();
  expect(state.layout).toEqual({ groupOrder: [], entries: [] });
});

test('stale layouts preserve the draft and require a refresh', async ({ page }) => {
  await page.route('**/api/system/menu-layout', (route) => route.request().method() === 'PUT'
    ? route.fulfill({ status: 409, json: { code: 'MENU_LAYOUT_CONFLICT' } })
    : route.fulfill({ json: { code: 'SUCCESS', data: { revision: 0, layout: { groupOrder: [], entries: [] } } } }));
  await loginAsAdmin(page);
  const sidebar = page.locator('aside.layout-sidebar');
  const editor = page.getByRole('main');
  await sidebar.getByRole('link', { name: '菜单管理' }).click();
  await editor.getByRole('switch', { name: '在侧栏显示工作台' }).press('Space');
  const save = editor.getByRole('button', { name: '保存', exact: true });
  await save.click();
  await expect(editor.getByText('菜单已被其他管理员更新，请刷新后再编辑。')).toBeVisible();
  await expect(save).toBeDisabled();
  await expect(editor.getByRole('switch', { name: '在侧栏显示工作台' })).toHaveAttribute('aria-checked', 'false');
  await expect(sidebar.getByRole('link', { name: '工作台', exact: true })).toBeVisible();
});

test('saved visibility does not expose admin routes to other users', async ({ page }) => {
  await page.route('**/api/system/menu-layout', (route) => route.fulfill({
    json: {
      code: 'SUCCESS',
      data: {
        revision: 1,
        layout: {
          groupOrder: ['delivery'],
          entries: [
            { path: '/system/menu-layout', group: 'delivery', visible: true },
            { path: '/release/artifacts', group: 'delivery', visible: true },
          ],
        },
      },
    },
  }));
  await loginAsAdmin(page);
  const sidebar = page.locator('aside.layout-sidebar');
  await expect(sidebar.locator('.group-title').first()).toHaveText('发布与回滚');
  const system = sidebar.locator('.group').filter({ has: page.locator('.group-title', { hasText: '系统管理' }) });
  await expect(system.getByRole('link', { name: '菜单管理' })).toBeVisible();

  await page.route('**/api/auth/me', (route) => route.fulfill({
    json: { code: 'SUCCESS', data: { userId: 2, username: 'operator', authorities: ['RELEASE_VIEW'] } },
  }));
  await page.goto('/system/menu-layout');
  await expect(page).toHaveURL(/\/workspace$/);
  await expect(sidebar.getByRole('link', { name: '版本管理' })).toBeVisible();
  await expect(sidebar.getByRole('link', { name: '制品管理' })).toHaveCount(0);
  await expect(sidebar.getByRole('link', { name: '菜单管理' })).toHaveCount(0);
});
