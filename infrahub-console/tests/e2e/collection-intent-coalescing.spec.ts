import { expect, test, type Page, type Route } from '@playwright/test';
import { loginAsAdmin } from './helpers/auth';

/**
 * F1：一轮同步的「意图版本」——排队不算失败，旧轮次不写当前展示。
 *
 * ## 合成数据边界（务必先读）
 *
 * - 本文件用 `page.route` 拦截了这两个页面会用到的**全部**业务接口；
 *   浏览器不会访问任何真实后端，也不连 SSH、不触发采集、不写数据库。
 * - 主机 / 运行实例 / 采集状态 / 健康档案 / 资源视图全部是本文件构造的合成数据，
 *   只用来验证「轮次归属与提示行为」，不对应任何真实环境。
 * - 唯一被当作结论看待的东西是前端的渲染与提示，不是数据本身。
 *
 * ## 被验证的时序（主机列表页与运行实例列表页各跑一遍）
 *
 * ```
 * 第 0 轮（首屏）完成
 *   ↓ 触发一次非手动轮次 A（与自动 tick 完全同一个入口），并让它的列表请求一直挂着
 * 用户在筛选框输入新关键字并回车（或点「下一页」）→ B 意图到达：A 当场失去写入资格，
 *                                                     B 登记为「恰好一次」补跑
 *   ↓ 这一段里必须：不弹「加载失败」、不再叠一个列表请求（单飞）
 * 放行 A 的迟到响应（带 A-LATE 行）
 *   ↓ A 的响应不得写进已经换了条件的表格
 * B 的补跑发出，且带的是**最新查询条件**
 *   ↓ B 成功 → 表格是 B 的行；B 响应 500 → 照旧提示真实失败
 * ```
 *
 * 「非手动轮次 A」走的是页面可见性恢复那条路（`void syncNow()`，不带 `manual`），
 * 与定时器 tick 调用的是**同一个入口、同一套非手动语义**；这样不必真的等 10 秒轮询间隔，
 * 时序完全由测试决定。
 */

interface ListQuery {
  keyword: string;
  pageNum: string;
}

/** 构造一页列表响应（与 `unwrapApiResponse` 期望的信封一致） */
function pageResponse(records: Array<Record<string, unknown>>, total = records.length) {
  return {
    code: 'SUCCESS',
    message: '',
    traceId: 'trace-synthetic',
    data: { pageNum: 1, pageSize: 10, total, records },
  };
}

function apiResponse(data: unknown) {
  return { code: 'SUCCESS', message: '', traceId: 'trace-synthetic', data };
}

interface PageConfig {
  /** 用例覆盖的页面路由 */
  route: string;
  /** 列表接口完整路径（用来把「列表请求」与同前缀的其他请求分开） */
  listPath: string;
  /** 列表接口的通配匹配串 */
  listGlob: string;
  /** 表格里表示行身份的那个字段 */
  rowNameField: 'hostname' | 'instanceName';
  /** 列表读取失败时页面上必须出现的那句「旧值可能过期」提示 */
  staleListText: string;
  /** 该页面还需要额外 mock 的接口（健康档案、资源视图、下拉选项……） */
  extraMocks: (page: Page) => Promise<void>;
}

const HOSTS: PageConfig = {
  route: '/resource/hosts',
  listPath: '/api/assets/hosts',
  listGlob: '**/api/assets/hosts**',
  rowNameField: 'hostname',
  staleListText: '主机状态可能已过期（最近一次列表读取失败）',
  extraMocks: async (page) => {
    // 列表行会逐台补健康档案；不 mock 的话 axios 会把 404 变成一条错误提示，
    // 污染「有没有假失败」的断言。
    await page.route(/\/api\/assets\/hosts\/(\d+)\/profile/, async (route) => {
      const hostId = Number(route.request().url().match(/\/hosts\/(\d+)\/profile/)?.[1] ?? 1);
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(apiResponse({
          hostId,
          healthStatus: 'UP',
          healthReason: '合成数据：健康档案',
          runtimeInstanceCount: 1,
        })),
      });
    });
    await page.route('**/api/envs**', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(pageResponse([{ id: 1, envCode: 'synthetic-env', envName: '合成环境' }])),
      });
    });
    await page.route('**/api/assets/resource-pools**', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(pageResponse([{ id: 1, poolCode: 'synthetic-pool', poolName: '合成资源池' }])),
      });
    });
  },
};

