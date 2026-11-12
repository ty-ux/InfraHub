import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils';
import ElementPlus from 'element-plus';
import { createPinia, setActivePinia } from 'pinia';
import { createMemoryHistory, createRouter } from 'vue-router';
import { i18n, setLocale } from '@/i18n';
import zhCN from '@/i18n/locales/zh-CN';
import enUS from '@/i18n/locales/en-US';
import PipelineRunsView from '../PipelineRunsView.vue';
import PipelineDefsView from '../PipelineDefsView.vue';
import { useAuthStore } from '@/store/auth';
import { pipelineApi, pipelineRunApi } from '@/api/pipeline';
import { appApi } from '@/api/resource';
import { getPageConfig } from '@/views/configs/page-configs';

/**
 * 流水线运行列表：状态标签必须区分已知状态、缺失与未知；耗时不得显示 NaN；
 * 无效运行 ID 不得构建 undefined/NaN 的步骤链接；页面保持只读。
 * 全部为组件级自测：网络被 mock，不连接真实后端，不产生任何写请求。
 */
vi.mock('@/api/pipeline', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/pipeline')>();
  const emptyPage = async () => ({ pageNum: 1, pageSize: 200, total: 0, records: [] as Record<string, unknown>[] });
  return {
    ...actual,
    pipelineApi: { ...actual.pipelineApi, page: vi.fn(emptyPage) },
    pipelineRunApi: { ...actual.pipelineRunApi, page: vi.fn() },
  };
});

vi.mock('@/api/resource', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/resource')>();
  const emptyPage = async () => ({ pageNum: 1, pageSize: 200, total: 0, records: [] as Record<string, unknown>[] });
  return {
    ...actual,
    appApi: { ...actual.appApi, page: vi.fn(emptyPage) },
    envApi: { ...actual.envApi, page: vi.fn(emptyPage) },
  };
});

const mockedRunPage = vi.mocked(pipelineRunApi.page);
const mockedPipelinePage = vi.mocked(pipelineApi.page);
const mockedAppPage = vi.mocked(appApi.page);

let wrapper: VueWrapper | undefined;

function runRow(overrides: Record<string, unknown> = {}) {
  return {
    id: 1,
    pipelineId: 101,
    appId: 201,
    envId: 301,
    runStatus: 'SUCCESS',
    triggerType: 'MANUAL',
    currentStage: 'deploy',
    durationMs: 1500,
    startTime: '2026-05-20T10:00:00',
    endTime: '2026-05-20T10:00:02',
    ...overrides,
  };
}

/** 列序号来自真实页面配置，避免把断言绑死在列的书写顺序上 */
function columnIndex(prop: string): number {
  const config = getPageConfig('pipelineRuns');
  if (!config) throw new Error('pipelineRuns 配置缺失');
  return config.columns.findIndex((column) => column.prop === prop);
}

function bodyRows(): HTMLElement[] {
  return Array.from(document.body.querySelectorAll<HTMLElement>('.el-table__body-wrapper .el-table__row'));
}

function cellText(rowIndex: number, prop: string): string {
  const cell = bodyRows()[rowIndex]?.querySelectorAll('.el-table__cell')[columnIndex(prop)];
  return (cell?.textContent || '').replace(/\s+/g, ' ').trim();
}

/** 单元格结构：标签与原始值是两个相邻元素，textContent 之间没有空白分隔 */
function unconfirmedWith(raw: string) {
  return `${zhCN.pipelineRuns.statusUnconfirmed}${raw}`;
}

function actionButtons(rowIndex: number): HTMLButtonElement[] {
  const container = bodyRows()[rowIndex]?.querySelector('.row-action-buttons, .el-table__cell:last-child');
  return Array.from(container?.querySelectorAll('button') ?? []);
}

