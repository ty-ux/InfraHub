import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { nextTick } from 'vue';
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils';
import ElementPlus from 'element-plus';
import { createPinia, setActivePinia } from 'pinia';
import { createMemoryHistory, createRouter, type Router } from 'vue-router';
import { i18n, setLocale } from '@/i18n';
import RuntimeInstancesView from '../RuntimeInstancesView.vue';
import { pageRuntimeServiceInstances, getRuntimeServiceInstanceLogs, getRuntimeServiceInstanceDeployConfig, type RuntimeServiceInstance, type RuntimeServiceInstanceLogs } from '@/api/runtime';
import RuntimeLogPanel from '@/components/resource/RuntimeLogPanel.vue';
import { pageAssetHosts, type AssetHost } from '@/api/asset';
import { listCollectionStates, type CollectionStateView } from '@/api/monitorCollection';

/**
 * 主机筛选「已选值全文入口」组件级自测。
 *
 * 背景：筛选栏的主机选择框固定 240px 宽，长的「主机名 (IP)」在框内被省略号截断。此前的完整值
 * 只挂在“下拉选项”的原生 title 上，必须把鼠标移到选项上才能看到；选择框本身既没有鼠标入口，
 * 也没有键盘入口，纯键盘用户无法得知当前选的是哪台主机。
 *
 * 本用例覆盖本次补充的入口与随之而来的数据一致性：
 *  1) hover（鼠标）与 focus（键盘 Tab 进选择框）都能读到完整已选值；
 *  2) 提示内容始终等于当前 hostId 在 hostOptions 中命中的那一条（选择 / A→B 切换 / 清空后无过期值、无 undefined）；
 *  3) 未选择、hostId 解析不到主机、主机缺 hostname 时不渲染任何提示（不出现「主机 999」这类占位提示）。
 *
 * 边界说明（避免把结论说过头）：
 *  - 接口全部 mock，不连真实后端，只能得出前端范围内的结论；
 *  - happy-dom 不计算真实排版，无法断言视觉省略号，因此不断言“看起来没被截断”；
 *  - happy-dom 里 EP 的 tooltip 关闭依赖的 Transition 不会真正结束，已弹出的 popper 元素会残留在 DOM 中，
 *    所以“是否弹出”以 popper 内是否有文本为准；未选择 / id 无法解析这两种「从未弹出」的场景可直接断言
 *    页面上没有任何深色 popper，是 DOM 层的硬证据；
 *  - EP 的 hover 触发走延迟切换（定时器），断言前需要让定时器跑完。
 */
vi.mock('@/api/runtime', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/runtime')>();
  return { ...actual, pageRuntimeServiceInstances: vi.fn(), getRuntimeServiceInstanceLogs: vi.fn(), getRuntimeServiceInstanceDeployConfig: vi.fn() };
});

vi.mock('@/api/asset', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/asset')>();
  return { ...actual, pageAssetHosts: vi.fn() };
});

// 服务 / 环境下拉在本次改动范围之外，固定返回空列表，避免真实请求带来的噪声
vi.mock('@/api/resource', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/resource')>();
  const emptyPage = async () => ({ pageNum: 1, pageSize: 200, total: 0, records: [] });
  return {
    ...actual,
    serviceApi: { ...actual.serviceApi, page: vi.fn(emptyPage) },
    envApi: { ...actual.envApi, page: vi.fn(emptyPage) },
  };
});

// 采集状态列读的是后台**已经记录**的结果，界面轮询只重读数据库；
// 这里固定不连真实后端，也不会触发任何探测。
vi.mock('@/api/monitorCollection', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/monitorCollection')>();
  return { ...actual, listCollectionStates: vi.fn() };
});

function hostFixture(id: number, hostname: string, ip: string): AssetHost {
  return {
    id,
    hostname,
    ip,
    osType: 'LINUX',
    cpuCores: 4,
    memoryGb: 8,
    diskGb: 100,
    hostType: 'PHYSICAL',
    hostStatus: 'ONLINE',
    createdAt: '2026-01-01T00:00:00',
    updatedAt: '2026-01-01T00:00:00',
  };
}

