import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { nextTick } from 'vue';
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils';
import ElementPlus from 'element-plus';
import { createPinia, setActivePinia } from 'pinia';
import { createMemoryHistory, createRouter, type Router } from 'vue-router';
import { i18n, setLocale } from '@/i18n';
import { HOSTNAME_MAX_LENGTH, IP_MAX_LENGTH } from '@/utils/hostValidation';
import HostsView from '../HostsView.vue';
import { createAssetHost, getHostProfile, pageAssetHosts, type AssetHost } from '@/api/asset';
import { listCollectionStates, type CollectionStateView } from '@/api/monitorCollection';

/** 直接取当前语言下的文案，避免测试里写死中文字符串 */
const t = (key: string) => i18n.global.t(key) as string;

/**
 * 后端对「未填写的规格」与「尚未探测的状态/时间」返回 null（前端类型里写的是必填或可选字段，
 * 没有表达 null），这里按接口在运行时真实的形状构造样本，因此需要一次显式转换。
 */
const hostRow = (row: Record<string, unknown>): AssetHost => row as unknown as AssetHost;

/**
 * 主机手动登记 + 状态展示的组件级自测。
 *
 * 覆盖：
 *  - 登记闭包：必填/格式校验拦截、成功后刷新列表、失败保留表单、提交中防重复提交；
 *  - 状态展示：UNKNOWN 中性且保留原始码、认不出的取值原样显示、无记录时不伪造时间；
 *  - 中英文切换下新增表单与状态文案都跟着变。
 *
 * 边界说明：接口全部 mock，不连真实后端；happy-dom 不计算真实排版与颜色，所以"中性"以
 * el-tag 的 type/effect 属性为准，"未记录"以文本为准，不断言像素级视觉。
 */
vi.mock('@/api/asset', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/asset')>();
  return {
    ...actual,
    pageAssetHosts: vi.fn(),
    pageResourcePools: vi.fn(async () => ({ pageNum: 1, pageSize: 200, total: 0, records: [] })),
    getHostProfile: vi.fn(),
    createAssetHost: vi.fn(),
  };
});

vi.mock('@/api/resource', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/resource')>();
  const envs = [{ id: 1, envCode: 'DEV', envName: '开发环境' }];
  return {
    ...actual,
    envApi: { ...actual.envApi, page: vi.fn(async () => ({ pageNum: 1, pageSize: 200, total: 1, records: envs })) },
  };
});

// 采集状态列读的是后台**已经记录**的结果。这里照旧不连真实后端：既不发探测请求，
// 也不让列表页的自测去打真实接口。
vi.mock('@/api/monitorCollection', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/monitorCollection')>();
  return { ...actual, listCollectionStates: vi.fn() };
});

const pageAssetHostsMock = vi.mocked(pageAssetHosts);
const getHostProfileMock = vi.mocked(getHostProfile);
const createAssetHostMock = vi.mocked(createAssetHost);
const listCollectionStatesMock = vi.mocked(listCollectionStates);

/** 采集状态列覆盖到的字段：默认"没有采集记录"，个别用例再按需要喂真实取值 */
const collectionState = (targetId: number, patch: Partial<CollectionStateView> = {}): CollectionStateView => ({
  targetType: 'HOST',
  targetId,
  observerType: 'SSH_PROCESS',
  source: 'SSH_PROBE',
  configPresent: true,
  result: 'SUCCESS',
  freshness: 'FRESH',
  lastAttemptAt: '2026-10-15T02:00:00',
  lastSuccessAt: '2026-10-15T02:00:00',
  ...patch,
});

const collectionResponse = (states: CollectionStateView[], collectionEnabled = true) => ({
  collectionEnabled,
  staleThresholdMs: 90_000,
  states,
});

const UNKNOWN_HOST: AssetHost = hostRow({
  id: 11,
  hostname: 'web-01',
  ip: '192.0.2.4',
  envId: 1,
  resourcePoolId: null,
  osType: 'LINUX',
  hostType: 'PHYSICAL',
  cpuCores: null,
  memoryGb: null,
  diskGb: null,
  hostStatus: 'UNKNOWN',
  agentStatus: 'UNKNOWN',
  lastHeartbeat: null,
  createdAt: '2026-01-01T00:00:00',
  updatedAt: '2026-01-01T00:00:00',
});

const WEIRD_HOST: AssetHost = {
  ...UNKNOWN_HOST,
  id: 12,
  hostname: 'legacy-01',
  ip: '192.0.2.5',
  hostStatus: 'SOMETHING_NEW',
  agentStatus: 'ONLINE',
  lastHeartbeat: '2026-01-02T03:04:05',
};

let wrapper: VueWrapper | undefined;
let router: Router;
let listCalls: Record<string, unknown>[];

const wait = (ms: number) => new Promise((resolve) => setTimeout(resolve, ms));

