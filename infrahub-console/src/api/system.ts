import { createReadonlyApi } from './crud';

export const userApi = createReadonlyApi<Record<string, unknown>>('/api/users');
export const roleApi = createReadonlyApi<Record<string, unknown>>('/api/roles');
export const permissionApi = createReadonlyApi<Record<string, unknown>>('/api/permissions');
export const enumApi = createReadonlyApi<Record<string, unknown>>('/api/enums');
