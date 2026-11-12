import { get, post, put } from '@/utils/http'

export interface HostSshSetup {
  username: string
  sshPort: number
  privateKeySecretRefId?: number
  enabled: boolean
  authType?: string
}
export interface InstanceRegistration {
  serviceId?: number
  envId?: number
  hostId?: number
  instanceName: string
}
export const getHostSshSetup = (hostId: number) =>
  get<HostSshSetup | null>(`/api/assets/hosts/${hostId}/ssh-config`)
export const saveHostSshSetup = (hostId: number, value: HostSshSetup) =>
  put<HostSshSetup>(`/api/assets/hosts/${hostId}/ssh-config`, value)
export const registerRuntimeInstance = (value: InstanceRegistration) =>
  post<{ id: number }>('/api/runtime/service-instances', value)