const RUNTIME_INSTANCES: PageConfig = {
  route: '/resource/runtime-instances',
  listPath: '/api/runtime/service-instances/page',
  listGlob: '**/api/runtime/service-instances/page**',
  rowNameField: 'instanceName',
  staleListText: '实例状态可能已过期（最近一次列表读取失败）',
  extraMocks: async (page) => {
    // 该页的下拉选项里有一次 200 条的主机查找，和主机列表页用的是同一个接口
    await page.route('**/api/assets/hosts**', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(pageResponse([
          { id: 1, hostname: 'synthetic-host', ip: '192.0.2.12', envId: 1 },
        ])),
      });
    });
    await page.route('**/api/services**', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(pageResponse([{ id: 1, serviceCode: 'synthetic-svc', serviceName: '合成服务' }])),
      });
    });
    await page.route('**/api/envs**', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(pageResponse([{ id: 1, envCode: 'synthetic-env', envName: '合成环境' }])),
      });
    });
    await page.route(/\/api\/runtime\/service-instances\/(\d+)\/resource-view/, async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(apiResponse({ instanceId: 1, cpuUsage: 0.1, memoryUsage: 0.2 })),
      });
    });
  },
};

/**
 * 装两样"全程都在看"的见证者：
 *
 * <ul>
 *   <li>`window.__errorToasts`：出现过的每一种错误提示（脱重）。判断"有没有假失败"必须在整条
 *       时序上成立，不能只在某一个瞬间看一眼——提示几十毫秒后就会自己消失。</li>
 *   <li>`window.__rowWitness`：这两个哨兵行**有没有在 DOM 里出现过**。同理，"旧轮次不写当前展示"
 *       是"从来没有出现过"，不是"最后一刻恰好看不见"。`fresh` 那一半是自检：它必须为真，
 *       否则说明见证者本身没工作，`late` 为假就毫无意义。</li>
 * </ul>
 */
async function installWitnesses(page: Page) {
  await page.addInitScript(
    ({ lateText, freshText }) => {
      const toasts: string[] = [];
      const witness = { late: false, fresh: false };
      (window as unknown as { __errorToasts: string[] }).__errorToasts = toasts;
      (window as unknown as { __rowWitness: typeof witness }).__rowWitness = witness;

      const scan = () => {
        document.querySelectorAll('.el-message--error').forEach((el) => {
          const text = (el.textContent || '').trim();
          if (text && !toasts.includes(text)) {
            toasts.push(text);
          }
        });
        const text = document.body ? document.body.textContent || '' : '';
        if (text.includes(lateText)) {
          witness.late = true;
        }
        if (text.includes(freshText)) {
          witness.fresh = true;
        }
      };

      document.addEventListener('DOMContentLoaded', () => {
        new MutationObserver(scan).observe(document.body, { childList: true, subtree: true });
        scan();
      });
    },
    { lateText: LATE_A, freshText: FRESH_B },
  );
}

function readErrorToasts(page: Page): Promise<string[]> {
  return page.evaluate(() => (window as unknown as { __errorToasts: string[] }).__errorToasts);
}

interface RowWitness {
  late: boolean;
  fresh: boolean;
}

function readRowWitness(page: Page): Promise<RowWitness> {
  return page.evaluate(() => (window as unknown as { __rowWitness: RowWitness }).__rowWitness);
}

