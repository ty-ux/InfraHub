import type { Page } from '@playwright/test';

/**
 * Standard test data IDs used across E2E tests.
 * These IDs are used by release-regression.spec.ts, release-approval.spec.ts, etc.
 */
export const TEST_DATA = {
  // Apps
  APP_ORDER_PLATFORM: { id: 1, appCode: 'order-platform', appName: '订单协同平台-后端' },
  APP_INFRAHUB: { id: 2, appCode: 'infrahub', appName: 'InfraHub' },
  APP_TEST: { id: 10, appCode: 'test-app', appName: '测试应用' },

  // Services
  SVC_ORDER_PLATFORM: { id: 101, serviceCode: 'order-platform-backend', serviceName: '订单协同平台后端服务', appId: 1 },
  SVC_INFRAHUB: { id: 102, serviceCode: 'infrahub-backend', serviceName: 'InfraHub 后端服务', appId: 2 },
  SVC_TEST: { id: 30, serviceCode: 'test-svc', serviceName: '测试服务', appId: 10 },

  // Environments
  ENV_TEST: { id: 20, envCode: 'test-env', envName: '测试环境' },

  // Versions
  VERSION_V100: { id: 1001, versionNo: 'v1.0.0' },
  VERSION_V101: { id: 1002, versionNo: 'v1.0.1' },
  VERSION_V200: { id: 1003, versionNo: 'v2.0.0' },
  VERSION_REAL_DEPLOY: { id: 1004, versionNo: 'infrahub-real-deploy-20260413-1' },

  // Risk levels
  RISK_LOW: 'LOW',
  RISK_MEDIUM: 'MEDIUM',
  RISK_HIGH: 'HIGH',

  // Trigger types
  TRIGGER_MANUAL: 'MANUAL',

  // Pipeline types
  PIPELINE_DEPLOY: 'DEPLOY',

  // Executor types
  EXECUTOR_LOCAL_SHELL: 'LOCAL_SSH',
  EXECUTOR_SSH: 'SSH',
};

/**
 * Mock all resource APIs needed for page data enrichment and create dialogs.
 * This ensures that enrichReleaseOrders / enrichRollbackOrders don't fail
 * when looking up display names for apps, services, envs, and versions.
 * Also ensures that create dialogs have options for dropdown selects.
 */