// 合法长度样本：主机名 115 字符（拼上 IP 共 128 字符），远超 240px 选择框的可见宽度
const LONG_HOSTNAME = `host-${'a'.repeat(110)}`;
const LONG_HOST = hostFixture(7, LONG_HOSTNAME, '192.0.2.8');
const SHORT_HOST = hostFixture(8, 'web-01', '192.0.2.9');
const NO_NAME_HOST = hostFixture(9, '', '192.0.2.10');
const HOSTS = [LONG_HOST, SHORT_HOST, NO_NAME_HOST];
const LONG_LABEL = `${LONG_HOSTNAME} (192.0.2.8)`;
const SHORT_LABEL = 'web-01 (192.0.2.9)';

/** 采集状态列覆盖到的字段：默认"没有采集记录"，个别用例再按需要喂真实取值 */
const collectionState = (targetId: number, patch: Partial<CollectionStateView> = {}): CollectionStateView => ({
  targetType: 'SERVICE_INSTANCE',
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

const listCalls: Record<string, unknown>[] = [];
let wrapper: VueWrapper | undefined;
let router: Router;

const wait = (ms: number) => new Promise((resolve) => setTimeout(resolve, ms));

async function settle() {
  await flushPromises();
  await nextTick();
  await flushPromises();
}

async function mountView(query: Record<string, string> = {}) {
  setLocale('zh-CN');
  const pinia = createPinia();
  setActivePinia(pinia);
  router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', component: { template: '<div />' } },
      { path: '/resource/hosts', component: { template: '<div />' } },
    ],
  });
  await router.push({ path: '/', query });
  await router.isReady();
  wrapper = mount(RuntimeInstancesView, {
    global: { plugins: [ElementPlus, i18n, pinia, router] },
    attachTo: document.body,
  });
  await settle();
}

// 页面里 el-tooltip 不止一个：el-select 自带的内部 tooltip（trigger 为 'click'）也在此列。
// 本次新增的那个是唯一用数组 trigger（hover + focus）的，据此定位。
function hostTip() {
  const candidates = wrapper!.findAllComponents({ name: 'ElTooltip' }).filter(
    (c) => Array.isArray(c.props('trigger')) && (c.props('trigger') as string[]).includes('hover'),
  );
  if (candidates.length !== 1) throw new Error(`主机筛选的全文提示应当唯一，实际找到 ${candidates.length} 个`);
  return candidates[0];
}

// 深色全文 tooltip 的 popper（el-select 自己的下拉 popper 排除在外）
function darkTips(): HTMLElement[] {
  return Array.from(document.body.querySelectorAll<HTMLElement>('.el-popper.el-tooltip')).filter(
    (el) => !el.classList.contains('el-select__popper') && !el.querySelector('.el-select-dropdown'),
  );
}

// 只取真正带文本的提示：happy-dom 里关闭的 popper 会残留，空文本不算“弹出了提示”
function visibleTipTexts(): string[] {
  return darkTips()
    .map((el) => (el.textContent || '').trim())
    .filter((text) => text.length > 0);
}

async function hoverHostFilter() {
  await wrapper!.find('.filter-host').trigger('mouseenter');
  await settle();
  if (darkTips().length === 0) {
    // EP 的 hover 触发是延迟切换，让定时器跑完再判断
    await wait(50);
    await settle();
  }
}

async function focusHostFilter() {
  const input = wrapper!.find('.filter-host input').element as HTMLInputElement;
  // 点过下拉之后焦点本就落在选择框内，同一个元素上再次 focus 不会再触发 focus 事件；
  // 真实键盘路径也是「焦点离开后再回来」，所以先 blur 一次再 focus。
  input.blur();
  await settle();
  input.focus();
  await settle();
  if (darkTips().length === 0) {
    await wait(50);
    await settle();
  }
}

function hostDropdownItems(): HTMLElement[] {
  const dropdowns = Array.from(document.body.querySelectorAll<HTMLElement>('.el-select-dropdown'));
  const hostDropdown = dropdowns.find((d) => (d.textContent || '').includes(LONG_HOSTNAME));
  if (!hostDropdown) throw new Error('没有找到主机下拉');
  return Array.from(hostDropdown.querySelectorAll<HTMLElement>('.el-select-dropdown__item'));
}

// 按真实路径选择主机：点开选择框 → 点下拉项（顺序与 hostOptions 一致：7 / 8 / 9）
async function selectHostOption(index: number) {
  await wrapper!.find('.filter-host .el-select__wrapper').trigger('click');
  await settle();
  const items = hostDropdownItems();
  if (!items[index]) throw new Error(`主机下拉没有第 ${index + 1} 项（共 ${items.length} 项）`);
  items[index].click();
  await settle();
}

