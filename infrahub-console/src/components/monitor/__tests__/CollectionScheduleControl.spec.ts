import { mount, flushPromises } from '@vue/test-utils'
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { defineComponent } from 'vue'
import Control from '../CollectionScheduleControl.vue'
import { getCollectionSchedule, setCollectionSchedule } from '@/api/monitorCollection'
import { i18n } from '@/i18n'
const auth = vi.hoisted(() => ({ allowed: true }))
vi.mock('@/store/auth', () => ({ useAuthStore: () => ({ canAccess: () => auth.allowed }) }))
vi.mock('@/api/monitorCollection', () => ({ getCollectionSchedule: vi.fn(), setCollectionSchedule: vi.fn() }))
const state = { enabled: false, periodMs: 30000, scope: 'CURRENT_PROCESS' as const, resetsOnRestart: true }
const stubs = {
  ElButton: defineComponent({ template: '<button><slot /></button>' }),
  ElDialog: defineComponent({ template: '<div><slot /></div>' }),
  ElSwitch: defineComponent({ props: ['modelValue', 'disabled'], emits: ['change'], template: '<button class="switch" :disabled="disabled" @click="$emit(\'change\', !modelValue)">{{ modelValue }}</button>' }),
}
async function open() {
  const w = mount(Control, { global: { plugins: [i18n], stubs } })
  await w.find('button').trigger('click'); await flushPromises(); return w
}
beforeEach(() => { vi.resetAllMocks(); auth.allowed = true; vi.mocked(getCollectionSchedule).mockResolvedValue(state) })
describe('collection schedule control', () => {
  it('loads before editing and refreshes parent only after successful update', async () => {
    vi.mocked(setCollectionSchedule).mockResolvedValue({ ...state, enabled: true })
    const w = await open()
    expect(getCollectionSchedule).toHaveBeenCalledOnce()
    await w.find('.switch').trigger('click'); await flushPromises()
    expect(setCollectionSchedule).toHaveBeenCalledWith(true)
    expect(w.emitted('changed')).toHaveLength(1)
    expect(w.find('.switch').text()).toBe('true')
  })
  it('keeps the last confirmed state and does not announce success on failure', async () => {
    vi.mocked(setCollectionSchedule).mockRejectedValue(new Error('failed'))
    const w = await open(); await w.find('.switch').trigger('click'); await flushPromises()
    expect(w.emitted('changed')).toBeUndefined()
    expect(w.find('.switch').text()).toBe('false')
    expect(w.find('[role="alert"]').exists()).toBe(true)
  })
  it('does not allow non-admin users to change the switch', async () => {
    auth.allowed = false
    const w = await open(); expect(w.find('.switch').attributes('disabled')).toBeDefined()
    expect(setCollectionSchedule).not.toHaveBeenCalled()
  })
})