async function settle() {
  await flushPromises();
  await nextTick();
  await flushPromises();
}

function text(selector: string) {
  return (document.body.querySelector(selector)?.textContent || '').replace(/\s+/g, ' ').trim();
}

function dialogOverlay(): HTMLElement | null {
  return document.body.querySelector<HTMLElement>('.el-dialog');
}

function dialogOpen() {
  const overlay = dialogOverlay()?.closest<HTMLElement>('.el-overlay');
  if (!overlay) return false;
  return overlay.style.display !== 'none';
}

function formInputs(): HTMLInputElement[] {
  return Array.from(document.body.querySelectorAll<HTMLInputElement>('.el-dialog .el-form-item input'));
}

async function setInput(index: number, value: string) {
  const input = formInputs()[index];
  if (!input) throw new Error(`找不到第 ${index} 个表单输入框`);
  input.value = value;
  input.dispatchEvent(new Event('input', { bubbles: true }));
  input.dispatchEvent(new Event('change', { bubbles: true }));
  await settle();
}

/** 打开登记弹窗：表头里唯一的 primary 按钮 */
async function openCreateDialog() {
  const button = wrapper!.find('.card-header__actions .el-button--primary');
  expect(button.exists(), '表头应有登记主机按钮').toBe(true);
  await button.trigger('click');
  await settle();
  expect(dialogOpen(), '弹窗应处于打开状态').toBe(true);
}

/** 按标签定位弹窗里的表单项，避免依赖字段顺序 */
function createFormItem(labelText: string): HTMLElement {
  const items = Array.from(document.body.querySelectorAll<HTMLElement>('.el-dialog .el-form-item'));
  const item = items.find((el) =>
    (el.querySelector('.el-form-item__label')?.textContent || '').includes(labelText),
  );
  if (!item) throw new Error(`找不到「${labelText}」表单项`);
  return item;
}

/** 选中环境：走真实的下拉路径（点开选择框 → 点选项） */
async function selectEnvOption() {
  const item = createFormItem(t('hosts.envDisplayName'));
  const selectWrapper = item.querySelector<HTMLElement>('.el-select__wrapper');
  if (!selectWrapper) throw new Error('找不到环境选择框');
  selectWrapper.dispatchEvent(new MouseEvent('mousedown', { bubbles: true }));
  selectWrapper.dispatchEvent(new MouseEvent('click', { bubbles: true }));
  await settle();
  // 页面上还有筛选栏的同名下拉，必须只挑当前展开（可见）的那一个，
  // 否则会把值写进筛选条件而不是登记表单。
  const dropdowns = Array.from(document.body.querySelectorAll<HTMLElement>('.el-select-dropdown')).filter(
    (d) => d.closest<HTMLElement>('.el-popper')?.style.display !== 'none'
  );
  const envDropdown = dropdowns.find((d) => (d.textContent || '').includes('开发环境'));
  if (!envDropdown) throw new Error('环境下拉没有渲染出来');
  const option = envDropdown.querySelector<HTMLElement>('.el-select-dropdown__item');
  if (!option) throw new Error('环境下拉里没有可选项');
  option.dispatchEvent(new MouseEvent('mousedown', { bubbles: true }));
  option.click();
  await settle();
  if (!(item.textContent || '').includes('开发环境')) throw new Error('环境没有选中');
}

function footerButtons(): HTMLButtonElement[] {
  return Array.from(document.body.querySelectorAll<HTMLButtonElement>('.el-dialog__footer button'));
}

/** 弹窗右上角的 X，和 Escape / 遮罩一样都属于“把弹窗关掉”的入口 */
function closeIcon(): HTMLElement {
  const el = document.body.querySelector<HTMLElement>('.el-dialog__headerbtn');
  if (!el) throw new Error('找不到弹窗关闭按钮');
  return el;
}

function overlayRoot(): HTMLElement {
  const el = document.body.querySelector<HTMLElement>('.el-overlay');
  if (!el) throw new Error('找不到弹窗遮罩');
  return el;
}

/** Escape 走的是 document 级 keydown（Element Plus 的 useEscapeKeydown） */
function pressEscape() {
  document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', code: 'Escape' }));
}

/** 表头「登记主机」按钮：提交期间它必须点不动，否则会换掉正在提交的那张表单 */
function headerRegisterButton() {
  return wrapper!.find<HTMLButtonElement>('.card-header__actions .el-button--primary');
}

function isDisabled(el: Element | null): boolean {
  return !!el && (el as HTMLButtonElement).disabled === true;
}

/** 组件内部状态：用于构造“弹窗里的表单已被替换”这一时序（X/Escape/遮罩都关不掉时，只有状态替换才能制造迟到的旧响应） */
function internalState() {
  return wrapper!.vm as unknown as {
    createFormData: Record<string, unknown>;
    createVisible: boolean;
    submitting: boolean;
  };
}

