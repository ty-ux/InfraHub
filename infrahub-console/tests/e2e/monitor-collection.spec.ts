import { test, expect } from '@playwright/test';
import { loginAsAdmin } from './helpers/auth';

test.describe('monitor collection', () => {
  test.beforeEach(async ({ page }) => {
    await loginAsAdmin(page);
  });

  test('monitor targets page has collect snapshot button', async ({ page }) => {
    await page.goto('/monitor/targets');
    await expect(page.locator('.page-title')).toContainText('监控目标');

    // Check if table has action column with collect button
    const actionColumn = page.locator('table').locator('th').filter({ hasText: '操作' });
    await expect(actionColumn).toBeVisible();

    // If there are rows, check for collect button presence
    const rows = page.locator('table tbody tr');
    const rowCount = await rows.count();

    if (rowCount > 0) {
      // Check first row has both view config and collect buttons
      const firstRowActions = rows.first().locator('td:last-child');
      await expect(firstRowActions.locator('button').filter({ hasText: '查看配置' })).toBeVisible();
      await expect(firstRowActions.locator('button').filter({ hasText: '采集快照' })).toBeVisible();
    }
  });

  test('collect snapshot button shows success or failure feedback', async ({ page }) => {
    await page.goto('/monitor/targets');

    const rows = page.locator('table tbody tr');
    const rowCount = await rows.count();

    if (rowCount > 0) {
      // Click collect button on first row
      const collectButton = rows.first().locator('button').filter({ hasText: '采集快照' });

      // Listen for response (success message or warning)
      const responsePromise = page.waitForResponse(resp =>
        resp.url().includes('/api/monitor/targets/') && resp.url().includes('/collect')
      );

      await collectButton.click();
      const response = await responsePromise;

      // Check response status
      expect(response.status()).toBe(200);

      const body = await response.json();
      const data = body.data;

      // Expect either success or failure message
      expect(data).toHaveProperty('success');
      expect(data).toHaveProperty('message');

      if (data.success) {
        // Success message should appear
        await expect(page.locator('.el-message--success')).toBeVisible();
      } else {
        // Warning message should appear
        await expect(page.locator('.el-message--warning')).toBeVisible();
      }
    }
  });

  test('run-once collection button triggers batch collection', async ({ page }) => {
    await page.goto('/monitor/targets');
    await expect(page.locator('.page-title')).toContainText('监控目标');

    // Check run-once button exists in header
    const runOnceButton = page.locator('.header-actions').locator('button').filter({ hasText: '运行一次采集' });
    await expect(runOnceButton).toBeVisible();

    // Listen for response
    const responsePromise = page.waitForResponse(resp =>
      resp.url().includes('/api/monitor/collections/run-once')
    );

    await runOnceButton.click();
    const response = await responsePromise;

    // run-once endpoint always returns 200 with summary structure
    // Single target failure does NOT interrupt batch - failures are recorded in results
    expect(response.status()).toBe(200);

    const body = await response.json();
    const data = body.data;

    // Always assert summary structure
    expect(data).toHaveProperty('totalCount');
    expect(data).toHaveProperty('successCount');
    expect(data).toHaveProperty('failureCount');
    expect(data).toHaveProperty('results');
    expect(typeof data.totalCount).toBe('number');
    expect(typeof data.successCount).toBe('number');
    expect(typeof data.failureCount).toBe('number');
    expect(Array.isArray(data.results)).toBe(true);

    // Success message should appear with summary
    await expect(page.locator('.el-message--success')).toBeVisible();
  });

  test('schedule collection status card is visible', async ({ page }) => {
    await page.goto('/monitor/targets');

    // Check schedule status card exists (header contains "定时采集状态")
    const statusCard = page.locator('.schedule-status-card');
    await expect(statusCard).toBeVisible();

    // Check refresh button in status card
    const refreshButton = statusCard.locator('button').filter({ hasText: '刷新' });
    await expect(refreshButton).toBeVisible();

    // Check status content or empty state is displayed
    const statusContent = statusCard.locator('.status-content, .status-empty');
    await expect(statusContent).toBeVisible();
  });
});
