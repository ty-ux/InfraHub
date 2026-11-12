import { computed, onMounted, onUnmounted, ref } from 'vue'
import {
  listCollectionStates,
  type CollectionStateView,
  type CollectionTargetType,
} from '@/api/monitorCollection'

/**
 * 主机/运行实例列表共用的「一轮同步」协调器（只读）。
 *
 * ## 为什么它不只是一个"采集状态读取器"
 *
 * 后端的调度器会去探测主机并把结论写回 `asset_host.host_status` /
 * `runtime_service_instance.instance_status` / `health_status`。但只轮询
 * `/api/monitor/collection-states` 的话，界面上的**原状态列**永远不会跟着变：
 * 采集表说"最近一次观察到 STOPPED"，列表却还挂着用户第一次进页面时的 RUNNING。
 * 那不是刷新，是两张互不相干的表。
 *
 * 所以这里的一轮 = **重读列表 + 重读采集状态**，两者都拿到才算一轮成功：
 *
 * | 数据 | 唯一写入者 | 权威来源 |
 * |------|-----------|----------|
 * | 原状态列（hostStatus / instanceStatus / healthStatus）与健康推导 | 页面传入的 `reloadList` | 列表接口 |
 * | 采集列（来源 / 最近成功观察时间 / 新鲜度 / 结果 / 失败原因） | 本协调器 | 采集状态接口 |
 *
 * 两者同属一轮、同一个**意图版本**：任一步失败就整轮不算成功，旧值保留并**显式标注**为
 * 可能过期（`listStale` / `collectionStale`），绝不把"没读到"粉饰成"现在是好的"。
 *
 * ## 意图版本：为什么"排队"不能算"失败"
 *
 * 单飞意味着同一时刻只有一轮在飞。但"在飞"不等于"还是用户想要的"：
 * 自动轮询 A 还在路上时用户改了筛选、按了 Enter，**用户此刻要的是 B**。
 * 旧实现只在"下一轮真正开始"时才递增令牌，于是有两处错：
 * <ol>
 *   <li>手动入口把"已排队"当成"读取失败"，弹一个 Load failed——可当时没有任何请求失败；</li>
 *   <li>A 的令牌仍是"当前"，它晚到的响应照样写进**已经换了筛选条件**的表格。</li>
 * </ol>
 * 现在把令牌改成**意图版本**：任何新意图（手动刷新 / 改筛选 / 翻页）到达时**立即**递增，
 * 在飞的那一轮当场失去写入资格（响应不再属于当前展示），并只登记**一次**待办补跑，
 * 补跑读的是最新查询条件。排队不产生错误提示，真实失败照旧提示。
 *
 * ## 一轮的三种结论
 *
 * `syncNow` 返回 {@link SyncOutcome}，而不是布尔：
 * - `SUCCESS`：整轮成功，且仍是当前意图；
 * - `FAILED`：整轮失败，且仍是当前意图——**只有这个值才该弹错**；
 * - `COALESCED`：这一轮已被新意图取代（或组件已卸载），它的成败与当前展示无关，不提示、
 *   不计入连续失败。被取代的手动调用不会立刻拿到 `COALESCED`，而是等补跑结束后拿到
 *   补跑的真实结论——所以"改筛选后接口真挂了"仍然会提示。
 *
 * ## 硬规矩
 * - 同一时刻只允许一轮在飞：自动 tick 撞上在飞的轮次就直接跳过并计数，不堆积请求；
 *   手动意图撞上则使旧轮次立即失效，当前轮结束后**恰好**补跑一次（用户意图不丢，也不横向堆叠）。
 * - 迟到的响应不许覆盖新一轮：单调递增的意图版本，页面侧异步批次用同一个 `isCurrent` 自查。
 * - 页面不可见、路由离开、登出（组件卸载）都要停掉定时器并解绑监听。
 * - 定时器**不因"采集未启用"而停**：列表本身（发布/部署会改状态）仍需自动同步，
 *   默认配置下采集就是关的，停表等于让 R1 的缺陷在默认场景里继续存在。
 * - 这个 composable **只读数据库**：不触发 SSH 探测，不写库，也不代替后端调度。
 */

/** 后端还没汇报过期阈值时的兜底间隔 */
const DEFAULT_STALE_FALLBACK_MS = 30_000
/** 间隔 = 过期阈值 / 该系数，保证一个过期周期内至少刷到几次 */
const INTERVAL_DIVISOR = 3
const MIN_INTERVAL_MS = 10_000
const MAX_INTERVAL_MS = 60_000
/** 连续失败到该次数就停止自动轮询：出错的表现不宜是"每 30 秒弹一次错" */
const MAX_CONSECUTIVE_FAILURES = 3

/** 一轮同步的结论。只有 `FAILED` 表示"当前意图真的没读到"。 */
export type SyncOutcome = 'SUCCESS' | 'FAILED' | 'COALESCED'

