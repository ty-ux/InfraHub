<template>
  <el-tag :type="tagType" :size="size" :effect="effect" :class="{ 'ih-status-pulse': status === 'RUNNING' }">
    {{ label }}
  </el-tag>
</template>

<script setup lang="ts">
import { computed } from 'vue';
import { useI18n } from 'vue-i18n';

interface Props {
  status: string;
  size?: 'large' | 'default' | 'small';
  effect?: 'dark' | 'light' | 'plain';
}

const props = withDefaults(defineProps<Props>(), {
  size: 'default',
  effect: 'light',
});

const { t } = useI18n();

const tagType = computed(() => {
  const status = props.status;
  // T-C: Contract 8.2 - CREATED=蓝, APPROVING=橙, APPROVED=绿, REJECTED=红, RUNNING=蓝(动画), SUCCESS=绿, FAILED=红, ROLLED_BACK=灰
  // 蓝色统一用合法的 Element Plus 类型 'primary'（渲染与默认蓝一致，同时消除非法的空 type 警告）
  switch (status) {
    case 'CREATED':
      return 'primary';  // blue/default
    case 'APPROVING':
      return 'warning';  // orange
    case 'APPROVED':
      return 'success';  // green
    case 'REJECTED':
      return 'danger';  // red
    case 'RUNNING':
      return 'primary';  // blue/default (parent adds animation)
    case 'SUCCESS':
      return 'success';  // green
    case 'FAILED':
      return 'danger';  // red
    case 'ROLLED_BACK':
      return 'info';  // gray
    default:
      return 'info';
  }
});

// 单据状态词表：已知状态取 i18n，未知状态保留后端原始值并显式标注，绝不默认成功
const label = computed(() => {
  const status = props.status;
  const labels: Record<string, string> = {
    CREATED: t('releaseStatus.created'),
    APPROVING: t('releaseStatus.approving'),
    APPROVED: t('releaseStatus.approved'),
    REJECTED: t('releaseStatus.rejected'),
    RUNNING: t('releaseStatus.running'),
    SUCCESS: t('releaseStatus.success'),
    FAILED: t('releaseStatus.failed'),
    ROLLED_BACK: t('releaseStatus.rolledBack'),
  };
  return labels[status] || (status ? t('releaseStatus.unknownRaw', { status }) : status);
});
</script>