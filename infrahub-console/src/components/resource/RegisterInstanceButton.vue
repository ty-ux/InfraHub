<template>
  <el-button v-if="canManage" size="small" type="primary" @click="open">登记实例</el-button>
  <el-dialog v-if="visible" v-model="visible" title="登记 Spring Boot JAR 实例" width="560px" append-to-body
    :show-close="!busy" :close-on-click-modal="!busy" :close-on-press-escape="!busy">
    <el-alert type="info" show-icon :closable="false" title="登记只建立部署目标，初始状态为未知。发布与健康探测完成后再确认实际状态。" />
    <el-form ref="formRef" :model="form" label-width="100px" :disabled="busy || !loaded" style="margin-top:16px">
      <el-form-item label="服务" prop="serviceId" :rules="required">
        <el-select v-model="form.serviceId" filterable style="width:100%">
          <el-option v-for="item in services" :key="Number(item.id)" :label="String(item.serviceName)" :value="Number(item.id)" />
        </el-select>
      </el-form-item>
      <el-form-item label="环境" prop="envId" :rules="required">
        <el-select v-model="form.envId" filterable style="width:100%" @change="form.hostId = undefined">
          <el-option v-for="item in envs" :key="Number(item.id)" :label="String(item.envName)" :value="Number(item.id)" />
        </el-select>
      </el-form-item>
      <el-form-item label="主机" prop="hostId" :rules="required">
        <el-select v-model="form.hostId" filterable :disabled="!form.envId" style="width:100%">
          <el-option v-for="host in matchingHosts" :key="host.id" :label="`${host.hostname} (${host.ip})`" :value="host.id" />
        </el-select>
      </el-form-item>
      <el-form-item label="实例名称" prop="instanceName" :rules="[{ required: true, whitespace: true, message: '请输入实例名称' }]">
        <el-input v-model="form.instanceName" maxlength="128" />
      </el-form-item>
    </el-form>
    <p v-if="failed" role="alert">{{ failed }}</p>
    <template #footer>
      <el-button :disabled="busy" @click="visible = false">取消</el-button>
      <el-button v-if="!loaded" :disabled="busy" @click="load">重新读取</el-button>
      <el-button type="primary" :loading="busy" :disabled="!loaded" @click="save">登记</el-button>
    </template>
  </el-dialog>
</template>
<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { ElMessage, type FormInstance } from 'element-plus'
import { useAuthStore } from '@/store/auth'
import { envApi, serviceApi } from '@/api/resource'
import { pageAssetHosts, type AssetHost } from '@/api/asset'
import { registerRuntimeInstance, type InstanceRegistration } from '@/api/deploymentTarget'
const emit = defineEmits<{ changed: [] }>()
const auth = useAuthStore()
const canManage = computed(() => auth.canAccess(['SYSTEM_ADMIN']))
const visible = ref(false)
const busy = ref(false)
const loaded = ref(false)
const failed = ref('')
const formRef = ref<FormInstance>()
const required = [{ required: true, message: '请选择' }]
const services = ref<Record<string, unknown>[]>([])
const envs = ref<Record<string, unknown>[]>([])
const hosts = ref<AssetHost[]>([])
const form = reactive<InstanceRegistration>({ instanceName: '' })
const matchingHosts = computed(() => hosts.value.filter(host => host.envId === form.envId))
async function open() {
  if (busy.value || !canManage.value) return
  Object.assign(form, { serviceId: undefined, envId: undefined, hostId: undefined, instanceName: '' })
  visible.value = true
  await load()
}
async function load() {
  if (busy.value) return
  busy.value = true
  loaded.value = false
  failed.value = ''
  try {
    const [svc, env, host] = await Promise.all([
      serviceApi.page({ pageNum: 1, pageSize: 200 }), envApi.page({ pageNum: 1, pageSize: 200 }),
      pageAssetHosts({ pageNum: 1, pageSize: 200 }),
    ])
    services.value = svc.records.filter(item => item.status === 'ENABLED')
    envs.value = env.records.filter(item => item.status === 'ENABLED')
    hosts.value = host.records
    loaded.value = true
  } catch { failed.value = '登记选项读取失败，请重试。' }
  finally { busy.value = false }
}
async function save() {
  if (!canManage.value || busy.value || !loaded.value || !(await formRef.value?.validate().catch(() => false))) return
  busy.value = true
  failed.value = ''
  try {
    await registerRuntimeInstance({ ...form, instanceName: form.instanceName.trim() })
    ElMessage.success('实例已登记，运行与健康状态待实测确认')
    visible.value = false
    emit('changed')
  } catch { failed.value = '登记失败，请检查主机环境、重复登记或正在执行的发布。' }
  finally { busy.value = false }
}
</script>
