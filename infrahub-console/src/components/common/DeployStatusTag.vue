<template>
  <el-tag :type="tagType" :size="size" :effect="effect" :class="{ 'ih-status-pulse': status === 'RUNNING' }">
    {{ label }}
  </el-tag>
</template>

<script setup lang="ts">
import { computed } from 'vue';
import { useI18n } from 'vue-i18n';

interface Props {
  status: string | null | undefined;
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
  // T-E: Contract 8.7 - RUNNING=蓝, SUCCESS=绿, FAILED=红
  // 蓝色统一用合法的 Element Plus 类型 'primary'（渲染与默认蓝一致，同时消除非法的空 type 警告）
  switch (status) {
    case 'RUNNING':
      return 'primary';  // blue/default
    case 'SUCCESS':
      return 'success';  // green
    case 'FAILED':
      return 'danger';  // red
    default:
      return 'info';  // gray for "尚未执行"
  }
});

// 部署状态词表：缺失的状态仍按“尚未执行”展示（部署记录本来就可能没有结果），
// 非空但未知的状态保留后端原始值并显式标注，绝不默认成功
const label = computed(() => {
  const status = props.status;
  if (!status) return t('deployStatus.notStarted');
  const labels: Record<string, string> = {
    RUNNING: t('deployStatus.running'),
    SUCCESS: t('deployStatus.success'),
    FAILED: t('deployStatus.failed'),
    // 服务端明确写入的“结果未知”：可能仍在运行，不能显示成失败
    UNKNOWN: t('deployStatus.unknown'),
  };
  return labels[status] || t('deployStatus.unknownRaw', { status });
});
</script>