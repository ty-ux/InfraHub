import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { nextTick } from 'vue';
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils';
import ElementPlus, { ElMessage } from 'element-plus';
import { createPinia, setActivePinia } from 'pinia';
import { createMemoryHistory, createRouter, type Router } from 'vue-router';
import { i18n, setLocale } from '@/i18n';
import zhCN from '@/i18n/locales/zh-CN';
import enUS from '@/i18n/locales/en-US';
import CurrentVersionsView from '../CurrentVersionsView.vue';
import DeployRecordsDialog from '@/components/release/DeployRecordsDialog.vue';
import { useAuthStore } from '@/store/auth';
import {
  getRuntimeServiceInstanceDeployConfig,
  getRuntimeServiceInstanceLogs,
  getRuntimeServiceInstanceStatus,
  listRuntimeServiceInstances,
  type RuntimeServiceInstance,
} from '@/api/runtime';
import { getLatestDeployRecords } from '@/api/release';

/**
 * 当前版本页：运行状态 / 运行日志 / 部署配置 / 最近部署记录 各成一条读取通道，
 * 每条通道固定"当前版本 ID + serviceId + envId + 选定实例 ID"，并在每个 await 之后重新确认归属。
 * 多实例必须先由用户选定目标再确认，确认之前不得发起任何远程诊断。
 * 全部为组件级自测：网络被 mock，不连接真实后端。
 */
const hoisted = vi.hoisted(() => ({ getPageConfig: vi.fn() }));

// 页面配置交给本用例自己构造：实例列表读取（enrichCurrentVersions）的覆盖在 page-configs.spec.ts 中，
// 这里只关心诊断通道自身的归属与请求参数，因此诊断相关的调用计数必须不受列表增强影响。
vi.mock('@/views/configs/page-configs', () => ({ getPageConfig: hoisted.getPageConfig }));

vi.mock('@/api/runtime', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/runtime')>();
  return {
    ...actual,
    listRuntimeServiceInstances: vi.fn(),
    getRuntimeServiceInstanceStatus: vi.fn(),
    getRuntimeServiceInstanceLogs: vi.fn(),
    getRuntimeServiceInstanceDeployConfig: vi.fn(),
  };
});

vi.mock('@/api/release', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/release')>();
  return { ...actual, getLatestDeployRecords: vi.fn() };
});

const mockedList = vi.mocked(listRuntimeServiceInstances);
const mockedStatus = vi.mocked(getRuntimeServiceInstanceStatus);
const mockedLogs = vi.mocked(getRuntimeServiceInstanceLogs);
const mockedDeployConfig = vi.mocked(getRuntimeServiceInstanceDeployConfig);
const mockedLatestRecords = vi.mocked(getLatestDeployRecords);
const listPage = vi.fn();

interface Deferred<T> {
  promise: Promise<T>;
  resolve: (value: T) => void;
  reject: (reason?: unknown) => void;
}

/** 手工控制完成时机的读取：用于构造延迟成功、延迟失败与目标切换后才返回的迟到响应 */
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

function currentVersionRow(overrides: Record<string, unknown> = {}) {
  return {
    id: 1,
    appId: 5,
    serviceId: 11,
    envId: 22,
    serviceDisplayName: '订单服务',
    envDisplayName: '生产',
    runtimeInstanceState: 'SINGLE',
    runtimeInstanceCount: 1,
    runtimeInstanceName: '订单服务-1',
    runtimeInstanceId: 900,
    ...overrides,
  };
}

function instance(overrides: Record<string, unknown> = {}): RuntimeServiceInstance {
  return {
    id: 900,
    instanceName: '订单服务-1',
    hostId: 7,
    versionId: 33,
    serviceId: 11,
    envId: 22,
    ...overrides,
  } as unknown as RuntimeServiceInstance;
}

function statusEvidence(overrides: Record<string, unknown> = {}) {
  return {
    instanceId: 900,
    serviceId: 11,
    envId: 22,
    versionId: 33,
    running: true,
    pid: 1234,
    portListening: true,
    healthStatus: 'UP',
    healthBody: '{"status":"UP"}',
    checkedAt: '2026-10-14 09:00:00',
    stdout: '',
    stderr: '',
    errorMessage: '',
    ...overrides,
  } as never;
}

function logsEvidence(overrides: Record<string, unknown> = {}) {
  return {
    instanceId: 900,
    serviceId: 11,
    envId: 22,
    versionId: 33,
    success: true,
    lines: 200,
    logPath: '/opt/app/logs/app.log',
    content: 'log-line-1\nlog-line-2',
    errorMessage: '',
    ...overrides,
  } as never;
}

function deployConfigEvidence(overrides: Record<string, unknown> = {}) {
  return {
    instanceId: 900,
    serviceId: 11,
    envId: 22,
    deployRoot: '/opt/app',
    deployPort: 8080,
    pidFile: '/opt/app/app.pid',
    logFile: '/opt/app/logs/app.log',
    healthUrl: 'http://127.0.0.1:8080/actuator/health',
    source: 'FILE',
    ...overrides,
  } as never;
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

/** 只读配置：提供 api.page，但不提供 create/update/remove，与真实只读页面配置一致 */
function pageConfig() {
  return {
    title: '当前版本',
    description: '按环境与服务查看当前绑定的版本',
    api: { page: listPage },
    columns: [
      { prop: 'serviceDisplayName', label: '服务', minWidth: 140 },
      { prop: 'envDisplayName', label: '环境', minWidth: 120 },
      { prop: 'runtimeInstanceState', label: '运行实例', minWidth: 200, slot: 'runtimeInstance' },
    ],
    createAuthority: ['RELEASE_CREATE', 'SYSTEM_ADMIN'],
    editAuthority: ['RELEASE_EDIT', 'SYSTEM_ADMIN'],
    deleteAuthority: ['SYSTEM_ADMIN'],
  };
}

async function mountView(records: Record<string, unknown>[] = [currentVersionRow()]) {
  setLocale('zh-CN');
  const pinia = createPinia();
  setActivePinia(pinia);
  router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', component: { template: '<div />' } },
      { path: '/release/orders', component: { template: '<div />' } },
      { path: '/resource/runtime-instances', component: { template: '<div />' } },
    ],
  });
  await router.push('/');
  await router.isReady();

  const authStore = useAuthStore();
  authStore.currentUser = { id: 1, username: 'tester', authorities: ['RELEASE_VIEW'] } as never;

  hoisted.getPageConfig.mockReturnValue(pageConfig());
  listPage.mockResolvedValue({ pageNum: 1, pageSize: 10, total: records.length, records });

  wrapper = mount(CurrentVersionsView, {
    global: { plugins: [ElementPlus, i18n, pinia, router] },
    attachTo: document.body,
  });
  await flushPromises();
}

function bodyRows(): HTMLElement[] {
  return Array.from(document.body.querySelectorAll<HTMLElement>('.el-table__body-wrapper .el-table__row'));
}

