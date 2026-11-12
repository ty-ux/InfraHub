import { afterEach, beforeEach, describe, expect, it, vi, type MockInstance } from 'vitest';
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils';
import ElementPlus from 'element-plus';
import { createMemoryHistory, createRouter } from 'vue-router';
import { i18n, setLocale } from '@/i18n';
import zhCN from '@/i18n/locales/zh-CN';
import enUS from '@/i18n/locales/en-US';
import ReleaseDetailDrawer from '../ReleaseDetailDrawer.vue';

/**
 * 详情抽屉必须区分四件事：单据状态、审批结论、最近部署记录状态、辅助信息（灰度）读取结果。
 * 另外锁定两条读边界：
 * 1) 读取失败必须给出原因与只读重试，不得伪装成“没有数据”；
 * 2) 迟到的 A 响应不得覆盖后发起的 B，也不得在切换对象后串详情。
 * 全部为组件级自测（网络被 mock，不连接真实后端，不触发任何写操作）。
 */
vi.mock('@/api/release', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/release')>();
  return {
    ...actual,
    getReleaseDetail: vi.fn(),
    getRollbackDetail: vi.fn(),
    getCanaryPlan: vi.fn(),
    evaluateBatchGate: vi.fn(),
    getBatchGateEvaluations: vi.fn(),
  };
});

import { getReleaseDetail, getRollbackDetail, getCanaryPlan } from '@/api/release';

const mockedReleaseDetail = vi.mocked(getReleaseDetail);
const mockedRollbackDetail = vi.mocked(getRollbackDetail);
const mockedCanaryPlan = vi.mocked(getCanaryPlan);

interface Deferred {
  promise: Promise<unknown>;
  resolve: (value: unknown) => void;
  reject: (reason?: unknown) => void;
}

function deferred(): Deferred {
  let resolve!: (value: unknown) => void;
  let reject!: (reason?: unknown) => void;
  const promise = new Promise<unknown>((res, rej) => {
    resolve = res;
    reject = rej;
  });
  return { promise, resolve, reject };
}

let wrapper: VueWrapper | undefined;
let consoleError: MockInstance;

function bodyText(): string {
  return (document.body.textContent || '').replace(/\s+/g, ' ');
}

function hasText(value: string): boolean {
  return bodyText().includes(value);
}

function buttonByText(label: string): HTMLButtonElement | undefined {
  return Array.from(document.body.querySelectorAll('button')).find(
    button => (button.textContent || '').trim() === label,
  ) as HTMLButtonElement | undefined;
}

async function mountDrawer(props: Record<string, unknown> = {}) {
  setLocale('zh-CN');
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/', component: { template: '<div />' } }],
  });
  await router.push('/');
  await router.isReady();

  wrapper = mount(ReleaseDetailDrawer, {
    props: { modelValue: false, id: 1, type: 'release', ...props },
    global: { plugins: [ElementPlus, i18n, router] },
    attachTo: document.body,
  });
  await flushPromises();
}

/** 打开抽屉（modelValue 由 false 变为 true，等价于列表点击「详情」） */
async function openDrawer(props: Record<string, unknown> = {}) {
  await mountDrawer(props);
  await wrapper!.setProps({ modelValue: true });
  await flushPromises();
}

beforeEach(() => {
  consoleError = vi.spyOn(console, 'error').mockImplementation(() => undefined);
  mockedReleaseDetail.mockReset();
  mockedRollbackDetail.mockReset();
  mockedCanaryPlan.mockReset();
  mockedCanaryPlan.mockResolvedValue(null as never);
});

afterEach(() => {
  wrapper?.unmount();
  wrapper = undefined;
  document.body.innerHTML = '';
  vi.restoreAllMocks();
});