export async function mockResourceApis(page: Page): Promise<void> {
  // Mock appApi.page - returns app data for dropdowns and enrichment
  await page.route('**/api/apps**', async (route) => {
    const request = route.request();
    const url = new URL(request.url());

    if (request.method() === 'GET' && url.pathname === '/api/apps') {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          code: 'SUCCESS',
          data: {
            pageNum: 1,
            pageSize: 200,
            total: 3,
            records: [
              TEST_DATA.APP_ORDER_PLATFORM,
              TEST_DATA.APP_INFRAHUB,
              TEST_DATA.APP_TEST,
            ],
          },
        }),
      });
      return;
    }
    await route.fallback();
  });

  // Mock serviceApi.page - returns service data for dropdowns and enrichment
  await page.route('**/api/services**', async (route) => {
    const request = route.request();
    const url = new URL(request.url());

    if (request.method() === 'GET' && url.pathname === '/api/services') {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          code: 'SUCCESS',
          data: {
            pageNum: 1,
            pageSize: 200,
            total: 3,
            records: [
              TEST_DATA.SVC_ORDER_PLATFORM,
              TEST_DATA.SVC_INFRAHUB,
              TEST_DATA.SVC_TEST,
            ],
          },
        }),
      });
      return;
    }
    await route.fallback();
  });

  // Mock envApi.page - returns env data for dropdowns and enrichment
  await page.route('**/api/envs**', async (route) => {
    const request = route.request();
    const url = new URL(request.url());

    if (request.method() === 'GET' && url.pathname === '/api/envs') {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          code: 'SUCCESS',
          data: {
            pageNum: 1,
            pageSize: 200,
            total: 1,
            records: [TEST_DATA.ENV_TEST],
          },
        }),
      });
      return;
    }
    await route.fallback();
  });

  // Mock versionApi.page - returns version data for dropdowns and enrichment
  await page.route('**/api/versions**', async (route) => {
    const request = route.request();
    const url = new URL(request.url());

    if (request.method() === 'GET' && url.pathname === '/api/versions') {
      const allRecords = [
        { ...TEST_DATA.VERSION_V100, appId: TEST_DATA.APP_ORDER_PLATFORM.id },
        { ...TEST_DATA.VERSION_V101, appId: TEST_DATA.APP_ORDER_PLATFORM.id },
        { ...TEST_DATA.VERSION_V200, appId: TEST_DATA.APP_ORDER_PLATFORM.id },
        { ...TEST_DATA.VERSION_REAL_DEPLOY, appId: TEST_DATA.APP_INFRAHUB.id },
      ];
      const appId = url.searchParams.get('appId');
      const records = appId ? allRecords.filter((r) => r.appId === Number(appId)) : allRecords;
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          code: 'SUCCESS',
          data: { pageNum: 1, pageSize: 200, total: records.length, records },
        }),
      });
      return;
    }
    await route.fallback();
  });

  // Runtime service instances: list, status, logs, deploy-config
  await page.route('**/api/runtime/service-instances**', async (route) => {
    const request = route.request();
    const url = new URL(request.url());
    const method = request.method();

    // List instances by serviceId/envId
    if (method === 'GET' && url.pathname === '/api/runtime/service-instances') {
      const serviceId = Number(url.searchParams.get('serviceId') || '0');
      // Only InfraHub backend (serviceId=102) has a runtime instance
      if (serviceId !== 102) {
        await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ code: 'SUCCESS', data: [] }) });
        return;
      }
      await route.fulfill({
        status: 200, contentType: 'application/json',
        body: JSON.stringify({
          code: 'SUCCESS',
          data: [{ id: 9001, serviceId, envId: 20, hostId: 30, instanceName: 'infrahub-web-1', instanceType: 'PROCESS', instanceStatus: 'RUNNING', healthStatus: 'UP', createdAt: '2026-05-01T00:00:00', updatedAt: '2026-05-20T10:00:00' }],
        }),
      });
      return;
    }

    // Status
    const statusMatch = url.pathname.match(/^\/api\/runtime\/service-instances\/(\d+)\/status$/);
    if (method === 'GET' && statusMatch) {
      await route.fulfill({
        status: 200, contentType: 'application/json',
        body: JSON.stringify({
          code: 'SUCCESS',
          data: { instanceId: Number(statusMatch[1]), serviceId: 102, envId: 20, hostId: 30, running: true, pid: '12345', portListening: true, healthStatus: 'UP', healthBody: '{"status":"UP"}', checkedAt: '2026-05-20T10:00:00', stdout: '', stderr: '', errorMessage: '', deployPort: 8080 },
        }),
      });
      return;
    }

    // Logs
    const logsMatch = url.pathname.match(/^\/api\/runtime\/service-instances\/(\d+)\/logs$/);
    if (method === 'GET' && logsMatch) {
      await route.fulfill({
        status: 200, contentType: 'application/json',
        body: JSON.stringify({
          code: 'SUCCESS',
          data: { instanceId: Number(logsMatch[1]), serviceId: 102, envId: 20, hostId: 30, success: true, lines: 200, logPath: '/opt/infrahub/infrahub-web/logs/app.log', content: '2026-05-20 10:00:00 INFO  Started InfrahubApplication in 3.2 seconds', stdout: '', stderr: '', errorMessage: '', fetchedAt: '2026-05-20T10:00:00' },
        }),
      });
      return;
    }

    // Deploy config
    const configMatch = url.pathname.match(/^\/api\/runtime\/service-instances\/(\d+)\/deploy-config$/);
    if (method === 'GET' && configMatch) {
      await route.fulfill({
        status: 200, contentType: 'application/json',
        body: JSON.stringify({
          code: 'SUCCESS',
          data: { instanceId: Number(configMatch[1]), serviceId: 102, envId: 20, hostId: 30, deployRoot: '/opt/infrahub/infrahub-web', deployPort: 8080, pidFile: '/opt/infrahub/infrahub-web/app.pid', logFile: '/opt/infrahub/infrahub-web/logs/app.log', healthUrl: 'http://localhost:8080/actuator/health', source: 'CONFIG' },
        }),
      });
      return;
    }

    await route.fallback();
  });
}

/**
 * Mock releaseApi.page with custom data
 */
export async function mockReleaseList(page: Page, records: Record<string, unknown>[], total = 1): Promise<void> {
  await page.route('**/api/releases**', async (route) => {
    const request = route.request();
    const url = new URL(request.url());

    // Handle list endpoint
    if (request.method() === 'GET' && url.pathname === '/api/releases') {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          code: 'SUCCESS',
          data: {
            pageNum: 1,
            pageSize: 20,
            total,
            records,
          },
        }),
      });
      return;
    }

    // Handle canary-plan sub-resource
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
}

/**
 * Shared state returned by mockPipelineApis for cross-mock coordination.
 * Passed to mockReleaseAndRollbackApis so execute-pipeline can inject
 * pipeline runs and step runs into the pipeline mock's in-memory store.
 */
export interface PipelineMockState {
  runs: Record<string, unknown>[];
  stepRuns: Record<string, unknown>[];
  stages: Record<string, unknown>[];
  steps: Record<string, unknown>[];
  addPipelineRun(run: Record<string, unknown>, stepRuns: Record<string, unknown>[]): void;
}

/**
 * Mock all pipeline CRUD and run APIs with in-memory state.
 * Covers the full data chain: create pipeline → list → detail → stages → steps → run → step runs.
 * Returns PipelineMockState so other mocks can inject runs and step runs.
 */