interface ScenarioState {
  /** 每一次「列表」请求带的查询条件（不含下拉选项的查找请求） */
  listRequests: ListQuery[];
  /** 没有落到任何显式 mock 上的业务请求（末尾断言它为空，才谈得上「全部拦截」） */
  unmockedApiRequests: string[];
  /** 让下一个列表请求一直挂着，直到测试放行 */
  holdNextListRequest(): void;
  /** 放行被挂住的列表请求 */
  releaseHeldList(answer: { status: number; body: unknown }): void;
}

interface ListAnswer {
  records: Array<Record<string, unknown>>;
  /** 省略时等于本页行数；翻页相关用例必须给真实总数，否则分页条会自己把页码夹回去 */
  total?: number;
}

interface ScenarioOptions {
  /** 列表在「不挂起」时返回什么：按查询条件决定，能让补跑不用额外写放行逻辑 */
  rowsFor: (query: ListQuery) => ListAnswer;
}

/**
 * 装好这一页需要的全部拦截，并返回列表请求的控制器。
 */
async function setupScenario(
  page: Page,
  config: PageConfig,
  options: ScenarioOptions,
): Promise<ScenarioState> {
  let hold = false;
  let release: ((answer: { status: number; body: unknown }) => void) | null = null;

  const state: ScenarioState = {
    listRequests: [],
    unmockedApiRequests: [],
    holdNextListRequest: () => { hold = true; },
    releaseHeldList: (answer) => {
      const pending = release;
      if (!pending) {
        throw new Error('当前没有被挂起的列表请求，用例时序写错了');
      }
      release = null;
      pending(answer);
    },
  };

  const listHandler = async (route: Route) => {
    const url = new URL(route.request().url());
    if (url.pathname !== config.listPath) {
      await route.fallback();
      return;
    }
    const query: ListQuery = {
      keyword: url.searchParams.get('keyword') ?? '',
      pageNum: url.searchParams.get('pageNum') ?? '',
    };
    state.listRequests.push(query);

    if (hold) {
      hold = false;
      await new Promise<void>((resolve) => {
        release = (answer) => {
          void route
            .fulfill({
              status: answer.status,
              contentType: 'application/json',
              body: JSON.stringify(answer.body),
            })
            .finally(resolve);
        };
      });
      return;
    }

    const answer = options.rowsFor(query);
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(pageResponse(answer.records, answer.total ?? answer.records.length)),
    });
  };

  // 兜底：注册顺序最靠前 = 匹配优先级最低。任何**没有**被显式 mock 命中的业务请求都落到这里，
  // 既不外泄到真实后端（本机 dev server 的 /api 代理），又被记下来供末尾断言。
  // 「全部业务 API 拦截」因此是被强制的属性，而不只是本文件的意图。
  //
  // 这里必须用 URL 判定而不是 `**/api/**` 通配串：后者会把 Vite 自己的模块请求
  // （`/src/api/asset.ts` 之类）也算成业务接口，整个应用都加载不起来。
  await page.route(
    (url) => url.pathname.startsWith('/api/'),
    async (route) => {
      const url = new URL(route.request().url());
      if (url.pathname.startsWith('/api/auth/')) {
        // 登录态由 loginAsAdmin 自己的 mock 负责，不能在这里截断
        await route.fallback();
        return;
      }
      state.unmockedApiRequests.push(url.pathname + url.search);
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(pageResponse([])),
      });
    },
  );

  await config.extraMocks(page);
  // 列表拦截最后注册：Playwright 后注册的先匹配，保证它优先于同前缀的其他路由
  await page.route(config.listGlob, listHandler);

  // 采集状态：返回空 states 但 collectionEnabled=true，页面上就不会多出「采集未启用」那条提示
  await page.route('**/api/monitor/collection-states**', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(apiResponse({
        collectionEnabled: true,
        staleThresholdMs: 30_000,
        states: [],
      })),
    });
  });

  return state;
}

function makeRow(config: PageConfig, id: number, name: string) {
  return { id, [config.rowNameField]: name, envId: 1, resourcePoolId: 1 };
}

