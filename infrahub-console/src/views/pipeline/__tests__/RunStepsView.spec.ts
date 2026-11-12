import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils';
import ElementPlus, { ElMessage } from 'element-plus';
import { createPinia, setActivePinia } from 'pinia';
import { createMemoryHistory, createRouter, type Router } from 'vue-router';
import { i18n, setLocale } from '@/i18n';
import zhCN from '@/i18n/locales/zh-CN';
import enUS from '@/i18n/locales/en-US';
import RunStepsView from '../RunStepsView.vue';
import DeployRecordsDialog from '@/components/release/DeployRecordsDialog.vue';
import { useAuthStore } from '@/store/auth';
import { getPipelineRun, getPipelineStepLog, pipelineStepRunApi } from '@/api/pipeline';
import { getReleaseDeployRecords } from '@/api/release';

/**
 * 步骤记录页：运行上下文与部署记录各成一条读取通道，各自持有请求序号与目标快照；
 * 运行 ID 在同一个组件内切换时旧上下文立即作废、旧证据弹窗立即关闭；
 * 读取失败不得伪装成"未关联发布单"，也不得伪装成"没有部署记录"。
 * 全部为组件级自测：网络被 mock，不连接真实后端，不产生任何写请求。
 */
vi.mock('@/api/pipeline', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/pipeline')>();
  return {
    ...actual,
    pipelineStepRunApi: { ...actual.pipelineStepRunApi, page: vi.fn() },
    getPipelineRun: vi.fn(),
    getPipelineStepLog: vi.fn(),
  };
});

vi.mock('@/api/release', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/release')>();
  return {
    ...actual,
    getReleaseDeployRecords: vi.fn(),
  };
});

const mockedGetRun = vi.mocked(getPipelineRun);
const mockedStepPage = vi.mocked(pipelineStepRunApi.page);
const mockedStepLog = vi.mocked(getPipelineStepLog);
const mockedDeployRecords = vi.mocked(getReleaseDeployRecords);

interface Deferred<T> {
  promise: Promise<T>;
  resolve: (value: T) => void;
  reject: (reason?: unknown) => void;
}

/** 手工控制完成时机的读取：用于构造乱序返回、目标切换后才返回的迟到响应 */
function deferred<T>(): Deferred<T> {
  let resolve!: (value: T) => void;
  let reject!: (reason?: unknown) => void;
  const promise = new Promise<T>((res, rej) => {
    resolve = res;
    reject = rej;
  });
  return { promise, resolve, reject };
}

let wrapper: VueWrapper | undefined;
let router: Router;
const writeText = vi.fn(async () => undefined);

function stepRow(overrides: Record<string, unknown> = {}) {
  return {
    id: 1,
    stepId: 10,
    stageName: 'build',
    stepName: '编译',
    runStatus: 'SUCCESS',
    durationMs: 1500,
    logExcerpt: 'line-1\nline-2',
    errorMessage: '',
    ...overrides,
  };
}

function context(overrides: Record<string, unknown> = {}) {
  return {
    id: 7,
    runStatus: 'SUCCESS',
    appId: 201,
    versionId: 301,
    releaseId: 89,
    ...overrides,
  };
}

async function mountView(path = '/pipeline/runs/7/steps', authorities = ['RELEASE_VIEW']) {
  setLocale('zh-CN');
  const pinia = createPinia();
  setActivePinia(pinia);
  router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', component: { template: '<div />' } },
      { path: '/pipeline/runs/:id/steps', component: { template: '<div />' } },
      { path: '/release/orders', component: { template: '<div />' } },
    ],
  });
  await router.push(path);
  await router.isReady();

  const authStore = useAuthStore();
  authStore.currentUser = { id: 1, username: 'tester', authorities } as never;

  wrapper = mount(RunStepsView, {
    global: { plugins: [ElementPlus, i18n, pinia, router] },
    attachTo: document.body,
  });
  await flushPromises();
}

