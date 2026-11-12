import { afterEach, beforeEach, describe, expect, it, vi, type MockInstance } from 'vitest';
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils';
import ElementPlus, { ElMessage } from 'element-plus';
import { createMemoryHistory, createRouter } from 'vue-router';
import { i18n, setLocale } from '@/i18n';
import zhCN from '@/i18n/locales/zh-CN';
import enUS from '@/i18n/locales/en-US';
import DeployRecordsDialog from '../DeployRecordsDialog.vue';

/**
 * 部署记录汇总的语义边界：
 * 只有每一条记录都被后端明确标记为 SUCCESS 才允许出现「全部成功」；
 * 缺失或未知状态必须显式表达为「未确认」，既不算成功也不算失败；
 * 总览与批次必须共用同一判据。
 * 同时锁定日志复制的原始文本与记录定位高亮，全部为组件级自测（不连接真实后端）。
 */

interface RecordLike {
  id: number;
  deployBatch: number;
  deployStatus?: string | null;
  deployLog?: string;
  hostId?: number;
  versionId?: number;
  startedAt?: string;
  endedAt?: string;
}

function record(id: number, status: string | null | undefined, extra: Partial<RecordLike> = {}): RecordLike {
  return {
    id,
    deployBatch: 1,
    deployStatus: status,
    hostId: id,
    versionId: 100 + id,
    startedAt: '2026-10-01T10:00:00',
    endedAt: '2026-10-01T10:00:05',
    ...extra,
  };
}

let wrapper: VueWrapper | undefined;
let successToast: MockInstance;
let warningToast: MockInstance;
let errorToast: MockInstance;
let writeText: MockInstance;

function body(): HTMLElement {
  return document.body;
}

function bySelector<T extends HTMLElement>(selector: string): T | null {
  return body().querySelector<T>(selector);
}

function text(selector: string): string {
  return (bySelector(selector)?.textContent || '').replace(/\s+/g, ' ').trim();
}

function buttonByText(label: string): HTMLButtonElement | undefined {
  return Array.from(body().querySelectorAll('button')).find(
    button => (button.textContent || '').trim() === label,
  ) as HTMLButtonElement | undefined;
}

async function mountDialog(props: Record<string, unknown> = {}) {
  setLocale('zh-CN');
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/', component: { template: '<div />' } }],
  });
  await router.push('/');
  await router.isReady();

  wrapper = mount(DeployRecordsDialog, {
    props: { modelValue: true, loading: false, records: [], ...props },
    global: { plugins: [ElementPlus, i18n, router] },
    attachTo: document.body,
  });
  await flushPromises();
}

beforeEach(() => {
  successToast = vi.spyOn(ElMessage, 'success').mockImplementation(() => undefined as never);
  warningToast = vi.spyOn(ElMessage, 'warning').mockImplementation(() => undefined as never);
  errorToast = vi.spyOn(ElMessage, 'error').mockImplementation(() => undefined as never);
  vi.spyOn(console, 'error').mockImplementation(() => undefined);
  writeText = vi.fn(async () => undefined);
  Object.defineProperty(navigator, 'clipboard', {
    value: { writeText },
    configurable: true,
  });
});

afterEach(() => {
  wrapper?.unmount();
  wrapper = undefined;
  vi.restoreAllMocks();
});