function cellText(rowIndex: number): string {
  const cell = bodyRows()[rowIndex]?.querySelectorAll('.el-table__cell')[0];
  return (cell?.textContent || '').replace(/\s+/g, ' ').trim();
}

function rowActionButtons(rowIndex: number): HTMLButtonElement[] {
  const container = bodyRows()[rowIndex]?.querySelector('.row-action-buttons');
  return Array.from(container?.querySelectorAll<HTMLButtonElement>('button') ?? []);
}

function buttonWithLabel(container: ParentNode, label: string): HTMLButtonElement | undefined {
  return Array.from(container.querySelectorAll<HTMLButtonElement>('button')).find(
    button => (button.textContent || '').trim() === label,
  );
}

async function clickRowButton(rowIndex: number, label: string) {
  const button = buttonWithLabel(bodyRows()[rowIndex] ?? document.body, label);
  if (!button) throw new Error(`第 ${rowIndex + 1} 行没有「${label}」按钮`);
  button.click();
  await flushPromises();
}

/**
 * 行内「更多」的真实触发器按钮（真实 <button>：可聚焦、可点击、可键盘展开）。
 * el-table 会额外渲染一份 .hidden-columns 影子节点，同一份行内「更多」在组件树与 DOM 里各有两份，
 * 影子节点先创建；这里按可见表格行（.el-table__body-wrapper 下的行）定位，不按 DOM 顺序取第一个。
 */
function moreTrigger(rowIndex: number): HTMLButtonElement {
  const trigger = bodyRows()[rowIndex]?.querySelector<HTMLButtonElement>('button.row-more-trigger');
  if (!trigger) throw new Error(`第 ${rowIndex + 1} 行没有「更多」入口按钮`);
  return trigger;
}

/**
 * 「更多」菜单元素：由触发器自身的 aria-controls 指向。
 * 该属性只有在下拉确实把弹层触发器绑定到这个真实按钮上时才会写入，
 * 它同时也是"点击/键盘真的能展开"的结构性证据；菜单项被 teleport 到 body，
 * 弹层默认常驻（persistent），未展开时只是 display:none。
 */
function moreMenuElement(rowIndex: number): HTMLElement {
  const trigger = moreTrigger(rowIndex);
  const controls = trigger.getAttribute('aria-controls');
  if (!controls) throw new Error(`第 ${rowIndex + 1} 行的「更多」按钮没有可展开的菜单（缺少 aria-controls）`);
  const menu = document.getElementById(controls);
  if (!menu) throw new Error(`第 ${rowIndex + 1} 行的「更多」菜单元素未找到`);
  return menu;
}

/** 菜单是否真的可见：自身与所有祖先都不是 display:none，弹层也没有 aria-hidden="true" */
function isMenuVisible(menu: HTMLElement): boolean {
  let node: HTMLElement | null = menu;
  while (node) {
    if (node.style.display === 'none' || node.getAttribute('aria-hidden') === 'true') return false;
    node = node.parentElement;
  }
  return true;
}

function menuItems(menu: HTMLElement): HTMLElement[] {
  return Array.from(menu.querySelectorAll<HTMLElement>('.el-dropdown-menu__item'));
}

/** 已展开菜单里的可见项文案（未展开直接报错，避免把隐藏项当成可达入口） */
function visibleMoreMenuLabels(rowIndex: number): string[] {
  const menu = moreMenuElement(rowIndex);
  if (!isMenuVisible(menu)) throw new Error(`第 ${rowIndex + 1} 行的「更多」菜单没有展开`);
  return menuItems(menu).map(item => (item.textContent || '').trim());
}

/**
 * 等待下拉真实展开/收起。
 * Element Plus 的展开与收起都经过 useDelayedToggle 的 window.setTimeout(…, 0)，
 * 而 flushPromises() 走的是 setImmediate，会先于该定时器返回；
 * 因此必须再等一个真实的宏任务，展开状态才会落到 DOM 上（不是用程序打开菜单）。
 */
async function flushToggleTimers() {
  await flushPromises();
  await new Promise(resolve => setTimeout(resolve, 10));
  await nextTick();
}

/**
 * 用真实点击展开某一行「更多」：先确认菜单确实展开（aria-expanded + 可见性），再返回菜单元素。
 * 展开前菜单是隐藏的（persistent 弹层 display:none），因此这里不会点到隐藏项。
 */
async function openMoreMenu(rowIndex: number): Promise<HTMLElement> {
  const trigger = moreTrigger(rowIndex);
  expect(trigger.getAttribute('aria-expanded')).toBe('false');
  trigger.click();
  await flushToggleTimers();
  expect(trigger.getAttribute('aria-expanded')).toBe('true');
  const menu = moreMenuElement(rowIndex);
  expect(isMenuVisible(menu)).toBe(true);
  return menu;
}

/** 展开某一行「更多」并点击其中可见的菜单项（与用户点击走同一条链路） */
async function openMoreItem(rowIndex: number, label: string) {
  const menu = await openMoreMenu(rowIndex);
  const item = menuItems(menu).find(node => (node.textContent || '').trim() === label);
  if (!item) throw new Error(`「更多」菜单没有「${label}」`);
  item.click();
  // 选中一项后下拉收起同样由定时器驱动，等它真的收起来，下一次展开才是从收起态开始
  await flushToggleTimers();
}

function dialogByTitle(title: string): HTMLElement {
  const dialogs = Array.from(document.body.querySelectorAll<HTMLElement>('.el-dialog'));
  const found = dialogs.find(dialog => (dialog.querySelector('.el-dialog__title')?.textContent || '').trim() === title);
  if (!found) throw new Error(`没有标题为「${title}」的弹窗`);
  return found;
}

function dialogText(dialog: HTMLElement): string {
  return (dialog.textContent || '').replace(/\s+/g, ' ').trim();
}

/**
 * 弹窗里各条 el-alert 的标题文本。
 * 判断"这次读取算不算失败"要看告警标题本身：附属配置的提示（statusConfigFailedHint）里也含
 * "读取失败"四个字，对整段文本做包含判断会把提示误当成失败状态。
 */
function alertTitles(dialog: HTMLElement): string[] {
  return Array.from(dialog.querySelectorAll<HTMLElement>('.el-alert__title'))
    .map(node => (node.textContent || '').trim());
}

/**
 * 状态摘要里的端口单元格文本。
 * 标签与取值是相邻的独立 span（拼接整段文本会因无空格而误判），因此按单元格本身取值。
 */
function summaryPortText(dialog: HTMLElement): string {
  const port = dialog.querySelector<HTMLElement>('.summary-port');
  if (!port) throw new Error('状态摘要里没有端口单元格');
  return (port.textContent || '').trim();
}

