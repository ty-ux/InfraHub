<template>
  <el-empty :description="computedDescription" :image-size="imageSize" class="ih-empty-state">
    <template v-if="showAction" #default>
      <el-button v-if="type === 'release-orders'" type="primary" size="small" @click="emit('action')">
        新建发布单
      </el-button>
    </template>
  </el-empty>
</template>

<script setup lang="ts">
import { computed } from 'vue';

interface Props {
  type: 'release-orders' | 'rollback-orders' | 'deploy-records' | 'precheck-records' | 'config-check-pass' | 'config-check-fail';
  missingCount?: number;
  imageSize?: number;
  showAction?: boolean;
}

const props = withDefaults(defineProps<Props>(), {
  imageSize: 100,
  showAction: false,
});

interface Emits {
  (e: 'action'): void;
}

const emit = defineEmits<Emits>();

const computedDescription = computed(() => {
  switch (props.type) {
    case 'release-orders':
      return '暂无发布单';
    case 'rollback-orders':
      return '暂无回滚单';
    case 'deploy-records':
      return '暂无部署记录';
    case 'precheck-records':
      return '暂无预检记录';
    case 'config-check-pass':
      return '配置完整，可正常发布';
    case 'config-check-fail':
      return props.missingCount ? `缺少 ${props.missingCount} 项必需配置` : '配置不完整';
    default:
      return '暂无数据';
  }
});
</script>