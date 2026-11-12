<template>
  <div class="release-environment-policies">
    <div class="page-header">
      <h2 class="page-title">{{ t('releaseEnvironmentPolicy.title') }}</h2>
    </div>

    <!-- Filter Card -->
    <el-card class="filter-card">
      <el-form :inline="true" class="filter-form">
        <el-form-item :label="t('releaseEnvironmentPolicy.env')">
          <el-select
            v-model="filters.envId"
            clearable
            :placeholder="t('releaseEnvironmentPolicy.allEnvironments')"
            style="width: 200px"
          >
            <el-option v-for="env in envOptions" :key="env.id" :label="env.name" :value="env.id" />
          </el-select>
        </el-form-item>
        <el-form-item :label="t('releaseEnvironmentPolicy.action')">
          <el-select
            v-model="filters.action"
            clearable
            :placeholder="t('releaseEnvironmentPolicy.allActions')"
            style="width: 200px"
          >
            <el-option :label="t('releaseEnvironmentPolicy.actionEnum.EXECUTE_RELEASE')" value="EXECUTE_RELEASE" />
            <el-option :label="t('releaseEnvironmentPolicy.actionEnum.EXECUTE_ROLLBACK')" value="EXECUTE_ROLLBACK" />
          </el-select>
        </el-form-item>
        <el-form-item :label="t('releaseEnvironmentPolicy.riskLevel')">
          <el-select
            v-model="filters.riskLevel"
            clearable
            :placeholder="t('releaseEnvironmentPolicy.allRiskLevels')"
            style="width: 200px"
          >
            <el-option :label="t('releaseEnvironmentPolicy.riskLevelEnum.LOW')" value="LOW" />
            <el-option :label="t('releaseEnvironmentPolicy.riskLevelEnum.MEDIUM')" value="MEDIUM" />
            <el-option :label="t('releaseEnvironmentPolicy.riskLevelEnum.HIGH')" value="HIGH" />
            <el-option :label="t('releaseEnvironmentPolicy.riskLevelEnum.CRITICAL')" value="CRITICAL" />
            <el-option :label="t('releaseEnvironmentPolicy.riskLevelAllOption')" value="ALL" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button @click="loadData">{{ t('releaseEnvironmentPolicy.search') }}</el-button>
          <el-button link class="filter-reset" @click="handleResetFilters">
            {{ t('common.resetFilters') }}
          </el-button>
        </el-form-item>
      </el-form>
    </el-card>

    <!-- Table Card -->
    <el-card class="table-card">
      <template #header>
        <div class="table-header">
          <el-button class="table-refresh" @click="loadData">{{ t('common.refresh') }}</el-button>
        </div>
      </template>

      <!-- A failed read is a read failure: it never takes the rows away and never reads as no data -->
      <el-alert
        v-if="readFailed"
        class="ih-error-alert ih-error-alert--danger read-error"
        type="error"
        :closable="false"
        show-icon
      >
        <template #title>{{ t('common.readFailed') }}</template>
        <template #default>
          <div class="read-error-body">
            <span class="read-error-hint">{{ t('common.readFailedHint') }}</span>
            <el-button size="small" type="primary" plain @click="loadData">{{ t('common.retry') }}</el-button>
          </div>
        </template>
      </el-alert>

      <el-table :data="policies" v-loading="loading" style="width: 100%">
        <el-table-column prop="envId" :label="t('releaseEnvironmentPolicy.env')" min-width="140" show-overflow-tooltip>
          <template #default="{ row }">
            {{ envDisplay(row) }}
          </template>
        </el-table-column>
        <el-table-column prop="action" :label="t('releaseEnvironmentPolicy.action')" min-width="180" show-overflow-tooltip>
          <template #default="{ row }">
            {{ enumLabel('releaseEnvironmentPolicy.actionEnum', row.action) }}
          </template>
        </el-table-column>
        <el-table-column prop="riskLevel" :label="t('releaseEnvironmentPolicy.riskLevel')" min-width="140" show-overflow-tooltip>
          <template #default="{ row }">
            {{ enumLabel('releaseEnvironmentPolicy.riskLevelEnum', row.riskLevel) }}
          </template>
        </el-table-column>
        <el-table-column prop="enabled" width="110" align="center">
          <template #header>
            <span class="gate-header">
              <span>{{ t('releaseEnvironmentPolicy.enabled') }}</span>
              <el-tooltip :content="t('releaseEnvironmentPolicy.helpEnabled')" placement="top">
                <el-icon class="gate-help"><QuestionFilled /></el-icon>
              </el-tooltip>
            </span>
          </template>
          <template #default="{ row }">
            <el-switch
              :model-value="isSwitchOn(row, 'enabled')"
              :loading="isSaving(row)"
              :aria-label="gateSwitchAria(row, 'enabled')"
              @change="requestToggle(row, 'enabled', $event)"
            />
          </template>
        </el-table-column>
        <el-table-column prop="approvalRequired" width="130" align="center">
          <template #header>
            <span class="gate-header">
              <span>{{ t('releaseEnvironmentPolicy.approvalRequired') }}</span>
              <el-tooltip :content="t('releaseEnvironmentPolicy.helpApprovalRequired')" placement="top">
                <el-icon class="gate-help"><QuestionFilled /></el-icon>
              </el-tooltip>
            </span>
          </template>
          <template #default="{ row }">
            <el-switch
              :model-value="isSwitchOn(row, 'approvalRequired')"
              :disabled="!row.enabled"
              :loading="isSaving(row)"
              :aria-label="gateSwitchAria(row, 'approvalRequired')"
              @change="requestToggle(row, 'approvalRequired', $event)"
            />
          </template>
        </el-table-column>
        <el-table-column prop="precheckRequired" width="150" align="center">
          <template #header>
            <span class="gate-header">
              <span>{{ t('releaseEnvironmentPolicy.precheckRequired') }}</span>
              <el-tooltip :content="t('releaseEnvironmentPolicy.helpPrecheckRequired')" placement="top">
                <el-icon class="gate-help"><QuestionFilled /></el-icon>
              </el-tooltip>
            </span>
          </template>
          <template #default="{ row }">
            <el-switch
              :model-value="isSwitchOn(row, 'precheckRequired')"
              :disabled="!row.enabled"
              :loading="isSaving(row)"
              :aria-label="gateSwitchAria(row, 'precheckRequired')"
              @change="requestToggle(row, 'precheckRequired', $event)"
            />
          </template>
        </el-table-column>
        <el-table-column prop="blocked" width="110" align="center">
          <template #header>
            <span class="gate-header">
              <span>{{ t('releaseEnvironmentPolicy.blocked') }}</span>
              <el-tooltip :content="t('releaseEnvironmentPolicy.helpBlocked')" placement="top">
                <el-icon class="gate-help"><QuestionFilled /></el-icon>
              </el-tooltip>
            </span>
          </template>
          <template #default="{ row }">
            <el-switch
              :model-value="isSwitchOn(row, 'blocked')"
              :disabled="!row.enabled"
              :loading="isSaving(row)"
              :aria-label="gateSwitchAria(row, 'blocked')"
              @change="requestToggle(row, 'blocked', $event)"
            />
          </template>
        </el-table-column>
        <el-table-column prop="freezeWindowEnabled" width="140" align="center">
          <template #header>
            <span class="gate-header">
              <span>{{ t('releaseEnvironmentPolicy.freezeWindowEnabled') }}</span>
              <el-tooltip :content="t('releaseEnvironmentPolicy.helpFreezeWindowEnabled')" placement="top">
                <el-icon class="gate-help"><QuestionFilled /></el-icon>
              </el-tooltip>
            </span>
          </template>
          <template #default="{ row }">
            <el-tag :type="row.freezeWindowEnabled ? 'warning' : 'info'" size="small">
              {{ row.freezeWindowEnabled ? t('common.yes') : t('common.no') }}
            </el-tag>
          </template>
        </el-table-column>
        <!-- The id stays queryable without competing with the environment, the action and the gates -->
        <el-table-column prop="id" :label="t('common.id')" width="80" align="center" />
        <el-table-column :label="t('common.actions')" width="110" fixed="right" align="center">
          <template #default="{ row }">
            <div class="ih-table-actions">
              <el-tooltip :disabled="!editEntryHint(row)" :content="editEntryHint(row)" placement="top">
                <span>
                  <el-button
                    type="primary"
                    size="small"
                    class="action-primary"
                    :disabled="editEntryClosed(row)"
                    @click="openEditDialog(row)"
                  >
                    {{ t('releaseEnvironmentPolicy.edit') }}
                  </el-button>
                </span>
              </el-tooltip>
            </div>
          </template>
        </el-table-column>

        <template #empty>
          <!-- A failed read says so above; it is never dressed up as an empty list -->
          <div v-if="!readFailed" class="ih-empty-state empty-state">
            <template v-if="hasActiveFilters">
              <p class="empty-title">{{ t('common.noMatch') }}</p>
              <p class="empty-hint">{{ t('common.noMatchHint') }}</p>
              <el-button size="small" @click="handleResetFilters">{{ t('common.clearFilters') }}</el-button>
            </template>
            <template v-else>
              <p class="empty-title">{{ t('common.noData') }}</p>
              <p class="empty-hint">{{ t('common.noDataHint') }}</p>
              <el-button size="small" @click="loadData">{{ t('common.refresh') }}</el-button>
            </template>
          </div>
        </template>
      </el-table>
    </el-card>

    <!-- Edit Dialog: a write in flight freezes it - no field, no cancel, no close, no ESC, no mask -->
    <el-dialog
      v-model="editDialogVisible"
      :title="t('releaseEnvironmentPolicy.editPolicy')"
      width="600px"
      :show-close="!dialogSaving"
      :close-on-press-escape="!dialogSaving"
      :close-on-click-modal="!dialogSaving"
    >
      <el-form :model="currentPolicy" label-width="150px">
        <el-form-item :label="t('releaseEnvironmentPolicy.enabled')">
          <el-switch
            v-model="currentPolicy.enabled"
            :disabled="dialogSaving"
            :aria-label="t('releaseEnvironmentPolicy.enabled')"
          />
        </el-form-item>
        <el-form-item :label="t('releaseEnvironmentPolicy.approvalRequired')">
          <el-switch
            v-model="currentPolicy.approvalRequired"
            :disabled="!currentPolicy.enabled || dialogSaving"
            :aria-label="t('releaseEnvironmentPolicy.approvalRequired')"
          />
        </el-form-item>
        <el-form-item :label="t('releaseEnvironmentPolicy.precheckRequired')">
          <el-switch
            v-model="currentPolicy.precheckRequired"
            :disabled="!currentPolicy.enabled || dialogSaving"
            :aria-label="t('releaseEnvironmentPolicy.precheckRequired')"
          />
        </el-form-item>
        <el-form-item :label="t('releaseEnvironmentPolicy.blocked')">
          <el-switch
            v-model="currentPolicy.blocked"
            :disabled="!currentPolicy.enabled || dialogSaving"
            :aria-label="t('releaseEnvironmentPolicy.blocked')"
          />
        </el-form-item>
        <el-form-item :label="t('releaseEnvironmentPolicy.freezeWindowEnabled')">
          <el-switch
            v-model="currentPolicy.freezeWindowEnabled"
            :disabled="!currentPolicy.enabled || dialogSaving"
            :aria-label="t('releaseEnvironmentPolicy.freezeWindowEnabled')"
          />
        </el-form-item>
        <el-form-item v-if="currentPolicy.freezeWindowEnabled" :label="t('releaseEnvironmentPolicy.freezeWindow')">
          <el-time-select
            v-model="currentPolicy.freezeWindowStart"
            :max-time="currentPolicy.freezeWindowEnd"
            class="freeze-time"
            :placeholder="t('releaseEnvironmentPolicy.freezeWindowStartPlaceholder')"
            :disabled="dialogSaving"
          />
          <span class="freeze-separator">{{ t('common.to') }}</span>
          <el-time-select
            v-model="currentPolicy.freezeWindowEnd"
            :min-time="currentPolicy.freezeWindowStart"
            class="freeze-time"
            :placeholder="t('releaseEnvironmentPolicy.freezeWindowEndPlaceholder')"
            :disabled="dialogSaving"
          />
        </el-form-item>
        <el-form-item v-if="currentPolicy.freezeWindowEnabled" :label="t('releaseEnvironmentPolicy.freezeWindowDays')">
          <el-checkbox-group v-model="freezeWindowDaysArray" :disabled="dialogSaving">
            <el-checkbox :label="1">{{ t('weekday.1') }}</el-checkbox>
            <el-checkbox :label="2">{{ t('weekday.2') }}</el-checkbox>
            <el-checkbox :label="3">{{ t('weekday.3') }}</el-checkbox>
            <el-checkbox :label="4">{{ t('weekday.4') }}</el-checkbox>
            <el-checkbox :label="5">{{ t('weekday.5') }}</el-checkbox>
            <el-checkbox :label="6">{{ t('weekday.6') }}</el-checkbox>
            <el-checkbox :label="7">{{ t('weekday.7') }}</el-checkbox>
          </el-checkbox-group>
        </el-form-item>
        <el-form-item :label="t('releaseEnvironmentPolicy.description')">
          <el-input v-model="currentPolicy.description" type="textarea" :rows="3" :disabled="dialogSaving" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button :disabled="dialogSaving" @click="requestCloseDialog">{{ t('releaseEnvironmentPolicy.cancel') }}</el-button>
        <el-button type="primary" :loading="dialogSaving" :disabled="dialogSaving" @click="handleSaveCurrent">{{ t('releaseEnvironmentPolicy.save') }}</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, ref, onMounted } from 'vue'
