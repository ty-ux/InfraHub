import { afterEach, beforeEach, describe, expect, it, vi, type MockInstance } from 'vitest';
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils';
import ElementPlus from 'element-plus';
import { createPinia, setActivePinia } from 'pinia';
import { createMemoryHistory, createRouter } from 'vue-router';
import { i18n, setLocale } from '@/i18n';
import zhCN from '@/i18n/locales/zh-CN';
import enUS from '@/i18n/locales/en-US';
import ReleaseOrdersView from '../ReleaseOrdersView.vue';
import DeployRecordsDialog from '@/components/release/DeployRecordsDialog.vue';
import PrecheckDialog from '@/components/release/PrecheckDialog.vue';
import ReleaseDetailDrawer from '@/components/release/ReleaseDetailDrawer.vue';
import { useAuthStore } from '@/store/auth';
import {
  releaseApi,
  executeRelease,
  getReleaseDeployRecords,
  getReleaseDetail,
  getPrecheckSummary,
  precheckRelease,
} from '@/api/release';

/**
 * 第二批把「配置检查 / 流水线发布」收进行内「更多」菜单，同时要求原有动作判据不被削弱。
 * 这里锁定：权限判据（RELEASE_EXECUTE / RELEASE_CANCEL）与状态条件（CREATED/APPROVED 可执行、
 * SUCCESS 不可执行）保持原样；每行最多只有一个高亮主操作；详情始终为稳定入口。
 * 全部为组件级自测（网络被 mock，不连接真实后端，不触发任何写请求）。
 */
vi.mock('@/api/release', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/release')>();
  const emptyPage = vi.fn(async () => ({ pageNum: 1, pageSize: 200, total: 0, records: [] }));
  return {
    ...actual,
    releaseApi: {
      ...actual.releaseApi,
      page: vi.fn(),
      create: vi.fn(),
      update: vi.fn(),
      remove: vi.fn(),
    },
    // 列表富化会用到的只读字典接口：不连接真实后端
    versionApi: { ...actual.versionApi, page: emptyPage },
    currentVersionApi: { ...actual.currentVersionApi, page: emptyPage },
    precheckRelease: vi.fn(),
    executeRelease: vi.fn(),
    cancelRelease: vi.fn(),
    getReleaseDeployRecords: vi.fn(),
    getPrecheckSummary: vi.fn(),
    // 详情抽屉的只读接口：组件级自测不连接真实后端
    getReleaseDetail: vi.fn(),
    getCanaryPlan: vi.fn(async () => null),
    checkDeployConfig: vi.fn(),
    executeReleasePipeline: vi.fn(),
  };
});

vi.mock('@/api/pipeline', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/pipeline')>();
  const emptyPage = async () => ({ pageNum: 1, pageSize: 200, total: 0, records: [] });
  return {
    ...actual,
    pipelineApi: { ...actual.pipelineApi, page: vi.fn(emptyPage) },
    pipelineRunApi: { ...actual.pipelineRunApi, page: vi.fn(emptyPage) },
    pipelineHasReleaseDeployStep: vi.fn(async () => false),
  };
});

vi.mock('@/api/resource', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/resource')>();
  const emptyPage = vi.fn(async () => ({ pageNum: 1, pageSize: 200, total: 0, records: [] }));
  return {
    ...actual,
    appApi: { ...actual.appApi, page: emptyPage },
    serviceApi: { ...actual.serviceApi, page: emptyPage },
    envApi: { ...actual.envApi, page: emptyPage },
    configGroupApi: { ...actual.configGroupApi, page: emptyPage },
    envConfigApi: { ...actual.envConfigApi, page: emptyPage },
    secretRefApi: { ...actual.secretRefApi, page: emptyPage },
  };
});

const mockedReleasePage = vi.mocked(releaseApi.page);
const mockedDeployRecords = vi.mocked(getReleaseDeployRecords);
const mockedPrecheckSummary = vi.mocked(getPrecheckSummary);
const mockedPrecheckRelease = vi.mocked(precheckRelease);
const mockedReleaseDetail = vi.mocked(getReleaseDetail);
const mockedExecuteRelease = vi.mocked(executeRelease);

