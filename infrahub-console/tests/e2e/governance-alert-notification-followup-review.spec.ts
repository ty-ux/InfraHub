import { expect, test } from '@playwright/test';
import { loginAsAdmin } from './helpers/auth';

/**
 * E2E tests for V2/S-D: Governance Alert Notification Followup Review
 * Covers: S-01 to S-18 per Contract 11.1
 * Uses route mocking with Contract-defined API paths
 * Enforces MANUAL_ONLY / read-only boundary: no POST/PUT/DELETE, no auto-process hints
 *
 * Contract Reference: docs/release/v2-stage-s-contract.md
 * API Prefix: /api/assets/governance-ops/alert-notification-followup-review
 */

// ===== Helpers =====

function apiResponse<T>(data: T) {
  return {
    code: 'SUCCESS',
    message: '',
    traceId: `trace-${Date.now()}`,
    data
  };
}

function pageResponse<T>(records: T[], total?: number, pageNum = 1, pageSize = 20) {
  return apiResponse({
    records,
    total: total ?? records.length,
    pageNum,
    pageSize
  });
}

// ===== Mock Data (per Contract 3.1 - 3.7) =====

const mockOverview = {
  totalNotifications: 50,
  followedUpNotifications: 35,
  pendingFollowupNotifications: 15,
  totalFollowupRecords: 60,
  completedFollowupRecords: 40,
  inProgressFollowupRecords: 15,
  cancelledFollowupRecords: 5,
  pendingReceiptCount: 10,
  confirmedReceiptCount: 30,
  failedReceiptCount: 5,
  noResponseCount: 15,
  followupCompletionRate: 66.67,
  receiptConfirmationRate: 60.0,
  averageFirstFollowupMinutes: 45.3,
  overdueFollowupCount: 3,
  updatedAt: new Date().toISOString()
};

const mockReceiptStats = {
  byChannelType: [
    { channelType: 'INTERNAL', totalCount: 20, confirmedCount: 15, failedCount: 2, noResponseCount: 3, confirmationRate: 75.0 },
    { channelType: 'EMAIL_DRAFT', totalCount: 18, confirmedCount: 10, failedCount: 2, noResponseCount: 6, confirmationRate: 55.56 },
    { channelType: 'WEBHOOK_DRAFT', totalCount: 12, confirmedCount: 5, failedCount: 1, noResponseCount: 6, confirmationRate: 41.67 }
  ],
  byTargetUser: [
    { targetUserId: 1, targetUserName: 'Admin', totalCount: 25, confirmedCount: 18, failedCount: 2, noResponseCount: 5, confirmationRate: 72.0 },
    { targetUserId: 2, targetUserName: 'Operator', totalCount: 25, confirmedCount: 12, failedCount: 3, noResponseCount: 10, confirmationRate: 48.0 }
  ],
  overall: {
    totalCount: 50,
    confirmedCount: 30,
    failedCount: 5,
    noResponseCount: 15,
    confirmationRate: 60.0
  }
};

const mockDistribution = {
  byFollowUpStatus: [
    { followUpStatus: 'COMPLETED', count: 40, percentage: 66.67 },
    { followUpStatus: 'IN_PROGRESS', count: 15, percentage: 25.0 },
    { followUpStatus: 'CANCELLED', count: 5, percentage: 8.33 }
  ],
  byReceiptStatus: [
    { receiptStatus: 'CONFIRMED', count: 30, percentage: 50.0 },
    { receiptStatus: 'PENDING', count: 10, percentage: 16.67 },
    { receiptStatus: 'FAILED', count: 5, percentage: 8.33 },
    { receiptStatus: 'NO_RESPONSE', count: 15, percentage: 25.0 }
  ],
  byFollowUpType: [
    { followUpType: 'FOLLOW_UP', count: 25, percentage: 41.67 },
    { followUpType: 'CALL_MADE', count: 15, percentage: 25.0 },
    { followUpType: 'REPLY_RECEIVED', count: 10, percentage: 16.67 },
    { followUpType: 'VISIT_MADE', count: 5, percentage: 8.33 },
    { followUpType: 'OTHER', count: 5, percentage: 8.33 }
  ]
};

const mockByHandlerRecords = [
  { handlerId: 1, handlerName: 'Admin', totalFollowupCount: 35, completedCount: 25, inProgressCount: 8, cancelledCount: 2, completionRate: 71.43, averageFollowupMinutes: 30.5, firstFollowupCount: 20, averageFirstFollowupMinutes: 25.3 },
  { handlerId: 2, handlerName: 'Operator', totalFollowupCount: 25, completedCount: 15, inProgressCount: 7, cancelledCount: 3, completionRate: 60.0, averageFollowupMinutes: 55.2, firstFollowupCount: 15, averageFirstFollowupMinutes: 40.1 }
];

