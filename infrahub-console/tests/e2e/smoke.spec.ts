import { expect, test, type Page } from '@playwright/test';
import { loginAsAdmin } from './helpers/auth';

/**
 * W-D: Lightweight smoke E2E covering core navigation and page load.
 * Does NOT trigger any real release/rollback/execute/precheck operations.
 * Covers Contract Section 9 pages and Section 11.5 timeout constraints.
 */

function sidebarLink(page: Page, name: string) {
  return page.locator('aside.layout-sidebar').getByRole('link', { name, exact: true });
}

/** Navigate via sidebar and verify the page heading is visible. */
async function smokeNavigate(page: Page, sidebarName: string, headingName?: string) {
  await sidebarLink(page, sidebarName).click();
  await expect(page.getByRole('heading', { name: headingName ?? sidebarName, exact: true })).toBeVisible({ timeout: 10000 });
}

/** Assert no global error overlay or console error banner is visible. */
async function assertNoErrorState(page: Page) {
  // Element-Plus error overlays
  const errorOverlay = page.locator('.el-overlay .el-message-box__content').filter({ hasText: /错误|失败|异常|error/i });
  await expect(errorOverlay).toHaveCount(0);
}

test.describe('smoke – core page load', () => {
  test.setTimeout(90000);

  test.beforeEach(async ({ page }) => {
    await loginAsAdmin(page);
  });

  test('workspace loads after login', async ({ page }) => {
    await expect(page).toHaveURL(/\/workspace/);
    await expect(page.getByText('InfraHub 控制台')).toBeVisible();
    await assertNoErrorState(page);
  });

  test('release orders page loads', async ({ page }) => {
    await smokeNavigate(page, '发布单');
    await assertNoErrorState(page);
    // Verify table structure is present (either rows or empty state)
    const tableOrEmpty = page.locator('.el-table, .el-table__empty-text');
    await expect(tableOrEmpty.first()).toBeVisible({ timeout: 10000 });
  });

  test('rollback orders page loads', async ({ page }) => {
    await smokeNavigate(page, '回滚单');
    await assertNoErrorState(page);
    const tableOrEmpty = page.locator('.el-table, .el-table__empty-text');
    await expect(tableOrEmpty.first()).toBeVisible({ timeout: 10000 });
  });

  test('current versions page loads', async ({ page }) => {
    await smokeNavigate(page, '当前版本');
    await assertNoErrorState(page);
    const tableOrEmpty = page.locator('.el-table, .el-table__empty-text');
    await expect(tableOrEmpty.first()).toBeVisible({ timeout: 10000 });
  });

  test('runtime instances page loads', async ({ page }) => {
    await smokeNavigate(page, '运行实例');
    await assertNoErrorState(page);
    const tableOrEmpty = page.locator('.el-table, .el-table__empty-text');
    await expect(tableOrEmpty.first()).toBeVisible({ timeout: 10000 });
  });

  test('pipeline defs page loads', async ({ page }) => {
    await smokeNavigate(page, '流水线定义');
    await assertNoErrorState(page);
    const tableOrEmpty = page.locator('.el-table, .el-table__empty-text');
    await expect(tableOrEmpty.first()).toBeVisible({ timeout: 10000 });
  });
});

test.describe('smoke – no real operations triggered', () => {
  test.setTimeout(90000);

  test('navigation does not trigger release execute endpoints', async ({ page }) => {
    const triggered: string[] = [];

    await page.route('**/api/**', async (route) => {
      const url = route.request().url();
      const method = route.request().method();
      // Capture any POST/PUT/DELETE that looks like an execute/precheck trigger
      if (
        method !== 'GET' &&
        (url.includes('/execute') || url.includes('/precheck') || url.includes('/rollback') && method === 'POST')
      ) {
        triggered.push(`${method} ${url}`);
      }
      await route.continue();
    });

    await loginAsAdmin(page);

    // Navigate through key pages
    await smokeNavigate(page, '发布单');
    await smokeNavigate(page, '回滚单');
    await smokeNavigate(page, '当前版本');
    await smokeNavigate(page, '运行实例');

    expect(triggered).toEqual([]);
  });
});
