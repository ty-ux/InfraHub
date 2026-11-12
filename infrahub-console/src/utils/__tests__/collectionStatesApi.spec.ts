import { beforeEach, describe, expect, it, vi } from 'vitest';

/**
 * 采集状态只读接口的入参处理。
 *
 * 这里钉住的是两件容易出错的事：
 * 1. 后端是 `@RequestParam List<Long>`，axios 默认的 `targetIds[]=1` 形式绑不上，
 *    必须自己拼成逗号串；同时要去重、去掉 null 与非整数，不能让非法值走到线上。
 * 2. 没有目标时**不发请求、也不返回一个伪造的 collectionEnabled:false**——
 *    "没查过"和"查过且采集是关的"是两件事，后者会让界面喊出没有依据的"采集未启用"。
 */

vi.mock('@/utils/http', () => ({
  get: vi.fn(),
}));

import { get } from '@/utils/http';
import {
  COLLECTION_TARGET_TYPE,
  listCollectionStates,
  type CollectionStateListResponse,
} from '@/api/monitorCollection';

const getMock = vi.mocked(get);

const response: CollectionStateListResponse = {
  collectionEnabled: true,
  staleThresholdMs: 90_000,
  states: [],
};

describe('listCollectionStates：入参与短路', () => {
  beforeEach(() => {
    getMock.mockReset();
    getMock.mockResolvedValue(response);
  });

  it('目标 id 去重并去掉 null / 非整数，再按逗号拼接（后端按 List<Long> 绑定）', async () => {
    await listCollectionStates(COLLECTION_TARGET_TYPE.HOST, [3, 1, 3, null, undefined, 2.5, 1]);

    expect(getMock).toHaveBeenCalledTimes(1);
    expect(getMock).toHaveBeenCalledWith('/api/monitor/collection-states', {
      targetType: 'HOST',
      targetIds: '3,1',
    });
  });

  it('目标类型原样透传：实例列表走同一条接口的另一分支', async () => {
    await listCollectionStates(COLLECTION_TARGET_TYPE.SERVICE_INSTANCE, [7]);

    expect(getMock).toHaveBeenCalledWith('/api/monitor/collection-states', {
      targetType: 'SERVICE_INSTANCE',
      targetIds: '7',
    });
  });

  it('没有任何可查目标：返回 null，且一个请求都不发', async () => {
    await expect(listCollectionStates(COLLECTION_TARGET_TYPE.HOST, [])).resolves.toBeNull();
    await expect(
      listCollectionStates(COLLECTION_TARGET_TYPE.HOST, [null, undefined]),
    ).resolves.toBeNull();

    expect(getMock).not.toHaveBeenCalled();
  });

  it('空结果不能被伪装成「采集未启用」：null 与 collectionEnabled:false 必须分得开', async () => {
    const result = await listCollectionStates(COLLECTION_TARGET_TYPE.HOST, []);

    expect(result).toBeNull();
    expect(result === null ? 'unknown' : 'would-claim-disabled').toBe('unknown');
  });

  it('把后端返回体原样交给调用方，不做本地加工', async () => {
    getMock.mockResolvedValue({
      collectionEnabled: false,
      staleThresholdMs: 45_000,
      states: [
        {
          targetType: 'HOST',
          targetId: 1,
          observerType: 'SSH_PROCESS',
          source: 'SSH_PROBE',
          configPresent: true,
          result: 'FAILED',
          freshness: 'STALE',
          lastSuccessAt: '2026-01-01T00:00:00Z',
          errorCategory: 'AUTH_FAILED',
        },
      ],
    });

    const result = await listCollectionStates(COLLECTION_TARGET_TYPE.HOST, [1]);

    expect(result?.collectionEnabled).toBe(false);
    expect(result?.staleThresholdMs).toBe(45_000);
    expect(result?.states).toHaveLength(1);
    expect(result?.states[0].errorCategory).toBe('AUTH_FAILED');
  });
});