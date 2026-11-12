import { beforeEach, afterEach, describe, expect, it, vi } from 'vitest'
import { defineComponent } from 'vue'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import ElementPlus, { ElDialog, ElInput, ElOption, ElSelect } from 'element-plus'
import HostSshSetupButton from '../HostSshSetupButton.vue'
import RegisterInstanceButton from '../RegisterInstanceButton.vue'
import { getHostSshSetup, saveHostSshSetup, registerRuntimeInstance } from '@/api/deploymentTarget'
import { secretRefApi, envApi, serviceApi } from '@/api/resource'
import { pageAssetHosts } from '@/api/asset'
const { allowed } = vi.hoisted(() => ({ allowed: { value: true } }))
vi.mock('@/store/auth', () => ({ useAuthStore: () => ({ canAccess: () => allowed.value }) }))
vi.mock('@/api/deploymentTarget', () => ({ getHostSshSetup: vi.fn(), saveHostSshSetup: vi.fn(), registerRuntimeInstance: vi.fn() }))
vi.mock('@/api/resource', () => ({ secretRefApi: { page: vi.fn() }, envApi: { page: vi.fn() }, serviceApi: { page: vi.fn() } }))
vi.mock('@/api/asset', () => ({ pageAssetHosts: vi.fn() }))
let wrapper: VueWrapper | undefined
const options = { global: { plugins: [ElementPlus], stubs: { teleport: true, ElSelect: defineComponent({ name: 'ElSelect', props: ['modelValue', 'disabled'], emits: ['update:modelValue'], template: '<select :value="modelValue" :disabled="disabled"><slot /></select>' }), ElOption: defineComponent({ name: 'ElOption', props: ['label', 'value'], template: '<option :value="value">{{ label }}</option>' }), ElDialog: defineComponent({ name: 'ElDialog', props: ['modelValue'], template: '<div v-if="modelValue"><slot /><slot name="footer" /></div>' }) } } }
async function click(label: string) {
  const button = wrapper!.findAll('button').find(item => item.text() === label)
  expect(button, label).toBeDefined()
  await button!.trigger('click'); await flushPromises()
}
function page(records: any[]) { return { records, total: records.length, pageNum: 1, pageSize: 200 } }
beforeEach(() => {
  vi.resetAllMocks(); allowed.value = true
  vi.mocked(getHostSshSetup).mockResolvedValue(null)
  vi.mocked(secretRefApi.page).mockResolvedValue(page([
    { id: 11, secretKey: 'deployment-key', secretProvider: 'LOCAL_FILE' },
    { id: 12, secretKey: 'inline-credential', secretProvider: 'INLINE' },
  ]))
  vi.mocked(serviceApi.page).mockResolvedValue(page([{ id: 15, serviceName: 'InfraHub', status: 'ENABLED' }]))
  vi.mocked(envApi.page).mockResolvedValue(page([{ id: 30, envName: 'example', status: 'ENABLED' }]))
  vi.mocked(pageAssetHosts).mockResolvedValue(page([
    { id: 34, hostname: 'server34', ip: '192.0.2.1', envId: 30 },
    { id: 99, hostname: 'other-env', ip: '192.0.2.2', envId: 99 },
  ]))
})
afterEach(() => { wrapper?.unmount(); wrapper = undefined; document.body.innerHTML = '' })
describe('host SSH setup', () => {
  it('hides administrative controls from viewers', () => {
    allowed.value = false; wrapper = mount(HostSshSetupButton, { ...options, props: { hostId: 34 } })
    expect(wrapper.find('button').exists()).toBe(false); expect(getHostSshSetup).not.toHaveBeenCalled()
  })
  it('offers only file references and submits IDs instead of private-key bodies', async () => {
    wrapper = mount(HostSshSetupButton, { ...options, props: { hostId: 34 } }); await click('SSH 配置')
    expect(wrapper.findAllComponents(ElOption).map(option => option.props('label'))).toEqual(['deployment-key'])
    await wrapper.findComponent(ElSelect).vm.$emit('update:modelValue', 11)
    await click('保存')
    expect(saveHostSshSetup).toHaveBeenCalledWith(34, { username: 'root', sshPort: 22, privateKeySecretRefId: 11, enabled: true })
    expect(wrapper.findComponent(ElDialog).exists()).toBe(false)
  })
  it('refuses to save when authoritative configuration cannot be read', async () => {
    vi.mocked(getHostSshSetup).mockRejectedValue(new Error('unavailable'))
    wrapper = mount(HostSshSetupButton, { ...options, props: { hostId: 34 } }); await click('SSH 配置')
    expect(wrapper.text()).toContain('读取失败')
    const save = wrapper.findAll('button').find(button => button.text() === '保存')!
    expect(save.attributes('disabled')).toBeDefined(); expect(saveHostSshSetup).not.toHaveBeenCalled()
  })
  it('retains entered values after a failed save', async () => {
    vi.mocked(saveHostSshSetup).mockRejectedValue(new Error('unreadable path'))
    wrapper = mount(HostSshSetupButton, { ...options, props: { hostId: 34 } }); await click('SSH 配置')
    await wrapper.findComponent(ElInput).setValue('deploy')
    wrapper.findComponent(ElSelect).vm.$emit('update:modelValue', 11)
    await click('保存')
    expect(wrapper.findComponent(ElDialog).props('modelValue')).toBe(true)
    expect(wrapper.findComponent(ElInput).props('modelValue')).toBe('deploy')
    expect(wrapper.text()).toContain('保存失败')
  })
})
describe('instance registration', () => {
  async function filledForm() {
    wrapper = mount(RegisterInstanceButton, options); await click('登记实例')
    const selects = wrapper.findAllComponents(ElSelect)
    selects[0].vm.$emit('update:modelValue', 15)
    selects[1].vm.$emit('update:modelValue', 30)
    await flushPromises()
    expect(wrapper.findAllComponents(ElOption).map(option => option.props('label'))).not.toContain('other-env (192.0.2.2)')
    selects[2].vm.$emit('update:modelValue', 34)
    await wrapper.findComponent(ElInput).setValue(' infrahub-example ')
    await flushPromises()
  }
  it('sends only registration facts and refreshes after success', async () => {
    vi.mocked(registerRuntimeInstance).mockResolvedValue({ id: 90 })
    await filledForm(); await click('登记')
    expect(registerRuntimeInstance).toHaveBeenCalledWith({ serviceId: 15, envId: 30, hostId: 34, instanceName: 'infrahub-example' })
    expect(wrapper!.emitted('changed')).toHaveLength(1)
    expect(wrapper!.findComponent(ElDialog).exists()).toBe(false)
  })
  it('preserves input and emits no success on a rejected registration', async () => {
    vi.mocked(registerRuntimeInstance).mockRejectedValue(new Error('duplicate'))
    await filledForm(); await click('登记')
    expect(wrapper!.emitted('changed')).toBeUndefined()
    expect(wrapper!.findComponent(ElDialog).props('modelValue')).toBe(true)
    expect(wrapper!.findComponent(ElInput).props('modelValue')).toBe(' infrahub-example ')
  })
})