/** 最近一条错误提示文案（ElMessage 渲染在 body 上） */
function lastMessageText() {
  const nodes = Array.from(document.body.querySelectorAll<HTMLElement>('.el-message'));
  return (nodes[nodes.length - 1]?.textContent || '').replace(/\s+/g, ' ').trim();
}

async function submit() {
  const buttons = footerButtons();
  const confirm = buttons[buttons.length - 1];
  confirm.click();
  await settle();
}

async function fillValidForm() {
  await setInput(0, '  web-02  '); // 首尾空格应被裁剪
  await setInput(1, '192.0.2.6');
  await selectEnvOption();
  await setInput(4, 'CENTOS7');
  await setInput(5, 'PHYSICAL');
}

async function mountView() {
  setLocale('zh-CN');
  const pinia = createPinia();
  setActivePinia(pinia);
  router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', component: { template: '<div />' } },
      { path: '/resource/runtime-instances', component: { template: '<div />' } },
    ],
  });
  await router.push({ path: '/' });
  await router.isReady();
  wrapper = mount(HostsView, {
    global: { plugins: [ElementPlus, i18n, pinia, router] },
    attachTo: document.body,
  });
  await settle();
}

beforeEach(() => {
  listCalls = [];
  setLocale('zh-CN');
  pageAssetHostsMock.mockReset();
  getHostProfileMock.mockReset();
  createAssetHostMock.mockReset();
  listCollectionStatesMock.mockReset();
  // 默认：这两个目标都还没有采集记录
  listCollectionStatesMock.mockResolvedValue(collectionResponse([]) as never);

  pageAssetHostsMock.mockImplementation(async (params) => {
    listCalls.push({ ...params });
    return { pageNum: 1, pageSize: 10, total: 2, records: [UNKNOWN_HOST, WEIRD_HOST] } as never;
  });
  getHostProfileMock.mockImplementation(async (id: number) => ({
    hostId: id,
    hostname: id === 11 ? 'web-01' : 'legacy-01',
    ip: '192.0.2.4',
    envName: '开发环境',
    resourcePoolName: null,
    runtimeInstanceCount: 0,
    healthStatus: 'UNKNOWN',
    healthReason: '主机状态未知，尚未确认',
    latestDeployAt: null,
    latestDeployStatus: null,
    latestMonitorSnapshotAt: null,
  }) as never);
  createAssetHostMock.mockResolvedValue(UNKNOWN_HOST as never);
});

afterEach(() => {
  wrapper?.unmount();
  wrapper = undefined;
  document.body.innerHTML = '';
  vi.clearAllMocks();
});

