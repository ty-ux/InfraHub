import { ref, watch, onMounted } from 'vue';

type Theme = 'light' | 'dark';

const THEME_STORAGE_KEY = 'infrahub-theme';

// Global reactive theme state
const currentTheme = ref<Theme>('light');

function applyTheme(theme: Theme) {
  document.documentElement.setAttribute('data-theme', theme);
  localStorage.setItem(THEME_STORAGE_KEY, theme);
}

function detectSystemTheme(): Theme {
  if (window.matchMedia && window.matchMedia('(prefers-color-scheme: dark)').matches) {
    return 'dark';
  }
  return 'light';
}

function getStoredTheme(): Theme | null {
  const stored = localStorage.getItem(THEME_STORAGE_KEY);
  if (stored === 'light' || stored === 'dark') {
    return stored;
  }
  return null;
}

export function useTheme() {
  const toggleTheme = () => {
    currentTheme.value = currentTheme.value === 'light' ? 'dark' : 'light';
    applyTheme(currentTheme.value);
  };

  const setTheme = (theme: Theme) => {
    currentTheme.value = theme;
    applyTheme(theme);
  };

  const initTheme = () => {
    // Priority: stored preference > system preference > default (light)
    const stored = getStoredTheme();
    if (stored) {
      currentTheme.value = stored;
    } else {
      currentTheme.value = detectSystemTheme();
    }
    applyTheme(currentTheme.value);
  };

  // Listen for system theme changes
  const mediaQuery = window.matchMedia('(prefers-color-scheme: dark)');
  const handleSystemChange = (e: MediaQueryListEvent) => {
    // Only auto-switch if no stored preference
    if (!getStoredTheme()) {
      currentTheme.value = e.matches ? 'dark' : 'light';
      applyTheme(currentTheme.value);
    }
  };

  onMounted(() => {
    initTheme();
    mediaQuery.addEventListener('change', handleSystemChange);
  });

  // Cleanup on unmount (though typically not needed for root component)
  watch(currentTheme, (newTheme) => {
    applyTheme(newTheme);
  });

  return {
    theme: currentTheme,
    toggleTheme,
    setTheme,
    initTheme,
    isDark: () => currentTheme.value === 'dark',
    isLight: () => currentTheme.value === 'light',
  };
}