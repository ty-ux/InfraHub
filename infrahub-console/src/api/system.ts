import { createReadonlyApi } from './crud';
import { get, post, put } from '@/utils/http';

export const userApi = createReadonlyApi<Record<string, unknown>>('/api/users');
export const roleApi = createReadonlyApi<Record<string, unknown>>('/api/roles');
export const permissionApi = createReadonlyApi<Record<string, unknown>>('/api/permissions');
export const enumApi = createReadonlyApi<Record<string, unknown>>('/api/enums');

export interface RoleOption {
  id: number;
  roleCode: string;
  roleName: string;
  status: string;
}

export interface RoleGrants {
  appScope: 'ALL' | 'SELECTED';
  permissionIds: number[];
  appIds: number[];
}

export interface AccessCatalog {
  roles: RoleOption[];
  permissions: { id: number; permCode: string; permName: string; resourceType: string; action: string }[];
  apps: { id: number; appCode: string; appName: string }[];
  scopedPermissions: string[];
}

export const accessApi = {
  catalog: () => get<AccessCatalog>('/api/roles/access-catalog'),
  userRoles: (id: number) => get<number[]>(`/api/users/${id}/roles`),
  saveUserRoles: (id: number, roleIds: number[]) => put<boolean>(`/api/users/${id}/roles`, { roleIds }),
  roleGrants: (id: number) => get<RoleGrants>(`/api/roles/${id}/grants`),
  saveRoleGrants: (id: number, grants: RoleGrants) => put<boolean>(`/api/roles/${id}/grants`, grants),
  createRole: (roleCode: string, roleName: string) => post<number>('/api/roles', { roleCode, roleName }),
};
