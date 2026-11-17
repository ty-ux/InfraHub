import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { setLocale } from '@/i18n';
import { getPageConfig } from '../page-configs';
import { envApi, serviceApi, appApi } from '@/api/resource';
import { currentVersionApi, releaseApi, rollbackApi, versionApi } from '@/api/release';
import { listRuntimeServiceInstances, type RuntimeServiceInstance } from '@/api/runtime';

/**
 * 回滚单页面必须取消通用编辑与通用删除：后端目标更新是 PUT /api/rollbacks/{id}，只接受 toVersionId，
 * 且 RollbackController 没有 DELETE 路由。通用编辑提交整张表单会让其余字段被静默忽略却提示成功，
 * 通用删除则会打到不存在的接口。这里用配置层断言锁住该约束，
 * 并确认没有用 editAuthority 掩盖接口与表单的差异、也没有波及其他 CRUD 页面。
 */
describe('page-configs: releaseRollbacks', () => {
  it('does not expose a generic update for rollback orders', () => {
    const config = getPageConfig('releaseRollbacks');

    expect(config).toBeTruthy();
    expect(config.api.update).toBeUndefined();
  });

  it('does not expose a generic remove for rollback orders', () => {
    const config = getPageConfig('releaseRollbacks');

    expect(config).toBeTruthy();
    expect(config.api.remove).toBeUndefined();
  });

  it('keeps query, create and the dedicated candidate target save path available', () => {
    const config = getPageConfig('releaseRollbacks');

    expect(typeof config.api.page).toBe('function');
    expect(typeof config.api.create).toBe('function');
    expect(config.createAuthority).toEqual(['ROLLBACK_CREATE', 'SYSTEM_ADMIN']);
    // 不得用 editAuthority 掩盖差异：去掉 update 后编辑入口自然消失
    expect(config.editAuthority).toBeUndefined();
  });

  it('leaves other CRUD pages with their generic edit untouched', () => {
    expect(typeof getPageConfig('releaseOrders').api.update).toBe('function');
    expect(typeof getPageConfig('apps').api.update).toBe('function');
  });
});

// 当前版本列表的实例增强会读取实例列表（只读 GET），因此这里把相关接口全部 mock：
// 断言只关心聚合结果与请求参数，不连接真实后端。
vi.mock('@/api/resource', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/resource')>();
  return {
    ...actual,
    envApi: { ...actual.envApi, page: vi.fn() },
    serviceApi: { ...actual.serviceApi, page: vi.fn() },
    appApi: { ...actual.appApi, page: vi.fn() },
  };
});

vi.mock('@/api/release', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/release')>();
  return {
    ...actual,
    currentVersionApi: { ...actual.currentVersionApi, page: vi.fn() },
    versionApi: { ...actual.versionApi, page: vi.fn() },
    releaseApi: { ...actual.releaseApi, page: vi.fn() },
    rollbackApi: { ...actual.rollbackApi, page: vi.fn() },
  };
});

vi.mock('@/api/runtime', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/runtime')>();
  return { ...actual, listRuntimeServiceInstances: vi.fn() };
});

const mockedEnvPage = vi.mocked(envApi.page);
const mockedServicePage = vi.mocked(serviceApi.page);
const mockedAppPage = vi.mocked(appApi.page);
const mockedVersionPage = vi.mocked(versionApi.page);
const mockedReleasePage = vi.mocked(releaseApi.page);
const mockedRollbackPage = vi.mocked(rollbackApi.page);
const mockedInstanceList = vi.mocked(listRuntimeServiceInstances);

function pageOf(records: Record<string, unknown>[]) {
  return { pageNum: 1, pageSize: 200, total: records.length, records } as never;
}

function instance(overrides: Record<string, unknown> = {}): RuntimeServiceInstance {
  return { id: 900, instanceName: '订单服务-1', hostId: 7, versionId: 33, serviceId: 11, envId: 22, ...overrides } as unknown as RuntimeServiceInstance;
}

/** 按当前版本记录 id 取出增强后的那一行 */
async function enrich(records: Record<string, unknown>[], pageSize = records.length) {
  const config = getPageConfig('currentVersions');
  mockedCurrentVersionPage.mockResolvedValue({ pageNum: 1, pageSize, total: records.length, records } as never);
  const result = await config.api.page!({ pageNum: 1, pageSize });
  return result.records;
}

const mockedCurrentVersionPage = vi.mocked(currentVersionApi.page);

