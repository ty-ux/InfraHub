import { createI18n } from 'vue-i18n';
import zhCN from './locales/zh-CN';
import enUS from './locales/en-US';

const LOCALE_KEY = 'INFRAHUB_LOCALE';

function getInitialLocale() {
  const stored = localStorage.getItem(LOCALE_KEY);
  if (stored === 'zh-CN' || stored === 'en-US') {
    return stored;
  }
  return 'zh-CN';
}

export const i18n = createI18n({
  legacy: false,
  locale: getInitialLocale(),
  fallbackLocale: 'zh-CN',
  messages: {
    'zh-CN': zhCN,
    'en-US': enUS,
  },
});

export function setLocale(locale: 'zh-CN' | 'en-US') {
  i18n.global.locale.value = locale;
  localStorage.setItem(LOCALE_KEY, locale);
}
