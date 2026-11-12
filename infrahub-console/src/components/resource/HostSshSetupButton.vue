<template>
  <el-button v-if="canManage" link type="primary" size="small" @click.stop="open">SSH 配置</el-button>
  <el-dialog v-if="visible" v-model="visible" title="主机 SSH 配置" width="560px" append-to-body
    :show-close="!busy" :close-on-click-modal="!busy" :close-on-press-escape="!busy">
    <el-alert type="info" :closable="false" show-icon
      title="配置通过私钥路径引用连接服务器；保存配置后仍需实测确认连接。" />
    <p>先在“密钥引用”登记：提供方填 LOCAL_FILE，路径填本地后端可读取的私钥绝对路径。此处请选择无口令的部署专用私钥引用，保护好私钥文件权限。</p>
    <el-form ref="formRef" :model="form" label-width="110px" :disabled="busy || !loaded">
      <el-form-item label="SSH 用户名" prop="username" :rules="[{ required: true, message: '请输入用户名' }]">
        <el-input v-model="form.username" maxlength="64" />
      </el-form-item>
      <el-form-item label="SSH 端口"><el-input-number v-model="form.sshPort" :min="1" :max="65535" /></el-form-item>
      <el-form-item label="私钥路径引用" prop="privateKeySecretRefId" :rules="[{ required: true, message: '请选择私钥路径引用' }]">
        <el-select v-model="form.privateKeySecretRefId" filterable style="width:100%">
          <el-option v-for="item in refs" :key="Number(item.id)" :label="String(item.secretKey)" :value="Number(item.id)" />
        </el-select>
      </el-form-item>
      <el-form-item label="启用连接"><el-switch v-model="form.enabled" /></el-form-item>
    </el-form>
    <p v-if="failed" role="alert">{{ failed }}</p>
    <template #footer>
      <el-button :disabled="busy" @click="visible = false">取消</el-button>
      <el-button v-if="!loaded" :disabled="busy" @click="load">重新读取</el-button>
      <el-button type="primary" :loading="busy" :disabled="!loaded" @click="save">保存</el-button>
    </template>
  </el-dialog>
</template>
<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { ElMessage, type FormInstance } from 'element-plus'
import { useAuthStore } from '@/store/auth'
import { secretRefApi } from '@/api/resource'
import { getHostSshSetup, saveHostSshSetup, type HostSshSetup } from '@/api/deploymentTarget'
const props = defineProps<{ hostId: number }>()
const auth = useAuthStore()
const canManage = computed(() => auth.canAccess(['SYSTEM_ADMIN']))
const visible = ref(false)
const busy = ref(false)
const loaded = ref(false)
const failed = ref('')
const formRef = ref<FormInstance>()
const refs = ref<Record<string, unknown>[]>([])
const form = reactive<HostSshSetup>({ username: 'root', sshPort: 22, enabled: true })
async function open() {
  if (busy.value || !canManage.value) return
  visible.value = true
  await load()
}
async function load() {
  if (busy.value) return
  loaded.value = false
  failed.value = ''
  busy.value = true
  Object.assign(form, { username: 'root', sshPort: 22, privateKeySecretRefId: undefined, enabled: true })
  try {
    const [current, options] = await Promise.all([getHostSshSetup(props.hostId), secretRefApi.page({ pageNum: 1, pageSize: 200 })])
    refs.value = options.records.filter(item => item.secretProvider === 'LOCAL_FILE')
    if (current) Object.assign(form, current)
    loaded.value = true
  } catch { failed.value = 'SSH 配置读取失败，请确认后端已更新后重试。' }
  finally { busy.value = false }
}
async function save() {
  if (!canManage.value || busy.value || !loaded.value || !(await formRef.value?.validate().catch(() => false))) return
  busy.value = true
  failed.value = ''
  try {
    await saveHostSshSetup(props.hostId, {
      username: form.username.trim(), sshPort: form.sshPort,
      privateKeySecretRefId: form.privateKeySecretRefId, enabled: form.enabled,
    })
    ElMessage.success('SSH 配置已保存，连接状态以实测为准')
    visible.value = false
  } catch { failed.value = 'SSH 配置保存失败，请检查私钥引用和文件路径后重试。' }
  finally { busy.value = false }
}
</script>
