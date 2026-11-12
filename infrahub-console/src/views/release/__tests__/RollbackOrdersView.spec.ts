import { afterEach, beforeEach, describe, expect, it, vi, type MockInstance } from 'vitest';
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils';
import ElementPlus, { ElMessage, ElTooltip } from 'element-plus';
import { createPinia, setActivePinia } from 'pinia';
import { createMemoryHistory, createRouter } from 'vue-router';
import { i18n, setLocale } from '@/i18n';
import zhCN from '@/i18n/locales/zh-CN';
import enUS from '@/i18n/locales/en-US';
import RollbackOrdersView from '../RollbackOrdersView.vue';
import RollbackCandidateDialog from '@/components/release/RollbackCandidateDialog.vue';
import DeployRecordsDialog from '@/components/release/DeployRecordsDialog.vue';
import PrecheckDialog from '@/components/release/PrecheckDialog.vue';
import ReleaseDetailDrawer from '@/components/release/ReleaseDetailDrawer.vue';
import { useAuthStore } from '@/store/auth';
import {
  rollbackApi,
  updateRollbackTarget,
  getRollbackCandidates,
  executeRollback,
  precheckRollback,
  getRollbackDeployRecords,
  getRollbackPrecheckSummary,
  getRollbackDetail,
} from '@/api/release';
import type { RollbackCandidateItem } from '@/api/release';

/**
 * 回滚目标保存必须区分“保存失败”与“保存成功后的刷新失败”：
 * - PUT 失败：保留所选候选，行内出现重试入口；
 * - PUT 成功：立即清除待保存候选，随后刷新失败只提示“已保存但展示刷新失败，可手动刷新”，
 *   不得把候选放回待保存、也不得提示上次保存未成功。
 * 只做组件级自测：网络被 mock，不连接真实后端。
 */
vi.mock('@/api/release', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/release')>();
  return {
    ...actual,
    rollbackApi: {
      page: vi.fn(),
      create: vi.fn(),
      update: vi.fn(),
      remove: vi.fn(),
    },
    releaseApi: { page: vi.fn(async () => ({ pageNum: 1, pageSize: 200, total: 0, records: [] })) },
    versionApi: { page: vi.fn(async () => ({ pageNum: 1, pageSize: 200, total: 0, records: [] })) },
    updateRollbackTarget: vi.fn(),
    getRollbackCandidates: vi.fn(),
    executeRollback: vi.fn(),
    precheckRollback: vi.fn(),
    getRollbackDeployRecords: vi.fn(),
    getRollbackPrecheckSummary: vi.fn(),
    // 详情抽屉的只读接口：组件级自测不连接真实后端
    getRollbackDetail: vi.fn(),
  };
});

vi.mock('@/api/resource', () => {
  const emptyPage = vi.fn(async () => ({ pageNum: 1, pageSize: 200, total: 0, records: [] }));
  return {
    appApi: { page: emptyPage },
    serviceApi: { page: emptyPage },
    envApi: { page: emptyPage },
    configGroupApi: { page: emptyPage },
    envConfigApi: { page: emptyPage },
    secretRefApi: { page: emptyPage },
  };
});

const mockedRollbackPage = vi.mocked(rollbackApi.page);
const mockedUpdateTarget = vi.mocked(updateRollbackTarget);
const mockedCandidates = vi.mocked(getRollbackCandidates);
const mockedExecuteRollback = vi.mocked(executeRollback);
const mockedPrecheckRollback = vi.mocked(precheckRollback);
const mockedDeployRecords = vi.mocked(getRollbackDeployRecords);
const mockedPrecheckSummary = vi.mocked(getRollbackPrecheckSummary);
const mockedRollbackDetail = vi.mocked(getRollbackDetail);

/** 明确支持的 SSH 回滚单：动作入口与候选入口都开放 */
function supportedCreatedRow() {
  return {
    id: 10,
    rollbackNo: 'RB-1',
    releaseOrderId: 1,
    status: 'CREATED',
    executorType: 'SSH',
    rollbackSupported: true,
    fromVersionId: 4,
    toVersionId: 5,
  };
}

const candidate: RollbackCandidateItem = {
  versionId: 7,
  versionNo: 'v1.2.0',
  isCurrent: false,
  deployedAt: '2026-10-01T10:00:00',
  rollbackCount: 0,
  recommendationScore: 90,
  recommendationReason: '稳定版本',
};

