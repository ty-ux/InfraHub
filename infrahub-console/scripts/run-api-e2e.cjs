const baseURL = process.env.PLAYWRIGHT_BASE_URL || 'http://localhost:5173';
const hardTimeout = setTimeout(() => {
  console.error('API E2E timed out after 25s.');
  process.exit(124);
}, 25_000);

async function main() {
  const suffix = new Date().toISOString().replace(/\D/g, '').slice(-9);
  const ruleName = `e3-live-notify-${suffix}`;
  const metricName = `e3_live_metric_${suffix}`;
  const targetId = Number(`93${suffix.slice(-6)}`);
  let token;
  let ruleId;

  try {
    token = (await post('/api/auth/login', {
      username: 'admin',
      password: 'admin123',
    })).accessToken;

    ruleId = await post('/api/monitor/alert-rules', {
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
    }, token);

    const evaluation = await post('/api/monitor/evaluations', {
      targetType: 'HOST',
      targetId,
      metricPayload: JSON.stringify({ [metricName]: 99 }),
    }, token);

    assert(evaluation.hitCount === 1, `expected hitCount=1, got ${evaluation.hitCount}`);
    assert(Array.isArray(evaluation.eventIds) && evaluation.eventIds.length === 1, 'expected one alert event');
    assert(evaluation.hitRules?.[0]?.ruleId === ruleId, 'expected hit rule to match created rule');

    const eventId = evaluation.eventIds[0];
    const byEvent = await get(`/api/monitor/alert-events/${eventId}/notifications`, token);
    assert(Array.isArray(byEvent) && byEvent.length === 1, 'expected one notification by event');
    assertNotification(byEvent[0], { ruleId, eventId, ruleName });

    const byRule = await get(`/api/monitor/alert-rules/${ruleId}/notifications?limit=10`, token);
    const record = byRule.find((item) => item.alertEventId === eventId);
    assert(record, 'expected notification to be queryable by rule');
    assertNotification(record, { ruleId, eventId, ruleName });

    console.log(`API_E2E_OK rule=${ruleId} event=${eventId} channel=${record.channelType} status=${record.notificationStatus}`);
  } finally {
    if (token && ruleId) {
      await request('DELETE', `/api/monitor/alert-rules/${ruleId}`, undefined, token, 3_000).catch(() => undefined);
    }
    clearTimeout(hardTimeout);
  }
}

function assertNotification(record, expected) {
  assert(record.alertEventId === expected.eventId, 'notification alertEventId mismatch');
  assert(record.alertRuleId === expected.ruleId, 'notification alertRuleId mismatch');
  assert(record.channelType === 'EMAIL', `expected EMAIL channel, got ${record.channelType}`);
  assert(record.channelTypeLabel === '邮件', `expected 邮件 label, got ${record.channelTypeLabel}`);
  assert(record.channelTarget === `${expected.ruleName}@example.com`, 'notification channelTarget mismatch');
  assert(record.notificationStatus === 'SENT', `expected SENT status, got ${record.notificationStatus}`);
  assert(record.notificationStatusLabel === '已发送', `expected 已发送 label, got ${record.notificationStatusLabel}`);
  assert(record.responseStatus === 200, `expected responseStatus=200, got ${record.responseStatus}`);
  assert(record.id, 'notification id should exist');
  assert(record.createdAt, 'notification createdAt should exist');
}

async function get(path, token) {
  return request('GET', path, undefined, token);
}

async function post(path, body, token) {
  return request('POST', path, body, token);
}

async function request(method, path, body, token, timeout = 5_000) {
  const headers = {};
  if (token) headers.Authorization = `Bearer ${token}`;
  if (body !== undefined) headers['Content-Type'] = 'application/json';

  const response = await fetch(new URL(path, baseURL), {
    method,
    headers,
    body: body === undefined ? undefined : JSON.stringify(body),
    signal: AbortSignal.timeout(timeout),
  });
  const text = await response.text();
  const payload = text ? JSON.parse(text) : null;
  assert(response.ok, `${method} ${path} returned ${response.status}: ${text}`);
  assert(payload?.code === 'SUCCESS', `${method} ${path} did not return SUCCESS: ${text}`);
  return payload.data;
}

function assert(condition, message) {
  if (!condition) {
    throw new Error(message);
  }
}

main().catch((error) => {
  clearTimeout(hardTimeout);
  console.error(error);
  process.exit(1);
});
