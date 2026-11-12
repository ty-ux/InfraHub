import { createCrudApi, createReadonlyApi } from './crud';
import { del, get, post, put } from '@/utils/http';

export const pipelineApi = createCrudApi<Record<string, unknown>>('/api/pipelines');
export const pipelineRunApi = createReadonlyApi<Record<string, unknown>>('/api/pipeline-runs');
export const pipelineStepRunApi = createReadonlyApi<Record<string, unknown>>('/api/pipeline-step-runs');

export async function listPipelineCatalog(page: typeof pipelineApi.page) {
  const records: Record<string, unknown>[] = [];
  for (let pageNum = 1; ; pageNum++) {
    const result = await page({ pageNum, pageSize: 100 });
    records.push(...result.records);
    if (!result.records.length || records.length >= result.total) return records;
  }
}

interface PipelineLogChunk {
  available: boolean;
  base64: string;
  nextOffset: number;
  hasMore: boolean;
  finished: boolean;
}

export function getPipelineStepLog(pipelineRunId: number, stepRunId: number, offset: number) {
  return get<PipelineLogChunk>(`/api/pipeline-step-runs/${stepRunId}/log`, { pipelineRunId, offset });
}

export const pipelineStageApi = {
  listByPipeline: (pipelineId: number | string) => get<Record<string, unknown>[]>(`/api/pipelines/${pipelineId}/stages`),
  create: (pipelineId: number | string, payload: Record<string, unknown>) => post<boolean>(`/api/pipelines/${pipelineId}/stages`, payload),
  update: (stageId: number | string, payload: Record<string, unknown>) => put<boolean>(`/api/pipeline-stages/${stageId}`, payload),
  remove: (stageId: number | string) => del<boolean>(`/api/pipeline-stages/${stageId}`),
  listSteps: (stageId: number | string) => get<Record<string, unknown>[]>(`/api/pipeline-stages/${stageId}/steps`),
  createStep: (stageId: number | string, payload: Record<string, unknown>) => post<boolean>(`/api/pipeline-stages/${stageId}/steps`, payload),
};

export const pipelineStepApi = {
  update: (stepId: number | string, payload: Record<string, unknown>) => put<boolean>(`/api/pipeline-steps/${stepId}`, payload),
  remove: (stepId: number | string) => del<boolean>(`/api/pipeline-steps/${stepId}`),
};

export function getPipeline(id: number | string) {
  return get<Record<string, unknown>>(`/api/pipelines/${id}`);
}

export function getPipelineRun(id: number | string) {
  return get<Record<string, unknown>>(`/api/pipeline-runs/${id}`);
}

export async function pipelineHasReleaseDeployStep(pipelineId: number | string) {
  return (await getPipelineExecutorTypes(pipelineId)).includes('RELEASE_DEPLOY');
}

export async function getPipelineExecutorTypes(pipelineId: number | string) {
  const stages = await pipelineStageApi.listByPipeline(pipelineId);
  const stepGroups = await Promise.all(stages.map((stage) => pipelineStageApi.listSteps(Number(stage.id))));
  return stepGroups.flat().map((step) => String(step.executorType ?? '').toUpperCase());
}

export interface RunPipelinePayload {
  envId?: number;
  serviceId?: number;
  versionId?: number;
  releaseId?: number;
}

export interface RunPipelineResponse {
  pipelineRunId: number;
  runStatus: string;
}

export function runPipeline(id: number | string, payload: RunPipelinePayload) {
  return post<RunPipelineResponse>(`/api/pipelines/${id}/run`, payload);
}
