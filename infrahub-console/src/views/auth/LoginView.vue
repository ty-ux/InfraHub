<template>
  <div class="login-shell">
    <svg
      class="login-atmosphere"
      viewBox="0 0 1440 900"
      preserveAspectRatio="xMidYMid slice"
      aria-hidden="true"
      focusable="false"
    >
      <defs>
        <pattern id="login-grid" width="48" height="48" patternUnits="userSpaceOnUse">
          <path d="M48 0H0v48" fill="none" stroke="currentColor" stroke-opacity="0.1" stroke-width="1" />
        </pattern>
      </defs>
      <rect width="1440" height="900" fill="url(#login-grid)" />

      <g class="ring ring-top">
        <circle cx="196" cy="128" r="64" />
        <circle cx="196" cy="128" r="112" />
        <circle class="ring-ticks" cx="196" cy="128" r="160" />
        <circle class="ring-pin" cx="196" cy="128" r="3" />
      </g>

      <g class="ring ring-bottom">
        <circle cx="1216" cy="760" r="140" />
        <circle cx="1216" cy="760" r="240" />
        <circle class="ring-ticks" cx="1216" cy="760" r="340" />
        <circle class="ring-orbit" cx="1216" cy="760" r="300" />
        <circle class="ring-pin" cx="1216" cy="760" r="3" />
      </g>

      <g class="login-scale">
        <path d="M56 176v568" />
        <path d="M48 240h16M48 328h16M48 416h16M48 504h16M48 592h16M48 680h16" />
      </g>

      <path
        class="login-trace"
        d="M0 848h96l12-16 18 34 14-26 10 8h110l12-16 18 34 14-26 10 8h110l12-16 18 34 14-26 10 8h110l12-16 18 34 14-26 10 8h110l12-16 18 34 14-26 10 8h110l12-16 18 34 14-26 10 8h110l12-16 18 34 14-26 10 8h110l12-16 18 34 14-26 10 8h110"
      />
    </svg>

    <div class="login-panel">
      <section class="login-brief">
        <div class="brief-instrument" aria-hidden="true">
          <span class="instrument-arc instrument-arc-outer"></span>
          <span class="instrument-arc instrument-arc-inner"></span>
          <span class="instrument-node"></span>
        </div>

        <div class="brand-lockup">
          <div class="brand-mark" aria-hidden="true">IH</div>
          <div class="brand-text">
            <span class="brand-name">InfraHub</span>
            <span class="brand-tag">{{ t('common.controlPlane') }}</span>
          </div>
        </div>

        <div class="brief-copy">
          <h1>{{ t('auth.slogan') }}</h1>
          <p>{{ t('auth.intro') }}</p>
        </div>

        <div class="brief-ledger">
          <div class="ledger-head">
            <span class="ledger-mark" aria-hidden="true"></span>
            <span class="ledger-title">{{ t('auth.capabilities') }}</span>
          </div>
          <ul class="capabilities">
            <li class="capability">
              <span class="capability-index measured">01</span>
              <span class="capability-label">{{ t('auth.featureRelease') }}</span>
            </li>
            <li class="capability">
              <span class="capability-index measured">02</span>
              <span class="capability-label">{{ t('auth.featurePipeline') }}</span>
            </li>
            <li class="capability">
              <span class="capability-index measured">03</span>
              <span class="capability-label">{{ t('auth.featureMonitor') }}</span>
            </li>
          </ul>
        </div>
      </section>

      <section class="login-form-area">
        <button
          class="theme-toggle-login"
          type="button"
          :title="theme === 'dark' ? t('common.lightMode') : t('common.darkMode')"
          :aria-label="theme === 'dark' ? t('common.lightMode') : t('common.darkMode')"
          @click="toggleTheme"
        >
          <svg v-if="theme === 'light'" class="theme-icon" viewBox="0 0 16 16" fill="currentColor" width="16" height="16" aria-hidden="true">
            <path d="M8 1a1 1 0 0 1 1 1v1a1 1 0 0 1-2 0V2a1 1 0 0 1 1-1zM8 12a4 4 0 1 0 0-8 4 4 0 0 0 0 8zm0-6a2 2 0 1 1 0 4 2 2 0 0 1 0-4zM8 13a1 1 0 0 1 1 1v1a1 1 0 0 1-2 0v-1a1 1 0 0 1 1-1zM1 8a1 1 0 0 1 1-1h1a1 1 0 0 1 0 2H2a1 1 0 0 1-1-1zM13 8a1 1 0 0 1 1-1h1a1 1 0 0 1 0 2h-1a1 1 0 0 1-1-1zM3.05 3.05a1 1 0 0 1 1.414 0l.707.707a1 1 0 0 1-1.414 1.414l-.707-.707a1 1 0 0 1 0-1.414zM11.243 11.243a1 1 0 0 1 1.414 0l.707.707a1 1 0 0 1-1.414 1.414l-.707-.707a1 1 0 0 1 0-1.414zM3.757 11.243a1 1 0 0 1 0 1.414l-.707.707a1 1 0 0 1-1.414-1.414l.707-.707a1 1 0 0 1 1.414 0zM11.95 3.05a1 1 0 0 1 0 1.414l-.707.707a1 1 0 0 1-1.414-1.414l.707-.707a1 1 0 0 1 1.414 0z"/>
          </svg>
          <svg v-else class="theme-icon" viewBox="0 0 16 16" fill="currentColor" width="16" height="16" aria-hidden="true">
            <path d="M9.598 1.591a.75.75 0 0 1 .785-.175 7 7 0 1 1-8.967 8.967.75.75 0 0 1 .961-.96 5.5 5.5 0 0 0 7.046-7.046.75.75 0 0 1 .175-.786zm1.616 1.945a7 7 0 1 1-7.678 7.678 5.5 5.5 0 1 0 7.678-7.678z"/>
          </svg>
        </button>

        <div class="login-card">
          <div class="login-title">
            <h2>{{ t('auth.signIn') }}</h2>
            <p>{{ t('auth.hint') }}</p>
          </div>
          <el-form ref="formRef" :model="form" :rules="rules" label-position="top" @submit.prevent="submit">
            <el-form-item :label="t('auth.username')" prop="username">
              <el-input v-model="form.username" autocomplete="username" :placeholder="t('auth.enterUsername')" />
            </el-form-item>
            <el-form-item :label="t('auth.password')" prop="password">
              <el-input v-model="form.password" type="password" autocomplete="current-password" show-password :placeholder="t('auth.enterPassword')" />
            </el-form-item>
            <el-button type="primary" native-type="submit" :loading="loading" class="submit-btn">{{ t('auth.login') }}</el-button>
          </el-form>
        </div>
      </section>
    </div>
  </div>
