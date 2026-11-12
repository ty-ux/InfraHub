import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount, type DOMWrapper, type VueWrapper } from '@vue/test-utils';
import ElementPlus from 'element-plus';
import { createPinia, setActivePinia } from 'pinia';
import { createMemoryHistory, createRouter, type Router } from 'vue-router';
import { i18n, setLocale } from '@/i18n';
import RuntimeInstancesView from '../RuntimeInstancesView.vue';

/**
 * 运行实例页「状态解释」组件级自测。
 *
 * 目的：证明页面把四类状态字段的来源讲清楚了，并且没有把「已记录的状态」当成实时结果、
 * 也没有把历史值 / 认不出的取值折算成成功或失败。
 *
 * 边界说明：接口全部 mock，只验证前端渲染与请求行为，不连真实后端、不做真实 SSH 探测。
 */

const pageRuntimeServiceInstances = vi.fn();
const getRuntimeServiceInstanceResourceView = vi.fn();
const getRuntimeServiceInstanceStatus = vi.fn();
const getRuntimeServiceInstanceDeployConfig = vi.fn();
const getRuntimeServiceInstanceLogs = vi.fn();
const pageAssetHosts = vi.fn();

vi.mock('@/api/runtime', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/runtime')>();
  return {
    ...actual,
    pageRuntimeServiceInstances: (...args: unknown[]) => pageRuntimeServiceInstances(...args),
    getRuntimeServiceInstanceResourceView: (...args: unknown[]) => getRuntimeServiceInstanceResourceView(...args),
    getRuntimeServiceInstanceStatus: (...args: unknown[]) => getRuntimeServiceInstanceStatus(...args),
    getRuntimeServiceInstanceDeployConfig: (...args: unknown[]) => getRuntimeServiceInstanceDeployConfig(...args),
    getRuntimeServiceInstanceLogs: (...args: unknown[]) => getRuntimeServiceInstanceLogs(...args),
  };
});

vi.mock('@/api/asset', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/asset')>();
  return { ...actual, pageAssetHosts: (...args: unknown[]) => pageAssetHosts(...args) };
});

vi.mock('@/api/resource', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/resource')>();
  const emptyPage = async () => ({ pageNum: 1, pageSize: 200, total: 0, records: [] });
  return {
    ...actual,
    serviceApi: { ...actual.serviceApi, page: vi.fn(emptyPage) },
    envApi: { ...actual.envApi, page: vi.fn(emptyPage) },
  };
});

interface Row {
  id: number;
  instanceName: string;
  instanceType: string;
  instanceStatus: string;
  healthStatus: string | null;
  lastHeartbeat: string | null;
}

const LEGACY_ONLINE: Row = {
  id: 101,
  instanceName: 'legacy-online',
  instanceType: 'JAVA',
  // 历史遗留取值：不在现行实例状态枚举内，且不等于 RUNNING
  instanceStatus: 'ONLINE',
  healthStatus: 'UNKNOWN',
  lastHeartbeat: null,
};

const UNRECOGNIZED: Row = {
  id: 102,
  instanceName: 'weird-status',
  instanceType: 'JAVA',
  instanceStatus: 'SOMETHING_NEW',
  healthStatus: 'UP',
  lastHeartbeat: '2026-01-02T03:04:05',
};

const RUNNING: Row = {
  id: 103,
  instanceName: 'normal',
  instanceType: 'JAVA',
  instanceStatus: 'RUNNING',
  healthStatus: 'UP',
  lastHeartbeat: null,
};

const ROWS = [LEGACY_ONLINE, UNRECOGNIZED, RUNNING];

const listCalls: Record<string, unknown>[] = [];
let wrapper: VueWrapper | undefined;
let router: Router;

async function settle() {
  await flushPromises();
  await flushPromises();
  await new Promise((resolve) => setTimeout(resolve, 0));
  await flushPromises();
}

async function mountView() {
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
  await router.push('/');
  await router.isReady();
  wrapper = mount(RuntimeInstancesView, {
    global: { plugins: [ElementPlus, i18n, pinia, router] },
    attachTo: document.body,
  });
  await settle();
}

const t = (key: string) => i18n.global.t(key) as string;

function rows(): HTMLElement[] {
  return Array.from(document.body.querySelectorAll<HTMLElement>('.el-table__body tbody tr'));
}

function rowByName(name: string): HTMLElement {
  const found = rows().find((r) => (r.textContent || '').includes(name));
  if (!found) throw new Error(`表格里找不到实例 ${name}`);
  return found;
}

