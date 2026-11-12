<template>
  <el-alert :type="alertType" :title="computedTitle" :description="detail" show-icon :closable="closable" class="ih-error-alert ih-error-alert--danger" />
</template>

<script setup lang="ts">
import { computed } from 'vue';

interface Props {
  errorType: 'create-failed' | 'execute-failed' | 'precheck-failed' | 'config-missing' | 'network' | 'permission';
  detail?: string;
  failCount?: number;
  missingKey?: string;
  closable?: boolean;
}

const props = withDefaults(defineProps<Props>(), {
  closable: false,
});

const alertType = computed(() => {
  return 'error';
});

const computedTitle = computed(() => {
  switch (props.errorType) {
    case 'create-failed':
      return props.detail ? `创建失败：${props.detail}` : '创建失败';
    case 'execute-failed':
      return props.detail ? `执行失败：${props.detail}` : '执行失败';
    case 'precheck-failed':
      return props.failCount ? `预检未通过，共 ${props.failCount} 项失败` : '预检未通过';
    case 'config-missing':
      return props.missingKey ? `缺少必需配置：${props.missingKey}` : '缺少必需配置';
    case 'network':
      return '网络错误，请稍后重试';
    case 'permission':
      return '权限不足，请联系管理员';
    default:
      return '操作失败';
  }
});
</script>