export async function mockPipelineApis(page: Page): Promise<PipelineMockState> {
  const pipelines: Record<string, unknown>[] = [];
  const stages: Record<string, unknown>[] = [];
  const steps: Record<string, unknown>[] = [];
  const runs: Record<string, unknown>[] = [];
  const stepRuns: Record<string, unknown>[] = [];
  let nextId = 1000;
  const nid = () => nextId++;

  const addPipelineRun = (run: Record<string, unknown>, newStepRuns: Record<string, unknown>[]) => {
    runs.push(run);
    stepRuns.push(...newStepRuns);
  };

  const state: PipelineMockState = { runs, stepRuns, stages, steps, addPipelineRun };

  // Seed: pipeline run 99 linked to release order 89 (used by release-regression.spec.ts)
  runs.push({
    id: 99, pipelineId: 1, runStatus: 'SUCCESS', releaseId: 89,
    startTime: '2026-05-20T10:00:00', endTime: '2026-05-20T10:05:00',
    appId: 1, envId: 20,
  });
  stepRuns.push({
    id: nid(), pipelineRunId: 99, stepId: 1, stageName: '真实部署标识阶段 seed',
    stepName: '真实部署标识步骤 seed', runStatus: 'SUCCESS',
    logExcerpt: 'Deploy step completed', executorNode: 'mock-e2e',
    startTime: '2026-05-20T10:00:00', endTime: '2026-05-20T10:05:00', durationMs: 300000,
  });

  // GET /api/pipeline-step-runs — must be registered before /api/pipeline-stages** to avoid glob overlap
  await page.route('**/api/pipeline-step-runs**', async (route) => {
    const request = route.request();
    const url = new URL(request.url());
    if (request.method() === 'GET' && url.pathname === '/api/pipeline-step-runs') {
      const runId = Number(url.searchParams.get('pipelineRunId'));
      const pageNum = Number(url.searchParams.get('pageNum') || 1);
      const pageSize = Number(url.searchParams.get('pageSize') || 10);
      const filtered = stepRuns.filter((r) => r.pipelineRunId === runId);
      const start = (pageNum - 1) * pageSize;
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          code: 'SUCCESS',
          data: { pageNum, pageSize, total: filtered.length, records: filtered.slice(start, start + pageSize) },
        }),
      });
      return;
    }
    await route.fallback();
  });

  // GET/POST /api/pipeline-stages** — handles stages and steps sub-resource
  await page.route('**/api/pipeline-stages**', async (route) => {
    const request = route.request();
    const url = new URL(request.url());
    const method = request.method();

    // GET /api/pipeline-stages/{stageId}/steps
    const stepsMatch = url.pathname.match(/^\/api\/pipeline-stages\/(\d+)\/steps$/);
    if (method === 'GET' && stepsMatch) {
      const stageId = Number(stepsMatch[1]);
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ code: 'SUCCESS', data: steps.filter((s) => s.stageId === stageId) }),
      });
      return;
    }
    // POST /api/pipeline-stages/{stageId}/steps
    if (method === 'POST' && stepsMatch) {
      const stageId = Number(stepsMatch[1]);
      const body = request.postDataJSON();
      steps.push({ id: nid(), stageId, ...body });
      await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ code: 'SUCCESS', data: true }) });
      return;
    }
    // PUT/DELETE /api/pipeline-stages/{stageId} (no /steps suffix)
    if (url.pathname.match(/^\/api\/pipeline-stages\/\d+$/) && !url.pathname.endsWith('/steps')) {
      // Not needed by tests but handle gracefully
      await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ code: 'SUCCESS', data: true }) });
      return;
    }
    await route.fallback();
  });

  // GET/POST /api/pipelines/{id}/run
  await page.route('**/api/pipelines/*/run', async (route) => {
    const request = route.request();
    const url = new URL(request.url());
    const match = url.pathname.match(/^\/api\/pipelines\/(\d+)\/run$/);
    if (request.method() === 'POST' && match) {
      const pipelineId = Number(match[1]);
      const runId = nid();
      const pipeline = pipelines.find((p) => p.id === pipelineId);
      // Create step run records for each step in each stage
      const pipelineStages = stages.filter((s) => s.pipelineId === pipelineId);
      for (const stage of pipelineStages) {
        const stageSteps = steps.filter((s) => s.stageId === stage.id);
        for (const step of stageSteps) {
          const isDeploy = String(step.executorType || '').toUpperCase() === 'RELEASE_DEPLOY';
          const script = String(step.scriptContent || '');
          const echoMatch = script.match(/echo\s+(.+)/);
          const logExcerpt = isDeploy ? 'Deploy step completed' : (echoMatch ? echoMatch[1].trim() : `Mock output for ${step.stepName}`);
          stepRuns.push({
            id: nid(),
            pipelineRunId: runId,
            stepId: step.id,
            stageName: stage.stageName,
            stepName: step.stepName,
            runStatus: 'SUCCESS',
            logExcerpt,
            executorNode: 'mock-e2e',
            startTime: new Date().toISOString(),
            endTime: new Date().toISOString(),
            durationMs: 100,
          });
        }
      }
      runs.push({
        id: runId,
        pipelineId,
        runStatus: 'SUCCESS',
        startTime: new Date().toISOString(),
        endTime: new Date().toISOString(),
        appId: pipeline?.appId,
        envId: request.postDataJSON()?.envId,
      });
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ code: 'SUCCESS', data: { pipelineRunId: runId, runStatus: 'SUCCESS' } }),
      });
      return;
    }
    await route.fallback();
  });

  // GET /api/pipelines/{id}/stages
  await page.route('**/api/pipelines/*/stages', async (route) => {
    const request = route.request();
    const url = new URL(request.url());
    const match = url.pathname.match(/^\/api\/pipelines\/(\d+)\/stages$/);
    if (request.method() === 'GET' && match) {
      const pipelineId = Number(match[1]);
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ code: 'SUCCESS', data: stages.filter((s) => s.pipelineId === pipelineId) }),
      });
      return;
    }
    // POST /api/pipelines/{id}/stages
    if (request.method() === 'POST' && match) {
      const pipelineId = Number(match[1]);
      const body = request.postDataJSON();
      stages.push({ id: nid(), pipelineId, ...body });
      await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ code: 'SUCCESS', data: true }) });
      return;
    }
    await route.fallback();
  });

  // GET /api/pipelines/{id} — single pipeline detail
  await page.route('**/api/pipelines/*', async (route) => {
    const request = route.request();
    const url = new URL(request.url());
    const match = url.pathname.match(/^\/api\/pipelines\/(\d+)$/);
    if (request.method() === 'GET' && match) {
      const pipelineId = Number(match[1]);
      const pipeline = pipelines.find((p) => p.id === pipelineId);
      if (pipeline) {
        await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ code: 'SUCCESS', data: pipeline }) });
      } else {
        await route.fulfill({ status: 404, contentType: 'application/json', body: JSON.stringify({ code: 'NOT_FOUND' }) });
      }
      return;
    }
    await route.fallback();
  });

  // GET/POST /api/pipelines — list (with pagination) and create
  await page.route('**/api/pipelines**', async (route) => {
    const request = route.request();
    const url = new URL(request.url());
    if (request.method() === 'GET' && url.pathname === '/api/pipelines') {
      const pageNum = Number(url.searchParams.get('pageNum') || 1);
      const pageSize = Number(url.searchParams.get('pageSize') || 10);
      const start = (pageNum - 1) * pageSize;
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          code: 'SUCCESS',
          data: { pageNum, pageSize, total: pipelines.length, records: pipelines.slice(start, start + pageSize) },
        }),
      });
      return;
    }
    if (request.method() === 'POST' && url.pathname === '/api/pipelines') {
      const body = request.postDataJSON();
      pipelines.push({ id: nid(), ...body });
      await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ code: 'SUCCESS', data: true }) });
      return;
    }
    await route.fallback();
  });

  // GET /api/pipeline-runs (list with pagination + optional releaseId filter)
  await page.route('**/api/pipeline-runs**', async (route) => {
    const request = route.request();
    const url = new URL(request.url());
    if (request.method() === 'GET' && url.pathname === '/api/pipeline-runs') {
      const pageNum = Number(url.searchParams.get('pageNum') || 1);
      const pageSize = Number(url.searchParams.get('pageSize') || 10);
      const releaseId = url.searchParams.get('releaseId');
      let filtered = runs;
      if (releaseId) {
        filtered = runs.filter((r) => r.releaseId === Number(releaseId));
      }
      const start = (pageNum - 1) * pageSize;
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          code: 'SUCCESS',
          data: { pageNum, pageSize, total: filtered.length, records: filtered.slice(start, start + pageSize) },
        }),
      });
      return;
    }
    await route.fallback();
  });

  // GET /api/pipeline-runs/{id}
  await page.route('**/api/pipeline-runs/*', async (route) => {
    const request = route.request();
    const url = new URL(request.url());
    const match = url.pathname.match(/^\/api\/pipeline-runs\/(\d+)$/);
    if (request.method() === 'GET' && match) {
      const runId = Number(match[1]);
      const run = runs.find((r) => r.id === runId);
      if (run) {
        await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ code: 'SUCCESS', data: run }) });
      } else {
        await route.fulfill({ status: 404, contentType: 'application/json', body: JSON.stringify({ code: 'NOT_FOUND' }) });
      }
      return;
    }
    await route.fallback();
  });

  return state;
}