function row(overrides: Record<string, unknown> = {}) {
  return {
    id: 1,
    orderNo: 'REL-1',
    appId: 11,
    serviceId: 22,
    envId: 33,
    appDisplayName: 'app-1',
    serviceDisplayName: 'svc-1',
    envDisplayName: 'prod',
    versionDisplayName: 'v1.0.0',
    status: 'CREATED',
    releaseType: 'FULL',
    ...overrides,
  };
}

let wrapper: VueWrapper | undefined;
let consoleError: MockInstance;

async function mountView(authorities: string[], records: Record<string, unknown>[]) {
  setLocale('zh-CN');
  const pinia = createPinia();
  setActivePinia(pinia);
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/', component: { template: '<div />' } }],
  });
  await router.push('/');
  await router.isReady();

  const authStore = useAuthStore();
  authStore.currentUser = { id: 1, username: 'tester', authorities } as never;

  mockedReleasePage.mockResolvedValue({
    pageNum: 1,
    pageSize: 20,
    total: records.length,
    records,
  } as never);

  wrapper = mount(ReleaseOrdersView, {
    global: { plugins: [ElementPlus, i18n, pinia, router] },
    attachTo: document.body,
  });
  await flushPromises();
}

/**
 * 行内动作区：限定在真实数据行内。
 * el-table 另有一份 hidden-columns 影子节点会以 undefined 的 row 渲染同一个插槽，
 * 它不是数据行，必须排除，否则会读到与真实行无关的动作集合。
 */
function rowActions(): HTMLElement[] {
  return Array.from(
    document.body.querySelectorAll<HTMLElement>('.el-table__row .row-action-buttons'),
  );
}

function actionLabels(index = 0): string[] {
  const container = rowActions()[index];
  if (!container) return [];
  return Array.from(container.querySelectorAll('button')).map(
    button => (button.textContent || '').trim(),
  );
}

function primaryActionTexts(index = 0): string[] {
  const container = rowActions()[index];
  if (!container) return [];
  return Array.from(container.querySelectorAll('.is-row-primary-action')).map(
    el => (el.textContent || '').trim(),
  );
}

/** 「更多」触发器：同样只取真实数据行内的那个 */
function moreTrigger(index = 0): HTMLButtonElement | undefined {
  const container = rowActions()[index];
  return (container?.querySelector('.row-more-trigger') as HTMLButtonElement | undefined) ?? undefined;
}

interface Deferred<T> {
  promise: Promise<T>;
  resolve: (value: T) => void;
  reject: (reason?: unknown) => void;
}

/** 手工控制完成时机的读取：用于构造乱序返回与关闭后才返回的迟到响应 */
function deferred<T>(): Deferred<T> {
  let resolve!: (value: T) => void;
  let reject!: (reason?: unknown) => void;
  const promise = new Promise<T>((res, rej) => {
    resolve = res;
    reject = rej;
  });
  return { promise, resolve, reject };
}

/** 部署记录的一条后端原始记录 */
function deployRecord(overrides: Record<string, unknown> = {}) {
  return {
    id: 900,
    releaseOrderId: 1,
    operationType: 'RELEASE',
    serviceId: 22,
    envId: 33,
    hostId: 22,
    versionId: 101,
    deployBatch: 1,
    deployStatus: 'SUCCESS',
    deployLog: '',
    startedAt: '2026-05-20T10:00:00',
    endedAt: '2026-05-20T10:01:00',
    ...overrides,
  };
}

function deployDialog() {
  const dialog = wrapper?.findComponent(DeployRecordsDialog);
  if (!dialog || !dialog.exists()) throw new Error('部署记录弹窗未挂载');
  return dialog;
}

function precheckDialog() {
  const dialog = wrapper?.findComponent(PrecheckDialog);
  if (!dialog || !dialog.exists()) throw new Error('前置检查弹窗未挂载');
  return dialog;
}

