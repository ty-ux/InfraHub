<template>
  <div class="layout-shell">
    <aside class="layout-sidebar">
      <div class="brand">
        <div class="brand-mark">IH</div>
        <div class="brand-text">
          <span class="brand-name">InfraHub</span>
          <span class="brand-tag">{{ t('common.controlPlane') }}</span>
        </div>
      </div>
      <AppSidebar />
    </aside>
    <div class="layout-main">
      <AppHeader />
      <main class="layout-content">
        <router-view />
      </main>
    </div>
  </div>
</template>

<script setup lang="ts">
import { useI18n } from 'vue-i18n';
import AppHeader from './components/AppHeader.vue';
import AppSidebar from './components/AppSidebar.vue';

const { t } = useI18n();
</script>

<style scoped lang="scss">
.layout-shell {
  display: grid;
  grid-template-columns: var(--rail-w) minmax(0, 1fr);
  min-height: 100vh;
  max-width: var(--ih-layout-max-width);
  margin: 0 auto;
  background: var(--color-paper);
}

.layout-sidebar {
  position: sticky;
  top: 0;
  height: 100vh;
  overflow-x: hidden;
  overflow-y: auto;
  padding: var(--space-md) var(--space-sm);
  border-right: var(--rule-hair) solid var(--color-rail-rule);
  background: var(--color-rail);
  color: var(--color-rail-ink);
  transition: background-color var(--dur-long) var(--ease-out),
    border-color var(--dur-long) var(--ease-out);
}

.brand {
  display: flex;
  align-items: center;
  gap: var(--space-xs);
  padding: var(--space-2xs) var(--space-xs) 20px;
}

.brand-mark {
  display: grid;
  width: 30px;
  height: 30px;
  flex-shrink: 0;
  place-items: center;
  border: var(--rule-hair) solid var(--color-rail-rule);
  border-radius: var(--radius-field);
  background: var(--color-rail-2);
  color: var(--color-rail-accent);
  font-family: var(--font-display);
  font-size: var(--text-2xs);
  font-weight: 500;
  letter-spacing: -0.02em;
}

.brand-text {
  display: flex;
  min-width: 0;
  flex-direction: column;
  gap: 1px;
}

.brand-name {
  color: var(--color-rail-ink);
  font-size: var(--text-md);
  font-weight: 500;
  letter-spacing: -0.01em;
  line-height: 1.2;
}

.brand-tag {
  color: var(--color-rail-muted);
  font-size: var(--text-2xs);
  font-weight: 400;
  line-height: 1.3;
}

.layout-main {
  display: flex;
  min-width: 0;
  flex-direction: column;
  background: var(--color-paper);
  transition: background-color var(--dur-long) var(--ease-out);
}

.layout-content {
  flex: 1;
  width: 100%;
  max-width: var(--content-max);
  margin: 0 auto;
  padding: var(--space-lg) var(--gutter-page) var(--space-2xl);
  overflow-x: auto;
}

@media (max-width: 1024px) {
  .layout-shell {
    grid-template-columns: var(--rail-w-collapsed) minmax(0, 1fr);
  }

  .brand {
    justify-content: center;
    padding: var(--space-2xs) 0 var(--space-md);
  }

  .brand-text {
    display: none;
  }

  :deep(.menu-item span) {
    display: none;
  }

  :deep(.group-title) {
    display: none;
  }
}

@media (max-width: 960px) {
  .layout-shell {
    grid-template-columns: minmax(0, 1fr);
  }

  .layout-sidebar {
    display: none;
  }

  .layout-content {
    padding: var(--space-md);
  }
}
</style>
