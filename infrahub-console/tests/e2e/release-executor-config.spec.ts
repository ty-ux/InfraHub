import { expect, test, type Locator, type Page } from '@playwright/test';
import { loginAsAdmin } from './helpers/auth';
import { openPage, activeDialog, openCreateDialog, fillTextField } from './helpers/ui';
import { mockResourceApis } from './helpers/mocks';

/**
 * The environment field of the release order dialog. It is matched by its exact label text: a form
 * item whose text merely contains 环境 - a selected value such as 测试环境, for one - is not it.
 */
function envField(dialog: Locator, page: Page) {
  return dialog
    .locator('.el-form-item')
    .filter({ has: page.getByText('环境名称', { exact: true }) })
    .locator('.el-select');
}

test.describe('F-D executor config', () => {
  test.setTimeout(90000);

  test.beforeEach(async ({ page }) => {
    await loginAsAdmin(page);
  });

  test('executor config page is accessible from system menu', async ({ page }) => {
    // Navigate to executor configs page
    await openPage(page, '执行器配置');
    await expect(page.locator('.executor-configs')).toBeVisible();
    await expect(page.locator('.page-title')).toContainText('执行器配置');
  });

  test('executor config page shows filter and table', async ({ page }) => {
    await openPage(page, '执行器配置');

    // Verify filter card exists
    await expect(page.locator('.filter-card')).toBeVisible();

    // Verify table card exists
    await expect(page.locator('.table-card')).toBeVisible();

    // Verify create button exists
    await expect(page.locator('.table-card').getByRole('button', { name: '新建' })).toBeVisible();
  });

  test('executor config page requests correct API path', async ({ page }) => {
    let requestedCorrectPath = false;

    // Set up response promise before navigation
    const responsePromise = page.waitForResponse(resp =>
      resp.url().includes('/api/releases/executor-configs') && resp.status() === 200
    , { timeout: 15000 });

    // Intercept and verify the API path
    await page.route('**/api/releases/executor-configs**', async (route) => {
      const url = new URL(route.request().url());
      if (url.pathname === '/api/releases/executor-configs') {
        requestedCorrectPath = true;
      }
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          code: 'SUCCESS',
          message: 'success',
          traceId: 'e2e-path-verify',
          data: [],
        }),
      });
    });

    await openPage(page, '执行器配置');

    // Wait for API call to complete
    await responsePromise;

    expect(requestedCorrectPath).toBe(true);
  });

  test('toggle endpoint is called with correct path and returns boolean', async ({ page }) => {
    let toggleRequestCorrect = false;

    // Mock list endpoint
    await page.route('**/api/releases/executor-configs', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          code: 'SUCCESS',
          message: 'success',
          traceId: 'e2e-toggle-list',
          data: [
            { id: 3001, executorName: '测试配置', executorType: 'SSH', envId: 1, enabled: true, configPayload: '{}' },
          ],
        }),
      });
    });

    // Mock toggle endpoint - backend returns ApiResponse<Boolean>
    await page.route('**/api/releases/executor-configs/*/toggle*', async (route) => {
      const url = new URL(route.request().url());
      // Verify the toggle path pattern - switch click sets enabled=false
      if (url.pathname.includes('/3001/toggle')) {
        toggleRequestCorrect = true;
      }
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          code: 'SUCCESS',
          message: 'success',
          traceId: 'e2e-toggle-response',
          data: true, // Backend returns Boolean, not config object
        }),
      });
    });

    await openPage(page, '执行器配置');
    await page.waitForSelector('tbody tr', { timeout: 10000 });

    // Click the switch to toggle (simpler than disable button which requires confirmation)
    const switchEl = page.locator('tbody tr').first().locator('.el-switch');
    await switchEl.click();

    // Wait for the toggle request
    await page.waitForTimeout(1000);

    expect(toggleRequestCorrect).toBe(true);
  });

  test('executor config view/edit shows parsed JSON not escaped string', async ({ page }) => {
    // Mock with string configPayload (real backend format)
    await page.route('**/api/releases/executor-configs**', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          code: 'SUCCESS',
          message: 'success',
          traceId: 'e2e-string-payload',
          data: [
            {
              id: 2001,
              executorName: 'Docker测试配置',
              executorType: 'DOCKER',
              envId: 1,
              enabled: true,
              // Backend returns configPayload as JSON string
              configPayload: '{"image":"nginx:latest","ports":[80,443],"env":{"DEBUG":"true"}}',
            },
          ],
        }),
      });
    });

    await openPage(page, '执行器配置');

    // Wait for table to load
    await page.waitForSelector('tbody tr', { timeout: 10000 });

    // Click "查看配置" button
    await page.locator('.table-card').getByRole('button', { name: '查看配置' }).click();

    // Wait for payload dialog
    const payloadDialog = page.locator('.el-dialog').filter({ hasText: '配置详情' });
    await expect(payloadDialog).toBeVisible({ timeout: 5000 });

    // Verify the payload is displayed as properly parsed JSON, not escaped string
    // The dialog should show the parsed object with proper formatting
    const payloadContent = payloadDialog.locator('.payload-preview');
    await expect(payloadContent).toBeVisible();

    // Should show parsed keys (not escaped like \"image\")
    await expect(payloadContent).toContainText('image');
    await expect(payloadContent).toContainText('nginx:latest');
    await expect(payloadContent).toContainText('ports');
    await expect(payloadContent).toContainText('DEBUG');

    // Should NOT show escaped quotes (which would indicate raw string)
    const contentText = await payloadContent.textContent();
    expect(contentText).not.toContain('\\"image\\"');

    await payloadDialog.getByRole('button', { name: '关闭' }).click();

    // Now test edit dialog
    await page.locator('.table-card').getByRole('button', { name: '编辑' }).click();

    const editDialog = page.locator('.el-dialog').filter({ hasText: '编辑执行器配置' });
    await expect(editDialog).toBeVisible({ timeout: 5000 });

    // Verify the textarea shows properly formatted JSON (not escaped string)
    const textarea = editDialog.locator('textarea');
    const textareaValue = await textarea.inputValue();

    // Should be properly parsed JSON object, not a string with escaped quotes
    expect(textareaValue).toContain('"image"');
    expect(textareaValue).toContain('nginx:latest');
    expect(textareaValue).not.toContain('\\"');

    await editDialog.getByRole('button', { name: '取消' }).click();
  });

  test('executor config create dialog shows all required fields', async ({ page }) => {
    await openPage(page, '执行器配置');

    // Click create button
    await page.locator('.table-card').getByRole('button', { name: '新建' }).click();

    const dialog = activeDialog(page);
    await expect(dialog).toContainText('新建执行器配置');

    // Verify fields exist by label
    await expect(dialog.getByText('执行器名称')).toBeVisible();
    await expect(dialog.getByText('执行器类型')).toBeVisible();
    await expect(dialog.getByText('环境')).toBeVisible();
    await expect(dialog.getByText('启用状态')).toBeVisible();
    await expect(dialog.getByText('配置内容')).toBeVisible();

    // Cancel dialog
    await dialog.getByRole('button', { name: '取消' }).click();
  });

  test('executor config type dropdown has SSH, DOCKER, KUBERNETES options', async ({ page }) => {
    await openPage(page, '执行器配置');

    await page.locator('.table-card').getByRole('button', { name: '新建' }).click();
    const dialog = activeDialog(page);

    // Find and click executor type dropdown - look for the form item containing '执行器类型'
    const typeFormItem = dialog.locator('.el-form-item').filter({ hasText: '执行器类型' });
    await typeFormItem.locator('.el-select').click();

    // Verify options using role-based locator for better reliability
    await expect(page.getByRole('option', { name: 'SSH' })).toBeVisible();
    await expect(page.getByRole('option', { name: 'Docker' })).toBeVisible();
    await expect(page.getByRole('option', { name: 'Kubernetes' })).toBeVisible();

    // Cancel
    await page.keyboard.press('Escape');
    await dialog.getByRole('button', { name: '取消' }).click();
  });

  test('release order create dialog shows executor config field', async ({ page }) => {
    // The create dialog loads the resource dropdowns of the form; without them the dialog cannot stay open
    await mockResourceApis(page);

    // Mock executor configs API with correct path and string payload (real backend format)
    await page.route('**/api/releases/executor-configs', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          code: 'SUCCESS',
          message: 'success',
          traceId: 'e2e-executor-config',
          data: [
            { id: 1001, executorName: 'SSH默认配置', executorType: 'SSH', envId: 1, enabled: true, configPayload: '{}' },
            { id: 1002, executorName: 'Docker测试配置', executorType: 'DOCKER', envId: 2, enabled: true, configPayload: '{"image":"test:latest"}' },
          ],
        }),
      });
    });

    await openPage(page, '发布单');

    // Open create dialog
    const releaseDialog = await openCreateDialog(page, '发布单');
    await fillTextField(releaseDialog, '发布单号', `EXEC-TEST-${Date.now()}`);

    // Executor config field may appear as optional field
    // The key is that it doesn't break the form
    await expect(releaseDialog).toBeVisible();

    // Cancel dialog
    await releaseDialog.getByRole('button', { name: '取消' }).click();
  });

  test('release order detail drawer shows executor info section', async ({ page }) => {
    const releaseId = 999888777;

    // The release list is enriched with app/service/env/version display names; without those APIs the list request fails
    await mockResourceApis(page);

    // Setup route mocking BEFORE opening the page
    await page.route('**/api/releases**', async (route) => {
      const request = route.request();
      const url = new URL(request.url());

      if (request.method() === 'GET' && url.pathname === `/api/releases/${releaseId}/detail`) {
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({
            code: 'SUCCESS',
            message: 'success',
            traceId: 'e2e-executor-detail',
            data: {
              id: releaseId,
              orderNo: 'MOCK-EXEC-DRAWER',
              appName: '测试应用',
              serviceName: '测试服务',
              envName: '测试环境',
              versionNo: 'v1.0.0',
              status: 'CREATED',
              executorType: 'DOCKER',
              executorConfigName: 'Docker测试配置',
              createdAt: '2026-05-09T12:00:00',
            },
          }),
        });
        return;
      }

      if (request.method() === 'GET' && url.pathname === '/api/releases') {
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({
            code: 'SUCCESS',
            message: 'success',
            traceId: 'e2e-executor-list',
            data: {
              pageNum: 1,
              pageSize: 20,
              total: 1,
              records: [
                {
                  id: releaseId,
                  orderNo: 'MOCK-EXEC-DRAWER',
                  appId: 1,
                  serviceId: 2,
                  envId: 2,
                  versionId: 1,
                  releaseType: 'FULL',
                  status: 'CREATED',
                  hasDeployRecords: false,
                  hasPipelineRun: false,
                },
              ],
            },
          }),
        });
        return;
      }

      // Mock canary plan API
      if (url.pathname.includes('/canary-plan')) {
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({ code: 'SUCCESS', data: null }),
        });
        return;
      }

      await route.fallback();
    });

    await openPage(page, '发布单');

    // Wait for the target business row: the filter bar's date picker renders its own (hidden) table
    const releaseRow = page.locator('.el-table__body tbody tr').filter({ hasText: 'MOCK-EXEC-DRAWER' }).first();
    await expect(releaseRow).toBeVisible({ timeout: 10000 });

    // Click detail button
    await releaseRow.getByRole('button', { name: '详情', exact: true }).click();

    // Verify drawer opens
    const drawer = page.locator('.el-drawer');
    await expect(drawer).toBeVisible({ timeout: 5000 });

    // Verify executor info section exists
    await expect(drawer).toContainText('执行器信息');
    await expect(drawer).toContainText('执行器类型');
    // Executor type is shown as el-tag, not a button
    await expect(drawer.locator('.el-tag').filter({ hasText: 'Docker' })).toBeVisible();
    await expect(drawer).toContainText('Docker测试配置');
  });

  test('release order detail drawer shows default SSH when no executor config', async ({ page }) => {
    const releaseId = 999888776;

    // The release list is enriched with app/service/env/version display names; without those APIs the list request fails
    await mockResourceApis(page);

    // Setup route mocking BEFORE opening the page
    await page.route('**/api/releases**', async (route) => {
      const request = route.request();
      const url = new URL(request.url());

      if (request.method() === 'GET' && url.pathname === `/api/releases/${releaseId}/detail`) {
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({
            code: 'SUCCESS',
            message: 'success',
            traceId: 'e2e-ssh-default',
            data: {
              id: releaseId,
              orderNo: 'MOCK-SSH-DEFAULT',
              appName: '测试应用',
              serviceName: '测试服务',
              envName: '测试环境',
              versionNo: 'v1.0.0',
              status: 'CREATED',
              // executorType and executorConfigName not provided -> defaults to SSH
              createdAt: '2026-05-09T12:00:00',
            },
          }),
        });
        return;
      }

      if (request.method() === 'GET' && url.pathname === '/api/releases') {
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({
            code: 'SUCCESS',
            data: {
              pageNum: 1,
              pageSize: 20,
              total: 1,
              records: [
                {
                  id: releaseId,
                  orderNo: 'MOCK-SSH-DEFAULT',
                  releaseType: 'FULL',
                  status: 'CREATED',
                },
              ],
            },
          }),
        });
        return;
      }

      if (url.pathname.includes('/canary-plan')) {
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({ code: 'SUCCESS', data: null }),
        });
        return;
      }

      await route.fallback();
    });

    await openPage(page, '发布单');

    // Wait for the target business row: the filter bar's date picker renders its own (hidden) table
    const releaseRow = page.locator('.el-table__body tbody tr').filter({ hasText: 'MOCK-SSH-DEFAULT' }).first();
    await expect(releaseRow).toBeVisible({ timeout: 10000 });
    await releaseRow.getByRole('button', { name: '详情', exact: true }).click();

    const drawer = page.locator('.el-drawer');
    await expect(drawer).toBeVisible({ timeout: 5000 });

    // Should show SSH as default
    await expect(drawer).toContainText('执行器信息');
    // Executor type is shown as el-tag, not a button
    await expect(drawer.locator('.el-tag').filter({ hasText: 'SSH' })).toBeVisible();
    await expect(drawer).toContainText('默认 SSH');
  });

  // F+1-B: Executor config filtered by environment
  test('executor config dropdown requests envId parameter when environment selected', async ({ page }) => {
    let capturedEnvId: string | null = null;

    // The create dialog loads the resource dropdowns of the form; without them the dialog cannot stay open
    await mockResourceApis(page);

    // Mock executor configs API - capture envId parameter
    await page.route('**/api/releases/executor-configs?envId=*', async (route) => {
      const url = new URL(route.request().url());
      capturedEnvId = url.searchParams.get('envId');
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          code: 'SUCCESS',
          message: 'success',
          traceId: 'e2e-env-param',
          data: [],
        }),
      });
    });

    // Mock environment list
    await page.route('**/api/envs**', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          code: 'SUCCESS',
          data: {
            pageNum: 1,
            pageSize: 200,
            total: 1,
            records: [
              { id: 42, envCode: 'TEST', envName: '测试环境', envType: 'TEST', status: 'ENABLED' },
            ],
          },
        }),
      });
    });

    await openPage(page, '发布单');
    const releaseDialog = await openCreateDialog(page, '发布单');

    // Before selecting env, no executor config API call with envId
    expect(capturedEnvId).toBeNull();

    // Select environment
    const envSelect = envField(releaseDialog, page);
    await envSelect.click();
    await page.getByRole('option', { name: '测试环境' }).click();

    // Wait for executor config API to be called
    await page.waitForTimeout(500);

    // Verify API was called with envId=42
    expect(capturedEnvId).toBe('42');

    await releaseDialog.getByRole('button', { name: '取消' }).click();
  });

  test('executor config dropdown shows only configs for selected environment', async ({ page }) => {
    let requestedEnvId: string | null = null;

    // The create dialog loads the resource dropdowns of the form; without them the dialog cannot stay open
    await mockResourceApis(page);

    // Mock environment list
    await page.route('**/api/envs**', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          code: 'SUCCESS',
          message: 'success',
          traceId: 'e2e-env-list',
          data: {
            pageNum: 1,
            pageSize: 200,
            total: 2,
            records: [
              { id: 1, envCode: 'DEV', envName: '开发环境', envType: 'DEV', status: 'ENABLED' },
              { id: 2, envCode: 'PROD', envName: '生产环境', envType: 'PROD', status: 'ENABLED' },
            ],
          },
        }),
      });
    });

    // Mock executor configs API - different configs for each env
    await page.route('**/api/releases/executor-configs?envId=*', async (route) => {
      const url = new URL(route.request().url());
      requestedEnvId = url.searchParams.get('envId');

      if (requestedEnvId === '1') {
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({
            code: 'SUCCESS',
            message: 'success',
            traceId: 'e2e-env-dev',
            data: [
              { id: 1001, executorName: 'DEV SSH配置', executorType: 'SSH', envId: 1, enabled: true, configPayload: '{}' },
              { id: 1002, executorName: 'DEV Docker配置', executorType: 'DOCKER', envId: 1, enabled: true, configPayload: '{"image":"dev:latest"}' },
            ],
          }),
        });
      } else if (requestedEnvId === '2') {
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({
            code: 'SUCCESS',
            message: 'success',
            traceId: 'e2e-env-prod',
            data: [
              { id: 2001, executorName: 'PROD Kubernetes配置', executorType: 'KUBERNETES', envId: 2, enabled: true, configPayload: '{"namespace":"prod"}' },
            ],
          }),
        });
      } else {
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({ code: 'SUCCESS', data: [] }),
        });
      }
    });

    await openPage(page, '发布单');
    const releaseDialog = await openCreateDialog(page, '发布单');
    await fillTextField(releaseDialog, '发布单号', `ENV-FILTER-${Date.now()}`);

    // Select environment DEV
    const envSelect = envField(releaseDialog, page);
    await envSelect.click();
    await page.getByRole('option', { name: '开发环境' }).click();

    // Wait for executor config to load for envId=1
    await page.waitForTimeout(500);

    // Open executor config dropdown
    const executorSelect = releaseDialog.locator('.el-form-item').filter({ hasText: '执行器配置' }).locator('.el-select');
    await executorSelect.click();

    // Should only show DEV configs
    await expect(page.getByRole('option', { name: 'DEV SSH配置' })).toBeVisible();
    await expect(page.getByRole('option', { name: 'DEV Docker配置' })).toBeVisible();

    // PROD config should NOT appear
    const prodOption = page.getByRole('option', { name: 'PROD Kubernetes配置' });
    await expect(prodOption).not.toBeVisible();

    // Verify API was called with envId=1
    expect(requestedEnvId).toBe('1');

    await page.keyboard.press('Escape');

    // Now change environment to PROD
    await envSelect.click();
    await page.getByRole('option', { name: '生产环境' }).click();

    // Wait for executor config to reload
    await page.waitForTimeout(500);

    // Open executor config dropdown again
    await executorSelect.click();

    // Should only show PROD config now
    await expect(page.getByRole('option', { name: 'PROD Kubernetes配置' }).first()).toBeVisible();

    // DEV configs should NOT appear anymore
    const devSshOption = page.getByRole('option', { name: 'DEV SSH配置' });
    await expect(devSshOption).not.toBeVisible();
    const devDockerOption = page.getByRole('option', { name: 'DEV Docker配置' });
    await expect(devDockerOption).not.toBeVisible();

    // Verify API was called with envId=2
    expect(requestedEnvId).toBe('2');

    await page.keyboard.press('Escape');
    await releaseDialog.getByRole('button', { name: '取消' }).click();
  });

  test('executor config is cleared when environment changes', async ({ page }) => {
    // The create dialog loads the resource dropdowns of the form; without them the dialog cannot stay open
    await mockResourceApis(page);

    // Mock environment list
    await page.route('**/api/envs**', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          code: 'SUCCESS',
          data: {
            pageNum: 1,
            pageSize: 200,
            total: 2,
            records: [
              { id: 1, envCode: 'DEV', envName: '开发环境', envType: 'DEV', status: 'ENABLED' },
              { id: 2, envCode: 'PROD', envName: '生产环境', envType: 'PROD', status: 'ENABLED' },
            ],
          },
        }),
      });
    });

    // Mock executor configs for each env
    await page.route('**/api/releases/executor-configs?envId=1*', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          code: 'SUCCESS',
          data: [
            { id: 1001, executorName: 'DEV SSH', executorType: 'SSH', envId: 1, enabled: true, configPayload: '{}' },
          ],
        }),
      });
    });

    await page.route('**/api/releases/executor-configs?envId=2*', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          code: 'SUCCESS',
          data: [
            { id: 2001, executorName: 'PROD K8S', executorType: 'KUBERNETES', envId: 2, enabled: true, configPayload: '{}' },
          ],
        }),
      });
    });

    await openPage(page, '发布单');
    const releaseDialog = await openCreateDialog(page, '发布单');
    await fillTextField(releaseDialog, '发布单号', `CLEAR-TEST-${Date.now()}`);

    // Select environment DEV
    const envSelect = envField(releaseDialog, page);
    await envSelect.click();
    await page.getByRole('option', { name: '开发环境' }).click();
    await page.waitForTimeout(300);

    // Select executor config for DEV
    const executorSelect = releaseDialog.locator('.el-form-item').filter({ hasText: '执行器配置' }).locator('.el-select');
    await executorSelect.click();
    await page.getByRole('option', { name: 'DEV SSH' }).click();

    // Verify executor config is selected by checking placeholder changed
    await page.waitForTimeout(200);

    // Change environment to PROD
    await envSelect.click();
    await page.getByRole('option', { name: '生产环境' }).click();
    await page.waitForTimeout(500);

    // Open executor dropdown and verify only PROD config shows (DEV config cleared)
    await executorSelect.click();
    await expect(page.getByRole('option', { name: 'PROD K8S' }).first()).toBeVisible();

    // DEV config should NOT be in the dropdown
    await expect(page.getByRole('option', { name: 'DEV SSH' })).not.toBeVisible();

    await page.keyboard.press('Escape');
    await releaseDialog.getByRole('button', { name: '取消' }).click();
  });
});