</template>

<script setup lang="ts">
import { reactive, ref } from 'vue';
import type { FormInstance, FormRules } from 'element-plus';
import { ElMessage } from 'element-plus';
import { useRoute, useRouter } from 'vue-router';
import { useI18n } from 'vue-i18n';
import { useAuthStore } from '@/store/auth';
import { useTheme } from '@/composables/useTheme';

const router = useRouter();
const route = useRoute();
const authStore = useAuthStore();
const { t } = useI18n();
const { theme, toggleTheme } = useTheme();
const formRef = ref<FormInstance>();
const loading = ref(false);
const form = reactive({
  username: '',
  password: '',
});

const rules: FormRules = {
  username: [{ required: true, message: t('common.required', { label: t('auth.username') }), trigger: 'blur' }],
  password: [{ required: true, message: t('common.required', { label: t('auth.password') }), trigger: 'blur' }],
};

async function submit() {
  if (loading.value || !await formRef.value?.validate().catch(() => false)) return;
  loading.value = true;
  try {
    await authStore.login(form.username.trim(), form.password);
    if (!authStore.currentUser?.mustChangePassword) ElMessage.success(t('auth.loginSuccess'));
    const redirect = typeof route.query.redirect === 'string' && /^\/(?![/\\])/.test(route.query.redirect)
      ? route.query.redirect : '/workspace';
    await router.replace(redirect);
  } catch {
    // Request errors are shown by the HTTP client.
  } finally {
    loading.value = false;
  }
}
</script>

