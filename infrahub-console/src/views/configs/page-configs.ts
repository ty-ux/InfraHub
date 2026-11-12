import type { CrudFieldOption, CrudPageConfig } from '@/types/page';
import { i18n } from '@/i18n';
import { appApi, configGroupApi, envApi, envConfigApi, secretRefApi, serviceApi } from '@/api/resource';
import { artifactApi, currentVersionApi, releaseApi, rollbackApi, versionApi, listExecutorConfigsByEnv, type ReleaseExecutorConfig } from '@/api/release';
import { listRuntimeServiceInstances, type RuntimeServiceInstance } from '@/api/runtime';
import { pipelineApi, pipelineRunApi } from '@/api/pipeline';
import { enumApi, permissionApi, roleApi, userApi } from '@/api/system';
import { formatBoolean, formatDateTime } from '@/utils/format';

const systemAdmin = ['SYSTEM_ADMIN'];
const releaseViewAuth = ['RELEASE_VIEW', 'SYSTEM_ADMIN'];
const releaseCreateAuth = ['RELEASE_CREATE', 'SYSTEM_ADMIN'];
const releaseExecuteAuth = ['RELEASE_EXECUTE', 'SYSTEM_ADMIN'];
const releaseCancelAuth = ['RELEASE_CANCEL', 'SYSTEM_ADMIN'];
const rollbackViewAuth = ['ROLLBACK_VIEW', 'SYSTEM_ADMIN'];
const rollbackCreateAuth = ['ROLLBACK_CREATE', 'SYSTEM_ADMIN'];
const rollbackExecuteAuth = ['ROLLBACK_EXECUTE', 'SYSTEM_ADMIN'];
const pipelineAuth = ['PIPELINE_EXECUTE', 'SYSTEM_ADMIN'];

// 回滚单页面不提供通用编辑：专用保存路径是 PUT /api/rollbacks/{id}，后端只接受 toVersionId；
// 通用编辑会把整张表单提交到同一地址，除目标版本外的字段被静默忽略却提示成功。
// RollbackController 也没有 DELETE 路由，通用删除同样不成立。因此这里显式剔除 update 与 remove，
// 保留查询 / 新建及专用候选目标保存，也不借助 editAuthority 掩盖接口与表单的差异。
const { update: _unsupportedRollbackUpdate, remove: _unsupportedRollbackRemove, ...rollbackApiWithoutUpdateOrRemove } = rollbackApi;

const releaseTypeOptions: CrudFieldOption[] = [
  { label: 'FULL', value: 'FULL' },
  { label: 'ROLLING', value: 'ROLLING' },
  { label: 'CANARY', value: 'CANARY' },
  { label: 'BLUE_GREEN', value: 'BLUE_GREEN' },
];

const releaseStatusOptions: CrudFieldOption[] = [
  { label: 'CREATED', value: 'CREATED' },
  { label: 'APPROVING', value: 'APPROVING' },
  { label: 'APPROVED', value: 'APPROVED' },
  { label: 'REJECTED', value: 'REJECTED' },
  { label: 'RUNNING', value: 'RUNNING' },
  { label: 'SUCCESS', value: 'SUCCESS' },
  { label: 'FAILED', value: 'FAILED' },
  { label: 'ROLLED_BACK', value: 'ROLLED_BACK' },
];

const rollbackStatusOptions: CrudFieldOption[] = [
  { label: 'CREATED', value: 'CREATED' },
  { label: 'APPROVING', value: 'APPROVING' },
  { label: 'APPROVED', value: 'APPROVED' },
  { label: 'REJECTED', value: 'REJECTED' },
  { label: 'RUNNING', value: 'RUNNING' },
  { label: 'SUCCESS', value: 'SUCCESS' },
  { label: 'FAILED', value: 'FAILED' },
];

const riskLevelOptions: CrudFieldOption[] = [
  { label: 'LOW', value: 'LOW' },
  { label: 'MEDIUM', value: 'MEDIUM' },
  { label: 'HIGH', value: 'HIGH' },
];

const statusOptions: CrudFieldOption[] = [
  { label: 'ENABLED', value: 'ENABLED' },
  { label: 'DISABLED', value: 'DISABLED' },
];

const envTypeOptions: CrudFieldOption[] = [
  { label: 'DEV', value: 'DEV' },
  { label: 'TEST', value: 'TEST' },
  { label: 'UAT', value: 'UAT' },
  { label: 'PROD', value: 'PROD' },
];

const valueTypeOptions: CrudFieldOption[] = [
  { label: 'STRING', value: 'STRING' },
  { label: 'NUMBER', value: 'NUMBER' },
  { label: 'BOOLEAN', value: 'BOOLEAN' },
  { label: 'JSON', value: 'JSON' },
  { label: 'SECRET_REF', value: 'SECRET_REF' },
];

const triggerTypeOptions: CrudFieldOption[] = [
  { label: 'MANUAL', value: 'MANUAL' },
  { label: 'GIT_EVENT', value: 'GIT_EVENT' },
  { label: 'SCHEDULE', value: 'SCHEDULE' },
];

const pipelineTypeOptions: CrudFieldOption[] = [
  { label: 'BUILD', value: 'BUILD' },
  { label: 'DEPLOY', value: 'DEPLOY' },
  { label: 'RELEASE', value: 'RELEASE' },
];

function t(key: string, named?: Record<string, unknown>) {
  return named ? i18n.global.t(key, named) : i18n.global.t(key);
}

function pageTitle(key: string) {
  return t(`pages.${key}.title`);
}

function pageDesc(key: string) {
  return t(`pages.${key}.desc`);
}

function field(key: string) {
  return t(`fields.${key}`);
}

function localizedLabel(zh: string, en: string) {
  return t('common.locale') === 'Language' ? en : zh;
}

async function loadEntityOptions(
  api: { page: (params: Record<string, unknown>) => Promise<{ records: Record<string, unknown>[] }> },
  labelProp: string,
  valueProp = 'id',
  filter?: (record: Record<string, unknown>) => boolean,
) {
  const page = await api.page({ pageNum: 1, pageSize: 200 });
  return page.records
    .filter((record) => (filter ? filter(record) : true))
    .map((record) => ({
      label: String(record[labelProp] ?? record[valueProp] ?? '-'),
      value: record[valueProp] as string | number | boolean,
    }));
}

async function buildLookupMap(
  api: { page: (params: Record<string, unknown>) => Promise<{ records: Record<string, unknown>[] }> },
  labelProp: string,
  valueProp = 'id',
) {
  const page = await api.page({ pageNum: 1, pageSize: 200 });
  return new Map(
    page.records.map((record) => [
      record[valueProp] as string | number,
      String(record[labelProp] ?? record[valueProp] ?? '-'),
    ]),
  );
}

