import { defineStore } from 'pinia';
import { computed, shallowRef } from 'vue';
import { getMenuLayout, saveMenuLayout, type MenuLayoutState } from '@/api/menuLayout';
import { resolveMenuLayout, type MenuLayout } from '@/router/navigation';
import { defaultMenuLayout } from '@/router/routes';

export const useMenuLayoutStore = defineStore('menuLayout', () => {
  const state = shallowRef<MenuLayoutState>();
  const layout = computed(() => resolveMenuLayout(defaultMenuLayout, state.value?.layout));
  let loading: Promise<MenuLayoutState> | undefined;

  function load() {
    loading ??= getMenuLayout()
      .then((result) => {
        if (!state.value || result.revision >= state.value.revision) state.value = result;
        return state.value;
      })
      .finally(() => { loading = undefined; });
    return loading;
  }

  async function save(revision: number, layout: MenuLayout) {
    state.value = await saveMenuLayout({ revision, layout });
    return state.value;
  }

  return { layout, load, save };
});