<style scoped lang="scss">
.login-shell {
  position: relative;
  display: grid;
  min-height: 100vh;
  overflow: hidden;
  place-items: center;
  padding: clamp(24px, 5vw, 64px);
  background: var(--color-paper);
  isolation: isolate;
}

.login-atmosphere {
  position: absolute;
  inset: 0;
  z-index: 0;
  width: 100%;
  height: 100%;
  color: var(--color-cool);
  pointer-events: none;

  .ring {
    fill: none;
    stroke: currentColor;

    circle {
      stroke-opacity: 0.14;
    }

    .ring-ticks {
      stroke-opacity: 0.2;
      stroke-dasharray: 2 16;
    }

    .ring-pin {
      fill: currentColor;
      stroke: none;
      opacity: 0.65;
    }

    .ring-orbit {
      stroke-opacity: 0.1;
      stroke-dasharray: 28 64;
      transform-box: view-box;
      transform-origin: 1216px 760px;
      animation: login-orbit 160s linear infinite;
    }
  }

  .login-scale {
    fill: none;
    stroke: currentColor;
    stroke-opacity: 0.16;
  }

  .login-trace {
    fill: none;
    stroke: currentColor;
    stroke-opacity: 0.16;
    stroke-width: 1.5;
  }
}

.login-panel {
  position: relative;
  z-index: 1;
  display: grid;
  grid-template-columns: minmax(0, 1fr) 440px;
  width: min(1080px, 100%);
  min-height: 580px;
  overflow: hidden;
  border: var(--rule-hair) solid var(--color-rule-2);
  border-radius: var(--radius-panel);
  background: var(--color-paper-2);
  box-shadow: var(--shadow-float);
  animation: login-panel-enter 560ms var(--ease-out) both,
    login-panel-float 10s ease-in-out 560ms infinite alternate;
  transition: box-shadow var(--dur-long) var(--ease-out);

  &:hover {
    box-shadow: var(--shadow-float-lift);
  }

  &:focus-within {
    animation-play-state: running, paused;
  }
}

@keyframes login-panel-enter {
  from {
    transform: translateY(18px);
  }

  to {
    transform: translateY(0);
  }
}

@keyframes login-panel-float {
  from {
    transform: translateY(0);
  }

  to {
    transform: translateY(-6px);
  }
}

@keyframes login-orbit {
  to {
    transform: rotate(360deg);
  }
}

.login-brief {
  position: relative;
  display: flex;
  flex-direction: column;
  justify-content: space-between;
  gap: var(--space-xl);
  padding: 40px;
  overflow: hidden;
  background: var(--color-brand);
  color: var(--color-brand-ink);
}

.brief-instrument {
  position: absolute;
  right: -150px;
  bottom: -170px;
  width: 480px;
  height: 480px;
  pointer-events: none;

  .instrument-arc {
    position: absolute;
    inset: 0;
    border: var(--rule-hair) solid var(--color-brand-rule);
    border-radius: 50%;
  }

  .instrument-arc-inner {
    inset: 96px;
    border-style: dashed;
    opacity: 0.65;
  }

  .instrument-node {
    position: absolute;
    top: 50%;
    left: 50%;
    width: 8px;
    height: 8px;
    background: var(--color-brand-accent);
    transform: translate(-50%, -50%) rotate(45deg);
  }
}

.brand-lockup,
.brief-copy,
.brief-ledger {
  position: relative;
  z-index: 1;
}

.brand-lockup {
  display: inline-flex;
  align-items: center;
  gap: var(--space-sm);
}

.brand-mark {
  display: grid;
  width: 36px;
  height: 36px;
  flex-shrink: 0;
  place-items: center;
  border: var(--rule-hair) solid var(--color-brand-rule);
  border-radius: var(--radius-field);
  background: var(--color-brand-2);
  color: var(--color-brand-accent);
  font-family: var(--font-display);
  font-size: var(--text-xs);
  font-weight: 500;
  letter-spacing: -0.02em;
}

.brand-text {
  display: flex;
  flex-direction: column;
  gap: 1px;
}

