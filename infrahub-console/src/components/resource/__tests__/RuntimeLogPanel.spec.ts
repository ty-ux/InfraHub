import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { mount, type VueWrapper } from '@vue/test-utils';
import { nextTick } from 'vue';
import { i18n, setLocale } from '@/i18n';
import RuntimeLogPanel from '../RuntimeLogPanel.vue';
import zhCN from '@/i18n/locales/zh-CN';

let wrapper: VueWrapper;
const button = { props: ['disabled', 'loading'], template: '<button :disabled="disabled || loading"><slot /></button>' };
function open(extra = {}) {
  wrapper = mount(RuntimeLogPanel, {
    props: { active: true, targetKey: 2, content: '日志\n<unsafe> & text', loading: false, ...extra },
    global: { plugins: [i18n], stubs: { ElButton: button } },
  });
  return wrapper;
}
async function click(text: string) {
  await wrapper.findAll('button').find(x => x.text() === text)!.trigger('click');
}
beforeEach(() => { vi.useFakeTimers(); setLocale('zh-CN'); });
afterEach(() => { wrapper?.unmount(); vi.restoreAllMocks(); vi.unstubAllGlobals(); vi.useRealTimers(); });

describe('RuntimeLogPanel', () => {
  it('polls every two seconds, never while the previous read is pending, and stops on close', async () => {
    open();
    await vi.advanceTimersByTimeAsync(2000);
    expect(wrapper.emitted('refresh')).toHaveLength(1);
    await wrapper.setProps({ loading: true });
    await vi.advanceTimersByTimeAsync(10000);
    expect(wrapper.emitted('refresh')).toHaveLength(1);
    await wrapper.setProps({ loading: false });
    await vi.advanceTimersByTimeAsync(2000);
    expect(wrapper.emitted('refresh')).toHaveLength(2);
    await wrapper.setProps({ active: false });
    await vi.advanceTimersByTimeAsync(10000);
    expect(wrapper.emitted('refresh')).toHaveLength(2);
  });
  it('pauses in background tabs and on errors, and allows a manual retry', async () => {
    open();
    vi.spyOn(document, 'hidden', 'get').mockReturnValue(true);
    document.dispatchEvent(new Event('visibilitychange'));
    await nextTick();
    await vi.advanceTimersByTimeAsync(4000);
    expect(wrapper.emitted('refresh')).toBeUndefined();
    vi.spyOn(document, 'hidden', 'get').mockReturnValue(false);
    document.dispatchEvent(new Event('visibilitychange'));
    await nextTick();
    await wrapper.setProps({ failed: true });
    await vi.advanceTimersByTimeAsync(4000);
    expect(wrapper.emitted('refresh')).toBeUndefined();
    await click(zhCN.common.refresh);
    expect(wrapper.emitted('refresh')).toHaveLength(1);
  });
  it('lets the user disable polling and releases the timer on unmount', async () => {
    open();
    await wrapper.find('input[type="checkbox"]').setValue(false);
    await vi.advanceTimersByTimeAsync(4000);
    expect(wrapper.emitted('refresh')).toBeUndefined();
    await wrapper.find('input[type="checkbox"]').setValue(true);
    wrapper.unmount();
    await vi.advanceTimersByTimeAsync(4000);
    expect(wrapper.emitted('refresh')).toBeUndefined();
  });
  it('follows new output, preserves scroll position when reading history, and resumes on request', async () => {
    open();
    const viewport = wrapper.find('pre').element;
    Object.defineProperties(viewport, { scrollHeight: { configurable: true, value: 1000 }, clientHeight: { value: 200 } });
    await wrapper.setProps({ content: 'new log' }); await nextTick();
    expect(viewport.scrollTop).toBe(1000);
    viewport.scrollTop = 100;
    await wrapper.find('pre').trigger('scroll');
    await wrapper.setProps({ content: 'newest log' }); await nextTick();
    expect(viewport.scrollTop).toBe(100);
    await click(zhCN.runtimeLogs.latest); await nextTick();
    expect(viewport.scrollTop).toBe(1000);
    await wrapper.setProps({ content: 'latest again' }); await nextTick();
    expect(viewport.scrollTop).toBe(1000);
  });
  it('exports the exact loaded UTF-8 text with a scoped filename and revokes its object URL', async () => {
    const create = vi.fn((_blob: Blob) => 'blob:runtime-log');
    const revoke = vi.fn();
    vi.stubGlobal('URL', Object.assign(class extends URL {}, { createObjectURL: create, revokeObjectURL: revoke }));
    let filename = '';
    vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(function (this: HTMLAnchorElement) { filename = this.download; });
    open({ lines: 500 });
    await click(zhCN.runtimeLogs.download);
    expect(await (create.mock.calls[0]![0] as Blob).text()).toBe('日志\n<unsafe> & text');
    expect(filename).toMatch(/^instance-2-tail-500-.*\.log$/);
    expect(document.querySelector('a[download]')).toBeNull();
    await vi.advanceTimersByTimeAsync(1000);
    expect(revoke).toHaveBeenCalledWith('blob:runtime-log');
  });
  it('does not export failed content and renders log markup as plain text', () => {
    open({ downloadable: false });
    expect(wrapper.findAll('button').find(x => x.text() === zhCN.runtimeLogs.download)!.attributes('disabled')).toBeDefined();
    expect(wrapper.find('pre').text()).toBe('日志\n<unsafe> & text');
    expect(wrapper.find('unsafe').exists()).toBe(false);
  });
  it('allows the supported tail sizes and clearly describes the download scope', async () => {
    open();
    await wrapper.find('select').setValue('1000');
    expect(wrapper.emitted('update:lines')).toEqual([[1000]]);
    expect(wrapper.text()).toContain('不包含服务器完整日志文件');
  });
});