async function enrichWithAppAndEnvNames(page: {
  pageNum: number;
  pageSize: number;
  total: number;
  records: Record<string, unknown>[];
}) {
  const [appMap, envMap] = await Promise.all([
    buildLookupMap(appApi, 'appName'),
    buildLookupMap(envApi, 'envName'),
  ]);

  return {
    ...page,
    records: page.records.map((record) => ({
      ...record,
      appDisplayName: appMap.get(record.appId as string | number) ?? String(record.appId ?? '-'),
      envDisplayName: envMap.get(record.envId as string | number) ?? String(record.envId ?? '-'),
    })),
  };
}

async function enrichEnvConfigs(page: {
  pageNum: number;
  pageSize: number;
  total: number;
  records: Record<string, unknown>[];
}) {
  const [appMap, envMap, groupMap] = await Promise.all([
    buildLookupMap(appApi, 'appName'),
    buildLookupMap(envApi, 'envName'),
    buildLookupMap(configGroupApi, 'groupName'),
  ]);

  return {
    ...page,
    records: page.records.map((record) => ({
      ...record,
      appDisplayName: appMap.get(record.appId as string | number) ?? String(record.appId ?? '-'),
      envDisplayName: envMap.get(record.envId as string | number) ?? String(record.envId ?? '-'),
      groupDisplayName: groupMap.get(record.groupId as string | number) ?? String(record.groupId ?? '-'),
    })),
  };
}

async function enrichReleaseOrders(page: {
  pageNum: number;
  pageSize: number;
  total: number;
  records: Record<string, unknown>[];
}) {
  const [appMap, serviceMap, envMap, versionMap] = await Promise.all([
    buildLookupMap(appApi, 'appName'),
    buildLookupMap(serviceApi, 'serviceName'),
    buildLookupMap(envApi, 'envName'),
    buildLookupMap(versionApi, 'versionNo'),
  ]);

  return {
    ...page,
    records: page.records.map((record) => ({
      ...record,
      appDisplayName: appMap.get(record.appId as string | number) ?? String(record.appId ?? '-'),
      serviceDisplayName: serviceMap.get(record.serviceId as string | number) ?? String(record.serviceId ?? '-'),
      envDisplayName: envMap.get(record.envId as string | number) ?? String(record.envId ?? '-'),
      versionDisplayName: versionMap.get(record.versionId as string | number) ?? String(record.versionId ?? '-'),
    })),
  };
}

async function enrichRollbackOrders(page: {
  pageNum: number;
  pageSize: number;
  total: number;
  records: Record<string, unknown>[];
}) {
  const [releaseOrderPage, versionMap, appMap, serviceMap, envMap] = await Promise.all([
    releaseApi.page({ pageNum: 1, pageSize: 200 }),
    buildLookupMap(versionApi, 'versionNo'),
    buildLookupMap(appApi, 'appName'),
    buildLookupMap(serviceApi, 'serviceName'),
    buildLookupMap(envApi, 'envName'),
  ]);

  const releaseOrderLookup = new Map(
    releaseOrderPage.records.map((record) => [record.id as string | number, record]),
  );

  return {
    ...page,
    records: page.records.map((record) => {
      const releaseOrder = releaseOrderLookup.get(record.releaseOrderId as string | number);
      return {
        ...record,
        releaseOrderDisplayName:
          releaseOrder?.orderNo ?? String(record.releaseOrderId ?? '-'),
        fromVersionDisplayName:
          versionMap.get(record.fromVersionId as string | number) ?? String(record.fromVersionId ?? '-'),
        toVersionDisplayName:
          versionMap.get(record.toVersionId as string | number) ?? String(record.toVersionId ?? '-'),
        appDisplayName: releaseOrder?.appDisplayName ?? appMap.get((record.appId ?? releaseOrder?.appId) as string | number) ?? '-',
        serviceDisplayName: releaseOrder?.serviceDisplayName ?? serviceMap.get((record.serviceId ?? releaseOrder?.serviceId) as string | number) ?? '-',
        envDisplayName: releaseOrder?.envDisplayName ?? envMap.get((record.envId ?? releaseOrder?.envId) as string | number) ?? '-',
        // latestFailureHint 由后端 VO 直接返回，前端仅透传
        latestFailureHint: record.latestFailureHint ?? null,
      };
    }),
  };
}

async function enrichServices(page: {
  pageNum: number;
  pageSize: number;
  total: number;
  records: Record<string, unknown>[];
}) {
  const appMap = await buildLookupMap(appApi, 'appName');
  return {
    ...page,
    records: page.records.map((record) => ({
      ...record,
      appDisplayName: appMap.get(record.appId as string | number) ?? String(record.appId ?? '-'),
    })),
  };
}

async function enrichVersions(page: {
  pageNum: number;
  pageSize: number;
  total: number;
  records: Record<string, unknown>[];
}) {
  const [appMap, artifactMap] = await Promise.all([
    buildLookupMap(appApi, 'appName'),
    buildLookupMap(artifactApi, 'artifactName'),
  ]);

  return {
    ...page,
    records: page.records.map((record) => ({
      ...record,
      appDisplayName: appMap.get(record.appId as string | number) ?? String(record.appId ?? '-'),
      artifactDisplayName: artifactMap.get(record.artifactId as string | number) ?? String(record.artifactId ?? '-'),
      buildRunDisplayName: record.buildPipelineRunId == null ? '-' : `#${String(record.buildPipelineRunId)}`,
    })),
  };
}

/**
 * 当前版本列表的"运行实例"摘要：无实例 / 实例列表读取失败（含作用域不符） / 多实例 / 缺少服务环境信息
 * 是几种不同的事实，必须分别表达，不能都塌成"-"。
 * 多实例时不给任何"代表实例"身份：后端没有把某个实例绑定为整个服务的权威实例，
 * 前端更不允许用数组顺序来判定，实例数量本身才是列表能负责的事实。
 */
type RuntimeInstanceState = 'SINGLE' | 'MULTIPLE' | 'NONE' | 'ERROR' | 'UNKNOWN';

interface RuntimeInstanceSummary {
  state: RuntimeInstanceState;
  count: number | null;
  name: string | null;
  instanceId: number | null;
}

function missingRuntimeInstances(state: 'NONE' | 'ERROR' | 'UNKNOWN'): RuntimeInstanceSummary {
  return { state, count: state === 'NONE' ? 0 : null, name: null, instanceId: null };
}

/**
 * 按 service/env 查询返回的列表：只要存在明显不属于本次目标的条目，整轮读取判为作用域错误。
 * 与诊断弹窗使用同一判据：不静默过滤后宣称无实例，也不把外来实例的身份写进列表摘要。
 */
