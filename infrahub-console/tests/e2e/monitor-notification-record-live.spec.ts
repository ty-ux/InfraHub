import { expect, test } from '@playwright/test';

test.describe('E3.2 monitor notification record - Live API', () => {
  test.setTimeout(15000);

  test('live API: creates and queries notification records by alert event and rule', async () => {
    const suffix = Date.now().toString().slice(-8);
    const ruleName = `e3-live-notify-${suffix}`;
    const metricName = `e3_live_metric_${suffix}`;
    const targetId = Number(`93${suffix.slice(-6)}`);
    const headers = await getAuthHeaders();
    let ruleId: number | undefined;

    try {
      ruleId = await test.step('create alert rule with simulated email channel', async () => {
        const data = await postApi<number>(headers, '/api/monitor/alert-rules', {
          ruleName,
          targetType: 'HOST',
          targetId,
          metricName,
          compareOp: 'GT',
          thresholdValue: 1,
          durationSeconds: 0,
          level: 'CRITICAL',
          notifyChannel: `email:${ruleName}@example.com`,
          enabled: true,
        });
        expect(data).toBeTruthy();
        return data;
      });

      const eventId = await test.step('evaluate rule and create notification record', async () => {
        const result = await postApi<{
          hitCount: number;
          eventIds: number[];
          hitRules: { ruleId: number; ruleName: string }[];
        }>(headers, '/api/monitor/evaluations', {
          targetType: 'HOST',
          targetId,
          metricPayload: JSON.stringify({ [metricName]: 99 }),
        });

        expect(result.hitCount).toBe(1);
        expect(result.eventIds).toHaveLength(1);
        expect(result.hitRules[0].ruleId).toBe(ruleId);
        expect(result.hitRules[0].ruleName).toBe(ruleName);
        return result.eventIds[0];
      });

      await test.step('query notification records by alert event', async () => {
        const records = await getApi<NotificationRecord[]>(headers, `/api/monitor/alert-events/${eventId}/notifications`);

        expect(records).toHaveLength(1);
        expect(records[0]).toMatchObject({
          alertEventId: eventId,
          alertRuleId: ruleId,
          channelType: 'EMAIL',
          channelTypeLabel: '邮件',
          notificationStatus: 'SENT',
          notificationStatusLabel: '已发送',
          responseStatus: 200,
        });
        expect(records[0].id).toBeTruthy();
        expect(records[0].channelTarget).toBe(`${ruleName}@example.com`);
        expect(records[0].sentAt).toBeTruthy();
        expect(records[0].createdAt).toBeTruthy();
      });

      await test.step('query notification records by alert rule', async () => {
        const records = await getApi<NotificationRecord[]>(headers, `/api/monitor/alert-rules/${ruleId}/notifications?limit=10`);
        const record = records.find((item) => item.alertEventId === eventId);

        expect(record).toBeTruthy();
        expect(record).toMatchObject({
          alertEventId: eventId,
          alertRuleId: ruleId,
          channelType: 'EMAIL',
          notificationStatus: 'SENT',
        });
      });
    } finally {
      if (ruleId) {
        await fetch(apiUrl(`/api/monitor/alert-rules/${ruleId}`), {
          method: 'DELETE',
          headers,
          signal: AbortSignal.timeout(3000),
        }).catch(() => undefined);
      }
    }
  });
});

async function getAuthHeaders() {
  const data = await postApi<{ accessToken: string }>({}, '/api/auth/login', {
    username: 'admin',
    password: 'admin123',
  });
  expect(data.accessToken).toBeTruthy();
  return { Authorization: `Bearer ${data.accessToken}` };
}

async function getApi<T>(headers: Record<string, string>, url: string): Promise<T> {
  return parseApiResponse<T>(await fetch(apiUrl(url), {
    method: 'GET',
    headers,
    signal: AbortSignal.timeout(5000),
  }), url);
}

async function postApi<T>(
  headers: Record<string, string>,
  url: string,
  data: Record<string, unknown>,
): Promise<T> {
  return parseApiResponse<T>(await fetch(apiUrl(url), {
    method: 'POST',
    headers: {
      ...headers,
      'Content-Type': 'application/json',
    },
    body: JSON.stringify(data),
    signal: AbortSignal.timeout(5000),
  }), url);
}

async function parseApiResponse<T>(response: Response, url: string): Promise<T> {
  const text = await response.text();
  const body = text ? JSON.parse(text) : null;
  expect(response.ok, `${url} should return 2xx: ${text}`).toBeTruthy();
  expect(body.code, `${url} should return SUCCESS envelope`).toBe('SUCCESS');
  return body.data as T;
}

function apiUrl(path: string) {
  const baseURL = process.env.PLAYWRIGHT_BASE_URL || 'http://localhost:5173';
  return new URL(path, baseURL).toString();
}

interface NotificationRecord {
  id: number;
  alertEventId: number;
  alertRuleId: number;
  channelType: string;
  channelTypeLabel: string;
  channelTarget: string;
  notificationStatus: string;
  notificationStatusLabel: string;
  responseStatus?: number;
  errorMessage?: string;
  sentAt?: string;
  createdAt: string;
}