/**
 * 关闭弹窗。
 * happy-dom 下 Element Plus 弹窗的三条用户关闭路径都不会真正跑起来：标题栏 ×、Esc、遮罩点击之后
 * 弹窗的 modelValue 仍停在 true（手动绑在同一个按钮上的原生监听器会触发，EP 内部的 close 处理不会执行）。
 * 因此这里改为走弹窗自身的 v-model 契约触发关闭，被测的仍是页面侧对可见性变化的处理
 * （statusVisible 的 flush:'sync' 监听、关闭后在途请求失效），不代替页面做任何状态清理。
 */
async function closeDialog(title: string) {
  const dialog = wrapper?.findAllComponents({ name: 'ElDialog' })
    .find(item => item.props('title') === title);
  if (!dialog) throw new Error(`没有标题为「${title}」的弹窗组件`);
  dialog.vm.$emit('update:modelValue', false);
  await flushPromises();
}

function instanceSelect(dialog: HTMLElement) {
  const selectElement = dialog.querySelector<HTMLElement>('.target-select-input');
  if (!selectElement) throw new Error('该弹窗没有实例选择器');
  const select = wrapper?.findAllComponents({ name: 'ElSelect' }).find(item => item.element === selectElement);
  if (!select) throw new Error('未找到实例选择器组件');
  return select;
}

/** 选择目标实例等价于用户在界面上选值：先更新 v-model，再触发 change（切换目标立即清空旧证据） */
async function selectInstance(dialog: HTMLElement, value: number) {
  const select = instanceSelect(dialog);
  select.vm.$emit('update:modelValue', value);
  await nextTick();
  select.vm.$emit('change', value);
  await flushPromises();
}

async function clickDialogButton(dialog: HTMLElement, label: string) {
  const button = buttonWithLabel(dialog, label);
  if (!button) throw new Error(`弹窗没有「${label}」按钮`);
  button.click();
  await flushPromises();
}

function deployDialog() {
  const dialog = wrapper?.findComponent(DeployRecordsDialog);
  if (!dialog || !dialog.exists()) throw new Error('部署记录弹窗未挂载');
  return dialog;
}

beforeEach(() => {
  vi.spyOn(console, 'error').mockImplementation(() => undefined);
  vi.spyOn(console, 'warn').mockImplementation(() => undefined);
  hoisted.getPageConfig.mockReset();
  listPage.mockReset();
  mockedList.mockReset();
  mockedStatus.mockReset();
  mockedLogs.mockReset();
  mockedDeployConfig.mockReset();
  mockedLatestRecords.mockReset();
  writeText.mockClear();
  Object.defineProperty(navigator, 'clipboard', { value: { writeText }, configurable: true });
  mockedList.mockResolvedValue([instance()]);
  mockedStatus.mockResolvedValue(statusEvidence());
  mockedLogs.mockResolvedValue(logsEvidence());
  mockedDeployConfig.mockResolvedValue(deployConfigEvidence());
});

afterEach(() => {
  wrapper?.unmount();
  wrapper = undefined;
  document.body.innerHTML = '';
  setLocale('zh-CN');
  vi.restoreAllMocks();
});

describe('CurrentVersionsView 入口分层与导航', () => {
  it('行内保留一个主诊断入口，其余配置与导航入口收进「更多」，能力不缺失', async () => {
    await mountView();

    const buttons = rowActionButtons(0);
    expect(buttons.map(button => (button.textContent || '').trim())).toEqual([
      zhCN.currentVersions.actionStatus,
      zhCN.currentVersions.actionLogs,
      zhCN.currentVersions.actionMore,
    ]);
    // 主操作唯一
    expect(bodyRows()[0].querySelectorAll('.is-row-primary-action')).toHaveLength(1);
    expect(buttons.map(button => (button.textContent || '').trim()))
      .not.toContain(zhCN.common.edit);
    expect(buttons.map(button => (button.textContent || '').trim()))
      .not.toContain(zhCN.common.delete);

    // 四个次要入口都真实可达：真实点击展开后菜单项可见才逐项比对
    await openMoreMenu(0);
    expect(visibleMoreMenuLabels(0)).toEqual([
      zhCN.currentVersions.actionDeployConfig,
      zhCN.currentVersions.actionLatestDeployRecords,
      zhCN.currentVersions.actionReleaseOrder,
      zhCN.currentVersions.actionRuntimeInstance,
    ]);
  });

  it('「更多」可以用键盘展开：Enter 之后菜单项可见，选择仍走可见菜单项', async () => {
    await mountView();
    const push = vi.spyOn(router, 'push');
    const trigger = moreTrigger(0);
    expect(trigger.getAttribute('aria-expanded')).toBe('false');

    trigger.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', code: 'Enter' }));
    await flushToggleTimers();

    expect(trigger.getAttribute('aria-expanded')).toBe('true');
    expect(visibleMoreMenuLabels(0)).toContain(zhCN.currentVersions.actionReleaseOrder);
    const item = menuItems(moreMenuElement(0))
      .find(node => (node.textContent || '').trim() === zhCN.currentVersions.actionReleaseOrder);
    expect(item).toBeDefined();
    item!.click();
    await flushPromises();

    expect(push).toHaveBeenCalledWith({
      path: '/release/orders',
      query: { appId: '5', envId: '22', serviceId: '11' },
    });
  });

  it('「更多」中的查看发布单与查看运行实例只做导航，并携带当前实际支持的筛选参数', async () => {
    await mountView();
    const push = vi.spyOn(router, 'push');

    await openMoreItem(0, zhCN.currentVersions.actionReleaseOrder);
    expect(push).toHaveBeenCalledWith({
      path: '/release/orders',
      query: { appId: '5', envId: '22', serviceId: '11' },
    });

    push.mockClear();
    await openMoreItem(0, zhCN.currentVersions.actionRuntimeInstance);
    expect(push).toHaveBeenCalledWith({
      path: '/resource/runtime-instances',
      query: { serviceId: '11', envId: '22' },
    });
  });

  it('「更多」中的部署配置仍然可以打开，并按当前版本的服务与环境确定目标', async () => {
    mockedList.mockResolvedValue([instance()]);
    await mountView();

    await openMoreItem(0, zhCN.currentVersions.actionDeployConfig);

    expect(dialogByTitle(zhCN.currentVersions.deployConfigTitle)).toBeDefined();
    expect(mockedList).toHaveBeenCalledWith({ serviceId: 11, envId: 22 });
    expect(mockedDeployConfig).toHaveBeenCalledWith(900);
  });
});