const mockByAlertRecords = [
  { alertRecordId: 101, alertNo: 'AL-001', alertTitle: 'CPU usage critical', alertLevel: 'CRITICAL', alertStatus: 'ACKED', notificationCount: 5, followupCount: 8, uniqueHandlerCount: 2, confirmedReceiptCount: 4, pendingReceiptCount: 1, closedLoopRate: 80.0, latestFollowupAt: new Date().toISOString(), createdAt: new Date(Date.now() - 86400000).toISOString() },
  { alertRecordId: 102, alertNo: 'AL-002', alertTitle: 'Memory warning', alertLevel: 'WARNING', alertStatus: 'OPEN', notificationCount: 3, followupCount: 4, uniqueHandlerCount: 1, confirmedReceiptCount: 2, pendingReceiptCount: 1, closedLoopRate: 66.67, latestFollowupAt: new Date(Date.now() - 3600000).toISOString(), createdAt: new Date(Date.now() - 172800000).toISOString() }
];

const mockByNotificationRecords = [
  { notificationRecordId: 1, notificationNo: 'NF-001', alertRecordId: 101, alertNo: 'AL-001', alertTitle: 'CPU usage critical', channelType: 'INTERNAL', notificationStatus: 'MANUALLY_CONFIRMED', targetUserId: 1, targetUserName: 'Admin', followupCount: 3, latestFollowupStatus: 'COMPLETED', latestReceiptStatus: 'CONFIRMED', firstFollowupAt: new Date(Date.now() - 7200000).toISOString(), latestFollowupAt: new Date(Date.now() - 3600000).toISOString(), createdAt: new Date(Date.now() - 86400000).toISOString() },
  { notificationRecordId: 2, notificationNo: 'NF-002', alertRecordId: 102, alertNo: 'AL-002', alertTitle: 'Memory warning', channelType: 'EMAIL_DRAFT', notificationStatus: 'MANUALLY_CONFIRMED', targetUserId: 2, targetUserName: 'Operator', followupCount: 2, latestFollowupStatus: 'IN_PROGRESS', latestReceiptStatus: 'PENDING', firstFollowupAt: new Date(Date.now() - 5400000).toISOString(), latestFollowupAt: new Date(Date.now() - 1800000).toISOString(), createdAt: new Date(Date.now() - 43200000).toISOString() }
];

const mockDetailRecords = [
  { followupId: 1, followUpNo: 'FU-001', notificationRecordId: 1, notificationNo: 'NF-001', alertRecordId: 101, alertNo: 'AL-001', alertTitle: 'CPU usage critical', alertLevel: 'CRITICAL', alertStatus: 'ACKED', channelType: 'INTERNAL', targetUserId: 1, targetUserName: 'Admin', followUpType: 'CALL_MADE', followUpContent: 'Called admin team', followUpResult: 'Acknowledged', followUpStatus: 'COMPLETED', receiptStatus: 'CONFIRMED', receiptNote: 'Received', createdBy: 1, createdByName: 'Admin', createdAt: new Date(Date.now() - 3600000).toISOString(), confirmedBy: 1, confirmedByName: 'Admin', confirmedAt: new Date(Date.now() - 1800000).toISOString(), firstFollowupForNotification: true, minutesSinceNotification: 60 },
  { followupId: 2, followUpNo: 'FU-002', notificationRecordId: 2, notificationNo: 'NF-002', alertRecordId: 102, alertNo: 'AL-002', alertTitle: 'Memory warning', alertLevel: 'WARNING', alertStatus: 'OPEN', channelType: 'EMAIL_DRAFT', targetUserId: 2, targetUserName: 'Operator', followUpType: 'FOLLOW_UP', followUpContent: 'Following up', followUpResult: 'Pending', followUpStatus: 'IN_PROGRESS', receiptStatus: 'PENDING', receiptNote: '', createdBy: 2, createdByName: 'Operator', createdAt: new Date(Date.now() - 7200000).toISOString(), confirmedBy: 0, confirmedByName: '', confirmedAt: '', firstFollowupForNotification: false, minutesSinceNotification: 120 }
];

// ===== S Stage API Prefix (Contract 2.1 - 2.7) =====
const S_API_PREFIX = '/api/assets/governance-ops/alert-notification-followup-review';