export interface UseCollectionStatesOptions {
  targetType: CollectionTargetType
  /** 取当前页面上真实存在、需要展示采集状态的目标 id */
  targetIds: () => Array<number | null | undefined>
  /**
   * 一轮同步中的「列表重读」，由页面提供，是**原状态列的唯一写入者**。
   *
   * 页面在该方法里重新拉取本页列表并把结果写进自己的 `tableData`（含派生的健康信息）。
   * 写入前必须用第二个参数 `isCurrent()` 自查：新输入/翻页意图到达时它会**立即**变 false，
   * 比"等下一轮开始"更早，避免旧一轮的晚到响应写进已经换过条件的表格。
   * **失败必须抛出**：是否保留旧值、标不标过期由协调器统一裁决，页面不要自己吞掉错误
   * 再对外装作这一轮成功了。
   */
  reloadList: (token: number, isCurrent: () => boolean) => Promise<void>
}

export interface SyncOptions {
  /** 手动触发（点刷新、改筛选、翻页）：会解除暂停、立刻作废旧轮次，并允许页面提示真实失败 */
  manual?: boolean
}

export function useCollectionStates(options: UseCollectionStatesOptions) {
  const stateMap = ref(new Map<number, CollectionStateView>())
  /** null = 还没查过。必须与 false（查过且采集是关的）区分开 */
  const collectionEnabled = ref<boolean | null>(null)
  const staleThresholdMs = ref<number | null>(null)
  const loading = ref(false)
  /** 连续失败导致的自动轮询暂停；手动刷新会解除 */
  const pollingPaused = ref(false)
  /**
   * 原状态列是否可能过期：列表重读失败时旧值继续显示，但必须让用户看见这件事。
   */
  const listStale = ref(false)
  /** 采集列是否可能过期：采集状态重读失败时旧值继续显示 */
  const collectionStale = ref(false)
  /** 最近一次失败的原因（已由拦截器脱敏的文案） */
  const lastError = ref<string | null>(null)
  /** 最近一次**整轮成功**的时刻 */
  const lastSyncedAt = ref<string | null>(null)
  /** 因为上一轮还在飞而被跳过的自动 tick 次数（可观测性） */
  const skippedRounds = ref(0)

  let timer: ReturnType<typeof setInterval> | null = null
  let currentIntervalMs: number | null = null
  /**
   * 当前**意图版本**。任何新意图（手动刷新 / 改筛选 / 翻页）到达时立即递增，
   * 每轮开始时也递增。在飞的一轮只在"自己的版本仍是当前版本"时才允许写状态。
   */
  let intentVersion = 0
  let consecutiveFailures = 0
  let inFlight = false
  let pendingRerun = false
  /** 被合并掉的手动调用：等补跑结束后拿到补跑的真实结论，而不是"排队"这个中间态 */
  let pendingResolvers: Array<(outcome: SyncOutcome) => void> = []
  let disposed = false

  const effectiveIntervalMs = computed(() => {
    const base = staleThresholdMs.value ?? DEFAULT_STALE_FALLBACK_MS
    const derived = Math.floor(base / INTERVAL_DIVISOR)
    return Math.min(MAX_INTERVAL_MS, Math.max(MIN_INTERVAL_MS, derived))
  })

  /** 这一轮的意图版本是否仍然有效（页面侧异步批次用它自查，避免迟到写入） */
  function isCurrentToken(token: number): boolean {
    return !disposed && token === intentVersion
  }

  function stopTimer() {
    if (timer !== null) {
      clearInterval(timer)
      timer = null
    }
    currentIntervalMs = null
  }

  function syncTimer() {
    const hidden = typeof document !== 'undefined' && document.visibilityState === 'hidden'
    // 采集关着时**不停表**：列表自身的状态列仍会随发布/部署变化，需要自动同步
    if (disposed || hidden || pollingPaused.value) {
      stopTimer()
      return
    }
    const desired = effectiveIntervalMs.value
    if (timer !== null && currentIntervalMs === desired) {
      return
    }
    stopTimer()
    currentIntervalMs = desired
    timer = setInterval(() => {
      void syncNow()
    }, desired)
  }

  /** 采集列的读取：本协调器是采集列的唯一写入者 */
  async function readCollectionStates(token: number): Promise<void> {
    const ids = options.targetIds()

    if (ids.length === 0) {
      // 本页没有目标，就没有状态可说；清空而不是留着上一页的陈旧数据
      if (isCurrentToken(token)) {
        stateMap.value = new Map()
      }
      return
    }

    const response = await listCollectionStates(options.targetType, ids)
    if (!isCurrentToken(token)) {
      // 期间用户改了筛选条件，这一份是上一轮的答案，丢掉
      return
    }
    if (!response) {
      return
    }
    const next = new Map<number, CollectionStateView>()
    for (const state of response.states) {
      next.set(state.targetId, state)
    }
    stateMap.value = next
    collectionEnabled.value = response.collectionEnabled
    staleThresholdMs.value = response.staleThresholdMs
  }

  /**
   * 跑一轮同步。返回这一轮相对**当前意图**的结论。
   *
   * 失败时保留旧值、置位对应的 stale 标记，并在连续失败到上限后暂停自动轮询；
   * 但若这一轮已被新意图取代，则不算失败、不置位、不计数（见 {@link SyncOutcome}）。
   */
  async function runRound(): Promise<SyncOutcome> {
    if (disposed) {
      return 'COALESCED'
    }

    inFlight = true
    loading.value = true
    const token = ++intentVersion
    const isCurrent = () => isCurrentToken(token)

    try {
      // 第一步：重读列表（原状态列 + 健康推导）。失败即整轮失败。
      try {
        await options.reloadList(token, isCurrent)
      } catch (err) {
        if (isCurrent()) {
          listStale.value = true
        }
        throw err
      }
      if (!isCurrent()) {
        return 'COALESCED'
      }
      listStale.value = false

      // 第二步：重读采集状态（采集列）。失败同样整轮不算成功。
      try {
        await readCollectionStates(token)
      } catch (err) {
        if (isCurrent()) {
          collectionStale.value = true
        }
        throw err
      }
      if (!isCurrent()) {
        return 'COALESCED'
      }
      collectionStale.value = false

      consecutiveFailures = 0
      pollingPaused.value = false
      lastError.value = null
      lastSyncedAt.value = new Date().toISOString()
      return 'SUCCESS'
    } catch (err) {
      if (!isCurrent()) {
        // 已被新意图取代：这点失败不属于当前展示，既不提示也不计入连续失败
        return 'COALESCED'
      }
      consecutiveFailures += 1
      lastError.value = err instanceof Error ? err.message : String(err)
      if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
        pollingPaused.value = true
      }
      return 'FAILED'
    } finally {
      inFlight = false
      if (isCurrent()) {
        loading.value = false
      }
      syncTimer()
      if (pendingRerun && !disposed) {
        pendingRerun = false
        const resolvers = pendingResolvers
        pendingResolvers = []
        // 补跑用的是最新查询条件（页面在 reloadList 里现取），所以"最终留下的是最新查询的结果"
        void runRound().then(outcome => resolvers.forEach(resolve => resolve(outcome)))
      }
    }
  }

  /**
   * 唯一的同步入口：首屏、点刷新、改筛选、翻页、可见性恢复都走它。
   * 页面所有直接读列表的地方都应改成调用它，否则那些请求会绕开"不重叠"与意图版本守卫。
   */
  function syncNow(opts: SyncOptions = {}): Promise<SyncOutcome> {
    if (disposed) {
      return Promise.resolve<SyncOutcome>('COALESCED')
    }
    if (opts.manual) {
      pollingPaused.value = false
      consecutiveFailures = 0
    }
    if (inFlight) {
      if (opts.manual) {
        // 新意图到达：**立刻**作废在飞的那一轮，它的晚到响应从此不属于当前展示
        intentVersion += 1
        pendingRerun = true
        // 合并成恰好一次补跑；调用方等补跑的真实结论，而不是"已排队"这个中间态
        return new Promise<SyncOutcome>(resolve => pendingResolvers.push(resolve))
      }
      // 自动 tick 不代表新意图，不作废旧轮次，只记一次跳过
      skippedRounds.value += 1
      return Promise.resolve<SyncOutcome>('COALESCED')
    }
    return runRound()
  }

  function onVisibilityChange() {
    if (document.visibilityState === 'hidden') {
      stopTimer()
    } else {
      void syncNow()
    }
  }

  onMounted(() => {
    document.addEventListener('visibilitychange', onVisibilityChange)
    // 首轮由页面在拿到查询条件后触发，避免列表还没准备好就先空查一次
    syncTimer()
  })

  onUnmounted(() => {
    disposed = true
    // 让在途响应到达时被判定为过期，不再写状态
    intentVersion += 1
    pendingRerun = false
    // 被合并的手动调用必须落地，否则页面的 await 永远不返回
    const resolvers = pendingResolvers
    pendingResolvers = []
    resolvers.forEach(resolve => resolve('COALESCED'))
    stopTimer()
    document.removeEventListener('visibilitychange', onVisibilityChange)
  })

  function stateFor(targetId: number): CollectionStateView | undefined {
    return stateMap.value.get(targetId)
  }

  return {
    stateMap,
    collectionEnabled,
    staleThresholdMs,
    effectiveIntervalMs,
    loading,
    pollingPaused,
    listStale,
    collectionStale,
    lastError,
    lastSyncedAt,
    skippedRounds,
    syncNow,
    isCurrentToken,
    stateFor,
  }
}