describe('CurrentVersionsView 最近部署记录', () => {
  it('读取失败保留已读到的记录并显式标注，只读重试仍绑定同一个当前版本', async () => {
    const first = [deployRecord({ id: 501 })];
    mockedLatestRecords
      .mockResolvedValueOnce(first as never)
      .mockRejectedValueOnce(new Error('部署记录读取超时'))
      .mockResolvedValueOnce([...first, deployRecord({ id: 502 })] as never);
    await mountView();

    await openMoreItem(0, zhCN.currentVersions.actionLatestDeployRecords);
    expect(mockedLatestRecords.mock.calls.map(call => call[0])).toEqual([1]);
    expect(deployDialog().props('records')).toEqual(first);

    await openMoreItem(0, zhCN.currentVersions.actionLatestDeployRecords);
    expect(deployDialog().props('records')).toEqual(first);
    expect(deployDialog().props('error')).toBe('部署记录读取超时');
    expect(deployDialog().props('loading')).toBe(false);
    // 读取失败不得被展示成"没有记录"
    expect(deployDialog().props('records')).not.toEqual([]);

    const retry = document.body.querySelector<HTMLButtonElement>('.read-error-block button');
    expect(retry).not.toBeNull();
    retry!.click();
    await flushPromises();

    expect(mockedLatestRecords.mock.calls.map(call => call[0])).toEqual([1, 1, 1]);
    expect(deployDialog().props('error')).toBeNull();
    expect(deployDialog().props('records')).toHaveLength(2);
  });

  it('当前版本缺少 ID 时明确提示，且不发起任何读取', async () => {
    const warning = vi.spyOn(ElMessage, 'warning').mockImplementation(() => ({}) as never);
    await mountView([currentVersionRow({ id: null })]);

    await openMoreItem(0, zhCN.currentVersions.actionLatestDeployRecords);

    expect(warning).toHaveBeenCalledWith(zhCN.currentVersions.recordsMissingId);
    expect(mockedLatestRecords).not.toHaveBeenCalled();
    expect(deployDialog().props('modelValue')).toBe(false);
  });
});

