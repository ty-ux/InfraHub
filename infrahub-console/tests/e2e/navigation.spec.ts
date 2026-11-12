import { expect, test, type Page } from '@playwright/test';
import { loginAsAdmin } from './helpers/auth';

function sidebarLink(page: import('@playwright/test').Page, name: string) {
  return page.locator('aside.layout-sidebar').getByRole('link', { name, exact: true });
}

/**
 * Wait until the table is either populated with rows or confirmed empty.
 * Resolves much faster than a fixed waitForSelector timeout when data is absent.
 * Returns true if rows are present, false if table is empty.
 */
async function waitForTableReadyOrEmpty(page: Page, timeout = 8000): Promise<boolean> {
  const hasRows = page.locator('.el-table__body-wrapper .el-table__row').first();
  const isEmpty = page.locator('.el-table__empty-text');

  // Race: rows appear vs empty state appears
  const result = await Promise.race([
    hasRows.waitFor({ state: 'visible', timeout }).then(() => 'rows' as const),
    isEmpty.waitFor({ state: 'visible', timeout }).then(() => 'empty' as const),
  ]).catch(() => 'timeout' as const);

  if (result === 'rows') return true;
  if (result === 'empty') return false;

  // Fallback: check DOM directly after short wait
  const rowCount = await page.locator('.el-table__body-wrapper .el-table__row').count();
  return rowCount > 0;
}

/**
 * Get first row first cell text, or null if no rows.
 */
async function getFirstRowFirstCellOrNull(page: Page): Promise<string | null> {
  const rowReady = await waitForTableReadyOrEmpty(page);
  if (!rowReady) return null;
  const text = await page
    .locator('.el-table__body-wrapper .el-table__row')
    .first()
    .locator('td')
    .first()
    .innerText()
    .catch(() => null);
  if (!text || text === '-' || text.trim() === '') return null;
  return text.trim();
}

test.beforeEach(async ({ page }) => {
  await loginAsAdmin(page);
});

test('core management pages can be opened', async ({ page }) => {
  await sidebarLink(page, '应用管理').click();
  await expect(page.getByRole('heading', { name: '应用管理' })).toBeVisible();

  await sidebarLink(page, '服务管理').click();
  await expect(page.getByRole('heading', { name: '服务管理' })).toBeVisible();

  await sidebarLink(page, '环境管理').click();
  await expect(page.getByRole('heading', { name: '环境管理' })).toBeVisible();

  await sidebarLink(page, '配置分组').click();
  await expect(page.getByRole('heading', { name: '配置分组' })).toBeVisible();

  await sidebarLink(page, '环境配置').click();
  await expect(page.getByRole('heading', { name: '环境配置' })).toBeVisible();
  await expect(page.getByRole('button', { name: '批量导入' })).toBeVisible();
  await expect(page.getByRole('button', { name: '从模板生成' })).toBeVisible();

  await sidebarLink(page, '发布单').click();
  await expect(page.getByRole('heading', { name: '发布单' })).toBeVisible();

  await sidebarLink(page, '回滚单').click();
  await expect(page.getByRole('heading', { name: '回滚单' })).toBeVisible();

  await sidebarLink(page, '版本管理').click();
  await expect(page.getByRole('heading', { name: '版本管理' })).toBeVisible();

  await sidebarLink(page, '当前版本').click();
  await expect(page.getByRole('heading', { name: '当前版本' })).toBeVisible();
});

test('pipeline and system pages can be opened', async ({ page }) => {
  await sidebarLink(page, '流水线定义').click();
  await expect(page.getByRole('heading', { name: '流水线定义' })).toBeVisible();

  await sidebarLink(page, '执行记录').click();
  await expect(page.getByRole('heading', { name: '执行记录' })).toBeVisible();

  await sidebarLink(page, '用户管理').click();
  await expect(page.getByRole('heading', { name: '用户管理' })).toBeVisible();

  await sidebarLink(page, '角色管理').click();
  await expect(page.getByRole('heading', { name: '角色管理' })).toBeVisible();

  await sidebarLink(page, '权限管理').click();
  await expect(page.getByRole('heading', { name: '权限管理' })).toBeVisible();

  await sidebarLink(page, '数据字典').click();
  await expect(page.getByRole('heading', { name: '数据字典' })).toBeVisible();
});