/** 限定在某个真实数据行内，按可见文案点击动作 */
async function clickRowAction(index: number, label: string) {
  const container = rowActions()[index];
  const button = Array.from(container?.querySelectorAll('button') ?? []).find(
    el => (el.textContent || '').trim() === label,
  ) as HTMLButtonElement | undefined;
  if (!button) throw new Error(`第 ${index + 1} 行没有「${label}」动作`);
  button.click();
  await flushPromises();
}

/** 通过弹窗自身的关闭入口关闭当前打开的弹窗（等价于用户点击弹窗底部「关闭」） */
async function closeOpenDialog() {
  const buttons = document.body.querySelectorAll<HTMLButtonElement>('.el-dialog__footer button');
  const closeButton = buttons[buttons.length - 1];
  if (!closeButton) throw new Error('弹窗没有关闭入口');
  closeButton.click();
  await flushPromises();
}

/** 读取失败态下的只读重试入口 */
async function clickRetryReadOnly() {
  const button = document.body.querySelector<HTMLButtonElement>('.read-error-block button');
  if (!button) throw new Error('只读重试入口未呈现');
  button.click();
  await flushPromises();
}

function bodyText(selector: string): string {
  return (document.body.querySelector(selector)?.textContent || '').replace(/\s+/g, ' ').trim();
}

/** 预检摘要：只区分归属，字段本身不是断言对象 */
function summaryOf(orderId: number): Awaited<ReturnType<typeof getPrecheckSummary>> {
  return {
    orderId,
    orderType: 'RELEASE',
    overallStatus: 'SUCCESS',
    totalHosts: 1,
    successHosts: 1,
    failedHosts: 0,
    totalSteps: 3,
    failedSteps: 0,
    hostSummaries: [],
    failureCategories: [],
    suggestions: [],
    generatedAt: '2026-05-20T10:00:00',
  };
}

beforeEach(() => {
  consoleError = vi.spyOn(console, 'error').mockImplementation(() => undefined);
  vi.spyOn(console, 'warn').mockImplementation(() => undefined);
  mockedReleasePage.mockReset();
});

afterEach(() => {
  wrapper?.unmount();
  wrapper = undefined;
  document.body.innerHTML = '';
  vi.restoreAllMocks();
});

describe('ReleaseOrdersView 行内动作判据', () => {
  it('缺少 RELEASE_EXECUTE 时不出现执行动作，详情与部署记录仍可用', async () => {
    await mountView(['RELEASE_VIEW'], [row({ status: 'CREATED' })]);

    const labels = actionLabels();
    expect(labels).toContain('详情');
    expect(labels).toContain('部署记录');
    expect(labels).not.toContain('执行发布');
    expect(labels).not.toContain('前置检查');
    expect(labels).not.toContain('流水线发布');
    // CREATED 但缺少 RELEASE_CANCEL：取消发布同样不开放
    expect(labels).not.toContain('取消发布');
    expect(primaryActionTexts()).toHaveLength(0);
  });

  it('拥有 RELEASE_EXECUTE 时 CREATED 行只高亮「执行发布」这一个主操作', async () => {
    await mountView(['RELEASE_EXECUTE'], [row({ status: 'CREATED' })]);

    const labels = actionLabels();
    expect(labels).toContain('前置检查');
    expect(labels).toContain('执行发布');
    expect(primaryActionTexts()).toEqual(['执行发布']);
  });

  it('SUCCESS 状态不再开放执行动作，也不产生高亮主操作', async () => {
    await mountView(['RELEASE_EXECUTE', 'RELEASE_CANCEL'], [row({ status: 'SUCCESS' })]);

    const labels = actionLabels();
    expect(labels).toContain('详情');
    expect(labels).not.toContain('执行发布');
    expect(labels).not.toContain('前置检查');
    expect(labels).not.toContain('取消发布');
    expect(labels).toContain('流水线日志');
    expect(primaryActionTexts()).toHaveLength(0);
  });

  it('RUNNING 行高亮转向证据入口，动作按钮不消失', async () => {
    await mountView(['RELEASE_EXECUTE'], [row({ status: 'RUNNING', hasPipelineRun: true })]);

    expect(actionLabels()).toContain('流水线日志');
    expect(primaryActionTexts()).toEqual(['流水线日志']);
  });

  it('FAILED 行高亮部署记录，详情仍为稳定入口', async () => {
    await mountView(['RELEASE_VIEW'], [row({ status: 'FAILED' })]);

    expect(actionLabels()).toContain('详情');
    expect(primaryActionTexts()).toEqual(['部署记录']);
  });

  it('「更多」仅在确有次要动作时出现，缺 appId/envId 且不可执行时没有菜单', async () => {
    await mountView(['RELEASE_VIEW'], [row({ status: 'CREATED', appId: null, envId: null })]);

    expect(actionLabels()).not.toContain('更多');
  });

  it('「更多」保留次要动作：可执行时同时提供配置检查与流水线发布', async () => {
    await mountView(['RELEASE_EXECUTE'], [row({ status: 'CREATED' })]);

    const trigger = moreTrigger();
    expect(trigger).toBeDefined();
    trigger!.click();
    await flushPromises();

    const menuItems = Array.from(document.body.querySelectorAll('.el-dropdown-menu__item')).map(
      item => (item.textContent || '').trim(),
    );
    expect(menuItems).toContain('配置检查');
    expect(menuItems).toContain('流水线发布');
  });

  it('「更多」中缺少不可执行条件时只保留配置检查', async () => {
    await mountView(['RELEASE_VIEW'], [row({ status: 'SUCCESS' })]);

    const trigger = moreTrigger();
    expect(trigger).toBeDefined();
    trigger!.click();
    await flushPromises();

    const menuItems = Array.from(document.body.querySelectorAll('.el-dropdown-menu__item')).map(
      item => (item.textContent || '').trim(),
    );
    expect(menuItems).toContain('配置检查');
    expect(menuItems).not.toContain('流水线发布');
  });
});

