import { expect, test, type Locator, type Page } from '@playwright/test';
import { loginAsAdmin } from './helpers/auth';
import { mockResourceApis, mockReleaseAndRollbackApis } from './helpers/mocks';
import { openPage, sidebarLink } from './helpers/ui';

/**
 * 当前版本列表的次要入口收在行内「更多」里：这里走真实展开——
 * 先确认真实触发器可点击、菜单确实打开（aria-expanded），再确认页面级菜单项可见，最后才选择。
 */
async function openRowMoreMenu(page: Page, row: Locator): Promise<Locator> {
  const trigger = row.getByRole('button', { name: '更多', exact: true });
  await expect(trigger).toBeVisible();
  await expect(trigger).toHaveAttribute('aria-expanded', 'false');
  await trigger.click();
  await expect(trigger).toHaveAttribute('aria-expanded', 'true');
  const menu = page.locator('.el-dropdown__popper:visible').getByRole('menu');
  await expect(menu).toBeVisible();
  return menu;
}

async function chooseRowMenuItem(page: Page, row: Locator, label: string) {
  const menu = await openRowMoreMenu(page, row);
  const item = menu.getByRole('menuitem', { name: label, exact: true });
  // 菜单项必须先可见再点击：隐藏节点不算可达入口
  await expect(item).toBeVisible();
  await item.click();
}

// V-D: Current versions → release orders cross-navigation
test.describe('V-D current versions cross-navigation (Contract 7, 8)', () => {
  test.setTimeout(90000);

  test.beforeEach(async ({ page }) => {
    await loginAsAdmin(page);
    await mockResourceApis(page);
    await mockReleaseAndRollbackApis(page, [], []);
  });

  // V-D-01: "查看发布单" button navigates to /release/orders with appId/envId
  test('current versions "查看发布单" navigates to release orders with query params', async ({ page }) => {
    await openPage(page, '当前版本');

    // Wait for table to load
    await expect(page.locator('tbody tr').first()).toBeVisible({ timeout: 15000 });

    const firstRow = page.locator('tbody tr').first();
    await expect(firstRow).toBeVisible();

    // 行内「更多」展开后选择页面级菜单项「查看发布单」
    await chooseRowMenuItem(page, firstRow, '查看发布单');

    await expect(page).toHaveURL(/\/release\/orders/, { timeout: 10000 });
    await page.waitForTimeout(1000);

    // Verify URL navigated to release orders
    const url = page.url();
    expect(url).toContain('/release/orders');
    // Mock data has appId=2 for first record
    expect(url).toMatch(/appId=\d+/);
  });

  // V-D-02: "查看运行实例" button navigates to /resource/runtime-instances with serviceId/envId
  test('current versions "查看运行实例" navigates to runtime instances with query params', async ({ page }) => {
    await openPage(page, '当前版本');

    await expect(page.locator('tbody tr').first()).toBeVisible({ timeout: 15000 });

    const firstRow = page.locator('tbody tr').first();
    // 行内「更多」展开后选择页面级菜单项「查看运行实例」
    await chooseRowMenuItem(page, firstRow, '查看运行实例');

    await expect(page).toHaveURL(/\/resource\/runtime-instances/, { timeout: 10000 });
    await page.waitForTimeout(1000);

    const url = page.url();
    expect(url).toContain('/resource/runtime-instances');
    expect(url).toMatch(/serviceId=\d+/);
    expect(url).toMatch(/envId=\d+/);
  });

  // V-D-03: "最近部署记录" button still works (regression)
  test('current versions "最近部署记录" opens deploy records dialog', async ({ page }) => {
    await openPage(page, '当前版本');

    await expect(page.locator('tbody tr').first()).toBeVisible({ timeout: 15000 });

    const firstRow = page.locator('tbody tr').first();
    // 行内「更多」展开后选择页面级菜单项「最近部署记录」
    await chooseRowMenuItem(page, firstRow, '最近部署记录');

    const dialog = page.getByRole('dialog').filter({ hasText: '部署记录' });
    await expect(dialog).toBeVisible({ timeout: 5000 });
  });
});

// V-D: Runtime instances → current versions cross-navigation
test.describe('V-D runtime instances cross-navigation (Contract 7, 8)', () => {
  test.setTimeout(90000);

  test.beforeEach(async ({ page }) => {
    await loginAsAdmin(page);
    await mockResourceApis(page);
    await mockReleaseAndRollbackApis(page, [], []);

    // Mock runtime instances page endpoint (distinct from list endpoint)
    await page.route('**/api/runtime/service-instances/page**', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          code: 'SUCCESS',
          data: {
            records: [
              { id: 9001, serviceId: 102, envId: 20, hostId: 30, instanceName: 'infrahub-web-1', instanceType: 'PROCESS', instanceStatus: 'RUNNING', healthStatus: 'UP', createdAt: '2026-05-01T00:00:00', updatedAt: '2026-05-20T10:00:00' },
            ],
            total: 1,
          },
        }),
      });
    });

    // Mock hosts endpoint
    await page.route('**/api/assets/hosts**', async (route) => {
      const url = new URL(route.request().url());
      if (url.pathname.match(/\/\d+\/resource-view/)) {
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({ code: 'SUCCESS', data: { resourcePoolName: 'default-pool', currentVersionNo: 'v1.0.0', latestDeployStatus: 'SUCCESS', latestDeployAt: '2026-05-20T10:00:00', monitorStatus: 'UP' } }),
        });
        return;
      }
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ code: 'SUCCESS', data: { records: [{ id: 30, hostname: 'web-host-1', ip: '192.0.2.12' }], total: 1 } }),
      });
    });

    // Mock resource-view endpoint
    await page.route('**/api/runtime/service-instances/*/resource-view**', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ code: 'SUCCESS', data: { resourcePoolName: 'default-pool', currentVersionNo: 'v1.0.0', latestDeployStatus: 'SUCCESS', latestDeployAt: '2026-05-20T10:00:00', monitorStatus: 'UP' } }),
      });
    });
  });

  // V-D-04: Runtime instances "当前版本" navigates to /release/current-versions
  test('runtime instances "当前版本" button navigates to current versions', async ({ page }) => {
    await sidebarLink(page, '运行实例').click();
    await expect(page.getByRole('heading', { name: '运行实例' })).toBeVisible();

    await expect(page.locator('tbody tr').first()).toBeVisible({ timeout: 15000 });

    const firstRow = page.locator('tbody tr').first();
    const currentVersionBtn = firstRow.getByRole('button', { name: '当前版本', exact: true });
    await expect(currentVersionBtn).toBeVisible();
    await currentVersionBtn.click();

    await page.waitForTimeout(1000);

    const url = page.url();
    expect(url).toContain('/release/current-versions');
  });
});