beforeEach(() => {
  vi.spyOn(console, 'error').mockImplementation(() => undefined);
  vi.spyOn(console, 'warn').mockImplementation(() => undefined);
  listCalls.length = 0;
  vi.mocked(pageAssetHosts).mockResolvedValue({ pageNum: 1, pageSize: 200, total: HOSTS.length, records: HOSTS });
  vi.mocked(listCollectionStates).mockReset();
  // 默认：本页实例都还没有采集记录
  vi.mocked(listCollectionStates).mockResolvedValue(collectionResponse([]) as never);
  vi.mocked(pageRuntimeServiceInstances).mockImplementation(async (params) => {
    // 记录调用当时的快照：queryParams 是响应式对象，保存引用会随之后的改动一起变化
    listCalls.push({ ...params });
    return { pageNum: 1, pageSize: 10, total: 0, records: [] as RuntimeServiceInstance[] };
  });
});

afterEach(() => {
  wrapper?.unmount();
  wrapper = undefined;
  document.body.innerHTML = '';
  setLocale('zh-CN');
  vi.restoreAllMocks();
});

describe('RuntimeInstancesView 主机筛选已选值全文入口', () => {
  it('未选择主机时不渲染任何全文提示，鼠标与键盘都不弹', async () => {
    await mountView();

    expect(hostTip().props('trigger')).toEqual(['hover', 'focus']);
    expect(hostTip().props('content')).toBe('');
    expect(hostTip().props('disabled')).toBe(true);

    await hoverHostFilter();
    await focusHostFilter();

    expect(darkTips()).toHaveLength(0);
  });

  it('长主机名（115 字符含 IP）选中后，hover 即可读到完整已选值', async () => {
    expect(LONG_HOSTNAME).toHaveLength(115);
    await mountView({ hostId: '7' });

    expect(hostTip().props('disabled')).toBe(false);
    expect(hostTip().props('content')).toBe(LONG_LABEL);

    await hoverHostFilter();

    expect(visibleTipTexts()).toEqual([LONG_LABEL]);
    // 选择框内仍是完整文本（框内省略是 CSS 行为，其宽度与截断本次未改动）
    expect(wrapper!.find('.filter-host').text()).toContain(LONG_LABEL);
  });

  it('长主机名（115 字符含 IP）选中后，纯键盘 focus 也能读到完整已选值', async () => {
    await mountView({ hostId: '7' });

    // 只 focus，不发生任何鼠标事件
    await focusHostFilter();

    expect(visibleTipTexts()).toEqual([LONG_LABEL]);
  });

  it('短值主机选中后提示内容与当前 hostId 一致，不拼接多余内容', async () => {
    await mountView();

    await selectHostOption(1);

    expect(hostTip().props('content')).toBe(SHORT_LABEL);
    await focusHostFilter();
    expect(visibleTipTexts()).toEqual([SHORT_LABEL]);
  });

  it('A→B 切换后提示只显示 B，不残留 A 的过期名称', async () => {
    await mountView();

    await selectHostOption(0);
    await focusHostFilter();
    expect(visibleTipTexts()).toEqual([LONG_LABEL]);

    await selectHostOption(1);

    expect(hostTip().props('content')).toBe(SHORT_LABEL);
    await focusHostFilter();
    expect(visibleTipTexts()).toEqual([SHORT_LABEL]);
    expect(visibleTipTexts().some((text) => text.includes(LONG_HOSTNAME))).toBe(false);
  });

  it('清空后提示为空且被禁用：不再弹出带文本的提示，也不出现 undefined', async () => {
    await mountView();

    await selectHostOption(0);
    expect(hostTip().props('content')).toBe(LONG_LABEL);

    // 清空按钮只在交互态渲染（isFocused || inputHovering），因此先让选择框处于 hover/focus
    await focusHostFilter();
    const clear = wrapper!.find('.filter-host .el-select__clear');
    expect(clear.exists()).toBe(true);
    await clear.trigger('click');
    await settle();

    expect(hostTip().props('content')).toBe('');
    expect(hostTip().props('disabled')).toBe(true);
    expect(wrapper!.find('.filter-host').text()).not.toContain('undefined');

    // 清空后再交互，不得再弹出任何带文本的提示（并确认此时页面上已有过弹层，断言不是空跑）
    expect(darkTips().length).toBeGreaterThan(0);
    await focusHostFilter();
    await hoverHostFilter();
    expect(visibleTipTexts()).toEqual([]);
    expect(darkTips().map((el) => (el.textContent || '').trim())).not.toContain(LONG_LABEL);
  });

  it('hostId 解析不到主机时不显示占位提示', async () => {
    await mountView({ hostId: '999' });

    expect(hostTip().props('content')).toBe('');
    expect(hostTip().props('disabled')).toBe(true);

    await hoverHostFilter();
    await focusHostFilter();

    expect(darkTips()).toHaveLength(0);
  });

  it('主机缺少 hostname 时不给「-」提示', async () => {
    await mountView();

    await selectHostOption(2);

    expect(hostTip().props('content')).toBe('');
    expect(hostTip().props('disabled')).toBe(true);
    expect(visibleTipTexts()).toEqual([]);
  });

  it('选中主机不改变筛选请求参数：键集合、hostId 与分页重置保持原行为', async () => {
    await mountView();

    expect(listCalls).toHaveLength(1);
    expect(Object.keys(listCalls[0]).sort()).toEqual([
      'envId',
      'healthStatus',
      'hostId',
      'instanceStatus',
      'keyword',
      'pageNum',
      'pageSize',
      'serviceId',
    ]);
    expect(listCalls[0].hostId).toBeUndefined();

    await selectHostOption(0);

    expect(listCalls).toHaveLength(2);
    expect(listCalls[1].hostId).toBe(7);
    expect(listCalls[1].pageNum).toBe(1);
    expect(listCalls[1].keyword).toBe('');
  });

  it('筛选栏结构未被额外节点改变：主机选择框仍是筛选栏的直接子元素', async () => {
    await mountView();

    const filterBar = wrapper!.find('.filter-bar');
    const hostSelect = wrapper!.find('.filter-host');
    expect(hostSelect.element.parentElement).toBe(filterBar.element);

    const selects = filterBar.findAllComponents({ name: 'ElSelect' });
    expect(selects).toHaveLength(5);
    expect(selects[2].classes()).toContain('filter-host');
  });
});