/**
 * 部署记录读取通道的归属：该通道持有独立请求序号与查看对象快照。
 * 切换查看对象或关闭弹窗后，旧请求的成功、失败与 finally 一律失去更新当前展示的资格；
 * 同一对象的读取失败保留已读到的证据，只显式标注本次读取失败；
 * 只读重试始终绑定当前对象且只走 GET。全部为组件级自测：网络被 mock，不连接真实后端，不产生写请求。
 */
describe('ReleaseOrdersView 部署记录读取归属', () => {
  beforeEach(() => {
    mockedDeployRecords.mockReset();
    mockedReleaseDetail.mockReset();
  });

  it('A/B 乱序返回时，晚到的 A 不得覆盖 B 的记录、错误与加载态', async () => {
    const readA = deferred<Record<string, unknown>[]>();
    const readB = deferred<Record<string, unknown>[]>();
    mockedDeployRecords
      .mockImplementationOnce(() => readA.promise)
      .mockImplementationOnce(() => readB.promise);

    await mountView(['RELEASE_VIEW'], [row({ id: 1 }), row({ id: 2 })]);

    await clickRowAction(0, '部署记录');
    await clickRowAction(1, '部署记录');
    // 每次读取都绑定当时查看的对象，不共用一次请求
    expect(mockedDeployRecords.mock.calls.map(call => call[0])).toEqual([1, 2]);

    // A 迟到成功：不得写进 B 的展示
    readA.resolve([deployRecord({ id: 501 })]);
    await flushPromises();
    expect(deployDialog().props('records')).toEqual([]);
    expect(deployDialog().props('error')).toBeNull();
    expect(deployDialog().props('loading')).toBe(true);

    // B 返回：展示 B 的记录
    readB.resolve([deployRecord({ id: 601 })]);
    await flushPromises();
    expect(deployDialog().props('records')).toEqual([deployRecord({ id: 601 })]);
    expect(deployDialog().props('error')).toBeNull();
    expect(deployDialog().props('loading')).toBe(false);
  });

  it('切换查看对象立即清空上一对象的证据与失败标记', async () => {
    const readB = deferred<Record<string, unknown>[]>();
    const evidenceA = [deployRecord({ id: 501 })];
    mockedDeployRecords
      .mockResolvedValueOnce(evidenceA)
      .mockRejectedValueOnce(new Error('A 的读取失败'))
      .mockImplementationOnce(() => readB.promise);

    await mountView(['RELEASE_VIEW'], [row({ id: 1 }), row({ id: 2 })]);

    // 同一对象的读取失败：保留已读到的证据，只显式标注本次失败
    await clickRowAction(0, '部署记录');
    expect(deployDialog().props('records')).toEqual(evidenceA);
    await clickRowAction(0, '部署记录');
    expect(mockedDeployRecords.mock.calls.map(call => call[0])).toEqual([1, 1]);
    expect(deployDialog().props('records')).toEqual(evidenceA);
    expect(deployDialog().props('error')).toBe('A 的读取失败');

    // 切换对象：旧证据与旧失败标记立即消失
    await clickRowAction(1, '部署记录');
    expect(deployDialog().props('records')).toEqual([]);
    expect(deployDialog().props('error')).toBeNull();
    expect(deployDialog().props('selectedRecordId')).toBeNull();

    readB.resolve([deployRecord({ id: 601 })]);
    await flushPromises();
    expect(deployDialog().props('records')).toEqual([deployRecord({ id: 601 })]);
    expect(deployDialog().props('error')).toBeNull();
  });

  it('关闭弹窗后迟到的成功与失败都不得更新当前展示', async () => {
    const lateSuccess = deferred<Record<string, unknown>[]>();
    const lateFailure = deferred<Record<string, unknown>[]>();
    mockedDeployRecords
      .mockImplementationOnce(() => lateSuccess.promise)
      .mockImplementationOnce(() => lateFailure.promise);

    await mountView(['RELEASE_VIEW'], [row({ id: 1 }), row({ id: 2 })]);

    // 读取在途时关闭：迟到的成功不得写回
    await clickRowAction(0, '部署记录');
    await closeOpenDialog();
    expect(deployDialog().props('modelValue')).toBe(false);
    lateSuccess.resolve([deployRecord({ id: 501 })]);
    await flushPromises();
    expect(deployDialog().props('records')).toEqual([]);
    expect(deployDialog().props('error')).toBeNull();
    expect(deployDialog().props('loading')).toBe(false);

    // 读取在途时关闭：迟到的失败不得冒充当前对象的错误
    await clickRowAction(1, '部署记录');
    expect(deployDialog().props('loading')).toBe(true);
    await closeOpenDialog();
    lateFailure.reject(new Error('部署记录读取失败'));
    await flushPromises();
    expect(deployDialog().props('records')).toEqual([]);
    expect(deployDialog().props('error')).toBeNull();
    expect(deployDialog().props('loading')).toBe(false);
  });

  it('旧请求的 finally 不得清掉当前请求的加载态，失败也不得记到新对象名下', async () => {
    const readA = deferred<Record<string, unknown>[]>();
    const readB = deferred<Record<string, unknown>[]>();
    mockedDeployRecords
      .mockImplementationOnce(() => readA.promise)
      .mockImplementationOnce(() => readB.promise);

    await mountView(['RELEASE_VIEW'], [row({ id: 1 }), row({ id: 2 })]);

    await clickRowAction(0, '部署记录');
    await clickRowAction(1, '部署记录');

    // A 失败：既不能记成 B 的读取错误，也不能把 B 的加载态收掉
    readA.reject(new Error('A 的读取失败'));
    await flushPromises();
    expect(deployDialog().props('loading')).toBe(true);
    expect(deployDialog().props('error')).toBeNull();
    expect(deployDialog().props('records')).toEqual([]);

    readB.resolve([deployRecord({ id: 601 })]);
    await flushPromises();
    expect(deployDialog().props('loading')).toBe(false);
    expect(deployDialog().props('records')).toEqual([deployRecord({ id: 601 })]);
    expect(deployDialog().props('error')).toBeNull();
  });

  it('同一对象的只读重试保留已读证据、显式标注失败，并仍定位到目标记录', async () => {
    const evidence = [
      deployRecord({ id: 77, deployBatch: 1, deployStatus: 'FAILED', deployLog: '部署失败：端口占用' }),
    ];
    const refreshed = [...evidence, deployRecord({ id: 78, deployBatch: 1, deployStatus: 'SUCCESS' })];
    mockedDeployRecords
      .mockResolvedValueOnce(evidence)
      .mockRejectedValueOnce(new Error('部署记录读取超时'))
      .mockResolvedValueOnce(refreshed);
    mockedReleaseDetail.mockResolvedValue({ id: 1, orderNo: 'REL-1', status: 'SUCCESS' });

    await mountView(['RELEASE_VIEW'], [row({ id: 1, status: 'SUCCESS' })]);

    // 从详情定位到具体记录
    await clickRowAction(0, '详情');
    const drawer = wrapper!.findComponent(ReleaseDetailDrawer);
    drawer.vm.$emit('open-deploy-records-with-id', 77);
    await flushPromises();

    expect(mockedDeployRecords.mock.calls.map(call => call[0])).toEqual([1]);
    expect(deployDialog().props('records')).toEqual(evidence);
    expect(deployDialog().props('error')).toBeNull();
    expect(deployDialog().props('selectedRecordId')).toBe(77);
    expect(document.body.querySelectorAll('.deploy-target-row')).toHaveLength(1);
    expect(bodyText('.record-summary')).toContain('记录ID: 77');

    // 同一对象的读取失败：保留已读到的证据，显式标注本次失败，定位不丢
    drawer.vm.$emit('open-deploy-records-with-id', 77);
    await flushPromises();
    expect(deployDialog().props('records')).toEqual(evidence);
    expect(deployDialog().props('error')).toBe('部署记录读取超时');
    expect(deployDialog().props('loading')).toBe(false);
    expect(deployDialog().props('selectedRecordId')).toBe(77);
    expect(document.body.querySelectorAll('.deploy-target-row')).toHaveLength(1);

    // 只读重试：仍绑定同一对象，读到的新记录里目标记录仍被定位
    await clickRetryReadOnly();
    expect(mockedDeployRecords.mock.calls.map(call => call[0])).toEqual([1, 1, 1]);
    expect(deployDialog().props('records')).toEqual(refreshed);
    expect(deployDialog().props('error')).toBeNull();
    expect(deployDialog().props('loading')).toBe(false);
    expect(deployDialog().props('selectedRecordId')).toBe(77);
    expect(document.body.querySelectorAll('.deploy-target-row')).toHaveLength(1);
    expect(bodyText('.record-summary')).toContain('记录ID: 77');
    expect(mockedExecuteRelease).not.toHaveBeenCalled();
    expect(mockedPrecheckRelease).not.toHaveBeenCalled();
  });
});