let wrapper: VueWrapper | undefined;
let successToast: MockInstance;
let warningToast: MockInstance;
let errorToast: MockInstance;
let consoleError: MockInstance;

async function mountView(records?: Record<string, unknown>[]) {
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
  authStore.currentUser = { id: 1, username: 'tester', authorities: ['SYSTEM_ADMIN'] } as never;

  if (records) {
    mockedRollbackPage.mockResolvedValue({
      pageNum: 1,
      pageSize: 20,
      total: records.length,
      records,
    } as never);
  }

  wrapper = mount(RollbackOrdersView, {
    global: { plugins: [ElementPlus, i18n, pinia, router] },
    attachTo: document.body,
  });
  await flushPromises();
}

/** 行内动作按钮文案（限定真实数据行，排除 el-table 的 hidden-columns 影子槽） */
function rowActionLabels(): string[] {
  const container = document.body.querySelector<HTMLElement>('.el-table__row .row-action-buttons');
  if (!container) return [];
  return Array.from(container.querySelectorAll('button')).map(
    button => (button.textContent || '').trim(),
  );
}

/** 行内支持状态标签文案与配色（回滚支持判据的可见结论） */
function supportTag(): { text: string; type: string } | undefined {
  const tag = document.body.querySelector<HTMLElement>(
    '.el-table__row .rollback-unsupported-actions .el-tag',
  );
  if (!tag) return undefined;
  return { text: (tag.textContent || '').trim(), type: tag.className };
}

/** 行内 disabled 占位按钮：不支持/无法确定时两者都必须保留且不可点击 */
function placeholderButtons(): { text: string; disabled: boolean }[] {
  const container = document.body.querySelector<HTMLElement>(
    '.el-table__row .rollback-unsupported-actions',
  );
  if (!container) return [];
  return Array.from(container.querySelectorAll('button')).map(button => ({
    text: (button.textContent || '').trim(),
    disabled: button.disabled,
  }));
}

/** 行内 tooltip 文案：支持状态原因通过 tooltip content 下发 */
function tooltipContents(): string[] {
  return wrapper!
    .findAllComponents(ElTooltip)
    .map(component => String(component.props('content') ?? ''))
    .filter(content => content.length > 0);
}

/** 视图根节点下的按钮：查询结果按明确的按钮类型取出，避免推断为 unknown */
function viewButtons(): HTMLButtonElement[] {
  return wrapper!.findAll<HTMLButtonElement>('button').map(button => button.element);
}

/** 打开候选弹窗：候选保存流程必须先经过行内“候选版本”入口，快照与回滚单 ID 才被写入 */
async function openCandidateDialog() {
  const button = viewButtons().find(
    el => (el.textContent || '').trim() === '候选版本',
  );
  if (!button) throw new Error('候选版本 button not found');
  button.click();
  await flushPromises();
}

/** 通过候选弹窗的对外 select 事件触发保存，等价于用户在弹窗中确认选择 */
async function selectCandidate(item: RollbackCandidateItem = candidate) {
  wrapper!.findComponent(RollbackCandidateDialog).vm.$emit('select', item);
  await flushPromises();
}

function hasRetryButton() {
  return viewButtons().some(
    el => (el.textContent || '').trim() === zhCN.rollbackCandidate.retry,
  );
}

/** 每一条真实数据行的动作容器（排除 el-table 的 hidden-columns 影子槽） */
function rowContainers(): HTMLElement[] {
  return Array.from(document.body.querySelectorAll<HTMLElement>('.el-table__row .row-action-buttons'));
}