describe('主机手动登记：校验与提交', () => {
  it('必填未填时提交被前端拦下，不发请求且表单保留', async () => {
    await mountView();
    await openCreateDialog();

    await submit();

    expect(createAssetHostMock).not.toHaveBeenCalled();
    expect(dialogOpen(), '校验失败时弹窗不应关闭').toBe(true);
    expect(lastMessageText(), '应给出可读的必填提示').toContain(t('hosts.requiredRule'));
  });

  it('登记表单把必填/长度/格式规则挂在对应字段上', async () => {
    await mountView();
    await openCreateDialog();

    const form = wrapper!.findComponent({ name: 'ElForm' });
    const rules = form.props('rules') as Record<string, { required?: boolean; max?: number; validator?: unknown }[]>;
    expect(Object.keys(rules).sort()).toEqual([
      'cpuCores',
      'diskGb',
      'envId',
      'hostname',
      'hostType',
      'ip',
      'memoryGb',
      'osType',
    ].sort());
    expect(rules.hostname[0].required).toBe(true);
    expect(rules.hostname[1].max).toBe(HOSTNAME_MAX_LENGTH);
    expect(rules.ip[0].required).toBe(true);
    expect(rules.ip[1].max).toBe(IP_MAX_LENGTH);
    expect(rules.envId[0].required).toBe(true);
    expect(rules.osType[0].required).toBe(true);
    expect(rules.hostType[0].required).toBe(true);
    // 规格是选填：留空合法，但填了就要满足取值契约（>0、不超过列宽上限、最多 2 位小数）
    expect(rules.cpuCores[0].required).toBeUndefined();
    expect(rules.memoryGb[0].required).toBeUndefined();
    expect(rules.diskGb[0].required).toBeUndefined();
    expect(typeof rules.cpuCores[0].validator, '规格应走取值契约校验而非只判非负').toBe('function');

    // 规则里的格式校验函数本身要能拒绝非法值（与后端同一套边界）
    const ipRule = rules.ip.find((rule) => typeof rule.validator === 'function');
    expect(ipRule, 'IP 应有格式校验函数').toBeTruthy();
    const rejected = await new Promise<string | null>((resolve) => {
      (ipRule!.validator as (r: unknown, v: string, cb: (e?: Error) => void) => void)(
        {},
        '999.1.1.1',
        (err?: Error) => resolve(err?.message ?? null),
      );
    });
    expect(rejected).toBe(t('hosts.ipRule'));
  });

  it('非法 IP 被拦下，不进入后端', async () => {
    await mountView();
    await openCreateDialog();

    await setInput(0, 'web-03');
    await setInput(1, '999.1.1.1');
    await selectEnvOption();
    await setInput(4, 'CENTOS7');
    await setInput(5, 'PHYSICAL');
    await submit();

    expect(createAssetHostMock).not.toHaveBeenCalled();
    expect(dialogOpen()).toBe(true);
    expect(lastMessageText()).toContain(t('hosts.ip'));
    expect(lastMessageText()).toContain(t('hosts.ipRule'));
  });

  it('合法登记：裁剪空格、不提交留空的规格，成功后刷新列表', async () => {
    await mountView();
    expect(listCalls).toHaveLength(1);
    await openCreateDialog();

    await fillValidForm();
    await submit();

    expect(createAssetHostMock).toHaveBeenCalledTimes(1);
    const payload = createAssetHostMock.mock.calls[0][0];
    expect(payload).toEqual({
      hostname: 'web-02',
      ip: '192.0.2.6',
      envId: 1,
      osType: 'CENTOS7',
      hostType: 'PHYSICAL',
    });
    expect(payload).not.toHaveProperty('cpuCores');
    expect(payload).not.toHaveProperty('memoryGb');
    expect(payload).not.toHaveProperty('diskGb');
    expect(payload).not.toHaveProperty('resourcePoolId');

    // 成功后重新拉列表
    expect(listCalls).toHaveLength(2);
    expect(createAssetHostMock.mock.calls[0][0]).not.toHaveProperty('hostStatus');
  });

  it('提交进行中不允许重复提交', async () => {
    let resolveCreate: (value: unknown) => void = () => {};
    createAssetHostMock.mockImplementation(
      () => new Promise((resolve) => {
        resolveCreate = resolve;
      }) as never,
    );

    await mountView();
    await openCreateDialog();
    await fillValidForm();

    await submit();
    expect(createAssetHostMock).toHaveBeenCalledTimes(1);

    // 提交未返回时再点一次确认、再点一次取消：都不应产生第二次请求，也不应关闭弹窗
    await submit();
    footerButtons()[0].click();
    await settle();

    expect(createAssetHostMock).toHaveBeenCalledTimes(1);
    expect(dialogOpen(), '提交中弹窗不应被关掉').toBe(true);

    resolveCreate(UNKNOWN_HOST);
    await settle();
  });

  it('提交进行中：X / Escape / 遮罩 / 取消 / 再次登记 五条关闭入口全部被拦下', async () => {
    let resolveCreate: (value: unknown) => void = () => {};
    createAssetHostMock.mockImplementation(
      () => new Promise((resolve) => {
        resolveCreate = resolve;
      }) as never,
    );

    await mountView();
    await openCreateDialog();
    await fillValidForm();
    await submit();
    expect(createAssetHostMock).toHaveBeenCalledTimes(1);

    // 表头的再次登记入口先被禁掉，避免提交还在飞时换一张表单
    expect(headerRegisterButton().attributes('disabled')).toBeDefined();

    closeIcon().click();
    await settle();
    expect(dialogOpen(), 'X 不能关掉提交中的弹窗').toBe(true);

    pressEscape();
    await settle();
    expect(dialogOpen(), 'Escape 不能关掉提交中的弹窗').toBe(true);

    overlayRoot().dispatchEvent(new MouseEvent('click', { bubbles: true }));
    await settle();
    expect(dialogOpen(), '点击遮罩不能关掉提交中的弹窗').toBe(true);

    expect(isDisabled(footerButtons()[0]), '取消按钮应被禁用').toBe(true);
    footerButtons()[0].click();
    await settle();
    expect(dialogOpen(), '取消按钮点不动').toBe(true);

    await headerRegisterButton().trigger('click');
    await settle();
    expect(dialogOpen(), '再次登记不能在提交期间换掉当前表单').toBe(true);

    expect(createAssetHostMock, '提交期间不应产生第二次请求').toHaveBeenCalledTimes(1);

    resolveCreate(UNKNOWN_HOST);
    await settle();
    expect(dialogOpen(), '响应回来后正常关闭').toBe(false);
  });

  it('提交进行中：整张表单冻结，字段值不会再被改动', async () => {
    const pending: { resolve: (value: unknown) => void } = { resolve: () => {} };
    createAssetHostMock.mockImplementation(
      () => new Promise((resolve) => {
        pending.resolve = resolve;
      }) as never,
    );

    await mountView();
    await openCreateDialog();
    await fillValidForm();
    await submit();

    const inputs = formInputs();
    expect(inputs.length).toBeGreaterThanOrEqual(6);
    expect(
      inputs.every((input) => input.disabled),
      '提交中弹窗里的每个字段都应处于禁用状态',
    ).toBe(true);
    expect(wrapper!.findComponent({ name: 'ElForm' }).props('disabled')).toBe(true);

    // 这次请求的载荷在提交那一刻就已经定下，之后字段冻结不会再影响它
    const payload = createAssetHostMock.mock.calls[0][0] as unknown as Record<string, unknown>;
    expect(payload.hostname).toBe('web-02');
    expect(payload.ip).toBe('192.0.2.6');

    pending.resolve(UNKNOWN_HOST);
    await settle();
  });

  it('迟到的成功响应不会关闭或清空后来打开的表单（按表单对象归属判定）', async () => {
    const pending: { resolve: (value: unknown) => void } = { resolve: () => {} };
    createAssetHostMock.mockImplementation(
      () => new Promise((resolve) => {
        pending.resolve = resolve;
      }) as never,
    );

    await mountView();
    await openCreateDialog();
    await fillValidForm();
    await submit();

    // 迟到响应的场景：请求还在飞的时候，弹窗里的表单已经被换成另一张。
    // 上面的关闭入口都指向同一条规则，所以这里直接替换组件状态来构造这个时序。
    const state = internalState();
    const laterForm = { hostname: 'later-01', ip: '192.0.2.7', envId: 1, osType: 'CENTOS7', hostType: 'PHYSICAL' };
    state.createFormData = laterForm;
    await settle();

    pending.resolve(UNKNOWN_HOST);
    await settle();

    expect(internalState().createFormData.hostname, '旧响应不得清空后来那份表单').toBe('later-01');
    expect(state.createVisible, '旧响应不得关掉后来的表单').toBe(true);
    expect(document.body.querySelector('.el-message--success'), '真正成功的登记提示不能被静默丢弃').not.toBeNull();
    expect(lastMessageText()).toContain(t('hosts.createSuccess'));
    // 归属变了也要照常刷新列表，不能因为“不是我的表单”就跳过数据同步
    expect(listCalls.length).toBeGreaterThanOrEqual(2);
  });

  it('登记成功后列表刷新变慢或失败，成功提示仍然可见', async () => {
    await mountView();
    await openCreateDialog();
    await fillValidForm();

    // 让成功之后的那次列表刷新挂住：提示必须在它之前就已经可见
    const slowList = new Promise((resolve) => setTimeout(() => resolve({
      pageNum: 1, pageSize: 10, total: 2, records: [UNKNOWN_HOST, WEIRD_HOST],
    }), 30));
    pageAssetHostsMock.mockImplementationOnce(() => slowList as never);

    await submit();

    expect(document.body.querySelector('.el-message--success'), '成功提示不应等列表刷新').not.toBeNull();
    expect(lastMessageText()).toContain(t('hosts.createSuccess'));
    expect(dialogOpen(), '登记成功后应关闭弹窗').toBe(false);

    await new Promise((resolve) => setTimeout(resolve, 40));
    await settle();
  });

  it('登记成功后表单已清空：重新打开是一张空白表单', async () => {
    await mountView();
    await openCreateDialog();
    await fillValidForm();
    await submit();

    expect(dialogOpen(), '成功后应关闭').toBe(false);

    await openCreateDialog();
    const values = formInputs().map((input) => input.value);
    expect(values[0], '主机名应已清空').toBe('');
    expect(values[1], 'IP 应已清空').toBe('');
    expect(formInputs().every((input) => !input.disabled), '新表单不应还是冻结状态').toBe(true);
  });

  it('规格按契约校验：显式 0、越界、超过两位小数被拦下，留空与边界值放行', async () => {
    await mountView();
    await openCreateDialog();
    await fillValidForm();

    // CPU：显式 0 —— 不是“未填写”，必须报错而不是静默按未登记处理
    await setInput(6, '0');
    await submit();
    expect(createAssetHostMock, '显式 0 不得发出请求').not.toHaveBeenCalled();
    expect(lastMessageText()).toContain(t('hosts.cpuRule'));

    await setInput(6, '1000000');
    await submit();
    expect(createAssetHostMock).not.toHaveBeenCalled();
    expect(lastMessageText()).toContain(t('hosts.cpuRule'));

    await setInput(6, '0.001');
    await submit();
    expect(createAssetHostMock).not.toHaveBeenCalled();
    expect(lastMessageText()).toContain(t('hosts.cpuRule'));

    // 内存越界
    await setInput(6, '4');
    await setInput(7, '100000000');
    await submit();
    expect(createAssetHostMock).not.toHaveBeenCalled();
    expect(lastMessageText()).toContain(t('hosts.memoryRule'));

    // 磁盘超过两位小数
    await setInput(7, '8');
    await setInput(8, '1.234');
    await submit();
    expect(createAssetHostMock).not.toHaveBeenCalled();
    expect(lastMessageText()).toContain(t('hosts.diskRule'));

    // 边界值：CPU 上限、内存上限、两位小数都放行并如实提交
    await setInput(6, '999999.99');
    await setInput(7, '99999999.99');
    await setInput(8, '0.01');
    await submit();
    expect(createAssetHostMock).toHaveBeenCalledTimes(1);
    expect(createAssetHostMock.mock.calls[0][0]).toMatchObject({
      cpuCores: 999999.99,
      memoryGb: 99999999.99,
      diskGb: 0.01,
    });
  });

  it('后端拒绝时保留弹窗与已填内容，并允许就地重试', async () => {
    createAssetHostMock.mockRejectedValueOnce(new Error('主机名或 IP 已存在，登记被拒绝'));
    await mountView();
    await openCreateDialog();
    await fillValidForm();

    await submit();

    expect(createAssetHostMock).toHaveBeenCalledTimes(1);
    expect(dialogOpen(), '失败时弹窗必须保留').toBe(true);
    expect(formInputs()[0].value).toBe('  web-02  ');
    expect(formInputs()[1].value).toBe('192.0.2.6');
    expect(document.body.querySelector('.el-message--success')).toBeNull();

    // 复位提交态后可就地重试
    await submit();
    expect(createAssetHostMock).toHaveBeenCalledTimes(2);
  });
});