function summarizeRuntimeInstances(
  instances: RuntimeServiceInstance[],
  serviceId: number,
  envId: number,
): RuntimeInstanceSummary {
  const foreign = instances.some((item) => item.serviceId !== serviceId || item.envId !== envId);
  if (foreign) {
    return missingRuntimeInstances('ERROR');
  }
  if (instances.length === 1) {
    const [instance] = instances;
    return {
      state: 'SINGLE',
      count: 1,
      name: instance.instanceName ?? null,
      instanceId: instance.id,
    };
  }
  if (instances.length > 1) {
    return { state: 'MULTIPLE', count: instances.length, name: null, instanceId: null };
  }
  return missingRuntimeInstances('NONE');
}

async function enrichCurrentVersions(page: {
  pageNum: number;
  pageSize: number;
  total: number;
  records: Record<string, unknown>[];
}) {
  const [envMap, servicePage, appPage, versionMap, releaseOrderMap, rollbackOrderMap] = await Promise.all([
    buildLookupMap(envApi, 'envName'),
    serviceApi.page({ pageNum: 1, pageSize: 200 }),
    appApi.page({ pageNum: 1, pageSize: 200 }),
    buildLookupMap(versionApi, 'versionNo'),
    buildLookupMap(releaseApi, 'orderNo'),
    buildLookupMap(rollbackApi, 'rollbackNo'),
  ]);
  const serviceMap = new Map(
    servicePage.records.map((record) => [record.id as string | number, String(record.serviceName ?? record.id ?? '-')]),
  );
  const serviceCodeMap = new Map(
    servicePage.records.map((record) => [record.id as string | number, String(record.serviceCode ?? '-')]),
  );
  const serviceAppIdMap = new Map(
    servicePage.records.map((record) => [record.id as string | number, record.appId as string | number]),
  );
  const appCodeMap = new Map(
    appPage.records.map((record) => [record.id as string | number, String(record.appCode ?? '-')]),
  );
  const instanceEntries = await Promise.all(
    page.records.map(async (record) => {
      const serviceId = Number(record.serviceId);
      const envId = Number(record.envId);
      const key = record.id as string | number;
      if (!serviceId || !envId) {
        return [key, missingRuntimeInstances('UNKNOWN')] as const;
      }
      try {
        return [key, summarizeRuntimeInstances(await listRuntimeServiceInstances({ serviceId, envId }), serviceId, envId)] as const;
      } catch {
        // 实例列表读取失败与"没有实例"是两件事，必须分别表达，不能都塌成没有实例
        return [key, missingRuntimeInstances('ERROR')] as const;
      }
    }),
  );
  const instanceMap = new Map(instanceEntries);

  return {
    ...page,
    records: page.records.map((record) => {
      const serviceId = record.serviceId as string | number;
      const appId = serviceAppIdMap.get(serviceId);
      const instances = instanceMap.get(record.id as string | number) ?? missingRuntimeInstances('UNKNOWN');
      return {
        ...record,
        envDisplayName: envMap.get(record.envId as string | number) ?? String(record.envId ?? '-'),
        serviceDisplayName: serviceMap.get(serviceId) ?? String(record.serviceId ?? '-'),
        serviceCode: serviceCodeMap.get(serviceId) ?? '-',
        appCode: appId == null ? '-' : appCodeMap.get(appId) ?? String(appId),
        // 列表只表达"这个服务环境有几个实例"，不按数组顺序挑一个实例代表整个服务：
        // 单实例给出身份信息，多实例只给数量与"诊断时选择"提示，诊断目标由用户在弹窗里显式选择。
        runtimeInstanceState: instances.state,
        runtimeInstanceCount: instances.count,
        runtimeInstanceName: instances.name,
        runtimeInstanceId: instances.instanceId,
        versionDisplayName: versionMap.get(record.versionId as string | number) ?? String(record.versionId ?? '-'),
        sourceReleaseOrderDisplayName:
          releaseOrderMap.get(record.sourceReleaseOrderId as string | number) ?? String(record.sourceReleaseOrderId ?? '-'),
        sourceRollbackOrderDisplayName:
          rollbackOrderMap.get(record.sourceRollbackOrderId as string | number) ?? String(record.sourceRollbackOrderId ?? '-'),
      };
    }),
  };
}

async function enrichPipelineDefs(page: {
  pageNum: number;
  pageSize: number;
  total: number;
  records: Record<string, unknown>[];
}) {
  const appMap = await buildLookupMap(appApi, 'appName');
  return {
    ...page,
    records: page.records.map((record) => ({
      ...record,
      appDisplayName: appMap.get(record.appId as string | number) ?? String(record.appId ?? '-'),
    })),
  };
}

async function enrichPipelineRuns(page: {
  pageNum: number;
  pageSize: number;
  total: number;
  records: Record<string, unknown>[];
}) {
  const [pipelineMap, appMap, envMap] = await Promise.all([
    buildLookupMap({ page: (params) => pipelineApi.page({ ...params, includeDeleted: true }) }, 'pipelineName'),
    buildLookupMap(appApi, 'appName'),
    buildLookupMap(envApi, 'envName'),
  ]);

  return {
    ...page,
    records: page.records.map((record) => ({
      ...record,
      pipelineDisplayName: pipelineMap.get(record.pipelineId as string | number) ?? String(record.pipelineId ?? '-'),
      appDisplayName: appMap.get(record.appId as string | number) ?? String(record.appId ?? '-'),
      envDisplayName: envMap.get(record.envId as string | number) ?? String(record.envId ?? '-'),
      versionDisplayName: String(record.versionNo ?? record.versionId ?? '-'),
    })),
  };
}

async function loadAppOptions() {
  return loadEntityOptions(appApi, 'appName');
}

async function loadEnvOptions() {
  return loadEntityOptions(envApi, 'envName');
}

async function loadServiceOptions(formState?: Record<string, unknown>) {
  const appId = formState?.appId;
  if (appId == null || appId === '') {
    return [];
  }
  return loadEntityOptions(serviceApi, 'serviceName', 'id', (record) => String(record.appId ?? '') === String(appId));
}

async function loadVersionOptions(formState?: Record<string, unknown>) {
  const appId = formState?.appId;
  if (appId == null || appId === '') {
    return [];
  }
  return loadEntityOptions(versionApi, 'versionNo', 'id', (record) => String(record.appId ?? '') === String(appId));
}

async function loadAllVersionOptions() {
  return loadEntityOptions(versionApi, 'versionNo');
}

async function loadConfigGroupOptions(formState?: Record<string, unknown>) {
  const appId = formState?.appId;
  const envId = formState?.envId;
  if (appId == null || appId === '' || envId == null || envId === '') {
    return [];
  }
  return loadEntityOptions(
    configGroupApi,
    'groupName',
    'id',
    (record) => String(record.appId ?? '') === String(appId) && String(record.envId ?? '') === String(envId),
  );
}

