<template>
  <div class="not-found page-card">
    <el-result icon="warning" :title="t('notFound.title')" :sub-title="t('notFound.subtitle')">
      <template #extra>
        <router-link to="/workspace">
          <el-button type="primary">{{ t('notFound.back') }}</el-button>
        </router-link>
      </template>
    </el-result>
  </div>
</template>

<script setup lang="ts">
import { useI18n } from 'vue-i18n';

const { t } = useI18n();
</script>

<style scoped lang="scss">
.not-found {
  padding: 48px 24px;
}
</style>