describe('状态展示：原始码、未知与时间', () => {
  it('UNKNOWN 中性展示，并保留可读的原始状态码', async () => {
    await mountView();

    const cells = Array.from(document.body.querySelectorAll<HTMLElement>('.status-cell[data-status-code="UNKNOWN"]'));
    expect(cells.length).toBeGreaterThanOrEqual(3); // 主机状态 + Agent 状态 + 派生健康状态

    for (const cell of cells) {
      const tag = cell.querySelector('.el-tag');
      expect(tag).not.toBeNull();
      expect(tag!.classList.contains('el-tag--info'), 'UNKNOWN 不该是成功/失败/告警色').toBe(true);
      expect(tag!.classList.contains('el-tag--danger'), 'UNKNOWN 不能被当成故障').toBe(false);
      expect(cell.getAttribute('title')).toContain('UNKNOWN');
    }
  });

  it('认不出的状态值原样展示，不被映射成成功或失败', async () => {
    await mountView();

    const cell = document.body.querySelector<HTMLElement>('.status-cell[data-status-code="SOMETHING_NEW"]');
    expect(cell, '原始取值必须出现在 DOM 上').not.toBeNull();
    expect(cell!.textContent).toContain('SOMETHING_NEW');
    expect(cell!.getAttribute('title')).toContain('SOMETHING_NEW');

    const tag = cell!.querySelector('.el-tag');
    expect(tag!.classList.contains('el-tag--info')).toBe(true);
    expect(tag!.classList.contains('el-tag--success')).toBe(false);
    expect(tag!.classList.contains('el-tag--danger')).toBe(false);
  });

  it('没有心跳记录时说明「未记录」，不伪造时间', async () => {
    await mountView();

    const rows = Array.from(document.body.querySelectorAll<HTMLElement>('.el-table__row'));
    expect(rows.length).toBe(2);
    const unknownRow = rows[0];

    // 断言落在「最近心跳时间」这一格上：整行文本里还并列着采集状态那几列，
    // 它们各自的「未记录」说的是"没查到采集记录"，与心跳是不是为空无关。
    const unknownHeartbeat = unknownRow.querySelector<HTMLElement>('.col-last-heartbeat');
    expect(unknownHeartbeat, '最近心跳时间列必须能单独定位').not.toBeNull();
    expect(unknownHeartbeat!.textContent).toContain('未记录');
    expect(unknownRow.textContent).not.toMatch(/\d{4}\/\d{1,2}\/\d{1,2}/);

    // 有记录的那一行按记录展示
    expect(rows[1].textContent).toMatch(/\d{4}/);
    const knownHeartbeat = rows[1].querySelector<HTMLElement>('.col-last-heartbeat');
    expect(knownHeartbeat!.textContent).toMatch(/\d{4}/);
    expect(knownHeartbeat!.textContent).not.toContain('未记录');
  });

  it('刷新列表只重读已记录数据，不做实时探测（文案与请求都只读）', async () => {
    await mountView();
    expect(listCalls).toHaveLength(1);

    await wrapper!.find('.refresh-list-btn').trigger('click');
    await settle();

    expect(listCalls).toHaveLength(2);
    expect(Object.keys(listCalls[1]).sort()).toEqual(Object.keys(listCalls[0]).sort());
  });

  it('登记主机入口与状态说明入口各自独立，说明弹窗可打开', async () => {
    await mountView();

    const buttons = wrapper!.findAll('.card-header__actions button');
    expect(buttons.length).toBe(4);
    // 登记按钮是唯一的主操作
    expect(wrapper!.findAll('.card-header__actions button.el-button--primary').length).toBe(1);

    await buttons[0].trigger('click');
    await settle();
    expect(text('.el-dialog__title')).toBeTruthy();
  });
});