function bodyText(): string {
  return (document.body.textContent || '').replace(/\s+/g, ' ').trim();
}

function toolbarButton(label: string): HTMLButtonElement | undefined {
  return Array.from(document.body.querySelectorAll<HTMLButtonElement>('.page-toolbar button')).find(
    button => (button.textContent || '').trim() === label,
  );
}

async function clickToolbarButton(label: string) {
  const button = toolbarButton(label);
  if (!button) throw new Error(`工具栏没有「${label}」按钮`);
  button.click();
  await flushPromises();
}

function bodyRows(): HTMLElement[] {
  return Array.from(document.body.querySelectorAll<HTMLElement>('.el-table__body-wrapper .el-table__row'));
}

function buttonWithLabel(container: ParentNode, label: string): HTMLButtonElement | undefined {
  return Array.from(container.querySelectorAll<HTMLButtonElement>('button')).find(
    button => (button.textContent || '').trim() === label,
  );
}

async function clickRowButton(rowIndex: number, label: string) {
  const row = bodyRows()[rowIndex];
  if (!row) throw new Error(`没有第 ${rowIndex + 1} 行步骤`);
  const button = buttonWithLabel(row, label);
  if (!button) throw new Error(`第 ${rowIndex + 1} 行没有「${label}」按钮`);
  button.click();
  await flushPromises();
}

function dialogByTitle(title: string): HTMLElement {
  const dialogs = Array.from(document.body.querySelectorAll<HTMLElement>('.el-dialog'));
  const found = dialogs.find(dialog => (dialog.querySelector('.el-dialog__title')?.textContent || '').trim() === title);
  if (!found) throw new Error(`没有标题为「${title}」的弹窗`);
  return found;
}

function deployDialog() {
  const dialog = wrapper?.findComponent(DeployRecordsDialog);
  if (!dialog || !dialog.exists()) throw new Error('部署记录弹窗未挂载');
  return dialog;
}

function deployRecord(overrides: Record<string, unknown> = {}) {
  return {
    id: 900,
    releaseOrderId: 89,
    operationType: 'RELEASE',
    deployStatus: 'SUCCESS',
    deployLog: '',
    ...overrides,
  };
}

beforeEach(() => {
  vi.spyOn(console, 'error').mockImplementation(() => undefined);
  vi.spyOn(console, 'warn').mockImplementation(() => undefined);
  mockedGetRun.mockReset();
  mockedStepPage.mockReset();
  mockedDeployRecords.mockReset();
  writeText.mockClear();
  Object.defineProperty(navigator, 'clipboard', { value: { writeText }, configurable: true });
  mockedStepPage.mockResolvedValue({ pageNum: 1, pageSize: 10, total: 1, records: [stepRow()] } as never);
  mockedGetRun.mockResolvedValue(context() as never);
});

afterEach(() => {
  wrapper?.unmount();
  wrapper = undefined;
  document.body.innerHTML = '';
  setLocale('zh-CN');
  vi.restoreAllMocks();
});

