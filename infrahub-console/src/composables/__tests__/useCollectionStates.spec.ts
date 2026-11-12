import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { defineComponent, h, ref } from 'vue';
import { mount, type VueWrapper } from '@vue/test-utils';

/**
 * 列表页共用的「一轮同步」协调器。
 *
 * 这份测试盯的是"界面上看起来正常、出事时才发现不对"的几处：
 * - **只重读采集表、不重读列表**：采集器已经观测到 STOPPED，原状态列却还挂着进页面时的 RUNNING；
 * - 迟到的响应覆盖新一轮筛选结果（用户切了筛选，屏上却显示上一轮的状态）；
 * - 页面隐藏/卸载后定时器还在跑（登出后仍持续打请求）；
 * - 轮询间隔拍脑袋写死，不随后端阈值变化；
 * - 上一轮还没结束就再开一轮（请求横向堆叠）；
 * - 旧轮还在飞时用户改了筛选，手动入口把"已排队"当成"读取失败"弹错误提示，而旧轮的晚到响应
 *   又照样写进已经换过条件的表格（既不提示失败、也不提示成功，而是写错数据）；
 * - 后端接口挂了就每 30 秒弹一次错，而不是有界地停下、并且**显式标注旧值可能过期**。
 *
 * 一轮的结论是 `SUCCESS` / `FAILED` / `COALESCED`（已排队或被取代）三种，页面只在 `FAILED` 时提示。
 *
 * 注意：这里全部用假时钟 + 可控的接口桩，不连任何真实主机、不发任何真实请求。
 */

const { listCollectionStatesMock } = vi.hoisted(() => ({
  listCollectionStatesMock: vi.fn(),
}));

vi.mock('@/api/monitorCollection', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/monitorCollection')>();
  return {
    ...actual,
    listCollectionStates: (...args: unknown[]) => listCollectionStatesMock(...args),
  };
});

import {
  COLLECTION_TARGET_TYPE,
  type CollectionStateListResponse,
  type CollectionStateView,
  type CollectionTargetType,
} from '@/api/monitorCollection';
import { useCollectionStates } from '@/composables/useCollectionStates';

type Api = ReturnType<typeof useCollectionStates>;

let api!: Api;
let wrapper: VueWrapper | null = null;
const idSource = ref<Array<number | null | undefined>>([1]);
/** 页面侧提供的「列表重读」桩：原状态列的唯一写入者 */
const reloadListMock = vi.fn<(token: number, isCurrent: () => boolean) => Promise<void>>();

/** 两页（主机 / 运行实例）共用这一个 composable，只有 targetType 不同 */
function makeHarness(targetType: CollectionTargetType) {
  return defineComponent({
    setup() {
      api = useCollectionStates({
        targetType,
        targetIds: () => idSource.value,
        reloadList: (token: number, isCurrent: () => boolean) => reloadListMock(token, isCurrent),
      });
      return () => h('div');
    },
  });
}

const Harness = makeHarness(COLLECTION_TARGET_TYPE.HOST);

function stateView(targetId: number, patch: Partial<CollectionStateView> = {}): CollectionStateView {
  return {
    targetType: 'HOST',
    targetId,
    observerType: 'SSH_PROCESS',
    source: 'SSH_PROBE',
    configPresent: true,
    result: 'SUCCESS',
    freshness: 'FRESH',
    lastAttemptAt: '2026-10-15T02:00:00Z',
    lastSuccessAt: '2026-10-15T02:00:00Z',
    ...patch,
  };
}

function ok(states: CollectionStateView[], staleThresholdMs = 90_000): CollectionStateListResponse {
  return { collectionEnabled: true, staleThresholdMs, states };
}

function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (reason?: unknown) => void;
  const promise = new Promise<T>((res, rej) => {
    resolve = res;
    reject = rej;
  });
  return { promise, resolve, reject };
}

