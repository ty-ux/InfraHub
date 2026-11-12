<template>
  <section class="workspace">
    <header class="workspace-head">
      <div class="head-copy">
        <h1>{{ t('workspace.welcome', { name: authStore.currentUser?.displayName || authStore.currentUser?.username }) }}</h1>
        <p>{{ t('workspace.description') }}</p>
      </div>
      <dl class="head-readout">
        <div class="readout-cell">
          <dt>{{ t('workspace.username') }}</dt>
          <dd class="measured">{{ authStore.currentUser?.username || '-' }}</dd>
        </div>
        <div class="readout-cell">
          <dt>{{ t('workspace.userId') }}</dt>
          <dd class="measured">{{ authStore.currentUser?.userId }}</dd>
        </div>
        <div class="readout-cell">
          <dt>{{ t('workspace.authorities') }}</dt>
          <dd class="measured">{{ authStore.currentUser?.authorities?.length || 0 }}</dd>
        </div>
      </dl>
    </header>

    <nav class="route-strip">
      <router-link v-for="item in quickLinks" :key="item.path" :to="item.path" class="route-cell">
        <span class="route-title">{{ item.title }}</span>
        <span class="route-desc">{{ item.desc }}</span>
        <span class="route-path measured">{{ item.path }}</span>
      </router-link>
    </nav>

    <WorkspaceObservabilityPanel />
  </section>
</template>

<script setup lang="ts">
import { computed } from 'vue';
import { useI18n } from 'vue-i18n';
import { useAuthStore } from '@/store/auth';
import WorkspaceObservabilityPanel from '@/components/workspace/WorkspaceObservabilityPanel.vue';

const authStore = useAuthStore();
const { t } = useI18n();

const quickLinks = computed(() => {
  const items = [
    { path: '/resource/apps', title: t('workspace.quick.appsTitle'), desc: t('workspace.quick.appsDesc'), auth: ['APP_VIEW', 'SYSTEM_ADMIN'] },
    { path: '/config/env-configs', title: t('workspace.quick.configsTitle'), desc: t('workspace.quick.configsDesc'), auth: ['SYSTEM_ADMIN'] },
    { path: '/release/orders', title: t('workspace.quick.releaseTitle'), desc: t('workspace.quick.releaseDesc'), auth: ['RELEASE_EXECUTE', 'SYSTEM_ADMIN'] },
    { path: '/pipeline/runs', title: t('workspace.quick.runsTitle'), desc: t('workspace.quick.runsDesc'), auth: ['PIPELINE_EXECUTE', 'SYSTEM_ADMIN'] },
  ];
  return items.filter((item) => authStore.canAccess(item.auth));
});
</script>

<style scoped lang="scss">
.workspace {
  display: grid;
  gap: var(--space-lg);
}

.workspace-head {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: var(--space-xl);
  padding: var(--space-xs) 0 var(--space-lg);
  border-bottom: var(--rule-hair) solid var(--color-rule);
}

.head-copy {
  min-width: 0;

  h1 {
    min-width: 0;
    overflow-wrap: break-word;
    color: var(--color-ink);
    font-size: var(--text-2xl);
    font-weight: var(--display-weight);
    letter-spacing: var(--tracking-display);
    line-height: 1.15;
    text-wrap: balance;
  }

  p {
    max-width: 58ch;
    margin-top: var(--space-xs);
    color: var(--color-muted);
    font-size: var(--text-sm);
    line-height: 1.6;
  }
}

.head-readout {
  display: flex;
  flex-shrink: 0;
  margin: 0;
  overflow: hidden;
  border: var(--rule-hair) solid var(--color-rule);
  border-radius: var(--radius-panel);
  background: var(--color-paper-2);
}

.readout-cell {
  min-width: 92px;
  padding: var(--space-xs) var(--space-sm);
  border-left: var(--rule-hair) solid var(--color-rule);

  &:first-child {
    border-left: 0;
  }

  dt {
    color: var(--color-muted);
    font-size: var(--text-2xs);
    line-height: 1.4;
  }

  dd {
    margin: var(--space-3xs) 0 0;
    overflow: hidden;
    color: var(--color-ink);
    font-size: var(--text-sm);
    line-height: 1.5;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
}

.route-strip {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(190px, 1fr));
  gap: var(--rule-hair);
  overflow: hidden;
  border: var(--rule-hair) solid var(--color-rule);
  border-radius: var(--radius-panel);
  background: var(--color-rule);
}

.route-cell {
  display: grid;
  min-width: 0;
  gap: var(--space-2xs);
  padding: var(--space-sm) var(--space-md);
  background: var(--color-paper-2);
  color: var(--color-ink);
  text-decoration: none;
  transition: background-color var(--dur-short) var(--ease-out);

  &:hover {
    background: var(--color-row-hover);
    color: var(--color-ink);
    text-decoration: none;
  }

  &:focus-visible {
    outline: 2px solid var(--color-focus);
    outline-offset: -2px;
  }
}

.route-title {
  color: var(--color-ink);
  font-size: var(--text-sm);
  font-weight: 500;
  line-height: 1.4;
}

.route-desc {
  overflow: hidden;
  color: var(--color-muted);
  font-size: var(--text-xs);
  line-height: 1.5;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.route-path {
  margin-top: var(--space-3xs);
  color: var(--color-placeholder);
  font-size: var(--text-2xs);
  line-height: 1.4;
}

@media (max-width: 1100px) {
  .workspace-head {
    align-items: flex-start;
    flex-direction: column;
    gap: var(--space-md);
  }
}

@media (max-width: 640px) {
  .head-readout {
    width: 100%;
  }

  .readout-cell {
    flex: 1;
    min-width: 0;
    padding: var(--space-xs);
  }

  .route-strip {
    grid-template-columns: 1fr;
  }
}
</style>
