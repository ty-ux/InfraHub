import { expect, test, type Page } from '@playwright/test';
import { loginAsAdmin } from './helpers/auth';
import {
  activeDialog,
  chooseFirstSelectOption,
  chooseSelectOption,
  fillTextField,
  openCreateDialog,
  openPage,
  saveDialog,
  tableRow,
} from './helpers/ui';

const TEST_POLICY_PREFIXES = ['阻断策略-', '审批策略-', '前置检查策略-', '证据链策略-', '总链路策略-'];

async function getAuthHeaders(page: Page) {
  const token = await page.evaluate(() => window.localStorage.getItem('INFRAHUB_TOKEN'));
  expect(token).toBeTruthy();
  return { Authorization: `Bearer ${token}` };
}

async function findRowAcrossPages(page: Page, text: string) {
  for (let pageNum = 1; pageNum <= 100; pageNum += 1) {
    const pageInput = page.getByRole('spinbutton', { name: 'Page' }).first();
    if ((await pageInput.count()) > 0) {
      await pageInput.fill(String(pageNum));
      await pageInput.press('Enter');
      await page.waitForTimeout(300);
    }
    const row = tableRow(page, text);
    if (await row.count() > 0 && await row.isVisible()) {
      return row;
    }
    const activePage = page.locator('.el-pager li.is-active').first();
    const activeText = ((await activePage.textContent().catch(() => '')) || '').trim();
    if (activeText && Number(activeText) < pageNum) {
      break;
    }
  }
  return tableRow(page, text);
}

async function createAdmissionPolicy(page: Page, envId: number, policyName: string, options: {
  blockEnabled?: boolean;
  approvalRequired?: boolean;
  requiredChecks?: string[];
  riskLevelThreshold?: string;
}) {
  const response = await page.request.put('/api/release-environment-policies', {
    headers: await getAuthHeaders(page),
    data: [{
      envId,
      action: 'EXECUTE_RELEASE',
      riskLevel: options.riskLevelThreshold || 'HIGH',
      enabled: true,
      approvalRequired: options.approvalRequired || false,
      precheckRequired: false,
      blocked: options.blockEnabled || false,
      freezeWindowEnabled: false,
      description: policyName
    }]
  });
  console.log('Create admission policy response:', response.status());
  expect(response.ok()).toBeTruthy();
  // Get the policy ID from the list
  const policies = await page.request.get('/api/release-environment-policies', {
    headers: await getAuthHeaders(page)
  });
  const policiesData = await policies.json();
  const createdPolicy = policiesData?.data?.find((p: { description: string }) => p.description === policyName);
  return createdPolicy?.id;
}

async function deleteAdmissionPolicy(page: Page, policyId: number) {
  try {
    await page.request.delete(`/api/release-environment-policies/${policyId}`, {
      headers: await getAuthHeaders(page)
    });
    console.log('Deleted admission policy:', policyId);
  } catch (e) {
    console.warn('Delete admission policy failed:', e);
  }
}

async function cleanupTestAdmissionPolicies(page: Page, envId: number) {
  try {
    const response = await page.request.get('/api/release-environment-policies', {
      headers: await getAuthHeaders(page)
    });
    const data = await response.json();
    const policies = data?.data ?? [];

    for (const policy of policies) {
      const description = String(policy.description ?? '');
      if (policy.envId === envId && TEST_POLICY_PREFIXES.some(prefix => description.startsWith(prefix))) {
        await page.request.delete(`/api/release-environment-policies/${policy.id}`, {
          headers: await getAuthHeaders(page)
        });
        console.log('Cleanup E2E admission policy:', policy.id, description);
      }
    }
  } catch (e) {
    console.warn('Cleanup admission policies failed:', e);
  }
}

async function getEnvIdByName(page: Page, envName: string): Promise<number> {
  const response = await page.request.get('/api/envs', {
    headers: await getAuthHeaders(page),
    params: { keyword: envName, pageSize: 10 }
  });
  const data = await response.json();
  const envs = data?.data?.records ?? data?.data ?? [];
  const env = envs.find((e: { envName: string }) => e.envName === envName);
  return env?.id ?? env?.envId;
}