import { useI18n } from 'vue-i18n'
import axios from 'axios'
import { ElMessage } from 'element-plus'
import { QuestionFilled } from '@element-plus/icons-vue'
import { releaseEnvironmentPolicyApi, type ReleaseEnvironmentPolicy } from '@/api/releaseEnvironmentPolicy'
import { envApi } from '@/api/resource'

const { t } = useI18n()

/** The flags the table edits in place. */
type ToggleField = 'enabled' | 'approvalRequired' | 'precheckRequired' | 'blocked'

/** Each in-place switch, named the way the column names it. */
const gateLabelKey: Record<ToggleField, string> = {
  enabled: 'releaseEnvironmentPolicy.enabled',
  approvalRequired: 'releaseEnvironmentPolicy.approvalRequired',
  precheckRequired: 'releaseEnvironmentPolicy.precheckRequired',
  blocked: 'releaseEnvironmentPolicy.blocked',
}

const loading = ref(false)
const policies = ref<ReleaseEnvironmentPolicy[]>([])
/** Whether the last read of the list failed: the rows on screen are then the ones read before it. */
const readFailed = ref(false)
const envOptions = ref<{ id: number; name: string }[]>([])
const filters = ref({
  envId: undefined as number | undefined,
  action: undefined as string | undefined,
  riskLevel: undefined as string | undefined
})