const ROUND0 = 'SYNTH-ROUND0';
const LATE_A = 'SYNTH-A-LATE';
const FRESH_B = 'SYNTH-B-FRESH';
const FRESH_KEYWORD = 'synth-b';
const FAILURE_MESSAGE = '合成数据：采集列表读取失败';

/** 首屏那 25 行：只有第一行有名字，其余只是把分页撑到「有下一页」 */
function initialRows(config: PageConfig) {
  const rows = [makeRow(config, 10, ROUND0)];
  for (let i = 1; i < 25; i++) {
    rows.push(makeRow(config, 100 + i, `SYNTH-FILLER-${i}`));
  }
  return rows;
}

/**
 * 触发一次**非手动**轮次并把它挂住。
 *
 * `visibilitychange` 到可见态时页面调的就是 `void syncNow()`——与自动 tick 同一个入口、
 * 同样的「非手动」语义，所以这里测到的确实是非手动轮次被新意图取代的场景。
 *
 * 两处细节都是为了不靠"猜"：
 * <ul>
 *   <li>先等到首屏那一轮的列表请求**已经发出**，才开始挂起——否则被挂住的可能是首屏那一轮；</li>
 *   <li>首屏那一轮可能还在飞，此时派发 `visibilitychange` 只会被当成一次 skip（非手动不新开轮次），
 *       所以反复派发直到真的多出一次列表请求为止。</li>
 * </ul>
 */
async function startHangingAutomaticRound(page: Page, scenario: ScenarioState) {
  await expect
    .poll(() => scenario.listRequests.length, { timeout: 15_000 })
    .toBe(1);

  scenario.holdNextListRequest();

  const expectedAfter = scenario.listRequests.length + 1;
  await expect
    .poll(async () => {
      await page.evaluate(() => document.dispatchEvent(new Event('visibilitychange')));
      return scenario.listRequests.length;
    }, { timeout: 15_000, intervals: [250] })
    .toBe(expectedAfter);
}

/** 改筛选并回车：这是与自动轮询同级的**新意图** */
async function submitFilter(page: Page, keyword: string) {
  const input = page.locator('.filter-bar input').first();
  await input.fill(keyword);
  await input.press('Enter');
}

/**
 * 两个页面共用的一条时序。
 *
 * `failFresh` 为真时，新筛选的**补跑**如实打 500（由用例放行时直接给出失败响应）
 * ——用来证明「把被取代的轮次合并掉」并没有顺手把真实失败也吞掉。
 */