/**
 * Mock rollbackApi.page with custom data.
 * Also mocks releaseApi.page because enrichRollbackOrders needs it for lookup.
 */
export async function mockRollbackList(page: Page, records: Record<string, unknown>[], total?: number): Promise<void> {
  const releaseRecords = records
    .filter((record) => record.releaseOrderId !== undefined && record.releaseOrderId !== null)
    .map((record) => ({
      id: record.releaseOrderId,
      orderNo: record.releaseOrderNo || `REL-${record.releaseOrderId}`,
      appId: record.appId || TEST_DATA.APP_ORDER_PLATFORM.id,
      serviceId: record.serviceId || TEST_DATA.SVC_ORDER_PLATFORM.id,
      envId: record.envId || TEST_DATA.ENV_TEST.id,
      appDisplayName: record.appDisplayName || TEST_DATA.APP_ORDER_PLATFORM.appName,
      serviceDisplayName: record.serviceDisplayName || TEST_DATA.SVC_ORDER_PLATFORM.serviceName,
      envDisplayName: record.envDisplayName || TEST_DATA.ENV_TEST.envName,
    }));

  await page.route('**/api/rollbacks**', async (route) => {
    const request = route.request();
    const url = new URL(request.url());
    if (request.method() === 'GET' && url.pathname === '/api/rollbacks') {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          code: 'SUCCESS',
          data: { pageNum: 1, pageSize: 20, total: total ?? records.length, records },
        }),
      });
      return;
    }
    if (request.method() === 'GET' && url.pathname.includes('/detail')) {
      const rollbackId = url.pathname.match(/\/api\/rollbacks\/(\d+)\/detail/)?.[1] || '9001';
      const record = records.find(r => String(r.id) === rollbackId) || records[0] || {};
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          code: 'SUCCESS',
          data: {
            id: Number(rollbackId),
            rollbackNo: record.rollbackNo || 'RB-TEST',
            appName: '测试应用', serviceName: '测试服务', envName: '测试环境',
            fromVersionNo: 'v2.0.0', toVersionNo: 'v1.0.0',
            status: record.status || 'CREATED', createdAt: '2026-05-20T10:00:00',
            ...record,
          },
        }),
      });
      return;
    }
    await route.fallback();
  });

  await page.route('**/api/releases**', async (route) => {
    const request = route.request();
    const url = new URL(request.url());
    if (request.method() === 'GET' && url.pathname === '/api/releases') {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          code: 'SUCCESS',
          data: {
            pageNum: Number(url.searchParams.get('pageNum') || 1),
            pageSize: Number(url.searchParams.get('pageSize') || 20),
            total: releaseRecords.length,
            records: releaseRecords,
          },
        }),
      });
      return;
    }
    await route.fallback();
  });
}

