import { expect, test } from '@playwright/test';
import { loginAsAdmin } from './helpers/auth';
import { mockResourceApis, mockRollbackList } from './helpers/mocks';
import { openPage } from './helpers/ui';

// Helper: use filter bar to trigger mocked API call
async function filterByKeyword(page: import('@playwright/test').Page, keyword: string) {
  const filterBar = page.locator('.order-filter-bar');
  const keywordInput = filterBar.locator('input').first();
  await keywordInput.fill(keyword);
  await filterBar.getByRole('button', { name: '查询', exact: true }).click();
}

// 行内“候选版本”入口只对明确支持的 SSH 回滚开放，因此每条正向夹具都逐条显式给出
// executorType 与 rollbackSupported；共享 mock 不补默认值，缺失即按“无法确定支持状态”处理。

test.describe('T-B rollback candidate (Contract 2.2, 3.4-3.5)', () => {
  test.setTimeout(90000);

  test.beforeEach(async ({ page }) => {
    await loginAsAdmin(page);
  });

  // T-04: Verify rollback candidate API path
  test('candidate dialog requests GET /api/releases/{releaseOrderId}/rollback-candidates', async ({ page }) => {
    let requestedCorrectPath = false;
    const releaseOrderId = 6001;
    const rollbackNo = 'RB-CAND-001';

    await mockResourceApis(page);
    await mockRollbackList(page, [
      { id: 7001, rollbackNo, releaseOrderId, status: 'CREATED', currentVersionNo: 'v2.0.0', fromVersionNo: 'v2.0.0', executorType: 'SSH', rollbackSupported: true },
    ]);

    // Mock rollback candidate API
    await page.route(`**/api/releases/${releaseOrderId}/rollback-candidates**`, async (route) => {
      const url = new URL(route.request().url());
      if (url.pathname === `/api/releases/${releaseOrderId}/rollback-candidates`) {
        requestedCorrectPath = true;
      }
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          code: 'SUCCESS',
          data: {
            records: [
              { versionId: 1, versionNo: 'v1.0.0', deployedAt: '2026-05-15T10:00:00+08:00', rollbackCount: 0, isCurrent: false, recommendationScore: 90, recommendationReason: '最近一次成功部署版本' },
              { versionId: 2, versionNo: 'v2.0.0', deployedAt: '2026-05-18T10:00:00+08:00', rollbackCount: 0, isCurrent: true, recommendationScore: 0, recommendationReason: '当前版本' },
            ],
            total: 2,
            pageNum: 1,
            pageSize: 20,
            currentVersionId: 2,
            currentVersionNo: 'v2.0.0',
          },
        }),
      });
    });

    await openPage(page, '回滚单');
    await filterByKeyword(page, rollbackNo);

    const row = page.locator('tbody tr').filter({ hasText: rollbackNo }).first();
    await expect(row).toBeVisible({ timeout: 15000 });

    await row.getByRole('button', { name: '候选版本', exact: true }).click();
    await page.waitForTimeout(2000);

    expect(requestedCorrectPath).toBe(true);
  });

  // T-05: Verify rollback candidate response contains all Contract-required fields
  test('candidate dialog displays all required response fields per Contract 3.4-3.5', async ({ page }) => {
    const releaseOrderId = 6002;
    const rollbackNo = 'RB-CAND-002';

    await mockResourceApis(page);
    await mockRollbackList(page, [
      { id: 7002, rollbackNo, releaseOrderId, status: 'CREATED', currentVersionNo: 'v2.0.0', fromVersionNo: 'v2.0.0', executorType: 'SSH', rollbackSupported: true },
    ]);

    await page.route(`**/api/releases/${releaseOrderId}/rollback-candidates**`, async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          code: 'SUCCESS',
          data: {
            records: [
              { versionId: 101, versionNo: 'v1.5.0', deployedAt: '2026-05-10T10:00:00+08:00', rollbackCount: 1, isCurrent: false, recommendationScore: 75, recommendationReason: '从未回滚的稳定版本' },
              { versionId: 102, versionNo: 'v2.0.0', deployedAt: '2026-05-18T10:00:00+08:00', rollbackCount: 0, isCurrent: true, recommendationScore: 0, recommendationReason: '当前版本' },
            ],
            total: 2,
            pageNum: 1,
            pageSize: 20,
            currentVersionId: 102,
            currentVersionNo: 'v2.0.0',
          },
        }),
      });
    });

    await openPage(page, '回滚单');
    await filterByKeyword(page, rollbackNo);

    const row = page.locator('tbody tr').filter({ hasText: rollbackNo }).first();
    await expect(row).toBeVisible({ timeout: 15000 });
    await row.getByRole('button', { name: '候选版本', exact: true }).click();

    const dialog = page.getByRole('dialog').filter({ hasText: '选择回滚候选版本' });
    await expect(dialog).toBeVisible({ timeout: 5000 });

    await expect(dialog).toContainText('当前版本');
    await expect(dialog).toContainText('v2.0.0');
    await expect(dialog).toContainText('v1.5.0');
    await expect(dialog).toContainText('75');
    await expect(dialog).toContainText('从未回滚的稳定版本');
  });

  // T-06: Verify candidates are sorted by recommendationScore descending
  test('candidates are displayed sorted by recommendationScore descending', async ({ page }) => {
    const releaseOrderId = 6003;
    const rollbackNo = 'RB-CAND-003';

    await mockResourceApis(page);
    await mockRollbackList(page, [
      { id: 7003, rollbackNo, releaseOrderId, status: 'CREATED', currentVersionNo: 'v3.0.0', fromVersionNo: 'v3.0.0', executorType: 'SSH', rollbackSupported: true },
    ]);

    await page.route(`**/api/releases/${releaseOrderId}/rollback-candidates**`, async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          code: 'SUCCESS',
          data: {
            records: [
              { versionId: 201, versionNo: 'v1.0.0', deployedAt: '2026-04-01T10:00:00+08:00', rollbackCount: 0, isCurrent: false, recommendationScore: 95, recommendationReason: '最近一次成功部署版本' },
              { versionId: 202, versionNo: 'v1.5.0', deployedAt: '2026-04-15T10:00:00+08:00', rollbackCount: 1, isCurrent: false, recommendationScore: 60, recommendationReason: '历史运行时间最长的版本' },
              { versionId: 203, versionNo: 'v3.0.0', deployedAt: '2026-05-18T10:00:00+08:00', rollbackCount: 0, isCurrent: true, recommendationScore: 0, recommendationReason: '当前版本' },
            ],
            total: 3,
            pageNum: 1,
            pageSize: 20,
            currentVersionId: 203,
            currentVersionNo: 'v3.0.0',
          },
        }),
      });
    });

    await openPage(page, '回滚单');
    await filterByKeyword(page, rollbackNo);

    const row = page.locator('tbody tr').filter({ hasText: rollbackNo }).first();
    await expect(row).toBeVisible({ timeout: 15000 });
    await row.getByRole('button', { name: '候选版本', exact: true }).click();

    const dialog = page.getByRole('dialog').filter({ hasText: '选择回滚候选版本' });
    await expect(dialog).toBeVisible({ timeout: 5000 });

    const versionCells = dialog.locator('td').filter({ hasText: /^v\d+\.\d+\.\d+$/ });
    const versionTexts = await versionCells.allTextContents();

    expect(versionTexts[0]).toContain('v1.0.0');
    expect(versionTexts[1]).toContain('v1.5.0');
    expect(versionTexts[2]).toContain('v3.0.0');
  });

  // Verify isCurrent tag and selection behavior
  test('candidate dialog shows isCurrent tag and disables select for current version', async ({ page }) => {
    const releaseOrderId = 6004;
    const rollbackNo = 'RB-CAND-004';

    await mockResourceApis(page);
    await mockRollbackList(page, [
      { id: 7004, rollbackNo, releaseOrderId, status: 'CREATED', currentVersionNo: 'v2.0.0', fromVersionNo: 'v2.0.0', executorType: 'SSH', rollbackSupported: true },
    ]);

    await page.route(`**/api/releases/${releaseOrderId}/rollback-candidates**`, async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          code: 'SUCCESS',
          data: {
            records: [
              { versionId: 301, versionNo: 'v1.0.0', deployedAt: '2026-04-01T10:00:00+08:00', rollbackCount: 0, isCurrent: false, recommendationScore: 85, recommendationReason: '从未回滚的稳定版本' },
              { versionId: 302, versionNo: 'v2.0.0', deployedAt: '2026-05-18T10:00:00+08:00', rollbackCount: 0, isCurrent: true, recommendationScore: 0, recommendationReason: '当前版本' },
            ],
            total: 2,
            pageNum: 1,
            pageSize: 20,
            currentVersionId: 302,
            currentVersionNo: 'v2.0.0',
          },
        }),
      });
    });

    await openPage(page, '回滚单');
    await filterByKeyword(page, rollbackNo);

    const row = page.locator('tbody tr').filter({ hasText: rollbackNo }).first();
    await expect(row).toBeVisible({ timeout: 15000 });
    await row.getByRole('button', { name: '候选版本', exact: true }).click();

    const dialog = page.getByRole('dialog').filter({ hasText: '选择回滚候选版本' });
    await expect(dialog).toBeVisible({ timeout: 5000 });

    await expect(dialog.locator('.el-tag').filter({ hasText: '是' })).toBeVisible();

    const currentVersionRow = dialog.locator('tbody tr').filter({ hasText: 'v2.0.0' }).first();
    await currentVersionRow.click();

    const selectBtn = dialog.getByRole('button', { name: '选择该版本' });
    await expect(selectBtn).toBeDisabled();
  });

  // Verify empty state for candidates
  test('candidate dialog shows empty state when no candidates available', async ({ page }) => {
    const releaseOrderId = 6005;
    const rollbackNo = 'RB-CAND-005';

    await mockResourceApis(page);
    await mockRollbackList(page, [
      { id: 7005, rollbackNo, releaseOrderId, status: 'CREATED', currentVersionNo: 'v1.0.0', executorType: 'SSH', rollbackSupported: true },
    ]);

    await page.route(`**/api/releases/${releaseOrderId}/rollback-candidates**`, async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          code: 'SUCCESS',
          data: { records: [], total: 0, pageNum: 1, pageSize: 20, currentVersionId: 0, currentVersionNo: 'v1.0.0' },
        }),
      });
    });

    await openPage(page, '回滚单');
    await filterByKeyword(page, rollbackNo);

    const row = page.locator('tbody tr').filter({ hasText: rollbackNo }).first();
    await expect(row).toBeVisible({ timeout: 15000 });
    await row.getByRole('button', { name: '候选版本', exact: true }).click();

    const dialog = page.getByRole('dialog').filter({ hasText: '选择回滚候选版本' });
    await expect(dialog).toBeVisible({ timeout: 5000 });

    await expect(dialog).toContainText('暂无候选版本');
  });

  // 不支持回滚的执行器：不提供候选版本入口，行内动作以危险标记整体禁用
  test('rejects rollback orders whose executor type does not support rollback', async ({ page }) => {
    await mockResourceApis(page);
    await mockRollbackList(page, [
      // 后端明确回绝该执行器类型
      { id: 7006, rollbackNo: 'RB-CAND-006', releaseOrderId: 6006, status: 'CREATED', currentVersionNo: 'v1.0.0', executorType: 'DOCKER', rollbackSupported: false },
      // 执行器类型本身就不是 SSH 路径
      { id: 7016, rollbackNo: 'RB-CAND-016', releaseOrderId: 6016, status: 'CREATED', currentVersionNo: 'v1.0.0', executorType: 'DOCKER' },
    ]);

    // 记录任何候选版本请求：被拒的行不得触发它
    let candidateRequests = 0;
    await page.route('**/api/releases/*/rollback-candidates**', async (route) => {
      candidateRequests += 1;
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ code: 'SUCCESS', data: { records: [], total: 0, pageNum: 1, pageSize: 20, currentVersionId: 0, currentVersionNo: '' } }),
      });
    });

    await openPage(page, '回滚单');
    await filterByKeyword(page, 'RB-CAND-006');

    for (const rollbackNo of ['RB-CAND-006', 'RB-CAND-016']) {
      const row = page.locator('tbody tr').filter({ hasText: rollbackNo }).first();
      await expect(row).toBeVisible({ timeout: 15000 });

      // 入口本身不出现，而不是出现后点击才被拒
      await expect(row.getByRole('button', { name: '候选版本', exact: true })).toHaveCount(0);

      const blocked = row.locator('.rollback-unsupported-actions');
      await expect(blocked.locator('.el-tag')).toHaveText('不支持回滚');
      await expect(blocked.locator('.el-tag')).toHaveClass(/el-tag--danger/);
      await expect(blocked.getByRole('button', { name: '前置检查', exact: true })).toBeDisabled();
      await expect(blocked.getByRole('button', { name: '执行回滚', exact: true })).toBeDisabled();
    }

    expect(candidateRequests).toBe(0);
  });

  // 无法确定支持状态：共享 mock 不补默认值，缺失字段一律 fail-closed，不得猜成支持
  test('rejects rollback orders whose rollback support state cannot be determined', async ({ page }) => {
    await mockResourceApis(page);
    await mockRollbackList(page, [
      // 类型为 SSH，但缺少明确的回滚支持确认
      { id: 7007, rollbackNo: 'RB-CAND-007', releaseOrderId: 6007, status: 'CREATED', currentVersionNo: 'v1.0.0', executorType: 'SSH' },
      // 执行器类型本身也缺失
      { id: 7017, rollbackNo: 'RB-CAND-017', releaseOrderId: 6017, status: 'CREATED', currentVersionNo: 'v1.0.0' },
    ]);

    // 记录任何候选版本请求：无法确定支持状态的行不得触发它
    let candidateRequests = 0;
    await page.route('**/api/releases/*/rollback-candidates**', async (route) => {
      candidateRequests += 1;
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ code: 'SUCCESS', data: { records: [], total: 0, pageNum: 1, pageSize: 20, currentVersionId: 0, currentVersionNo: '' } }),
      });
    });

    await openPage(page, '回滚单');
    await filterByKeyword(page, 'RB-CAND-007');

    for (const rollbackNo of ['RB-CAND-007', 'RB-CAND-017']) {
      const row = page.locator('tbody tr').filter({ hasText: rollbackNo }).first();
      await expect(row).toBeVisible({ timeout: 15000 });

      await expect(row.getByRole('button', { name: '候选版本', exact: true })).toHaveCount(0);

      const blocked = row.locator('.rollback-unsupported-actions');
      await expect(blocked.locator('.el-tag')).toHaveText('无法确定支持状态');
      await expect(blocked.locator('.el-tag')).toHaveClass(/el-tag--warning/);
      await expect(blocked.getByRole('button', { name: '前置检查', exact: true })).toBeDisabled();
      await expect(blocked.getByRole('button', { name: '执行回滚', exact: true })).toBeDisabled();
    }

    expect(candidateRequests).toBe(0);
  });
});