/** Whether any filter is on screen: an empty table is then a filter result, not an empty list. */
const hasActiveFilters = computed(
  () => !!filters.value.envId || !!filters.value.action || !!filters.value.riskLevel
)

/**
 * Clears the filters alone: no other state of the page - and no open draft - is touched. This page
 * applies its filters at read time, so the cleared filters only take effect through a read.
 */
function handleResetFilters() {
  filters.value.envId = undefined
  filters.value.action = undefined
  filters.value.riskLevel = undefined
  void loadData()
}
const editDialogVisible = ref(false)
const currentPolicy = ref<ReleaseEnvironmentPolicy>({} as ReleaseEnvironmentPolicy)
const freezeWindowDaysArray = ref<number[]>([])
/**
 * The policies whose write is in flight, by policy identity. Both entry points - the in-place
 * switches and the dialog - run on this one set, so a policy is never written twice at once, while
 * every other policy stays free to be saved on its own.
 */
const savingRows = ref<Set<string>>(new Set())
/** Whether the dialog is showing the policy being written right now; it freezes the dialog while it is. */
const dialogSaving = computed(
  () => editDialogVisible.value && savingRows.value.has(policyKey(currentPolicy.value)),
)
/** Only the newest read of the list may write its rows into the table. */
let loadSequence = 0