// B1: Resource runtime view pages
test('resource pool page can be opened', async ({ page }) => {
  await sidebarLink(page, '资源池').click();
  await expect(page.getByRole('heading', { name: '资源池' })).toBeVisible();
  await expect(page.getByPlaceholder('搜索资源池编码或名称')).toBeVisible();
  await expect(page.locator('.el-table')).toBeVisible();
});

test('hosts page can be opened', async ({ page }) => {
  await sidebarLink(page, '主机管理').click();
  await expect(page.getByRole('heading', { name: '主机管理' })).toBeVisible();
  await expect(page.getByPlaceholder('搜索主机名或IP')).toBeVisible();
  await expect(page.locator('.el-table')).toBeVisible();
});

test('runtime instances page can be opened', async ({ page }) => {
  await sidebarLink(page, '运行实例').click();
  await expect(page.getByRole('heading', { name: '运行实例' })).toBeVisible();
  await expect(page.getByPlaceholder('搜索实例名称')).toBeVisible();
  await expect(page.locator('.el-table')).toBeVisible();
  await expect(page.locator('.el-table__header').getByText('操作')).toBeVisible();

  const firstRow = page.locator('.el-table__body-wrapper .el-table__row').first();
  if (await firstRow.count()) {
    await expect(firstRow.getByRole('button', { name: '运行状态' })).toBeVisible();
    await expect(firstRow.getByRole('button', { name: '运行日志' })).toBeVisible();
    await expect(firstRow.getByRole('button', { name: '部署配置' })).toBeVisible();
  }
});

test('workspace shows release and pipeline observability cards', async ({ page }) => {
  await page.goto('/workspace');

  await expect(page.getByText('发布与流水线关注区')).toBeVisible();
  await expect(page.getByText('最近发布单')).toBeVisible();
  await expect(page.getByText('最近回滚单')).toBeVisible();
  await expect(page.getByText('最近流水线运行')).toBeVisible();

  // V1.23: Verify refresh button is visible
  await expect(page.getByRole('button', { name: '刷新' })).toBeVisible();

  // V1.25: Verify status summary tags are visible (one of: 正常 / 失败 N / 执行中 N)
  const statusTags = page.locator('.card-title .el-tag');
  await expect(statusTags.first()).toBeVisible();
});

test('workspace refresh button updates last updated time', async ({ page }) => {
  await page.goto('/workspace');

  // V1.23: Click refresh button
  await page.getByRole('button', { name: '刷新' }).click();

  // V1.23: After refresh, should show "最后更新：" time
  await expect(page.getByText(/最后更新：/)).toBeVisible();

  // V1.23: Cards should still be visible after refresh
  await expect(page.getByText('最近发布单')).toBeVisible();
  await expect(page.getByText('最近回滚单')).toBeVisible();
  await expect(page.getByText('最近流水线运行')).toBeVisible();

  // V1.25: Status summary tags should still be visible after refresh
  const statusTags = page.locator('.card-title .el-tag');
  await expect(statusTags.first()).toBeVisible();
});

test('release and rollback pages initialize keyword filters from query', async ({ page }) => {
  await page.goto('/release/orders?keyword=REL-WORKSPACE-JUMP');
  await expect(page.getByPlaceholder('发布单号')).toHaveValue('REL-WORKSPACE-JUMP');
  await expect(page.getByText('单号：REL-WORKSPACE-JUMP')).toBeVisible();

  await page.goto('/release/rollbacks?keyword=RB-WORKSPACE-JUMP');
  await expect(page.getByPlaceholder('回滚单号')).toHaveValue('RB-WORKSPACE-JUMP');
  await expect(page.getByText('单号：RB-WORKSPACE-JUMP')).toBeVisible();
});

