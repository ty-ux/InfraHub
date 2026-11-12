<template>
  <el-config-provider :locale="elementLocale">
    <router-view />
  </el-config-provider>
</template>

<script setup lang="ts">
import { computed } from 'vue';
import { useI18n } from 'vue-i18n';
import { ElConfigProvider } from 'element-plus';
import zhCn from 'element-plus/es/locale/lang/zh-cn';
import enUs from 'element-plus/es/locale/lang/en';
import { useTheme } from '@/composables/useTheme';

useTheme();

const { locale } = useI18n();
const elementLocale = computed(() => (locale.value.startsWith('en') ? enUs : zhCn));
</script>