onMounted(async () => {
  await loadEnvs()
  await loadData()
})

function textOrEmpty(value: unknown): string {
  return typeof value === 'string' ? value : ''
}

/**
 * The environment page arrives already unwrapped, so its records sit on the page itself - one record
 * per environment, shown by envName, then by envCode, and an environment this console cannot name at
 * all still stays recognizable as the id it is, so no option is nameless.
 */
function envOption(record: Record<string, unknown>): { id: number; name: string } {
  const name =
    textOrEmpty(record.envName)
    || textOrEmpty(record.envCode)
    || envFallbackLabel(record.id)
  return { id: Number(record.id), name }
}

/** The label of an environment this console has no name for: its id, explicitly marked as one. */
function envFallbackLabel(id: unknown): string {
  return t('common.envFallback', { id: id === undefined || id === null ? '?' : String(id) })
}

/**
 * How a policy's environment is shown: the name the page lists for that id, and the id itself when
 * nothing lists it. No environment is invented for an id the page does not carry.
 */
function envDisplay(policy: ReleaseEnvironmentPolicy): string {
  return envOptions.value.find(env => env.id === policy.envId)?.name || envFallbackLabel(policy.envId)
}

/**
 * The label of an enum the backend sent. A value this build does not know stays readable as the value
 * itself: no label is invented for it.
 */