.brand-name {
  color: var(--color-brand-ink);
  font-size: var(--text-md);
  font-weight: 500;
  letter-spacing: -0.01em;
  line-height: 1.2;
}

.brand-tag {
  color: var(--color-brand-muted);
  font-size: var(--text-2xs);
  line-height: 1.3;
}

.brief-copy {
  max-width: 380px;

  h1 {
    color: var(--color-brand-ink);
    font-size: var(--text-2xl);
    font-weight: var(--display-weight);
    letter-spacing: -0.02em;
    line-height: 1.3;
    text-wrap: balance;
    word-break: keep-all;
  }

  p {
    margin-top: var(--space-sm);
    color: var(--color-brand-ink-2);
    font-size: var(--text-sm);
    line-height: 1.7;
    text-wrap: pretty;
  }
}

.brief-ledger {
  max-width: 380px;
}

.ledger-head {
  display: flex;
  align-items: center;
  gap: var(--space-xs);
  padding-bottom: var(--space-xs);
  border-bottom: var(--rule-hair) solid var(--color-brand-rule);
}

.ledger-mark {
  width: 14px;
  height: var(--rule-hair);
  flex-shrink: 0;
  background: var(--color-brand-accent);
}

.ledger-title {
  color: var(--color-brand-muted);
  font-size: var(--text-2xs);
  line-height: 1.4;
}

.capabilities {
  margin: 0;
  padding: 0;
  list-style: none;
}

.capability {
  display: grid;
  grid-template-columns: 28px minmax(0, 1fr);
  gap: var(--space-sm);
  align-items: baseline;
  padding: var(--space-xs) 0;
  border-bottom: var(--rule-hair) solid var(--color-brand-rule);

  &:last-child {
    border-bottom: 0;
  }
}

.capability-index {
  color: var(--color-brand-muted);
  font-size: var(--text-2xs);
  line-height: 1.5;
}

.capability-label {
  color: var(--color-brand-ink-2);
  font-size: var(--text-sm);
  line-height: 1.5;
}

.login-form-area {
  position: relative;
  display: grid;
  place-items: center;
  padding: 52px 44px;
  background: var(--color-paper-2);
}

.theme-toggle-login {
  position: absolute;
  top: var(--space-md);
  right: var(--space-md);
  display: grid;
  width: var(--control-h-sm);
  height: var(--control-h-sm);
  place-items: center;
  border: var(--rule-hair) solid transparent;
  border-radius: var(--radius-field);
  background: transparent;
  color: var(--color-muted);
  transition: color var(--dur-short) var(--ease-out),
    background-color var(--dur-short) var(--ease-out);

  &:hover {
    background: var(--color-paper-3);
    color: var(--color-ink);
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

.login-card {
  width: 100%;
  max-width: 340px;
}

.login-title {
  margin-bottom: var(--space-lg);

  h2 {
    color: var(--color-ink);
    font-size: var(--text-2xl);
    font-weight: 500;
    letter-spacing: -0.02em;
  }

  p {
    margin-top: var(--space-xs);
    color: var(--color-muted);
    font-size: var(--text-sm);
    line-height: 1.6;
  }
}

.submit-btn {
  width: 100%;
  height: var(--control-h);
  margin: var(--space-xs) 0 var(--space-md);
  font-weight: 500;
}

@media (max-width: 900px) {
  .login-panel {
    grid-template-columns: 1fr;
  }

  .login-brief {
    min-height: 380px;
    padding: 32px;
  }

  .login-form-area {
    padding: 44px 24px 36px;
  }
}

@media (max-width: 640px) {
  .login-shell {
    align-items: stretch;
    padding: 0;
  }

  .login-panel {
    min-height: 100vh;
    border: 0;
    border-radius: 0;
    box-shadow: none;
    animation: none;
  }

  .login-brief {
    min-height: auto;
    gap: var(--space-lg);
  }

  .brief-copy h1 {
    font-size: var(--text-2xl);
  }

  .brief-ledger {
    display: none;
  }
}

@media (prefers-reduced-motion: reduce) {
  .login-panel,
  .login-atmosphere .ring-orbit {
    animation: none;
  }
}
</style>