describe('DeployRecordsDialog 汇总语义', () => {
  it('全部明确成功：只有此时才显示「全部成功」', async () => {
    await mountDialog({
      records: [record(1, 'SUCCESS'), record(2, 'SUCCESS'), record(3, 'SUCCESS')],
    });

    expect(text('.deploy-summary-bar')).toContain('全部成功');
    expect(text('.deploy-summary-bar')).toContain('共 3 条记录，1 个批次');
    expect(text('.summary-breakdown')).toBe('成功 3');
    expect(bySelector('.deploy-summary-bar .el-tag')?.className).toContain('el-tag--success');
  });

  it('全部执行中：显示执行中数量，绝不显示「全部成功」', async () => {
    await mountDialog({
      records: [record(1, 'RUNNING'), record(2, 'RUNNING')],
    });

    expect(text('.deploy-summary-bar')).toContain('2 项执行中');
    expect(text('.deploy-summary-bar')).not.toContain('全部成功');
    expect(text('.summary-breakdown')).toBe('执行中 2');
    expect(bySelector('.deploy-summary-bar .el-tag')?.className).toContain('el-tag--primary');
  });

  it('存在失败：明确显示失败，先于其它状态', async () => {
    await mountDialog({ records: [record(1, 'FAILED')] });

    expect(text('.deploy-summary-bar')).toContain('1 项失败');
    expect(text('.deploy-summary-bar')).not.toContain('全部成功');
    expect(bySelector('.deploy-summary-bar .el-tag')?.className).toContain('el-tag--danger');
  });

  it('混合状态：成功 / 失败 / 执行中 / 未确认分别列出，且不出现「尚无失败」式的全成功表述', async () => {
    await mountDialog({
      records: [record(1, 'SUCCESS'), record(2, 'FAILED'), record(3, 'RUNNING'), record(4, 'PENDING')],
    });

    const summaryText = text('.deploy-summary-bar');
    expect(summaryText).toContain('1 项失败');
    expect(summaryText).not.toContain('全部成功');
    expect(text('.summary-breakdown')).toBe('成功 1 · 失败 1 · 执行中 1 · 未确认 1');
  });

  it('状态未知或缺失：显式表达未确认，且不计入成功', async () => {
    await mountDialog({ records: [record(1, 'PENDING'), record(2, null)] });

    const summaryText = text('.deploy-summary-bar');
    expect(summaryText).toContain('2 项状态未确认');
    expect(summaryText).not.toContain('全部成功');
    expect(text('.summary-breakdown')).toBe('未确认 2');
    expect(text('.summary-breakdown')).not.toContain('成功');
  });

  it('空集合：不渲染总览，展示空态而不是失败或成功', async () => {
    await mountDialog({ records: [] });

    expect(bySelector('.deploy-summary-bar')).toBeNull();
    expect(text('.el-empty__description')).toBe('暂无部署记录');
  });

  it('批次汇总与总览共用同一判据：全部成功的批次才显示「全部成功」', async () => {
    await mountDialog({
      records: [
        record(1, 'SUCCESS', { deployBatch: 1 }),
        record(2, 'SUCCESS', { deployBatch: 1 }),
        record(3, 'SUCCESS', { deployBatch: 2 }),
        record(4, 'PENDING', { deployBatch: 2 }),
      ],
    });

    const batchHeaders = Array.from(body().querySelectorAll('.batch-header')).map(
      el => (el.textContent || '').replace(/\s+/g, ' ').trim(),
    );
    // 总览存在未确认项，因此整体不得宣称全部成功
    expect(text('.deploy-summary-bar')).not.toContain('全部成功');
    expect(text('.deploy-summary-bar')).toContain('1 项状态未确认');
    expect(batchHeaders[0]).toContain('全部成功');
    expect(batchHeaders[1]).toContain('1 项状态未确认');
    expect(batchHeaders[1]).not.toContain('全部成功');
  });
});

describe('DeployRecordsDialog 加载 / 空 / 读取失败三态', () => {
  it('读取中与「暂无部署记录」分开表达', async () => {
    await mountDialog({ records: [], loading: true });

    expect(text('.deploy-loading')).toContain('正在读取部署记录');
    expect(bySelector('.el-empty__description')).toBeNull();
  });

  it('读取失败时给出原因与只读重试，绝不被当作没有记录', async () => {
    await mountDialog({ records: [], error: '网关超时' });

    const errorBlock = text('.read-error-block');
    expect(errorBlock).toContain('部署记录读取失败');
    expect(errorBlock).toContain('网关超时');
    expect(errorBlock).toContain('读取失败不会被当作没有记录');
    // 不得渲染「暂无部署记录」空态
    expect(bySelector('.el-empty__description')).toBeNull();

    buttonByText('只读重试')?.click();
    await flushPromises();
    expect(wrapper?.emitted('retry')).toHaveLength(1);
  });
});