describe('CurrentVersionsView 实例目标确定', () => {
  it('无实例时说明没有可诊断实例，且不发起任何远程诊断', async () => {
    mockedList.mockResolvedValue([]);
    await mountView();

    await clickRowButton(0, zhCN.currentVersions.actionStatus);

    const dialog = dialogByTitle(zhCN.currentVersions.statusTitle);
    expect(dialogText(dialog)).toContain(zhCN.currentVersions.instanceNoneTitle);
    expect(dialogText(dialog)).toContain(zhCN.currentVersions.instanceNoneHint);
    expect(dialogText(dialog)).not.toContain(zhCN.currentVersions.instanceListFailed);
    expect(mockedStatus).not.toHaveBeenCalled();
    expect(mockedLogs).not.toHaveBeenCalled();
    expect(mockedDeployConfig).not.toHaveBeenCalled();
  });

  it('单实例沿用点击后直接读取，目标头展示业务名称、实例 ID、主机 ID 与实例版本', async () => {
    await mountView();

    await clickRowButton(0, zhCN.currentVersions.actionStatus);

    expect(mockedList).toHaveBeenCalledTimes(1);
    expect(mockedList).toHaveBeenCalledWith({ serviceId: 11, envId: 22 });
    expect(mockedStatus.mock.calls.map(call => call[0])).toEqual([900]);
    expect(mockedLogs).not.toHaveBeenCalled();

    const dialog = dialogByTitle(zhCN.currentVersions.statusTitle);
    const text = dialogText(dialog);
    expect(text).toContain(`${zhCN.currentVersions.dialogCurrentVersionId}: 1`);
    expect(text).toContain(`${zhCN.currentVersions.dialogService}: 订单服务`);
    expect(text).toContain(`${zhCN.currentVersions.dialogEnv}: 生产`);
    expect(text).toContain(`${zhCN.currentVersions.dialogInstanceName}: 订单服务-1`);
    expect(text).toContain(`${zhCN.currentVersions.dialogInstanceId}: 900`);
    expect(text).toContain(`${zhCN.currentVersions.dialogHostId}: 7`);
    expect(text).toContain(`${zhCN.currentVersions.dialogInstanceVersion}: 33`);
    // 实例选择器只在多实例时出现
    expect(dialog.querySelector('.target-select')).toBeNull();
  });

  it('当前版本缺少服务或环境信息时不推断诊断目标，连实例列表都不读', async () => {
    await mountView([currentVersionRow({ serviceId: 0 })]);

    await clickRowButton(0, zhCN.currentVersions.actionStatus);

    const dialog = dialogByTitle(zhCN.currentVersions.statusTitle);
    expect(dialogText(dialog)).toContain(zhCN.currentVersions.instanceUnknownTitle);
    expect(dialogText(dialog)).toContain(zhCN.currentVersions.instanceUnknownHint);
    expect(mockedList).not.toHaveBeenCalled();
    expect(mockedStatus).not.toHaveBeenCalled();
  });

  it('实例列表读取失败与"没有实例"分开表达，并保留后端原始错误', async () => {
    mockedList.mockRejectedValue(new Error('运行实例列表读取超时'));
    await mountView();

    await clickRowButton(0, zhCN.currentVersions.actionStatus);

    const dialog = dialogByTitle(zhCN.currentVersions.statusTitle);
    const text = dialogText(dialog);
    expect(text).toContain(zhCN.currentVersions.instanceListFailed);
    expect(text).toContain(zhCN.currentVersions.instanceListFailedHint);
    expect(text).not.toContain(zhCN.currentVersions.instanceNoneTitle);
    expect(dialog.querySelector('.raw-error-text')?.textContent).toContain('运行实例列表读取超时');
    expect(mockedStatus).not.toHaveBeenCalled();
    expect(mockedLogs).not.toHaveBeenCalled();
  });

  it('多实例必须先选定目标再确认：确认之前不发起任何远程诊断', async () => {
    mockedList.mockResolvedValue([instance({ id: 900 }), instance({ id: 901, instanceName: '订单服务-2' })]);
    await mountView();

    await clickRowButton(0, zhCN.currentVersions.actionStatus);

    const dialog = dialogByTitle(zhCN.currentVersions.statusTitle);
    expect(dialogText(dialog)).toContain(zhCN.currentVersions.selectInstanceTitle.replace('{count}', '2'));
    expect(dialog.querySelector('.target-select')).not.toBeNull();
    // 只有实例列表这一条只读 GET，没有任何远程诊断
    expect(mockedList).toHaveBeenCalledTimes(1);
    expect(mockedStatus).not.toHaveBeenCalled();
    expect(mockedLogs).not.toHaveBeenCalled();
    expect(mockedDeployConfig).not.toHaveBeenCalled();
  });

  it('多实例未选定目标就点确认时给出明确提示，且仍然不发起远程诊断', async () => {
    mockedList.mockResolvedValue([instance({ id: 900 }), instance({ id: 901 })]);
    const warning = vi.spyOn(ElMessage, 'warning').mockImplementation(() => ({}) as never);
    await mountView();

    await clickRowButton(0, zhCN.currentVersions.actionStatus);
    await clickDialogButton(dialogByTitle(zhCN.currentVersions.statusTitle), zhCN.currentVersions.confirmRead);

    expect(warning).toHaveBeenCalledWith(zhCN.currentVersions.selectInstanceRequired);
    expect(mockedStatus).not.toHaveBeenCalled();
    expect(mockedDeployConfig).not.toHaveBeenCalled();
  });

  it('多实例选定后只读取所选实例，目标头归属该实例', async () => {
    const second = instance({ id: 901, instanceName: '订单服务-2', hostId: 8, versionId: 44 });
    mockedList.mockResolvedValue([instance({ id: 900 }), second]);
    mockedStatus.mockResolvedValue(statusEvidence({ instanceId: 901, versionId: 44 }));
    mockedDeployConfig.mockResolvedValue(deployConfigEvidence({ instanceId: 901 }));
    await mountView();

    await clickRowButton(0, zhCN.currentVersions.actionStatus);
    const dialog = dialogByTitle(zhCN.currentVersions.statusTitle);
    await selectInstance(dialog, 901);
    await clickDialogButton(dialog, zhCN.currentVersions.confirmRead);

    // 请求参数严格来自所选实例，不取数组首个元素
    expect(mockedStatus.mock.calls.map(call => call[0])).toEqual([901]);
    expect(mockedDeployConfig.mock.calls.map(call => call[0])).toEqual([901]);
    const text = dialogText(dialog);
    expect(text).toContain(`${zhCN.currentVersions.dialogInstanceName}: 订单服务-2`);
    expect(text).toContain(`${zhCN.currentVersions.dialogInstanceId}: 901`);
    expect(text).toContain(`${zhCN.currentVersions.dialogHostId}: 8`);
  });

  it('多实例选项文案包含实例名称、运行实例 ID、主机 ID 与实例版本', async () => {
    mockedList.mockResolvedValue([instance({ id: 900 }), instance({ id: 901, instanceName: '订单服务-2', hostId: 8, versionId: 44 })]);
    await mountView();

    await clickRowButton(0, zhCN.currentVersions.actionStatus);
    const dialog = dialogByTitle(zhCN.currentVersions.statusTitle);
    const selectWrapper = dialog.querySelector<HTMLElement>('.el-select__wrapper');
    expect(selectWrapper).not.toBeNull();
    selectWrapper!.click();
    await flushPromises();

    // 同一页面上还有表格分页等其它 el-select 的下拉层，只取实例选择器自己的那一份
    const dropdowns = Array.from(document.body.querySelectorAll<HTMLElement>('.el-select-dropdown'))
      .filter(node => (node.textContent || '').includes('订单服务-1'));
    expect(dropdowns).toHaveLength(1);
    const optionTexts = Array.from(dropdowns[0].querySelectorAll<HTMLElement>('.el-select-dropdown__item'))
      .map(node => (node.textContent || '').replace(/\s+/g, ' ').trim());
    expect(optionTexts).toEqual([
      `订单服务-1 · ${zhCN.currentVersions.dialogInstanceId} 900 · ${zhCN.currentVersions.dialogHostId} 7 · ${zhCN.currentVersions.dialogInstanceVersion} 33`,
      `订单服务-2 · ${zhCN.currentVersions.dialogInstanceId} 901 · ${zhCN.currentVersions.dialogHostId} 8 · ${zhCN.currentVersions.dialogInstanceVersion} 44`,
    ]);
  });

  it('实例列表作用域不符时判为读取失败，不得静默过滤后宣称没有实例', async () => {
    mockedList.mockResolvedValue([instance({ id: 900, serviceId: 999 }), instance({ id: 901, envId: 999 })]);
    await mountView();

    await clickRowButton(0, zhCN.currentVersions.actionStatus);

    const dialog = dialogByTitle(zhCN.currentVersions.statusTitle);
    const text = dialogText(dialog);
    expect(text).toContain(zhCN.currentVersions.instanceListScopeFailed);
    expect(text).toContain(zhCN.currentVersions.instanceListScopeFailedHint);
    expect(text).not.toContain(zhCN.currentVersions.instanceNoneTitle);
    expect(text).not.toContain(zhCN.currentVersions.instanceNoneHint);
    // 作用域错误不发起任何远程诊断
    expect(mockedStatus).not.toHaveBeenCalled();
    expect(mockedLogs).not.toHaveBeenCalled();
    expect(mockedDeployConfig).not.toHaveBeenCalled();
  });

  it('部分条目作用域不符也算作用域错误：不静默过滤，也不把外来身份带进列表', async () => {
    mockedList.mockResolvedValue([instance({ id: 5000, serviceId: 999 }), instance({ id: 900 })]);
    await mountView();

    await clickRowButton(0, zhCN.currentVersions.actionStatus);

    const dialog = dialogByTitle(zhCN.currentVersions.statusTitle);
    const text = dialogText(dialog);
    expect(text).toContain(zhCN.currentVersions.instanceListScopeFailed);
    expect(text).not.toContain(zhCN.currentVersions.instanceNoneTitle);
    // 既不做单实例直读，也不出现实例选择器与外来实例 ID
    expect(text).not.toContain(`${zhCN.currentVersions.dialogInstanceId}: 5000`);
    expect(dialog.querySelector('.target-select')).toBeNull();
    expect(mockedStatus).not.toHaveBeenCalled();
    expect(mockedLogs).not.toHaveBeenCalled();
  });
});