// V1.26: Focus highlight from workspace
test('release orders page highlights focused row from workspace', async ({ page }) => {
  await page.goto('/release/orders');
  const orderNo = await getFirstRowFirstCellOrNull(page);

  if (!orderNo) {
    // No data available, skip the highlight test but verify page loads
    await page.goto('/release/orders?keyword=REL-FOCUS-TEST&focus=REL-FOCUS-TEST');
    await expect(page.getByPlaceholder('发布单号')).toHaveValue('REL-FOCUS-TEST');
    return;
  }

  // Navigate with focus on the real orderNo
  await page.goto(`/release/orders?keyword=${encodeURIComponent(orderNo)}&focus=${encodeURIComponent(orderNo)}`);
  await expect(page.getByPlaceholder('发布单号')).toHaveValue(orderNo);
  // Verify the focused row has highlight class
  const focusedRow = page.locator('.el-table .is-focused-from-workspace');
  await expect(focusedRow).toBeVisible();
});

test('rollback orders page highlights focused row from workspace', async ({ page }) => {
  await page.goto('/release/rollbacks');
  const rollbackNo = await getFirstRowFirstCellOrNull(page);

  if (!rollbackNo) {
    // No data available, skip the highlight test but verify page loads
    await page.goto('/release/rollbacks?keyword=RB-FOCUS-TEST&focus=RB-FOCUS-TEST');
    await expect(page.getByPlaceholder('回滚单号')).toHaveValue('RB-FOCUS-TEST');
    return;
  }

  // Navigate with focus on the real rollbackNo
  await page.goto(`/release/rollbacks?keyword=${encodeURIComponent(rollbackNo)}&focus=${encodeURIComponent(rollbackNo)}`);
  await expect(page.getByPlaceholder('回滚单号')).toHaveValue(rollbackNo);
  // Verify the focused row has highlight class
  const focusedRow = page.locator('.el-table .is-focused-from-workspace');
  await expect(focusedRow).toBeVisible();
});

// B2: Resource runtime view enhancement - display names and navigation
test('resource pool page has view hosts action and navigation works', async ({ page }) => {
  await sidebarLink(page, '资源池').click();
  await expect(page.getByRole('heading', { name: '资源池' })).toBeVisible();
  await expect(page.locator('.el-table')).toBeVisible();

  // B2: Verify "查看主机" action column exists
  await expect(page.locator('.el-table__header').getByText('操作')).toBeVisible();

  const firstRow = page.locator('.el-table__body-wrapper .el-table__row').first();
  if (await firstRow.count()) {
    const viewHostsBtn = firstRow.getByRole('button', { name: '查看主机' });
    await expect(viewHostsBtn).toBeVisible();

    // B2: Click the button and verify navigation with resourcePoolId in URL
    await viewHostsBtn.click();
    await expect(page).toHaveURL(/\/resource\/hosts\?resourcePoolId=\d+/);
    await expect(page.getByRole('heading', { name: '主机管理' })).toBeVisible();
  }
});

test('hosts page shows environment and resource pool display names with navigation', async ({ page }) => {
  await sidebarLink(page, '主机管理').click();
  await expect(page.getByRole('heading', { name: '主机管理' })).toBeVisible();
  await expect(page.locator('.el-table')).toBeVisible();

  // B2: Verify display name columns instead of raw IDs
  await expect(page.locator('.el-table__header').getByText('环境')).toBeVisible();
  await expect(page.locator('.el-table__header').getByText('资源池')).toBeVisible();

  // B2: Verify filter bar has select dropdowns (check by count - should have multiple selects)
  const filterBar = page.locator('.filter-bar');
  await expect(filterBar.locator('.el-select')).toHaveCount(4, { timeout: 10000 }); // env, pool, hostStatus, agentStatus

  // B2: Verify "查看运行实例" action column exists
  await expect(page.locator('.el-table__header').getByText('操作')).toBeVisible();

  const firstRow = page.locator('.el-table__body-wrapper .el-table__row').first();
  if (await firstRow.count()) {
    const viewInstancesBtn = firstRow.getByRole('button', { name: '查看运行实例' });
    await expect(viewInstancesBtn).toBeVisible();

    // B2: Click the button and verify navigation with hostId in URL
    await viewInstancesBtn.click();
    await expect(page).toHaveURL(/\/resource\/runtime-instances\?hostId=\d+/);
    await expect(page.getByRole('heading', { name: '运行实例' })).toBeVisible();
  }
});