describe('DeployRecordsDialog 日志复制与记录定位', () => {
  it('复制日志写入完整原始文本，并给出成功反馈', async () => {
    const log = 'line-1\nline-2-long-line-with-very-long-content\nline-3';
    await mountDialog({
      records: [record(42, 'FAILED', { deployLog: log })],
      selectedRecordId: 42,
    });

    // 选中记录摘要保留记录 ID 与状态，便于定位
    expect(text('.record-summary')).toContain('记录ID: 42');
    expect(text('.record-summary')).toContain('失败');
    // 日志原文逐字渲染（未被截断或改写）
    expect(bySelector('.deploy-log')?.textContent).toBe(log);

    buttonByText('复制日志')?.click();
    await flushPromises();

    expect(writeText).toHaveBeenCalledWith(log);
    expect(successToast).toHaveBeenCalledWith('日志已复制');
  });

  it('没有可复制日志时明确提示，不静默丢弃', async () => {
    await mountDialog({
      records: [record(7, 'SUCCESS', { deployLog: undefined })],
      selectedRecordId: 7,
    });

    expect(buttonByText('复制日志')).toBeUndefined();
    expect(writeText).not.toHaveBeenCalled();
  });

  it('深链定位的记录被高亮，且失败行仍保留失败底色', async () => {
    await mountDialog({
      records: [
        record(1, 'FAILED', { deployBatch: 1, deployLog: 'failed log' }),
        record(2, 'SUCCESS', { deployBatch: 1, deployLog: 'ok log' }),
      ],
      selectedRecordId: 2,
    });

    const rows = Array.from(body().querySelectorAll('.el-table__body tr'));
    const targetRow = rows.find(row => row.textContent?.includes('记录ID') === false && row.querySelector('td'));
    const classNameOf = (row: Element) => row.className;

    // 目标记录（id=2）被定位高亮
    const highlighted = rows.filter(row => classNameOf(row).includes('deploy-target-row'));
    expect(highlighted).toHaveLength(1);
    expect(highlighted[0].textContent).toContain('2');
    // 失败记录（id=1）保持失败底色，不因高亮另一个而被抹掉
    const failed = rows.filter(row => classNameOf(row).includes('deploy-failed-row'));
    expect(failed).toHaveLength(1);
    expect(failed[0].textContent).toContain('1');
    expect(targetRow).toBeTruthy();
  });
});