describe('CurrentVersionsView 证据归属与在途失效', () => {
  it('延迟返回时先显示加载态，返回后才显示证据与证据获取时间', async () => {
    const read = deferred<never>();
    mockedStatus.mockImplementation(() => read.promise);
    await mountView();

    await clickRowButton(0, zhCN.currentVersions.actionStatus);
    const dialog = dialogByTitle(zhCN.currentVersions.statusTitle);
    expect(dialog.querySelector('.el-skeleton')).not.toBeNull();
    expect(dialogText(dialog)).toContain(zhCN.currentVersions.dialogNotRetrieved);

    read.resolve(statusEvidence());
    await flushPromises();

    expect(dialog.querySelector('.el-skeleton')).toBeNull();
    expect(dialog.querySelector('.quick-summary')).not.toBeNull();
    expect(dialogText(dialog)).toContain(zhCN.currentVersions.statusRunning);
    expect(dialogText(dialog)).not.toContain(zhCN.currentVersions.dialogNotRetrieved);
  });

  it('关闭弹窗后迟到的成功不得写回证据，也不得影响加载状态', async () => {
    const read = deferred<never>();
    mockedStatus.mockImplementation(() => read.promise);
    await mountView();

    await clickRowButton(0, zhCN.currentVersions.actionStatus);
    const dialog = dialogByTitle(zhCN.currentVersions.statusTitle);
    expect(dialog.querySelector('.el-dialog__headerbtn')).not.toBeNull();
    await closeDialog(zhCN.currentVersions.statusTitle);

    read.resolve(statusEvidence());
    await flushPromises();

    expect(dialog.querySelector('.quick-summary')).toBeNull();
    expect(dialog.querySelector('.el-skeleton')).toBeNull();
    expect(mockedStatus).toHaveBeenCalledTimes(1);
  });

  it('关闭弹窗后迟到的失败不得把读取失败写到已经关闭的弹窗上', async () => {
    const read = deferred<never>();
    mockedStatus.mockImplementation(() => read.promise);
    await mountView();

    await clickRowButton(0, zhCN.currentVersions.actionStatus);
    const dialog = dialogByTitle(zhCN.currentVersions.statusTitle);
    await closeDialog(zhCN.currentVersions.statusTitle);

    read.reject(new Error('诊断命令执行超时'));
    await flushPromises();

    expect(dialogText(dialog)).not.toContain(zhCN.currentVersions.readFailed);
    expect(dialog.querySelector('.raw-error-text')).toBeNull();
  });

  it('切换到另一个当前版本后，旧请求的迟到成功不得覆盖新目标的证据', async () => {
    const oldRead = deferred<never>();
    mockedStatus.mockImplementationOnce(() => oldRead.promise);
    mockedList.mockResolvedValue([instance({ id: 900 })]);
    await mountView([
      currentVersionRow({ id: 1 }),
      currentVersionRow({ id: 2, serviceDisplayName: '支付服务', serviceId: 12 }),
    ]);

    await clickRowButton(0, zhCN.currentVersions.actionStatus);

    // 切换到第二个当前版本：新目标立即重读，旧证据与旧请求全部作废
    mockedList.mockResolvedValue([instance({ id: 950, instanceName: '支付服务-1', serviceId: 12, hostId: 9, versionId: 55 })]);
    mockedStatus.mockResolvedValue(statusEvidence({ instanceId: 950, serviceId: 12, versionId: 55 }));
    mockedDeployConfig.mockResolvedValue(deployConfigEvidence({ instanceId: 950, serviceId: 12 }));
    await clickRowButton(1, zhCN.currentVersions.actionStatus);
    await flushPromises();

    // 900 的那次读取在切换目标时仍在途，不做取消；能写回证据的只有新目标的 950
    expect(mockedStatus.mock.calls.map(call => call[0])).toEqual([900, 950]);

    oldRead.resolve(statusEvidence({ instanceId: 900 }));
    await flushPromises();

    const dialog = dialogByTitle(zhCN.currentVersions.statusTitle);
    const text = dialogText(dialog);
    expect(text).toContain(`${zhCN.currentVersions.dialogCurrentVersionId}: 2`);
    expect(text).toContain(`${zhCN.currentVersions.dialogInstanceId}: 950`);
    expect(text).not.toContain(`${zhCN.currentVersions.dialogInstanceId}: 900`);
    expect(dialog.querySelector('.raw-error-text')).toBeNull();
  });

  it('多实例下切换所选实例时立即清空旧证据，旧证据不会挂到新实例上', async () => {
    mockedList.mockResolvedValue([instance({ id: 900 }), instance({ id: 901, instanceName: '订单服务-2' })]);
    await mountView();

    await clickRowButton(0, zhCN.currentVersions.actionStatus);
    const dialog = dialogByTitle(zhCN.currentVersions.statusTitle);
    await selectInstance(dialog, 900);
    await clickDialogButton(dialog, zhCN.currentVersions.confirmRead);
    expect(dialog.querySelector('.quick-summary')).not.toBeNull();

    await selectInstance(dialog, 901);

    // 切换后目标回到"待确认"：目标头、证据与证据时间都不再挂在新旧任一实例上
    expect(dialog.querySelector('.quick-summary')).toBeNull();
    expect(dialog.querySelector('.target-header')).toBeNull();
    expect(dialogText(dialog)).not.toContain(zhCN.currentVersions.statusRunning);
    expect(dialogText(dialog)).toContain(zhCN.currentVersions.selectInstanceTitle.replace('{count}', '2'));
    // 切换目标本身不是一次读取
    expect(mockedStatus.mock.calls.map(call => call[0])).toEqual([900]);
  });

  it('运行状态与运行日志各自持有目标实例：日志刷新只使用日志弹窗自己的实例', async () => {
    mockedList.mockResolvedValue([instance({ id: 900 }), instance({ id: 901, instanceName: '订单服务-2' })]);
    mockedStatus.mockResolvedValue(statusEvidence({ instanceId: 900 }));
    mockedLogs.mockResolvedValue(logsEvidence({ instanceId: 901 }));
    await mountView();

    await clickRowButton(0, zhCN.currentVersions.actionStatus);
    const statusDialog = dialogByTitle(zhCN.currentVersions.statusTitle);
    await selectInstance(statusDialog, 900);
    await clickDialogButton(statusDialog, zhCN.currentVersions.confirmRead);

    await clickRowButton(0, zhCN.currentVersions.actionLogs);
    const logsDialog = dialogByTitle(zhCN.currentVersions.logsTitle);
    await selectInstance(logsDialog, 901);
    await clickDialogButton(logsDialog, zhCN.currentVersions.confirmRead);

    await clickDialogButton(logsDialog, zhCN.currentVersions.logsRefresh);

    expect(mockedLogs.mock.calls.map(call => call[0])).toEqual([901, 901]);
    expect(mockedDeployConfig.mock.calls.map(call => call[0])).toEqual([900]);
    // 日志弹窗选实例不影响运行状态弹窗的证据归属
    expect(dialogText(statusDialog)).toContain(`${zhCN.currentVersions.dialogInstanceId}: 900`);
    expect(mockedStatus.mock.calls.map(call => call[0])).toEqual([900]);
  });
});