describe('RunStepsView 运行 ID 校验与运行信息', () => {
  it('后台刷新期间保留已显示的三个标签和表格', async () => {
    const pending = deferred<Record<string, unknown>>();
    mockedGetRun.mockResolvedValueOnce(context({ runStatus: 'RUNNING', releaseId: null }))
      .mockReturnValueOnce(pending.promise);
    await mountView();
    const tags = () => Array.from(document.querySelectorAll('.toolbar-actions .el-tag'));
    const initial = tags();
    expect(initial).toHaveLength(3);

    await vi.waitFor(() => expect(mockedGetRun).toHaveBeenCalledTimes(2), { timeout: 3000 });
    expect(tags()).toEqual(initial);
    expect(bodyText()).toContain(zhCN.runSteps.releaseOrderNone);
    expect(bodyText()).not.toContain(zhCN.runSteps.runContextLoading);
    expect(document.querySelector('.el-loading-mask')).toBeNull();
    pending.resolve(context({ releaseId: null }));
    await flushPromises();
    expect(bodyText()).toContain(zhCN.pipelineRuns.statusSuccess);
  });

  it('合法运行 ID 读取运行信息，步骤列表按该运行 ID 过滤', async () => {
    await mountView('/pipeline/runs/7/steps');

    expect(mockedGetRun.mock.calls.map(call => call[0])).toEqual([7]);
    expect(mockedStepPage).toHaveBeenCalled();
    for (const call of mockedStepPage.mock.calls) {
      expect(call[0]).toMatchObject({ pipelineRunId: 7 });
    }
    expect(bodyText()).toContain(`${zhCN.runSteps.runIdLabel}：7`);
    expect(bodyText()).toContain(zhCN.runSteps.releaseOrderLinked.replace('{id}', '89'));
  });

  it('无效运行 ID 既不读运行信息，也不发起步骤请求', async () => {
    await mountView('/pipeline/runs/not-a-number/steps');

    expect(mockedGetRun).not.toHaveBeenCalled();
    expect(mockedStepPage).not.toHaveBeenCalled();
    expect(bodyText()).toContain(zhCN.runSteps.invalidRunId);
    expect(bodyText()).not.toContain(zhCN.runSteps.runStatusLabel);
  });

  it('小数运行 ID 与列表入口判据一致：判为无效，零请求', async () => {
    await mountView('/pipeline/runs/1.5/steps');

    expect(mockedGetRun).not.toHaveBeenCalled();
    expect(mockedStepPage).not.toHaveBeenCalled();
    expect(bodyText()).toContain(zhCN.runSteps.invalidRunId);
  });

  it('运行信息读取失败不伪装成"未关联发布单"，并保留后端原始错误', async () => {
    mockedGetRun.mockRejectedValueOnce(new Error('运行记录不存在：id=7'));
    await mountView('/pipeline/runs/7/steps');

    expect(document.body.querySelector('.run-context-alert')).not.toBeNull();
    expect(bodyText()).toContain(zhCN.runSteps.runContextFailed);
    expect(bodyText()).toContain(zhCN.runSteps.runContextFailedHint);
    expect(bodyText()).toContain(zhCN.runSteps.runNotFound);
    expect(document.body.querySelector('.run-context-alert .raw-error-text')?.textContent).toContain('运行记录不存在：id=7');
    // 读取失败时不做任何"无关联发布单"的推断
    expect(bodyText()).not.toContain(zhCN.runSteps.releaseOrderNone);
    expect(toolbarButton(zhCN.runSteps.deployRecordsAction)).toBeUndefined();
    expect(toolbarButton(zhCN.runSteps.viewReleaseOrder)).toBeUndefined();
  });

  it('运行信息读取成功但确实没有关联发布单时才说明未关联', async () => {
    mockedGetRun.mockResolvedValueOnce(context({ releaseId: null }) as never);
    await mountView('/pipeline/runs/7/steps');

    expect(document.body.querySelector('.run-context-alert')).toBeNull();
    expect(bodyText()).toContain(zhCN.runSteps.releaseOrderNone);
    expect(bodyText()).not.toContain('89');
    // 没有关联发布单就没有部署记录目标，入口本身不出现
    expect(toolbarButton(zhCN.runSteps.deployRecordsAction)).toBeUndefined();
  });

  it('运行信息返回的作用域与当前运行不符时拒绝展示，也不推断未关联', async () => {
    mockedGetRun.mockResolvedValueOnce(context({ id: 8, releaseId: 90 }) as never);
    await mountView('/pipeline/runs/7/steps');

    expect(document.body.querySelector('.run-context-alert')).not.toBeNull();
    expect(bodyText()).toContain(zhCN.runSteps.runContextFailed);
    expect(bodyText()).not.toContain(zhCN.runSteps.releaseOrderNone);
    expect(bodyText()).not.toContain('90');
  });
});

