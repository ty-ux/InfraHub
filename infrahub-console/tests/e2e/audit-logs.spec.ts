import { expect, test } from '@playwright/test';
import { loginAsAdmin } from './helpers/auth';

test.beforeEach(async ({ page }) => {
  await loginAsAdmin(page);
});

test.describe('audit logs UI', () => {
  test('audit logs page loads correctly', async ({ page }) => {
    await page.goto('/system/audit-logs');
    await expect(page.getByRole('heading', { name: '审计日志' })).toBeVisible();
    await expect(page.locator('.filter-bar')).toBeVisible();
    await expect(page.getByRole('button', { name: '刷新' })).toBeVisible();
    await expect(page.getByRole('button', { name: '搜索' })).toBeVisible();
  });

  test('audit logs table displays pagination', async ({ page }) => {
    await page.goto('/system/audit-logs');
    await expect(page.locator('.table-card')).toBeVisible();
    await expect(page.locator('.el-pagination')).toBeVisible();
  });

  test('audit logs has date picker for time range', async ({ page }) => {
    await page.goto('/system/audit-logs');
    await expect(page.locator('.el-date-editor')).toBeVisible();
  });

  test('audit logs filter bar has multiple select dropdowns', async ({ page }) => {
    await page.goto('/system/audit-logs');
    const filterBar = page.locator('.filter-bar');
    await expect(filterBar).toBeVisible();

    // Verify multiple select dropdowns for filtering
    const selects = filterBar.locator('.el-select');
    const selectCount = await selects.count();
    expect(selectCount).toBeGreaterThanOrEqual(3);
  });

  test('audit logs table shows column headers', async ({ page }) => {
    await page.goto('/system/audit-logs');
    const table = page.locator('.el-table');
    await expect(table).toBeVisible();

    // Check for expected column headers
    const headers = table.locator('th');
    const headerCount = await headers.count();
    expect(headerCount).toBeGreaterThanOrEqual(5);
  });

  test('audit logs has input field for bizId', async ({ page }) => {
    await page.goto('/system/audit-logs');
    const filterBar = page.locator('.filter-bar');

    // Verify input field for bizId exists
    const input = filterBar.locator('input').filter({ hasNot: page.locator('.el-select input') }).first();
    await expect(input).toBeVisible();
  });

  test('audit logs page navigation works', async ({ page }) => {
    // Navigate from sidebar
    await page.locator('aside.layout-sidebar').getByRole('link', { name: '审计日志' }).click();
    await expect(page.getByRole('heading', { name: '审计日志' })).toBeVisible();

    // Verify URL
    expect(page.url()).toContain('/system/audit-logs');
  });
});