describe('CurrentVersionsView 三态、失败与空状态', () => {
  it('只有后端明确返回 true/false 才给结论，缺失字段一律无法确认', async () => {
    mockedStatus.mockResolvedValue(statusEvidence({
      running: undefined,
      portListening: null,
      healthStatus: null,
      pid: null,
      errorMessage: '诊断命令返回非零退出码：command not found',
    }));
    await mountView();

    await clickRowButton(0, zhCN.currentVersions.actionStatus);

    const text = dialogText(dialogByTitle(zhCN.currentVersions.statusTitle));
    expect(text).not.toContain(zhCN.currentVersions.statusNotRunning);
    expect(text).not.toContain(zhCN.currentVersions.statusNotListening);
    expect(text.match(new RegExp(zhCN.currentVersions.statusUnconfirmed, 'g'))?.length ?? 0).toBeGreaterThanOrEqual(3);
    // 缺失字段不作为错误层级的证据：既不判进程/端口，也不落进"无法定位"以外的结论
    expect(text).toContain(zhCN.currentVersions.layerUnknown);
    expect(text).not.toContain(zhCN.currentVersions.layerProcess);
    expect(text).not.toContain(zhCN.currentVersions.layerPort);
  });

  it('明确返回的 true/false 正常渲染，未知健康状态原样保留、不被当作正常', async () => {
    mockedStatus.mockResolvedValue(statusEvidence({
      running: true,
      portListening: false,
      healthStatus: 'DEGRADED',
      errorMessage: '端口未监听：127.0.0.1:8080 无响应',
    }));
    await mountView();

    await clickRowButton(0, zhCN.currentVersions.actionStatus);

    const text = dialogText(dialogByTitle(zhCN.currentVersions.statusTitle));
    expect(text).toContain(zhCN.currentVersions.statusRunning);
    expect(text).toContain(zhCN.currentVersions.statusNotListening);
    // 健康状态保留后端原始值，不翻译、不折算成"正常"
    expect(text).toContain('DEGRADED');
    expect(text).toContain(zhCN.currentVersions.layerPort);
  });

  it('进程在跑、端口在听但健康检查异常时，错误层级指到健康检查而不是成功', async () => {
    mockedStatus.mockResolvedValue(statusEvidence({
      running: true,
      portListening: true,
      healthStatus: 'DEGRADED',
      errorMessage: '健康检查返回 DEGRADED',
    }));
    await mountView();

    await clickRowButton(0, zhCN.currentVersions.actionStatus);

    const text = dialogText(dialogByTitle(zhCN.currentVersions.statusTitle));
    expect(text).toContain('DEGRADED');
    expect(text).toContain(zhCN.currentVersions.layerHealth);
    expect(text).not.toContain(zhCN.currentVersions.layerPort);
    expect(text).not.toContain(zhCN.currentVersions.layerProcess);
    expect(text).not.toContain(zhCN.currentVersions.layerUnknown);
    expect(text).not.toContain(zhCN.currentVersions.logsCopied);
  });

  it('读取失败不等于服务宕机：保留后端原始错误，并给出可操作提示与只读重试', async () => {
    mockedStatus.mockRejectedValue(new Error('目标主机缺少启用中的 SSH 配置，无法执行远程诊断'));
    await mountView();

    await clickRowButton(0, zhCN.currentVersions.actionStatus);

    const dialog = dialogByTitle(zhCN.currentVersions.statusTitle);
    const text = dialogText(dialog);
    expect(text).toContain(zhCN.currentVersions.readFailed);
    expect(text).toContain(zhCN.currentVersions.statusReadFailedHint);
    expect(text).toContain(zhCN.currentVersions.errorHostSshMissing);
    expect(dialog.querySelector('.raw-error-text')?.textContent).toContain('目标主机缺少启用中的 SSH 配置');
    expect(text).toContain(zhCN.currentVersions.readOnlyRetry);
    expect(text).not.toContain(zhCN.currentVersions.statusNotRunning);
    // 首次读取失败时没有旧证据，不给出"上一次证据"的说明
    expect(text).not.toContain(zhCN.currentVersions.staleEvidenceNotice);
  });

  it('同一目标再次读取失败时保留上一次证据，并明确标注本次读取失败', async () => {
    mockedStatus
      .mockResolvedValueOnce(statusEvidence({ running: true }))
      .mockRejectedValueOnce(new Error('SSH 连接超时'));
    await mountView();

    await clickRowButton(0, zhCN.currentVersions.actionStatus);
    const dialog = dialogByTitle(zhCN.currentVersions.statusTitle);
    // 已读到证据时的手动重读入口：只重新发起一次只读读取
    await clickDialogButton(dialog, zhCN.currentVersions.readAgain);

    const text = dialogText(dialog);
    expect(text).toContain(zhCN.currentVersions.readFailed);
    expect(text).toContain(zhCN.currentVersions.staleEvidenceNotice);
    expect(text).toContain(zhCN.currentVersions.statusRunning);
    expect(dialog.querySelector('.raw-error-text')?.textContent).toContain('SSH 连接超时');
    expect(mockedStatus.mock.calls.map(call => call[0])).toEqual([900, 900]);
    // 失败后入口改为"只读重试"，提示不暗示重启、重新部署或恢复服务
    expect(dialogText(dialog)).toContain(zhCN.currentVersions.readOnlyRetry);
    expect(dialogText(dialog)).toContain(zhCN.currentVersions.readAgainHint);
  });

  it('部署配置读取失败只影响端口展示，不会被当作运行状态读取失败', async () => {
    mockedDeployConfig.mockRejectedValue(new Error('部署配置读取失败'));
    await mountView();

    await clickRowButton(0, zhCN.currentVersions.actionStatus);

    const dialog = dialogByTitle(zhCN.currentVersions.statusTitle);
    const text = dialogText(dialog);
    expect(dialog.querySelector('.quick-summary')).not.toBeNull();
    expect(text).toContain(zhCN.currentVersions.statusRunning);
    expect(summaryPortText(dialog)).toBe(zhCN.currentVersions.statusUnconfirmed);
    expect(dialog.querySelector('.summary-port-unconfirmed')).not.toBeNull();
    // 附属配置读不到时明确提示"无法确认"，但不把状态证据判成读取失败
    expect(text).toContain(zhCN.currentVersions.statusConfigUnavailableTitle);
    expect(text).toContain(zhCN.currentVersions.statusConfigFailedHint);
    // 状态读取本身没有失败：失败态由告警标题表达，附属配置提示里的"读取失败"不算
    expect(alertTitles(dialog)).not.toContain(zhCN.currentVersions.readFailed);
    expect(text).not.toContain(zhCN.currentVersions.statusNotRunning);
  });

  it('附属部署配置的目标与本次实例/服务/环境不符时拒绝展示其端口与来源', async () => {
    // 合成响应：instanceId/serviceId/envId 全部为 999，端口 6666 不得出现在正常目标的状态摘要里
    mockedDeployConfig.mockResolvedValue(deployConfigEvidence({
      instanceId: 999, serviceId: 999, envId: 999, deployPort: 6666, source: '/tmp/foreign.yaml',
    }));
    await mountView();

    await clickRowButton(0, zhCN.currentVersions.actionStatus);

    const dialog = dialogByTitle(zhCN.currentVersions.statusTitle);
    const text = dialogText(dialog);
    expect(text).toContain(zhCN.currentVersions.statusConfigUnavailableTitle);
    expect(text).toContain(zhCN.currentVersions.statusConfigScopeHint);
    expect(text).not.toContain('6666');
    expect(text).not.toContain('/tmp/foreign.yaml');
    expect(summaryPortText(dialog)).toBe(zhCN.currentVersions.statusUnconfirmed);
    // 状态证据来自状态接口，仍然有效；也不因此宣称服务停机
    expect(text).toContain(zhCN.currentVersions.statusRunning);
    expect(text).not.toContain(zhCN.currentVersions.statusNotRunning);
    // 状态读取本身没有失败：失败态由告警标题表达，附属配置提示里的"读取失败"不算
    expect(alertTitles(dialog)).not.toContain(zhCN.currentVersions.readFailed);
  });

  it('状态证据作用域与当前目标不符时拒绝展示，并说明原因', async () => {
    mockedStatus.mockResolvedValue(statusEvidence({ instanceId: 999 }));
    await mountView();

    await clickRowButton(0, zhCN.currentVersions.actionStatus);

    const dialog = dialogByTitle(zhCN.currentVersions.statusTitle);
    const text = dialogText(dialog);
    expect(text).toContain(zhCN.currentVersions.readFailed);
    expect(text).toContain(zhCN.currentVersions.scopeMismatch);
    expect(dialog.querySelector('.quick-summary')).toBeNull();
  });

  it('日志 success=false 与读取请求失败分开表达，且保留后端原始错误信息', async () => {
    mockedLogs.mockResolvedValue(logsEvidence({ success: false, content: '', errorMessage: '日志文件不存在: /opt/app/logs/app.log' }));
    await mountView();

    await clickRowButton(0, zhCN.currentVersions.actionLogs);

    const dialog = dialogByTitle(zhCN.currentVersions.logsTitle);
    const text = dialogText(dialog);
    expect(text).toContain('日志文件不存在: /opt/app/logs/app.log');
    expect(text).not.toContain(zhCN.currentVersions.readFailed);
    expect(text).not.toContain(zhCN.currentVersions.logsSuccessUnknown);
    expect(text).toContain(zhCN.currentVersions.logsEmpty);
  });

  it('日志 success 缺失时不当作读取成功', async () => {
    mockedLogs.mockResolvedValue(logsEvidence({ success: undefined }));
    await mountView();

    await clickRowButton(0, zhCN.currentVersions.actionLogs);

    const text = dialogText(dialogByTitle(zhCN.currentVersions.logsTitle));
    expect(text).toContain(zhCN.currentVersions.logsSuccessUnknown);
    expect(text).toContain(zhCN.currentVersions.logsTailNotice.replace('{lines}', '200'));
  });

  it('空日志给出明确空状态，复制按钮不可用且提示没有可复制内容', async () => {
    mockedLogs.mockResolvedValue(logsEvidence({ success: true, content: '' }));
    const warning = vi.spyOn(ElMessage, 'warning').mockImplementation(() => ({}) as never);
    await mountView();

    await clickRowButton(0, zhCN.currentVersions.actionLogs);

    const dialog = dialogByTitle(zhCN.currentVersions.logsTitle);
    expect(dialogText(dialog)).toContain(zhCN.currentVersions.logsEmpty);
    const copy = buttonWithLabel(dialog, zhCN.currentVersions.logsCopy)!;
    expect(copy.disabled).toBe(true);
    copy.click();
    await flushPromises();
    expect(writeText).not.toHaveBeenCalled();
    expect(warning).not.toHaveBeenCalled();
    expect(mockedLogs).toHaveBeenCalledTimes(1);
  });

  it('复制的是接口返回的原始日志内容，逐字不改写；复制失败给出显式反馈', async () => {
    const raw = 'line-1\n\ttabbed\nline-3 中文原文';
    mockedLogs.mockResolvedValue(logsEvidence({ content: raw }));
    const success = vi.spyOn(ElMessage, 'success').mockImplementation(() => ({}) as never);
    await mountView();

    await clickRowButton(0, zhCN.currentVersions.actionLogs);
    const dialog = dialogByTitle(zhCN.currentVersions.logsTitle);
    expect(dialog.querySelector('.runtime-log')?.textContent).toBe(raw);

    await clickDialogButton(dialog, zhCN.currentVersions.logsCopy);
    expect(writeText).toHaveBeenCalledTimes(1);
    expect(writeText).toHaveBeenCalledWith(raw);
    expect(success).toHaveBeenCalledWith(zhCN.currentVersions.logsCopied);

    writeText.mockRejectedValueOnce(new Error('clipboard denied'));
    const error = vi.spyOn(ElMessage, 'error').mockImplementation(() => ({}) as never);
    await clickDialogButton(dialog, zhCN.currentVersions.logsCopy);
    expect(error).toHaveBeenCalledWith(zhCN.currentVersions.logsCopyFailed);
  });
});