/** 冲掉微任务队列：假时钟下不能用 flushPromises（它依赖真实 setTimeout） */
async function flush(times = 8) {
  for (let i = 0; i < times; i += 1) {
    await Promise.resolve();
  }
}

function setVisibility(value: 'visible' | 'hidden') {
  Object.defineProperty(document, 'visibilityState', { configurable: true, get: () => value });
  document.dispatchEvent(new Event('visibilitychange'));
}

/** 采集状态接口被调用的次数（列表重读单独数，见 reloadListMock） */
function callCount() {
  return listCollectionStatesMock.mock.calls.length;
}

/** 手动同步：与页面里点「刷新列表」走的是同一条入口 */
function sync() {
  return api.syncNow({ manual: true });
}

describe('useCollectionStates', () => {
  beforeEach(() => {
    vi.useFakeTimers();
    listCollectionStatesMock.mockReset();
    listCollectionStatesMock.mockResolvedValue(ok([stateView(1)]));
    reloadListMock.mockReset();
    reloadListMock.mockResolvedValue(undefined);
    idSource.value = [1];
    setVisibility('visible');
  });

  afterEach(() => {
    wrapper?.unmount();
    wrapper = null;
    vi.useRealTimers();
  });

  describe('R1：一轮同步必须同时重读列表与采集状态', () => {
    it('每一轮都重读列表，而不是只重读采集状态', async () => {
      wrapper = mount(Harness);
      await sync();

      expect(reloadListMock).toHaveBeenCalledTimes(1);
      expect(callCount()).toBe(1);

      // 自动轮询推进一个周期：列表也要跟着重读一次
      await vi.advanceTimersByTimeAsync(30_000);
      expect(reloadListMock).toHaveBeenCalledTimes(2);
      expect(callCount()).toBe(2);

      // 同一轮里的两次读取共用一个令牌，且令牌单调递增（旧轮无法覆盖新轮）
      const firstToken = reloadListMock.mock.calls[0][0] as number;
      const secondToken = reloadListMock.mock.calls[1][0] as number;
      expect(secondToken).toBeGreaterThan(firstToken);
      expect(api.isCurrentToken(secondToken)).toBe(true);
      expect(api.isCurrentToken(firstToken)).toBe(false);
    });

    it('列表接口给出的原状态列与采集列在同一轮里都落到界面上', async () => {
      const hostStatus = ref('ONLINE');
      reloadListMock.mockImplementation(async () => {
        // 这一轮列表接口返回了新的原状态列（后端已完成写回）
        hostStatus.value = 'STOPPED';
      });
      listCollectionStatesMock.mockResolvedValue(
        ok([stateView(1, { observedStatus: 'STOPPED', result: 'SUCCESS' })]),
      );

      wrapper = mount(Harness);
      expect(hostStatus.value).toBe('ONLINE');

      const result = await sync();

      expect(result).toBe('SUCCESS');
      expect(hostStatus.value).toBe('STOPPED');
      expect(api.stateMap.value.get(1)?.observedStatus).toBe('STOPPED');
      expect(api.listStale.value).toBe(false);
      expect(api.collectionStale.value).toBe(false);
      expect(api.lastSyncedAt.value).not.toBeNull();
    });

    it('列表重读失败：整轮不算成功，旧值保留并显式标注列表可能过期', async () => {
      wrapper = mount(Harness);
      await sync();
      const syncedAt = api.lastSyncedAt.value;
      expect(syncedAt).not.toBeNull();

      reloadListMock.mockRejectedValue(new Error('list down'));
      const result = await sync();

      expect(result).toBe('FAILED');
      expect(api.listStale.value).toBe(true);
      // 旧值还在，没有被清空
      expect([...api.stateMap.value.keys()]).toEqual([1]);
      // 失败的一轮不许推进"最近一次整轮成功"的时刻
      expect(api.lastSyncedAt.value).toBe(syncedAt);
      expect(api.lastError.value).toBe('list down');
    });

    it('采集状态重读失败：标注采集列可能过期，列表已更新也不算整轮成功', async () => {
      wrapper = mount(Harness);
      await sync();
      const syncedAt = api.lastSyncedAt.value;

      listCollectionStatesMock.mockRejectedValue(new Error('collection down'));
      const result = await sync();

      expect(result).toBe('FAILED');
      expect(api.collectionStale.value).toBe(true);
      expect(api.listStale.value).toBe(false);
      expect(api.lastSyncedAt.value).toBe(syncedAt);
      expect(api.lastError.value).toBe('collection down');
    });

    it('后端说采集未启用时列表仍按周期重读：状态列不因采集关闭就停止跟进', async () => {
      listCollectionStatesMock.mockResolvedValue({
        collectionEnabled: false,
        staleThresholdMs: 90_000,
        states: [],
      });

      wrapper = mount(Harness);
      await sync();
      expect(api.collectionEnabled.value).toBe(false);
      expect(reloadListMock).toHaveBeenCalledTimes(1);

      await vi.advanceTimersByTimeAsync(30_000);
      expect(reloadListMock).toHaveBeenCalledTimes(2);
    });
  });

  describe('不堆叠请求', () => {
    it('上一轮还在飞时自动 tick 跳过并计数，不叠加请求', async () => {
      const slow = deferred<void>();
      reloadListMock.mockReturnValueOnce(slow.promise);

      wrapper = mount(Harness);
      const inFlight = api.syncNow();
      await flush();

      await vi.advanceTimersByTimeAsync(90_000);
      expect(reloadListMock).toHaveBeenCalledTimes(1);
      expect(api.skippedRounds.value).toBeGreaterThanOrEqual(1);

      slow.resolve();
      await inFlight;
    });

    it('上一轮还在飞时手动刷新记一次待办，当前轮结束后恰好补跑一次', async () => {
      const slow = deferred<void>();
      reloadListMock.mockReturnValueOnce(slow.promise);

      wrapper = mount(Harness);
      const inFlight = api.syncNow();
      await flush();

      const manual = api.syncNow({ manual: true });
      expect(reloadListMock).toHaveBeenCalledTimes(1);

      slow.resolve();
      await inFlight;
      await manual;
      await flush();

      // 补跑一次：用户意图不丢，也不横向堆叠
      expect(reloadListMock).toHaveBeenCalledTimes(2);
      // 排队本身不是失败：调用方拿到的是补跑的真实结论，页面据此决定要不要提示
      expect(await manual).toBe('SUCCESS');
    });
  });

  describe('F1：区分成功 / 真实失败 / 已排队或被取代', () => {
    it('自动轮询在飞时改筛选回车：不报假失败，旧轮晚到的响应不写当前展示', async () => {
      // 页面侧：重读列表的人把「当前显示的行」写进 displayedRows；晚到的旧轮不许碰它
      const displayedRows = ref<string[]>(['initial']);
      const roundA = deferred<void>();
      const roundB = deferred<void>();
      let call = 0;
      reloadListMock.mockImplementation(async (_token, isCurrent) => {
        call += 1;
        const isOldest = call === 1;
        await (isOldest ? roundA.promise : roundB.promise);
        // 页面写入前自查：新筛选意图一到，旧轮的写入资格当场作废
        if (!isCurrent()) {
          return;
        }
        displayedRows.value = [isOldest ? 'late-A-host' : 'match-B-host'];
      });

      wrapper = mount(Harness);
      const autoRun = api.syncNow(); // 自动轮询 A
      await flush();
      expect(reloadListMock).toHaveBeenCalledTimes(1);
      const tokenA = reloadListMock.mock.calls[0][0] as number;
      expect(api.isCurrentToken(tokenA)).toBe(true);

      // 用户在查询框输入 match-B 并回车
      const manualRun = api.syncNow({ manual: true });
      await flush();

      // 关键：旧轮不是"等补跑开始才作废"，新意图一到就立刻失效
      expect(api.isCurrentToken(tokenA)).toBe(false);

      // A 晚到：既不许写当前展示，也不许被当成当前展示的失败
      roundA.resolve();
      expect(await autoRun).toBe('COALESCED');
      await flush();
      expect(displayedRows.value).toEqual(['initial']);
      expect(api.listStale.value).toBe(false);
      expect(api.lastError.value).toBeNull();

      // B 成功：最终留下的是最新查询的结果，且排队这件事不产生任何错误提示
      roundB.resolve();
      expect(await manualRun).toBe('SUCCESS');
      expect(displayedRows.value).toEqual(['match-B-host']);
    });

    it('补跑真的失败时仍然报失败：不提示排队不等于把真失败一起吞掉', async () => {
      const roundA = deferred<void>();
      reloadListMock.mockReturnValueOnce(roundA.promise);

      wrapper = mount(Harness);
      const autoRun = api.syncNow();
      await flush();

      const manualRun = api.syncNow({ manual: true });
      // 补跑时列表接口真的挂了
      reloadListMock.mockRejectedValue(new Error('list down'));

      roundA.resolve();
      await autoRun;

      expect(await manualRun).toBe('FAILED');
      expect(api.listStale.value).toBe(true);
      expect(api.lastError.value).toBe('list down');
    });

    it('旧轮在失效后失败：不置过期标记、不计入连续失败，因为它已不属于当前展示', async () => {
      const roundA = deferred<void>();
      reloadListMock.mockReturnValueOnce(roundA.promise);

      wrapper = mount(Harness);
      const autoRun = api.syncNow();
      await flush();

      const manualRun = api.syncNow({ manual: true });
      roundA.reject(new Error('old round exploded'));

      expect(await autoRun).toBe('COALESCED');
      await flush();

      // 这一轮确实失败了，但用户已经不关心它；错误不该挂到当前展示上
      expect(api.listStale.value).toBe(false);
      expect(await manualRun).toBe('SUCCESS');
      expect(api.lastError.value).toBeNull();
      expect(api.pollingPaused.value).toBe(false);
    });

    it('连续两次手动意图合并成恰好一次补跑，两个调用方都拿到补跑的结论', async () => {
      const roundA = deferred<void>();
      reloadListMock.mockReturnValueOnce(roundA.promise);

      wrapper = mount(Harness);
      const autoRun = api.syncNow();
      await flush();

      const first = api.syncNow({ manual: true });
      const second = api.syncNow({ manual: true });
      expect(reloadListMock).toHaveBeenCalledTimes(1);

      roundA.resolve();
      await autoRun;
      await flush();

      expect(reloadListMock).toHaveBeenCalledTimes(2);
      expect(await first).toBe('SUCCESS');
      expect(await second).toBe('SUCCESS');
    });

    const PAGES: Array<[string, CollectionTargetType]> = [
      ['主机页', COLLECTION_TARGET_TYPE.HOST],
      ['运行实例页', COLLECTION_TARGET_TYPE.SERVICE_INSTANCE],
    ];

    for (const [label, targetType] of PAGES) {
      it(`${label}：列表、派生健康、采集列三处写入共用同一意图版本`, async () => {
        const rows = ref<string[]>([]); // 列表写入
        const derivedHealth = ref<string[]>([]); // 派生健康写入
        const roundA = deferred<void>();
        const roundB = deferred<void>();
        let call = 0;
        reloadListMock.mockImplementation(async (_token, isCurrent) => {
          call += 1;
          const isOldest = call === 1;
          await (isOldest ? roundA.promise : roundB.promise);
          if (!isCurrent()) {
            return;
          }
          rows.value = [isOldest ? 'A行' : 'B行'];
          derivedHealth.value = [isOldest ? 'A健康' : 'B健康'];
        });

        wrapper = mount(makeHarness(targetType));
        const autoRun = api.syncNow();
        await flush();

        const manualRun = api.syncNow({ manual: true });
        await flush();

        roundA.resolve();
        expect(await autoRun).toBe('COALESCED');
        await flush();

        // 旧轮三处写入（列表 / 派生健康 / 采集列）全部作废
        expect(rows.value).toEqual([]);
        expect(derivedHealth.value).toEqual([]);
        expect(api.stateMap.value.size).toBe(0);

        roundB.resolve();
        expect(await manualRun).toBe('SUCCESS');
        await flush();

        // 只有最新查询的结果留下，采集列由同一轮写入
        expect(rows.value).toEqual(['B行']);
        expect(derivedHealth.value).toEqual(['B健康']);
        expect([...api.stateMap.value.keys()]).toEqual([1]);
      });
    }
  });

  it('轮询间隔由后端下发的过期阈值推导，并夹在 [10s, 60s] 之间', async () => {
    wrapper = mount(Harness);

    // 还没拿到后端阈值时用兜底值推导，同样受下限约束
    expect(api.effectiveIntervalMs.value).toBe(10_000);

    listCollectionStatesMock.mockResolvedValue(ok([], 90_000));
    await sync();
    expect(api.effectiveIntervalMs.value).toBe(30_000);

    listCollectionStatesMock.mockResolvedValue(ok([], 5_000));
    await sync();
    expect(api.effectiveIntervalMs.value).toBe(10_000);

    listCollectionStatesMock.mockResolvedValue(ok([], 600_000));
    await sync();
    expect(api.effectiveIntervalMs.value).toBe(60_000);
  });

  it('本页没有目标时一个采集请求都不发，并清掉上一页残留的状态', async () => {
    wrapper = mount(Harness);
    await sync();
    expect(api.stateMap.value.size).toBe(1);
    expect(callCount()).toBe(1);

    idSource.value = [];
    await sync();

    expect(callCount()).toBe(1);
    expect(api.stateMap.value.size).toBe(0);
  });

  it('轮次之间串行：新一轮等旧一轮结束后才发出，最终留下的是新一轮的结果', async () => {
    const first = deferred<CollectionStateListResponse>();
    listCollectionStatesMock.mockReturnValueOnce(first.promise);

    wrapper = mount(Harness);

    const firstRun = api.syncNow();
    await flush();
    expect(callCount()).toBe(1);

    // 第一轮还在飞时点刷新：不叠加请求，只登记一次待办
    const secondRun = api.syncNow({ manual: true });
    await flush();
    expect(callCount()).toBe(1);

    // 后端此刻已经把本页目标换成 [2]，第二轮据此写入
    idSource.value = [2];
    listCollectionStatesMock.mockResolvedValue(ok([stateView(2)]));
    first.resolve(ok([stateView(1)]));

    await firstRun;
    await secondRun;
    await flush();

    expect(callCount()).toBe(2);
    expect([...api.stateMap.value.keys()]).toEqual([2]);
  });

  it('读不到时保留上一次成功的结果，连续失败到上限就停下自动轮询', async () => {
    wrapper = mount(Harness);
    await sync();
    expect(api.stateMap.value.size).toBe(1);
    const afterFirstSuccess = callCount();

    listCollectionStatesMock.mockRejectedValue(new Error('backend down'));

    // 第 1 次失败：状态还在，轮询继续
    await vi.advanceTimersByTimeAsync(30_000);
    expect(api.pollingPaused.value).toBe(false);
    expect(api.collectionStale.value).toBe(true);
    expect(api.stateMap.value.has(1)).toBe(true);
    expect(api.stateMap.value.get(1)?.lastSuccessAt).toBe('2026-10-15T02:00:00Z');
    expect(callCount()).toBe(afterFirstSuccess + 1);

    // 第 2 次失败：仍未暂停
    await vi.advanceTimersByTimeAsync(30_000);
    expect(api.pollingPaused.value).toBe(false);

    // 第 3 次失败：暂停，且定时器停掉
    await vi.advanceTimersByTimeAsync(30_000);
    expect(api.pollingPaused.value).toBe(true);
    const atPause = callCount();
    expect(atPause).toBe(afterFirstSuccess + 3);

    // 暂停后不再空打请求
    await vi.advanceTimersByTimeAsync(120_000);
    expect(callCount()).toBe(atPause);

    // 用户手动刷新可以解除暂停并恢复轮询
    listCollectionStatesMock.mockResolvedValue(ok([stateView(1)]));
    await sync();
    expect(api.pollingPaused.value).toBe(false);
    expect(api.collectionStale.value).toBe(false);

    await vi.advanceTimersByTimeAsync(30_000);
    expect(callCount()).toBe(atPause + 2);
  });

  it('页面隐藏时停止定时器，恢复可见时立刻补一次并重启', async () => {
    wrapper = mount(Harness);
    await sync();
    const afterLoad = callCount();

    setVisibility('hidden');
    await vi.advanceTimersByTimeAsync(300_000);
    expect(callCount()).toBe(afterLoad);

    setVisibility('visible');
    await flush();
    await flush();
    expect(callCount()).toBe(afterLoad + 1);

    await vi.advanceTimersByTimeAsync(30_000);
    expect(callCount()).toBe(afterLoad + 2);
  });

  it('首屏列表尚未加载时不空查；拿到回答之前不许把「还没查」说成「采集未启用」', async () => {
    idSource.value = [];
    wrapper = mount(Harness);

    expect(api.collectionEnabled.value).toBeNull();
    expect(api.staleThresholdMs.value).toBeNull();

    // 目标还没加载出来，轮询照常按兜底间隔武装着，但采集请求会被短路掉
    await vi.advanceTimersByTimeAsync(60_000);
    expect(callCount()).toBe(0);
    expect(api.collectionEnabled.value).toBeNull();

    // 列表加载出来了、接口却还没回答：仍然不能自行推断成"未启用"
    const pending = deferred<CollectionStateListResponse>();
    listCollectionStatesMock.mockReturnValue(pending.promise);
    idSource.value = [1];

    void api.syncNow();
    await flush();

    expect(callCount()).toBe(1);
    expect(api.collectionEnabled.value).toBeNull();
    expect(api.pollingPaused.value).toBe(false);
  });

  it('卸载后停止监听与轮询，在途响应也不再写状态', async () => {
    wrapper = mount(Harness);
    await sync();
    expect([...api.stateMap.value.keys()]).toEqual([1]);

    const inFlight = deferred<CollectionStateListResponse>();
    listCollectionStatesMock.mockReturnValueOnce(inFlight.promise);
    const pending = api.syncNow();
    await flush();

    const inFlightToken = reloadListMock.mock.calls[reloadListMock.mock.calls.length - 1][0] as number;
    expect(api.isCurrentToken(inFlightToken)).toBe(true);

    wrapper.unmount();
    wrapper = null;

    inFlight.resolve(ok([stateView(2)]));
    await pending;
    await flush();

    // 卸载后才到达的响应不许写进来，通往页面的令牌也一律失效
    expect([...api.stateMap.value.keys()]).toEqual([1]);
    expect(api.isCurrentToken(inFlightToken)).toBe(false);

    const afterUnmount = callCount();
    await vi.advanceTimersByTimeAsync(300_000);
    setVisibility('visible');
    await flush();
    await flush();

    expect(callCount()).toBe(afterUnmount);
  });

  it('stateFor 给出对应目标的状态，未采集到的目标返回 undefined', async () => {
    wrapper = mount(Harness);
    await sync();

    expect(api.stateFor(1)?.targetId).toBe(1);
    expect(api.stateFor(2)).toBeUndefined();
  });

  it('请求参数带上当前目标类型，并只请求本页真实存在的目标', async () => {
    idSource.value = [11, 12, null];
    wrapper = mount(Harness);
    await sync();

    expect(listCollectionStatesMock).toHaveBeenCalledWith(COLLECTION_TARGET_TYPE.HOST, [11, 12, null]);
  });
});