/** 行内某个状态字段的单元格元素（data-status-code 由 StatusTag 提供，取值逐字保留） */
function statusCell(row: HTMLElement, code: string): HTMLElement | undefined {
  return Array.from(row.querySelectorAll<HTMLElement>('.status-cell')).find(
    (cell) => cell.getAttribute('data-status-code') === code
  );
}

function legendButton(): DOMWrapper<Element> {
  const buttons = wrapper!.findAll('button');
  const found = buttons.find((b) => (b.text() || '').includes(t('runtimeInstances.legendButton')));
  if (!found) throw new Error('找不到状态说明入口');
  return found;
}

function refreshButton(): DOMWrapper<Element> {
  const found = wrapper!.find('button.refresh-list-btn');
  if (!found.exists()) throw new Error('找不到刷新列表按钮');
  return found;
}

beforeEach(() => {
  setLocale('zh-CN');
  listCalls.length = 0;
  pageRuntimeServiceInstances.mockReset();
  getRuntimeServiceInstanceResourceView.mockReset();
  getRuntimeServiceInstanceStatus.mockReset();
  getRuntimeServiceInstanceDeployConfig.mockReset();
  getRuntimeServiceInstanceLogs.mockReset();
  pageAssetHosts.mockReset();

  pageRuntimeServiceInstances.mockImplementation(async (query: Record<string, unknown>) => {
    listCalls.push(query);
    return { pageNum: 1, pageSize: 20, total: ROWS.length, records: ROWS.map((row) => ({ ...row })) };
  });
  // 101 有记录但没有部署/监控字段 -> 时间与状态都应为「未记录」；
  // 102 有部署与监控记录 -> 应按记录展示；103 取不到 resource view -> 一律「未记录」。
  getRuntimeServiceInstanceResourceView.mockImplementation(async (id: number) => {
    if (id === 102) {
      return {
        instanceId: 102,
        monitorStatus: 'UP',
        latestDeployStatus: 'SUCCESS',
        latestDeployAt: '2026-01-03T05:06:07',
      };
    }
    if (id === 101) {
      return { instanceId: 101, monitorStatus: null, latestDeployStatus: null, latestDeployAt: null };
    }
    throw new Error('no resource view');
  });
  getRuntimeServiceInstanceStatus.mockImplementation(async () => ({
    running: true,
    portListening: false,
    healthStatus: 'UP',
    checkedAt: '2026-01-04T00:00:00',
  }));
  // 实时探测动作会先读部署配置，这里一并 mock，避免用例真的发请求出去
  getRuntimeServiceInstanceDeployConfig.mockImplementation(async () => ({ deployPort: 8080 }));
  getRuntimeServiceInstanceLogs.mockImplementation(async () => ({ content: '' }));
  pageAssetHosts.mockImplementation(async () => ({ pageNum: 1, pageSize: 200, total: 0, records: [] }));
});

afterEach(() => {
  wrapper?.unmount();
  document.body.innerHTML = '';
});