test('runtime instances page shows service, environment and host display names', async ({ page }) => {
  await sidebarLink(page, '运行实例').click();
  await expect(page.getByRole('heading', { name: '运行实例' })).toBeVisible();
  await expect(page.locator('.el-table')).toBeVisible();

  // B2: Verify display name columns instead of raw IDs
  await expect(page.locator('.el-table__header').getByText('服务')).toBeVisible();
  await expect(page.locator('.el-table__header').getByText('环境')).toBeVisible();
  await expect(page.locator('.el-table__header').getByText('主机')).toBeVisible();

  // B2: Verify filter bar has select dropdowns (check by count - should have multiple selects)
  const filterBar = page.locator('.filter-bar');
  await expect(filterBar.locator('.el-select')).toHaveCount(5, { timeout: 10000 }); // service, env, host, instanceStatus, healthStatus
});

test('hosts page initializes filters from query params', async ({ page }) => {
  // B2: Navigate with query params
  await page.goto('/resource/hosts?keyword=test-host&envId=1&resourcePoolId=2');

  // B2: Verify filters are initialized from query
  await expect(page.getByPlaceholder('搜索主机名或IP')).toHaveValue('test-host');
  // Note: Select values may not be set if options don't exist, but page should load without error
  await expect(page.getByRole('heading', { name: '主机管理' })).toBeVisible();
});

test('runtime instances page initializes filters from query params', async ({ page }) => {
  // B2: Navigate with query params
  await page.goto('/resource/runtime-instances?keyword=test-instance&serviceId=1&envId=2&hostId=3');

  // B2: Verify filters are initialized from query
  await expect(page.getByPlaceholder('搜索实例名称')).toHaveValue('test-instance');
  // Note: Select values may not be set if options don't exist, but page should load without error
  await expect(page.getByRole('heading', { name: '运行实例' })).toBeVisible();
});

// B3: Monitor center pages - need to expand monitor group first
test('monitor targets page can be opened', async ({ page }) => {
  // First expand the monitor group in sidebar
  await page.locator('aside.layout-sidebar').getByText('监控中心').click();
  await sidebarLink(page, '监控目标').click();
  await expect(page.getByRole('heading', { name: '监控目标' })).toBeVisible();
  await expect(page.getByPlaceholder('搜索监控Key')).toBeVisible();
  await expect(page.locator('.el-table')).toBeVisible();
  await expect(page.locator('.el-table__header').getByText('操作')).toBeVisible();
});

test('monitor snapshots page can be opened', async ({ page }) => {
  await page.locator('aside.layout-sidebar').getByText('监控中心').click();
  await sidebarLink(page, '监控快照').click();
  await expect(page.getByRole('heading', { name: '监控快照' })).toBeVisible();
  await expect(page.locator('.el-table')).toBeVisible();
  await expect(page.locator('.el-table__header').getByText('操作')).toBeVisible();
});

test('monitor alert rules page can be opened', async ({ page }) => {
  await page.locator('aside.layout-sidebar').getByText('监控中心').click();
  await sidebarLink(page, '告警规则').click();
  await expect(page.getByRole('heading', { name: '告警规则' })).toBeVisible();
  await expect(page.locator('.el-table')).toBeVisible();
  // Verify level and enabled filters exist
  const filterBar = page.locator('.filter-bar');
  await expect(filterBar.locator('.el-select').first()).toBeVisible();
});

test('monitor alert events page can be opened', async ({ page }) => {
  await page.locator('aside.layout-sidebar').getByText('监控中心').click();
  await sidebarLink(page, '告警事件').click();
  await expect(page.getByRole('heading', { name: '告警事件' })).toBeVisible();
  await expect(page.locator('.el-table')).toBeVisible();
});

test('monitor health checks page can be opened', async ({ page }) => {
  await page.locator('aside.layout-sidebar').getByText('监控中心').click();
  await sidebarLink(page, '健康检查').click();
  await expect(page.getByRole('heading', { name: '健康检查' })).toBeVisible();
  await expect(page.locator('.el-table')).toBeVisible();
});