async function mountView(records: Record<string, unknown>[], authorities = ['PIPELINE_EXECUTE'], view = PipelineRunsView) {
  setLocale('zh-CN');
  const pinia = createPinia();
  setActivePinia(pinia);
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', component: { template: '<div />' } },
      { path: '/pipeline/runs/:id/steps', component: { template: '<div />' } },
    ],
  });
  await router.push('/');
  await router.isReady();

  const authStore = useAuthStore();
  authStore.currentUser = { id: 1, username: 'tester', authorities } as never;

  mockedRunPage.mockResolvedValue({
    pageNum: 1,
    pageSize: 10,
    total: records.length,
    records,
  } as never);

  wrapper = mount(view, {
    global: { plugins: [ElementPlus, i18n, pinia, router] },
    attachTo: document.body,
  });
  await flushPromises();
}

beforeEach(() => {
  vi.spyOn(console, 'error').mockImplementation(() => undefined);
  vi.spyOn(console, 'warn').mockImplementation(() => undefined);
  mockedRunPage.mockReset();
  const empty = { pageNum: 1, pageSize: 100, total: 0, records: [] };
  mockedPipelinePage.mockReset().mockResolvedValue(empty);
  mockedAppPage.mockReset().mockResolvedValue(empty);
});

describe('流水线左侧导航', () => {
  const app = { id: 201, appCode: 'app-201', appName: 'InfraHub' };
  const pipeline = { id: 101, appId: 201, pipelineCode: 'compose', pipelineName: '联合交付' };
  const page = (records: Record<string, unknown>[]) => ({ pageNum: 1, pageSize: 100, total: records.length, records });

  it('按选中的流水线请求执行记录，切换到全部后去掉筛选', async () => {
    mockedPipelinePage.mockResolvedValue(page([pipeline]));
    mockedAppPage.mockResolvedValue(page([app]));
    await mountView([runRow()]);

    const option = wrapper!.findAll('.catalog-node').find(node => node.text() === pipeline.pipelineName);
    await option!.trigger('click');
    await flushPromises();
    expect(mockedRunPage).toHaveBeenLastCalledWith({ pageNum: 1, pageSize: 10, pipelineId: pipeline.id });

    await wrapper!.get('.catalog-all').trigger('click');
    await flushPromises();
    expect(mockedRunPage).toHaveBeenLastCalledWith({ pageNum: 1, pageSize: 10 });
  });

  it('流水线定义按所选应用查询', async () => {
    mockedPipelinePage.mockResolvedValue(page([pipeline]));
    mockedAppPage.mockResolvedValue(page([app]));
    await mountView([], ['PIPELINE_EXECUTE'], PipelineDefsView);

    const option = wrapper!.findAll('.catalog-node').find(node => node.text() === app.appName);
    await option!.trigger('click');
    await flushPromises();
    expect(mockedPipelinePage).toHaveBeenLastCalledWith({ pageNum: 1, pageSize: 10, appId: app.id });
  });
});

afterEach(() => {
  wrapper?.unmount();
  wrapper = undefined;
  document.body.innerHTML = '';
  setLocale('zh-CN');
  vi.restoreAllMocks();
});

describe('PipelineRunsView 运行状态标签', () => {
  it('后端明确返回的已知状态按当前语言渲染，并区分成功/失败/运行中', async () => {
    await mountView([
      runRow({ id: 1, runStatus: 'SUCCESS' }),
      runRow({ id: 2, runStatus: 'FAILED' }),
      runRow({ id: 3, runStatus: 'RUNNING' }),
    ]);

    expect(cellText(0, 'runStatus')).toBe(zhCN.pipelineRuns.statusSuccess);
    expect(cellText(1, 'runStatus')).toBe(zhCN.pipelineRuns.statusFailed);
    expect(cellText(2, 'runStatus')).toBe(zhCN.pipelineRuns.statusRunning);
  });

  it('小写与 CANCELLED 拼写按同一判据归一，不落进"无法确认"', async () => {
    await mountView([runRow({ id: 1, runStatus: 'cancelled' }), runRow({ id: 2, runStatus: ' queued ' })]);

    expect(cellText(0, 'runStatus')).toBe(zhCN.pipelineRuns.statusCanceled);
    expect(cellText(1, 'runStatus')).toBe(zhCN.pipelineRuns.statusQueued);
  });

  it('缺失状态显示"无法确认"并说明状态缺失，不猜测成任何结论', async () => {
    await mountView([runRow({ id: 1, runStatus: null }), runRow({ id: 2, runStatus: '' })]);

    expect(cellText(0, 'runStatus')).toBe(unconfirmedWith(zhCN.pipelineRuns.statusMissing));
    expect(cellText(1, 'runStatus')).toBe(unconfirmedWith(zhCN.pipelineRuns.statusMissing));
  });

  it('未知状态保留后端原始值原样展示，不改写、不翻译', async () => {
    await mountView([runRow({ id: 1, runStatus: 'WEIRD_STATE' })]);

    const text = cellText(0, 'runStatus');
    expect(text).toBe(unconfirmedWith('WEIRD_STATE'));
    expect(text).not.toContain(zhCN.pipelineRuns.statusSuccess);
  });
});

