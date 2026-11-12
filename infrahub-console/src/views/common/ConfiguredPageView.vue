<template>
  <CrudPage v-if="config" :config="config" />
  <el-result v-else icon="warning" :title="t('page.missingConfigTitle')" :sub-title="t('page.missingConfigSubtitle')" />
</template>

<script setup lang="ts">
import { computed } from 'vue';
import { useRoute } from 'vue-router';
import { useI18n } from 'vue-i18n';
import CrudPage from '@/components/page/CrudPage.vue';
import { getPageConfig } from '../configs/page-configs';

const route = useRoute();
const { t } = useI18n();
const config = computed(() => getPageConfig(String(route.meta.pageKey || '')));
</script>