async function loadSecretRefOptions() {
  return loadEntityOptions(secretRefApi, 'secretKey');
}

async function loadReleaseOrderOptions() {
  return loadEntityOptions(releaseApi, 'orderNo');
}

// F+1-B: Executor config options filtered by environment
async function loadExecutorConfigOptions(formState?: Record<string, unknown>) {
  const envId = formState?.envId;
  if (envId == null || envId === '') {
    return [];
  }
  try {
    const configs = await listExecutorConfigsByEnv(envId as number | string);
    return configs.map((config) => ({
      label: `${config.executorName} (${config.executorType})`,
      value: config.id,
    }));
  } catch {
    return [];
  }
}

function readonlyConfig(
  title: string,
  description: string,
  api: CrudPageConfig<Record<string, unknown>>['api'],
  columns: CrudPageConfig<Record<string, unknown>>['columns'],
  authorities?: string[],
): CrudPageConfig<Record<string, unknown>> {
  return {
    title,
    description,
    api,
    columns,
    createAuthority: authorities,
    editAuthority: authorities,
    deleteAuthority: authorities,
  };
}

// V1.20: 计算发布单下一步建议（纯前端逻辑）
// 建议文案取自 i18n 的 nextStep 词表：列表「下一步建议」列与详情抽屉共用同一套判据，
// 判据本身不变，只把返回的展示文案接入 i18n，随语言切换重算
export function computeReleaseNextStep(row: Record<string, unknown>): string {
  const status = String(row.status ?? '');
  const hasPipelineRun = Boolean(row.hasPipelineRun);
  const hasDeployRecords = Boolean(row.hasDeployRecords);

  if (status === 'CREATED' || status === 'APPROVED') {
    return t('nextStep.precheck');
  }
  if (status === 'RUNNING') {
    return hasPipelineRun ? t('nextStep.viewPipelineLog') : t('nextStep.viewDeployRecords');
  }
  if (status === 'FAILED') {
    return t('nextStep.viewDeployRecords');
  }
  if (status === 'SUCCESS') {
    return hasDeployRecords ? t('nextStep.viewDeployRecords') : t('nextStep.viewDetail');
  }
  // REJECTED / ROLLED_BACK
  return t('nextStep.viewDetail');
}

// V1.20: 计算回滚单下一步建议（纯前端逻辑）
export function computeRollbackNextStep(row: Record<string, unknown>): string {
  const status = String(row.status ?? '');
  const hasDeployRecords = Boolean(row.hasDeployRecords);

  if (status === 'CREATED' || status === 'APPROVED') {
    return t('nextStep.precheck');
  }
  if (status === 'RUNNING' || status === 'FAILED') {
    return t('nextStep.viewDeployRecords');
  }
  if (status === 'SUCCESS') {
    return hasDeployRecords ? t('nextStep.viewDeployRecords') : t('nextStep.viewDetail');
  }
  // REJECTED etc.
  return t('nextStep.viewDetail');
}

// T-E: 发布单状态标签类型（per Contract 8.2）
// CREATED=蓝, APPROVING=橙, APPROVED=绿, REJECTED=红, RUNNING=蓝, SUCCESS=绿, FAILED=红, ROLLED_BACK=灰
// 蓝色统一用合法的 Element Plus 类型 'primary'，渲染与默认蓝一致，同时消除非法的空 type 警告
export function getReleaseStatusTagType(status: string): 'primary' | 'info' | 'warning' | 'success' | 'danger' {
  switch (status) {
    case 'CREATED':
      return 'primary';  // blue/default
    case 'APPROVING':
      return 'warning';  // orange
    case 'APPROVED':
      return 'success';  // green
    case 'REJECTED':
      return 'danger';  // red
    case 'RUNNING':
      return 'primary';  // blue/default
    case 'SUCCESS':
      return 'success';  // green
    case 'FAILED':
      return 'danger';  // red
    case 'ROLLED_BACK':
      return 'info';  // gray
    default:
      return 'info';
  }
}

// T-E: 回滚单状态标签类型（per Contract 8.2）
// CREATED=蓝, APPROVING=橙, APPROVED=绿, REJECTED=红, RUNNING=蓝, SUCCESS=绿, FAILED=红
export function getRollbackStatusTagType(status: string): 'primary' | 'info' | 'warning' | 'success' | 'danger' {
  switch (status) {
    case 'CREATED':
      return 'primary';  // blue/default
    case 'APPROVING':
      return 'warning';  // orange
    case 'APPROVED':
      return 'success';  // green
    case 'REJECTED':
      return 'danger';  // red
    case 'RUNNING':
      return 'primary';  // blue/default
    case 'SUCCESS':
      return 'success';  // green
    case 'FAILED':
      return 'danger';  // red
    default:
      return 'info';
  }
}

// T-E: 部署状态标签类型（per Contract 8.7）
// RUNNING=蓝, SUCCESS=绿, FAILED=红
export function getDeployStatusTagType(status: string | null | undefined): 'primary' | 'info' | 'warning' | 'success' | 'danger' {
  switch (status) {
    case 'RUNNING':
      return 'primary';  // blue/default
    case 'SUCCESS':
      return 'success';  // green
    case 'FAILED':
      return 'danger';  // red
    default:
      return 'info';  // gray for "尚未执行"
  }
}

// T-E: 发布单状态标签文案（per Contract 8.2）：已知状态取词表，未知状态保留原始值并显式标注
export function getReleaseStatusLabel(status: string): string {
  const labels: Record<string, string> = {
    CREATED: t('releaseStatus.created'),
    APPROVING: t('releaseStatus.approving'),
    APPROVED: t('releaseStatus.approved'),
    REJECTED: t('releaseStatus.rejected'),
    RUNNING: t('releaseStatus.running'),
    SUCCESS: t('releaseStatus.success'),
    FAILED: t('releaseStatus.failed'),
    ROLLED_BACK: t('releaseStatus.rolledBack'),
  };
  return labels[status] || (status ? t('releaseStatus.unknownRaw', { status }) : status);
}

// T-E: 回滚单状态标签文案（per Contract 8.2）
export function getRollbackStatusLabel(status: string): string {
  const labels: Record<string, string> = {
    CREATED: t('releaseStatus.created'),
    APPROVING: t('releaseStatus.approving'),
    APPROVED: t('releaseStatus.approved'),
    REJECTED: t('releaseStatus.rejected'),
    RUNNING: t('releaseStatus.running'),
    SUCCESS: t('releaseStatus.success'),
    FAILED: t('releaseStatus.failed'),
  };
  return labels[status] || (status ? t('releaseStatus.unknownRaw', { status }) : status);
}