describe('多语言', () => {
  it('切换到英文后新增表单与状态文案都跟着变', async () => {
    await mountView();

    const headerZh = wrapper!.find('.card-header__actions').text();
    await openCreateDialog();
    const titleZh = text('.el-dialog__title');

    setLocale('en-US');
    await settle();

    const headerEn = wrapper!.find('.card-header__actions').text();
    const titleEn = text('.el-dialog__title');
    const unknownCell = document.body.querySelector<HTMLElement>('.status-cell[data-status-code="UNKNOWN"]');
    const unknownTextEn = (unknownCell?.textContent || '').trim();

    expect(headerEn).not.toBe(headerZh);
    expect(titleEn).not.toBe(titleZh);
    expect(unknownTextEn).toBeTruthy();
    expect(unknownTextEn).not.toBe('未知');

    // 原始码不随语言变化
    expect(unknownCell!.getAttribute('data-status-code')).toBe('UNKNOWN');

    setLocale('zh-CN');
    await wait(0);
    await settle();
    expect((document.body.querySelector('.status-cell[data-status-code="UNKNOWN"]')?.textContent || '').trim()).toBe('未知');
  });
});

/**
 * 采集五列：状态来源 / 最近成功观察时间 / 采集新鲜度 / 采集结果 / 失败原因。
 *
 * 这几列展示的是后台采集器**已经记录**的结果，界面只负责如实呈现。这里钉住的是
 * "如实"的边界：没有记录就说没有记录（不是失败、也不能编一个时间），以及采集结果
 * 绝不参与主机状态、Agent 状态、健康状态的判定。
 */