async function runIntentScenario(page: Page, config: PageConfig, failFresh: boolean) {
  await installWitnesses(page);
  const scenario = await setupScenario(page, config, {
    // 只决定首屏那一轮的响应：被挂起的 A 与补跑 B 都由用例在放行时给出响应，
    // 这样"谁写的表格"完全由时序决定，不依赖按查询条件反推。
    rowsFor: () => ({ records: [makeRow(config, 11, ROUND0)] }),
  });

  await page.goto(config.route);
  expect(await readErrorToasts(page), '首屏不应有任何错误提示').toEqual([]);

  // ---- 非手动轮次 A 挂起 ----
  await startHangingAutomaticRound(page, scenario);
  expect(scenario.listRequests.map((r) => r.keyword)).toEqual(['', '']);

  // ---- 用户改筛选并回车 ----
  await submitFilter(page, FRESH_KEYWORD);

  // A 还挂着，补跑发不出去。这一刻的关键是**不叠请求**、也**不弹假失败**
  await page.waitForTimeout(300);
  expect(scenario.listRequests.length, '排队期间不得再叠一个列表请求（单飞）').toBe(2);
  expect(await readErrorToasts(page), '「已排队」不得被当成读取失败').toEqual([]);

  // ---- A 迟到：它已经失去写入资格 ----
  //
  // 放行 A **之前**先把下一个列表请求也挂住：紧接着的补跑（B）于是停在原地，
  // A 的迟到响应就成了这一段时间里**最后一个可能写表格的东西**。
  // 不这么做的话，A 之后紧跟 B 的正常响应，B 会把表格整个覆盖掉——
  // 那时再看 DOM 就分不清"A 写没写"（两次写入还可能在同一批里合并渲染，
  // 连 MutationObserver 都未必看得到），断言也就成了"最终展示对不对"，
  // 而不是"旧轮次有没有写当前展示"。挂住 B 之后这个判断是确定的。
  scenario.holdNextListRequest();
  scenario.releaseHeldList({
    status: 200,
    body: pageResponse([makeRow(config, 11, LATE_A)]),
  });

  // ---- 先断言 A 未写当前展示（此刻 B 还挂着一动没动） ----
  await expect
    .poll(() => scenario.listRequests.length, { timeout: 10_000 })
    .toBe(3);
  // 补跑已发出 ⇒ A 的迟到响应已经被处理完，此后表格不会再变（B 被挂住）。
  await page.waitForTimeout(300);
  const rowTextWhileBHeld = await page.locator('.el-table__body').innerText();
  expect(
    rowTextWhileBHeld,
    'B 放行之前，表格里只能是上一轮的 ROUND0：A 的迟到响应不得动它',
  ).toContain(ROUND0);
  expect(
    rowTextWhileBHeld,
    'A 的迟到响应不得写进当前展示（这里才是判定点；等到 B 覆盖之后再看就分不清是谁写的）',
  ).not.toContain(LATE_A);
  expect(await page.locator('.el-table__body').getByText(LATE_A).count()).toBe(0);

  // ---- 再放行补跑 B（用的是最新查询条件） ----
  scenario.releaseHeldList(
    failFresh
      ? {
          status: 500,
          body: { code: 'FAILED', message: FAILURE_MESSAGE, data: null },
        }
      : { status: 200, body: pageResponse([makeRow(config, 22, FRESH_B)]) },
  );

  if (!failFresh) {
    await expect(page.locator('.el-table__body').getByText(FRESH_B)).toBeVisible();
  } else {
    // 被取代的手动调用等的是补跑的真实结论，所以「改完筛选后接口真挂了」照样提示
    await expect.poll(() => page.locator('.el-message--error').count(), { timeout: 10_000 })
      .toBeGreaterThan(0);
  }

  // 三次请求，不多不少：首屏、被挂起的 A、以及带新条件的补跑。
  // A 的迟到响应换来的是「补跑一次」，而不是「再叠一轮」。
  expect(
    scenario.listRequests,
    '补跑必须使用最新查询条件，且旧轮次迟到不得多带出一轮',
  ).toEqual([
    { keyword: '', pageNum: '1' },
    { keyword: '', pageNum: '1' },
    { keyword: FRESH_KEYWORD, pageNum: '1' },
  ]);

  if (!failFresh) {
    expect(
      await page.locator('.el-table__body').getByText(LATE_A).count(),
      '旧轮次 A 的晚到响应不得写进当前展示',
    ).toBe(0);
    expect(await readErrorToasts(page), '整条时序里不应出现任何错误提示').toEqual([]);
  } else {
    expect(await readErrorToasts(page)).toContain(FAILURE_MESSAGE);
    expect(await page.locator('.el-table__body').getByText(LATE_A).count()).toBe(0);
    // 读不到时旧值仍留在屏幕上，但必须显式标注为可能过期
    await expect(page.getByText(config.staleListText)).toBeVisible();
  }

  // 「旧轮次不写当前展示」是全程属性，不是最后一刻的快照。
  // 见证者与提示收集器共用同一个 observer：成功分支靠 fresh 自检它活着，
  // 失败分支靠上面那条"确实弹了错"，两者都不会让 late=false 变成一句空话。
  const witness = await readRowWitness(page);
  if (!failFresh) {
    expect(witness.fresh, '见证者自检：新条件的行应该出现过').toBe(true);
  }
  expect(witness.late, '旧轮次 A 的行不得在任何时刻出现在页面上').toBe(false);

  expect(
    scenario.unmockedApiRequests,
    '有业务接口没被显式拦截，说明这个用例的「全部拦截」并不成立',
  ).toEqual([]);
}

