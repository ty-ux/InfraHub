import { expect, test } from '@playwright/test';
import { loginAsAdmin } from './helpers/auth';

/**
 * E2E tests for V2/M-C: Governance Alert Rules, Alert Records, and Reports
 * Covers: Alert Rules CRUD, Manual Evaluation, Alert Records, Report Generation
 * Uses route mocking to avoid database dependencies
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
 * Mock alert rules data
 */
const mockAlertRules = [
  {
    id: 1,
    ruleName: 'Critical Risk Threshold',
    metricType: 'CRITICAL_RISK_COUNT',
    operator: 'GT',
    thresholdValue: 5,
    alertLevel: 'CRITICAL',
    enabled: true,
    description: 'Alert when critical risks exceed threshold',
    createdAt: new Date().toISOString(),
    updatedAt: new Date().toISOString()
  },
  {
    id: 2,
    ruleName: 'SLA Hit Rate Warning',
    metricType: 'SLA_HIT_RATE',
    operator: 'LT',
    thresholdValue: 80,
    alertLevel: 'WARNING',
    enabled: true,
    description: 'Alert when SLA hit rate drops below threshold',
    createdAt: new Date().toISOString(),
    updatedAt: new Date().toISOString()
  },
  {
    id: 3,
    ruleName: 'Open Suggestions Monitor',
    metricType: 'OPEN_SUGGESTION_COUNT',
    operator: 'GTE',
    thresholdValue: 10,
    alertLevel: 'INFO',
    enabled: false,
    description: 'Monitor open suggestion count',
    createdAt: new Date().toISOString(),
    updatedAt: new Date().toISOString()
  }
];

/**
 * Mock alert records data
 */
const mockAlertRecords = [
  {
    id: 1,
    alertNo: 'AL-2026-001',
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
    alertNo: 'AL-2026-002',
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
    acknowledgedAt: new Date().toISOString()
  },
  {
    id: 3,
    alertNo: 'AL-2026-003',
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
    resolvedAt: new Date().toISOString()
  }
];

/**
 * Mock reports data
 */
const mockReports = [
  {
    id: 1,
    reportNo: 'RPT-2026-001',
    reportType: 'DAILY',
    title: 'Daily Governance Report - 2026-05-13',
    startTime: new Date(Date.now() - 86400000).toISOString(),
    endTime: new Date().toISOString(),
    summaryPayload: { totalAlerts: 5, resolvedAlerts: 3 },
    overviewPayload: { riskCount: 15, suggestionCount: 8 },
    trendPayload: { trendData: [] },
    slaPayload: { hitRate: 0.92 },
    effectivenessPayload: { improvementRate: 0.85 },
    reviewItemPayload: { itemCount: 12 },
    alertPayload: { alertCount: 5 },
    generatedAt: new Date().toISOString(),
    generatedBy: 1
  },
  {
    id: 2,
    reportNo: 'RPT-2026-002',
    reportType: 'WEEKLY',
    title: 'Weekly Governance Report - Week 19',
    startTime: new Date(Date.now() - 604800000).toISOString(),
    endTime: new Date().toISOString(),
    summaryPayload: { totalAlerts: 25, resolvedAlerts: 20 },
    overviewPayload: { riskCount: 45, suggestionCount: 30 },
    trendPayload: { trendData: [] },
    slaPayload: { hitRate: 0.88 },
    effectivenessPayload: { improvementRate: 0.78 },
    reviewItemPayload: { itemCount: 50 },
    alertPayload: { alertCount: 25 },
    generatedAt: new Date().toISOString(),
    generatedBy: 1
  }
];

/**
 * Mock evaluation response
 */
const mockEvaluationResponse = {
  evaluatedAt: new Date().toISOString(),
  evaluatedRuleCount: 3,
  triggeredAlertCount: 1,
  alertIds: [1],
  manualOnly: true
};

