import { expect, test } from '@playwright/test';
import { loginAsAdmin } from './helpers/auth';

test.describe('E3.3 collection retry', () => {
  test.beforeEach(async ({ page }) => {
    await loginAsAdmin(page);
  });

  test('run-once collection response carries retry-success marker', async ({ page }) => {
    await page.route('**/api/monitor/collections/schedule/status', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          code: 'SUCCESS',
          message: 'success',
          data: {
            enabled: true,
            fixedDelayMs: 60000,
            lastRunAt: null,
            lastTotalCount: 0,
            lastSuccessCount: 0,
            lastFailureCount: 0,
            lastMessage: 'ok',
          },
        }),
      });
    });

    await page.route('**/api/monitor/targets**', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          code: 'SUCCESS',
          message: 'success',
          data: {
            pageNum: 1,
            pageSize: 10,
            total: 1,
            records: [
              {
                id: 1,
                targetType: 'HOST',
                targetId: 100,
                monitorKey: 'host.cpu',
                scrapeConfig: '{}',
                status: 'ENABLED',
                createdAt: '2026-05-09T12:00:00',
              },
            ],
          },
        }),
      });
    });

    await page.route('**/api/monitor/collections/run-once', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          code: 'SUCCESS',
          message: 'success',
          data: {
            totalCount: 1,
            successCount: 1,
            failureCount: 0,
            results: [
              {
                monitorTargetId: 1,
                targetType: 'HOST',
                targetId: 100,
                success: true,
                snapshotId: 9527,
                message: '采集成功 (重试1次成功)',
              },
            ],
          },
        }),
      });
    });

    await page.goto('/monitor/targets');
    const runOnceResponse = page.waitForResponse((resp) =>
      resp.request().method() === 'POST' && resp.url().includes('/api/monitor/collections/run-once'),
    );
    await page.getByRole('button', { name: '运行一次采集', exact: true }).click();

    const response = await runOnceResponse;
    expect(response.ok()).toBeTruthy();
    const body = await response.json();
    expect(body.data.results[0].message).toContain('重试1次成功');
    await expect(page.locator('.el-message--success')).toBeVisible();
  });

  test('run-once collection response carries retry-failed marker', async ({ page }) => {
    await page.route('**/api/monitor/collections/schedule/status', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          code: 'SUCCESS',
          message: 'success',
          data: {
            enabled: true,
            fixedDelayMs: 60000,
            lastRunAt: null,
            lastTotalCount: 0,
            lastSuccessCount: 0,
            lastFailureCount: 0,
            lastMessage: 'ok',
          },
        }),
      });
    });

    await page.route('**/api/monitor/targets**', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          code: 'SUCCESS',
          message: 'success',
          data: {
            pageNum: 1,
            pageSize: 10,
            total: 1,
            records: [
              {
                id: 2,
                targetType: 'HOST',
                targetId: 101,
                monitorKey: 'host.mem',
                scrapeConfig: '{}',
                status: 'ENABLED',
                createdAt: '2026-05-09T12:00:00',
              },
            ],
          },
        }),
      });
    });

    await page.route('**/api/monitor/collections/run-once', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          code: 'SUCCESS',
          message: 'success',
          data: {
            totalCount: 1,
            successCount: 0,
            failureCount: 1,
            results: [
              {
                monitorTargetId: 2,
                targetType: 'HOST',
                targetId: 101,
                success: false,
                snapshotId: null,
                message: 'HTTP 请求失败 (重试2次均失败)',
              },
            ],
          },
        }),
      });
    });

    await page.goto('/monitor/targets');
    const runOnceResponse = page.waitForResponse((resp) =>
      resp.request().method() === 'POST' && resp.url().includes('/api/monitor/collections/run-once'),
    );
    await page.getByRole('button', { name: '运行一次采集', exact: true }).click();

    const response = await runOnceResponse;
    expect(response.ok()).toBeTruthy();
    const body = await response.json();
    expect(body.data.results[0].message).toContain('重试2次均失败');
    await expect(page.locator('.el-message--success')).toBeVisible();
  });
});