/**
 * 通过 API 获取发布单完整记录（含 numeric id）
 * 废弃 UI 第一列假设，避免表格列顺序变化导致错误
 */
async function getReleaseOrderByNo(page: Page, releaseOrderNo: string): Promise<{ id: number; orderNo: string; [key: string]: unknown } | null> {
  const response = await page.request.get('/api/releases', {
    headers: await getAuthHeaders(page),
    params: { keyword: releaseOrderNo, pageSize: 10 }
  });
  if (!response.ok()) {
    console.warn('Failed to get release order:', response.status());
    return null;
  }
  const data = await response.json();
  const records = data?.data?.records ?? data?.data ?? [];
  const order = records.find((r: { orderNo: string }) => r.orderNo === releaseOrderNo);
  return order ?? null;
}

async function findReleaseOrderRow(page: Page, releaseOrderNo: string) {
  await page.goto(`/release/orders?keyword=${encodeURIComponent(releaseOrderNo)}`);
  await expect(page.getByRole('heading', { name: '发布单', exact: true })).toBeVisible();
  await page.waitForSelector('.el-loading-mask', { state: 'hidden', timeout: 5000 }).catch(() => {});
  const row = tableRow(page, releaseOrderNo);
  await expect(row).toBeVisible({ timeout: 10000 });
  return row;
}

async function waitForReleaseExecuteResponse(page: Page) {
  return page.waitForResponse(resp =>
    resp.url().includes('/api/releases/') &&
    resp.url().includes('/execute') &&
    resp.request().method() === 'POST',
    { timeout: 30000 }
  );
}

function responseMessage(body: unknown) {
  return JSON.stringify(body);
}

