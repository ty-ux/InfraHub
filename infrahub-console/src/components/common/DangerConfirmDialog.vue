<template>
  <el-dialog
    v-model="visible"
    :title="title"
    :width="width"
    :before-close="handleClose"
    destroy-on-close
    class="ih-confirm-dialog ih-confirm-dialog--danger"
  >
    <el-alert type="warning" :closable="false" show-icon class="ih-confirm-warning">
      <template #title>
        <span class="ih-confirm-warning-text">{{ warningText }}</span>
      </template>
    </el-alert>

    <div v-if="summaryItems && summaryItems.length > 0" class="ih-confirm-summary">
      <el-descriptions :column="1" border size="small">
        <el-descriptions-item
          v-for="item in summaryItems"
          :key="item.label"
          :label="item.label"
        >
          {{ item.value || '-' }}
        </el-descriptions-item>
      </el-descriptions>
    </div>

    <div v-if="extraContent" class="ih-confirm-extra">
      {{ extraContent }}
    </div>

    <template #footer>
      <el-button @click="handleClose">取消</el-button>
      <el-button type="primary" :loading="loading" class="danger-confirm-btn" @click="handleConfirm">
        {{ confirmText }}
      </el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { computed } from 'vue';

interface SummaryItem {
  label: string;
  value: string;
}

interface Props {
  modelValue: boolean;
  title?: string;
  warningText?: string;
  summaryItems?: SummaryItem[];
  confirmText?: string;
  width?: string | number;
  loading?: boolean;
  extraContent?: string;
}

const props = withDefaults(defineProps<Props>(), {
  title: '危险操作确认',
  warningText: '此操作具有风险，请确认后继续。',
  confirmText: '确认执行',
  width: '480px',
  loading: false,
});

interface Emits {
  (e: 'update:modelValue', value: boolean): void;
  (e: 'confirm'): void;
  (e: 'cancel'): void;
}

const emit = defineEmits<Emits>();

const visible = computed({
  get: () => props.modelValue,
  set: (val) => emit('update:modelValue', val),
});

function handleClose() {
  emit('cancel');
  visible.value = false;
}

function handleConfirm() {
  emit('confirm');
}
</script>

<style scoped lang="scss">
.ih-confirm-warning {
  margin-bottom: var(--ih-spacing-lg);
  border-radius: var(--ih-radius-md);
}

.ih-confirm-warning-text {
  font-size: 14px;
  color: var(--ih-warning);
  font-weight: 500;
}

.ih-confirm-summary {
  margin-bottom: var(--ih-spacing-lg);
}

.ih-confirm-extra {
  margin-top: var(--ih-spacing-md);
  font-size: 13px;
  color: var(--ih-text-secondary);
}

.danger-confirm-btn {
  background-color: var(--ih-danger);
  border-color: var(--ih-danger);

  &:hover,
  &:focus {
    background-color: var(--ih-danger-hover);
    border-color: var(--ih-danger-hover);
  }
}
</style>