/**
 * Stateful release and rollback API mocks with in-memory storage.
 * Tracks records created via UI POST so they appear in subsequent GET list calls.
 * Also mocks enrichment sub-resources (canary-plan) and detail endpoints.
 */
export async function mockReleaseAndRollbackApis(
  page: Page,
  seedReleases: Record<string, unknown>[] = [],
  seedRollbacks: Record<string, unknown>[] = [],
  pipelineState?: PipelineMockState,
): Promise<void> {
  const releases: Record<string, unknown>[] = [...seedReleases];
  const rollbacks: Record<string, unknown>[] = [...seedRollbacks];
  let nextId = 1000;
  const nid = () => nextId++;

  // Releases: GET (list), POST (create), and sub-resource actions
  await page.route('**/api/releases**', async (route) => {
    const request = route.request();
    const url = new URL(request.url());
    const method = request.method();

    if (method === 'POST' && url.pathname === '/api/releases') {
      const body = request.postDataJSON();
      const record = { id: nid(), status: 'CREATED', ...body };
      releases.push(record);
      await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ code: 'SUCCESS', data: record }) });
      return;
    }

    // GET list (paginated, with keyword/status filtering)
    if (method === 'GET' && url.pathname === '/api/releases') {
      let filtered = [...releases];
      const keyword = url.searchParams.get('keyword');
      if (keyword) {
        const kw = keyword.toLowerCase();
        filtered = filtered.filter((r) => {
          const orderNo = String(r.orderNo || '').toLowerCase();
          return orderNo.includes(kw);
        });
      }
      const statusParam = url.searchParams.get('status');
      if (statusParam) {
        const allowedStatuses = statusParam.split(',').map((s) => s.trim());
        filtered = filtered.filter((r) => allowedStatuses.includes(String(r.status)));
      }
      await route.fulfill({
        status: 200, contentType: 'application/json',
        body: JSON.stringify({ code: 'SUCCESS', data: { pageNum: 1, pageSize: 200, total: filtered.length, records: filtered } }),
      });
      return;
    }

    // Sub-resource: detail
    const detailMatch = url.pathname.match(/^\/api\/releases\/(\d+)\/detail$/);
    if (method === 'GET' && detailMatch) {
      const releaseId = Number(detailMatch[1]);
      const record = releases.find((r) => r.id === releaseId) || releases[0] || {};
      const status = record.status || 'CREATED';
      await route.fulfill({
        status: 200, contentType: 'application/json',
        body: JSON.stringify({ code: 'SUCCESS', data: {
          id: releaseId,
          ...record,
          timeline: [
            { key: 'created', title: '单据创建', status: 'SUCCESS', time: '2026-05-20T09:00:00', summary: '创建发布单', actionType: 'NONE', actionData: null },
            { key: 'execute', title: '执行发布', status: status === 'CREATED' ? 'PENDING' : status, time: '2026-05-20T10:00:00', summary: status === 'CREATED' ? '等待执行' : '发布已执行', actionType: status !== 'CREATED' ? 'VIEW_DEPLOY_RECORDS' : 'NONE', actionData: status !== 'CREATED' ? { releaseOrderId: releaseId } : null },
            { key: 'result', title: '最终结果', status: status, time: '2026-05-20T10:05:00', summary: `发布${status === 'SUCCESS' ? '成功' : status === 'FAILED' ? '失败' : status === 'RUNNING' ? '执行中' : '待执行'}`, actionType: 'NONE', actionData: null },
          ],
          executionHistory: status !== 'CREATED' ? [
            { deployRecordId: 1, attemptNumber: 1, operationType: 'RELEASE', status: status, startedAt: '2026-05-20T10:00:00', failureHint: null, summary: '发布执行完成' },
          ] : [],
        } }),
      });
      return;
    }

    // Sub-resource: deploy-records
    const deployMatch = url.pathname.match(/^\/api\/releases\/(\d+)\/deploy-records$/);
    if (method === 'GET' && deployMatch) {
      const releaseId = Number(deployMatch[1]);
      await route.fulfill({
        status: 200, contentType: 'application/json',
        body: JSON.stringify({
          code: 'SUCCESS',
          data: [{
            id: 1, releaseOrderId: releaseId, operationType: 'RELEASE',
            deployStatus: 'SUCCESS', status: 'SUCCESS',
            deployLog: '前置检查通过\nDeploy completed\nHealth check passed',
            logExcerpt: 'Deploy completed', executorNode: 'mock-e2e',
            startedAt: '2026-05-20T10:00:00', endedAt: '2026-05-20T10:05:00',
            startTime: '2026-05-20T10:00:00', endTime: '2026-05-20T10:05:00', durationMs: 300000,
            deployBatch: 'batch-1', hostId: 'mock-host', versionId: 1004,
          }],
        }),
      });
      return;
    }

    // Sub-resource: precheck
    const precheckMatch = url.pathname.match(/^\/api\/releases\/(\d+)\/precheck$/);
    if (method === 'POST' && precheckMatch) {
      await route.fulfill({
        status: 200, contentType: 'application/json',
        body: JSON.stringify({
          code: 'SUCCESS',
          data: [{ success: true, hostId: 1, instanceId: 1, artifactUri: 'mock://artifact/app.jar', stdout: '版本校验通过', stderr: '', errorMessage: '' }],
        }),
      });
      return;
    }

    // Sub-resource: execute
    const execMatch = url.pathname.match(/^\/api\/releases\/(\d+)\/execute$/);
    if (method === 'POST' && execMatch) {
      const releaseId = Number(execMatch[1]);
      const record = releases.find((r) => r.id === releaseId);
      if (record) record.status = 'RUNNING';
      await route.fulfill({
        status: 200, contentType: 'application/json',
        body: JSON.stringify({ code: 'SUCCESS', data: { status: 'RUNNING' } }),
      });
      return;
    }

    // Sub-resource: execute-pipeline
    const execPipeMatch = url.pathname.match(/^\/api\/releases\/(\d+)\/execute-pipeline$/);
    if (method === 'POST' && execPipeMatch) {
      const releaseId = Number(execPipeMatch[1]);
      const record = releases.find((r) => r.id === releaseId);
      if (record && record.status !== 'CREATED') {
        await route.fulfill({
          status: 200, contentType: 'application/json',
          body: JSON.stringify({ code: 'FAILED', message: '当前发布单状态不允许执行' }),
        });
        return;
      }
      const body = request.postDataJSON();
      if (pipelineState) {
        const newRunId = 1000 + pipelineState.runs.length;
        // Look up the pipeline's actual stages and steps
        const pipelineStages = pipelineState.stages.filter((s) => s.pipelineId === body?.pipelineId);
        const newStepRuns: Record<string, unknown>[] = [];
        let runFailed = false;
        for (const stage of pipelineStages) {
          const stageSteps = pipelineState.steps.filter((s) => s.stageId === stage.id);
          for (const step of stageSteps) {
            const script = String(step.scriptContent || '');
            const echoMatch = script.match(/echo\s+(.+)/);
            const isFailedStep = script.includes('/nonexistent/') || String(step.stepName || '').includes('失败');
            if (isFailedStep) runFailed = true;
            const logExcerpt = isFailedStep ? 'ls: No such file or directory' : (echoMatch ? echoMatch[1].trim() : 'Mock output');
            newStepRuns.push({
              id: 10000 + pipelineState.stepRuns.length + newStepRuns.length,
              pipelineRunId: newRunId,
              stepId: step.id,
              stageName: stage.stageName,
              stepName: step.stepName,
              runStatus: isFailedStep ? 'FAILED' : 'SUCCESS',
              logExcerpt,
              executorNode: 'mock-e2e',
              startTime: new Date().toISOString(),
              endTime: new Date().toISOString(),
              durationMs: 100,
              errorMessage: isFailedStep ? 'exit code 1' : undefined,
            });
          }
        }
        pipelineState.addPipelineRun(
          { id: newRunId, pipelineId: body?.pipelineId, runStatus: runFailed ? 'FAILED' : 'SUCCESS', releaseId, startTime: new Date().toISOString(), endTime: new Date().toISOString(), appId: record?.appId, envId: record?.envId },
          newStepRuns,
        );
        if (record) record.status = runFailed ? 'FAILED' : 'SUCCESS';
        await route.fulfill({
          status: 200, contentType: 'application/json',
          body: JSON.stringify({ code: 'SUCCESS', data: { pipelineRunId: newRunId, runStatus: runFailed ? 'FAILED' : 'SUCCESS' } }),
        });
        return;
      }
      await route.fulfill({
        status: 200, contentType: 'application/json',
        body: JSON.stringify({ code: 'SUCCESS', data: { pipelineRunId: nid(), runStatus: 'RUNNING' } }),
      });
      return;
    }

    // Sub-resource: cancel
    const cancelMatch = url.pathname.match(/^\/api\/releases\/(\d+)\/cancel$/);
    if (method === 'POST' && cancelMatch) {
      const releaseId = Number(cancelMatch[1]);
      const record = releases.find((r) => r.id === releaseId);
      if (record) record.status = 'CANCELLED';
      await route.fulfill({
        status: 200, contentType: 'application/json',
        body: JSON.stringify({ code: 'SUCCESS', data: { status: 'CANCELLED' } }),
      });
      return;
    }

    if (method === 'GET' && url.pathname === '/api/releases') {
      const pageNum = Number(url.searchParams.get('pageNum') || 1);
      const pageSize = Number(url.searchParams.get('pageSize') || 20);
      const keyword = url.searchParams.get('keyword');
      const statusParam = url.searchParams.get('status');
      let filtered = releases;
      if (keyword) {
        filtered = filtered.filter((r) => String(r.orderNo || '').includes(keyword));
      }
      if (statusParam) {
        const allowedStatuses = statusParam.split(',').map((s) => s.trim());
        filtered = filtered.filter((r) => allowedStatuses.includes(String(r.status)));
      }
      const start = (pageNum - 1) * pageSize;
      await route.fulfill({
        status: 200, contentType: 'application/json',
        body: JSON.stringify({
          code: 'SUCCESS',
          data: { pageNum, pageSize, total: filtered.length, records: filtered.slice(start, start + pageSize) },
        }),
      });
      return;
    }

    if (url.pathname.includes('/canary-plan')) {
      await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ code: 'SUCCESS', data: null }) });
      return;
    }

    await route.fallback();
  });

  // Rollbacks: GET (list, detail, deploy-records), POST (create, execute, precheck)
  await page.route('**/api/rollbacks**', async (route) => {
    const request = route.request();
    const url = new URL(request.url());
    const method = request.method();

    if (method === 'POST' && url.pathname === '/api/rollbacks') {
      const body = request.postDataJSON();
      const record = { id: nid(), status: 'CREATED', ...body };
      rollbacks.push(record);
      await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ code: 'SUCCESS', data: record }) });
      return;
    }

    // Sub-resource: detail
    const rbDetailMatch = url.pathname.match(/^\/api\/rollbacks\/(\d+)\/detail$/);
    if (method === 'GET' && rbDetailMatch) {
      const rollbackId = Number(rbDetailMatch[1]);
      const record = rollbacks.find((r) => r.id === rollbackId) || rollbacks[0] || {};
      const status = record.status || 'CREATED';
      await route.fulfill({
        status: 200, contentType: 'application/json',
        body: JSON.stringify({ code: 'SUCCESS', data: {
          id: rollbackId,
          ...record,
          timeline: [
            { key: 'created', title: '单据创建', status: 'SUCCESS', time: '2026-05-20T09:00:00', summary: '创建回滚单', actionType: 'NONE', actionData: null },
            { key: 'execute', title: '执行回滚', status: status === 'CREATED' ? 'PENDING' : status, time: '2026-05-20T11:00:00', summary: status === 'CREATED' ? '等待执行' : '回滚已执行', actionType: status !== 'CREATED' ? 'VIEW_DEPLOY_RECORDS' : 'NONE', actionData: status !== 'CREATED' ? { rollbackId } : null },
            { key: 'result', title: '最终结果', status: status, time: '2026-05-20T11:05:00', summary: `回滚${status === 'SUCCESS' ? '成功' : status === 'FAILED' ? '失败' : status === 'RUNNING' ? '执行中' : '待执行'}`, actionType: 'NONE', actionData: null },
          ],
          executionHistory: status !== 'CREATED' ? [
            { deployRecordId: 2, attemptNumber: 1, operationType: 'ROLLBACK', status: status, startedAt: '2026-05-20T11:00:00', failureHint: null, summary: '回滚执行完成' },
          ] : [],
        } }),
      });
      return;
    }

    // Sub-resource: deploy-records
    const rbDeployMatch = url.pathname.match(/^\/api\/rollbacks\/(\d+)\/deploy-records$/);
    if (method === 'GET' && rbDeployMatch) {
      const rollbackId = Number(rbDeployMatch[1]);
      await route.fulfill({
        status: 200, contentType: 'application/json',
        body: JSON.stringify({
          code: 'SUCCESS',
          data: [{
            id: 2, rollbackId, operationType: 'ROLLBACK', deployStatus: 'SUCCESS', status: 'SUCCESS',
            deployLog: 'Rollback completed\nHealth check passed',
            logExcerpt: 'Health check passed', executorNode: 'mock-e2e',
            startTime: '2026-05-20T11:00:00', endTime: '2026-05-20T11:05:00', durationMs: 300000,
          }],
        }),
      });
      return;
    }

    // Sub-resource: execute
    const rbExecMatch = url.pathname.match(/^\/api\/rollbacks\/(\d+)\/execute$/);
    if (method === 'POST' && rbExecMatch) {
      const rollbackId = Number(rbExecMatch[1]);
      const record = rollbacks.find((r) => r.id === rollbackId);
      if (record) record.status = 'RUNNING';
      await route.fulfill({
        status: 200, contentType: 'application/json',
        body: JSON.stringify({ code: 'SUCCESS', data: { status: 'RUNNING' } }),
      });
      return;
    }

    // Sub-resource: precheck
    const rbPrecheckMatch = url.pathname.match(/^\/api\/rollbacks\/(\d+)\/precheck$/);
    if (method === 'POST' && rbPrecheckMatch) {
      await route.fulfill({
        status: 200, contentType: 'application/json',
        body: JSON.stringify({
          code: 'SUCCESS',
          data: [{ success: true, hostId: 1, instanceId: 1, artifactUri: 'mock://artifact/app.jar', stdout: '回滚校验通过', stderr: '', errorMessage: '' }],
        }),
      });
      return;
    }

    if (method === 'GET' && url.pathname === '/api/rollbacks') {
      const pageNum = Number(url.searchParams.get('pageNum') || 1);
      const pageSize = Number(url.searchParams.get('pageSize') || 20);
      const keyword = url.searchParams.get('keyword');
      const rbStatusParam = url.searchParams.get('status');
      let filtered = rollbacks;
      if (keyword) {
        filtered = filtered.filter((r) =>
          String(r.rollbackNo || '').includes(keyword) ||
          String(r.releaseOrderNo || '').includes(keyword),
        );
      }
      if (rbStatusParam) {
        const allowedStatuses = rbStatusParam.split(',').map((s) => s.trim());
        filtered = filtered.filter((r) => allowedStatuses.includes(String(r.status)));
      }
      const start = (pageNum - 1) * pageSize;
      await route.fulfill({
        status: 200, contentType: 'application/json',
        body: JSON.stringify({
          code: 'SUCCESS',
          data: { pageNum, pageSize, total: filtered.length, records: filtered.slice(start, start + pageSize) },
        }),
      });
      return;
    }

    await route.fallback();
  });

  // Current versions and their deploy records
  await page.route('**/api/current-versions**', async (route) => {
    const request = route.request();
    const url = new URL(request.url());
    const method = request.method();

    if (method === 'GET' && url.pathname === '/api/current-versions') {
      await route.fulfill({
        status: 200, contentType: 'application/json',
        body: JSON.stringify({
          code: 'SUCCESS',
          data: {
            pageNum: 1, pageSize: 20, total: 2,
            records: [
              { id: 1, appId: 2, serviceId: 102, serviceName: 'InfraHub 后端服务', envId: 20, envName: '测试环境', currentVersionId: 1004, appCode: 'infrahub', serviceCode: 'infrahub-backend', instanceCode: 'infrahub-web', releaseOrderId: 100 },
              { id: 2, appId: 1, serviceId: 101, serviceName: '订单协同平台后端服务', envId: 20, envName: '测试环境', currentVersionId: 1001, appCode: 'order-platform', serviceCode: 'order-platform-backend', instanceCode: 'srm-backend', releaseOrderId: 100 },
            ],
          },
        }),
      });
      return;
    }

    const latestDeployMatch = url.pathname.match(/^\/api\/current-versions\/(\d+)\/latest-deploy-records$/);
    if (method === 'GET' && latestDeployMatch) {
      await route.fulfill({
        status: 200, contentType: 'application/json',
        body: JSON.stringify({
          code: 'SUCCESS',
          data: [{
            id: 1, operationType: 'RELEASE', deployStatus: 'SUCCESS', status: 'SUCCESS',
            deployLog: '前置检查通过\nDeploy completed\nHealth check passed',
            logExcerpt: 'Health check passed', executorNode: 'mock-e2e',
            startTime: '2026-05-20T10:00:00', endTime: '2026-05-20T10:05:00', durationMs: 300000,
            hostId: 'mock-host', versionId: 1004,
          }],
        }),
      });
      return;
    }

    await route.fallback();
  });

  // Release diagnostics: sequence health and test data preview
  await page.route('**/api/release-diagnostics/sequences', async (route) => {
    await route.fulfill({
      status: 200, contentType: 'application/json',
      body: JSON.stringify({
        code: 'SUCCESS',
        data: [
          { tableName: 'release_artifact', sequenceName: 'release_artifact_id_seq', maxId: 42, sequenceValue: 50, healthy: true, suggestedFixSql: '' },
          { tableName: 'release_version', sequenceName: 'release_version_id_seq', maxId: 38, sequenceValue: 35, healthy: false, suggestedFixSql: "SELECT setval('release_version_id_seq', (SELECT MAX(id) FROM release_version));" },
          { tableName: 'release_order', sequenceName: 'release_order_id_seq', maxId: 120, sequenceValue: 130, healthy: true, suggestedFixSql: '' },
        ],
      }),
    });
  });

  await page.route('**/api/release-diagnostics/test-data-preview', async (route) => {
    await route.fulfill({
      status: 200, contentType: 'application/json',
      body: JSON.stringify({
        code: 'SUCCESS',
        data: [
          {
            groupName: 'V1.4/V1.5 真实流水线部署证据链',
            description: '包含真实流水线运行记录和部署证据',
            protectedEvidence: true,
            recommendedAction: '建议保留 - 生产环境部署证据',
            records: [
              { id: 1, name: 'pipeline-run-001', type: '流水线运行', createdAt: '2026-04-10T10:00:00', description: 'V1.4 部署流水线执行' },
            ],
          },
          {
            groupName: 'V1.6 真实上传/发布证据链',
            description: '包含真实制品上传和发布单证据',
            protectedEvidence: true,
            recommendedAction: '建议保留 - 发布审批证据',
            records: [
              { id: 2, name: 'artifact-upload-001', type: '制品上传', createdAt: '2026-04-15T14:00:00', description: 'V1.6 制品上传记录' },
            ],
          },
          {
            groupName: 'E2E临时数据',
            description: 'E2E 测试产生的临时数据',
            protectedEvidence: false,
            recommendedAction: '可安全清理',
            records: [
              { id: 100, name: 'e2e-test-data', type: '测试数据', createdAt: '2026-05-20T10:00:00', description: 'E2E 测试临时记录' },
            ],
          },
        ],
      }),
    });
  });
}