describe('RunStepsView 部署记录权限门禁', () => {
  it('缺少 RELEASE_VIEW / SYSTEM_ADMIN 时入口不出现，也不发起部署记录请求', async () => {
    await mountView('/pipeline/runs/7/steps', ['USER']);

    expect(toolbarButton(zhCN.runSteps.deployRecordsAction)).toBeUndefined();
    expect(toolbarButton(zhCN.runSteps.viewReleaseOrder)).toBeUndefined();
    expect(mockedDeployRecords).not.toHaveBeenCalled();
    // 权限判据不因存在 releaseId 而被授予
    expect(bodyText()).toContain(zhCN.runSteps.releaseOrderLinked.replace('{id}', '89'));
  });

  it('有权限时入口出现，点击后按当前运行的 releaseId 读取部署记录', async () => {
    mockedDeployRecords.mockResolvedValueOnce([deployRecord({ id: 501 })] as never);
    await mountView('/pipeline/runs/7/steps', ['SYSTEM_ADMIN']);

    await clickToolbarButton(zhCN.runSteps.deployRecordsAction);

    expect(mockedDeployRecords.mock.calls.map(call => call[0])).toEqual([89]);
    expect(deployDialog().props('modelValue')).toBe(true);
    expect(deployDialog().props('records')).toEqual([deployRecord({ id: 501 })]);
    expect(deployDialog().props('error')).toBeNull();
    expect(deployDialog().props('loading')).toBe(false);
  });

  it('查看发布单只携带发布单列表已支持的筛选参数', async () => {
    await mountView('/pipeline/runs/7/steps', ['RELEASE_VIEW']);
    const push = vi.spyOn(router, 'push');

    await clickToolbarButton(zhCN.runSteps.viewReleaseOrder);

    expect(push).toHaveBeenCalledWith({ path: '/release/orders', query: { appId: '201', versionId: '301' } });
  });
});

describe('RunStepsView 部署记录读取归属', () => {
  it('读取失败保留已读到证据并显式标注失败，只读重试仍绑定同一运行与发布单', async () => {
    const evidence = [deployRecord({ id: 501 })];
    const refreshed = [...evidence, deployRecord({ id: 502 })];
    mockedDeployRecords
      .mockResolvedValueOnce(evidence as never)
      .mockRejectedValueOnce(new Error('部署记录读取超时'))
      .mockResolvedValueOnce(refreshed as never);

    await mountView('/pipeline/runs/7/steps', ['RELEASE_VIEW']);

    await clickToolbarButton(zhCN.runSteps.deployRecordsAction);
    expect(deployDialog().props('records')).toEqual(evidence);

    await clickToolbarButton(zhCN.runSteps.deployRecordsAction);
    expect(deployDialog().props('records')).toEqual(evidence);
    expect(deployDialog().props('error')).toBe('部署记录读取超时');
    expect(deployDialog().props('loading')).toBe(false);

    const retry = document.body.querySelector<HTMLButtonElement>('.read-error-block button');
    expect(retry).not.toBeNull();
    retry!.click();
    await flushPromises();

    expect(mockedDeployRecords.mock.calls.map(call => call[0])).toEqual([89, 89, 89]);
    expect(deployDialog().props('records')).toEqual(refreshed);
    expect(deployDialog().props('error')).toBeNull();
  });

  it('在途读取时切换运行 ID：迟到成功不得写回，弹窗关闭、证据清空', async () => {
    const readA = deferred<Record<string, unknown>[]>();
    mockedDeployRecords.mockImplementationOnce(() => readA.promise);
    mockedGetRun.mockImplementation((id) => Promise.resolve(context({
      id: Number(id),
      releaseId: Number(id) === 7 ? 89 : 90,
    }) as never));

    await mountView('/pipeline/runs/7/steps', ['RELEASE_VIEW']);
    await clickToolbarButton(zhCN.runSteps.deployRecordsAction);
    expect(deployDialog().props('loading')).toBe(true);

    await router.push('/pipeline/runs/8/steps');
    await flushPromises();

    expect(deployDialog().props('modelValue')).toBe(false);
    expect(deployDialog().props('records')).toEqual([]);
    expect(deployDialog().props('loading')).toBe(false);

    readA.resolve([deployRecord({ id: 501 })]);
    await flushPromises();

    expect(deployDialog().props('records')).toEqual([]);
    expect(deployDialog().props('error')).toBeNull();
    expect(deployDialog().props('loading')).toBe(false);
  });

  it('在途读取时切换运行 ID：迟到失败不得冒充新目标的错误', async () => {
    const readA = deferred<Record<string, unknown>[]>();
    mockedDeployRecords.mockImplementationOnce(() => readA.promise);
    mockedGetRun.mockImplementation((id) => Promise.resolve(context({ id: Number(id) }) as never));

    await mountView('/pipeline/runs/7/steps', ['RELEASE_VIEW']);
    await clickToolbarButton(zhCN.runSteps.deployRecordsAction);

    await router.push('/pipeline/runs/8/steps');
    await flushPromises();

    readA.reject(new Error('A 的部署记录读取失败'));
    await flushPromises();

    expect(deployDialog().props('error')).toBeNull();
    expect(deployDialog().props('records')).toEqual([]);
  });
});

