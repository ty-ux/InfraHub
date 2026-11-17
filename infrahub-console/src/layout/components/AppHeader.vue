<template>
  <header class="app-header">
    <div class="header-left">
      <span v-if="groupTitle" class="header-group">{{ groupTitle }}</span>
      <span v-if="groupTitle" class="header-sep" aria-hidden="true">/</span>
      <span class="header-title">{{ pageTitle }}</span>
    </div>
    <div class="header-right">
      <el-select
        v-model="currentLocale"
        size="small"
        class="locale-select"
        :aria-label="t('common.locale')"
        @change="handleLocaleChange"
      >
        <el-option value="zh-CN" :label="t('common.zhCN')" />
        <el-option value="en-US" :label="t('common.enUS')" />
      </el-select>
      <button
        class="theme-toggle"
        type="button"
        :title="theme === 'dark' ? t('common.lightMode') : t('common.darkMode')"
        :aria-label="theme === 'dark' ? t('common.lightMode') : t('common.darkMode')"
        @click="toggleTheme"
      >
        <svg v-if="theme === 'light'" class="theme-icon" viewBox="0 0 16 16" fill="currentColor" width="16" height="16" aria-hidden="true">
          <path d="M8 1a1 1 0 0 1 1 1v1a1 1 0 0 1-2 0V2a1 1 0 0 1 1-1zM8 12a4 4 0 1 0 0-8 4 4 0 0 0 0 8zm0-6a2 2 0 1 1 0 4 2 2 0 0 1 0-4zM8 13a1 1 0 0 1 1 1v1a1 1 0 0 1-2 0v-1a1 1 0 0 1 1-1zM1 8a1 1 0 0 1 1-1h1a1 1 0 0 1 0 2H2a1 1 0 0 1-1-1zM13 8a1 1 0 0 1 1-1h1a1 1 0 0 1 0 2h-1a1 1 0 0 1-1-1zM3.05 3.05a1 1 0 0 1 1.414 0l.707.707a1 1 0 0 1-1.414 1.414l-.707-.707a1 1 0 0 1 0-1.414zM11.243 11.243a1 1 0 0 1 1.414 0l.707.707a1 1 0 0 1-1.414 1.414l-.707-.707a1 1 0 0 1 0-1.414zM3.757 11.243a1 1 0 0 1 0 1.414l-.707.707a1 1 0 0 1-1.414-1.414l.707-.707a1 1 0 0 1 1.414 0zM11.95 3.05a1 1 0 0 1 0 1.414l-.707.707a1 1 0 0 1-1.414-1.414l.707-.707a1 1 0 0 1 1.414 0z"/>
        </svg>
        <svg v-else class="theme-icon" viewBox="0 0 16 16" fill="currentColor" width="16" height="16" aria-hidden="true">
          <path d="M9.598 1.591a.75.75 0 0 1 .785-.175 7 7 0 1 1-8.967 8.967.75.75 0 0 1 .961-.96 5.5 5.5 0 0 0 7.046-7.046.75.75 0 0 1 .175-.786zm1.616 1.945a7 7 0 0 1-7.678 7.678 5.5 5.5 0 1 0 7.678-7.678z"/>
        </svg>
      </button>
      <div class="user-meta">
        <strong>{{ authStore.currentUser?.displayName || authStore.currentUser?.username || t('common.guest') }}</strong>
        <span class="measured">{{ authStore.currentUser?.username || '-' }}</span>
      </div>
      <el-button link class="logout" @click="handleLogout">
        {{ t('common.logout') }}
      </el-button>
    </div>
  </header>
</template>

<script setup lang="ts">
import { computed } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { useI18n } from 'vue-i18n';
import { useAuthStore } from '@/store/auth';
import { useTheme } from '@/composables/useTheme';
import { setLocale as applyLocale } from '@/i18n';
import { menuGroupTitles } from '@/router/navigation';

const route = useRoute();
const router = useRouter();
const authStore = useAuthStore();
const { t, locale } = useI18n();
const currentLocale = locale;
const { theme, toggleTheme } = useTheme();

const pageTitle = computed(() => {
  const title = route.meta.title;
  return title ? t(String(title)) : t('header.title');
});

const groupTitle = computed(() => {
  const group = route.meta.group;
  return group ? t(menuGroupTitles[group]) : '';
});

function handleLogout() {
  authStore.logout();
  router.replace('/login');
}

function handleLocaleChange(value: 'zh-CN' | 'en-US') {
  applyLocale(value);
}
</script>

<style scoped lang="scss">
.app-header {
  position: sticky;
  top: 0;
  z-index: var(--z-sticky);
  display: flex;
  height: var(--header-h);
  align-items: center;
  justify-content: space-between;
  gap: var(--space-md);
  padding: 0 var(--gutter-page);
  border-bottom: var(--rule-hair) solid var(--color-rule);
  background: var(--color-paper);
}

.header-left {
  display: flex;
  min-width: 0;
  align-items: baseline;
  gap: var(--space-xs);
}

.header-group {
  color: var(--color-muted);
  font-size: var(--text-xs);
  font-weight: 400;
  white-space: nowrap;
}

.header-sep {
  color: var(--color-rule-2);
  font-family: var(--font-outlier);
  font-size: var(--text-xs);
}

.header-title {
  overflow: hidden;
  color: var(--color-ink);
  font-size: var(--text-sm);
  font-weight: 500;
  letter-spacing: -0.01em;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.header-right {
  display: flex;
  flex-shrink: 0;
  align-items: center;
  gap: var(--space-xs);
}

.locale-select {
  width: 104px;

  :deep(.el-select__wrapper) {
    min-height: var(--control-h-sm);
    padding: 0 var(--space-2xs);
    background: transparent;
    box-shadow: none;

    &:hover {
      background: var(--color-paper-3);
    }

    &.is-focused {
      box-shadow: inset 0 0 0 1px var(--color-accent);
    }
  }

  :deep(.el-select__placeholder),
  :deep(.el-select__selected-item) {
    color: var(--color-ink-2);
    font-size: var(--text-xs);
  }
}

.theme-toggle {
  display: grid;
  width: var(--control-h-sm);
  height: var(--control-h-sm);
  place-items: center;
  border: var(--rule-hair) solid transparent;
  border-radius: var(--radius-chip);
  background: transparent;
  color: var(--color-muted);
  transition: color var(--dur-short) var(--ease-out),
    background-color var(--dur-short) var(--ease-out);

  &:hover {
    background: var(--color-paper-3);
    color: var(--color-ink);
  }

  &:active {
    background: var(--color-rule);
  }

  &:focus-visible {
    outline: 2px solid var(--color-focus);
    outline-offset: 2px;
  }
}

.theme-icon {
  width: 16px;
  height: 16px;
}

.user-meta {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  gap: 1px;
  margin-left: var(--space-xs);
  padding-left: var(--space-sm);
  border-left: var(--rule-hair) solid var(--color-rule);

  strong {
    color: var(--color-ink);
    font-size: var(--text-xs);
    font-weight: 500;
    line-height: 1.3;
  }

  span {
    color: var(--color-muted);
    font-size: var(--text-2xs);
    line-height: 1.3;
  }
}

.logout {
  color: var(--color-muted);
  font-size: var(--text-xs);

  &:hover {
    color: var(--color-ink);
  }
}

@media (max-width: 960px) {
  .app-header {
    padding: 0 var(--space-md);
  }

  .header-group,
  .header-sep {
    display: none;
  }

  .user-meta span {
    display: none;
  }
}

@media (max-width: 640px) {
  .user-meta {
    display: none;
  }

  .locale-select {
    width: 92px;
  }
}
</style>