describe('运行实例：状态解释与展示', () => {
  it('历史遗留取值 ONLINE 原样展示，且不被当成 RUNNING', async () => {
    await mountView();

    const row = rowByName('legacy-online');
    const cell = statusCell(row, 'ONLINE');
    expect(cell, '历史值 ONLINE 应带原始状态码渲染').toBeTruthy();
    expect(cell!.textContent).toContain(t('statusSemantics.value.instanceStatus.ONLINE'));
    // 历史值单独标注，不与现行 RUNNING 合并
    expect(cell!.getAttribute('title')).toContain(t('statusSemantics.note.instanceOnlineLegacy'));
    expect(cell!.textContent).not.toContain(t('statusSemantics.value.instanceStatus.RUNNING'));
    // 中性展示：未确认语义不着成功色
    const tag = cell!.querySelector('.el-tag');
    expect(tag?.className).toContain('el-tag--info');

    const runningRow = rowByName('normal');
    const runningCell = statusCell(runningRow, 'RUNNING');
    expect(runningCell!.textContent).toContain(t('statusSemantics.value.instanceStatus.RUNNING'));
    expect(runningCell!.getAttribute('title')).not.toContain(t('statusSemantics.note.instanceOnlineLegacy'));
  });

  it('认不出的状态取值原样展示，不折算成成功或失败', async () => {
    await mountView();

    const row = rowByName('weird-status');
    const cell = statusCell(row, 'SOMETHING_NEW');
    expect(cell, '认不出的取值应保留原始码').toBeTruthy();
    expect(cell!.textContent).toContain('SOMETHING_NEW');
    expect(cell!.getAttribute('title')).toContain(t('statusSemantics.unknownValue'));
    expect(cell!.querySelector('.el-tag')?.className).toContain('el-tag--info');
  });

  it('没有记录时不臆断：部署状态/部署时间/监控状态/心跳一律「未记录」', async () => {
    await mountView();

    const row = rowByName('legacy-online');
    const text = row.textContent || '';
    expect(text).toContain(t('statusSemantics.notRecorded'));
    // 该行只有两个有记录的状态：登记态 ONLINE 与健康检查 UNKNOWN；
    // 部署状态与监控状态都没有记录，必须是空文案而不是拿 UNKNOWN 冒充快照。
    const cells = Array.from(row.querySelectorAll<HTMLElement>('.status-cell'));
    expect(cells.map((c) => c.getAttribute('data-status-code'))).toEqual(['ONLINE', 'UNKNOWN', '', '']);
    const emptyCells = cells.filter((c) => c.getAttribute('data-status-code') === '');
    expect(emptyCells.map((c) => (c.textContent || '').trim())).toEqual([
      t('runtimeInstances.noDeploy'),
      t('statusSemantics.notRecorded'),
    ]);
    // 时间列：部署时间与心跳时间都没有记录，不能伪造当前时间
    const emptyTimes = Array.from(row.querySelectorAll('.cell-time')).filter(
      (el) => (el.textContent || '').trim() === t('statusSemantics.notRecorded')
    );
    expect(emptyTimes.length).toBeGreaterThanOrEqual(2);
  });

  it('有记录时按记录展示，未确认的健康状态仍是中性', async () => {
    await mountView();

    const row = rowByName('weird-status');
    expect(statusCell(row, 'UP')).toBeTruthy();
    expect(row.textContent || '').toContain(t('statusSemantics.value.deployStatus.SUCCESS'));
    expect(row.textContent || '').toContain(t('statusSemantics.value.monitorStatus.UP'));
    expect(row.textContent || '').toContain(t('statusSemantics.value.instanceHealthStatus.UP'));
    const unknownCell = statusCell(rowByName('legacy-online'), 'UNKNOWN');
    expect(unknownCell!.getAttribute('title')).toContain(t('statusSemantics.note.healthUnknown'));
    expect(unknownCell!.querySelector('.el-tag')?.className).toContain('el-tag--info');
  });

  it('「刷新列表」只重读已记录数据，不做实时探测', async () => {
    await mountView();
    expect(listCalls.length).toBe(1);
    const before = listCalls.length;

    await refreshButton().trigger('click');
    await settle();

    expect(listCalls.length).toBe(before + 1);
    // 关键：刷新不触发 SSH 实时探测
    expect(getRuntimeServiceInstanceStatus).not.toHaveBeenCalled();
  });

  it('实时探测是单独动作，且提示结果不回写数据库', async () => {
    await mountView();

    const row = rowByName('normal');
    const probe = Array.from(row.querySelectorAll('button')).find((b) =>
      (b.textContent || '').includes(t('runtimeInstances.statusRealtime'))
    );
    expect(probe, '行内应有写明「实时探测」的动作').toBeTruthy();
    probe!.click();
    await settle();

    expect(getRuntimeServiceInstanceStatus).toHaveBeenCalledTimes(1);
    expect(getRuntimeServiceInstanceStatus.mock.calls[0][0]).toBe(103);
    // 探测结果只用于本次展示：提示里明确说明不落库
    expect(document.body.textContent).toContain(t('runtimeInstances.statusProbeNote'));
    // 列表里的登记状态没有被探测结果覆盖
    expect(statusCell(rowByName('normal'), 'RUNNING')).toBeTruthy();
  });

  it('状态说明弹窗列出字段来源与「是否实时」的说明', async () => {
    await mountView();

    legendButton().trigger('click');
    await settle();

    const body = document.body.textContent || '';
    expect(body).toContain(t('statusSemantics.legendTitle'));
    expect(body).toContain(t('statusSemantics.kind.instanceStatus'));
    expect(body).toContain(t('statusSemantics.kind.instanceStatusSource'));
    expect(body).toContain(t('statusSemantics.legendNote'));
  });
});