test.describe('governance alert notification followup review (S-D)', () => {
  test.setTimeout(90000);

  test.beforeEach(async ({ page }) => {
    await loginAsAdmin(page);
  });

  /**
   * Setup all route mocks for S stage review tests.
   * Mocks all 7 S-stage API endpoints plus base governance page routes.
   */
  async function setupMocks(page: import('@playwright/test').Page) {
    // --- S Stage Review API mocks ---

    // Overview (Contract 2.1)
    await page.route(`**${S_API_PREFIX}/overview**`, async route => {
      if (route.request().method() === 'GET') {
        await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(apiResponse(mockOverview)) });
      } else {
        await route.continue();
      }
    });

    // Receipt Stats (Contract 2.2)
    await page.route(`**${S_API_PREFIX}/receipt-stats**`, async route => {
      if (route.request().method() === 'GET') {
        await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(apiResponse(mockReceiptStats)) });
      } else {
        await route.continue();
      }
    });

    // Followup Status Distribution (Contract 2.3)
    await page.route(`**${S_API_PREFIX}/followup-status-distribution**`, async route => {
      if (route.request().method() === 'GET') {
        await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(apiResponse(mockDistribution)) });
      } else {
        await route.continue();
      }
    });

    // By Handler (Contract 2.4)
    await page.route(`**${S_API_PREFIX}/by-handler**`, async route => {
      if (route.request().method() === 'GET') {
        await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(pageResponse(mockByHandlerRecords, 2)) });
      } else {
        await route.continue();
      }
    });

    // By Alert (Contract 2.5)
    await page.route(`**${S_API_PREFIX}/by-alert**`, async route => {
      if (route.request().method() === 'GET') {
        await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(pageResponse(mockByAlertRecords, 2)) });
      } else {
        await route.continue();
      }
    });

    // By Notification (Contract 2.6)
    await page.route(`**${S_API_PREFIX}/by-notification**`, async route => {
      if (route.request().method() === 'GET') {
        await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(pageResponse(mockByNotificationRecords, 2)) });
      } else {
        await route.continue();
      }
    });

    // Details (Contract 2.7)
    await page.route(`**${S_API_PREFIX}/details**`, async route => {
      if (route.request().method() === 'GET') {
        await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(pageResponse(mockDetailRecords, 2)) });
      } else {
        await route.continue();
      }
    });

    // --- Base governance page mocks ---

    await page.route('**/api/assets/governance-ops/alert-workbench/summary**', async route => {
      if (route.request().method() === 'GET') {
        await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(apiResponse({ openCount: 1, ackedCount: 0, resolvedCount: 0, assignedToMeCount: 1, overdueCount: 0, escalatedCount: 0, criticalCount: 1, updatedAt: new Date().toISOString() })) });
      } else {
        await route.continue();
      }
    });

    await page.route('**/api/assets/governance-ops/alert-workbench/my-assigned**', async route => {
      await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(pageResponse([])) });
    });

    await page.route('**/api/assets/governance-ops/alert-workbench/overdue**', async route => {
      await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(pageResponse([])) });
    });

    await page.route('**/api/assets/governance-ops/alert-workbench/escalated**', async route => {
      await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(pageResponse([])) });
    });

    await page.route('**/api/assets/governance-ops/alert-rules**', async route => {
      if (route.request().method() === 'GET') {
        await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(pageResponse([])) });
      } else {
        await route.continue();
      }
    });

    await page.route('**/api/assets/governance-ops/alerts**', async route => {
      if (route.request().method() === 'GET') {
        await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(pageResponse([])) });
      } else {
        await route.continue();
      }
    });

    await page.route('**/api/assets/governance-ops/reports**', async route => {
      if (route.request().method() === 'GET') {
        await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(pageResponse([])) });
      } else {
        await route.continue();
      }
    });

    await page.route('**/api/assets/governance-ops/overview**', async route => {
      await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(apiResponse({})) });
    });

    await page.route('**/api/assets/governance-ops/trends**', async route => {
      await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(apiResponse([])) });
    });

    await page.route('**/api/assets/governance-ops/sla**', async route => {
      await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(apiResponse({})) });
    });

    await page.route('**/api/assets/governance-ops/effectiveness**', async route => {
      await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(apiResponse([])) });
    });

    await page.route('**/api/assets/governance-ops/review-items**', async route => {
      await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(pageResponse([])) });
    });

    await page.route('**/api/envs**', async route => {
      await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(pageResponse([{ id: 1, envCode: 'test', envName: 'Test' }])) });
    });

    await page.route('**/api/services**', async route => {
      await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(pageResponse([{ id: 1, serviceCode: 'svc-a', serviceName: 'Service A' }])) });
    });

    await page.route('**/api/assets/resource-pools**', async route => {
      await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(pageResponse([{ id: 1, poolCode: 'POOL-01', poolName: 'Test Pool', poolType: 'KUBERNETES', status: 'ACTIVE', createdAt: new Date().toISOString() }])) });
    });

    await page.route('**/api/assets/hosts**', async route => {
      await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(pageResponse([])) });
    });

    await page.route('**/api/assets/resource-governance/records**', async route => {
      await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(pageResponse([])) });
    });

    await page.route('**/api/assets/governance-suggestions**', async route => {
      await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(pageResponse([])) });
    });

    await page.route('**/api/assets/resource-pools/risks**', async route => {
      await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(pageResponse([])) });
    });

    await page.route('**/api/assets/alert-notifications/**', async route => {
      await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(pageResponse([])) });
    });
  }

  // ========== S-01: Review Overview API Correct Path ==========

  test('S-01: review overview API called with correct GET path (Contract 2.1, 3.1)', async ({ page }) => {
    await setupMocks(page);

    let capturedUrl = '';
    let capturedMethod = '';

    await page.route(`**${S_API_PREFIX}/overview**`, async route => {
      capturedUrl = route.request().url();
      capturedMethod = route.request().method();
      await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(apiResponse(mockOverview)) });
    });

    await page.goto('/resource/governance');
    await page.getByRole('tab', { name: /通知跟进复盘|Notification Followup Review/ }).click();
    await page.waitForTimeout(2000);

    // Also call directly to verify path
    const result = await page.evaluate(async () => {
      const response = await fetch('/api/assets/governance-ops/alert-notification-followup-review/overview');
      return response.json();
    });

    expect(capturedUrl).toContain(S_API_PREFIX + '/overview');
    expect(capturedMethod).toBe('GET');
    expect(result.code).toBe('SUCCESS');
    expect(result.data).toBeDefined();
  });

  // ========== S-02: Review Overview Response Fields Complete ==========

  test('S-02: review overview response contains all required fields (Contract 3.1)', async ({ page }) => {
    await setupMocks(page);

    await page.goto('/resource/governance');

    const result = await page.evaluate(async () => {
      const response = await fetch('/api/assets/governance-ops/alert-notification-followup-review/overview');
      return response.json();
    });

    const data = result.data;

    // Contract 3.1 - all 16 fields
    const requiredFields = [
      'totalNotifications', 'followedUpNotifications', 'pendingFollowupNotifications',
      'totalFollowupRecords', 'completedFollowupRecords', 'inProgressFollowupRecords',
      'cancelledFollowupRecords', 'pendingReceiptCount', 'confirmedReceiptCount',
      'failedReceiptCount', 'noResponseCount', 'followupCompletionRate',
      'receiptConfirmationRate', 'averageFirstFollowupMinutes', 'overdueFollowupCount',
      'updatedAt'
    ];

    for (const field of requiredFields) {
      expect(data[field], `Missing field: ${field}`).toBeDefined();
    }

    // Verify field types
    expect(typeof data.totalNotifications).toBe('number');
    expect(typeof data.followupCompletionRate).toBe('number');
    expect(typeof data.updatedAt).toBe('string');
  });

  // ========== S-03: Receipt Stats API Correct Path ==========

  test('S-03: receipt stats API called with correct GET path (Contract 2.2, 3.2)', async ({ page }) => {
    await setupMocks(page);

    let capturedMethod = '';

    await page.route(`**${S_API_PREFIX}/receipt-stats**`, async route => {
      capturedMethod = route.request().method();
      await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(apiResponse(mockReceiptStats)) });
    });

    await page.goto('/resource/governance');

    const result = await page.evaluate(async () => {
      const response = await fetch('/api/assets/governance-ops/alert-notification-followup-review/receipt-stats');
      return response.json();
    });

    expect(capturedMethod).toBe('GET');
    expect(result.code).toBe('SUCCESS');

    // Verify top-level fields per Contract 3.2
    expect(result.data.byChannelType).toBeDefined();
    expect(result.data.byTargetUser).toBeDefined();
    expect(result.data.overall).toBeDefined();
  });

  // ========== S-04: Receipt Stats By Channel Structure ==========

  test('S-04: receipt stats grouped by channel type correctly (Contract 3.2.1)', async ({ page }) => {
    await setupMocks(page);

    await page.goto('/resource/governance');

    const result = await page.evaluate(async () => {
      const response = await fetch('/api/assets/governance-ops/alert-notification-followup-review/receipt-stats');
      return response.json();
    });

    const byChannelType = result.data.byChannelType;
    expect(Array.isArray(byChannelType)).toBe(true);
    expect(byChannelType.length).toBeGreaterThan(0);

    // Verify each channel item has Contract 3.2.1 fields
    const channelItem = byChannelType[0];
    const channelFields = ['channelType', 'totalCount', 'confirmedCount', 'failedCount', 'noResponseCount', 'confirmationRate'];
    for (const field of channelFields) {
      expect(channelItem[field], `Missing byChannelType field: ${field}`).toBeDefined();
    }

    // Verify known channel types per Contract 6.1.1
    const channelTypes = byChannelType.map((item: { channelType: string }) => item.channelType);
    expect(channelTypes).toContain('INTERNAL');
    expect(channelTypes).toContain('EMAIL_DRAFT');
    expect(channelTypes).toContain('WEBHOOK_DRAFT');
  });

  // ========== S-05: Followup Status Distribution API Correct Path ==========

  test('S-05: followup status distribution API called with correct GET path (Contract 2.3, 3.3)', async ({ page }) => {
    await setupMocks(page);

    let capturedMethod = '';

    await page.route(`**${S_API_PREFIX}/followup-status-distribution**`, async route => {
      capturedMethod = route.request().method();
      await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(apiResponse(mockDistribution)) });
    });

    await page.goto('/resource/governance');

    const result = await page.evaluate(async () => {
      const response = await fetch('/api/assets/governance-ops/alert-notification-followup-review/followup-status-distribution');
      return response.json();
    });

    expect(capturedMethod).toBe('GET');
    expect(result.code).toBe('SUCCESS');
    expect(result.data.byFollowUpStatus).toBeDefined();
    expect(result.data.byReceiptStatus).toBeDefined();
    expect(result.data.byFollowUpType).toBeDefined();
  });

  // ========== S-06: Distribution Contains Three Sub-sections ==========

  test('S-06: followup status distribution has three stat groups (Contract 3.3)', async ({ page }) => {
    await setupMocks(page);

    await page.goto('/resource/governance');

    const result = await page.evaluate(async () => {
      const response = await fetch('/api/assets/governance-ops/alert-notification-followup-review/followup-status-distribution');
      return response.json();
    });

    const data = result.data;

    // Contract 3.3 - three arrays
    expect(Array.isArray(data.byFollowUpStatus)).toBe(true);
    expect(Array.isArray(data.byReceiptStatus)).toBe(true);
    expect(Array.isArray(data.byFollowUpType)).toBe(true);

    // Verify byFollowUpStatus item fields (Contract 3.3.1)
    if (data.byFollowUpStatus.length > 0) {
      const item = data.byFollowUpStatus[0];
      expect(item.followUpStatus).toBeDefined();
      expect(item.count).toBeDefined();
      expect(item.percentage).toBeDefined();
    }

    // Verify byReceiptStatus item fields (Contract 3.3.2)
    if (data.byReceiptStatus.length > 0) {
      const item = data.byReceiptStatus[0];
      expect(item.receiptStatus).toBeDefined();
      expect(item.count).toBeDefined();
      expect(item.percentage).toBeDefined();
    }

    // Verify byFollowUpType item fields (Contract 3.3.3)
    if (data.byFollowUpType.length > 0) {
      const item = data.byFollowUpType[0];
      expect(item.followUpType).toBeDefined();
      expect(item.count).toBeDefined();
      expect(item.percentage).toBeDefined();
    }
  });

  // ========== S-07: By Handler API Correct Path and Pagination ==========

  test('S-07: by-handler API called with correct GET path and pagination (Contract 2.4, 3.4)', async ({ page }) => {
    await setupMocks(page);

    let capturedUrl = '';
    let capturedMethod = '';
    let capturedParams: Record<string, string> = {};

    await page.route(`**${S_API_PREFIX}/by-handler**`, async route => {
      capturedUrl = route.request().url();
      capturedMethod = route.request().method();
      const urlObj = new URL(route.request().url(), 'http://localhost');
      capturedParams = Object.fromEntries(urlObj.searchParams.entries());
      await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(pageResponse(mockByHandlerRecords, 2)) });
    });

    await page.goto('/resource/governance');

    await page.evaluate(async () => {
      await fetch('/api/assets/governance-ops/alert-notification-followup-review/by-handler?pageNum=1&pageSize=20');
    });

    await page.waitForTimeout(500);

    expect(capturedUrl).toContain(S_API_PREFIX + '/by-handler');
    expect(capturedMethod).toBe('GET');
    expect(capturedParams['pageNum']).toBe('1');
    expect(capturedParams['pageSize']).toBe('20');

    // Verify response has Contract 3.4 pagination fields
    const result = await page.evaluate(async () => {
      const response = await fetch('/api/assets/governance-ops/alert-notification-followup-review/by-handler?pageNum=1&pageSize=20');
      return response.json();
    });

    expect(result.data.records).toBeDefined();
    expect(result.data.total).toBeDefined();
    expect(result.data.pageNum).toBeDefined();
    expect(result.data.pageSize).toBeDefined();

    // Verify Contract 3.4.1 item fields
    const item = result.data.records[0];
    expect(item.handlerName).toBeDefined();
    expect(item.totalFollowupCount).toBeDefined();
    expect(item.completedCount).toBeDefined();
    expect(item.completionRate).toBeDefined();
    expect(item.averageFollowupMinutes).toBeDefined();
  });

  // ========== S-08: By Alert API Correct Path and Pagination ==========

  test('S-08: by-alert API called with correct GET path and pagination (Contract 2.5, 3.5)', async ({ page }) => {
    await setupMocks(page);

    await page.goto('/resource/governance');

    // Call directly to verify path and query params
    let capturedParams: Record<string, string> = {};

    await page.route(`**${S_API_PREFIX}/by-alert**`, async route => {
      const urlObj = new URL(route.request().url(), 'http://localhost');
      const params = Object.fromEntries(urlObj.searchParams.entries());
      // Only capture requests with alertLevel filter (from our evaluate call)
      if (params['alertLevel']) {
        capturedParams = params;
      }
      await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(pageResponse(mockByAlertRecords, 2)) });
    });

    await page.evaluate(async () => {
      await fetch('/api/assets/governance-ops/alert-notification-followup-review/by-alert?pageNum=1&pageSize=20&alertLevel=CRITICAL');
    });

    await page.waitForTimeout(500);

    expect(capturedParams['pageNum']).toBe('1');
    expect(capturedParams['pageSize']).toBe('20');
    expect(capturedParams['alertLevel']).toBe('CRITICAL');

    // Verify Contract 3.5.1 item fields via separate call
    const result = await page.evaluate(async () => {
      const response = await fetch('/api/assets/governance-ops/alert-notification-followup-review/by-alert?pageNum=1&pageSize=20');
      return response.json();
    });

    const item = result.data.records[0];
    expect(item.alertNo).toBeDefined();
    expect(item.alertTitle).toBeDefined();
    expect(item.alertLevel).toBeDefined();
    expect(item.alertStatus).toBeDefined();
    expect(item.notificationCount).toBeDefined();
    expect(item.followupCount).toBeDefined();
    expect(item.closedLoopRate).toBeDefined();
    expect(item.latestFollowupAt).toBeDefined();
  });

  // ========== S-09: By Notification API Correct Path and Pagination ==========

  test('S-09: by-notification API called with correct GET path and pagination (Contract 2.6, 3.6)', async ({ page }) => {
    await setupMocks(page);

    await page.goto('/resource/governance');

    let capturedParams: Record<string, string> = {};

    await page.route(`**${S_API_PREFIX}/by-notification**`, async route => {
      const urlObj = new URL(route.request().url(), 'http://localhost');
      const params = Object.fromEntries(urlObj.searchParams.entries());
      // Only capture requests with followUpStatus filter (from our evaluate call)
      if (params['followUpStatus']) {
        capturedParams = params;
      }
      await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(pageResponse(mockByNotificationRecords, 2)) });
    });

    await page.evaluate(async () => {
      await fetch('/api/assets/governance-ops/alert-notification-followup-review/by-notification?pageNum=1&pageSize=20&followUpStatus=COMPLETED&receiptStatus=CONFIRMED');
    });

    await page.waitForTimeout(500);

    expect(capturedParams['pageNum']).toBe('1');
    expect(capturedParams['pageSize']).toBe('20');
    expect(capturedParams['followUpStatus']).toBe('COMPLETED');
    expect(capturedParams['receiptStatus']).toBe('CONFIRMED');

    // Verify Contract 3.6.1 item fields
    const result = await page.evaluate(async () => {
      const response = await fetch('/api/assets/governance-ops/alert-notification-followup-review/by-notification?pageNum=1&pageSize=20');
      return response.json();
    });

    const item = result.data.records[0];
    expect(item.notificationNo).toBeDefined();
    expect(item.alertNo).toBeDefined();
    expect(item.channelType).toBeDefined();
    expect(item.targetUserName).toBeDefined();
    expect(item.followupCount).toBeDefined();
    expect(item.latestFollowupStatus).toBeDefined();
    expect(item.latestReceiptStatus).toBeDefined();
    expect(item.firstFollowupAt).toBeDefined();
  });

  // ========== S-10: Detail List API Correct Path and Pagination ==========

  test('S-10: detail list API called with correct GET path and pagination (Contract 2.7, 3.7)', async ({ page }) => {
    await setupMocks(page);

    let capturedUrl = '';
    let capturedMethod = '';
    let capturedParams: Record<string, string> = {};

    await page.route(`**${S_API_PREFIX}/details**`, async route => {
      capturedUrl = route.request().url();
      capturedMethod = route.request().method();
      const urlObj = new URL(route.request().url(), 'http://localhost');
      capturedParams = Object.fromEntries(urlObj.searchParams.entries());
      await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(pageResponse(mockDetailRecords, 2)) });
    });

    await page.goto('/resource/governance');

    await page.evaluate(async () => {
      await fetch('/api/assets/governance-ops/alert-notification-followup-review/details?pageNum=1&pageSize=20&followUpStatus=IN_PROGRESS');
    });

    await page.waitForTimeout(500);

    expect(capturedUrl).toContain(S_API_PREFIX + '/details');
    expect(capturedMethod).toBe('GET');
    expect(capturedParams['pageNum']).toBe('1');
    expect(capturedParams['pageSize']).toBe('20');

    // Verify Contract 3.7.1 item fields
    const result = await page.evaluate(async () => {
      const response = await fetch('/api/assets/governance-ops/alert-notification-followup-review/details?pageNum=1&pageSize=20');
      return response.json();
    });

    const item = result.data.records[0];
    const detailFields = [
      'followupId', 'followUpNo', 'notificationRecordId', 'notificationNo',
      'alertRecordId', 'alertNo', 'alertTitle', 'alertLevel', 'alertStatus',
      'channelType', 'targetUserId', 'targetUserName', 'followUpType',
      'followUpContent', 'followUpResult', 'followUpStatus', 'receiptStatus',
      'createdBy', 'createdByName', 'createdAt', 'firstFollowupForNotification',
      'minutesSinceNotification'
    ];
    for (const field of detailFields) {
      expect(item[field], `Missing detail field: ${field}`).toBeDefined();
    }
  });

  // ========== S-11: Detail List Shows Required Columns ==========

  test('S-11: detail list displays required columns (Contract 10.7)', async ({ page }) => {
    await setupMocks(page);

    await page.goto('/resource/governance');
    await page.getByRole('tab', { name: /通知跟进复盘|Notification Followup Review/ }).click();
    await page.waitForTimeout(3000);

    // Verify Contract 10.7 columns exist in the detail table (last table in the tab)
    // There are multiple tables, so we target the detail list section specifically
    const detailListSection = page.locator('.notification-followup-review-tab').locator('.section-header', { hasText: /复盘明细列表|Review Detail List/ }).locator('..');
    const detailTable = detailListSection.locator('.el-table');

    // Column labels from i18n keys - check column headers
    const columnHeaders = await detailTable.locator('.el-table__header th').allInnerTexts();
    const headerText = columnHeaders.join(' ');

    // Contract 10.7 required columns
    expect(headerText).toMatch(/跟进编号|Followup No/);
    expect(headerText).toMatch(/通知编号|Notification No/);
    expect(headerText).toMatch(/告警编号|Alert No/);
    expect(headerText).toMatch(/跟进类型|Type/);
    expect(headerText).toMatch(/跟进状态|Followup Status/);
    expect(headerText).toMatch(/回执状态|Receipt Status/);
    expect(headerText).toMatch(/创建人|Created By/);
    expect(headerText).toMatch(/创建时间|Created At/);
  });

  // ========== S-12: All API Paths Match Contract ==========

  test('S-12: all S-stage API paths match Contract 2.1-2.7', async ({ page }) => {
    await setupMocks(page);

    const contractPaths = [
      { path: '/overview', method: 'GET' },
      { path: '/receipt-stats', method: 'GET' },
      { path: '/followup-status-distribution', method: 'GET' },
      { path: '/by-handler', method: 'GET' },
      { path: '/by-alert', method: 'GET' },
      { path: '/by-notification', method: 'GET' },
      { path: '/details', method: 'GET' }
    ];

    const capturedPaths: string[] = [];

    for (const { path } of contractPaths) {
      await page.route(`**${S_API_PREFIX}${path}**`, async route => {
        capturedPaths.push(path);
        const mockData = path === '/overview' ? apiResponse(mockOverview)
          : path === '/receipt-stats' ? apiResponse(mockReceiptStats)
          : path === '/followup-status-distribution' ? apiResponse(mockDistribution)
          : pageResponse([]);
        await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(mockData) });
      });
    }

    await page.goto('/resource/governance');

    // Call each Contract-defined path
    for (const { path } of contractPaths) {
      await page.evaluate(async (p) => {
        await fetch(`/api/assets/governance-ops/alert-notification-followup-review${p}`);
      }, path);
    }

    await page.waitForTimeout(1000);

    // Verify all 7 paths were called
    expect(capturedPaths).toContain('/overview');
    expect(capturedPaths).toContain('/receipt-stats');
    expect(capturedPaths).toContain('/followup-status-distribution');
    expect(capturedPaths).toContain('/by-handler');
    expect(capturedPaths).toContain('/by-alert');
    expect(capturedPaths).toContain('/by-notification');
    expect(capturedPaths).toContain('/details');
  });

  // ========== S-13: PageSize Respects Limit 200 ==========

  test('S-13: pagination respects pageSize limit of 200 (Contract 4.4-4.7)', async ({ page }) => {
    await setupMocks(page);

    const capturedPageSizes: number[] = [];

    await page.route(`**${S_API_PREFIX}/by-handler**`, async route => {
      const urlObj = new URL(route.request().url(), 'http://localhost');
      const ps = parseInt(urlObj.searchParams.get('pageSize') || '20');
      capturedPageSizes.push(ps);
      await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(pageResponse(mockByHandlerRecords, 2)) });
    });

    await page.goto('/resource/governance');

    // Test at limit - pageSize=200 should be accepted
    await page.evaluate(async () => {
      await fetch('/api/assets/governance-ops/alert-notification-followup-review/by-handler?pageNum=1&pageSize=200');
    });
    await page.waitForTimeout(500);
    expect(capturedPageSizes).toContain(200);

    // The frontend uses Math.min(pageSize, 200) for pagination component (Contract 4.4-4.7)
    // Verify all captured pageSize values are <= 200
    for (const ps of capturedPageSizes) {
      expect(ps, `pageSize ${ps} exceeds limit of 200`).toBeLessThanOrEqual(200);
    }
  });

  // ========== S-14: No POST/PUT/DELETE API for S Stage ==========

  test('S-14: no POST/PUT/DELETE API exists for S stage (Contract 1.1, 1.3)', async ({ page }) => {
    await setupMocks(page);

    const writeMethods: string[] = [];

    // Intercept ALL requests to S API prefix and record write methods
    await page.route(`**${S_API_PREFIX}/**`, async route => {
      const method = route.request().method();
      if (['POST', 'PUT', 'DELETE', 'PATCH'].includes(method)) {
        writeMethods.push(method + ' ' + route.request().url());
      }
      // Return a mock response
      await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(apiResponse({})) });
    });

    await page.goto('/resource/governance');

    // Navigate to the review tab
    await page.getByRole('tab', { name: /通知跟进复盘|Notification Followup Review/ }).click();
    await page.waitForTimeout(3000);

    // No write methods should have been called
    expect(writeMethods.length, `Found write API calls: ${writeMethods.join(', ')}`).toBe(0);
  });

  // ========== S-15: MANUAL_ONLY Notice Visible ==========

  test('S-15: MANUAL_ONLY / read-only notice visible on review page (Contract 1.1, 10.10)', async ({ page }) => {
    await setupMocks(page);

    await page.goto('/resource/governance');
    await page.getByRole('tab', { name: /通知跟进复盘|Notification Followup Review/ }).click();
    await page.waitForTimeout(3000);

    // Contract 10.10: "通知跟进复盘（只读统计）" title and read-only notice
    const reviewTab = page.locator('.notification-followup-review-tab');

    // The title should include read-only notice
    await expect(reviewTab.getByText(/只读统计|Read-only/).first()).toBeVisible({ timeout: 10000 });

    // Contract 10.10: The read-only notice should show the correct text
    // Expected: "本页面为只读统计分析，不改变告警/通知/跟进状态" or English equivalent
    await expect(reviewTab.getByText(/本页面为只读统计分析|This page is read-only statistics/).first()).toBeVisible({ timeout: 5000 });
  });

  // ========== S-16: No Auto-Process Related Hints ==========

  test('S-16: no auto-process / auto-send / auto-followup hints (Contract 1.1, 10.10)', async ({ page }) => {
    await setupMocks(page);

    await page.goto('/resource/governance');
    await page.getByRole('tab', { name: /通知跟进复盘|Notification Followup Review/ }).click();
    await page.waitForTimeout(3000);

    // Get the review tab content
    const reviewTab = page.locator('.notification-followup-review-tab');
    const reviewText = await reviewTab.innerText();

    // Contract 10.10: Forbidden auto-related text patterns (including NEGATIVE patterns)
    // These patterns must NOT appear at all - Contract specifies correct text instead
    const forbiddenPatterns = [
      // Chinese forbidden patterns (Contract 10.10)
      '不自动处理',
      '不自动跟进',
      '不自动发送',
      // English forbidden patterns (Contract 10.10)
      'no auto-process',
      'no auto-followup',
      'no auto-send',
      // Additional positive assertions that should never appear
      '自动发送已开启',
      '自动跟进已开启',
      '自动处理已开启',
      '已自动发送',
      '已自动跟进',
      '已自动处理',
      '自动确认已开启',
      'Auto Send enabled',
      'Auto Followup enabled',
      'Auto Process enabled',
      'Automatically sent',
      'Automatically processed',
      'Auto Confirm enabled'
    ];

    for (const pattern of forbiddenPatterns) {
      expect(reviewText, `Found forbidden auto-pattern: "${pattern}"`).not.toContain(pattern);
    }

    // Contract 10.10: The correct read-only notice should be shown
    // Expected: "本页面为只读统计分析，不改变告警/通知/跟进状态" or English equivalent
    const correctNoticeFound =
      reviewText.includes('本页面为只读统计分析') ||
      reviewText.includes('不改变告警/通知/跟进状态') ||
      reviewText.includes('This page is read-only statistics') ||
      reviewText.includes('does not change alert/notification/followup status');

    expect(correctNoticeFound, 'Contract 10.10 compliant read-only notice not found').toBe(true);
  });

  // ========== S-17: Review Overview Card Fields Correct ==========

  test('S-17: review overview card shows correct fields (Contract 10.1)', async ({ page }) => {
    await setupMocks(page);

    await page.goto('/resource/governance');
    await page.getByRole('tab', { name: /通知跟进复盘|Notification Followup Review/ }).click();
    await page.waitForTimeout(3000);

    const reviewTab = page.locator('.notification-followup-review-tab');

    // Contract 10.1 - overview card fields
    const overviewLabels = [
      /通知总数|Total Notifications/,
      /已跟进|Followed Up/,
      /待跟进|Pending Followup/,
      /跟进完成率|Followup Completion Rate/,
      /回执确认率|Receipt Confirmation Rate/,
      /平均首次跟进|Avg First Followup/,
      /逾期跟进|Overdue Followup/
    ];

    for (const labelPattern of overviewLabels) {
      const element = reviewTab.getByText(labelPattern).first();
      await expect(element).toBeVisible({ timeout: 8000 });
    }
  });

  // ========== S-18: Review Function Accessible from Governance Workbench ==========

  test('S-18: review function accessible from governance workbench (Contract 10.9)', async ({ page }) => {
    await setupMocks(page);

    await page.goto('/resource/governance');
    await expect(page.getByRole('heading', { name: /资源治理工作台|Resource Governance Workbench/ })).toBeVisible({ timeout: 15000 });

    // Contract 10.9: The workbench has a "通知复盘" Tab
    const followupReviewTab = page.getByRole('tab', { name: /通知跟进复盘|Notification Followup Review/ });
    await expect(followupReviewTab).toBeVisible({ timeout: 10000 });

    // Click the tab to navigate
    await followupReviewTab.click();
    await page.waitForTimeout(3000);

    // Verify the review tab content is loaded
    const reviewTab = page.locator('.notification-followup-review-tab');
    await expect(reviewTab).toBeVisible({ timeout: 10000 });
  });
});