// T-E: 部署状态标签文案（per Contract 8.7）：缺失状态仍按「尚未执行」，非空未知状态保留原始值并显式标注
export function getDeployStatusLabel(status: string | null | undefined): string {
  if (!status) return t('deployStatus.notStarted');
  const labels: Record<string, string> = {
    RUNNING: t('deployStatus.running'),
    SUCCESS: t('deployStatus.success'),
    FAILED: t('deployStatus.failed'),
  };
  return labels[status] || t('deployStatus.unknownRaw', { status });
}

function createConfigs(): Record<string, CrudPageConfig<Record<string, unknown>>> {
  return {
    apps: {
      title: pageTitle('apps'),
      description: pageDesc('apps'),
      api: appApi,
      columns: [
        { prop: 'id', label: field('id'), width: 90 },
        { prop: 'appCode', label: field('appCode'), minWidth: 140 },
        { prop: 'appName', label: field('appName'), minWidth: 160 },
        { prop: 'ownerId', label: field('ownerId'), width: 100 },
        { prop: 'repoUrl', label: field('repoUrl'), minWidth: 200 },
        { prop: 'defaultBranch', label: field('defaultBranch'), width: 130 },
        { prop: 'status', label: field('status'), width: 100 },
        { prop: 'updatedAt', label: field('updatedAt'), minWidth: 180, formatter: formatDateTime },
      ],
      fields: [
        { prop: 'appCode', label: field('appCode'), required: true },
        { prop: 'appName', label: field('appName'), required: true },
        { prop: 'ownerId', label: field('ownerId'), type: 'number' },
        { prop: 'repoUrl', label: field('repoUrl') },
        { prop: 'defaultBranch', label: field('defaultBranch') },
        { prop: 'description', label: field('description'), type: 'textarea' },
        { prop: 'status', label: field('status'), type: 'select', required: true, options: statusOptions },
      ],
      defaultForm: { status: 'ENABLED' },
      createAuthority: systemAdmin,
      editAuthority: systemAdmin,
      deleteAuthority: systemAdmin,
    },
    services: {
      title: pageTitle('services'),
      description: pageDesc('services'),
      api: {
        ...serviceApi,
        page: async (params) => enrichServices(await serviceApi.page(params)),
      },
      columns: [
        { prop: 'id', label: field('id'), width: 90 },
        { prop: 'appDisplayName', label: field('appName'), minWidth: 180 },
        { prop: 'serviceCode', label: field('serviceCode'), minWidth: 140 },
        { prop: 'serviceName', label: field('serviceName'), minWidth: 160 },
        { prop: 'serviceType', label: field('serviceType'), width: 120 },
        { prop: 'runtimeType', label: field('runtimeType'), width: 120 },
        { prop: 'port', label: field('port'), width: 90 },
        { prop: 'status', label: field('status'), width: 100 },
        { prop: 'updatedAt', label: field('updatedAt'), minWidth: 180, formatter: formatDateTime },
      ],
      fields: [
        { prop: 'appId', label: field('appName'), type: 'select', required: true, loadOptions: loadAppOptions },
        { prop: 'serviceCode', label: field('serviceCode'), required: true },
        { prop: 'serviceName', label: field('serviceName'), required: true },
        { prop: 'serviceType', label: field('serviceType') },
        { prop: 'runtimeType', label: field('runtimeType') },
        { prop: 'port', label: field('port'), type: 'number' },
        { prop: 'healthCheckPath', label: field('healthCheckPath') },
        { prop: 'status', label: field('status'), type: 'select', required: true, options: statusOptions },
      ],
      defaultForm: { status: 'ENABLED' },
      createAuthority: systemAdmin,
      editAuthority: systemAdmin,
      deleteAuthority: systemAdmin,
    },
    envs: {
      title: pageTitle('envs'),
      description: pageDesc('envs'),
      api: envApi,
      columns: [
        { prop: 'id', label: field('id'), width: 90 },
        { prop: 'envCode', label: field('envCode'), width: 110 },
        { prop: 'envName', label: field('envName'), minWidth: 140 },
        { prop: 'envType', label: field('envType'), width: 120 },
        { prop: 'region', label: field('region'), width: 120 },
        { prop: 'namespace', label: field('namespace'), minWidth: 140 },
        { prop: 'status', label: field('status'), width: 100 },
        { prop: 'updatedAt', label: field('updatedAt'), minWidth: 180, formatter: formatDateTime },
      ],
      fields: [
        { prop: 'envCode', label: field('envCode'), required: true },
        { prop: 'envName', label: field('envName'), required: true },
        { prop: 'envType', label: field('envType'), type: 'select', required: true, options: envTypeOptions },
        { prop: 'region', label: field('region') },
        { prop: 'namespace', label: field('namespace') },
        { prop: 'description', label: field('description'), type: 'textarea' },
        { prop: 'status', label: field('status'), type: 'select', required: true, options: statusOptions },
      ],
      defaultForm: { status: 'ENABLED' },
      createAuthority: systemAdmin,
      editAuthority: systemAdmin,
      deleteAuthority: systemAdmin,
    },
    configGroups: {
      title: pageTitle('configGroups'),
      description: pageDesc('configGroups'),
      api: {
        ...configGroupApi,
        page: async (params) => enrichWithAppAndEnvNames(await configGroupApi.page(params)),
      },
      columns: [
        { prop: 'id', label: field('id'), width: 90 },
        { prop: 'groupCode', label: field('groupCode'), minWidth: 140 },
        { prop: 'groupName', label: field('groupName'), minWidth: 150 },
        { prop: 'envDisplayName', label: field('envName'), minWidth: 140 },
        { prop: 'appDisplayName', label: field('appName'), minWidth: 180 },
        { prop: 'description', label: field('description'), minWidth: 180 },
        { prop: 'createdAt', label: field('createdAt'), minWidth: 180, formatter: formatDateTime },
      ],
      fields: [
        { prop: 'groupCode', label: field('groupCode'), required: true },
        { prop: 'groupName', label: field('groupName'), required: true },
        { prop: 'envId', label: field('envName'), type: 'select', required: true, loadOptions: loadEnvOptions },
        { prop: 'appId', label: field('appName'), type: 'select', required: true, loadOptions: loadAppOptions },
        { prop: 'description', label: field('description'), type: 'textarea' },
      ],
      createAuthority: systemAdmin,
      editAuthority: systemAdmin,
      deleteAuthority: systemAdmin,
    },
    envConfigs: {
      title: pageTitle('envConfigs'),
      description: pageDesc('envConfigs'),
      api: {
        ...envConfigApi,
        page: async (params) => enrichEnvConfigs(await envConfigApi.page(params)),
      },
      columns: [
        { prop: 'id', label: field('id'), width: 90 },
        { prop: 'envDisplayName', label: field('envName'), minWidth: 140 },
        { prop: 'appDisplayName', label: field('appName'), minWidth: 180 },
        { prop: 'groupDisplayName', label: field('groupName'), minWidth: 160 },
        { prop: 'configKey', label: field('configKey'), minWidth: 160 },
        { prop: 'configValue', label: field('configValue'), minWidth: 180 },
        { prop: 'valueType', label: field('valueType'), width: 120 },
        { prop: 'versionNo', label: field('versionNo'), width: 100 },
        { prop: 'updatedAt', label: field('updatedAt'), minWidth: 180, formatter: formatDateTime },
      ],
      fields: [
        { prop: 'envId', label: field('envName'), type: 'select', required: true, loadOptions: loadEnvOptions },
        { prop: 'appId', label: field('appName'), type: 'select', required: true, loadOptions: loadAppOptions },
        {
          prop: 'groupId',
          label: field('groupName'),
          type: 'select',
          required: true,
          loadOptions: loadConfigGroupOptions,
          dependsOn: ['appId', 'envId'],
        },
        { prop: 'configKey', label: field('configKey'), required: true },
        { prop: 'configValue', label: field('configValue'), type: 'textarea' },
        { prop: 'valueType', label: field('valueType'), type: 'select', required: true, options: valueTypeOptions },
        { prop: 'secretRefId', label: field('secretRefId'), type: 'select', loadOptions: loadSecretRefOptions },
        { prop: 'versionNo', label: field('versionNo'), type: 'number' },
      ],
      createAuthority: systemAdmin,
      editAuthority: systemAdmin,
      deleteAuthority: systemAdmin,
    },
    secretRefs: {
      title: pageTitle('secretRefs'),
      description: pageDesc('secretRefs'),
      api: secretRefApi,
      columns: [
        { prop: 'id', label: field('id'), width: 90 },
        { prop: 'secretKey', label: field('secretKey'), minWidth: 160 },
        { prop: 'secretProvider', label: field('secretProvider'), width: 140 },
        { prop: 'secretPath', label: field('secretPath'), minWidth: 180 },
        { prop: 'description', label: field('description'), minWidth: 180 },
        { prop: 'createdAt', label: field('createdAt'), minWidth: 180, formatter: formatDateTime },
      ],
      fields: [
        { prop: 'secretKey', label: field('secretKey'), required: true },
        { prop: 'secretProvider', label: field('secretProvider'), required: true },
        { prop: 'secretPath', label: field('secretPath'), required: true },
        { prop: 'description', label: field('description'), type: 'textarea' },
      ],
      createAuthority: systemAdmin,
      editAuthority: systemAdmin,
      deleteAuthority: systemAdmin,
    },
    releaseOrders: {
      title: pageTitle('releaseOrders'),
      description: pageDesc('releaseOrders'),
      api: {
        ...releaseApi,
        page: async (params) => enrichReleaseOrders(await releaseApi.page(params)),
      },
      columns: [
        // V1.19: Column order optimized for triage - orderNo → app/service/env → status → deploy → failure → pipeline → deploy records → time
        // 第二批: 版本提前到环境之后，先看「哪个单 · 哪个对象 · 哪个环境 · 哪个版本」，再看状态与证据
        { prop: 'orderNo', label: field('orderNo'), minWidth: 140 },
        { prop: 'appDisplayName', label: field('appName'), minWidth: 160 },
        { prop: 'serviceDisplayName', label: field('serviceName'), minWidth: 160 },
        { prop: 'envDisplayName', label: field('envName'), minWidth: 120 },
        { prop: 'versionDisplayName', label: field('version'), minWidth: 120 },
        // T-C: Status column uses slot for colored tags (Contract 8.2)
        { prop: 'status', label: field('status'), width: 110, slot: 'status' },
        // T-C: Deploy status column uses slot for colored tags
        { prop: 'latestDeployStatus', label: t('releaseOrders.columnLatestDeployStatus'), minWidth: 110, headerTooltip: t('releaseOrders.columnLatestDeployStatusHint'), slot: 'deployStatus' },
        { prop: 'latestFailureHint', label: t('releaseOrders.columnFailureHint'), minWidth: 180, headerTooltip: t('releaseOrders.columnFailureHintHint'), formatter: (val) => val || '-' },
        { prop: '_nextStep', label: t('releaseOrders.columnNextStep'), minWidth: 120, headerTooltip: t('releaseOrders.columnNextStepHint'), formatter: (_val, row) => computeReleaseNextStep(row) },
        // T-C: Indicators use slots for clickable icons (Contract 8.2)
        { prop: 'hasPipelineRun', label: t('releaseOrders.columnPipeline'), width: 80, slot: 'pipelineIndicator', headerTooltip: t('releaseOrders.columnPipelineHint') },
        { prop: 'hasDeployRecords', label: t('releaseOrders.columnDeployRecords'), width: 90, slot: 'deployRecordIndicator', headerTooltip: t('releaseOrders.columnDeployRecordsHint') },
        { prop: 'createdAt', label: field('createdAt'), minWidth: 160, formatter: formatDateTime },
      ],
      fields: [
        { prop: 'orderNo', label: field('orderNo'), required: true },
        { prop: 'appId', label: field('appName'), type: 'select', required: true, loadOptions: loadAppOptions },
        {
          prop: 'serviceId',
          label: field('serviceName'),
          type: 'select',
          required: true,
          loadOptions: loadServiceOptions,
          dependsOn: ['appId'],
        },
        { prop: 'envId', label: field('envName'), type: 'select', required: true, loadOptions: loadEnvOptions },
        {
          prop: 'versionId',
          label: field('version'),
          type: 'select',
          required: true,
          loadOptions: loadVersionOptions,
          dependsOn: ['appId'],
        },
        { prop: 'releaseType', label: field('releaseType'), type: 'select', required: true, options: releaseTypeOptions },
        { prop: 'riskLevel', label: field('riskLevel'), type: 'select', options: riskLevelOptions },
        // F+1-B: Executor config selection (optional, defaults to SSH) - filtered by environment
        {
          prop: 'executorConfigId',
          label: localizedLabel('执行器配置', 'Executor Config'),
          type: 'select',
          loadOptions: loadExecutorConfigOptions,
          dependsOn: ['envId'],
        },
        { prop: 'status', label: field('status'), type: 'select', required: true, options: releaseStatusOptions },
      ],
      defaultForm: { status: 'CREATED', releaseType: 'FULL' },
      createAuthority: releaseCreateAuth,
      actionColumnWidth: 380,
    },
    releaseRollbacks: {
      title: pageTitle('releaseRollbacks'),
      description: pageDesc('releaseRollbacks'),
      api: {
        ...rollbackApiWithoutUpdateOrRemove,
        page: async (params) => enrichRollbackOrders(await rollbackApi.page(params)),
      },
      columns: [
        // V1.19: Column order - rollbackNo → releaseOrderNo → app/service/env → status → deploy → failure → deploy records → time
        // T-D: Added slot for status tag colors per Contract 8.2
        // 第二批: 原版本/目标版本紧跟环境，先看清「回滚什么 · 从哪到哪」，再看状态与证据
        { prop: 'rollbackNo', label: field('rollbackNo'), minWidth: 140 },
        { prop: 'releaseOrderDisplayName', label: field('orderNo'), minWidth: 140 },
        { prop: 'appDisplayName', label: field('appName'), minWidth: 140 },
        { prop: 'serviceDisplayName', label: field('serviceName'), minWidth: 140 },
        { prop: 'envDisplayName', label: field('envName'), minWidth: 100 },
        { prop: 'fromVersionDisplayName', label: localizedLabel('原版本', 'From Version'), minWidth: 120, headerTooltip: t('rollbackOrders.columnFromVersionHint') },
        { prop: 'toVersionDisplayName', label: localizedLabel('目标版本', 'To Version'), minWidth: 120, headerTooltip: t('rollbackOrders.columnToVersionHint') },
        { prop: 'status', label: field('status'), width: 100, slot: 'status' },
        { prop: 'latestDeployStatus', label: t('rollbackOrders.columnLatestDeployStatus'), minWidth: 110, headerTooltip: t('rollbackOrders.columnLatestDeployStatusHint'), slot: 'latestDeployStatus' },
        { prop: 'latestFailureHint', label: t('rollbackOrders.columnFailureHint'), minWidth: 180, headerTooltip: t('rollbackOrders.columnFailureHintHint'), formatter: (val) => val || '-' },
        { prop: '_nextStep', label: t('rollbackOrders.columnNextStep'), minWidth: 120, headerTooltip: t('rollbackOrders.columnNextStepHint'), formatter: (_val, row) => computeRollbackNextStep(row) },
        { prop: 'hasDeployRecords', label: t('rollbackOrders.columnDeployRecords'), width: 90, slot: 'hasDeployRecords', headerTooltip: t('rollbackOrders.columnDeployRecordsHint') },
        { prop: 'hasPrecheckRecords', label: t('rollbackOrders.columnPrecheck'), width: 70, slot: 'hasPrecheckRecords', headerTooltip: t('rollbackOrders.columnPrecheckHint') },
        { prop: 'createdAt', label: field('createdAt'), minWidth: 160, formatter: formatDateTime },
      ],
      fields: [
        { prop: 'rollbackNo', label: field('rollbackNo'), required: true },
        { prop: 'releaseOrderId', label: field('orderNo'), type: 'select', required: true, loadOptions: loadReleaseOrderOptions },
        {
          prop: 'fromVersionId',
          label: localizedLabel('原版本', 'From Version'),
          type: 'select',
          required: true,
          loadOptions: loadAllVersionOptions,
        },
        {
          prop: 'toVersionId',
          label: localizedLabel('目标版本', 'To Version'),
          type: 'select',
          required: true,
          loadOptions: loadAllVersionOptions,
        },
        { prop: 'status', label: field('status'), type: 'select', required: true, options: rollbackStatusOptions },
        { prop: 'reason', label: field('reason'), type: 'textarea' },
      ],
      defaultForm: { status: 'CREATED' },
      createAuthority: rollbackCreateAuth,
      actionColumnWidth: 340,
    },
    releaseVersions: readonlyConfig(pageTitle('releaseVersions'), pageDesc('releaseVersions'), {
      ...versionApi,
      page: async (params) => enrichVersions(await versionApi.page(params)),
    }, [
      { prop: 'id', label: field('id'), width: 90 },
      { prop: 'appDisplayName', label: field('appName'), minWidth: 180 },
      { prop: 'versionNo', label: field('version'), minWidth: 140 },
      { prop: 'gitCommit', label: field('gitCommit'), minWidth: 160 },
      { prop: 'branchName', label: field('branchName'), minWidth: 140 },
      { prop: 'buildNo', label: field('buildNo'), width: 140 },
      { prop: 'artifactDisplayName', label: field('artifactName'), minWidth: 180 },
      { prop: 'buildRunDisplayName', label: localizedLabel('构建记录', 'Build Run'), minWidth: 120 },
      { prop: 'createdAt', label: field('createdAt'), minWidth: 180, formatter: formatDateTime },
    ], releaseViewAuth),
    releaseArtifacts: readonlyConfig(pageTitle('releaseArtifacts'), pageDesc('releaseArtifacts'), artifactApi, [
      { prop: 'id', label: field('id'), width: 90 },
      { prop: 'artifactType', label: field('artifactType'), width: 120 },
      { prop: 'artifactName', label: field('artifactName'), minWidth: 180 },
      { prop: 'artifactUri', label: field('artifactUri'), minWidth: 220 },
      { prop: 'checksum', label: field('checksum'), minWidth: 180 },
      { prop: 'sizeBytes', label: field('sizeBytes'), width: 120 },
      { prop: 'repositoryType', label: field('repositoryType'), width: 140 },
      { prop: 'createdAt', label: field('createdAt'), minWidth: 180, formatter: formatDateTime },
    ], systemAdmin),
    currentVersions: readonlyConfig(pageTitle('currentVersions'), pageDesc('currentVersions'), {
      ...currentVersionApi,
      page: async (params) => enrichCurrentVersions(await currentVersionApi.page(params)),
    }, [
      { prop: 'id', label: localizedLabel('当前版本ID', 'Current Version ID'), width: 120 },
      { prop: 'envDisplayName', label: field('envName'), minWidth: 140 },
      { prop: 'appCode', label: field('appCode'), minWidth: 120 },
      { prop: 'serviceCode', label: field('serviceCode'), minWidth: 140 },
      { prop: 'serviceDisplayName', label: field('serviceName'), minWidth: 180 },
      { prop: 'runtimeInstanceState', label: localizedLabel('运行实例', 'Runtime Instance'), minWidth: 200, slot: 'runtimeInstance' },
      { prop: 'versionDisplayName', label: field('version'), minWidth: 140 },
      // 指针是 (服务, 环境) 一行，只表示“最近一次成功发布的版本号”；覆盖范围让它只能按 verdict 说话
      {
        prop: 'coverage',
        label: localizedLabel('部署覆盖', 'Deploy coverage'),
        minWidth: 190,
        slot: 'coverage',
        headerTooltip: localizedLabel(
          '本次执行实际覆盖了当前哪些实例；这不表示这些实例现在仍登记在该版本',
          'Which current instances this execution actually covered; this does not mean they are still registered to this version',
        ),
      },
      // 当前登记版本一致性：逐实例比较平台登记的版本，与“当时覆盖了谁”分开表达
      {
        prop: 'registrationConsistency',
        label: localizedLabel('当前登记版本', 'Registered versions'),
        minWidth: 190,
        slot: 'registrationConsistency',
        headerTooltip: localizedLabel(
          '当前实例在平台登记的版本是否都等于指针版本；这是平台登记状态，不是远端实际运行版本',
          'Whether current instances are registered to the pointer version; platform registration, not actual remote runtime',
        ),
      },
      { prop: 'sourceReleaseOrderDisplayName', label: field('orderNo'), minWidth: 140 },
      { prop: 'sourceRollbackOrderDisplayName', label: field('rollbackNo'), minWidth: 140 },
      { prop: 'confirmedAt', label: field('confirmedAt'), minWidth: 180, formatter: formatDateTime },
      { prop: 'updatedAt', label: field('updatedAt'), minWidth: 180, formatter: formatDateTime },
    ], releaseViewAuth),
    pipelineDefs: {
      title: pageTitle('pipelineDefs'),
      description: pageDesc('pipelineDefs'),
      api: {
        ...pipelineApi,
        page: async (params) => enrichPipelineDefs(await pipelineApi.page(params)),
      },
      columns: [
        { prop: 'id', label: field('id'), width: 90 },
        { prop: 'pipelineCode', label: field('pipelineCode'), minWidth: 160 },
        { prop: 'pipelineName', label: field('pipelineName'), minWidth: 170 },
        { prop: 'appDisplayName', label: field('appName'), minWidth: 180 },
        { prop: 'triggerType', label: field('triggerType'), width: 120 },
        { prop: 'pipelineType', label: field('pipelineType'), width: 130 },
        { prop: 'enabled', label: field('enabled'), width: 100, formatter: formatBoolean },
        { prop: 'version', label: field('version'), width: 90 },
        { prop: 'updatedAt', label: field('updatedAt'), minWidth: 180, formatter: formatDateTime },
      ],
      fields: [
        { prop: 'pipelineCode', label: field('pipelineCode'), required: true },
        { prop: 'pipelineName', label: field('pipelineName'), required: true },
        { prop: 'appId', label: field('appName'), type: 'select', required: true, loadOptions: loadAppOptions },
        { prop: 'triggerType', label: field('triggerType'), type: 'select', required: true, options: triggerTypeOptions },
        { prop: 'pipelineType', label: field('pipelineType'), type: 'select', required: true, options: pipelineTypeOptions },
        { prop: 'enabled', label: field('enabled'), type: 'switch' },
        { prop: 'version', label: field('version'), type: 'number' },
        { prop: 'remark', label: field('remark'), type: 'textarea' },
      ],
      defaultForm: { enabled: true, version: 1 },
      createAuthority: pipelineAuth,
      deleteAuthority: systemAdmin,
    },
    pipelineRuns: readonlyConfig(pageTitle('pipelineRuns'), pageDesc('pipelineRuns'), {
      ...pipelineRunApi,
      page: async (params) => enrichPipelineRuns(await pipelineRunApi.page(params)),
    }, [
      { prop: 'id', label: field('id'), width: 90 },
      { prop: 'pipelineDisplayName', label: field('pipelineName'), minWidth: 180 },
      { prop: 'appDisplayName', label: field('appName'), minWidth: 180 },
      { prop: 'envDisplayName', label: field('envName'), minWidth: 140 },
      { prop: 'versionDisplayName', label: field('version'), minWidth: 120 },
      { prop: 'runStatus', label: field('runStatus'), width: 140, slot: 'runStatus' },
      { prop: 'triggerType', label: field('triggerType'), width: 120 },
      { prop: 'currentStage', label: field('currentStage'), minWidth: 140 },
      { prop: 'durationMs', label: field('durationMs'), width: 120, slot: 'duration' },
      { prop: 'startTime', label: field('startTime'), minWidth: 180, formatter: formatDateTime },
      { prop: 'endTime', label: field('endTime'), minWidth: 180, formatter: formatDateTime },
    ], pipelineAuth),
    systemUsers: readonlyConfig(pageTitle('systemUsers'), pageDesc('systemUsers'), userApi, [
      { prop: 'id', label: field('id'), width: 90 },
      { prop: 'username', label: field('username'), minWidth: 140 },
      { prop: 'displayName', label: field('displayName'), minWidth: 140 },
      { prop: 'email', label: field('email'), minWidth: 180 },
      { prop: 'mobile', label: field('mobile'), width: 140 },
      { prop: 'authType', label: field('authType'), width: 120 },
      { prop: 'status', label: field('status'), width: 100 },
      { prop: 'lastLoginAt', label: field('lastLoginAt'), minWidth: 180, formatter: formatDateTime },
    ], systemAdmin),
    systemRoles: readonlyConfig(pageTitle('systemRoles'), pageDesc('systemRoles'), roleApi, [
      { prop: 'id', label: field('id'), width: 90 },
      { prop: 'roleCode', label: field('roleCode'), minWidth: 140 },
      { prop: 'roleName', label: field('roleName'), minWidth: 160 },
      { prop: 'status', label: field('status'), width: 100 },
      { prop: 'createdAt', label: field('createdAt'), minWidth: 180, formatter: formatDateTime },
    ], systemAdmin),
    systemPermissions: readonlyConfig(pageTitle('systemPermissions'), pageDesc('systemPermissions'), permissionApi, [
      { prop: 'id', label: field('id'), width: 90 },
      { prop: 'permCode', label: field('permissionCode'), minWidth: 170 },
      { prop: 'permName', label: field('permissionName'), minWidth: 180 },
      { prop: 'resourceType', label: field('resourceCode'), width: 120 },
      { prop: 'action', label: field('actionCode'), width: 120 },
      { prop: 'createdAt', label: field('createdAt'), minWidth: 180, formatter: formatDateTime },
    ], systemAdmin),
    systemEnums: readonlyConfig(pageTitle('systemEnums'), pageDesc('systemEnums'), enumApi, [
      { prop: 'id', label: field('id'), width: 90 },
      { prop: 'enumType', label: field('enumType'), minWidth: 150 },
      { prop: 'enumCode', label: field('enumCode'), minWidth: 140 },
      { prop: 'enumName', label: field('enumName'), minWidth: 160 },
      { prop: 'sortOrder', label: field('sortOrder'), width: 90 },
      { prop: 'status', label: field('status'), width: 100 },
    ], systemAdmin),
  };
}

export function getPageConfig(key: string) {
  return createConfigs()[key];
}