test.describe('release admission governance', () => {
  test.setTimeout(180000);

  test.beforeEach(async ({ page }) => {
    await loginAsAdmin(page);
  });

  // Clean up residual policies from previous failed runs before each test
  test.afterEach(async ({ page }) => {
    try {
      const envId = await getEnvIdByName(page, '测试环境');
      if (envId) {
        await cleanupTestAdmissionPolicies(page, envId);
      }
    } catch (e) {
      console.warn('AfterEach cleanup failed:', e);
    }
  });

  test('scenario 1: release blocked by admission policy shows reason and audit log', async ({ page }) => {
    const suffix = Date.now().toString().slice(-8);
    const releaseOrderNo = `REL-BLOCK-${suffix}`;

    // Find test environment ID (use 测试环境)
    const envId = await getEnvIdByName(page, '测试环境');
    console.log('Test environment ID:', envId);
    expect(envId).toBeTruthy();

    // Clean up any residual policies before starting
    await cleanupTestAdmissionPolicies(page, envId);

    let policyId: number | null = null;

    try {
      // Step 1: Create admission policy with block enabled for test environment
      policyId = await createAdmissionPolicy(page, envId, `阻断策略-${suffix}`, {
        blockEnabled: true,
        approvalRequired: false,
        riskLevelThreshold: 'LOW'
      });
      console.log('Created admission policy ID:', policyId);
      expect(policyId).toBeTruthy();

      // Step 2: Create a release order
      const releaseDialog = await openCreateDialog(page, '发布单');
      await fillTextField(releaseDialog, '发布单号', releaseOrderNo);
      await chooseSelectOption(releaseDialog, page, '应用名称', 'InfraHub');
      await chooseSelectOption(releaseDialog, page, '服务名称', 'InfraHub 后端服务');
      await chooseSelectOption(releaseDialog, page, '环境名称', '测试环境');
      await chooseFirstSelectOption(releaseDialog, page, '版本');
      await chooseSelectOption(releaseDialog, page, '风险等级', 'LOW');
      await saveDialog(page);

      const releaseRow = await findReleaseOrderRow(page, releaseOrderNo);

      // Wait for any loading overlay to disappear before clicking
      await page.waitForSelector('.el-loading-mask', { state: 'hidden', timeout: 5000 }).catch(() => {});
      await page.waitForTimeout(500);

      // Get the release order ID (numeric) via API for audit search
      const releaseOrder = await getReleaseOrderByNo(page, releaseOrderNo);
      expect(releaseOrder).not.toBeNull();
      const releaseOrderId = releaseOrder!.id;
      console.log('Release order ID for audit search:', releaseOrderId);

      // Step 3: Execute release and verify blocked response
      const responsePromise = waitForReleaseExecuteResponse(page);
      await releaseRow.getByRole('button', { name: '执行发布', exact: true }).click();

      const confirmDialog = page.locator('.el-message-box');
      await expect(confirmDialog).toBeVisible();
      await confirmDialog.getByRole('button', { name: 'OK', exact: true }).click();

      const response = await responsePromise;
      expect(response.status()).toBe(400);
      const responseBody = await response.json();
      expect(responseMessage(responseBody)).toMatch(/准入阻断|策略配置阻断|阻断/);

      // Should show block message in UI (BizException error message)
      await expect(page.locator('.el-message').getByText(/准入阻断|发布被阻断|不符合发布策略|策略配置阻断/)).toBeVisible({ timeout: 5000 });

      // Step 4: Verify audit log contains RELEASE_ADMISSION_BLOCKED
      await openPage(page, '审计日志');
      await page.waitForTimeout(1500);

      // Click search button to load data first
      await page.getByRole('button', { name: '搜索', exact: true }).click();
      await page.waitForTimeout(1500);

      const auditResponse = await page.request.get('/api/audit/logs', {
        headers: await getAuthHeaders(page),
        params: {
          bizType: 'RELEASE_ORDER',
          bizId: releaseOrderId || '',
          action: 'RELEASE_ADMISSION_BLOCKED',
          pageSize: 10
        }
      });
      expect(auditResponse.ok()).toBeTruthy();
      const auditBody = await auditResponse.json();
      const auditRecords = auditBody?.data?.records ?? [];
      expect(auditRecords.some((record: { action?: string; bizId?: number }) =>
        record.action === 'RELEASE_ADMISSION_BLOCKED' && String(record.bizId) === String(releaseOrderId)
      )).toBeTruthy();

      console.log('Scenario 1 (block) completed successfully');

    } finally {
      // Cleanup: Delete admission policy
      if (policyId) {
        await deleteAdmissionPolicy(page, policyId);
        console.log('Cleanup: policy deleted');
      }
    }
  });

  test('scenario 2: release with approval requirement returns approvalId and can be processed', async ({ page }) => {
    const suffix = Date.now().toString().slice(-8);
    const releaseOrderNo = `REL-APPROVE-${suffix}`;

    // Find test environment ID
    const envId = await getEnvIdByName(page, '测试环境');
    console.log('Test environment ID:', envId);
    expect(envId).toBeTruthy();

    // Clean up any residual policies before starting
    await cleanupTestAdmissionPolicies(page, envId);

    let policyId: number | null = null;
    let approvalId: number | null = null;

    try {
      // Step 1: Create admission policy with approval required for test environment
      policyId = await createAdmissionPolicy(page, envId, `审批策略-${suffix}`, {
        blockEnabled: false,
        approvalRequired: true,
        riskLevelThreshold: 'MEDIUM'
      });
      console.log('Created admission policy ID:', policyId);
      expect(policyId).toBeTruthy();

      // Step 2: Create a release order
      const releaseDialog = await openCreateDialog(page, '发布单');
      await fillTextField(releaseDialog, '发布单号', releaseOrderNo);
      await chooseSelectOption(releaseDialog, page, '应用名称', 'InfraHub');
      await chooseSelectOption(releaseDialog, page, '服务名称', 'InfraHub 后端服务');
      await chooseSelectOption(releaseDialog, page, '环境名称', '测试环境');
      await chooseFirstSelectOption(releaseDialog, page, '版本');
      await chooseSelectOption(releaseDialog, page, '风险等级', 'MEDIUM');
      await saveDialog(page);

      const releaseRow = await findReleaseOrderRow(page, releaseOrderNo);

      // Wait for any loading overlay to disappear before clicking
      await page.waitForSelector('.el-loading-mask', { state: 'hidden', timeout: 5000 }).catch(() => {});
      await page.waitForTimeout(500);

      // Step 3: Execute release and capture response - MUST get valid response
      const responsePromise = waitForReleaseExecuteResponse(page);

      await releaseRow.getByRole('button', { name: '执行发布', exact: true }).click();

      const confirmDialog = page.locator('.el-message-box');
      await expect(confirmDialog).toBeVisible();
      await confirmDialog.getByRole('button', { name: 'OK', exact: true }).click();

      // CRITICAL: Must receive valid response - this is the core freeze scenario
      const response = await responsePromise;
      expect(response).not.toBeNull();
      expect(response.ok()).toBeTruthy();

      const responseBody = await response.json();
      console.log('Execute response status:', response.status());
      console.log('Execute response body:', JSON.stringify(responseBody));

      // CRITICAL: Must extract approvalId - this proves admission policy triggered approval
      approvalId = responseBody?.data?.approvalId ?? responseBody?.approvalId;
      console.log('Extracted approvalId:', approvalId);
      expect(approvalId).toBeTruthy();
      expect(typeof approvalId).toBe('number');

      // Should show approval submitted message
      await expect(page.locator('.el-message').getByText(/审批已提交|审批成功|提交审批/)).toBeVisible({ timeout: 10000 });

      // Step 4: Navigate to approval center and verify the request exists
      await openPage(page, '审批中心');
      await page.waitForTimeout(1000);

      await page.getByRole('button', { name: '搜索', exact: true }).click();
      await page.waitForTimeout(2000);

      // CRITICAL: Must find the approval request in approval center
      const approvalRow = await findRowAcrossPages(page, releaseOrderNo);
      if (await approvalRow.count() > 0) {
        await expect(approvalRow).toBeVisible();
        console.log('Found approval request by release order number');
      } else {
        // Try to find by approvalId
        const approvalRowById = await findRowAcrossPages(page, String(approvalId));
        await expect(approvalRowById).toBeVisible();
        console.log('Found approval request by approvalId');
      }

      // Step 5: Approve via API - must succeed
      const approveResponse = await page.request.post(`/api/approvals/requests/${approvalId}/approve`, {
        headers: await getAuthHeaders(page),
        data: { approveNote: 'E2E test admission approval' }
      });
      console.log('Approve response status:', approveResponse.status());
      expect(approveResponse.ok()).toBeTruthy();

      // Step 6: Verify release execution started
      const releaseRowFinal = await findReleaseOrderRow(page, releaseOrderNo);
      const rowText = await releaseRowFinal.textContent();
      expect(rowText).not.toContain('CREATED');
      expect(rowText).toMatch(/PENDING_APPROVAL|RUNNING|SUCCESS|FAILED|待审批|执行中|成功|失败/);

      console.log('Scenario 2 (approval required) completed successfully');

    } finally {
      // Cleanup: Delete admission policy
      if (policyId) {
        await deleteAdmissionPolicy(page, policyId);
        console.log('Cleanup: policy deleted');
      }
    }
  });

  test('scenario 3: release blocked when precheck required and no success record', async ({ page }) => {
    const suffix = Date.now().toString().slice(-8);
    const releaseOrderNo = `REL-PRECHECK-${suffix}`;

    // Find test environment ID
    const envId = await getEnvIdByName(page, '测试环境');
    console.log('Test environment ID:', envId);
    expect(envId).toBeTruthy();

    // Clean up any residual policies before starting
    await cleanupTestAdmissionPolicies(page, envId);

    let policyId: number | null = null;

    try {
      // Step 1: Create admission policy requiring precheck (blocked=false to test precheck logic specifically)
      const createPolicyResponse = await page.request.put('/api/release-environment-policies', {
        headers: await getAuthHeaders(page),
        data: [{
          envId,
          action: 'EXECUTE_RELEASE',
          riskLevel: 'LOW',
          enabled: true,
          approvalRequired: false,
          precheckRequired: true,
          blocked: false,  // Must be false to ensure we test precheck logic, not blocked logic
          freezeWindowEnabled: false,
          description: `前置检查策略-${suffix}`
        }]
      });
      expect(createPolicyResponse.ok()).toBeTruthy();

      // Get the policy ID
      const policies = await page.request.get('/api/release-environment-policies', {
        headers: await getAuthHeaders(page)
      });
      const policiesData = await policies.json();
      const createdPolicy = policiesData?.data?.find((p: { description: string }) => p.description === `前置检查策略-${suffix}`);
      policyId = createdPolicy?.id;
      console.log('Created admission policy ID:', policyId);
      expect(policyId).toBeTruthy();

      // Step 2: Create a release order
      const releaseDialog = await openCreateDialog(page, '发布单');
      await fillTextField(releaseDialog, '发布单号', releaseOrderNo);
      await chooseSelectOption(releaseDialog, page, '应用名称', 'InfraHub');
      await chooseSelectOption(releaseDialog, page, '服务名称', 'InfraHub 后端服务');
      await chooseSelectOption(releaseDialog, page, '环境名称', '测试环境');
      await chooseFirstSelectOption(releaseDialog, page, '版本');
      await chooseSelectOption(releaseDialog, page, '风险等级', 'LOW');
      await saveDialog(page);

      const releaseRow = await findReleaseOrderRow(page, releaseOrderNo);

      // Wait for any loading overlay to disappear before clicking
      await page.waitForSelector('.el-loading-mask', { state: 'hidden', timeout: 5000 }).catch(() => {});
      await page.waitForTimeout(500);

      // Step 3: Execute release and verify blocked due to missing precheck
      const responsePromise = waitForReleaseExecuteResponse(page);
      await releaseRow.getByRole('button', { name: '执行发布', exact: true }).click();

      const confirmDialog = page.locator('.el-message-box');
      await expect(confirmDialog).toBeVisible();
      await confirmDialog.getByRole('button', { name: 'OK', exact: true }).click();

      const response = await responsePromise;
      expect(response.status()).toBe(400);
      const responseBody = await response.json();
      expect(responseMessage(responseBody)).toMatch(/前置检查|无有效成功记录|准入阻断/);

      // Should show block message mentioning precheck (BizException)
      await expect(page.locator('.el-message').getByText(/前置检查|检查未通过|需要检查|无有效|准入阻断/)).toBeVisible({ timeout: 5000 });

      console.log('Scenario 3 (precheck required) completed successfully');

    } finally {
      // Cleanup: Delete admission policy
      if (policyId) {
        await deleteAdmissionPolicy(page, policyId);
        console.log('Cleanup: policy deleted');
      }
    }
  });

  test('scenario 4: D1 governance evidence chain shows admission audit records', async ({ page }) => {
    const suffix = Date.now().toString().slice(-8);
    const releaseOrderNo = `REL-EVIDENCE-${suffix}`;

    // Find test environment ID
    const envId = await getEnvIdByName(page, '测试环境');
    console.log('Test environment ID:', envId);
    expect(envId).toBeTruthy();

    // Clean up any residual policies before starting
    await cleanupTestAdmissionPolicies(page, envId);

    let policyId: number | null = null;

    try {
      // Step 1: Create admission policy
      policyId = await createAdmissionPolicy(page, envId, `证据链策略-${suffix}`, {
        blockEnabled: true,
        approvalRequired: false,
        riskLevelThreshold: 'LOW'
      });
      console.log('Created admission policy ID:', policyId);
      expect(policyId).toBeTruthy();

      // Step 2: Create and execute release order to trigger admission
      const releaseDialog = await openCreateDialog(page, '发布单');
      await fillTextField(releaseDialog, '发布单号', releaseOrderNo);
      await chooseSelectOption(releaseDialog, page, '应用名称', 'InfraHub');
      await chooseSelectOption(releaseDialog, page, '服务名称', 'InfraHub 后端服务');
      await chooseSelectOption(releaseDialog, page, '环境名称', '测试环境');
      await chooseFirstSelectOption(releaseDialog, page, '版本');
      await chooseSelectOption(releaseDialog, page, '风险等级', 'LOW');
      await saveDialog(page);

      const releaseRow = await findReleaseOrderRow(page, releaseOrderNo);

      // Wait for any loading overlay to disappear before clicking
      await page.waitForSelector('.el-loading-mask', { state: 'hidden', timeout: 5000 }).catch(() => {});
      await page.waitForTimeout(500);

      // Get release order ID via API for audit search
      const releaseOrder = await getReleaseOrderByNo(page, releaseOrderNo);
      expect(releaseOrder).not.toBeNull();
      const releaseOrderId = releaseOrder!.id;
      console.log('Release order ID:', releaseOrderId);

      // Execute release to trigger admission check
      const responsePromise = waitForReleaseExecuteResponse(page);
      await releaseRow.getByRole('button', { name: '执行发布', exact: true }).click();

      const confirmDialog = page.locator('.el-message-box');
      await expect(confirmDialog).toBeVisible();
      await confirmDialog.getByRole('button', { name: 'OK', exact: true }).click();

      const response = await responsePromise;
      expect(response.status()).toBe(400);

      // Step 3: Verify audit log via API with precise query
      const auditResponse = await page.request.get('/api/audit/logs', {
        headers: await getAuthHeaders(page),
        params: {
          bizType: 'RELEASE_ORDER',
          bizId: releaseOrderId,
          action: 'RELEASE_ADMISSION_BLOCKED',
          pageSize: 10
        }
      });
      expect(auditResponse.ok()).toBeTruthy();
      const auditBody = await auditResponse.json();
      const auditRecords = auditBody?.data?.records ?? [];
      expect(auditRecords.length).toBeGreaterThan(0);
      expect(auditRecords.some((r: { action?: string; bizId?: number }) =>
        r.action === 'RELEASE_ADMISSION_BLOCKED' && String(r.bizId) === String(releaseOrderId)
      )).toBeTruthy();

      // Step 4: Navigate to audit logs page and verify evidence chain
      await openPage(page, '审计日志');
      await page.waitForTimeout(1000);

      await page.getByRole('button', { name: '搜索', exact: true }).click();
      await page.waitForTimeout(1500);

      // Find audit row for this release (by numeric ID)
      const auditRow = await findRowAcrossPages(page, releaseOrderId || '');
      await expect(auditRow).toBeVisible();

      // Step 4: Open detail dialog and click evidence chain
      await auditRow.getByRole('button', { name: '详情' }).click();
      await expect(activeDialog(page)).toBeVisible();

      // Check if evidence chain button exists
      const chainButton = activeDialog(page).getByRole('button', { name: '查看证据链' });
      await expect(chainButton).toBeVisible();
      await chainButton.click();
      await page.waitForURL(url => url.pathname.includes('/governance-chain'));
      await page.waitForTimeout(1000);

      // Verify the page shows relevant data
      const businessSummaryCard = page.locator('.el-card').filter({ hasText: '业务摘要' });
      await expect(businessSummaryCard).toBeVisible();

      console.log('Scenario 4: Governance evidence chain shows admission records');

      console.log('Scenario 4 (evidence chain) completed successfully');

    } finally {
      // Cleanup: Delete admission policy
      if (policyId) {
        await deleteAdmissionPolicy(page, policyId);
        console.log('Cleanup: policy deleted');
      }
    }
  });
});