describe('RunStepsView 运行路由复用', () => {
  it('同一组件内切换运行 ID：新目标立即重读运行信息与步骤列表，旧关联不再显示', async () => {
    await mountView('/pipeline/runs/7/steps', ['RELEASE_VIEW']);
    expect(bodyText()).toContain(zhCN.runSteps.releaseOrderLinked.replace('{id}', '89'));

    mockedGetRun.mockResolvedValue(context({ id: 8, releaseId: 90, runStatus: 'FAILED' }) as never);
    const callsBefore = mockedStepPage.mock.calls.length;

    await router.push('/pipeline/runs/8/steps');
    await flushPromises();

    expect(mockedGetRun.mock.calls.map(call => call[0])).toEqual([7, 8]);
    expect(bodyText()).toContain(zhCN.runSteps.releaseOrderLinked.replace('{id}', '90'));
    expect(bodyText()).not.toContain(zhCN.runSteps.releaseOrderLinked.replace('{id}', '89'));
    const newCalls = mockedStepPage.mock.calls.slice(callsBefore);
    expect(newCalls.length).toBeGreaterThan(0);
    for (const call of newCalls) {
      expect(call[0]).toMatchObject({ pipelineRunId: 8 });
    }
  });

  it('旧目标的迟到成功不得覆盖新目标的运行信息', async () => {
    const contextA = deferred<Record<string, unknown>>();
    mockedGetRun.mockImplementation((id) => (Number(id) === 7
      ? contextA.promise
      : Promise.resolve(context({ id: 8, releaseId: 90, runStatus: 'RUNNING' }) as never)));

    await mountView('/pipeline/runs/7/steps', ['RELEASE_VIEW']);

    await router.push('/pipeline/runs/8/steps');
    await flushPromises();
    expect(bodyText()).toContain(zhCN.runSteps.releaseOrderLinked.replace('{id}', '90'));

    contextA.resolve(context({ id: 7, releaseId: 89, runStatus: 'SUCCESS' }));
    await flushPromises();

    expect(bodyText()).toContain(zhCN.runSteps.releaseOrderLinked.replace('{id}', '90'));
    expect(bodyText()).not.toContain(zhCN.runSteps.releaseOrderLinked.replace('{id}', '89'));
    expect(document.body.querySelector('.run-context-alert')).toBeNull();
  });

  it('旧目标的迟到失败不得覆盖新目标的运行信息，也不得显示失败横幅', async () => {
    const contextA = deferred<Record<string, unknown>>();
    mockedGetRun.mockImplementation((id) => (Number(id) === 7
      ? contextA.promise
      : Promise.resolve(context({ id: 8, releaseId: 90 }) as never)));

    await mountView('/pipeline/runs/7/steps', ['RELEASE_VIEW']);
    await router.push('/pipeline/runs/8/steps');
    await flushPromises();

    contextA.reject(new Error('运行记录不存在：id=7'));
    await flushPromises();

    expect(document.body.querySelector('.run-context-alert')).toBeNull();
    expect(bodyText()).toContain(zhCN.runSteps.releaseOrderLinked.replace('{id}', '90'));
  });

  it('刷新运行信息时同时重读运行信息与步骤列表', async () => {
    await mountView('/pipeline/runs/7/steps', ['RELEASE_VIEW']);
    const runCalls = mockedGetRun.mock.calls.length;
    const stepCalls = mockedStepPage.mock.calls.length;

    await clickToolbarButton(zhCN.runSteps.refreshRunContext);

    expect(mockedGetRun.mock.calls.length).toBe(runCalls + 1);
    expect(mockedStepPage.mock.calls.length).toBeGreaterThan(stepCalls);
    for (const call of mockedStepPage.mock.calls) {
      expect(call[0]).toMatchObject({ pipelineRunId: 7 });
    }
  });
});

