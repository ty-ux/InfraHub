import { expect, test, type Page } from '@playwright/test';
import { loginAsAdmin } from './helpers/auth';
import {
  activeDialog,
  chooseFirstSelectOption,
  chooseSelectOption,
  fillTextField,
  openCreateDialog,
  saveDialog,
  tableRow,
} from './helpers/ui';

const FULL_CHAIN_POLICY_PREFIX = '总链路策略-';

async function getAuthHeaders(page: Page) {
  const token = await page.evaluate(() => window.localStorage.getItem('INFRAHUB_TOKEN'));
  expect(token).toBeTruthy();
  return { Authorization: `Bearer ${token}` };
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

async function getReleaseOrderByNo(page: Page, releaseOrderNo: string): Promise<{ id: number; orderNo: string; [key: string]: unknown } | null> {
  const response = await page.request.get('/api/releases', {
    headers: await getAuthHeaders(page),
    params: { keyword: releaseOrderNo, pageSize: 10 }
  });
  if (!response.ok()) return null;
  const data = await response.json();
  const records = data?.data?.records ?? data?.data ?? [];
  return records.find((r: { orderNo: string }) => r.orderNo === releaseOrderNo) ?? null;
}

async function cleanupTestPolicies(page: Page, envId: number) {
  const prefixes = ['阻断策略-', '审批策略-', '前置检查策略-', '证据链策略-', FULL_CHAIN_POLICY_PREFIX];
  try {
    const response = await page.request.get('/api/release-environment-policies', {
      headers: await getAuthHeaders(page)
    });
    const data = await response.json();
    const policies = data?.data ?? [];

    for (const policy of policies) {
      const description = String(policy.description ?? '');
      if (policy.envId === envId && prefixes.some(prefix => description.startsWith(prefix))) {
        await page.request.delete(`/api/release-environment-policies/${policy.id}`, {
          headers: await getAuthHeaders(page)
        });
        console.log('Cleanup policy:', policy.id, description);
      }
    }
  } catch (e) {
    console.warn('Cleanup policies failed:', e);
  }
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

test.describe('D governance full chain', () => {
  test.setTimeout(180000);

  test.beforeEach(async ({ page }) => {
    await loginAsAdmin(page);
  });

  test('full chain from admission approval to evidence chain', async ({ page }) => {
    const suffix = Date.now().toString().slice(-8);
    const releaseOrderNo = `REL-FULL-${suffix}`;
    const policyName = `${FULL_CHAIN_POLICY_PREFIX}${suffix}`;

    // Step 1: Get test environment ID
    const envId = await getEnvIdByName(page, '测试环境');
    console.log('Test environment ID:', envId);
    expect(envId).toBeTruthy();

    // Step 2: Cleanup any residual policies
    await cleanupTestPolicies(page, envId);

    let policyId: number | null = null;

    try {
      // Step 3: Create admission policy with approvalRequired=true, blocked=false via API
      const createResponse = await page.request.put('/api/release-environment-policies', {
        headers: await getAuthHeaders(page),
        data: [{
          envId,
          action: 'EXECUTE_RELEASE',
          riskLevel: 'MEDIUM',
          enabled: true,
          approvalRequired: true,
          precheckRequired: false,
          blocked: false,
          freezeWindowEnabled: false,
          description: policyName
        }]
      });
      expect(createResponse.ok()).toBeTruthy();
      console.log('Created admission policy');

      // Get policy ID for cleanup
      const policiesResp = await page.request.get('/api/release-environment-policies', {
        headers: await getAuthHeaders(page)
      });
      const policiesData = await policiesResp.json();
      const createdPolicy = policiesData?.data?.find((p: { description: string }) => p.description === policyName);
      policyId = createdPolicy?.id;
      console.log('Policy ID:', policyId);

      // Step 4: Create release order via UI using helpers
      const dialog = await openCreateDialog(page, '发布单');
      await fillTextField(dialog, '发布单号', releaseOrderNo);
      await chooseSelectOption(dialog, page, '应用名称', 'InfraHub');
      await chooseSelectOption(dialog, page, '服务名称', 'InfraHub 后端服务');
      await chooseSelectOption(dialog, page, '环境名称', '测试环境');
      await chooseFirstSelectOption(dialog, page, '版本');
      await chooseSelectOption(dialog, page, '风险等级', 'MEDIUM');
      await saveDialog(page);

      // Refresh to load the newly created release order
      await page.getByRole('button', { name: '刷新', exact: true }).click();
      await page.waitForTimeout(2000);
      await page.waitForSelector('.el-loading-mask', { state: 'hidden', timeout: 5000 }).catch(() => {});

      // Step 5: Get release order via API
      const releaseOrder = await getReleaseOrderByNo(page, releaseOrderNo);
      expect(releaseOrder).not.toBeNull();
      const releaseOrderId = releaseOrder!.id;
      console.log('Release order ID:', releaseOrderId);

      // Find the row
      const row = await findReleaseOrderRow(page, releaseOrderNo);

      // Step 6: Execute release and capture response
      await page.waitForSelector('.el-loading-mask', { state: 'hidden', timeout: 5000 }).catch(() => {});
      await page.waitForTimeout(500);

      const responsePromise = waitForReleaseExecuteResponse(page);

      await row.getByRole('button', { name: '执行发布', exact: true }).click();

      const confirmDialog = page.locator('.el-message-box');
      await expect(confirmDialog).toBeVisible();
      await confirmDialog.getByRole('button', { name: 'OK', exact: true }).click();

      const response = await responsePromise;
      expect(response.ok()).toBeTruthy();

      const responseBody = await response.json();
      console.log('Execute response:', JSON.stringify(responseBody));

      // Step 7: Assert approvalId exists and directExecuted=false
      const approvalId = responseBody?.data?.approvalId ?? responseBody?.approvalId;
      expect(approvalId).toBeTruthy();
      expect(typeof approvalId).toBe('number');
      expect(responseBody?.data?.directExecuted).toBe(false);
      console.log('Approval ID:', approvalId);

      // Step 8: Approve via API
      const approveResponse = await page.request.post(`/api/approvals/requests/${approvalId}/approve`, {
        headers: await getAuthHeaders(page),
        data: { approveNote: 'D governance full chain E2E approval' }
      });
      console.log('Approve response status:', approveResponse.status());
      expect(approveResponse.ok()).toBeTruthy();

      // Step 9: Verify audit log
      const auditResponse = await page.request.get('/api/audit/logs', {
        headers: await getAuthHeaders(page),
        params: {
          bizType: 'RELEASE_ORDER',
          bizId: releaseOrderId,
          pageSize: 20
        }
      });
      expect(auditResponse.ok()).toBeTruthy();
      const auditBody = await auditResponse.json();
      const auditRecords = auditBody?.data?.records ?? [];
      console.log('Audit records count:', auditRecords.length);

      // Assert at least one admission-related action
      const admissionActions = ['RELEASE_ADMISSION_APPROVAL_REQUIRED', 'RELEASE_ADMISSION_PASS'];
      const hasAdmissionRecord = auditRecords.some((r: { action?: string; bizId?: number }) =>
        admissionActions.includes(r.action) && String(r.bizId) === String(releaseOrderId)
      );
      expect(hasAdmissionRecord).toBeTruthy();
      console.log('Found admission audit record');

      // Step 10: Navigate to governance chain page
      await page.goto(`/system/governance-chain?bizType=RELEASE_ORDER&bizId=${releaseOrderId}`);
      await page.waitForTimeout(2000);
      await page.waitForSelector('.el-loading-mask', { state: 'hidden', timeout: 5000 }).catch(() => {});

      // Step 11: Assert evidence chain page content - all sections must be visible
      const businessSummaryCard = page.locator('.el-card').filter({ hasText: '业务摘要' });
      await expect(businessSummaryCard).toBeVisible({ timeout: 10000 });

      // Assert release order number visible
      await expect(page.getByText(releaseOrderNo)).toBeVisible();

      // Assert approval record section - MUST be visible (no conditional check)
      const approvalSection = page.locator('.el-card').filter({ hasText: '审批记录' });
      await expect(approvalSection).toBeVisible({ timeout: 10000 });

      // Assert the approvalId from this test is visible in approval section
      await expect(approvalSection.getByText(String(approvalId))).toBeVisible();

      // Assert audit record section - MUST be visible (no conditional check)
      const auditSection = page.locator('.el-card').filter({ hasText: '审计日志' });
      await expect(auditSection).toBeVisible({ timeout: 10000 });

      // Assert both RELEASE_ADMISSION actions are visible in audit section
      await expect(auditSection.getByText('RELEASE_ADMISSION_PASS')).toBeVisible();
      await expect(auditSection.getByText('RELEASE_ADMISSION_APPROVAL_REQUIRED')).toBeVisible();

      console.log('D governance full chain test completed successfully');

    } finally {
      // Cleanup: Delete admission policy
      if (policyId) {
        try {
          await page.request.delete(`/api/release-environment-policies/${policyId}`, {
            headers: await getAuthHeaders(page)
          });
          console.log('Cleanup: policy deleted');
        } catch (e) {
          console.warn('Cleanup policy failed:', e);
        }
      }

      // Final cleanup by prefix
      await cleanupTestPolicies(page, envId);
    }
  });
});