function enumLabel(namespace: string, value: string): string {
  if (!value) return '-'
  const key = `${namespace}.${value}`
  const label = t(key)
  return label === key ? value : label
}

/** The accessible name of an in-place switch: which policy it belongs to, and which gate it sets. */
function gateSwitchAria(policy: ReleaseEnvironmentPolicy, field: ToggleField): string {
  return t('releaseEnvironmentPolicy.gateSwitchAria', {
    env: envDisplay(policy),
    action: enumLabel('releaseEnvironmentPolicy.actionEnum', policy.action),
    gate: t(gateLabelKey[field]),
  })
}

async function loadEnvs() {
  try {
    const page = await envApi.page({})
    envOptions.value = page.records.map(envOption)
  } catch {
    // The shared interceptor reports the failure; an empty answer leaves the select empty
    envOptions.value = []
  }
}

/** The rows of the list endpoint that answer the filters on screen. */
function applyFilters(list: ReleaseEnvironmentPolicy[]): ReleaseEnvironmentPolicy[] {
  let rows = list
  if (filters.value.envId) {
    rows = rows.filter(p => p.envId === filters.value.envId)
  }
  if (filters.value.action) {
    rows = rows.filter(p => p.action === filters.value.action)
  }
  if (filters.value.riskLevel) {
    rows = rows.filter(p => p.riskLevel === filters.value.riskLevel)
  }
  return rows
}

/**
 * Reads the list. The endpoint answers with the policy array itself, so the rows are taken from it
 * directly - never through a second unwrapping - and an empty answer stays an empty table: no
 * policy is invented and no environment is defaulted. Reports whether the list could be read; a
 * failure keeps the rows that are already on screen, and the shared interceptor has reported it.
 */
async function loadData(): Promise<boolean> {
  const sequence = ++loadSequence
  loading.value = true
  try {
    const list = await releaseEnvironmentPolicyApi.list()
    if (sequence !== loadSequence) return true
    policies.value = applyFilters(list || [])
    readFailed.value = false
    return true
  } catch {
    // A failed read keeps the rows that are already on screen: it never empties the page, and it is
    // shown as a read failure rather than as an empty list. The shared interceptor has reported it.
    if (sequence !== loadSequence) return true
    readFailed.value = true
    return false
  } finally {
    if (sequence === loadSequence) loading.value = false
  }
}