describe('RunStepsView 步骤状态与日志摘录', () => {
  it('整次运行日志按步骤追加，并发现运行中出现的下一阶段', async () => {
    const first = stepRow({ id: 1, stageName: '构建', stepName: '构建镜像' });
    const next = stepRow({ id: 2, stageName: '验证', stepName: '健康检查' });
    const rows = (records: Record<string, unknown>[]) => ({ pageNum: 1, pageSize: 100, total: records.length, records });
    let discoveries = 0;
    mockedStepPage.mockImplementation(async params => params.pageSize === 100
      ? rows(++discoveries === 1 ? [first] : [first, next])
      : rows([first]));
    const chunk = (text: string, finished: boolean) => ({
      available: true, base64: Buffer.from(text).toString('base64'),
      nextOffset: Buffer.byteLength(text), hasMore: false, finished,
    });
    mockedStepLog.mockReset()
      .mockResolvedValueOnce(chunk('image built\n', true))
      .mockResolvedValueOnce(chunk('health check started\n', false))
      .mockResolvedValueOnce(chunk('HTTP 200\n', true));
    mockedGetRun.mockResolvedValueOnce(context({ runStatus: 'RUNNING' }));

    await mountView();
    await clickToolbarButton(zhCN.runSteps.openRunLog);
    const dialog = dialogByTitle(zhCN.runSteps.runLogTitle);
    await vi.waitFor(() => expect(dialog.textContent).toContain('health check started'), { timeout: 2000 });
    expect(dialog.textContent).toContain(zhCN.runSteps.fullLogLive);
    await vi.waitFor(() => expect(dialog.textContent).toContain(zhCN.runSteps.runLogFinished), { timeout: 2800 });
    expect(Array.from(dialog.querySelectorAll('h3')).map(node => node.textContent)).toEqual(['构建 / 构建镜像', '验证 / 健康检查']);
    expect(Array.from(dialog.querySelectorAll('pre')).map(node => node.textContent)).toEqual(['image built\n', 'health check started\nHTTP 200\n']);
    expect(mockedStepLog.mock.calls).toEqual([[7, 1, 0], [7, 2, 0], [7, 2, 21]]);
  });

  it('完整日志增量读取并合并跨分段的 UTF-8 字符', async () => {
    const output = 'x'.repeat(5000) + '构建完成\n';
    const bytes = Buffer.from(output);
    const splitAt = 5001;
    mockedStepLog.mockReset()
      .mockResolvedValueOnce({ available: true, base64: bytes.subarray(0, splitAt).toString('base64'), nextOffset: splitAt, hasMore: false, finished: false })
      .mockResolvedValueOnce({ available: true, base64: bytes.subarray(splitAt).toString('base64'), nextOffset: bytes.length, hasMore: false, finished: true });

    await mountView('/pipeline/runs/7/steps');
    await clickRowButton(0, zhCN.runSteps.openFullLog);
    const dialog = dialogByTitle(zhCN.runSteps.fullLogTitle);
    expect(dialog.querySelector('pre')?.textContent).toBe('x'.repeat(5000));
    expect(dialog.textContent).toContain(zhCN.runSteps.fullLogLive);

    await vi.waitFor(() => expect(dialog.querySelector('pre')?.textContent).toBe(output), { timeout: 2000 });
    expect(mockedStepLog.mock.calls).toEqual([[7, 1, 0], [7, 1, splitAt]]);
    expect(dialog.textContent).toContain(zhCN.runSteps.fullLogFinished);
  });

  it('没有完整日志的历史步骤仍可查看摘录', async () => {
    mockedStepLog.mockReset().mockResolvedValueOnce({ available: false, base64: '', nextOffset: 0, hasMore: false, finished: true });
    await mountView('/pipeline/runs/7/steps');
    await clickRowButton(0, zhCN.runSteps.openFullLog);

    const dialog = dialogByTitle(zhCN.runSteps.fullLogTitle);
    expect(dialog.textContent).toContain(zhCN.runSteps.fullLogUnavailable);
    expect(dialog.querySelector('pre')).toBeNull();
    expect(buttonWithLabel(bodyRows()[0], zhCN.runSteps.openLogExcerpt)).toBeDefined();
  });

  it('步骤状态与流水线状态分开显示：步骤未知状态保留原始值，不推断整条流水线成功', async () => {
    mockedGetRun.mockResolvedValueOnce(context({ runStatus: 'RUNNING' }) as never);
    mockedStepPage.mockResolvedValueOnce({
      pageNum: 1,
      pageSize: 10,
      total: 2,
      records: [
        stepRow({ id: 1, runStatus: 'SUCCESS' }),
        stepRow({ id: 2, runStatus: 'ODD_STATE' }),
      ],
    } as never);

    await mountView('/pipeline/runs/7/steps');

    expect(bodyText()).toContain(`${zhCN.runSteps.runStatusLabel}：${zhCN.pipelineRuns.statusRunning}`);
    expect(bodyText()).toContain(`${zhCN.runSteps.stepsStatusLabel}`);
    expect(bodyText()).toContain(zhCN.pipelineRuns.statusSuccess);
    expect(bodyText()).toContain(`${zhCN.pipelineRuns.statusUnconfirmed}ODD_STATE`);
    // 步骤列表页不给出整条流水线的结论
    expect(bodyText()).not.toContain(zhCN.runSteps.statusSeparateHint);
  });

  it('日志摘录只读打开并原样展示后端内容，明确说明这不是完整日志', async () => {
    const raw = 'line-1\n\tline-2\nline-3';
    mockedStepPage.mockResolvedValueOnce({
      pageNum: 1,
      pageSize: 10,
      total: 1,
      records: [stepRow({ logExcerpt: raw, stepName: '编译' })],
    } as never);

    await mountView('/pipeline/runs/7/steps');
    await clickRowButton(0, zhCN.runSteps.openLogExcerpt);

    const dialog = dialogByTitle(zhCN.runSteps.logExcerptTitle);
    expect(dialog.querySelector('.excerpt-step')?.textContent).toContain('编译');
    expect(dialog.querySelector('.excerpt-notice')?.textContent).toContain('4000');
    expect(dialog.querySelector('.excerpt-body')?.textContent).toBe(raw);

    const copyButton = buttonWithLabel(dialog, zhCN.runSteps.logExcerptCopy);
    expect(copyButton).toBeDefined();
    copyButton!.click();
    await flushPromises();

    expect(writeText).toHaveBeenCalledTimes(1);
    expect(writeText).toHaveBeenCalledWith(raw);
  });

  it('空摘录明确提示没有内容，复制不产生剪贴板调用', async () => {
    mockedStepPage.mockResolvedValueOnce({
      pageNum: 1,
      pageSize: 10,
      total: 1,
      records: [stepRow({ logExcerpt: '' })],
    } as never);
    const warning = vi.spyOn(ElMessage, 'warning').mockImplementation(() => ({}) as never);

    await mountView('/pipeline/runs/7/steps');
    await clickRowButton(0, zhCN.runSteps.openLogExcerpt);

    const dialog = dialogByTitle(zhCN.runSteps.logExcerptTitle);
    expect(dialog.querySelector('.excerpt-body')).toBeNull();
    expect(dialog.textContent).toContain(zhCN.runSteps.logExcerptEmpty);

    buttonWithLabel(dialog, zhCN.runSteps.logExcerptCopy)!.click();
    await flushPromises();

    expect(writeText).not.toHaveBeenCalled();
    expect(warning).toHaveBeenCalledWith(zhCN.runSteps.logExcerptCopyEmpty);
  });

  it('复制失败给出显式反馈，不静默失败', async () => {
    writeText.mockRejectedValueOnce(new Error('clipboard denied'));
    const error = vi.spyOn(ElMessage, 'error').mockImplementation(() => ({}) as never);

    await mountView('/pipeline/runs/7/steps');
    await clickRowButton(0, zhCN.runSteps.openLogExcerpt);

    const dialog = dialogByTitle(zhCN.runSteps.logExcerptTitle);
    buttonWithLabel(dialog, zhCN.runSteps.logExcerptCopy)!.click();
    await flushPromises();

    expect(error).toHaveBeenCalledWith(zhCN.runSteps.logExcerptCopyFailed);
  });
});