beforeEach(() => {
  mockedEnvPage.mockResolvedValue(pageOf([{ id: 22, envName: '生产' }]));
  mockedServicePage.mockResolvedValue(pageOf([{ id: 11, serviceName: '订单服务', serviceCode: 'ORDER', appId: 5 }]));
  mockedAppPage.mockResolvedValue(pageOf([{ id: 5, appCode: 'APP-ORDER' }]));
  mockedVersionPage.mockResolvedValue(pageOf([{ id: 33, versionNo: 'v1.0.0' }]));
  mockedReleasePage.mockResolvedValue(pageOf([{ id: 44, orderNo: 'REL-1' }]));
  mockedRollbackPage.mockResolvedValue(pageOf([{ id: 55, rollbackNo: 'RB-1' }]));
  mockedInstanceList.mockResolvedValue([instance()]);
});

afterEach(() => {
  vi.clearAllMocks();
  setLocale('zh-CN');
});

describe('page-configs: currentVersions 只读与实例列', () => {
  it('当前版本列表不暴露任何写入口，查询被实例增强包装', () => {
    const config = getPageConfig('currentVersions');

    expect(config).toBeTruthy();
    expect(typeof config.api.page).toBe('function');
    expect(config.api.create).toBeUndefined();
    expect(config.api.update).toBeUndefined();
    expect(config.api.remove).toBeUndefined();
    // 实例列走专用渲染槽，不由通用格式化推断
    const column = config.columns.find(item => item.prop === 'runtimeInstanceState');
    expect(column?.slot).toBe('runtimeInstance');
    // 页面沿用现有发布查看权限，不因新增诊断入口放宽
    expect(config.createAuthority).toEqual(['RELEASE_VIEW', 'SYSTEM_ADMIN']);
  });

  it('单实例给出这一条实例的身份信息', async () => {
    const [record] = await enrich([{ id: 1, serviceId: 11, envId: 22, versionId: 33, sourceReleaseOrderId: 44, sourceRollbackOrderId: 55 }]);

    expect(mockedInstanceList).toHaveBeenCalledWith({ serviceId: 11, envId: 22 });
    expect(record.runtimeInstanceState).toBe('SINGLE');
    expect(record.runtimeInstanceCount).toBe(1);
    expect(record.runtimeInstanceName).toBe('订单服务-1');
    expect(record.runtimeInstanceId).toBe(900);
    expect(record.serviceDisplayName).toBe('订单服务');
    expect(record.envDisplayName).toBe('生产');
    expect(record.versionDisplayName).toBe('v1.0.0');
  });

  it('多实例只给出数量，不按数组顺序挑一个实例代表整个服务', async () => {
    mockedInstanceList.mockResolvedValue([instance({ id: 900 }), instance({ id: 901, instanceName: '订单服务-2' })]);

    const [record] = await enrich([{ id: 1, serviceId: 11, envId: 22 }]);

    expect(record.runtimeInstanceState).toBe('MULTIPLE');
    expect(record.runtimeInstanceCount).toBe(2);
    // 多实例下不得出现"绑定实例"的名字或 ID，诊断目标由用户在弹窗里显式选择
    expect(record.runtimeInstanceName).toBeNull();
    expect(record.runtimeInstanceId).toBeNull();
  });

  it('没有实例与实例列表读取失败分别表达，读取失败不塌成"没有实例"', async () => {
    mockedInstanceList.mockResolvedValue([]);
    const [none] = await enrich([{ id: 1, serviceId: 11, envId: 22 }]);
    expect(none.runtimeInstanceState).toBe('NONE');
    expect(none.runtimeInstanceCount).toBe(0);
    expect(none.runtimeInstanceId).toBeNull();

    mockedInstanceList.mockRejectedValue(new Error('实例列表读取超时'));
    const [failed] = await enrich([{ id: 1, serviceId: 11, envId: 22 }]);
    expect(failed.runtimeInstanceState).toBe('ERROR');
    expect(failed.runtimeInstanceState).not.toBe('NONE');
    // 读取失败不是"0 个实例"，数量保持未知
    expect(failed.runtimeInstanceCount).toBeNull();
    expect(failed.runtimeInstanceId).toBeNull();
  });

  it('返回的实例全部不属于本目标时判为作用域错误，不静默过滤成"没有实例"', async () => {
    // 合成响应：按 serviceId=11 / envId=22 查询，返回的条目却属于 serviceId=999 / envId=999
    mockedInstanceList.mockResolvedValue([instance({ id: 999, instanceName: '外来实例', serviceId: 999, envId: 999 })]);

    const [record] = await enrich([{ id: 1, serviceId: 11, envId: 22 }]);

    expect(record.runtimeInstanceState).toBe('ERROR');
    // 过滤掉外来条目再宣称"没有实例"正是要禁止的行为
    expect(record.runtimeInstanceState).not.toBe('NONE');
    // 作用域错误也不是"0 个实例"，数量保持未知
    expect(record.runtimeInstanceCount).toBeNull();
    // 不得把外来实例的身份写进列表摘要
    expect(record.runtimeInstanceName).toBeNull();
    expect(record.runtimeInstanceId).toBeNull();
    expect(JSON.stringify(record)).not.toContain('外来实例');
  });

  it('只有部分条目作用域不符时同样判为作用域错误，与诊断弹窗判据一致', async () => {
    mockedInstanceList.mockResolvedValue([
      instance({ id: 900, instanceName: '订单服务-1' }),
      instance({ id: 999, instanceName: '外来实例', serviceId: 999, envId: 999 }),
    ]);

    const [record] = await enrich([{ id: 1, serviceId: 11, envId: 22 }]);

    expect(record.runtimeInstanceState).toBe('ERROR');
    expect(record.runtimeInstanceCount).toBeNull();
    expect(record.runtimeInstanceId).toBeNull();
    expect(JSON.stringify(record)).not.toContain('外来实例');
  });

  it('实例请求严格按每条记录自己的 serviceId 与 envId 发起，聚合结果不串行', async () => {
    mockedServicePage.mockResolvedValue(pageOf([
      { id: 11, serviceName: '订单服务', serviceCode: 'ORDER', appId: 5 },
      { id: 12, serviceName: '支付服务', serviceCode: 'PAY', appId: 5 },
    ]));
    mockedInstanceList.mockImplementation(async (params: { serviceId: number }) => (
      params.serviceId === 11 ? [instance({ id: 900 })] : [instance({ id: 950, serviceId: 12, instanceName: '支付服务-1' }), instance({ id: 951, serviceId: 12, instanceName: '支付服务-2' })]
    ));

    const records = await enrich([
      { id: 1, serviceId: 11, envId: 22 },
      { id: 2, serviceId: 12, envId: 22 },
    ]);

    expect(mockedInstanceList.mock.calls.map(call => call[0])).toEqual([{ serviceId: 11, envId: 22 }, { serviceId: 12, envId: 22 }]);
    expect(records[0].runtimeInstanceState).toBe('SINGLE');
    expect(records[0].runtimeInstanceId).toBe(900);
    expect(records[1].runtimeInstanceState).toBe('MULTIPLE');
    expect(records[1].runtimeInstanceCount).toBe(2);
  });

  it('缺少服务或环境信息时不推断实例状态，也不读实例列表', async () => {
    const [record] = await enrich([{ id: 1, serviceId: 0, envId: 22 }]);

    expect(record.runtimeInstanceState).toBe('UNKNOWN');
    expect(record.runtimeInstanceCount).toBeNull();
    expect(mockedInstanceList).not.toHaveBeenCalled();
  });

  it('实例列标题随语言切换，且列表本身仍是同一份只读配置', () => {
    setLocale('zh-CN');
    const zh = getPageConfig('currentVersions').columns.find(item => item.prop === 'runtimeInstanceState');
    expect(zh?.label).toBe('运行实例');

    setLocale('en-US');
    const en = getPageConfig('currentVersions').columns.find(item => item.prop === 'runtimeInstanceState');
    expect(en?.label).toBe('Runtime Instance');
    expect(getPageConfig('currentVersions').api.update).toBeUndefined();
  });
});

describe('page-configs: pipelineRuns 只读与状态列', () => {
  it('运行列表保持只读，状态与耗时使用专用渲染槽', () => {
    const config = getPageConfig('pipelineRuns');

    expect(config.api.create).toBeUndefined();
    expect(config.api.update).toBeUndefined();
    expect(config.api.remove).toBeUndefined();
    const status = config.columns.find(item => item.prop === 'runStatus');
    expect(status?.slot).toBe('runStatus');
    const duration = config.columns.find(item => item.prop === 'durationMs');
    expect(duration?.slot).toBe('duration');
    expect(config.createAuthority).toEqual(['PIPELINE_VIEW', 'PIPELINE_EXECUTE', 'SYSTEM_ADMIN']);
  });
});