/**
 * 采集五列在实例列表上的表现。
 *
 * 关键边界：实例的「运行状态 / 健康状态」是台账字段，采集列只描述"后台这一次采集做得怎么样"，
 * 两者不得互相顶替——即使探测到进程不存在，也不能反过来把实例的运行状态改成"未运行"。
 */
describe('RuntimeInstancesView 采集状态列', () => {
  const t = (key: string) => i18n.global.t(key) as string;

  const instanceFixture = (
    id: number,
    patch: Partial<RuntimeServiceInstance> = {},
  ): RuntimeServiceInstance => ({
    id,
    serviceId: 1,
    envId: 1,
    hostId: 8,
    instanceName: `svc-${id}`,
    instanceType: 'JAVA',
    instanceStatus: 'RUNNING',
    healthStatus: 'UNKNOWN',
    // 默认"没有心跳记录"：该字段在契约里是可缺省的（没有记录就是不写），不是 null
    createdAt: '2026-01-01T00:00:00',
    updatedAt: '2026-01-01T00:00:00',
    ...patch,
  });

  const INSTANCES: RuntimeServiceInstance[] = [
    instanceFixture(101),
    instanceFixture(102, { lastHeartbeat: '2026-01-02T03:04:05' }),
    instanceFixture(103),
  ];

  const rowCells = (rowIndex: number) =>
    Array.from(document.body.querySelectorAll<HTMLElement>('.el-table__row'))[rowIndex] ?? null;

  const cellOf = (rowIndex: number, columnClass: string) =>
    rowCells(rowIndex)?.querySelector<HTMLElement>(`.${columnClass}`) ?? null;

  const codeOf = (cell: HTMLElement | null) =>
    cell?.querySelector<HTMLElement>('.status-cell')?.getAttribute('data-status-code') ?? null;

  beforeEach(() => {
    vi.mocked(pageRuntimeServiceInstances).mockImplementation(async (params) => {
      listCalls.push({ ...params });
      return { pageNum: 1, pageSize: 10, total: INSTANCES.length, records: INSTANCES };
    });
  });

  it('按后台记录展示来源、新鲜度、结果与最近成功观察时间', async () => {
    vi.mocked(listCollectionStates).mockResolvedValue(
      collectionResponse([
        collectionState(101, { source: 'SSH_PROBE', freshness: 'FRESH', result: 'SUCCESS' }),
        collectionState(102, {
          source: 'AGENT_REPORT',
          freshness: 'STALE',
          result: 'FAILED',
          errorCategory: 'HEALTH_ENDPOINT_ERROR',
          errorSummary: '/health 返回 500',
        }),
        collectionState(103, {
          source: 'NONE',
          freshness: 'NONE',
          result: 'SKIPPED_UNCONFIGURED',
          configPresent: false,
          // 没配采集就谈不上"成功观察过"，时间字段本来就不该有值
          lastAttemptAt: null,
          lastSuccessAt: null,
        }),
      ]) as never,
    );

    await mountView();
    await settle();

    expect(codeOf(cellOf(0, 'col-collection-source'))).toBe('SSH_PROBE');
    expect(codeOf(cellOf(0, 'col-collection-freshness'))).toBe('FRESH');
    expect(codeOf(cellOf(0, 'col-collection-result'))).toBe('SUCCESS');
    expect(cellOf(0, 'col-collection-last-success')!.textContent).toMatch(/\d{4}/);

    expect(codeOf(cellOf(1, 'col-collection-source'))).toBe('AGENT_REPORT');
    expect(codeOf(cellOf(1, 'col-collection-freshness'))).toBe('STALE');
    expect(codeOf(cellOf(1, 'col-collection-result'))).toBe('FAILED');
    expect(codeOf(cellOf(1, 'col-collection-failure'))).toBe('HEALTH_ENDPOINT_ERROR');

    // 没配采集的目标：明说"没配"，既不是失败，也不是离线
    expect(codeOf(cellOf(2, 'col-collection-result'))).toBe('SKIPPED_UNCONFIGURED');
    expect(cellOf(2, 'col-collection-last-success')!.textContent).toBe(t('statusSemantics.notRecorded'));
  });

  it('两类「未配置」在页面上分得开：缺 SSH 配置 / 缺诊断参数，且都不算失败', async () => {
    vi.mocked(listCollectionStates).mockResolvedValue(
      collectionResponse([
        collectionState(101, {
          source: 'NONE',
          freshness: 'NONE',
          result: 'SKIPPED_UNCONFIGURED',
          configPresent: false,
          errorCategory: 'CONFIG_MISSING',
          errorSummary: '目标主机(192.0.2.11)未配置 SSH 连接信息',
          lastAttemptAt: null,
          lastSuccessAt: null,
        }),
        collectionState(102, {
          source: 'NONE',
          freshness: 'NONE',
          result: 'SKIPPED_UNCONFIGURED',
          // SSH 连接配置是在的，缺的只是诊断参数 —— 这时 configPresent 必须照实为 true，
          // 不能因为"这次没采成"就反过来把它说成"没有连接配置"
          configPresent: true,
          errorCategory: 'DIAGNOSTIC_CONFIG_MISSING',
          errorSummary: '服务(svc-102)未配置部署诊断参数',
          lastAttemptAt: null,
          lastSuccessAt: null,
        }),
        collectionState(103, { result: 'FAILED', errorCategory: 'AUTH_FAILED' }),
      ]) as never,
    );

    await mountView();
    await settle();

    // 两行都是"跳过"，页面区分它们的唯一依据就是失败原因列的分类码
    const sshMissingCell = cellOf(0, 'col-collection-failure')!;
    const diagnosticsMissingCell = cellOf(1, 'col-collection-failure')!;
    expect(codeOf(cellOf(0, 'col-collection-result'))).toBe('SKIPPED_UNCONFIGURED');
    expect(codeOf(cellOf(1, 'col-collection-result'))).toBe('SKIPPED_UNCONFIGURED');
    expect(codeOf(sshMissingCell)).toBe('CONFIG_MISSING');
    expect(codeOf(diagnosticsMissingCell)).toBe('DIAGNOSTIC_CONFIG_MISSING');

    // 文案必须是两句不同的话：使用者据此决定去补 SSH 认证，还是去补 deploy.root / deploy.port
    const sshMissingText = (sshMissingCell.textContent ?? '').trim();
    const diagnosticsMissingText = (diagnosticsMissingCell.textContent ?? '').trim();
    expect(sshMissingText).toContain(t('statusSemantics.value.collectionErrorCategory.CONFIG_MISSING'));
    expect(diagnosticsMissingText).toContain(
      t('statusSemantics.value.collectionErrorCategory.DIAGNOSTIC_CONFIG_MISSING'),
    );
    expect(sshMissingText).not.toBe(diagnosticsMissingText);

    // 结果列不能替使用者断定"没有连接配置"：缺诊断参数时主机连接本身是好的
    expect(cellOf(1, 'col-collection-result')!.textContent).not.toContain('连接配置');
    // 说明里要明说"主机连接没问题"，否则会有人去白改一遍 SSH 配置
    expect(diagnosticsMissingCell.querySelector('.status-cell')!.getAttribute('title')).toContain(
      t('statusSemantics.note.collectionErrorDiagnosticConfigMissing'),
    );

    // 两类"未配置"都不是失败，不给失败色；同页的真失败（认证失败）必须给，作为对照
    expect(sshMissingCell.querySelector('.el-tag--danger')).toBeNull();
    expect(diagnosticsMissingCell.querySelector('.el-tag--danger')).toBeNull();
    expect(cellOf(2, 'col-collection-failure')!.querySelector('.el-tag--danger')).not.toBeNull();

    // 英文下同样两句分开、都不是 key
    setLocale('en-US');
    await settle();
    const enSsh = (cellOf(0, 'col-collection-failure')!.textContent ?? '').trim();
    const enDiagnostics = (cellOf(1, 'col-collection-failure')!.textContent ?? '').trim();
    expect(enDiagnostics).toContain(t('statusSemantics.value.collectionErrorCategory.DIAGNOSTIC_CONFIG_MISSING'));
    expect(enDiagnostics).not.toContain('statusSemantics.');
    expect(enSsh).not.toContain('statusSemantics.');
    expect(enSsh).not.toBe(enDiagnostics);

    setLocale('zh-CN');
    await settle();
  });

  it('没有采集记录时中性说明「未记录」，不伪造时间也不判为失败', async () => {
    await mountView();
    await settle();

    const source = cellOf(0, 'col-collection-source');
    expect(source!.textContent).toContain(t('statusSemantics.notRecorded'));
    expect(source!.querySelector('.el-tag'), '没有记录时不该出现任何状态标签').toBeNull();

    const lastSuccess = cellOf(0, 'col-collection-last-success')!;
    expect(lastSuccess.textContent).toBe(t('statusSemantics.notRecorded'));
    expect(lastSuccess.textContent).not.toMatch(/\d{4}/);

    expect(cellOf(0, 'col-collection-failure')!.textContent).toBe(t('runtimeInstances.collectionNoFailure'));
    expect(cellOf(0, 'col-collection-failure')!.querySelector('.el-tag--danger')).toBeNull();
  });

  it('采集结果不改写实例的运行状态与健康状态（进程不存在也不等于实例未运行）', async () => {
    vi.mocked(listCollectionStates).mockResolvedValue(
      collectionResponse([
        collectionState(102, {
          result: 'FAILED',
          errorCategory: 'PROCESS_ABSENT',
          freshness: 'FRESH',
          observedStatus: 'STOPPED',
          observedHealth: 'UNHEALTHY',
        }),
      ]) as never,
    );

    await mountView();
    await settle();

    const row = rowCells(1)!;
    expect(
      row.querySelector('.status-cell[data-status-code="RUNNING"]'),
      '台账运行状态必须保持原样',
    ).not.toBeNull();
    expect(row.querySelector('.status-cell[data-status-code="UNHEALTHY"]')).toBeNull();
    expect(row.querySelectorAll('.status-cell[data-status-code="STOPPED"]').length).toBe(0);
    expect(codeOf(cellOf(1, 'col-collection-failure'))).toBe('PROCESS_ABSENT');
  });

  it('采集未启用时给出明确说明，且不说成实例离线', async () => {
    vi.mocked(listCollectionStates).mockResolvedValue(collectionResponse([], false) as never);

    await mountView();
    await settle();

    const alert = document.body.querySelector<HTMLElement>('.collection-alert');
    expect(alert, '采集关闭必须有可见说明，不能悄悄不更新').not.toBeNull();
    expect(alert!.textContent).toContain(t('runtimeInstances.collectionDisabledNotice'));
    expect(alert!.textContent).not.toContain('离线');
  });

  it('按本页实例 id 只读查询采集状态', async () => {
    await mountView();
    await settle();

    expect(listCollectionStates).toHaveBeenCalledWith('SERVICE_INSTANCE', [101, 102, 103]);
  });

  it('R1：后端改判实例为 STOPPED / DOWN 后，原状态列与健康列在同一轮里跟着变', async () => {
    await mountView();
    await settle();

    // 进页面时后端记录的是 RUNNING，页面上就是 RUNNING
    const firstRow = () => rowCells(0)!;
    expect(firstRow().querySelector('.status-cell[data-status-code="RUNNING"]')).not.toBeNull();
    expect(firstRow().querySelector('.status-cell[data-status-code="STOPPED"]')).toBeNull();

    // 采集写回把这一台改判为 STOPPED / DOWN；界面这一轮重读列表必须跟着变
    vi.mocked(pageRuntimeServiceInstances).mockImplementation(async (params) => {
      listCalls.push({ ...params });
      return {
        pageNum: 1,
        pageSize: 10,
        total: INSTANCES.length,
        records: [
          { ...INSTANCES[0], instanceStatus: 'STOPPED', healthStatus: 'DOWN' },
          INSTANCES[1],
          INSTANCES[2],
        ],
      };
    });

    // 走的是与自动 tick 完全相同的协调器路径（runRound → reloadList）
    await wrapper!.find('.refresh-list-btn').trigger('click');
    await settle();

    expect(
      firstRow().querySelector('.status-cell[data-status-code="STOPPED"]'),
      '实例运行状态必须跟着后端的新判定走，不能停在进页面时的 RUNNING',
    ).not.toBeNull();
    expect(
      firstRow().querySelector('.status-cell[data-status-code="DOWN"]'),
      '同一轮里的健康判定也要跟着变',
    ).not.toBeNull();
    expect(listCalls.length).toBeGreaterThanOrEqual(2);
  });
});