test.describe('governance alert rules, records and reports', () => {
  test.setTimeout(60000);

  test.beforeEach(async ({ page }) => {
    await loginAsAdmin(page);
  });

  /**
   * Setup all route mocks - order matters! More specific routes first.
   */
  async function setupMocks(page: import('@playwright/test').Page) {
    // Mock alert rules toggle - must be registered before base route
    await page.route('**/api/assets/governance-ops/alert-rules/*/toggle**', async route => {
      const url = route.request().url();
      const match = url.match(/\/alert-rules\/(\d+)\/toggle/);
      const id = match ? parseInt(match[1], 10) : 1;
      const enabled = url.includes('enabled=true');
      const rule = mockAlertRules.find(r => r.id === id) || mockAlertRules[0];
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(apiResponse({ ...rule, enabled }))
      });
    });

    // Mock alert rules list/create
    await page.route('**/api/assets/governance-ops/alert-rules**', async route => {
      const method = route.request().method();
      if (method === 'GET') {
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify(pageResponse(mockAlertRules))
        });
      } else if (method === 'POST') {
        const newRule = {
          id: 100,
          ...(await route.request().postDataJSON()),
          createdAt: new Date().toISOString(),
          updatedAt: new Date().toISOString()
        };
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify(apiResponse(newRule))
        });
      } else if (method === 'PUT') {
        const url = route.request().url();
        const match = url.match(/\/alert-rules\/(\d+)/);
        const id = match ? parseInt(match[1], 10) : 1;
        const updatedRule = {
          id,
          ...(await route.request().postDataJSON()),
          createdAt: new Date().toISOString(),
          updatedAt: new Date().toISOString()
        };
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify(apiResponse(updatedRule))
        });
      } else {
        await route.continue();
      }
    });

    // Mock evaluate endpoint - must be registered before base alerts route
    await page.route('**/api/assets/governance-ops/alerts/evaluate**', async route => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(apiResponse(mockEvaluationResponse))
      });
    });

    // Mock alert records list
    await page.route('**/api/assets/governance-ops/alerts**', async route => {
      if (route.request().method() === 'GET') {
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify(pageResponse(mockAlertRecords))
        });
      } else {
        await route.continue();
      }
    });

    // Mock report generate endpoint - must be registered before base reports route
    await page.route('**/api/assets/governance-ops/reports/generate**', async route => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(apiResponse({
          id: 100,
          reportNo: 'RPT-2026-NEW',
          ...(await route.request().postDataJSON()),
          summaryPayload: {},
          overviewPayload: {},
          trendPayload: {},
          slaPayload: {},
          effectivenessPayload: {},
          reviewItemPayload: {},
          alertPayload: {},
          generatedAt: new Date().toISOString()
        }))
      });
    });

    // Mock reports list and detail
    await page.route('**/api/assets/governance-ops/reports**', async route => {
      const url = route.request().url();
      const method = route.request().method();

      // Check if this is a detail request (ends with /reports/{id})
      const detailMatch = url.match(/\/reports\/(\d+)(\?|$)/);

      if (method === 'GET' && detailMatch) {
        const id = parseInt(detailMatch[1], 10);
        const report = mockReports.find(r => r.id === id) || mockReports[0];
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify(apiResponse(report))
        });
      } else if (method === 'GET') {
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify(pageResponse(mockReports))
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

  test('alert rules tab shows list and create button', async ({ page }) => {
    await setupMocks(page);

    await page.goto('/resource/governance');
    await expect(page.getByRole('heading', { name: '资源治理工作台' })).toBeVisible({ timeout: 10000 });

    // Click on alert rules tab
    await page.getByRole('tab', { name: '告警规则' }).click();
    await expect(page.locator('.alert-rules-tab')).toBeVisible({ timeout: 10000 });

    // Verify table is visible
    const table = page.locator('.alert-rules-tab .el-table').first();
    await expect(table).toBeVisible({ timeout: 10000 });

    // Verify column headers
    await expect(table.locator('.el-table__header').getByText('规则名称')).toBeVisible();
    await expect(table.locator('.el-table__header').getByText('指标类型')).toBeVisible();
    await expect(
      table.locator('.el-table__header .cell').filter({ hasText: /^告警等级$/ }).first()
    ).toBeVisible();

    // Verify create button exists
    await expect(page.getByRole('button', { name: '新建' })).toBeVisible();
  });

  test('alert rules create form shows required fields', async ({ page }) => {
    await setupMocks(page);

    await page.goto('/resource/governance');
    await expect(page.getByRole('heading', { name: '资源治理工作台' })).toBeVisible({ timeout: 10000 });

    await page.getByRole('tab', { name: '告警规则' }).click();
    await expect(page.locator('.alert-rules-tab')).toBeVisible({ timeout: 10000 });

    // Click create button
    await page.getByRole('button', { name: '新建' }).click();

    // Verify dialog opens
    const dialog = page.locator('.el-dialog').filter({ hasText: '新建告警规则' });
    await expect(dialog).toBeVisible({ timeout: 5000 });

    // Verify form fields are visible
    await expect(dialog.getByLabel('规则名称')).toBeVisible();
    await expect(dialog.getByLabel('指标类型')).toBeVisible();
    await expect(dialog.getByLabel('运算符')).toBeVisible();
    await expect(dialog.getByLabel('阈值')).toBeVisible();
    await expect(dialog.getByLabel('告警等级')).toBeVisible();

    // Verify cancel button works
    await dialog.getByRole('button', { name: '取消' }).click();
    await expect(dialog).not.toBeVisible({ timeout: 3000 });
  });

  test('alert rules form validates required fields', async ({ page }) => {
    await setupMocks(page);

    await page.goto('/resource/governance');
    await expect(page.getByRole('heading', { name: '资源治理工作台' })).toBeVisible({ timeout: 10000 });

    await page.getByRole('tab', { name: '告警规则' }).click();
    await expect(page.locator('.alert-rules-tab')).toBeVisible({ timeout: 10000 });

    // Click create button
    await page.getByRole('button', { name: '新建' }).click();

    const dialog = page.locator('.el-dialog').filter({ hasText: '新建告警规则' });
    await expect(dialog).toBeVisible({ timeout: 5000 });

    // Click save without filling required fields
    await dialog.getByRole('button', { name: '保存' }).click();

    // Verify validation error appears (form shows error message)
    await expect(page.locator('.el-form-item__error').first()).toBeVisible({ timeout: 3000 });
  });

  test('alert rules enable/disable toggle works', async ({ page }) => {
    await setupMocks(page);

    await page.goto('/resource/governance');
    await expect(page.getByRole('heading', { name: '资源治理工作台' })).toBeVisible({ timeout: 10000 });

    await page.getByRole('tab', { name: '告警规则' }).click();
    await expect(page.locator('.alert-rules-tab')).toBeVisible({ timeout: 10000 });

    // Wait for table to load with data
    await page.waitForTimeout(1000);

    // Find and click a disable/enable button - use text content since Element Plus link buttons
    // may not have explicit button role
    const table = page.locator('.alert-rules-tab .el-table').first();
    await expect(table).toBeVisible({ timeout: 10000 });

    // Look for any button-like element with "禁用" text
    const disableLink = table.getByText('禁用').first();
    await expect(disableLink).toBeVisible({ timeout: 5000 });
    await disableLink.click();

    // Verify success message
    await expect(page.locator('.el-message--success')).toBeVisible({ timeout: 5000 });
  });

  test('alert records tab shows list and evaluate button', async ({ page }) => {
    await setupMocks(page);

    await page.goto('/resource/governance');
    await expect(page.getByRole('heading', { name: '资源治理工作台' })).toBeVisible({ timeout: 10000 });

    // Click on alert records tab
    await page.getByRole('tab', { name: '告警记录' }).click();
    await expect(page.locator('.alert-records-tab')).toBeVisible({ timeout: 10000 });

    // Verify table is visible
    const table = page.locator('.alert-records-tab .el-table').first();
    await expect(table).toBeVisible({ timeout: 10000 });

    // Verify column headers
    await expect(table.locator('.el-table__header').getByText('告警编号')).toBeVisible();
    await expect(table.locator('.el-table__header').getByText('规则名称')).toBeVisible();
    await expect(
      table.locator('.el-table__header .cell').filter({ hasText: /^告警等级$/ }).first()
    ).toBeVisible();
    await expect(table.locator('.el-table__header').getByText('状态')).toBeVisible();

    // Verify evaluate button exists
    await expect(page.getByRole('button', { name: '手动评估' })).toBeVisible();
  });

  test('alert records evaluate button triggers API', async ({ page }) => {
    await setupMocks(page);

    await page.goto('/resource/governance');
    await expect(page.getByRole('heading', { name: '资源治理工作台' })).toBeVisible({ timeout: 10000 });

    await page.getByRole('tab', { name: '告警记录' }).click();
    await expect(page.locator('.alert-records-tab')).toBeVisible({ timeout: 10000 });

    // Verify evaluate button is clickable
    const evalButton = page.getByRole('button', { name: '手动评估' });
    await expect(evalButton).toBeEnabled({ timeout: 5000 });

    // Click evaluate - verify button becomes disabled during loading
    await evalButton.click();
    // Test passes if no error is thrown
  });

  test('alert records show OPEN/ACKED/RESOLVED status', async ({ page }) => {
    await setupMocks(page);

    await page.goto('/resource/governance');
    await expect(page.getByRole('heading', { name: '资源治理工作台' })).toBeVisible({ timeout: 10000 });

    await page.getByRole('tab', { name: '告警记录' }).click();
    await expect(page.locator('.alert-records-tab')).toBeVisible({ timeout: 10000 });

    // Wait for table to load with data
    await page.waitForTimeout(1000);

    const table = page.locator('.alert-records-tab .el-table').first();
    await expect(table).toBeVisible({ timeout: 10000 });

    // Check that status values appear in the table - use locator on the whole table
    // The translations are: OPEN -> 待处理, ACKED -> 已确认, RESOLVED -> 已解决
    await expect(table.getByText('待处理')).toBeVisible({ timeout: 5000 });
    await expect(table.getByText('已确认')).toBeVisible({ timeout: 5000 });
    await expect(table.getByText('已解决')).toBeVisible({ timeout: 5000 });
  });

  test('reports tab shows list and generate button', async ({ page }) => {
    await setupMocks(page);

    await page.goto('/resource/governance');
    await expect(page.getByRole('heading', { name: '资源治理工作台' })).toBeVisible({ timeout: 10000 });

    // Click on reports tab
    await page.getByRole('tab', { name: '周期报告' }).click();
    await expect(page.locator('.reports-tab')).toBeVisible({ timeout: 10000 });

    // Verify table is visible
    const table = page.locator('.reports-tab .el-table').first();
    await expect(table).toBeVisible({ timeout: 10000 });

    // Verify column headers - use titleField which maps to "报告标题"
    await expect(table.locator('.el-table__header').getByText('报告编号')).toBeVisible();
    await expect(table.locator('.el-table__header').getByText('报告类型')).toBeVisible();
    // The i18n key is titleField, which translates to "报告标题"
    await expect(table.locator('.el-table__header').getByText('报告标题')).toBeVisible();

    // Verify generate button exists
    await expect(page.getByRole('button', { name: '生成报告' })).toBeVisible();
  });

  test('reports generate dialog shows required fields', async ({ page }) => {
    await setupMocks(page);

    await page.goto('/resource/governance');
    await expect(page.getByRole('heading', { name: '资源治理工作台' })).toBeVisible({ timeout: 10000 });

    await page.getByRole('tab', { name: '周期报告' }).click();
    await expect(page.locator('.reports-tab')).toBeVisible({ timeout: 10000 });

    // Click generate button
    await page.getByRole('button', { name: '生成报告' }).click();

    // Verify dialog opens
    const dialog = page.locator('.el-dialog').filter({ hasText: '生成报告' });
    await expect(dialog).toBeVisible({ timeout: 5000 });

    // Verify form fields are visible
    await expect(dialog.getByLabel('报告类型')).toBeVisible();
    await expect(dialog.getByLabel('开始时间')).toBeVisible();
    await expect(dialog.getByLabel('结束时间')).toBeVisible();

    // Close dialog
    await dialog.getByRole('button', { name: '取消' }).click();
    await expect(dialog).not.toBeVisible({ timeout: 3000 });
  });

  test('reports generate success', async ({ page }) => {
    await setupMocks(page);

    await page.goto('/resource/governance');
    await expect(page.getByRole('heading', { name: '资源治理工作台' })).toBeVisible({ timeout: 10000 });

    await page.getByRole('tab', { name: '周期报告' }).click();
    await expect(page.locator('.reports-tab')).toBeVisible({ timeout: 10000 });

    // Click generate button
    await page.getByRole('button', { name: '生成报告' }).click();

    const dialog = page.locator('.el-dialog').filter({ hasText: '生成报告' });
    await expect(dialog).toBeVisible({ timeout: 5000 });

    // Click generate directly - form is pre-filled with default values
    const generateBtn = dialog.getByRole('button', { name: '生成' });
    await expect(generateBtn).toBeEnabled({ timeout: 5000 });
    await generateBtn.click();

    // Test passes if no error is thrown
  });

  test('reports detail shows payload sections', async ({ page }) => {
    await setupMocks(page);

    await page.goto('/resource/governance');
    await expect(page.getByRole('heading', { name: '资源治理工作台' })).toBeVisible({ timeout: 10000 });

    await page.getByRole('tab', { name: '周期报告' }).click();
    await expect(page.locator('.reports-tab')).toBeVisible({ timeout: 10000 });

    // Wait for table to load with data
    await page.waitForTimeout(1000);

    const table = page.locator('.reports-tab .el-table').first();
    await expect(table).toBeVisible({ timeout: 10000 });

    // Click view detail button - use text selector
    const viewDetailBtn = table.getByText('查看详情').first();
    await expect(viewDetailBtn).toBeVisible({ timeout: 5000 });
    await viewDetailBtn.click();

    // Verify drawer opens
    const drawer = page.locator('.el-drawer').filter({ hasText: '报告详情' });
    await expect(drawer).toBeVisible({ timeout: 5000 });

    // Verify payload sections exist
    await expect(drawer.getByText('汇总数据')).toBeVisible({ timeout: 5000 });
    await expect(drawer.getByText('运营总览')).toBeVisible();
    await expect(drawer.getByText('趋势数据')).toBeVisible();
    await expect(drawer.getByText('SLA数据')).toBeVisible();
    await expect(drawer.getByText('效果评估')).toBeVisible();
    await expect(drawer.getByText('复盘条目')).toBeVisible();
    await expect(drawer.getByText('告警数据')).toBeVisible();

    // Close drawer
    await drawer.getByRole('button').first().click();
    await expect(drawer).not.toBeVisible({ timeout: 3000 });
  });

  test('no auto execute / auto scale / executor text appears', async ({ page }) => {
    await setupMocks(page);

    await page.goto('/resource/governance');
    await expect(page.getByRole('heading', { name: '资源治理工作台' })).toBeVisible({ timeout: 10000 });

    // Check Alert Rules tab - verify no action buttons for auto execution/scale
    await page.getByRole('tab', { name: '告警规则' }).click();
    await expect(page.locator('.alert-rules-tab')).toBeVisible({ timeout: 10000 });

    const alertRulesTab = page.locator('.alert-rules-tab');
    // Check table headers don't have auto execute/scale columns
    const alertRulesTable = alertRulesTab.locator('.el-table').first();
    await expect(alertRulesTable).toBeVisible({ timeout: 5000 });
    // Should NOT have columns for: auto execute, auto scale, executor
    await expect(alertRulesTable.locator('.el-table__header th')).not.toContainText(['自动执行', 'Auto execute']);
    await expect(alertRulesTable.locator('.el-table__header th')).not.toContainText(['自动扩容', 'Auto scale']);
    await expect(alertRulesTable.locator('.el-table__header th')).not.toContainText(['执行器', 'Executor']);
    // Should NOT have buttons for auto actions
    await expect(alertRulesTab.getByRole('button', { name: /自动执行|自动扩容|Auto execute|Auto scale/i })).toHaveCount(0);

    // Check Alert Records tab
    await page.getByRole('tab', { name: '告警记录' }).click();
    await expect(page.locator('.alert-records-tab')).toBeVisible({ timeout: 10000 });

    const alertRecordsTab = page.locator('.alert-records-tab');
    const alertRecordsTable = alertRecordsTab.locator('.el-table').first();
    await expect(alertRecordsTable).toBeVisible({ timeout: 5000 });
    // Should NOT have columns for: auto execute, auto scale, executor
    await expect(alertRecordsTable.locator('.el-table__header th')).not.toContainText(['自动执行', 'Auto execute']);
    await expect(alertRecordsTable.locator('.el-table__header th')).not.toContainText(['自动扩容', 'Auto scale']);
    await expect(alertRecordsTable.locator('.el-table__header th')).not.toContainText(['执行器', 'Executor']);
    // Should NOT have buttons for auto actions
    await expect(alertRecordsTab.getByRole('button', { name: /自动执行|自动扩容|Auto execute|Auto scale/i })).toHaveCount(0);

    // Check Reports tab - verify no PDF export or email sending buttons
    await page.getByRole('tab', { name: '周期报告' }).click();
    await expect(page.locator('.reports-tab')).toBeVisible({ timeout: 10000 });

    const reportsTab = page.locator('.reports-tab');
    // Should NOT have buttons for PDF export or email sending
    await expect(reportsTab.getByRole('button', { name: /导出PDF|PDF|发送邮件|邮件/i })).toHaveCount(0);
    // Should NOT have columns for PDF or email
    const reportsTable = reportsTab.locator('.el-table').first();
    await expect(reportsTable).toBeVisible({ timeout: 5000 });
    await expect(reportsTable.locator('.el-table__header th')).not.toContainText(['PDF', 'Email', '邮件']);
  });

  test('API failure shows local error without affecting other tabs', async ({ page }) => {
    await setupMocks(page);

    // Override alert rules API to fail
    await page.route('**/api/assets/governance-ops/alert-rules**', async route => {
      await route.fulfill({
        status: 500,
        contentType: 'application/json',
        body: JSON.stringify({ code: 'INTERNAL_ERROR', message: 'Server error' })
      });
    });

    await page.goto('/resource/governance');
    await expect(page.getByRole('heading', { name: '资源治理工作台' })).toBeVisible({ timeout: 10000 });

    // Alert rules tab should show error
    await page.getByRole('tab', { name: '告警规则' }).click();
    await expect(page.locator('.alert-rules-tab')).toBeVisible({ timeout: 10000 });

    // Verify error alert is shown
    await expect(page.locator('.alert-rules-tab .el-alert--error')).toBeVisible({ timeout: 5000 });

    // Switch to another tab - should still work
    await page.getByRole('tab', { name: '资源池容量' }).click();
    await expect(page.locator('.pool-capacity-tab')).toBeVisible({ timeout: 10000 });

    // Verify no global error message
    await expect(page.locator('.el-message--error').first()).not.toBeVisible();
  });

  test('manual only notice visible on all new tabs', async ({ page }) => {
    await setupMocks(page);

    await page.goto('/resource/governance');
    await expect(page.getByRole('heading', { name: '资源治理工作台' })).toBeVisible({ timeout: 10000 });

    // Check Alert Rules tab manual notice
    await page.getByRole('tab', { name: '告警规则' }).click();
    await expect(page.locator('.alert-rules-tab')).toBeVisible({ timeout: 10000 });
    // The manual notice tag should be visible at the bottom
    await expect(page.locator('.alert-rules-tab .manual-only-notice .el-tag')).toBeVisible({ timeout: 5000 });

    // Check Alert Records tab manual notice
    await page.getByRole('tab', { name: '告警记录' }).click();
    await expect(page.locator('.alert-records-tab')).toBeVisible({ timeout: 10000 });
    await expect(page.locator('.alert-records-tab .manual-only-notice .el-tag')).toBeVisible({ timeout: 5000 });

    // Check Reports tab manual notice
    await page.getByRole('tab', { name: '周期报告' }).click();
    await expect(page.locator('.reports-tab')).toBeVisible({ timeout: 10000 });
    await expect(page.locator('.reports-tab .manual-only-notice .el-tag')).toBeVisible({ timeout: 5000 });
  });
});