describe('CurrentVersionsView 文案与语言切换', () => {
  it('已打开的弹窗随语言即时切换，且切换语言不触发任何新的远程诊断', async () => {
    mockedList.mockResolvedValue([instance({ id: 900 }), instance({ id: 901 })]);
    await mountView();

    await clickRowButton(0, zhCN.currentVersions.actionStatus);
    const dialog = dialogByTitle(zhCN.currentVersions.statusTitle);
    expect(dialogText(dialog)).toContain(zhCN.currentVersions.selectInstanceTitle.replace('{count}', '2'));

    const listCalls = mockedList.mock.calls.length;
    setLocale('en-US');
    await flushPromises();

    const englishDialog = dialogByTitle(enUS.currentVersions.statusTitle);
    expect(englishDialog).toBe(dialog);
    expect(dialogText(englishDialog))
      .toContain(enUS.currentVersions.selectInstanceTitle.replace('{count}', '2'));
    expect(buttonWithLabel(englishDialog, enUS.currentVersions.confirmRead)).toBeDefined();
    expect(buttonWithLabel(englishDialog, zhCN.currentVersions.confirmRead)).toBeUndefined();
    // 语言切换不是用户动作，不得触发任何远程诊断
    expect(mockedList.mock.calls.length).toBe(listCalls);
    expect(mockedStatus).not.toHaveBeenCalled();
    expect(mockedLogs).not.toHaveBeenCalled();
    expect(mockedDeployConfig).not.toHaveBeenCalled();

    setLocale('zh-CN');
    await flushPromises();
    expect(dialogText(dialogByTitle(zhCN.currentVersions.statusTitle)))
      .toContain(zhCN.currentVersions.selectInstanceTitle.replace('{count}', '2'));
  });

  it('行内入口文案随语言切换，业务名称与后端原始值不被翻译', async () => {
    mockedList.mockRejectedValue(new Error('实例列表读取失败：connection refused'));
    await mountView();

    expect(rowActionButtons(0).map(button => (button.textContent || '').trim())).toEqual([
      zhCN.currentVersions.actionStatus,
      zhCN.currentVersions.actionLogs,
      zhCN.currentVersions.actionMore,
    ]);

    await clickRowButton(0, zhCN.currentVersions.actionStatus);
    setLocale('en-US');
    await flushPromises();

    expect(rowActionButtons(0).map(button => (button.textContent || '').trim())).toEqual([
      enUS.currentVersions.actionStatus,
      enUS.currentVersions.actionLogs,
      enUS.currentVersions.actionMore,
    ]);
    expect(cellText(0)).toBe('订单服务');
    expect(dialogByTitle(enUS.currentVersions.statusTitle).querySelector('.raw-error-text')?.textContent)
      .toContain('实例列表读取失败：connection refused');
  });
});