describe('PipelineRunsView 耗时展示', () => {
  it('合法耗时按量级格式化', async () => {
    await mountView([
      runRow({ id: 1, durationMs: 0 }),
      runRow({ id: 2, durationMs: 1500 }),
      runRow({ id: 3, durationMs: 65000 }),
      runRow({ id: 4, durationMs: 3720000 }),
    ]);

    expect(cellText(0, 'durationMs')).toBe(zhCN.pipelineRuns.durationMilliseconds.replace('{ms}', '0'));
    expect(cellText(1, 'durationMs')).toBe(zhCN.pipelineRuns.durationSeconds.replace('{seconds}', '2'));
    expect(cellText(2, 'durationMs')).toBe(
      zhCN.pipelineRuns.durationMinutesSeconds.replace('{minutes}', '1').replace('{seconds}', '5'),
    );
    expect(cellText(3, 'durationMs')).toBe(
      zhCN.pipelineRuns.durationHoursMinutes.replace('{hours}', '1').replace('{minutes}', '2'),
    );
  });

  it('缺失或非法耗时显示"无法确认"，不显示 NaN', async () => {
    await mountView([
      runRow({ id: 1, durationMs: null }),
      runRow({ id: 2, durationMs: undefined }),
      runRow({ id: 3, durationMs: 'abc' }),
      runRow({ id: 4, durationMs: -1 }),
    ]);

    for (let index = 0; index < 4; index += 1) {
      expect(cellText(index, 'durationMs')).toBe(zhCN.pipelineRuns.durationUnconfirmed);
      expect(cellText(index, 'durationMs')).not.toContain('NaN');
    }
  });
});

