<template>
  <el-button size="small" @click="visible = true; load()">{{ t('collectionControl.title') }}</el-button>
  <el-dialog v-if="visible" v-model="visible" :title="t('collectionControl.title')" width="460px" append-to-body>
    <p>{{ t('collectionControl.scope') }}</p>
    <p>{{ t('collectionControl.restart') }}</p>
    <el-switch :model-value="state?.enabled ?? false" :loading="busy"
      :disabled="busy || !state || !canManage" :active-text="t('collectionControl.title')"
      @change="change" />
    <p v-if="state">{{ t('collectionControl.period', { seconds: state.periodMs / 1000 }) }}</p>
    <p v-if="failed" role="alert">{{ t('collectionControl.failed') }}</p>
    <el-button v-if="failed" size="small" @click="load">{{ t('common.refresh') }}</el-button>
  </el-dialog>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useAuthStore } from '@/store/auth'
import { getCollectionSchedule, setCollectionSchedule, type CollectionSchedule } from '@/api/monitorCollection'
const emit = defineEmits<{ changed: [] }>()
const { t } = useI18n()
const auth = useAuthStore()
const canManage = computed(() => auth.canAccess(['SYSTEM_ADMIN']))
const visible = ref(false)
const state = ref<CollectionSchedule | null>(null)
const busy = ref(false)
const failed = ref(false)
async function load() {
  if (busy.value) return
  busy.value = true
  failed.value = false
  try { state.value = await getCollectionSchedule() }
  catch { state.value = null; failed.value = true }
  finally { busy.value = false }
}
async function change(value: string | number | boolean) {
  if (!canManage.value || busy.value) return
  busy.value = true
  failed.value = false
  try {
    state.value = await setCollectionSchedule(value === true)
    emit('changed')
  } catch { failed.value = true }
  finally { busy.value = false }
}
</script>