/** 限定在某个真实数据行内，按可见文案点击动作 */
async function clickRowAction(index: number, label: string) {
  const container = rowContainers()[index];
  const button = Array.from(container?.querySelectorAll('button') ?? []).find(
    el => (el.textContent || '').trim() === label,
  ) as HTMLButtonElement | undefined;
  if (!button) throw new Error(`第 ${index + 1} 行没有「${label}」动作`);
  button.click();
  await flushPromises();
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
    rollbackOrderId: 10,
    operationType: 'ROLLBACK',
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

/** 明确支持的 SSH 回滚单，按 ID 生成多行夹具 */
function rollbackRow(id: number) {
  return { ...supportedCreatedRow(), id, rollbackNo: `RB-${id}` };
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
function summaryOf(orderId: number): Awaited<ReturnType<typeof getRollbackPrecheckSummary>> {
  return {
    orderId,
    orderType: 'ROLLBACK',
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
  successToast = vi.spyOn(ElMessage, 'success').mockImplementation(() => undefined as never);
  warningToast = vi.spyOn(ElMessage, 'warning').mockImplementation(() => undefined as never);
  errorToast = vi.spyOn(ElMessage, 'error').mockImplementation(() => undefined as never);
  consoleError = vi.spyOn(console, 'error').mockImplementation(() => undefined);

  mockedRollbackPage.mockResolvedValue({
    pageNum: 1,
    pageSize: 20,
    total: 1,
    records: [supportedCreatedRow()],
  } as never);
  mockedCandidates.mockResolvedValue({
    pageNum: 1,
    pageSize: 20,
    total: 1,
    records: [candidate],
  } as never);
  mockedUpdateTarget.mockReset();
  mockedExecuteRollback.mockReset();
  mockedPrecheckRollback.mockReset();
});

afterEach(() => {
  wrapper?.unmount();
  wrapper = undefined;
  document.body.innerHTML = '';
  vi.restoreAllMocks();
});

describe('RollbackOrdersView 回滚目标保存', () => {
  it('保存失败时保留所选候选并提供行内重试入口', async () => {
    mockedUpdateTarget.mockRejectedValue(new Error('backend rejected'));
    await mountView();
    await openCandidateDialog();

    await selectCandidate();

    expect(mockedUpdateTarget).toHaveBeenCalledWith(10, { toVersionId: 7 });
    expect(successToast).not.toHaveBeenCalled();
    expect(warningToast).not.toHaveBeenCalled();
    expect(hasRetryButton()).toBe(true);
  });

  it('保存成功后刷新失败：清除待保存候选，只提示已保存但展示刷新失败', async () => {
    mockedUpdateTarget.mockResolvedValue(true as never);
    await mountView();
    await openCandidateDialog();

    // 首次列表加载成功，保存后的刷新失败
    mockedRollbackPage.mockRejectedValue(new Error('refresh failed'));

    await selectCandidate();

    expect(successToast).toHaveBeenCalledWith(
      zhCN.rollbackCandidate.saved.replace('{versionNo}', 'v1.2.0'),
    );
    expect(warningToast).toHaveBeenCalledWith(
      zhCN.rollbackCandidate.savedRefreshFailed.replace('{versionNo}', 'v1.2.0'),
    );
    expect(hasRetryButton()).toBe(false);
    expect(errorToast).not.toHaveBeenCalled();
  });

  it('保存与刷新都成功：只提示保存成功，不出现刷新失败提示，也不出现重试入口', async () => {
    mockedUpdateTarget.mockResolvedValue(true as never);
    await mountView();
    await openCandidateDialog();

    await selectCandidate();

    expect(successToast).toHaveBeenCalledTimes(1);
    expect(warningToast).not.toHaveBeenCalled();
    expect(hasRetryButton()).toBe(false);
  });

  it('执行器类型无法确定时不开放候选入口，也不会发送保存请求', async () => {
    mockedUpdateTarget.mockResolvedValue(true as never);
    // 覆盖为“支持状态无法确定”的行：入口即 fail-closed，不调用任何回滚 API
    mockedRollbackPage.mockResolvedValue({
      pageNum: 1,
      pageSize: 20,
      total: 1,
      records: [{ ...supportedCreatedRow(), rollbackSupported: null, executorType: null }],
    } as never);
    await mountView();

    const buttons = viewButtons().map(
      el => (el.textContent || '').trim(),
    );
    expect(buttons).not.toContain('候选版本');
    expect(mockedUpdateTarget).not.toHaveBeenCalled();
  });
});

describe('RollbackOrdersView 回滚支持判据', () => {
  it('rollbackSupported=false 判为“不支持回滚”，原因按执行器类型给出，且不开放任何执行入口', async () => {
    await mountView([{ ...supportedCreatedRow(), rollbackSupported: false, executorType: 'SSH' }]);

    // 明确不支持与“无法确定”必须是两种不同结论
    expect(supportTag()?.text).toBe(zhCN.rollbackSupport.unsupportedTag);
    expect(supportTag()?.text).not.toBe(zhCN.rollbackSupport.unknownTag);
    expect(supportTag()?.type).toContain('el-tag--danger');
    expect(tooltipContents()).toContain(
      zhCN.rollbackSupport.unsupportedByType.replace('{type}', 'SSH'),
    );

    // 候选版本入口不开放，执行入口仅保留禁用占位
    expect(rowActionLabels()).not.toContain('候选版本');
    const placeholders = placeholderButtons();
    expect(placeholders.map(button => button.text)).toEqual(['前置检查', '执行回滚']);
    expect(placeholders.every(button => button.disabled)).toBe(true);

    // 未发生任何回滚调用
    expect(mockedUpdateTarget).not.toHaveBeenCalled();
    expect(mockedExecuteRollback).not.toHaveBeenCalled();
    expect(mockedPrecheckRollback).not.toHaveBeenCalled();
  });

  it('执行器类型不是 SSH 时同样判为“不支持回滚”，原因带上该类型', async () => {
    await mountView([{ ...supportedCreatedRow(), rollbackSupported: true, executorType: 'DOCKER' }]);

    expect(supportTag()?.text).toBe(zhCN.rollbackSupport.unsupportedTag);
    expect(tooltipContents()).toContain(
      zhCN.rollbackSupport.unsupportedByType.replace('{type}', 'DOCKER'),
    );
  });

  it('“无法确定支持状态”与“不支持回滚”是两种结论，原因文案也不同', async () => {
    await mountView([{ ...supportedCreatedRow(), rollbackSupported: null, executorType: 'SSH' }]);

    expect(supportTag()?.text).toBe(zhCN.rollbackSupport.unknownTag);
    expect(supportTag()?.type).toContain('el-tag--warning');
    expect(tooltipContents()).toContain(zhCN.rollbackSupport.unknownState);
    // 不得把“缺少明确结论”表达成“明确不支持”
    expect(tooltipContents()).not.toContain(zhCN.rollbackSupport.unsupportedTag);
    expect(tooltipContents()).not.toContain(
      zhCN.rollbackSupport.unsupportedByType.replace('{type}', 'SSH'),
    );
    expect(rowActionLabels()).not.toContain('候选版本');
    expect(mockedExecuteRollback).not.toHaveBeenCalled();
    expect(mockedPrecheckRollback).not.toHaveBeenCalled();
  });

  it('类型完全缺失时给出“无法确定执行器类型”的原因，仍不猜成 SSH', async () => {
    await mountView([{ ...supportedCreatedRow(), rollbackSupported: null, executorType: null }]);

    expect(supportTag()?.text).toBe(zhCN.rollbackSupport.unknownTag);
    expect(tooltipContents()).toContain(zhCN.rollbackSupport.unknownType);
    // 执行入口只以禁用占位存在，候选版本入口不开放
    const placeholders = placeholderButtons();
    expect(placeholders.map(button => button.text)).toEqual(['前置检查', '执行回滚']);
    expect(placeholders.every(button => button.disabled)).toBe(true);
    expect(rowActionLabels()).not.toContain('候选版本');
    expect(mockedExecuteRollback).not.toHaveBeenCalled();
    expect(mockedPrecheckRollback).not.toHaveBeenCalled();
  });

  it('后端给出明确不支持原因时以该原因为准，不回退到类型推断', async () => {
    await mountView([
      {
        ...supportedCreatedRow(),
        rollbackSupported: false,
        executorType: 'SSH',
        rollbackUnsupportedReason: '目标版本已不可回滚：制品已清理',
      },
    ]);

    expect(supportTag()?.text).toBe(zhCN.rollbackSupport.unsupportedTag);
    expect(tooltipContents()).toContain('目标版本已不可回滚：制品已清理');
  });
});

/**
 * 与发布单同一归属判据：部署记录读取通道持有独立请求序号与查看对象快照。
 * 切换查看对象或关闭弹窗后，旧请求的成功、失败与 finally 一律失去更新当前展示的资格；
 * 同一对象的读取失败保留已读到的证据，只显式标注本次读取失败；
 * 只读重试始终绑定当前对象且只走 GET。全部为组件级自测：网络被 mock，不连接真实后端，不产生写请求。
 */
describe('RollbackOrdersView 部署记录读取归属', () => {
  beforeEach(() => {
    mockedDeployRecords.mockReset();
    mockedRollbackDetail.mockReset();
  });

  it('A/B 乱序返回时，晚到的 A 不得覆盖 B 的记录、错误与加载态', async () => {
    const readA = deferred<Record<string, unknown>[]>();
    const readB = deferred<Record<string, unknown>[]>();
    mockedDeployRecords
      .mockImplementationOnce(() => readA.promise)
      .mockImplementationOnce(() => readB.promise);

    await mountView([rollbackRow(10), rollbackRow(11)]);

    await clickRowAction(0, zhCN.rollbackOrders.actionDeployRecords);
    await clickRowAction(1, zhCN.rollbackOrders.actionDeployRecords);
    // 每次读取都绑定当时查看的对象，不共用一次请求
    expect(mockedDeployRecords.mock.calls.map(call => call[0])).toEqual([10, 11]);

    // A 迟到成功：不得写进 B 的展示
    readA.resolve([deployRecord({ id: 501 })]);
    await flushPromises();
    expect(deployDialog().props('records')).toEqual([]);
    expect(deployDialog().props('error')).toBeNull();
    expect(deployDialog().props('loading')).toBe(true);

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

    await mountView([rollbackRow(10), rollbackRow(11)]);

    // 同一对象的读取失败：保留已读到的证据，只显式标注本次失败
    await clickRowAction(0, zhCN.rollbackOrders.actionDeployRecords);
    expect(deployDialog().props('records')).toEqual(evidenceA);
    await clickRowAction(0, zhCN.rollbackOrders.actionDeployRecords);
    expect(mockedDeployRecords.mock.calls.map(call => call[0])).toEqual([10, 10]);
    expect(deployDialog().props('records')).toEqual(evidenceA);
    expect(deployDialog().props('error')).toBe('A 的读取失败');

    // 切换对象：旧证据与旧失败标记立即消失
    await clickRowAction(1, zhCN.rollbackOrders.actionDeployRecords);
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

    await mountView([rollbackRow(10), rollbackRow(11)]);

    await clickRowAction(0, zhCN.rollbackOrders.actionDeployRecords);
    await closeOpenDialog();
    expect(deployDialog().props('modelValue')).toBe(false);
    lateSuccess.resolve([deployRecord({ id: 501 })]);
    await flushPromises();
    expect(deployDialog().props('records')).toEqual([]);
    expect(deployDialog().props('error')).toBeNull();
    expect(deployDialog().props('loading')).toBe(false);

    await clickRowAction(1, zhCN.rollbackOrders.actionDeployRecords);
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

    await mountView([rollbackRow(10), rollbackRow(11)]);

    await clickRowAction(0, zhCN.rollbackOrders.actionDeployRecords);
    await clickRowAction(1, zhCN.rollbackOrders.actionDeployRecords);

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
      deployRecord({ id: 77, deployBatch: 1, deployStatus: 'FAILED', deployLog: '回滚失败：端口占用' }),
    ];
    const refreshed = [...evidence, deployRecord({ id: 78, deployBatch: 1, deployStatus: 'SUCCESS' })];
    mockedDeployRecords
      .mockResolvedValueOnce(evidence)
      .mockRejectedValueOnce(new Error('部署记录读取超时'))
      .mockResolvedValueOnce(refreshed);
    mockedRollbackDetail.mockResolvedValue({ id: 10, rollbackNo: 'RB-10', status: 'SUCCESS' });

    await mountView([rollbackRow(10)]);

    // 从详情定位到具体记录
    await clickRowAction(0, zhCN.rollbackOrders.actionDetail);
    const drawer = wrapper!.findComponent(ReleaseDetailDrawer);
    drawer.vm.$emit('open-deploy-records-with-id', 77);
    await flushPromises();

    expect(mockedDeployRecords.mock.calls.map(call => call[0])).toEqual([10]);
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
    expect(mockedDeployRecords.mock.calls.map(call => call[0])).toEqual([10, 10, 10]);
    expect(deployDialog().props('records')).toEqual(refreshed);
    expect(deployDialog().props('error')).toBeNull();
    expect(deployDialog().props('loading')).toBe(false);
    expect(deployDialog().props('selectedRecordId')).toBe(77);
    expect(document.body.querySelectorAll('.deploy-target-row')).toHaveLength(1);
    expect(bodyText('.record-summary')).toContain('记录ID: 77');
    expect(mockedExecuteRollback).not.toHaveBeenCalled();
    expect(mockedPrecheckRollback).not.toHaveBeenCalled();
    expect(mockedUpdateTarget).not.toHaveBeenCalled();
  });
});

/**
 * 回滚单的预检摘要读取通道同样持有独立请求序号：摘要乱序返回时，
 * 晚到的旧对象摘要不得覆盖当前对象的摘要。
 */
describe('RollbackOrdersView 预检摘要读取归属', () => {
  beforeEach(() => {
    mockedPrecheckSummary.mockReset();
    mockedPrecheckRollback.mockReset();
  });

  it('摘要请求乱序返回时，晚到的 A 摘要不得覆盖 B 的摘要', async () => {
    const execA = deferred<Awaited<ReturnType<typeof precheckRollback>>>();
    const execB = deferred<Awaited<ReturnType<typeof precheckRollback>>>();
    const summaryA = deferred<Awaited<ReturnType<typeof getRollbackPrecheckSummary>>>();
    const summaryB = deferred<Awaited<ReturnType<typeof getRollbackPrecheckSummary>>>();
    mockedPrecheckRollback
      .mockImplementationOnce(() => execA.promise)
      .mockImplementationOnce(() => execB.promise);
    mockedPrecheckSummary
      .mockImplementationOnce(() => summaryA.promise)
      .mockImplementationOnce(() => summaryB.promise);

    await mountView([rollbackRow(10), rollbackRow(11)]);

    // A 的前置检查完成，摘要读取在途
    await clickRowAction(0, zhCN.rollbackOrders.actionPrecheck);
    execA.resolve([]);
    await flushPromises();
    expect(mockedPrecheckSummary.mock.calls.map(call => call[0])).toEqual([10]);

    // 切到 B：摘要被清空，A 的摘要随后迟到
    await clickRowAction(1, zhCN.rollbackOrders.actionPrecheck);
    expect(precheckDialog().props('precheckSummary')).toBeNull();

    summaryA.resolve(summaryOf(10));
    await flushPromises();
    expect(precheckDialog().props('precheckSummary')).toBeNull();

    // B 的摘要正常展示
    execB.resolve([]);
    await flushPromises();
    expect(mockedPrecheckSummary.mock.calls.map(call => call[0])).toEqual([10, 11]);
    summaryB.resolve(summaryOf(11));
    await flushPromises();
    expect(precheckDialog().props('precheckSummary')).toEqual(summaryOf(11));
  });

  /**
   * 刷新窗口回归：A 的前置检查已完成、列表刷新仍挂起时关闭并切到 B。
   * A 的旧流程必须就地结束——既不启动摘要读取，也不能作废 B 正在进行的摘要读取或改写 B 的加载态。
   */
  it('刷新挂起期间关闭并切到 B：A 不再启动摘要读取，B 的摘要与加载态不受影响', async () => {
    const execA = deferred<Awaited<ReturnType<typeof precheckRollback>>>();
    const execB = deferred<Awaited<ReturnType<typeof precheckRollback>>>();
    const refreshA = deferred<Awaited<ReturnType<typeof rollbackApi.page>>>();
    const summaryB = deferred<Awaited<ReturnType<typeof getRollbackPrecheckSummary>>>();
    mockedPrecheckRollback
      .mockImplementationOnce(() => execA.promise)
      .mockImplementationOnce(() => execB.promise);
    mockedPrecheckSummary.mockImplementationOnce(() => summaryB.promise);

    await mountView([rollbackRow(10), rollbackRow(11)]);
    // 下一次列表查询就是 A 的刷新：挂起它，让 A 停在刷新窗口里
    mockedRollbackPage.mockImplementationOnce(() => refreshA.promise);

    await clickRowAction(0, zhCN.rollbackOrders.actionPrecheck);
    execA.resolve([]);
    await flushPromises();
    expect(mockedPrecheckSummary).not.toHaveBeenCalled();

    // 刷新还没返回就关闭弹窗并切到 B
    await closeOpenDialog();
    await clickRowAction(1, zhCN.rollbackOrders.actionPrecheck);
    execB.resolve([]);
    await flushPromises();
    // B 的刷新已收口，摘要读取在途
    expect(mockedPrecheckSummary.mock.calls.map(call => call[0])).toEqual([11]);
    expect(precheckDialog().props('loading')).toBe(true);

    // 释放 A 的刷新：旧流程必须止步，不得作废 B 的摘要读取
    refreshA.resolve({
      pageNum: 1,
      pageSize: 20,
      total: 2,
      records: [rollbackRow(10), rollbackRow(11)],
    } as never);
    await flushPromises();
    expect(mockedPrecheckSummary.mock.calls.map(call => call[0])).toEqual([11]);
    expect(precheckDialog().props('loading')).toBe(true);

    // B 的摘要照常展示，加载态由 B 自己收口
    summaryB.resolve(summaryOf(11));
    await flushPromises();
    expect(precheckDialog().props('precheckSummary')).toEqual(summaryOf(11));
    expect(precheckDialog().props('loading')).toBe(false);
  });
});

/**
 * 本批文案必须走既有 zh/en 词条而不是写死中文；同时锁定正向回滚支持判据：
 * 只有 rollbackSupported 显式为 true 且执行器类型确定为 SSH 才开放执行入口。
 */
describe('RollbackOrdersView 文案国际化与正向支持判据', () => {
  it('明确支持的行（SSH + rollbackSupported=true）开放前置检查与执行回滚，不出现支持状态标签', async () => {
    await mountView([supportedCreatedRow()]);

    expect(supportTag()).toBeUndefined();
    const labels = rowActionLabels();
    expect(labels).toContain(zhCN.rollbackOrders.actionPrecheck);
    expect(labels).toContain(zhCN.rollbackOrders.actionExecute);
    expect(placeholderButtons()).toHaveLength(0);
  });

  it('执行器类型大小写不影响判据：小写 ssh 仍判为支持', async () => {
    await mountView([{ ...supportedCreatedRow(), executorType: 'ssh' }]);

    expect(supportTag()).toBeUndefined();
    expect(rowActionLabels()).toContain(zhCN.rollbackOrders.actionExecute);
    expect(placeholderButtons()).toHaveLength(0);
  });

  it('行内动作与支持状态文案随语言切换，列表只重发同样的只读查询、不触发写操作', async () => {
    await mountView([{ ...supportedCreatedRow(), rollbackSupported: null, executorType: null }]);

    expect(supportTag()?.text).toBe(zhCN.rollbackSupport.unknownTag);
    expect(rowActionLabels()).toContain(zhCN.rollbackOrders.actionDetail);

    setLocale('en-US');
    await flushPromises();

    expect(supportTag()?.text).toBe(enUS.rollbackSupport.unknownTag);
    expect(supportTag()?.text).not.toBe(zhCN.rollbackSupport.unknownTag);
    expect(tooltipContents()).toContain(enUS.rollbackSupport.unknownType);
    expect(tooltipContents()).not.toContain(zhCN.rollbackSupport.unknownType);
    expect(rowActionLabels()).toContain(enUS.rollbackOrders.actionDetail);
    expect(rowActionLabels()).not.toContain(zhCN.rollbackOrders.actionDetail);

    setLocale('zh-CN');
    await flushPromises();
    expect(supportTag()?.text).toBe(zhCN.rollbackSupport.unknownTag);
    expect(rowActionLabels()).toContain(zhCN.rollbackOrders.actionDetail);

    // 列表配置本身是当前语言的（列头 / 空态文案），切换语言会让 config 重算，
    // CrudPage 对 extraQuery 的既有行为因此各重发一次列表查询：
    // 查询条件必须与首次完全一致，且整条链路只有查询、没有任何写操作。
    const calls = mockedRollbackPage.mock.calls;
    expect(calls.length).toBeGreaterThanOrEqual(1);
    const firstQuery = JSON.stringify(calls[0][0]);
    for (const call of calls) {
      expect(JSON.stringify(call[0])).toBe(firstQuery);
    }
    expect(mockedExecuteRollback).not.toHaveBeenCalled();
    expect(mockedPrecheckRollback).not.toHaveBeenCalled();
    expect(mockedUpdateTarget).not.toHaveBeenCalled();
  });
});