describe('RunStepsView 文案与语言切换', () => {
  it('已打开的界面随语言即时切换，且切换语言本身不是一次读取', async () => {
    await mountView('/pipeline/runs/7/steps', ['RELEASE_VIEW']);

    expect(toolbarButton(zhCN.runSteps.deployRecordsAction)).toBeDefined();
    expect(toolbarButton(zhCN.runSteps.refreshRunContext)).toBeDefined();

    const runCalls = mockedGetRun.mock.calls.length;
    const stepCalls = mockedStepPage.mock.calls.length;

    setLocale('en-US');
    await flushPromises();

    expect(toolbarButton(enUS.runSteps.deployRecordsAction)).toBeDefined();
    expect(toolbarButton(enUS.runSteps.refreshRunContext)).toBeDefined();
    expect(toolbarButton(zhCN.runSteps.deployRecordsAction)).toBeUndefined();
    expect(bodyText()).toContain(enUS.runSteps.releaseOrderLinked.replace('{id}', '89'));
    // 切换语言不重读运行信息，也不触发任何部署记录读取
    expect(mockedGetRun.mock.calls.length).toBe(runCalls);
    expect(mockedDeployRecords).not.toHaveBeenCalled();
    // 共享列表组件会在页面配置重算时重发列表查询（沿用既有行为）：允许重发，但目标必须是同一个运行
    expect(mockedStepPage.mock.calls.length).toBeGreaterThanOrEqual(stepCalls);
    expect(mockedStepPage.mock.calls.length).toBeLessThanOrEqual(stepCalls + 1);
    for (const call of mockedStepPage.mock.calls) {
      expect(call[0]).toMatchObject({ pipelineRunId: 7 });
    }

    setLocale('zh-CN');
    await flushPromises();
    expect(toolbarButton(zhCN.runSteps.deployRecordsAction)).toBeDefined();
    expect(mockedGetRun.mock.calls.length).toBe(runCalls);
  });

  it('后端原始错误与未知状态原始值不随语言被改写', async () => {
    mockedGetRun.mockRejectedValueOnce(new Error('运行记录不存在：id=7'));
    await mountView('/pipeline/runs/7/steps');

    setLocale('en-US');
    await flushPromises();

    expect(document.body.querySelector('.run-context-alert .raw-error-text')?.textContent)
      .toContain('运行记录不存在：id=7');
  });
});
