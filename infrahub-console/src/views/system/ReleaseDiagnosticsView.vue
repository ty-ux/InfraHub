<template>
  <div class="release-diagnostics">
    <h2 class="page-title">发布诊断</h2>
    
    <!-- 序列健康状态区域 -->
    <el-card class="section-card" v-loading="loadingSequences">
      <template #header>
        <div class="card-header">
          <span class="card-title">序列健康状态</span>
          <el-tag v-if="unhealthyCount > 0" type="danger" effect="dark" class="status-tag">
            {{ unhealthyCount }} 个异常
          </el-tag>
          <el-tag v-else type="success" effect="dark" class="status-tag">
            全部健康
          </el-tag>
        </div>
      </template>
      
      <el-table 
        :data="sequenceHealth" 
        :row-class-name="getSequenceRowClass"
        style="width: 100%"
      >
        <el-table-column prop="tableName" label="表名" min-width="150" />
        <el-table-column prop="sequenceName" label="序列名" min-width="180" />
        <el-table-column prop="maxId" label="最大ID" width="100" align="right" />
        <el-table-column prop="sequenceValue" label="序列值" width="100" align="right" />
        <el-table-column label="健康状态" width="100" align="center">
          <template #default="{ row }">
            <el-tag :type="row.healthy ? 'success' : 'danger'" effect="dark">
              {{ row.healthy ? '健康' : '异常' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="建议修复SQL" min-width="350">
          <template #default="{ row }">
            <div v-if="!row.healthy && row.suggestedFixSql" class="fix-sql">
              <code>{{ row.suggestedFixSql }}</code>
              <el-button 
                type="primary" 
                link 
                size="small" 
                @click="copySql(row.suggestedFixSql)"
              >
                复制
              </el-button>
            </div>
            <span v-else-if="row.healthy" class="no-fix">-</span>
          </template>
        </el-table-column>
      </el-table>
      
      <el-alert
        v-if="unhealthyCount > 0"
        title="检测到序列异常"
        type="error"
        description="序列值小于表最大ID，可能导致主键冲突。请复制建议SQL手动修复。本页面不提供自动修复功能。"
        show-icon
        :closable="false"
        class="mt-4"
      />
    </el-card>
    
    <!-- 测试数据预览区域 -->
    <el-card class="section-card" v-loading="loadingTestData">
      <template #header>
        <div class="card-header">
          <span class="card-title">测试数据治理预览</span>
          <div>
            <el-button 
              type="primary" 
              size="small" 
              @click="generateCleanupSql"
              :loading="loadingCleanupSql"
              class="mr-2"
            >
              生成清理SQL
            </el-button>
            <el-tag type="info" effect="plain" class="status-tag">
              只读预览
            </el-tag>
          </div>
        </div>
      </template>
      
      <el-alert
        title="数据治理说明"
        type="info"
        description="本页面仅展示测试数据分组和推荐操作，不提供删除功能。V1.9新增安全清理SQL生成功能，脚本默认包含ROLLBACK，需管理员审阅后手工执行。"
        show-icon
        :closable="false"
        class="mb-4"
      />
      
      <el-collapse v-model="activeGroups">
        <el-collapse-item
          v-for="group in testDataGroups"
          :key="group.groupName"
          :name="group.groupName"
        >
          <template #title>
            <div class="group-title">
              <span class="group-name">{{ group.groupName }}</span>
              <el-tag 
                :type="group.protectedEvidence ? 'warning' : 'info'"
                size="small"
                class="ml-2"
              >
                {{ group.protectedEvidence ? '建议保留' : '可清理' }}
              </el-tag>
              <el-tag type="info" size="small" class="ml-2">
                {{ group.records.length }} 条
              </el-tag>
            </div>
          </template>
          
          <div class="group-content">
            <p class="group-description">{{ group.description }}</p>
            
            <el-alert
              :title="group.recommendedAction"
              :type="group.protectedEvidence ? 'warning' : 'info'"
              show-icon
              :closable="false"
              class="mb-3"
            />
            
            <el-table :data="group.records" size="small">
              <el-table-column prop="id" label="ID" width="80" />
              <el-table-column prop="name" label="名称" min-width="200" />
              <el-table-column prop="type" label="类型" width="150" />
              <el-table-column 
                prop="createdAt" 
                label="创建时间" 
                width="180"
              >
                <template #default="{ row }">
                  {{ row.createdAt ? formatDate(row.createdAt) : '-' }}
                </template>
              </el-table-column>
              <el-table-column prop="description" label="描述" min-width="200" />
            </el-table>
          </div>
        </el-collapse-item>
      </el-collapse>
    </el-card>

    <!-- SQL生成对话框 -->
    <el-dialog
      v-model="sqlDialogVisible"
      title="测试数据清理SQL脚本"
      width="80%"
      :close-on-click-modal="false"
      class="sql-dialog"
    >
      <el-alert
        title="安全提示"
        type="warning"
        description="此脚本默认包含ROLLBACK语句，不会自动提交删除操作。请在审阅后手工执行，执行前务必备份数据。"
        show-icon
        :closable="false"
        class="mb-4"
      />
      
      <div class="sql-info mb-4">
        <el-descriptions :column="2" border size="small">
          <el-descriptions-item label="文件名">{{ cleanupSql?.fileName }}</el-descriptions-item>
          <el-descriptions-item label="生成时间">{{ cleanupSql?.generatedAt ? formatDate(cleanupSql.generatedAt) : '-' }}</el-descriptions-item>
          <el-descriptions-item label="真实证据链" :span="2">
            <el-tag type="success">已保护（未包含在删除语句中）</el-tag>
          </el-descriptions-item>
        </el-descriptions>
      </div>

      <div class="sql-container">
        <pre class="sql-code"><code>{{ cleanupSql?.sql }}</code></pre>
      </div>

      <template #footer>
        <div class="dialog-footer">
          <el-button @click="sqlDialogVisible = false">关闭</el-button>
          <el-button type="primary" @click="copyCleanupSql">
            复制SQL
          </el-button>
          <el-button @click="downloadCleanupSql">
            下载SQL
          </el-button>
        </div>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import {
  getReleaseSequenceHealth,
  getReleaseTestDataPreview,
  generateTestDataCleanupSql,
  type DatabaseSequenceHealth,
  type TestDataPreviewGroup,
  type TestDataCleanupSqlResponse
} from '@/api/release-diagnostics'

// 序列健康状态
const sequenceHealth = ref<DatabaseSequenceHealth[]>([])
const loadingSequences = ref(false)

// 测试数据预览
const testDataGroups = ref<TestDataPreviewGroup[]>([])
const loadingTestData = ref(false)
const activeGroups = ref<string[]>([])

// V1.9: SQL清理脚本
const sqlDialogVisible = ref(false)
const loadingCleanupSql = ref(false)
const cleanupSql = ref<TestDataCleanupSqlResponse | null>(null)

// 计算属性：异常序列数量
const unhealthyCount = computed(() => {
  return sequenceHealth.value.filter(s => !s.healthy).length
})

// 获取序列行样式
const getSequenceRowClass = ({ row }: { row: DatabaseSequenceHealth }) => {
  return row.healthy ? 'healthy-row' : 'unhealthy-row'
}

// 复制SQL到剪贴板
const copySql = (sql: string) => {
  navigator.clipboard.writeText(sql).then(() => {
    ElMessage.success('SQL已复制到剪贴板')
  }).catch(() => {
    ElMessage.error('复制失败，请手动复制')
  })
}

// 格式化日期
const formatDate = (dateStr: string) => {
  const date = new Date(dateStr)
  return date.toLocaleString('zh-CN')
}

// 加载序列健康状态
const loadSequenceHealth = async () => {
  loadingSequences.value = true
  try {
    sequenceHealth.value = await getReleaseSequenceHealth()
  } catch (err) {
    console.error('Failed to load sequence health:', err)
    ElMessage.error('加载序列健康状态失败')
  } finally {
    loadingSequences.value = false
  }
}

// 加载测试数据预览
const loadTestDataPreview = async () => {
  loadingTestData.value = true
  try {
    testDataGroups.value = await getReleaseTestDataPreview()
    // 默认展开第一组
    if (testDataGroups.value.length > 0) {
      activeGroups.value = [testDataGroups.value[0].groupName]
    }
  } catch (err) {
    console.error('Failed to load test data preview:', err)
    ElMessage.error('加载测试数据预览失败')
  } finally {
    loadingTestData.value = false
  }
}

// V1.9: 生成清理SQL
const generateCleanupSql = async () => {
  loadingCleanupSql.value = true
  try {
    cleanupSql.value = await generateTestDataCleanupSql()
    sqlDialogVisible.value = true
    ElMessage.success('清理SQL生成成功')
  } catch (err) {
    console.error('Failed to generate cleanup SQL:', err)
    ElMessage.error('生成清理SQL失败')
  } finally {
    loadingCleanupSql.value = false
  }
}

// V1.9: 复制清理SQL
const copyCleanupSql = () => {
  if (!cleanupSql.value?.sql) return
  navigator.clipboard.writeText(cleanupSql.value.sql).then(() => {
    ElMessage.success('SQL脚本已复制到剪贴板')
  }).catch(() => {
    ElMessage.error('复制失败，请手动复制')
  })
}

// V1.9: 下载清理SQL
const downloadCleanupSql = () => {
  if (!cleanupSql.value) return
  const blob = new Blob([cleanupSql.value.sql], { type: 'text/plain;charset=utf-8' })
  const link = document.createElement('a')
  link.href = URL.createObjectURL(blob)
  link.download = cleanupSql.value.fileName
  document.body.appendChild(link)
  link.click()
  document.body.removeChild(link)
  URL.revokeObjectURL(link.href)
  ElMessage.success('SQL脚本已下载')
}

// 页面加载时获取数据
onMounted(() => {
  loadSequenceHealth()
  loadTestDataPreview()
})
</script>

<style scoped>
.release-diagnostics {
  padding: 0;
  max-width: 1400px;
  margin: 0 auto;
}

.page-title {
  font-size: var(--text-xl);
  font-weight: 500;
  letter-spacing: var(--tracking-display);
  line-height: 1.25;
  color: var(--color-ink);
}

.section-card {
  margin-bottom: var(--space-md);
}

.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.card-title {
  font-size: var(--text-md);
  font-weight: 500;
  color: var(--color-ink);
}

.status-tag {
  margin-left: var(--space-xs);
}

/* 序列行样式 */
:deep(.healthy-row) {
  background-color: var(--ih-primary-bg) !important;
}

:deep(.unhealthy-row) {
  background-color: var(--ih-danger-bg) !important;
}

:deep(.unhealthy-row:hover) {
  background-color: var(--ih-danger-border) !important;
}

/* 修复SQL样式 */
.fix-sql {
  display: flex;
  align-items: center;
  gap: var(--space-xs);
}

.fix-sql code {
  background-color: var(--ih-bg-subtle);
  padding: 4px 8px;
  border-radius: 4px;
  font-family: var(--ih-font-family-mono);
  font-size: 12px;
  color: var(--ih-danger);
  word-break: break-all;
  flex: 1;
}

.no-fix {
  color: var(--ih-text-secondary);
}

/* 测试数据分组样式 */
.group-title {
  display: flex;
  align-items: center;
  flex: 1;
}

.group-name {
  font-weight: 500;
}

.ml-2 {
  margin-left: 8px;
}

.group-content {
  padding: var(--space-xs) 0;
}

.group-description {
  margin-bottom: var(--space-md);
  color: var(--ih-text-secondary);
  line-height: 1.6;
}

.mb-3 {
  margin-bottom: var(--space-md);
}

.mt-4 {
  margin-top: 20px;
}

.mb-4 {
  margin-bottom: var(--space-md);
}

.mr-2 {
  margin-right: 8px;
}

/* SQL对话框样式 */
.sql-dialog :deep(.el-dialog__body) {
  padding-top: var(--space-xs);
}

.sql-info {
  background-color: var(--ih-bg-subtle);
  padding: 12px;
  border-radius: 4px;
}

.sql-container {
  background-color: var(--ih-bg-subtle);
  border: 1px solid var(--ih-border);
  border-radius: 4px;
  padding: 16px;
  max-height: 400px;
  overflow: auto;
}

.sql-code {
  margin: 0;
  font-family: var(--ih-font-family-mono);
  font-size: 13px;
  line-height: 1.6;
  color: var(--color-ink);
  white-space: pre-wrap;
  word-wrap: break-word;
}

.dialog-footer {
  display: flex;
  justify-content: flex-end;
  gap: var(--space-xs);
}
</style>