describe('DeployRecordsDialog 文案国际化与动态汇总', () => {
  /** 按 vue-i18n 的具名插值展开文案，断言直接对照语言包而不是手写字符串 */
  function fill(template: string, params: Record<string, string | number>): string {
    return Object.entries(params).reduce(
      (text, [key, value]) => text.split(`{${key}}`).join(String(value)),
      template,
    );
  }

  /** 与组件一致的时间格式化（同一区域、同一选项），用于断言时间跟随控制台语言 */
  function timeIn(locale: 'zh-CN' | 'en-US', value = '2026-10-01T10:00:00'): string {
    return new Date(value).toLocaleString(locale, {
      year: 'numeric',
      month: '2-digit',
      day: '2-digit',
      hour: '2-digit',
      minute: '2-digit',
      second: '2-digit',
    });
  }

  it('英文控制台渲染英文标题、列头与总览，且不残留中文文案', async () => {
    await mountDialog({
      records: [record(1, 'FAILED', { deployLog: 'boom' })],
      selectedRecordId: 1,
    });

    setLocale('en-US');
    await flushPromises();

    expect(text('.el-dialog__header')).toContain(enUS.deployRecords.title);
    const header = text('.el-table__header');
    expect(header).toContain(enUS.deployRecords.columnStatus);
    expect(header).toContain(enUS.deployRecords.columnStartedAt);
    expect(header).toContain(enUS.deployRecords.columnAction);

    const bar = text('.deploy-summary-bar');
    expect(bar).toContain(fill(enUS.deployRecords.summaryFailed, { count: 1 }));
    expect(bar).toContain(
      fill(enUS.deployRecords.summaryDetail, { total: 1, batchCount: 1 }),
    );
    expect(bar).not.toContain(fill(zhCN.deployRecords.summaryFailed, { count: 1 }));
    expect(header).not.toContain(zhCN.deployRecords.columnStartedAt);

    // 日志正文是后端原文，任何语言下都不翻译
    expect(bySelector('.deploy-log')?.textContent).toBe('boom');
  });

  it('空态按当前语言表达，并在切换语言后立即换词', async () => {
    await mountDialog({ records: [] });

    setLocale('en-US');
    await flushPromises();
    expect(text('.el-empty__description')).toBe(enUS.deployRecords.empty);

    setLocale('zh-CN');
    await flushPromises();
    expect(text('.el-empty__description')).toBe(zhCN.deployRecords.empty);
  });

  it('读取失败态按当前语言表达，原因保持后端原文，且仍不被当作没有记录', async () => {
    await mountDialog({ records: [], error: 'gateway timeout' });

    setLocale('en-US');
    await flushPromises();

    const block = text('.read-error-block');
    expect(block).toContain(enUS.deployRecords.readFailed);
    expect(block).toContain('gateway timeout');
    expect(block).toContain(enUS.deployRecords.readFailedHint);
    // 读取失败不得渲染成空态
    expect(bySelector('.el-empty__description')).toBeNull();

    // 只读重试入口仍在，且只发读取请求（emit retry），不触发任何执行
    buttonByText(enUS.deployRecords.retryReadOnly)?.click();
    await flushPromises();
    expect(wrapper?.emitted('retry')).toHaveLength(1);
  });

  it('汇总与时间随语言切换即时更新，并在切回中文时复原', async () => {
    await mountDialog({
      records: [record(1, 'SUCCESS'), record(2, 'FAILED')],
      selectedRecordId: 1,
    });

    expect(text('.deploy-summary-bar')).toContain(
      fill(zhCN.deployRecords.summaryFailed, { count: 1 }),
    );
    expect(text('.record-summary')).toContain(timeIn('zh-CN'));

    setLocale('en-US');
    await flushPromises();

    const bar = text('.deploy-summary-bar');
    expect(bar).toContain(fill(enUS.deployRecords.summaryFailed, { count: 1 }));
    // 明细同样跟随语言（总数 > 1 时渲染）
    expect(bar).toContain(fill(enUS.deployRecords.summarySuccessCount, { count: 1 }));
    expect(bar).toContain(fill(enUS.deployRecords.summaryFailedCount, { count: 1 }));
    expect(bar).not.toContain(fill(zhCN.deployRecords.summaryFailed, { count: 1 }));
    expect(text('.record-summary')).toContain(timeIn('en-US'));
    expect(text('.record-summary')).not.toContain(timeIn('zh-CN'));

    setLocale('zh-CN');
    await flushPromises();
    expect(text('.deploy-summary-bar')).toContain(
      fill(zhCN.deployRecords.summaryFailed, { count: 1 }),
    );
    expect(text('.record-summary')).toContain(timeIn('zh-CN'));
  });

  it('失败定位提示的分类外框与分类名都随语言切换，日志原文两种语言下都不改写', async () => {
    const rawHint = 'Health check failed: port 8080 not ready';
    await mountDialog({
      records: [record(1, 'FAILED', { deployLog: `step-1 ok\n${rawHint}` })],
      selectedRecordId: 1,
    });

    // 分类外框不是写死的中文括号，而是与详情抽屉共用的 zh/en 条目
    const zhCategory = zhCN.deployRecords.failureCategoryHealthCheckFailed;
    const expectedZh = fill(zhCN.releaseDetail.failureItem, { source: zhCategory, text: rawHint });
    expect(text('.failure-hint')).toContain(expectedZh);
    expect(text('.failure-hint')).toContain('【');

    setLocale('en-US');
    await flushPromises();

    const enCategory = enUS.deployRecords.failureCategoryHealthCheckFailed;
    const expectedEn = fill(enUS.releaseDetail.failureItem, { source: enCategory, text: rawHint });
    const alertText = text('.failure-hint');
    expect(alertText).toContain(expectedEn);
    // 英文控制台不再出现中文括号外框
    expect(alertText).not.toContain('【');
    // 后端日志片段原样保留，未被翻译或改写
    expect(alertText).toContain(rawHint);

    setLocale('zh-CN');
    await flushPromises();
    expect(text('.failure-hint')).toContain(expectedZh);
  });
});