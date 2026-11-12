<template>
  <el-dialog
    :model-value="modelValue"
    title="选择回滚候选版本"
    width="960px"
    @update:model-value="emit('update:modelValue', $event)"
  >
    <div v-if="currentVersionNo" class="current-version-bar">
      <span>当前版本：</span>
      <el-tag type="info" size="small">{{ currentVersionNo }}</el-tag>
    </div>

    <el-table
      v-loading="loading"
      :data="candidates"
      empty-text="暂无候选版本"
      highlight-current-row
      @current-change="selectCandidate"
    >
      <el-table-column prop="versionNo" label="版本号" min-width="140" />
      <el-table-column prop="isCurrent" label="当前" width="70">
        <template #default="{ row }">
          <el-tag v-if="row.isCurrent" type="info" size="small">是</el-tag>
          <span v-else>-</span>
        </template>
      </el-table-column>
      <el-table-column prop="deployedAt" label="部署时间" min-width="160">
        <template #default="{ row }">
          {{ row.deployedAt ? formatDateTime(row.deployedAt) : '-' }}
        </template>
      </el-table-column>
      <el-table-column prop="rollbackCount" label="回滚次数" width="90">
        <template #default="{ row }">
          <span :class="{ 'text-warning': row.rollbackCount > 0 }">{{ row.rollbackCount }}</span>
        </template>
      </el-table-column>
      <el-table-column prop="recommendationScore" label="推荐分值" width="110">
        <template #default="{ row }">
          <el-progress
            :percentage="row.recommendationScore"
            :color="scoreColor(row.recommendationScore)"
            :stroke-width="14"
            :text-inside="true"
            style="width: 80px"
          />
        </template>
      </el-table-column>
      <el-table-column prop="recommendationReason" label="推荐理由" min-width="200" show-overflow-tooltip />
    </el-table>

    <!-- V-C: Selected candidate detail panel -->
    <div v-if="selected" class="candidate-detail-panel">
      <div class="detail-header">
        <span class="detail-title">候选版本详情</span>
        <el-tag :type="scoreLevelType" size="small">{{ scoreLevelLabel }}</el-tag>
      </div>

      <div class="detail-compare-row">
        <div class="compare-item">
          <span class="compare-label">当前版本</span>
          <el-tag type="info" size="small">{{ currentVersionNo || '-' }}</el-tag>
        </div>
        <div class="compare-arrow">→</div>
        <div class="compare-item">
          <span class="compare-label">回滚目标</span>
          <el-tag type="success" size="small">{{ selected.versionNo }}</el-tag>
        </div>
      </div>

      <div v-if="selected.recommendationReason" class="detail-reason">
        <div class="reason-label">推荐理由</div>
        <div class="reason-content">{{ selected.recommendationReason }}</div>
      </div>

      <div class="detail-meta-row">
        <div class="meta-item">
          <span class="meta-label">部署时间</span>
          <span class="meta-value">{{ selected.deployedAt ? formatDateTime(selected.deployedAt) : '未知' }}</span>
        </div>
        <div class="meta-item">
          <span class="meta-label">历史回滚</span>
          <span class="meta-value" :class="{ 'text-warning': selected.rollbackCount > 0 }">
            {{ selected.rollbackCount > 0 ? `${selected.rollbackCount} 次` : '无回滚记录' }}
          </span>
        </div>
        <div class="meta-item">
          <span class="meta-label">推荐分值</span>
          <span class="meta-value">{{ selected.recommendationScore }}</span>
        </div>
      </div>
    </div>

    <div v-if="total > pageSize" class="pagination-bar">
      <el-pagination
        v-model:current-page="pageNum"
        :page-size="pageSize"
        :total="total"
        layout="total, prev, pager, next"
        @current-change="loadCandidates"
      />
    </div>

    <template #footer>
      <el-button @click="emit('update:modelValue', false)">取消</el-button>
      <el-button
        type="primary"
        :disabled="!selected || selected.isCurrent"
        @click="confirmSelection"
      >
        选择该版本
      </el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { getRollbackCandidates, type RollbackCandidateItem } from '@/api/release';

const props = defineProps<{
  modelValue: boolean;
  releaseOrderId: number | null;
  currentVersionId?: number;
  currentVersionNo?: string;
}>();