describe('PipelineRunsView 步骤入口与只读', () => {
  it('运行 ID 有效时构建步骤链接', async () => {
    await mountView([runRow({ id: 42 })]);

    const link = bodyRows()[0].querySelector('a');
    expect(link?.getAttribute('href')).toBe('/pipeline/runs/42/steps');
    expect(link?.textContent?.trim()).toBe(zhCN.common.stepRuns);
  });

  it('正常的数字字符串同样可以构建步骤链接', async () => {
    await mountView([runRow({ id: '42' })]);

    expect(bodyRows()[0].querySelector('a')?.getAttribute('href')).toBe('/pipeline/runs/42/steps');
  });

  it('小数、非数值、布尔、零、负数与超出安全整数范围的 ID 都不生成链接', async () => {
    await mountView([
      runRow({ id: 1.5 }),
      runRow({ id: '1.5' }),
      runRow({ id: 'abc' }),
      runRow({ id: true }),
      runRow({ id: 0 }),
      runRow({ id: -3 }),
      runRow({ id: '' }),
      runRow({ id: Number.MAX_SAFE_INTEGER + 1 }),
      runRow({ id: '9007199254740993' }),
    ]);

    for (let index = 0; index < 9; index += 1) {
      const row = bodyRows()[index];
      expect(row.querySelector('a')).toBeNull();
      // 去掉模板注释后再看渲染结果：注释里出现「undefined/NaN」字样不算渲染出无效路由
      const html = row.innerHTML.replace(/<!--[\s\S]*?-->/g, '');
      expect(html).not.toContain('undefined');
      expect(html).not.toContain('NaN');
      expect(html).not.toContain('href');
      expect(html).not.toContain('/pipeline/runs/');
      const disabled = Array.from(row.querySelectorAll('button')).filter(button => button.disabled);
      expect(disabled.map(button => (button.textContent || '').trim())).toEqual([zhCN.common.stepRuns]);
    }
  });

  it('运行 ID 无效时既没有步骤链接，也不构建 undefined/NaN 路由', async () => {
    await mountView([
      runRow({ id: null }),
      runRow({ id: 0 }),
      runRow({ id: 'abc' }),
      runRow({ id: undefined }),
    ]);

    for (let index = 0; index < 4; index += 1) {
      const row = bodyRows()[index];
      expect(row.querySelector('a')).toBeNull();
      // 去掉模板注释后再看渲染结果：注释里出现「undefined/NaN」字样不算渲染出无效路由
      const html = row.innerHTML.replace(/<!--[\s\S]*?-->/g, '');
      expect(html).not.toContain('undefined');
      expect(html).not.toContain('NaN');
      expect(html).not.toContain('href');
      const disabled = Array.from(row.querySelectorAll('button')).filter(button => button.disabled);
      expect(disabled.map(button => (button.textContent || '').trim())).toEqual([zhCN.common.stepRuns]);
    }
  });

  it('页面只读：不出现新建、编辑、删除，行内只有步骤入口', async () => {
    await mountView([runRow({ id: 1 })]);

    const labels = actionButtons(0).map(button => (button.textContent || '').trim());
    expect(labels).toEqual([zhCN.common.stepRuns]);
    expect(labels).not.toContain(zhCN.common.create);
    expect(labels).not.toContain(zhCN.common.edit);
    expect(labels).not.toContain(zhCN.common.delete);
    // 只读接口没有 create/update/remove：配置层也不提供
    const config = getPageConfig('pipelineRuns');
    expect(config?.api.create).toBeUndefined();
    expect(config?.api.update).toBeUndefined();
    expect(config?.api.remove).toBeUndefined();
  });

  it('列表查询只发生一次，且不产生任何写请求', async () => {
    await mountView([runRow({ id: 1 })]);

    expect(mockedRunPage).toHaveBeenCalledTimes(1);
    expect(mockedRunPage.mock.calls[0][0]).toMatchObject({ pageNum: 1, pageSize: 10 });
    // 运行列表用的只读接口本身没有写方法
    const api = pipelineRunApi as unknown as Record<string, unknown>;
    expect(api.create).toBeUndefined();
    expect(api.update).toBeUndefined();
    expect(api.remove).toBeUndefined();
  });
});

describe('PipelineRunsView 文案与语言切换', () => {
  it('状态标签、耗时与步骤入口随语言即时切换', async () => {
    await mountView([runRow({ id: 7, runStatus: 'FAILED', durationMs: 1500 })]);

    expect(cellText(0, 'runStatus')).toBe(zhCN.pipelineRuns.statusFailed);
    expect(cellText(0, 'durationMs')).toBe(zhCN.pipelineRuns.durationSeconds.replace('{seconds}', '2'));
    expect(actionButtons(0).map(button => (button.textContent || '').trim())).toEqual([zhCN.common.stepRuns]);

    setLocale('en-US');
    await flushPromises();

    expect(cellText(0, 'runStatus')).toBe(enUS.pipelineRuns.statusFailed);
    expect(cellText(0, 'durationMs')).toBe(enUS.pipelineRuns.durationSeconds.replace('{seconds}', '2'));
    expect(actionButtons(0).map(button => (button.textContent || '').trim())).toEqual([enUS.common.stepRuns]);

    setLocale('zh-CN');
    await flushPromises();

    expect(cellText(0, 'runStatus')).toBe(zhCN.pipelineRuns.statusFailed);
    expect(cellText(0, 'runStatus')).not.toBe(enUS.pipelineRuns.statusFailed);
  });

  it('切换语言时未知状态的原始值不被翻译', async () => {
    await mountView([runRow({ id: 1, runStatus: 'WEIRD_STATE' })]);

    const before = cellText(0, 'runStatus');
    setLocale('en-US');
    await flushPromises();
    const after = cellText(0, 'runStatus');

    expect(before).toContain('WEIRD_STATE');
    expect(after).toContain('WEIRD_STATE');
    expect(after).toBe(`${enUS.pipelineRuns.statusUnconfirmed}WEIRD_STATE`);
  });
});