describe('ReleaseDetailDrawer 读取顺序与失败表达', () => {
  it('迟到的 A 响应不会覆盖后发起的 B', async () => {
    const first = deferred();
    const second = deferred();
    mockedReleaseDetail.mockReturnValueOnce(first.promise as never);
    mockedReleaseDetail.mockReturnValueOnce(second.promise as never);

    await openDrawer({ id: 1, type: 'release' });
    expect(mockedReleaseDetail).toHaveBeenCalledWith(1);

    // 切换到另一张单据（B），A 仍在途
    await wrapper!.setProps({ id: 2 });
    await flushPromises();
    expect(mockedReleaseDetail).toHaveBeenCalledWith(2);

    second.resolve({ orderNo: 'REL-B-0002', status: 'RUNNING', appName: 'app-b' });
    await flushPromises();
    expect(hasText('REL-B-0002')).toBe(true);

    // A 迟到返回：必须被丢弃，既不覆盖 B 也不改变加载态
    first.resolve({ orderNo: 'REL-A-0001', status: 'SUCCESS', appName: 'app-a' });
    await flushPromises();

    expect(hasText('REL-B-0002')).toBe(true);
    expect(hasText('REL-A-0001')).toBe(false);
    expect(hasText('app-a')).toBe(false);
  });

  it('切换对象时立即清空上一对象详情，不出现串详情', async () => {
    const first = deferred();
    const second = deferred();
    mockedReleaseDetail.mockReturnValueOnce(first.promise as never);
    mockedReleaseDetail.mockReturnValueOnce(second.promise as never);

    await openDrawer({ id: 1, type: 'release' });
    first.resolve({ orderNo: 'REL-A-0001', status: 'SUCCESS' });
    await flushPromises();
    expect(hasText('REL-A-0001')).toBe(true);

    // B 尚未返回：不得继续展示 A 的详情
    await wrapper!.setProps({ id: 2 });
    await flushPromises();
    expect(hasText('REL-A-0001')).toBe(false);

    second.resolve({ orderNo: 'REL-B-0002', status: 'SUCCESS' });
    await flushPromises();
    expect(hasText('REL-B-0002')).toBe(true);
  });

  it('读取失败给出原因与只读重试，且不伪装成没有数据', async () => {
    mockedReleaseDetail
      .mockRejectedValueOnce(new Error('详情服务 500'))
      .mockResolvedValueOnce({ orderNo: 'REL-0009', status: 'SUCCESS' } as never);

    await openDrawer({ id: 9, type: 'release' });

    expect(hasText('详情读取失败')).toBe(true);
    expect(hasText('详情服务 500')).toBe(true);
    expect(hasText('读取失败不代表该单据没有数据')).toBe(true);
    // 失败态不得渲染成“没有数据”的详情内容
    expect(hasText('详情未返回执行时间线')).toBe(false);
    expect(hasText('状态未返回，无法判断当前单据状态')).toBe(false);

    buttonByText('只读重试')?.click();
    await flushPromises();

    expect(mockedReleaseDetail).toHaveBeenCalledTimes(2);
    expect(hasText('REL-0009')).toBe(true);
    expect(hasText('详情服务 500')).toBe(false);
  });

  it('灰度信息读取失败与“没有灰度计划”分开表达', async () => {
    mockedReleaseDetail.mockResolvedValue({ orderNo: 'REL-0003', status: 'RUNNING' } as never);
    mockedCanaryPlan.mockRejectedValue(new Error('灰度接口超时'));

    await openDrawer({ id: 3, type: 'release' });

    expect(hasText('灰度信息读取失败')).toBe(true);
    expect(hasText('灰度接口超时')).toBe(true);
    expect(hasText('读取失败不等于该单据没有灰度发布计划')).toBe(true);
    // 详情本身仍可用
    expect(hasText('REL-0003')).toBe(true);
  });

  it('没有灰度计划时不出现读取失败提示', async () => {
    mockedReleaseDetail.mockResolvedValue({ orderNo: 'REL-0004', status: 'SUCCESS' } as never);
    mockedCanaryPlan.mockResolvedValue(null as never);

    await openDrawer({ id: 4, type: 'release' });

    expect(hasText('REL-0004')).toBe(true);
    expect(hasText('灰度信息读取失败')).toBe(false);
    expect(hasText('读取失败不等于该单据没有灰度发布计划')).toBe(false);
  });
});

describe('ReleaseDetailDrawer 事实分组与缺失占位', () => {
  it('审批事实与单据状态、最近部署记录状态相互区分', async () => {
    mockedReleaseDetail.mockResolvedValue({
      orderNo: 'REL-0005',
      status: 'FAILED',
      latestDeployStatus: 'FAILED',
      latestFailureHint: 'SSH 连接失败',
    } as never);

    await openDrawer({ id: 5, type: 'release' });

    // 审批结论不是本详情的能力，必须显式说明去向
    expect(hasText('本详情未返回审批明细，审批结论见上方单据状态；审批记录请到审批中心查看')).toBe(true);
    // 最近部署记录状态与单据状态相互独立
    expect(hasText('来自最近一次部署记录，与单据状态相互独立')).toBe(true);
    // 失败原因按来源分条
    expect(hasText('【最近部署记录】SSH 连接失败')).toBe(true);
  });

  it('缺失信息使用明确占位，不用“-”冒充已读取到的值', async () => {
    mockedReleaseDetail.mockResolvedValue({ orderNo: 'REL-0006' } as never);

    await openDrawer({ id: 6, type: 'release' });

    expect(hasText('状态未返回，无法判断当前单据状态')).toBe(true);
    expect(hasText('未提供')).toBe(true);
    // 版本变化分组仍然渲染（缺值时用占位，而不是整块消失）
    expect(hasText('发布版本')).toBe(true);
  });

  it('回滚单详情展示原版本与目标版本区间', async () => {
    mockedRollbackDetail.mockResolvedValue({
      rollbackNo: 'RB-0007',
      status: 'CREATED',
      fromVersionNo: 'v1.0.0',
      toVersionNo: 'v0.9.0',
    } as never);

    await openDrawer({ id: 7, type: 'rollback' });

    expect(mockedReleaseDetail).not.toHaveBeenCalled();
    expect(hasText('回滚单详情')).toBe(true);
    expect(hasText('RB-0007')).toBe(true);
    expect(hasText('v1.0.0')).toBe(true);
    expect(hasText('v0.9.0')).toBe(true);
    // 发布单专属区块不得出现在回滚单详情里
    expect(hasText('执行器信息')).toBe(false);
  });
});