const emit = defineEmits<{
  (e: 'update:modelValue', value: boolean): void;
  (e: 'select', candidate: RollbackCandidateItem): void;
}>();

const loading = ref(false);
const candidates = ref<RollbackCandidateItem[]>([]);
const selected = ref<RollbackCandidateItem | null>(null);
const total = ref(0);
const pageNum = ref(1);
const pageSize = 20;

const scoreLevelType = computed(() => {
  if (!selected.value) return 'info';
  const s = selected.value.recommendationScore;
  if (s >= 80) return 'success';
  if (s >= 50) return 'warning';
  return 'danger';
});

const scoreLevelLabel = computed(() => {
  if (!selected.value) return '';
  const s = selected.value.recommendationScore;
  if (s >= 80) return '推荐';
  if (s >= 50) return '一般';
  return '不推荐';
});

function formatDateTime(date: string | null | undefined): string {
  if (!date) return '-';
  const str = String(date);
  if (str.includes('T')) {
    return str.replace('T', ' ').substring(0, 19);
  }
  return str;
}

function scoreColor(score: number): string {
  if (score >= 80) return 'var(--color-ok)';
  if (score >= 50) return 'var(--color-accent)';
  return 'var(--color-bad)';
}

function selectCandidate(row: RollbackCandidateItem | null) {
  selected.value = row;
}

async function loadCandidates() {
  if (!props.releaseOrderId) return;
  loading.value = true;
  try {
    const res = await getRollbackCandidates(props.releaseOrderId, {
      pageNum: pageNum.value,
      pageSize,
    });
    candidates.value = res.records;
    total.value = res.total;
  } finally {
    loading.value = false;
  }
}

function confirmSelection() {
  if (selected.value && !selected.value.isCurrent) {
    emit('select', selected.value);
    emit('update:modelValue', false);
  }
}

watch(
  () => props.modelValue,
  (val) => {
    if (val && props.releaseOrderId) {
      selected.value = null;
      pageNum.value = 1;
      loadCandidates();
    }
  },
);
</script>

<style scoped lang="scss">
.current-version-bar {
  margin-bottom: var(--ih-section-gap);
  padding: var(--ih-spacing-sm);
  border: 1px solid var(--ih-border);
  border-radius: var(--ih-radius-md);
  background: transparent;
  font-size: 13px;
}

.candidate-detail-panel {
  margin-top: var(--ih-section-gap);
  padding: var(--ih-spacing-sm);
  border: 1px solid var(--ih-border);
  border-radius: var(--ih-radius-md);
  background: transparent;
}

.detail-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: var(--ih-spacing-sm);
}

.detail-title {
  font-weight: 500;
  font-size: 14px;
}

.detail-compare-row {
  display: flex;
  align-items: center;
  gap: var(--ih-spacing-sm);
  margin-bottom: var(--ih-spacing-sm);
  padding: var(--ih-spacing-3);
  border: 1px solid var(--ih-border);
  border-radius: var(--ih-radius-sm);
  background: var(--ih-bg-card);
}

.compare-item {
  display: flex;
  align-items: center;
  gap: var(--ih-spacing-3);
}

.compare-label {
  font-size: 12px;
  color: var(--ih-text-secondary);
}

.compare-arrow {
  font-size: 18px;
  color: var(--ih-text-secondary);
}

.detail-reason {
  margin-bottom: var(--ih-spacing-sm);
}

.reason-label {
  font-size: 12px;
  color: var(--ih-text-secondary);
  margin-bottom: var(--ih-spacing-3);
}

.reason-content {
  font-size: 13px;
  line-height: 1.6;
  color: var(--ih-text-primary);
  padding: var(--ih-spacing-3);
  border-radius: var(--ih-radius-sm);
  background: transparent;
  border: 1px solid var(--ih-border);
}

.detail-meta-row {
  display: flex;
  gap: var(--ih-spacing-lg);
}

.meta-item {
  display: flex;
  align-items: center;
  gap: var(--ih-spacing-3);
}

.meta-label {
  font-size: 12px;
  color: var(--ih-text-secondary);
}

.meta-value {
  font-size: 13px;
}

.text-warning {
  color: var(--ih-warning);
  font-weight: 500;
}

.pagination-bar {
  display: flex;
  justify-content: flex-end;
  margin-top: var(--ih-section-gap);
}
</style>
