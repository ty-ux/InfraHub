import { createCrudApi } from './crud';
import { get, put } from '@/utils/http';

export const appApi = createCrudApi<Record<string, unknown>>('/api/apps');
export const serviceApi = createCrudApi<Record<string, unknown>>('/api/services');
export const envApi = createCrudApi<Record<string, unknown>>('/api/envs');
export const configGroupApi = createCrudApi<Record<string, unknown>>('/api/config-groups');
export const envConfigApi = createCrudApi<Record<string, unknown>>('/api/env-configs');

export interface EnvConfigScope {
  groupId?: number;
  envId?: number;
  appId?: number;
}

export interface EnvConfigDocument {
  json: string;
  revision: string;
}

const documentUrl = '/api/env-configs/documents';
export const envConfigDocumentApi = {
  ungroupedScopes: () => get<Array<Required<Pick<EnvConfigScope, 'envId'>> & Pick<EnvConfigScope, 'appId'>>>(`${documentUrl}/ungrouped-scopes`),
  read: (scope: EnvConfigScope) => get<EnvConfigDocument>(documentUrl, { ...scope }),
  save: (scope: EnvConfigScope, document: EnvConfigDocument) =>
    put<EnvConfigDocument>(documentUrl, document, { params: { ...scope } }),
};
export const secretRefApi = createCrudApi<Record<string, unknown>>('/api/secret-refs');
