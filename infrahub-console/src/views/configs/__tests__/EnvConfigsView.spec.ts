import { flushPromises, mount } from '@vue/test-utils';
import ElementPlus from 'element-plus';
import { createI18n } from 'vue-i18n';
import { createMemoryHistory, createRouter } from 'vue-router';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import zhCN from '@/i18n/locales/zh-CN';
import EnvConfigsView from '../EnvConfigsView.vue';

const api = vi.hoisted(() => ({ read: vi.fn(), save: vi.fn() }));

vi.mock('@/api/resource', () => {
  const emptyPage = { page: async () => ({ records: [], total: 0 }) };
  return {
    appApi: emptyPage,
    envApi: emptyPage,
    envConfigApi: emptyPage,
    secretRefApi: emptyPage,
    configGroupApi: {
      page: async () => ({
        records: [
          { id: 1, groupName: '应用 A', envId: 3, appId: 7 },
          { id: 2, groupName: '应用 B', envId: 3, appId: 8 },
        ],
        total: 2,
      }),
    },
    envConfigDocumentApi: { ...api, ungroupedScopes: async () => [] },
  };
});

async function render() {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/', component: EnvConfigsView }],
  });
  await router.push('/');
  const i18n = createI18n({ legacy: false, locale: 'zh-CN', messages: { 'zh-CN': zhCN } });
  const wrapper = mount(EnvConfigsView, { global: { plugins: [router, i18n, ElementPlus] } });
  await flushPromises();
  return wrapper;
}

describe('environment configuration documents', () => {
  beforeEach(() => {
    vi.resetAllMocks();
    api.read.mockImplementation(async ({ groupId }: { groupId: number }) => ({
      json: JSON.stringify({ port: groupId === 1 ? 8080 : 8081 }),
      revision: `revision-${groupId}`,
    }));
  });

  it('shows only the selected group and replaces its document when switching groups', async () => {
    const wrapper = await render();
    expect(wrapper.get('textarea').element.value).toBe('{"port":8080}');
    await wrapper.findAll('.group-option')[1]!.trigger('click');
    await flushPromises();
    expect(api.read).toHaveBeenLastCalledWith({ groupId: 2, envId: 3, appId: 8 });
    expect(wrapper.get('textarea').element.value).toBe('{"port":8081}');
    wrapper.unmount();
  });

  it('saves the selected revision and preserves the raw JSON number', async () => {
    const wrapper = await render();
    const json = '{"port":8080,"preciseId":9007199254740993}';
    api.save.mockResolvedValue({ json, revision: 'revision-2' });

    await wrapper.findAll('button').find((button) => button.text() === zhCN.common.edit)!.trigger('click');
    await wrapper.get('textarea').setValue(json);
    await wrapper.findAll('button').find((button) => button.text() === zhCN.common.save)!.trigger('click');
    await flushPromises();

    expect(api.save).toHaveBeenCalledWith({ groupId: 1, envId: 3, appId: 7 }, { json, revision: 'revision-1' });
    expect(wrapper.get('textarea').element.readOnly).toBe(true);
    wrapper.unmount();
  });
});