/**
 * 预检摘要读取通道与执行通道相互独立，各自持有请求序号；
 * 摘要乱序返回时，晚到的旧对象摘要不得覆盖当前对象的摘要。
 */
describe('ReleaseOrdersView 预检摘要读取归属', () => {
  beforeEach(() => {
    mockedPrecheckSummary.mockReset();
    mockedPrecheckRelease.mockReset();
  });

  it('摘要请求乱序返回时，晚到的 A 摘要不得覆盖 B 的摘要', async () => {
    const execA = deferred<Awaited<ReturnType<typeof precheckRelease>>>();
    const execB = deferred<Awaited<ReturnType<typeof precheckRelease>>>();
    const summaryA = deferred<Awaited<ReturnType<typeof getPrecheckSummary>>>();
    const summaryB = deferred<Awaited<ReturnType<typeof getPrecheckSummary>>>();
    mockedPrecheckRelease
      .mockImplementationOnce(() => execA.promise)
      .mockImplementationOnce(() => execB.promise);
    mockedPrecheckSummary
      .mockImplementationOnce(() => summaryA.promise)
      .mockImplementationOnce(() => summaryB.promise);

    await mountView(['RELEASE_EXECUTE'], [row({ id: 1 }), row({ id: 2 })]);

    // A 的前置检查完成，摘要读取在途
    await clickRowAction(0, '前置检查');
    execA.resolve([]);
    await flushPromises();
    expect(mockedPrecheckSummary.mock.calls.map(call => call[0])).toEqual([1]);

    // 切到 B：摘要被清空，A 的摘要随后迟到
    await clickRowAction(1, '前置检查');
    expect(precheckDialog().props('precheckSummary')).toBeNull();

    summaryA.resolve({ ...summaryOf(1), overallStatus: 'FAILED' });
    await flushPromises();
    expect(precheckDialog().props('precheckSummary')).toBeNull();

    // B 的摘要正常展示
    execB.resolve([]);
    await flushPromises();
    expect(mockedPrecheckSummary.mock.calls.map(call => call[0])).toEqual([1, 2]);
    summaryB.resolve(summaryOf(2));
    await flushPromises();
    expect(precheckDialog().props('precheckSummary')).toEqual(summaryOf(2));
  });

  it('关闭弹窗后迟到的摘要不得再写回展示', async () => {
    const execA = deferred<Awaited<ReturnType<typeof precheckRelease>>>();
    const summaryA = deferred<Awaited<ReturnType<typeof getPrecheckSummary>>>();
    mockedPrecheckRelease.mockImplementationOnce(() => execA.promise);
    mockedPrecheckSummary.mockImplementationOnce(() => summaryA.promise);

    await mountView(['RELEASE_EXECUTE'], [row({ id: 1 })]);
    await clickRowAction(0, '前置检查');
    execA.resolve([]);
    await flushPromises();

    await closeOpenDialog();
    summaryA.resolve(summaryOf(1));
    await flushPromises();
    expect(precheckDialog().props('precheckSummary')).toBeNull();
  });

  /**
   * 刷新窗口回归：A 的前置检查已完成、列表刷新仍挂起时关闭并切到 B。
   * A 的旧流程必须就地结束——既不启动摘要读取，也不能作废 B 正在进行的摘要读取或改写 B 的加载态。
   */
  it('刷新挂起期间关闭并切到 B：A 不再启动摘要读取，B 的摘要与加载态不受影响', async () => {
    const execA = deferred<Awaited<ReturnType<typeof precheckRelease>>>();
    const execB = deferred<Awaited<ReturnType<typeof precheckRelease>>>();
    const refreshA = deferred<Awaited<ReturnType<typeof releaseApi.page>>>();
    const summaryB = deferred<Awaited<ReturnType<typeof getPrecheckSummary>>>();
    mockedPrecheckRelease
      .mockImplementationOnce(() => execA.promise)
      .mockImplementationOnce(() => execB.promise);
    mockedPrecheckSummary.mockImplementationOnce(() => summaryB.promise);

    await mountView(['RELEASE_EXECUTE'], [row({ id: 1 }), row({ id: 2 })]);
    // 下一次列表查询就是 A 的刷新：挂起它，让 A 停在刷新窗口里
    mockedReleasePage.mockImplementationOnce(() => refreshA.promise);

    await clickRowAction(0, '前置检查');
    execA.resolve([]);
    await flushPromises();
    expect(mockedPrecheckSummary).not.toHaveBeenCalled();

    // 刷新还没返回就关闭弹窗并切到 B
    await closeOpenDialog();
    await clickRowAction(1, '前置检查');
    execB.resolve([]);
    await flushPromises();
    // B 的刷新已收口，摘要读取在途
    expect(mockedPrecheckSummary.mock.calls.map(call => call[0])).toEqual([2]);
    expect(precheckDialog().props('loading')).toBe(true);

    // 释放 A 的刷新：旧流程必须止步，不得作废 B 的摘要读取
    refreshA.resolve({
      pageNum: 1,
      pageSize: 20,
      total: 2,
      records: [row({ id: 1 }), row({ id: 2 })],
    } as never);
    await flushPromises();
    expect(mockedPrecheckSummary.mock.calls.map(call => call[0])).toEqual([2]);
    expect(precheckDialog().props('loading')).toBe(true);

    // B 的摘要照常展示，加载态由 B 自己收口
    summaryB.resolve(summaryOf(2));
    await flushPromises();
    expect(precheckDialog().props('precheckSummary')).toEqual(summaryOf(2));
    expect(precheckDialog().props('loading')).toBe(false);
  });
});