test.describe('F1 一轮同步的意图归属——主机列表页', () => {
  test.beforeEach(async ({ page }) => {
    await loginAsAdmin(page);
  });

  test('自动轮次挂起 + 用户改筛选：不假失败，旧轮次不写当前展示', async ({ page }) => {
    await runIntentScenario(page, HOSTS, false);
  });

  test('同样的时序下，补跑的真实失败照旧提示（故障响应控制）', async ({ page }) => {
    await runIntentScenario(page, HOSTS, true);
  });
});

test.describe('F1 一轮同步的意图归属——运行实例列表页', () => {
  test.beforeEach(async ({ page }) => {
    await loginAsAdmin(page);
  });

  test('自动轮次挂起 + 用户改筛选：不假失败，旧轮次不写当前展示', async ({ page }) => {
    await runIntentScenario(page, RUNTIME_INSTANCES, false);
  });

  test('翻页意图同样立即作废旧轮次（两页列表检查的是同一个意图版本）', async ({ page }) => {
    await installWitnesses(page);
    // 两页都报「共 25 行」：只给本页行数的话，分页条会因为总数变小而把页码夹回第 1 页，
    // 于是"翻页"根本没发生——那是夹具失真，不是被测行为。
    const scenario = await setupScenario(page, RUNTIME_INSTANCES, {
      rowsFor: (query) =>
        query.pageNum === '2'
          ? { records: [makeRow(RUNTIME_INSTANCES, 30, FRESH_B)], total: 25 }
          : { records: initialRows(RUNTIME_INSTANCES), total: 25 },
    });

    await page.goto(RUNTIME_INSTANCES.route);
    await expect(page.locator('.el-table__body').getByText(ROUND0)).toBeVisible();

    await startHangingAutomaticRound(page, scenario);

    // 点「下一页」：与改筛选同级的**新意图**，同样立刻作废在飞的轮次。
    // 这里直接派发 click 而不是用鼠标点：轮次 A 正在飞，表格的 loading 遮罩盖住了分页条，
    // 按坐标点下去会被遮罩吃掉（force 也救不了，force 只是跳过可点性检查，点击仍按坐标发生）。
    // 遮罩本身正是本用例要造的状态，不能为了"点得动"先把 A 结束掉。
    await page.locator('.el-pagination button.btn-next').dispatchEvent('click');

    await page.waitForTimeout(300);
    expect(scenario.listRequests.length, '翻页期间不得再叠一个列表请求').toBe(2);
    expect(await readErrorToasts(page), '翻页排队不得被当成读取失败').toEqual([]);

    scenario.releaseHeldList({
      status: 200,
      body: pageResponse([makeRow(RUNTIME_INSTANCES, 11, LATE_A)], 25),
    });

    // 补跑用的是最新页码，所以最终展示的是第 2 页那行
    await expect(page.locator('.el-table__body').getByText(FRESH_B)).toBeVisible();

    // 三次请求，不多不少：首屏、被挂起的 A、以及带新页码的补跑。
    // A 的迟到响应换来的是「补跑一次」，而不是「再叠一轮」。
    expect(
      scenario.listRequests,
      '补跑必须使用最新页码，且旧轮次迟到不得多带出一轮',
    ).toEqual([
      { keyword: '', pageNum: '1' },
      { keyword: '', pageNum: '1' },
      { keyword: '', pageNum: '2' },
    ]);
    expect(await page.locator('.el-table__body').getByText(LATE_A).count()).toBe(0);
    expect(await readErrorToasts(page)).toEqual([]);
    expect(scenario.unmockedApiRequests, '有业务接口没被显式拦截').toEqual([]);

    const witness = await readRowWitness(page);
    expect(witness.fresh, '见证者自检：第 2 页那行应该出现过').toBe(true);
    expect(witness.late, '旧轮次 A 的行不得在任何时刻出现在页面上').toBe(false);
  });
});