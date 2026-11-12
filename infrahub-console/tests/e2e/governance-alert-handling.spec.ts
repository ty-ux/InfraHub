import { expect, test } from '@playwright/test';
import { loginAsAdmin } from './helpers/auth';

/**
 * E2E tests for V2/N-C: Governance Alert Handling
 * Covers: Ack, Resolve, Reopen actions; evidencePayload handling;
 *         MANUAL_ONLY boundary; status-based button visibility
 * Uses route mocking with Contract-defined API paths
 * Enforces MANUAL_ONLY: no auto execute / auto scale / executor text
 */

/**
 * Helper to wrap response in ApiResponse structure
 */
function apiResponse<T>(data: T) {
  return {
    code: 'SUCCESS',
    message: '',
    traceId: `trace-${Date.now()}`,
    data
  };
}

/**
 * Helper to wrap paginated response
 */
function pageResponse<T>(records: T[], total?: number) {
  return apiResponse({
    records,
    total: total ?? records.length,
    pageNum: 1,
    pageSize: 10
  });
}

/**
 * Mock alert records data - one per status for testing
 */
const mockAlertRecords = [
  {
    id: 1,
    alertNo: 'AL-N-001',
    ruleId: 1,
    ruleName: 'Critical Risk Threshold',
    metricType: 'CRITICAL_RISK_COUNT',
    currentValue: 8,
    thresholdValue: 5,
    operator: 'GT',
    alertLevel: 'CRITICAL',
    alertStatus: 'OPEN',
    reason: 'Critical risk count exceeded threshold',
    createdAt: new Date().toISOString()
  },
  {
    id: 2,
    alertNo: 'AL-N-002',
    ruleId: 2,
    ruleName: 'SLA Hit Rate Warning',
    metricType: 'SLA_HIT_RATE',
    currentValue: 72.5,
    thresholdValue: 80,
    operator: 'LT',
    alertLevel: 'WARNING',
    alertStatus: 'ACKED',
    reason: 'SLA hit rate below threshold',
    createdAt: new Date().toISOString(),
    acknowledgedAt: new Date().toISOString(),
    acknowledgedBy: 1,
    acknowledgedByName: 'Admin'
  },
  {
    id: 3,
    alertNo: 'AL-N-003',
    ruleId: 1,
    ruleName: 'Critical Risk Threshold',
    metricType: 'CRITICAL_RISK_COUNT',
    currentValue: 3,
    thresholdValue: 5,
    operator: 'GT',
    alertLevel: 'CRITICAL',
    alertStatus: 'RESOLVED',
    reason: 'Issue resolved, risks mitigated',
    createdAt: new Date(Date.now() - 86400000).toISOString(),
    resolvedAt: new Date().toISOString(),
    resolvedBy: 1,
    resolvedByName: 'Admin'
  }
];

/**
 * Mock handling records for alert detail
 */
const mockHandlingRecords = [
  {
    id: 101,
    alertId: 3,
    alertNo: 'AL-N-003',
    fromStatus: 'OPEN',
    toStatus: 'ACKED',
    action: 'ACK',
    handleNote: 'Investigating',
    evidencePayload: { confirmedBy: 'Admin', confirmedAt: '2026-05-13T10:00:00Z' },
    operatorId: 1,
    operatorName: 'Admin',
    createdAt: new Date(Date.now() - 3600000).toISOString()
  },
  {
    id: 102,
    alertId: 3,
    alertNo: 'AL-N-003',
    fromStatus: 'ACKED',
    toStatus: 'RESOLVED',
    action: 'RESOLVE',
    handleNote: 'Fixed by scaling',
    evidencePayload: { resolution: 'Scaled pool instances from 4 to 8' },
    operatorId: 1,
    operatorName: 'Admin',
    createdAt: new Date().toISOString()
  }
];

/**
 * Mock evaluation response
 */
const mockEvaluationResponse = {
  evaluatedAt: new Date().toISOString(),
  evaluatedRuleCount: 3,
  triggeredAlertCount: 0,
  alertIds: [],
  manualOnly: true
};

