import type { RouteRecordRaw } from 'vue-router';
import {
  Aim,
  Avatar,
  Bell,
  Box,
  Camera,
  CircleCheck,
  CollectionTag,
  Connection,
  Cpu,
  DataAnalysis,
  DataBoard,
  DataLine,
  DocumentChecked,
  Finished,
  FirstAidKit,
  Grid,
  Guide,
  HomeFilled,
  Key,
  Lock,
  Management,
  MapLocation,
  Notebook,
  Notification,
  Platform,
  RefreshLeft,
  Service,
  SetUp,
  Share,
  Tickets,
  Tools,
  TrendCharts,
  User,
} from '@element-plus/icons-vue';
import type { AppRouteMeta } from '@/types/layout';
import MainLayoutClean from '@/layout/MainLayoutClean.vue';
import ConfiguredPageView from '@/views/common/ConfiguredPageView.vue';

type AppRouteRecord = RouteRecordRaw & { meta: AppRouteMeta };

export const appRoutes: AppRouteRecord[] = [
  {
    path: '/workspace',
    component: () => import('@/views/workspace/WorkspaceView.vue'),
    meta: {
      title: 'routes.workspace',
      group: 'workspace',
      order: 1,
      icon: HomeFilled,
      description: 'Quick links and current user overview',
    },
  },
  {
    path: '/resource/apps',
    component: ConfiguredPageView,
    meta: { title: 'routes.apps', group: 'resource', order: 10, icon: Grid, authorities: ['APP_VIEW', 'SYSTEM_ADMIN'], pageKey: 'apps' },
  },
  {
    path: '/resource/services',
    component: ConfiguredPageView,
    meta: { title: 'routes.services', group: 'resource', order: 11, icon: Service, authorities: ['APP_VIEW', 'SYSTEM_ADMIN'], pageKey: 'services' },
  },
  {
    path: '/resource/envs',
    component: ConfiguredPageView,
    meta: { title: 'routes.envs', group: 'resource', order: 12, icon: MapLocation, authorities: ['APP_VIEW', 'SYSTEM_ADMIN'], pageKey: 'envs' },
  },
  {
    path: '/resource/resource-pools',
    component: () => import('@/views/resource/ResourcePoolsView.vue'),
    meta: { title: 'routes.resourcePools', group: 'resource', order: 13, icon: Platform, authorities: ['SYSTEM_ADMIN'] },
  },
  {
    path: '/resource/hosts',
    component: () => import('@/views/resource/HostsView.vue'),
    meta: { title: 'routes.hosts', group: 'resource', order: 14, icon: Cpu, authorities: ['SYSTEM_ADMIN'] },
  },
  {
    path: '/resource/runtime-instances',
    component: () => import('@/views/resource/RuntimeInstancesView.vue'),
    meta: { title: 'routes.runtimeInstances', group: 'resource', order: 15, icon: DataBoard, authorities: ['RELEASE_VIEW', 'SYSTEM_ADMIN'] },
  },
  {
    path: '/resource/governance',
    component: () => import('@/views/resource/ResourceGovernanceView.vue'),
    meta: { title: 'routes.resourceGovernance', group: 'resource', order: 16, icon: Management, authorities: ['RELEASE_VIEW', 'SYSTEM_ADMIN'] },
  },
  {
    path: '/resource/capacity-policies',
    component: () => import('@/views/resource/CapacityPoliciesView.vue'),
    meta: { title: 'routes.capacityPolicies', group: 'resource', order: 17, icon: TrendCharts, authorities: ['SYSTEM_ADMIN'] },
  },
  {
    path: '/config/groups',
    component: ConfiguredPageView,
    meta: { title: 'routes.configGroups', group: 'config', order: 20, icon: CollectionTag, authorities: ['SYSTEM_ADMIN'], pageKey: 'configGroups' },
  },
  {
    path: '/config/env-configs',
    component: () => import('@/views/configs/EnvConfigsView.vue'),
    meta: { title: 'routes.envConfigs', group: 'config', order: 21, icon: SetUp, authorities: ['SYSTEM_ADMIN'], pageKey: 'envConfigs' },
  },
  {
    path: '/config/secret-refs',
    component: ConfiguredPageView,
    meta: { title: 'routes.secretRefs', group: 'config', order: 22, icon: Lock, authorities: ['SYSTEM_ADMIN'], pageKey: 'secretRefs' },
  },
  {
    path: '/release/orders',
    component: () => import('@/views/release/ReleaseOrdersView.vue'),
    meta: { title: 'routes.releaseOrders', group: 'release', order: 30, icon: DocumentChecked, authorities: ['RELEASE_VIEW', 'SYSTEM_ADMIN'], pageKey: 'releaseOrders', hidden: true },
  },
  {
    path: '/release/rollbacks',
    component: () => import('@/views/release/RollbackOrdersView.vue'),
    meta: { title: 'pipelineDelivery.rollbackHistory', group: 'release', order: 31, icon: RefreshLeft, authorities: ['ROLLBACK_VIEW', 'SYSTEM_ADMIN'], pageKey: 'releaseRollbacks', hidden: true },
  },
  {
    path: '/release/application-rollback',
    component: () => import('@/views/release/ApplicationRollbackView.vue'),
    meta: { title: 'pipelineDelivery.rollbackTitle', group: 'release', order: 31, icon: RefreshLeft, authorities: ['ROLLBACK_VIEW', 'ROLLBACK_EXECUTE', 'SYSTEM_ADMIN'] },
  },
  {
    path: '/release/versions',
    component: () => import('@/views/release/ReleaseVersionsView.vue'),
    meta: { title: 'routes.releaseVersions', group: 'release', order: 32, icon: Tickets, authorities: ['RELEASE_VIEW', 'SYSTEM_ADMIN'], pageKey: 'releaseVersions' },
  },
  {
    path: '/release/artifacts',
    component: () => import('@/views/release/ReleaseArtifactsView.vue'),
    meta: { title: 'routes.releaseArtifacts', group: 'release', order: 33, icon: Box, authorities: ['SYSTEM_ADMIN'], pageKey: 'releaseArtifacts' },
  },
  {
    path: '/release/current-versions',
    component: () => import('@/views/release/CurrentVersionsView.vue'),
    meta: { title: 'routes.currentVersions', group: 'release', order: 34, icon: Finished, authorities: ['RELEASE_VIEW', 'SYSTEM_ADMIN'], pageKey: 'currentVersions' },
  },
  {
    path: '/release/unknown-executions',
    component: () => import('@/views/release/UnknownExecutionsView.vue'),
    meta: { title: 'routes.unknownExecutions', group: 'release', order: 35, icon: Guide, authorities: ['RELEASE_VIEW', 'SYSTEM_ADMIN'], pageKey: 'unknownExecutions' },
  },
  {
    path: '/pipeline/defs',
    component: () => import('@/views/pipeline/PipelineDefsView.vue'),
    meta: { title: 'routes.pipelineDefs', group: 'pipeline', order: 40, icon: Connection, authorities: ['PIPELINE_EXECUTE', 'SYSTEM_ADMIN'], pageKey: 'pipelineDefs' },
  },
  {
    path: '/pipeline/defs/:id',
    component: () => import('@/views/pipeline/PipelineDetailView.vue'),
    meta: { title: 'routes.pipelineDetail', authorities: ['PIPELINE_EXECUTE', 'SYSTEM_ADMIN'], hidden: true },
  },
  {
    path: '/pipeline/runs',
    component: () => import('@/views/pipeline/PipelineRunsView.vue'),
    meta: { title: 'routes.pipelineRuns', group: 'pipeline', order: 41, icon: DataLine, authorities: ['PIPELINE_EXECUTE', 'SYSTEM_ADMIN'] },
  },
  {
    path: '/pipeline/runs/:id/steps',
    component: () => import('@/views/pipeline/RunStepsView.vue'),
    meta: { title: 'routes.stepRuns', authorities: ['PIPELINE_EXECUTE', 'SYSTEM_ADMIN'], hidden: true },
  },
  {
    path: '/monitor/targets',
    component: () => import('@/views/monitor/MonitorTargetsView.vue'),
    meta: { title: 'routes.monitorTargets', group: 'monitor', order: 60, icon: Aim, authorities: ['RELEASE_VIEW', 'SYSTEM_ADMIN'] },
  },
  {
    path: '/monitor/snapshots',
    component: () => import('@/views/monitor/MonitorSnapshotsView.vue'),
    meta: { title: 'routes.monitorSnapshots', group: 'monitor', order: 61, icon: Camera, authorities: ['RELEASE_VIEW', 'SYSTEM_ADMIN'] },
  },
  {
    path: '/monitor/alert-rules',
    component: () => import('@/views/monitor/MonitorAlertRulesView.vue'),
    meta: { title: 'routes.monitorAlertRules', group: 'monitor', order: 62, icon: Bell, authorities: ['RELEASE_VIEW', 'SYSTEM_ADMIN'] },
  },
  {
    path: '/monitor/alert-events',
    component: () => import('@/views/monitor/MonitorAlertEventsView.vue'),
    meta: { title: 'routes.monitorAlertEvents', group: 'monitor', order: 63, icon: Notification, authorities: ['RELEASE_VIEW', 'SYSTEM_ADMIN'] },
  },
  {
    path: '/monitor/health-checks',
    component: () => import('@/views/monitor/MonitorHealthChecksView.vue'),
    meta: { title: 'routes.monitorHealthChecks', group: 'monitor', order: 64, icon: FirstAidKit, authorities: ['RELEASE_VIEW', 'SYSTEM_ADMIN'] },
  },
  {
    path: '/system/users',
    component: ConfiguredPageView,
    meta: { title: 'routes.users', group: 'system', order: 50, icon: User, authorities: ['SYSTEM_ADMIN'], pageKey: 'systemUsers' },
  },
  {
    path: '/system/roles',
    component: ConfiguredPageView,
    meta: { title: 'routes.roles', group: 'system', order: 51, icon: Avatar, authorities: ['SYSTEM_ADMIN'], pageKey: 'systemRoles' },
  },
  {
    path: '/system/permissions',
    component: ConfiguredPageView,
    meta: { title: 'routes.permissions', group: 'system', order: 52, icon: Key, authorities: ['SYSTEM_ADMIN'], pageKey: 'systemPermissions' },
  },
  {
    path: '/system/enums',
    component: ConfiguredPageView,
    meta: { title: 'routes.enums', group: 'system', order: 53, icon: CollectionTag, authorities: ['SYSTEM_ADMIN'], pageKey: 'systemEnums' },
  },
  {
    path: '/system/release-diagnostics',
    component: () => import('@/views/system/ReleaseDiagnosticsView.vue'),
    meta: { title: 'routes.releaseDiagnostics', group: 'system', order: 54, icon: DataAnalysis, authorities: ['SYSTEM_ADMIN'] },
  },
  {
    path: '/system/audit-logs',
    component: () => import('@/views/system/AuditLogsView.vue'),
    meta: { title: 'routes.auditLogs', group: 'system', order: 55, icon: Notebook, authorities: ['SYSTEM_ADMIN'] },
  },
  {
    path: '/system/approvals',
    component: () => import('@/views/system/ApprovalCenterView.vue'),
    meta: { title: 'routes.approvals', group: 'system', order: 56, icon: CircleCheck, authorities: ['SYSTEM_ADMIN', 'APPROVAL_VIEW'] },
  },
  {
    path: '/system/governance-chain',
    component: () => import('@/views/system/GovernanceChainView.vue'),
    meta: { title: 'routes.governanceChain', group: 'system', order: 57, icon: Share, authorities: ['SYSTEM_ADMIN'] },
  },
  {
    path: '/system/release-environment-policies',
    component: () => import('@/views/system/ReleaseEnvironmentPoliciesView.vue'),
    meta: { title: 'routes.releaseEnvironmentPolicies', group: 'system', order: 58, icon: Guide, authorities: ['SYSTEM_ADMIN'] },
  },
  {
    path: '/system/executor-configs',
    component: () => import('@/views/system/ReleaseExecutorConfigsView.vue'),
    meta: { title: 'routes.executorConfigs', group: 'system', order: 59, icon: Tools, authorities: ['SYSTEM_ADMIN'] },
  },
];

export const routes: RouteRecordRaw[] = [
  {
    path: '/login',
    component: () => import('@/views/auth/LoginView.vue'),
    meta: { title: 'routes.login', hidden: true },
  },
  {
    path: '/',
    component: MainLayoutClean,
    redirect: '/workspace',
    children: appRoutes,
  },
  {
    path: '/:pathMatch(.*)*',
    component: () => import('@/views/system/NotFoundView.vue'),
    meta: { title: 'routes.notFound', hidden: true },
  },
];