describe('RuntimeInstancesView 日志目标隔离', () => {
  const rows = [1, 2].map(id => ({ id, instanceName: `logs-${id}`, serviceId: 15, envId: 30, hostId: 8 } as RuntimeServiceInstance));
  const evidence = (id: number, content: string) => ({ instanceId: id, serviceId: 15, envId: 30, success: true, content, lines: 200 } as RuntimeServiceInstanceLogs);
  async function rowAction(index: number, label: string) {
    const row = document.body.querySelectorAll('.el-table__row')[index]!;
    const action = Array.from(row.querySelectorAll('button')).find(button => button.textContent?.trim() === label)!;
    action.click();
    await settle();
  }
  beforeEach(() => {
    vi.mocked(pageRuntimeServiceInstances).mockResolvedValue({ pageNum: 1, pageSize: 10, total: rows.length, records: rows });
    vi.mocked(getRuntimeServiceInstanceLogs).mockReset();
    vi.mocked(getRuntimeServiceInstanceDeployConfig).mockResolvedValue({} as never);
  });
  it('ignores an old instance response that arrives after a different instance was opened', async () => {
    let resolveOld!: (value: RuntimeServiceInstanceLogs) => void;
    vi.mocked(getRuntimeServiceInstanceLogs)
      .mockImplementationOnce(() => new Promise(resolve => { resolveOld = resolve; }))
      .mockResolvedValueOnce(evidence(2, 'instance B latest'));
    await mountView();
    await rowAction(0, '运行日志');
    await rowAction(1, '运行日志');
    resolveOld(evidence(1, 'instance A stale'));
    await settle();
    expect(wrapper!.findComponent(RuntimeLogPanel).props('content')).toBe('instance B latest');
    expect(wrapper!.findComponent(RuntimeLogPanel).props('loading')).toBe(false);
  });
  it('keeps log refresh scoped to its own instance after another diagnostic dialog changes target', async () => {
    vi.mocked(getRuntimeServiceInstanceLogs).mockResolvedValue(evidence(1, 'instance A log'));
    await mountView();
    await rowAction(0, '运行日志');
    await rowAction(1, '部署配置');
    wrapper!.findComponent(RuntimeLogPanel).vm.$emit('refresh');
    await settle();
    expect(getRuntimeServiceInstanceLogs).toHaveBeenLastCalledWith(1, 200);
  });
});