function openEditDialog(policy: ReleaseEnvironmentPolicy) {
  // The dialog is frozen while its own write is in flight, and a policy already being written has no
  // open entry: neither that policy nor another one takes the dialog over mid-save
  if (dialogSaving.value || savingRows.value.has(policyKey(policy))) return
  currentPolicy.value = { ...policy }
  freezeWindowDaysArray.value = policy.freezeWindowDays
    ? policy.freezeWindowDays.split(',').map(Number)
    : []
  editDialogVisible.value = true
}

/** Closes the dialog for the operator; a write in flight freezes it, so this close is refused. */
function requestCloseDialog() {
  if (dialogSaving.value) return
  editDialogVisible.value = false
}

async function handleSaveCurrent() {
  const key = policyKey(currentPolicy.value)
  // No dialog is open, or the policy on screen is already being written: this entry stands down
  if (!editDialogVisible.value || savingRows.value.has(key)) return

  // The draft is copied, with the days it shows right now: the API is handed this snapshot alone,
  // never the form state the operator can still be typing into
  const payload: ReleaseEnvironmentPolicy = {
    ...currentPolicy.value,
    freezeWindowDays: freezeWindowDaysArray.value.join(','),
  }

  markSaving(key, true)
  try {
    await releaseEnvironmentPolicyApi.saveAll([payload])
  } catch (err) {
    // The refused write keeps the dialog open with everything the operator typed
    reportError(err, 'releaseEnvironmentPolicy.saveFailed')
    return
  } finally {
    markSaving(key, false)
  }

  ElMessage.success(t('releaseEnvironmentPolicy.saveSuccess'))
  // This callback answers the save it started: the dialog is closed only while it is still showing
  // that same policy, so no response closes a dialog another policy has taken over
  if (editDialogVisible.value && policyKey(currentPolicy.value) === key) {
    editDialogVisible.value = false
  }
  await refreshAfterSave()
}

/** The identity of a row: its id when it has one, its target otherwise. */
function policyKey(policy: ReleaseEnvironmentPolicy): string {
  if (policy.id !== undefined && policy.id !== null) return `id:${policy.id}`
  return `target:${policy.envId}:${policy.action}:${policy.riskLevel}`
}

function isSaving(policy: ReleaseEnvironmentPolicy): boolean {
  return savingRows.value.has(policyKey(policy))
}

/** Whether a row's edit entry is closed right now: the row's own write, or the dialog's own save. */
function editEntryClosed(policy: ReleaseEnvironmentPolicy): boolean {
  return isSaving(policy) || dialogSaving.value
}

/** Why a row's edit entry is closed, or nothing at all while it is open. */
function editEntryHint(policy: ReleaseEnvironmentPolicy): string {
  if (isSaving(policy)) return t('releaseEnvironmentPolicy.writePendingHint')
  if (dialogSaving.value) return t('releaseEnvironmentPolicy.dialogWritePendingHint')
  return ''
}

function markSaving(key: string, saving: boolean) {
  const keys = new Set(savingRows.value)
  if (saving) keys.add(key)
  else keys.delete(key)
  savingRows.value = keys
}

/** The row as it stands now: a refresh may have replaced the object a pending write started from. */
function currentRow(policy: ReleaseEnvironmentPolicy): ReleaseEnvironmentPolicy | undefined {
  const key = policyKey(policy)
  return policies.value.find(row => policyKey(row) === key)
}

/**
 * Whether that flag's switch is on. The switch is controlled by this value alone, so the row shows
 * the confirmed state until a write is accepted, and the value it reports is the one the operator
 * asked for - never a value the row was mutated into behind the switch's back.
 */
function isSwitchOn(policy: ReleaseEnvironmentPolicy, field: ToggleField): boolean {
  return policy[field] === true
}