describe('采集状态列：只读已记录结果，不改写任何判定', () => {
  const rowCells = (rowIndex: number) =>
    Array.from(document.body.querySelectorAll<HTMLElement>('.el-table__row'))[rowIndex] ?? null;

  const cellOf = (rowIndex: number, columnClass: string) =>
    rowCells(rowIndex)?.querySelector<HTMLElement>(`.${columnClass}`) ?? null;

  const codeOf = (cell: HTMLElement | null) =>
    cell?.querySelector<HTMLElement>('.status-cell')?.getAttribute('data-status-code') ?? null;

  it('按后台记录展示来源、新鲜度、结果与最近成功观察时间，并保留可读的原始码', async () => {
    listCollectionStatesMock.mockResolvedValue(
      collectionResponse([
        collectionState(11, { source: 'SSH_PROBE', freshness: 'FRESH', result: 'SUCCESS' }),
        collectionState(12, {
          source: 'AGENT_REPORT',
          freshness: 'STALE',
          result: 'FAILED',
          errorCategory: 'NETWORK_TIMEOUT',
          errorSummary: 'connect timed out',
        }),
      ]) as never,
    );

    await mountView();

    // 第一行：探测成功，来源/结果都是原始码，时间是记录下来的时间
    expect(codeOf(cellOf(0, 'col-collection-source'))).toBe('SSH_PROBE');
    expect(codeOf(cellOf(0, 'col-collection-freshness'))).toBe('FRESH');
    expect(codeOf(cellOf(0, 'col-collection-result'))).toBe('SUCCESS');
    expect(cellOf(0, 'col-collection-last-success')!.textContent).toMatch(/\d{4}/);
    expect(cellOf(0, 'col-collection-failure')!.textContent).toBe(t('hosts.collectionNoFailure'));

    // 第二行：过期 + 失败，失败原因单列，且原始码可读
    expect(codeOf(cellOf(1, 'col-collection-source'))).toBe('AGENT_REPORT');
    expect(codeOf(cellOf(1, 'col-collection-freshness'))).toBe('STALE');
    expect(codeOf(cellOf(1, 'col-collection-result'))).toBe('FAILED');
    expect(codeOf(cellOf(1, 'col-collection-failure'))).toBe('NETWORK_TIMEOUT');
  });

  it('没有采集记录时中性说明「未记录」，不判为失败也不伪造时间', async () => {
    await mountView(); // 默认桩：两个目标都没有采集记录

    for (const column of ['col-collection-source', 'col-collection-freshness', 'col-collection-result']) {
      const cell = cellOf(0, column);
      expect(cell!.textContent).toContain(t('statusSemantics.notRecorded'));
      expect(cell!.querySelector('.el-tag'), '没有记录时不该出现任何状态标签').toBeNull();
    }

    const lastSuccess = cellOf(0, 'col-collection-last-success')!;
    expect(lastSuccess.textContent).toBe(t('statusSemantics.notRecorded'));
    expect(lastSuccess.textContent).not.toMatch(/\d{4}/);

    // 没有失败原因时说的是"无失败记录"，不是红色故障标签
    expect(cellOf(0, 'col-collection-failure')!.textContent).toBe(t('hosts.collectionNoFailure'));
    expect(cellOf(0, 'col-collection-failure')!.querySelector('.el-tag--danger')).toBeNull();
  });

  it('探测成功也不改写主机状态 / Agent 状态 / 健康状态（未确认就还是未确认）', async () => {
    listCollectionStatesMock.mockResolvedValue(
      collectionResponse([
        collectionState(11, {
          source: 'SSH_PROBE',
          result: 'SUCCESS',
          freshness: 'FRESH',
          observedStatus: 'RUNNING',
          observedHealth: 'HEALTHY',
        }),
      ]) as never,
    );

    await mountView();

    // 11 号主机后端记录的是 UNKNOWN：主机状态 + Agent 状态 + 派生健康状态三格保持中性
    const unknownCells = rowCells(0)!.querySelectorAll('.status-cell[data-status-code="UNKNOWN"]');
    expect(unknownCells.length).toBe(3);

    // 主动探测的观测结果不得冒充 Agent 心跳或健康结论。
    // 只在本行内断言：另一行自带的 agentStatus=ONLINE 是被测样本的一部分，与采集无关。
    expect(rowCells(0)!.querySelector('.status-cell[data-status-code="ONLINE"]')).toBeNull();
    expect(rowCells(0)!.querySelector('.status-cell[data-status-code="HEALTHY"]')).toBeNull();
    expect(rowCells(0)!.querySelector('.status-cell[data-status-code="RUNNING"]')).toBeNull();
  });

  it('采集未启用时给出明确说明，且不说成主机离线', async () => {
    listCollectionStatesMock.mockResolvedValue(collectionResponse([], false) as never);

    await mountView();

    const alert = document.body.querySelector<HTMLElement>('.collection-alert');
    expect(alert, '采集关闭必须有可见说明，不能悄悄不更新').not.toBeNull();
    expect(alert!.textContent).toContain(t('hosts.collectionDisabledNotice'));
    expect(alert!.textContent).not.toContain('离线');
    expect(alert!.textContent).not.toContain(t('statusSemantics.value.hostStatus.OFFLINE'));
  });

  it('按本页主机 id 只读查询采集状态：一个目标一次，且不带任何写操作', async () => {
    await mountView();

    expect(listCollectionStatesMock).toHaveBeenCalledWith('HOST', [11, 12]);
  });

  it('R1：后端改判主机状态后，原状态列与派生健康在同一轮里跟着变，不停在进页面时的旧值', async () => {
    await mountView();

    // 进页面时后端记录的是 UNKNOWN（默认桩），页面上就是 UNKNOWN
    expect(rowCells(0)!.querySelector('.status-cell[data-status-code="UNKNOWN"]')).not.toBeNull();
    expect(rowCells(0)!.querySelector('.status-cell[data-status-code="OFFLINE"]')).toBeNull();

    // 采集写回把后端这一台改判为 OFFLINE/DOWN；界面这一轮重读列表必须跟着变
    pageAssetHostsMock.mockImplementation(async (params) => {
      listCalls.push({ ...params });
      return {
        pageNum: 1,
        pageSize: 10,
        total: 2,
        records: [{ ...UNKNOWN_HOST, hostStatus: 'OFFLINE' }, WEIRD_HOST],
      } as never;
    });
    getHostProfileMock.mockImplementation(async (id: number) => ({
      hostId: id,
      hostname: id === 11 ? 'web-01' : 'legacy-01',
      ip: '192.0.2.4',
      envName: '开发环境',
      resourcePoolName: null,
      runtimeInstanceCount: 0,
      healthStatus: id === 11 ? 'DOWN' : 'UNKNOWN',
      healthReason: id === 11 ? '主机离线' : '主机状态未知，尚未确认',
      latestDeployAt: null,
      latestDeployStatus: null,
      latestMonitorSnapshotAt: null,
    }) as never);

    // 走的是与自动 tick 完全相同的协调器路径（runRound → reloadList）
    await wrapper!.find('.refresh-list-btn').trigger('click');
    await settle();

    expect(
      rowCells(0)!.querySelector('.status-cell[data-status-code="OFFLINE"]'),
      '原状态列必须跟着后端的新判定走',
    ).not.toBeNull();
    expect(
      rowCells(0)!.querySelector('.status-cell[data-status-code="DOWN"]'),
      '同一轮里的派生健康也要跟着变',
    ).not.toBeNull();

    // 列表确实被重读过（不是只重读了采集状态那一侧）
    expect(listCalls.length).toBeGreaterThanOrEqual(2);
  });
});
