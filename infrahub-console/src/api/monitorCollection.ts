import { get, put } from '@/utils/http'

/**
 * 采集状态只读接口（后端 `GET /api/monitor/collection-states`）。
 *
 * 一个接口同时服务主机列表与运行实例列表（后端按 targetType 区分），所以单独成模块，
 * 而不是在 asset.ts / runtime.ts 里各写一份拷贝——两份实现迟早会漂移。
 *
 * 这里读到的都是**后端调度器已经记录下来的状态**。这个请求不会触发任何 SSH 探测、
 * 不会写数据库，也不代替后端调度：界面轮询它不等于"去探了一遍主机"。
 */

export const COLLECTION_TARGET_TYPE = {
  HOST: 'HOST',
  SERVICE_INSTANCE: 'SERVICE_INSTANCE',
} as const

export type CollectionTargetType =
  (typeof COLLECTION_TARGET_TYPE)[keyof typeof COLLECTION_TARGET_TYPE]

/** 状态来源：SSH_PROBE 是平台主动探测；AGENT_REPORT 当前无人写入（本平台没有部署 Agent） */
export type CollectionSourceCode = 'SSH_PROBE' | 'AGENT_REPORT' | 'NONE'

/** 新鲜度：按 last_success_at 与过期阈值算出来，不依赖 updated_at */
export type CollectionFreshnessCode = 'FRESH' | 'STALE' | 'NONE'

/** 最近一次采集结果；SKIPPED_* 不是失败，EXPIRED 是"证据过期" */
export type CollectionResultCode =
  | 'SUCCESS'
  | 'FAILED'
  | 'SKIPPED_UNCONFIGURED'
  | 'SKIPPED_UNSUPPORTED'
  | 'EXPIRED'
  | 'NONE'

/** 单个目标的采集状态（对应后端 CollectionStateView） */
export interface CollectionStateView {
  targetType: string
  targetId: number
  /** 产出该状态的观测器；NONE 表示该目标类型没有可用观测器，即"不支持" */
  observerType: string
  source: string
  /** 采样时目标有没有可用的启用连接配置 */
  configPresent: boolean
  result: string
  freshness: string
  /** 最近一次尝试采集的时刻；失败也会推进它 */
  lastAttemptAt?: string | null
  /** 最近一次成功观测的时刻；失败**不会**清除它 */
  lastSuccessAt?: string | null
  /** 最近一次被接受的样本时刻 */
  observedAt?: string | null
  consecutiveFailureCount?: number | null
  errorCategory?: string | null
  /** 已脱敏并截断的失败摘要 */
  errorSummary?: string | null
  observedStatus?: string | null
  observedHealth?: string | null
  discardedSampleCount?: number | null
}

/** 批量读取的响应体（对应后端 CollectionStateListResponse） */
export interface CollectionStateListResponse {
  /** 自动采集是否启用：两个后端开关同时为真才为 true */
  collectionEnabled: boolean
  /** 当前生效的过期阈值（毫秒） */
  staleThresholdMs: number
  states: CollectionStateView[]
}

/**
 * 批量读取采集状态。
 *
 * 没有任何可查目标时返回 `null`，**不**发请求、也不编造一个 `collectionEnabled: false`：
 * "没查过"和"查过且发现采集是关的"是两件事，界面必须能区分，否则会在空列表上
 * 喊一句没有依据的"采集未启用"。
 */
export function listCollectionStates(
  targetType: CollectionTargetType,
  targetIds: Array<number | null | undefined>,
): Promise<CollectionStateListResponse | null> {
  const ids = Array.from(
    new Set(targetIds.filter((id): id is number => typeof id === 'number' && Number.isInteger(id))),
  )
  if (ids.length === 0) {
    return Promise.resolve(null)
  }
  // 逗号拼接：后端是 @RequestParam List<Long>，axios 默认的 targetIds[]= 形式绑不上
  return get<CollectionStateListResponse>('/api/monitor/collection-states', {
    targetType,
    targetIds: ids.join(','),
  })
}
export interface CollectionSchedule {
  enabled: boolean
  periodMs: number
  scope: 'CURRENT_PROCESS'
  resetsOnRestart: boolean
}
export function getCollectionSchedule() {
  return get<CollectionSchedule>('/api/monitor/host-instance-collection')
}
export function setCollectionSchedule(enabled: boolean) {
  return put<CollectionSchedule>('/api/monitor/host-instance-collection', { enabled })
}