describe('ReleaseDetailDrawer 文案国际化与语言切换', () => {
  it('读取失败原因保持后端原文，标题与提示随语言切换即时更新且不触发重读', async () => {
    mockedReleaseDetail.mockRejectedValueOnce(new Error('详情服务 500'));

    await openDrawer({ id: 11, type: 'release' });

    expect(hasText(zhCN.releaseDetail.loadErrorTitle)).toBe(true);
    expect(hasText(zhCN.releaseDetail.loadErrorHint)).toBe(true);
    expect(hasText(zhCN.releaseDetail.retryReadOnly)).toBe(true);
    expect(hasText('详情服务 500')).toBe(true);
    expect(mockedReleaseDetail).toHaveBeenCalledTimes(1);

    setLocale('en-US');
    await flushPromises();

    expect(hasText(enUS.releaseDetail.loadErrorTitle)).toBe(true);
    expect(hasText(enUS.releaseDetail.loadErrorHint)).toBe(true);
    expect(hasText(enUS.releaseDetail.retryReadOnly)).toBe(true);
    // 后端给出的原因不翻译、不改写
    expect(hasText('详情服务 500')).toBe(true);
    // 中文文案不再残留
    expect(hasText(zhCN.releaseDetail.loadErrorTitle)).toBe(false);
    expect(hasText(zhCN.releaseDetail.loadErrorHint)).toBe(false);
    // 切换语言不是一次读取：不重读
    expect(mockedReleaseDetail).toHaveBeenCalledTimes(1);

    setLocale('zh-CN');
    await flushPromises();
    expect(hasText(zhCN.releaseDetail.loadErrorTitle)).toBe(true);
    expect(mockedReleaseDetail).toHaveBeenCalledTimes(1);
  });

  it('没有后端原因时的兜底文案不写死在初始化语言，随切换即时换词', async () => {
    mockedReleaseDetail.mockRejectedValueOnce(undefined);

    await openDrawer({ id: 12, type: 'release' });

    expect(hasText(zhCN.releaseDetail.loadErrorFallback)).toBe(true);

    setLocale('en-US');
    await flushPromises();
    expect(hasText(enUS.releaseDetail.loadErrorFallback)).toBe(true);
    expect(hasText(zhCN.releaseDetail.loadErrorFallback)).toBe(false);
    expect(mockedReleaseDetail).toHaveBeenCalledTimes(1);

    setLocale('zh-CN');
    await flushPromises();
    expect(hasText(zhCN.releaseDetail.loadErrorFallback)).toBe(true);
    expect(mockedReleaseDetail).toHaveBeenCalledTimes(1);
  });

  it('正常详情下本批文案在中英两种语言各自成立，单据事实不翻译', async () => {
    mockedReleaseDetail.mockResolvedValue({ orderNo: 'REL-0012', status: 'SUCCESS' } as never);

    await openDrawer({ id: 12, type: 'release' });

    expect(hasText(zhCN.releaseDetail.titleRelease)).toBe(true);
    expect(hasText(zhCN.releaseDetail.sectionBasic)).toBe(true);
    expect(hasText('REL-0012')).toBe(true);

    setLocale('en-US');
    await flushPromises();

    expect(hasText(enUS.releaseDetail.titleRelease)).toBe(true);
    expect(hasText(enUS.releaseDetail.sectionBasic)).toBe(true);
    expect(hasText('REL-0012')).toBe(true);
    expect(hasText(zhCN.releaseDetail.sectionBasic)).toBe(false);
    expect(hasText(zhCN.releaseDetail.titleRelease)).toBe(false);
    // 语言切换不重读详情
    expect(mockedReleaseDetail).toHaveBeenCalledTimes(1);
  });
});