test.describe('governance alert handling (N-C)', () => {
  test.setTimeout(60000);

  test.beforeEach(async ({ page }) => {
    await loginAsAdmin(page);
  });

  /**
   * Setup all route mocks for alert handling tests
   */
  async function setupMocks(page: import('@playwright/test').Page) {
    // Mock evaluate endpoint
    await page.route('**/api/assets/governance-ops/alerts/evaluate**', async route => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(apiResponse(mockEvaluationResponse))
      });
    });

    // Mock alert records list (GET base alerts path)
    await page.route('**/api/assets/governance-ops/alerts**', async route => {
      const url = route.request().url();
      const method = route.request().method();

      // Handle ack/resolve/reopen action endpoints
      const ackMatch = url.match(/\/alerts\/(\d+)\/ack$/);
      const resolveMatch = url.match(/\/alerts\/(\d+)\/resolve$/);
      const reopenMatch = url.match(/\/alerts\/(\d+)\/reopen$/);
      const handlingRecordsMatch = url.match(/\/alerts\/(\d+)\/handling-records$/);

      if (method === 'POST' && ackMatch) {
        const id = parseInt(ackMatch[1], 10);
        const record = mockAlertRecords.find(r => r.id === id) || mockAlertRecords[0];
        const body = await route.request().postDataJSON().catch(() => ({}));
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify(apiResponse({
            ...record,
            alertStatus: 'ACKED',
            acknowledgedAt: new Date().toISOString(),
            acknowledgedBy: 1,
            acknowledgedByName: 'Admin',
            handleNote: body.handleNote || undefined,
            evidencePayload: body.evidencePayload || undefined
          }))
        });
      } else if (method === 'POST' && resolveMatch) {
        const id = parseInt(resolveMatch[1], 10);
        const record = mockAlertRecords.find(r => r.id === id) || mockAlertRecords[0];
        const body = await route.request().postDataJSON().catch(() => ({}));
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify(apiResponse({
            ...record,
            alertStatus: 'RESOLVED',
            resolvedAt: new Date().toISOString(),
            resolvedBy: 1,
            resolvedByName: 'Admin',
            handleNote: body.handleNote || undefined,
            evidencePayload: body.evidencePayload || undefined
          }))
        });
      } else if (method === 'POST' && reopenMatch) {
        const id = parseInt(reopenMatch[1], 10);
        const record = mockAlertRecords.find(r => r.id === id) || mockAlertRecords[0];
        const body = await route.request().postDataJSON().catch(() => ({}));
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify(apiResponse({
            ...record,
            alertStatus: 'OPEN',
            handleNote: body.handleNote || undefined,
            evidencePayload: body.evidencePayload || undefined
          }))
        });
      } else if (method === 'GET' && handlingRecordsMatch) {
        const id = parseInt(handlingRecordsMatch[1], 10);
        const records = id === 3 ? mockHandlingRecords : [];
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify(pageResponse(records))
        });
      } else if (method === 'GET') {
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify(pageResponse(mockAlertRecords))
        });
      } else {
        await route.continue();
      }
    });

    // Mock alert rules
    await page.route('**/api/assets/governance-ops/alert-rules**', async route => {
      if (route.request().method() === 'GET') {
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify(pageResponse([]))
        });
      } else {
        await route.continue();
      }
    });

    // Mock reports
    await page.route('**/api/assets/governance-ops/reports**', async route => {
      if (route.request().method() === 'GET') {
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify(pageResponse([]))
        });
      } else {
        await route.continue();
      }
    });

    // Mock envs and services lookup
    await page.route('**/api/envs**', async route => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(pageResponse([{ id: 1, envCode: 'test', envName: 'Test' }]))
      });
    });

    await page.route('**/api/services**', async route => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(pageResponse([{ id: 1, serviceCode: 'svc-a', serviceName: 'Service A' }]))
      });
    });

    // Mock resource pools
    await page.route('**/api/assets/resource-pools**', async route => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(pageResponse([{ id: 1, poolCode: 'POOL-01', poolName: 'Test Pool', poolType: 'KUBERNETES', status: 'ACTIVE', createdAt: new Date().toISOString() }]))
      });
    });

    // Mock hosts
    await page.route('**/api/assets/hosts**', async route => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(pageResponse([]))
      });
    });

    // Mock other governance APIs with empty responses
    await page.route('**/api/assets/resource-governance/records**', async route => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(pageResponse([]))
      });
    });

    await page.route('**/api/assets/governance-suggestions**', async route => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(pageResponse([]))
      });
    });

    await page.route('**/api/assets/resource-pools/risks**', async route => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(pageResponse([]))
      });
    });
  }

  // ========== Status-based button visibility tests ==========

  test('OPEN status shows Ack and Resolve buttons', async ({ page }) => {
    await setupMocks(page);

    await page.goto('/resource/governance');
    await expect(page.getByRole('heading', { name: '资源治理工作台' })).toBeVisible({ timeout: 10000 });

    await page.getByRole('tab', { name: '告警记录' }).click();
    await expect(page.locator('.alert-records-tab')).toBeVisible({ timeout: 10000 });

    const table = page.locator('.alert-records-tab .el-table').first();
    await expect(table).toBeVisible({ timeout: 10000 });

    // Find the OPEN row (AL-N-001) and check for Ack + Resolve buttons
    const openRow = table.locator('tr').filter({ hasText: 'AL-N-001' });
    await expect(openRow).toBeVisible({ timeout: 5000 });
    await expect(openRow.getByText('确认')).toBeVisible();
    await expect(openRow.getByText('解决')).toBeVisible();
    // Should NOT show Reopen for OPEN
    await expect(openRow.getByText('重新打开')).toHaveCount(0);
  });

  test('ACKED status shows Resolve and Reopen buttons', async ({ page }) => {
    await setupMocks(page);

    await page.goto('/resource/governance');
    await expect(page.getByRole('heading', { name: '资源治理工作台' })).toBeVisible({ timeout: 10000 });

    await page.getByRole('tab', { name: '告警记录' }).click();
    await expect(page.locator('.alert-records-tab')).toBeVisible({ timeout: 10000 });

    const table = page.locator('.alert-records-tab .el-table').first();
    await expect(table).toBeVisible({ timeout: 10000 });

    // Find the row containing AL-N-002 (ACKED status)
    const ackedRow = table.locator('tbody tr').filter({ hasText: 'AL-N-002' });
    await expect(ackedRow).toBeVisible({ timeout: 5000 });

    // Check status column shows 已确认 (ACKED)
    await expect(ackedRow.getByText('已确认')).toBeVisible();

    // Check actions column - should have Resolve and Reopen
    const actionsCell = ackedRow.locator('td').last();
    await expect(actionsCell.getByText('解决')).toBeVisible();
    await expect(actionsCell.getByText('重新打开')).toBeVisible();
    // Should NOT show Ack (确认) button for ACKED status
    await expect(actionsCell.getByRole('button', { name: '确认' })).toHaveCount(0);
  });

  test('RESOLVED status shows Reopen but NOT Ack button', async ({ page }) => {
    await setupMocks(page);

    await page.goto('/resource/governance');
    await expect(page.getByRole('heading', { name: '资源治理工作台' })).toBeVisible({ timeout: 10000 });

    await page.getByRole('tab', { name: '告警记录' }).click();
    await expect(page.locator('.alert-records-tab')).toBeVisible({ timeout: 10000 });

    const table = page.locator('.alert-records-tab .el-table').first();
    await expect(table).toBeVisible({ timeout: 10000 });

    const resolvedRow = table.locator('tr').filter({ hasText: 'AL-N-003' });
    await expect(resolvedRow).toBeVisible({ timeout: 5000 });
    await expect(resolvedRow.getByText('重新打开')).toBeVisible();
    // RESOLVED must NOT show Ack (Contract 5.3: RESOLVED -> ACKED is forbidden)
    await expect(resolvedRow.getByText('确认')).toHaveCount(0);
  });

  // ========== Action dialog and API call tests ==========

  test('OPEN can ack - dialog opens and submits to correct API path', async ({ page }) => {
    await setupMocks(page);
    let ackCalled = false;

    // Intercept to verify the API path
    await page.route('**/api/assets/governance-ops/alerts/1/ack', async route => {
      ackCalled = true;
      let body: Record<string, unknown> = {};
      try {
        body = await route.request().postDataJSON() || {};
      } catch {
        body = {};
      }
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(apiResponse({
          ...mockAlertRecords[0],
          alertStatus: 'ACKED',
          acknowledgedAt: new Date().toISOString(),
          acknowledgedBy: 1,
          acknowledgedByName: 'Admin',
          handleNote: body.handleNote,
          evidencePayload: body.evidencePayload
        }))
      });
    });

    await page.goto('/resource/governance');
    await expect(page.getByRole('heading', { name: '资源治理工作台' })).toBeVisible({ timeout: 10000 });

    await page.getByRole('tab', { name: '告警记录' }).click();
    await expect(page.locator('.alert-records-tab')).toBeVisible({ timeout: 10000 });

    const table = page.locator('.alert-records-tab .el-table').first();
    await expect(table).toBeVisible({ timeout: 10000 });

    // Click Ack on OPEN row
    const openRow = table.locator('tr').filter({ hasText: 'AL-N-001' });
    await openRow.getByText('确认').click();

    // Verify dialog opens
    const dialog = page.locator('.el-dialog').filter({ hasText: '确认告警' });
    await expect(dialog).toBeVisible({ timeout: 5000 });

    // Enter handle note
    await dialog.locator('textarea').first().fill('Test ack note');

    // Submit
    await dialog.getByRole('button', { name: '确认' }).click();

    // Verify API was called
    await page.waitForTimeout(2000);
    expect(ackCalled).toBe(true);
  });

  test('OPEN can resolve - dialog opens and submits', async ({ page }) => {
    await setupMocks(page);
    let resolveCalled = false;

    await page.route('**/api/assets/governance-ops/alerts/1/resolve', async route => {
      resolveCalled = true;
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(apiResponse({
          ...mockAlertRecords[0],
          alertStatus: 'RESOLVED',
          resolvedAt: new Date().toISOString(),
          resolvedBy: 1,
          resolvedByName: 'Admin'
        }))
      });
    });

    await page.goto('/resource/governance');
    await expect(page.getByRole('heading', { name: '资源治理工作台' })).toBeVisible({ timeout: 10000 });

    await page.getByRole('tab', { name: '告警记录' }).click();
    await expect(page.locator('.alert-records-tab')).toBeVisible({ timeout: 10000 });

    const table = page.locator('.alert-records-tab .el-table').first();
    await expect(table).toBeVisible({ timeout: 10000 });

    const openRow = table.locator('tr').filter({ hasText: 'AL-N-001' });
    await openRow.getByText('解决').click();

    const dialog = page.locator('.el-dialog').filter({ hasText: '解决告警' });
    await expect(dialog).toBeVisible({ timeout: 5000 });
    await dialog.getByRole('button', { name: '确认' }).click();

    await page.waitForTimeout(2000);
    expect(resolveCalled).toBe(true);
  });

  test('ACKED can resolve', async ({ page }) => {
    await setupMocks(page);
    let resolveCalled = false;

    await page.route('**/api/assets/governance-ops/alerts/2/resolve', async route => {
      resolveCalled = true;
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(apiResponse({
          ...mockAlertRecords[1],
          alertStatus: 'RESOLVED',
          resolvedAt: new Date().toISOString(),
          resolvedBy: 1,
          resolvedByName: 'Admin'
        }))
      });
    });

    await page.goto('/resource/governance');
    await expect(page.getByRole('heading', { name: '资源治理工作台' })).toBeVisible({ timeout: 10000 });

    await page.getByRole('tab', { name: '告警记录' }).click();
    await expect(page.locator('.alert-records-tab')).toBeVisible({ timeout: 10000 });

    const table = page.locator('.alert-records-tab .el-table').first();
    await expect(table).toBeVisible({ timeout: 10000 });

    const ackedRow = table.locator('tr').filter({ hasText: 'AL-N-002' });
    await ackedRow.getByText('解决').click();

    const dialog = page.locator('.el-dialog').filter({ hasText: '解决告警' });
    await expect(dialog).toBeVisible({ timeout: 5000 });
    await dialog.getByRole('button', { name: '确认' }).click();

    await page.waitForTimeout(2000);
    expect(resolveCalled).toBe(true);
  });

  test('ACKED can reopen', async ({ page }) => {
    await setupMocks(page);
    let reopenCalled = false;

    await page.route('**/api/assets/governance-ops/alerts/2/reopen', async route => {
      reopenCalled = true;
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(apiResponse({
          ...mockAlertRecords[1],
          alertStatus: 'OPEN'
        }))
      });
    });

    await page.goto('/resource/governance');
    await expect(page.getByRole('heading', { name: '资源治理工作台' })).toBeVisible({ timeout: 10000 });

    await page.getByRole('tab', { name: '告警记录' }).click();
    await expect(page.locator('.alert-records-tab')).toBeVisible({ timeout: 10000 });

    const table = page.locator('.alert-records-tab .el-table').first();
    await expect(table).toBeVisible({ timeout: 10000 });

    const ackedRow = table.locator('tr').filter({ hasText: 'AL-N-002' });
    await ackedRow.getByText('重新打开').click();

    const dialog = page.locator('.el-dialog').filter({ hasText: '重新打开告警' });
    await expect(dialog).toBeVisible({ timeout: 5000 });
    await dialog.getByRole('button', { name: '确认' }).click();

    await page.waitForTimeout(2000);
    expect(reopenCalled).toBe(true);
  });

  test('RESOLVED can reopen', async ({ page }) => {
    await setupMocks(page);
    let reopenCalled = false;

    await page.route('**/api/assets/governance-ops/alerts/3/reopen', async route => {
      reopenCalled = true;
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(apiResponse({
          ...mockAlertRecords[2],
          alertStatus: 'OPEN'
        }))
      });
    });

    await page.goto('/resource/governance');
    await expect(page.getByRole('heading', { name: '资源治理工作台' })).toBeVisible({ timeout: 10000 });

    await page.getByRole('tab', { name: '告警记录' }).click();
    await expect(page.locator('.alert-records-tab')).toBeVisible({ timeout: 10000 });

    const table = page.locator('.alert-records-tab .el-table').first();
    await expect(table).toBeVisible({ timeout: 10000 });

    const resolvedRow = table.locator('tr').filter({ hasText: 'AL-N-003' });
    await resolvedRow.getByText('重新打开').click();

    const dialog = page.locator('.el-dialog').filter({ hasText: '重新打开告警' });
    await expect(dialog).toBeVisible({ timeout: 5000 });
    await dialog.getByRole('button', { name: '确认' }).click();

    await page.waitForTimeout(2000);
    expect(reopenCalled).toBe(true);
  });

  // ========== evidencePayload handling tests ==========

  test('evidencePayload JSON object sent correctly', async ({ page }) => {
    await setupMocks(page);
    let capturedBody: Record<string, unknown> = {};

    await page.route('**/api/assets/governance-ops/alerts/1/ack', async route => {
      try {
        capturedBody = await route.request().postDataJSON() || {};
      } catch {
        capturedBody = {};
      }
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(apiResponse({
          ...mockAlertRecords[0],
          alertStatus: 'ACKED',
          acknowledgedAt: new Date().toISOString()
        }))
      });
    });

    await page.goto('/resource/governance');
    await expect(page.getByRole('heading', { name: '资源治理工作台' })).toBeVisible({ timeout: 10000 });

    await page.getByRole('tab', { name: '告警记录' }).click();
    await expect(page.locator('.alert-records-tab')).toBeVisible({ timeout: 10000 });

    const table = page.locator('.alert-records-tab .el-table').first();
    await expect(table).toBeVisible({ timeout: 10000 });

    const openRow = table.locator('tr').filter({ hasText: 'AL-N-001' });
    await openRow.getByText('确认').click();

    const dialog = page.locator('.el-dialog').filter({ hasText: '确认告警' });
    await expect(dialog).toBeVisible({ timeout: 5000 });

    // Fill evidence payload as JSON object
    const textareas = dialog.locator('textarea');
    await textareas.nth(1).fill('{"confirmedBy":"Admin","confirmedAt":"2026-05-13T10:00:00Z"}');

    await dialog.getByRole('button', { name: '确认' }).click();
    await page.waitForTimeout(2000);

    // Verify evidencePayload is a JSON object
    expect(capturedBody.evidencePayload).toBeDefined();
    expect(typeof capturedBody.evidencePayload).toBe('object');
    expect((capturedBody.evidencePayload as Record<string, unknown>).confirmedBy).toBe('Admin');
  });

  test('evidencePayload plain text wrapped as { text: ... }', async ({ page }) => {
    await setupMocks(page);
    let capturedBody: Record<string, unknown> = {};

    await page.route('**/api/assets/governance-ops/alerts/1/ack', async route => {
      try {
        capturedBody = await route.request().postDataJSON() || {};
      } catch {
        capturedBody = {};
      }
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(apiResponse({
          ...mockAlertRecords[0],
          alertStatus: 'ACKED',
          acknowledgedAt: new Date().toISOString()
        }))
      });
    });

    await page.goto('/resource/governance');
    await expect(page.getByRole('heading', { name: '资源治理工作台' })).toBeVisible({ timeout: 10000 });

    await page.getByRole('tab', { name: '告警记录' }).click();
    await expect(page.locator('.alert-records-tab')).toBeVisible({ timeout: 10000 });

    const table = page.locator('.alert-records-tab .el-table').first();
    await expect(table).toBeVisible({ timeout: 10000 });

    const openRow = table.locator('tr').filter({ hasText: 'AL-N-001' });
    await openRow.getByText('确认').click();

    const dialog = page.locator('.el-dialog').filter({ hasText: '确认告警' });
    await expect(dialog).toBeVisible({ timeout: 5000 });

    // Fill evidence payload as plain text (not JSON)
    const textareas = dialog.locator('textarea');
    await textareas.nth(1).fill('This is a plain text note');

    await dialog.getByRole('button', { name: '确认' }).click();
    await page.waitForTimeout(2000);

    // Verify evidencePayload is wrapped as { text: "..." }
    expect(capturedBody.evidencePayload).toBeDefined();
    expect(typeof capturedBody.evidencePayload).toBe('object');
    expect((capturedBody.evidencePayload as Record<string, unknown>).text).toBe('This is a plain text note');
  });

  // ========== MANUAL_ONLY boundary tests ==========

  test('no auto execute / auto scale / executor text appears in alert records tab', async ({ page }) => {
    await setupMocks(page);

    await page.goto('/resource/governance');
    await expect(page.getByRole('heading', { name: '资源治理工作台' })).toBeVisible({ timeout: 10000 });

    await page.getByRole('tab', { name: '告警记录' }).click();
    await expect(page.locator('.alert-records-tab')).toBeVisible({ timeout: 10000 });

    const tab = page.locator('.alert-records-tab');
    // Must NOT have buttons for auto execute, auto scale, executor
    await expect(tab.getByRole('button', { name: /自动执行|自动扩容|调用执行器|Auto execute|Auto scale/i })).toHaveCount(0);
    // Must NOT have column headers for auto actions
    const tableHeader = tab.locator('.el-table__header th');
    await expect(tableHeader).not.toContainText(['自动执行', 'Auto execute', '自动扩容', 'Auto scale', '执行器', 'Executor']);
  });

  // ========== API failure isolation test ==========

  test('API failure shows local error without affecting other tabs', async ({ page }) => {
    await setupMocks(page);

    // Override alerts API to fail
    await page.route('**/api/assets/governance-ops/alerts**', async route => {
      await route.fulfill({
        status: 500,
        contentType: 'application/json',
        body: JSON.stringify({ code: 'INTERNAL_ERROR', message: 'Server error' })
      });
    });

    await page.goto('/resource/governance');
    await expect(page.getByRole('heading', { name: '资源治理工作台' })).toBeVisible({ timeout: 10000 });

    await page.getByRole('tab', { name: '告警记录' }).click();
    await expect(page.locator('.alert-records-tab')).toBeVisible({ timeout: 10000 });

    // Verify error alert is shown
    await expect(page.locator('.alert-records-tab .el-alert--error')).toBeVisible({ timeout: 5000 });

    // Switch to another tab - should still work
    await page.getByRole('tab', { name: '资源池容量' }).click();
    await expect(page.locator('.pool-capacity-tab')).toBeVisible({ timeout: 10000 });

    // Verify no global error message
    await expect(page.locator('.el-message--error').first()).not.toBeVisible();
  });
});
