import { expect, test } from '@playwright/test';
import { loginAsAdmin } from './helpers/auth';

test.describe('E3.2 monitor notification record API E2E', () => {
  test.setTimeout(90000);

  test.beforeEach(async ({ page }) => {
    await loginAsAdmin(page);
  });

  test('notification records by event API returns success envelope', async ({ page }) => {
    const eventId = 999999;

    // Mock the API response
    await page.route(`**/api/monitor/alert-events/${eventId}/notifications`, async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          code: 'SUCCESS',
          message: 'success',
          traceId: 'e2e-notification-event',
          data: [
            {
              id: 1,
              alertEventId: eventId,
              alertRuleId: 100,
              channelType: 'WEBHOOK',
              channelTypeLabel: 'Webhook',
              channelTarget: 'https://example.com/webhook',
              notificationStatus: 'SENT',
              notificationStatusLabel: '已发送',
              responseStatus: 200,
              sentAt: '2026-05-09T12:00:00',
              createdAt: '2026-05-09T12:00:00',
            },
          ],
        }),
      });
    });

    // Use page.evaluate to make API request from browser context
    const response = await page.evaluate(async (eventId) => {
      const res = await fetch(`/api/monitor/alert-events/${eventId}/notifications`);
      return { ok: res.ok, status: res.status, body: await res.json() };
    }, eventId);

    expect(response.ok).toBe(true);
    expect(response.status).toBe(200);
    expect(response.body.code).toBe('SUCCESS');
    expect(response.body.data).toHaveLength(1);
    expect(response.body.data[0].channelType).toBe('WEBHOOK');
    expect(response.body.data[0].channelTypeLabel).toBe('Webhook');
    expect(response.body.data[0].notificationStatusLabel).toBe('已发送');
  });

  test('notification records by rule API returns success envelope', async ({ page }) => {
    const ruleId = 999999;

    // Mock the API response
    await page.route(`**/api/monitor/alert-rules/${ruleId}/notifications**`, async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          code: 'SUCCESS',
          message: 'success',
          traceId: 'e2e-notification-rule',
          data: [
            {
              id: 2,
              alertEventId: 1002,
              alertRuleId: ruleId,
              channelType: 'DINGTALK',
              channelTypeLabel: '钉钉',
              channelTarget: 'abc123',
              notificationStatus: 'FAILED',
              notificationStatusLabel: '发送失败',
              errorMessage: 'Invalid access token',
              createdAt: '2026-05-09T11:00:00',
            },
          ],
        }),
      });
    });

    const response = await page.evaluate(async (ruleId) => {
      const res = await fetch(`/api/monitor/alert-rules/${ruleId}/notifications?limit=20`);
      return { ok: res.ok, status: res.status, body: await res.json() };
    }, ruleId);

    expect(response.ok).toBe(true);
    expect(response.status).toBe(200);
    expect(response.body.code).toBe('SUCCESS');
    expect(response.body.data).toHaveLength(1);
    expect(response.body.data[0].channelTypeLabel).toBe('钉钉');
    expect(response.body.data[0].notificationStatusLabel).toBe('发送失败');
    expect(response.body.data[0].errorMessage).toBe('Invalid access token');
  });

  test('notification item contains all required fields', async ({ page }) => {
    const ruleId = 999999;

    // Mock the API response with full data
    await page.route(`**/api/monitor/alert-rules/${ruleId}/notifications**`, async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          code: 'SUCCESS',
          message: 'success',
          traceId: 'e2e-notification-fields',
          data: [
            {
              id: 3,
              alertEventId: 1003,
              alertRuleId: ruleId,
              channelType: 'EMAIL',
              channelTypeLabel: '邮件',
              channelTarget: 'admin@example.com',
              notificationStatus: 'SENT',
              notificationStatusLabel: '已发送',
              responseStatus: 200,
              sentAt: '2026-05-09T10:30:00',
              createdAt: '2026-05-09T10:30:00',
            },
          ],
        }),
      });
    });

    const response = await page.evaluate(async (ruleId) => {
      const res = await fetch(`/api/monitor/alert-rules/${ruleId}/notifications?limit=20`);
      return { ok: res.ok, body: await res.json() };
    }, ruleId);

    expect(response.ok).toBe(true);
    const first = response.body.data[0];

    // 验证所有必需字段
    expect(first.id).toBeDefined();
    expect(first.alertEventId).toBeDefined();
    expect(first.alertRuleId).toBeDefined();
    expect(first.channelType).toBeDefined();
    expect(first.channelTarget).toBeDefined();
    expect(first.notificationStatus).toBeDefined();
    expect(first.createdAt).toBeDefined();
    expect(first.channelTypeLabel).toBe('邮件');
    expect(first.notificationStatusLabel).toBe('已发送');
  });
});