/**
 * 本批文案必须走既有的 zh/en 词条而不是写死中文：
 * 同一份已渲染的界面在切换语言后立即换词，且切换语言本身不是一次读取。
 */
describe('ReleaseOrdersView 文案国际化与语言切换', () => {
  beforeEach(() => {
    mockedPrecheckSummary.mockReset();
    mockedPrecheckRelease.mockReset();
  });

  it('行内动作文案随语言切换，列表只重发同样的只读查询、不触发写操作', async () => {
    await mountView(['RELEASE_EXECUTE'], [row({ id: 1, status: 'CREATED' })]);

    expect(actionLabels()).toContain(zhCN.releaseOrders.actionDeployRecords);
    expect(actionLabels()).toContain(zhCN.releaseOrders.actionExecute);
    expect(primaryActionTexts()).toEqual([zhCN.releaseOrders.actionExecute]);
    expect(mockedReleasePage).toHaveBeenCalledTimes(1);

    setLocale('en-US');
    await flushPromises();

    expect(actionLabels()).toContain(enUS.releaseOrders.actionDeployRecords);
    expect(actionLabels()).toContain(enUS.releaseOrders.actionExecute);
    expect(actionLabels()).toContain(enUS.releaseOrders.actionDetail);
    expect(actionLabels()).not.toContain(zhCN.releaseOrders.actionDeployRecords);
    // 主操作判据不因语言变化：仍是同一个高亮动作
    expect(primaryActionTexts()).toEqual([enUS.releaseOrders.actionExecute]);

    setLocale('zh-CN');
    await flushPromises();
    expect(actionLabels()).toContain(zhCN.releaseOrders.actionDeployRecords);
    expect(actionLabels()).not.toContain(enUS.releaseOrders.actionDeployRecords);

    // 列表配置本身是当前语言的（列头 / 空态文案），切换语言会让 config 重算，
    // CrudPage 对 extraQuery 的既有行为因此各重发一次列表查询：
    // 查询条件必须与首次完全一致，且整条链路只有查询、没有任何写操作。
    const calls = mockedReleasePage.mock.calls;
    expect(calls.length).toBeGreaterThanOrEqual(1);
    const firstQuery = JSON.stringify(calls[0][0]);
    for (const call of calls) {
      expect(JSON.stringify(call[0])).toBe(firstQuery);
      expect(call[0]).toMatchObject({ sortMode: 'focus' });
    }
    expect(mockedExecuteRelease).not.toHaveBeenCalled();
    expect(mockedPrecheckRelease).not.toHaveBeenCalled();
  });

  it('摘要读取失败的兜底文案随语言切换，且读取仍只发生一次', async () => {
    mockedPrecheckRelease.mockResolvedValue([]);
    mockedPrecheckSummary.mockRejectedValueOnce(undefined);

    await mountView(['RELEASE_EXECUTE'], [row({ id: 1 })]);
    await clickRowAction(0, '前置检查');

    expect(mockedPrecheckSummary.mock.calls.map(call => call[0])).toEqual([1]);
    expect(precheckDialog().props('summaryError')).toBe(zhCN.releaseOrders.summaryReadFailedRetry);

    setLocale('en-US');
    await flushPromises();

    expect(precheckDialog().props('summaryError')).toBe(enUS.releaseOrders.summaryReadFailedRetry);
    expect(precheckDialog().props('summaryError')).not.toBe(zhCN.releaseOrders.summaryReadFailedRetry);
    expect(mockedPrecheckSummary.mock.calls.map(call => call[0])).toEqual([1]);

    setLocale('zh-CN');
    await flushPromises();

    expect(precheckDialog().props('summaryError')).toBe(zhCN.releaseOrders.summaryReadFailedRetry);
    expect(mockedPrecheckSummary.mock.calls.map(call => call[0])).toEqual([1]);
    // 切换语言不得触发任何执行动作
    expect(mockedPrecheckRelease).toHaveBeenCalledTimes(1);
    expect(mockedExecuteRelease).not.toHaveBeenCalled();
  });
});