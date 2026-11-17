<template>
  <div class="sidebar-groups">
    <section v-for="group in menuGroups" :key="group.key" class="group">
      <div class="group-title">{{ t(group.titleKey) }}</div>
      <router-link
        v-for="item in group.items"
        :key="item.path"
        :to="item.path"
        class="menu-item"
        :class="{ active: activeMenuPath === item.path }"
        :aria-label="t(item.meta.title)"
        :title="t(item.meta.title)"
      >
        <component :is="item.meta.icon" v-if="item.meta.icon" class="menu-icon" />
        <span>{{ t(item.meta.title) }}</span>
      </router-link>
    </section>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted } from 'vue';
import { useRoute } from 'vue-router';
import { useI18n } from 'vue-i18n';
import { menuGroupTitles } from '@/router/navigation';
import { menuRoutes } from '@/router/routes';
import { useAuthStore } from '@/store/auth';
import { useMenuLayoutStore } from '@/store/menuLayout';

const route = useRoute();
const authStore = useAuthStore();
const menu = useMenuLayoutStore();
const { t } = useI18n();

const activeMenuPath = computed(() => route.meta.activeMenuPath ?? route.path);

const menuGroups = computed(() =>
  menu.layout.groupOrder
    .map((key) => ({
      key,
      titleKey: menuGroupTitles[key],
      items: menu.layout.entries
        .filter((entry) => entry.visible && entry.group === key)
        .map((entry) => menuRoutes.get(entry.path))
        .filter((item) => item && authStore.canAccess(item.meta.authorities)),
    }))
    .filter((group) => group.items.length > 0),
);

onMounted(() => { void menu.load().catch(() => {}); });
</script>

<style scoped lang="scss">
.sidebar-groups {
  display: flex;
  flex-direction: column;
  gap: var(--space-md);
}

.group-title {
  padding: 0 var(--space-xs) var(--space-2xs);
  color: var(--color-rail-muted);
  font-family: var(--font-body);
  font-size: var(--text-2xs);
  font-weight: 500;
  letter-spacing: 0;
  line-height: 1.4;
  text-transform: none;
}

.menu-item {
  position: relative;
  display: flex;
  align-items: center;
  gap: var(--space-xs);
  height: var(--control-h);
  margin: 0 4px 1px;
  padding: 0 var(--space-xs);
  border: var(--rule-hair) solid transparent;
  border-radius: var(--radius-field);
  color: var(--color-rail-ink-2);
  font-family: var(--font-body);
  font-size: var(--text-sm);
  font-weight: 400;
  line-height: 1.2;
  text-decoration: none;
  white-space: nowrap;
  transition: color var(--dur-short) var(--ease-out),
    background-color var(--dur-short) var(--ease-out);

  &:hover {
    border-color: transparent;
    background: var(--color-rail-hover);
    color: var(--color-rail-ink);
    text-decoration: none;
  }

  &.active {
    border-color: transparent;
    background: var(--color-rail-accent-soft);
    color: var(--color-rail-ink);
    font-weight: 500;

    &::before {
      position: absolute;
      top: 7px;
      bottom: 7px;
      left: -1px;
      width: 2px;
      border-radius: 0 1px 1px 0;
      background: var(--color-rail-accent);
      content: '';
    }

    .menu-icon {
      color: var(--color-rail-accent);
    }
  }

  &:focus-visible {
    outline: 2px solid var(--color-rail-accent);
    outline-offset: 2px;
  }

  &:active {
    background: var(--color-rail-2);
  }
}

.menu-icon {
  flex-shrink: 0;
  width: 16px;
  height: 16px;
  color: var(--color-rail-muted);
  transition: color var(--dur-short) var(--ease-out);
}

.menu-item:hover .menu-icon {
  color: var(--color-rail-ink-2);
}

@media (max-width: 1024px) {
  .group-title {
    display: none;
  }

  .menu-item {
    justify-content: center;
    margin: 1px var(--space-2xs);
    padding: 0;

    span {
      display: none;
    }
  }

  .menu-icon {
    margin: 0;
  }
}
</style>
