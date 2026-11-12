<template>
  <div class="sidebar-groups">
    <section v-for="group in menuGroups" :key="group.key" class="group">
      <div class="group-title">{{ t(group.titleKey) }}</div>
      <router-link
        v-for="item in group.items"
        :key="item.path"
        :to="item.path"
        class="menu-item"
        :class="{ active: route.path === item.path }"
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
import { computed } from 'vue';
import { useRoute } from 'vue-router';
import { useI18n } from 'vue-i18n';
import { appRoutes } from '@/router/routes';
import { useAuthStore } from '@/store/auth';

const route = useRoute();
const authStore = useAuthStore();
const { t } = useI18n();

const groupTitles = {
  workspace: 'sidebar.workspace',
  resource: 'sidebar.resource',
  config: 'sidebar.config',
  release: 'sidebar.release',
  pipeline: 'sidebar.pipeline',
  monitor: 'sidebar.monitor',
  system: 'sidebar.system',
} as const;

const menuGroups = computed(() =>
  Object.entries(groupTitles)
    .map(([key, title]) => ({
      key,
      titleKey: title,
      items: appRoutes
        .filter((item) => !item.meta.hidden && item.meta.group === key && authStore.canAccess(item.meta.authorities))
        .sort((a, b) => (a.meta.order || 0) - (b.meta.order || 0)),
    }))
    .filter((group) => group.items.length > 0),
);
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