/** Reports a failure the shared interceptor never sees; a rejected request is reported there. */
function reportError(err: unknown, fallbackKey: string) {
  if (axios.isAxiosError(err)) return
  ElMessage.error(err instanceof Error && err.message ? err.message : t(fallbackKey))
}

/**
 * Reloads the rows after an accepted write. The write itself has landed, so a refresh that fails is
 * reported as a refresh that failed: the operator is told what was saved, and nothing is sent twice.
 */
async function refreshAfterSave() {
  const refreshed = await loadData()
  if (!refreshed) ElMessage.error(t('releaseEnvironmentPolicy.saveRefreshFailed'))
}

/**
 * Writes one row from an in-place switch. A policy whose write is still in flight refuses a second
 * request from either entry point, so it is never written twice, while every other policy stays free
 * to be written on its own. The row keeps showing the value it was last confirmed with until the
 * write is accepted; a write the backend refuses leaves it exactly there.
 */
async function requestToggle(policy: ReleaseEnvironmentPolicy, field: ToggleField, value: boolean | string | number) {
  const next = value === true
  const key = policyKey(policy)
  if (savingRows.value.has(key)) return

  // The switch already shows the value the operator asks for: there is nothing to write
  const confirmed = policy[field]
  if (next === (confirmed === true)) return

  // Only a copy carrying this one change goes to the API, so the row on screen is never moved to a
  // value the backend has not accepted
  const payload: ReleaseEnvironmentPolicy = { ...policy }
  payload[field] = next

  markSaving(key, true)
  try {
    await releaseEnvironmentPolicyApi.saveAll([payload])
  } catch (err) {
    // The refused write changes nothing: the row still shows the value it was last confirmed with,
    // so no snapshot is put back over whatever a refresh has read since
    reportError(err, 'releaseEnvironmentPolicy.saveFailed')
    return
  } finally {
    markSaving(key, false)
  }

  ElMessage.success(t('releaseEnvironmentPolicy.saveSuccess'))
  // The accepted value lands on the row the table holds now - a refresh may have replaced the object
  // this write started from - and a row the filters have taken away is not put back
  const row = currentRow(policy)
  if (row) row[field] = next
  await refreshAfterSave()
}
</script>

<style scoped>
.release-environment-policies {
  padding: 0;
}

.page-header {
  margin-bottom: var(--space-md);
}

.page-title {
  min-width: 0;
  overflow-wrap: break-word;
  font-size: var(--text-xl);
  font-weight: 500;
  letter-spacing: var(--tracking-display);
  line-height: 1.25;
  color: var(--color-ink);
}

.filter-card {
  margin-bottom: var(--space-md);
}

.table-card {
  margin-bottom: var(--space-md);
}

.table-header {
  display: flex;
  align-items: center;
  gap: var(--ih-spacing-sm);
}

/* One primary action stands out in the row; the rest stay lightweight entries next to it */
.action-primary {
  font-weight: 500;
}

.read-error {
  margin-bottom: var(--ih-spacing-md);
}

.read-error-body {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--ih-spacing-sm);
}

.read-error-hint {
  font-size: var(--ih-font-size-sm);
  color: var(--ih-text-secondary);
}

.empty-state {
  padding: var(--ih-spacing-xl) 0;
}

.empty-title {
  margin: 0 0 var(--ih-spacing-1);
  font-size: var(--ih-font-size-md);
  color: var(--color-ink);
}

.empty-hint {
  margin: 0 0 var(--ih-spacing-md);
  font-size: var(--ih-font-size-sm);
  color: var(--ih-text-secondary);
}

/* What a gate means stays one hover away, and the header keeps reading as the gate's own name */
.gate-header {
  display: inline-flex;
  align-items: center;
  gap: var(--ih-spacing-1);
}

.gate-help {
  color: var(--ih-text-secondary);
  cursor: help;
}

.freeze-time {
  width: 140px;
}

.freeze-separator {
  margin: 0 var(--ih-spacing-4);
  color: var(--ih-text-secondary);
}

:deep(.el-table__empty-text) {
  width: 100%;
  line-height: 1.6;
}
</style>
