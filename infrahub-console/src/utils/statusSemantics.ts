/**
 * 状态语义：把控制台里出现过的状态字段收成一处，避免"同一个字符串在不同页面含义不同"。
 *
 * 三条硬规则：
 * 1. 只做展示映射，不改写原始值：调用方始终能拿到未加工的原始状态码（descriptor.code）
 *    并把它渲染出来，不做归一化、不做迁移、不把历史值折算成现行语义。
 * 2. UNKNOWN、"认不出来的取值"与历史遗留取值一律中性（tone='info'，unconfirmed=true）：既不当成功，
 *    也不当已确认的故障，只表示"尚未确认/未登记/现时含义待确认"。
 * 3. 状态只来自已记录的数据。运行实例列表的「运行状态」动作是即时探测、结果不回写数据库；
 *    后台自动采集则会把观测结果写回采集字段。两者都不是"此刻的实时结果"：自动采集有周期，
 *    新鲜度由 collectionFreshness 单独表达，不拿一个旧样本冒充实时。
 * 4. 采集相关的四个字段（来源/新鲜度/结果/失败原因）只描述"这一次采集发生了什么"，
 *    不参与、也不改写部署结果、当前版本、审批与回滚状态。
 */

export type StatusTone = 'success' | 'warning' | 'danger' | 'info';

export type StatusKind =
  | 'hostStatus'
  | 'agentStatus'
  | 'hostHealthStatus'
  | 'instanceStatus'
  | 'instanceHealthStatus'
  | 'deployStatus'
  | 'monitorStatus'
  | 'collectionSource'
  | 'collectionFreshness'
  | 'collectionResult'
  | 'collectionErrorCategory';

export interface StatusDescriptor {
  kind: StatusKind;
  /** 原始状态码，逐字保留（仅去掉首尾空白）；无记录时为空串 */
  code: string;
  /** 取值文案的 i18n key；无记录或认不出取值时为空串 */
  labelKey: string;
  tone: StatusTone;
  /** 是否为登记在册、可解释的取值（含历史遗留值） */
  known: boolean;
  /** 是否属于"尚未确认"：无记录、UNKNOWN、认不出的取值 */
  unconfirmed: boolean;
  /** 是否为历史遗留取值（不在现行枚举内，单独列出，不与现行语义合并） */
  legacy: boolean;
  /** 补充说明的 i18n key；没有补充时为空串 */
  noteKey: string;
}

/** 只用到 t(key, named) 这一种调用形态，避免把 vue-i18n 的完整重载签名泄漏进本模块 */
export type TranslateFn = (key: string, named?: Record<string, unknown>) => string;

interface ValueSpec {
  code: string;
  tone: StatusTone;
  noteKey?: string;
  legacy?: boolean;
}

const NOTE_HOST_RECORDED = 'statusSemantics.note.hostRecorded';
const NOTE_AGENT_RECORDED = 'statusSemantics.note.agentRecorded';
const NOTE_INSTANCE_RECORDED = 'statusSemantics.note.instanceRecorded';
const NOTE_HEALTH_RECORDED = 'statusSemantics.note.healthRecorded';
const NOTE_DEPLOY_RECORDED = 'statusSemantics.note.deployRecorded';
const NOTE_MONITOR_RECORDED = 'statusSemantics.note.monitorRecorded';
const NOTE_SOURCE_RECORDED = 'statusSemantics.note.collectionSourceRecorded';
const NOTE_FRESHNESS_RECORDED = 'statusSemantics.note.collectionFreshnessRecorded';
const NOTE_RESULT_RECORDED = 'statusSemantics.note.collectionResultRecorded';
const NOTE_ERROR_FAILURE = 'statusSemantics.note.collectionErrorFailure';

/**
 * 取值登记表：每一个取值都必须在这里写明 tone 与含义，认不出的取值不会被猜成成功或失败。
 * 关键判断（与后端一一对应，未经确认不写进代码）：
 *  - hostStatus / agentStatus 的唯一 Java 写入点是主机手动登记（写 UNKNOWN），
 *    既有 ONLINE/OFFLINE/MAINTENANCE 来自人工或既有数据；
 *  - 运行实例的 ONLINE 是历史遗留取值，现行枚举里没有它，也不等同于 RUNNING；
 *  - deployStatus 是"最近一次部署执行结果"，与"服务当前是否健康"不是一回事。
 */
const REGISTRY: Record<StatusKind, ValueSpec[]> = {
  hostStatus: [
    { code: 'ONLINE', tone: 'success', noteKey: NOTE_HOST_RECORDED },
    { code: 'OFFLINE', tone: 'danger', noteKey: NOTE_HOST_RECORDED },
    { code: 'MAINTENANCE', tone: 'warning', noteKey: NOTE_HOST_RECORDED },
    { code: 'UNKNOWN', tone: 'info', noteKey: 'statusSemantics.note.hostUnknown' },
  ],
  agentStatus: [
    { code: 'ONLINE', tone: 'success', noteKey: NOTE_AGENT_RECORDED },
    { code: 'OFFLINE', tone: 'danger', noteKey: NOTE_AGENT_RECORDED },
    { code: 'UNKNOWN', tone: 'info', noteKey: 'statusSemantics.note.agentUnknown' },
  ],
  hostHealthStatus: [
    { code: 'ONLINE', tone: 'success', noteKey: 'statusSemantics.note.hostHealthRecorded' },
    { code: 'OFFLINE', tone: 'danger', noteKey: 'statusSemantics.note.hostHealthRecorded' },
    { code: 'DEGRADED', tone: 'warning', noteKey: 'statusSemantics.note.hostHealthRecorded' },
    { code: 'UNKNOWN', tone: 'info', noteKey: 'statusSemantics.note.hostHealthUnknown' },
  ],
  instanceStatus: [
    { code: 'RUNNING', tone: 'success', noteKey: NOTE_INSTANCE_RECORDED },
    { code: 'STOPPED', tone: 'danger', noteKey: NOTE_INSTANCE_RECORDED },
    { code: 'STARTING', tone: 'warning', noteKey: NOTE_INSTANCE_RECORDED },
    { code: 'STOPPING', tone: 'warning', noteKey: NOTE_INSTANCE_RECORDED },
    { code: 'UNKNOWN', tone: 'info', noteKey: 'statusSemantics.note.instanceUnknown' },
    { code: 'ONLINE', tone: 'info', legacy: true, noteKey: 'statusSemantics.note.instanceOnlineLegacy' },
  ],
  instanceHealthStatus: [
    { code: 'UP', tone: 'success', noteKey: NOTE_HEALTH_RECORDED },
    { code: 'DOWN', tone: 'danger', noteKey: NOTE_HEALTH_RECORDED },
    { code: 'UNKNOWN', tone: 'info', noteKey: 'statusSemantics.note.healthUnknown' },
  ],
  deployStatus: [
    { code: 'SUCCESS', tone: 'success', noteKey: NOTE_DEPLOY_RECORDED },
    { code: 'FAILED', tone: 'danger', noteKey: NOTE_DEPLOY_RECORDED },
    { code: 'RUNNING', tone: 'warning', noteKey: NOTE_DEPLOY_RECORDED },
    { code: 'PENDING', tone: 'warning', noteKey: NOTE_DEPLOY_RECORDED },
    { code: 'UNKNOWN', tone: 'info', noteKey: 'statusSemantics.note.deployUnknown' },
  ],
  monitorStatus: [
    { code: 'UP', tone: 'success', noteKey: NOTE_MONITOR_RECORDED },
    { code: 'DOWN', tone: 'danger', noteKey: NOTE_MONITOR_RECORDED },
    { code: 'UNKNOWN', tone: 'info', noteKey: 'statusSemantics.note.monitorUnknown' },
  ],
  // ---------- 自动采集（主机与运行实例共用） ----------
  // 这四个字段回答的是"这一次采集发生了什么"，与"服务好不好"是两码事：
  // 采集失败(FAILED)不等于服务故障，采集成功(SUCCESS)也不等于服务健康。
  collectionSource: [
    { code: 'SSH_PROBE', tone: 'info', noteKey: NOTE_SOURCE_RECORDED },
    { code: 'AGENT_REPORT', tone: 'info', noteKey: 'statusSemantics.note.collectionSourceAgentReport' },
    { code: 'NONE', tone: 'info', noteKey: NOTE_SOURCE_RECORDED },
    { code: 'UNKNOWN', tone: 'info', noteKey: 'statusSemantics.note.collectionSourceUnknown' },
  ],
  collectionFreshness: [
    { code: 'FRESH', tone: 'success', noteKey: NOTE_FRESHNESS_RECORDED },
    { code: 'STALE', tone: 'warning', noteKey: 'statusSemantics.note.collectionFreshnessStale' },
    { code: 'NONE', tone: 'info', noteKey: 'statusSemantics.note.collectionFreshnessNone' },
    { code: 'UNKNOWN', tone: 'info', noteKey: 'statusSemantics.note.collectionFreshnessUnknown' },
  ],
  collectionResult: [
    { code: 'SUCCESS', tone: 'success', noteKey: NOTE_RESULT_RECORDED },
    { code: 'FAILED', tone: 'danger', noteKey: 'statusSemantics.note.collectionResultFailed' },
    {
      code: 'SKIPPED_UNCONFIGURED',
      tone: 'info',
      noteKey: 'statusSemantics.note.collectionResultSkippedUnconfigured',
    },
    {
      code: 'SKIPPED_UNSUPPORTED',
      tone: 'info',
      noteKey: 'statusSemantics.note.collectionResultSkippedUnsupported',
    },
    {
      // 准入后身份被改：本轮根本没拨号，也不是目标故障，所以与其余跳过同类，一律 info。
      code: 'SKIPPED_ADMISSION_CHANGED',
      tone: 'info',
      noteKey: 'statusSemantics.note.collectionResultSkippedAdmissionChanged',
    },
    { code: 'EXPIRED', tone: 'warning', noteKey: 'statusSemantics.note.collectionResultExpired' },
    { code: 'NONE', tone: 'info', noteKey: 'statusSemantics.note.collectionResultNone' },
    { code: 'UNKNOWN', tone: 'info', noteKey: 'statusSemantics.note.collectionResultUnknown' },
  ],
  // 失败原因分类。注意 PROCESS_ABSENT / HEALTH_ENDPOINT_ERROR 不是"采集失败"：
  // 它们是采集成功之后得到的结论，所以只给 warning，不混进 danger 的失败色里。
  // 同理 CONFIG_MISSING / DIAGNOSTIC_CONFIG_MISSING / UNSUPPORTED_TARGET 也不是失败：
  // 它们对应 SKIPPED_* 跳过，不推进连续失败计数、不改写目标状态，所以一律 info。
  collectionErrorCategory: [
    // 两类"未配置"必须分成两个码：处置办法不同（配 SSH 认证 vs 配 deploy.root/deploy.port），
    // 而页面能区分它们的唯一依据就是这个分类码。
    { code: 'CONFIG_MISSING', tone: 'info', noteKey: 'statusSemantics.note.collectionErrorConfigMissing' },
    {
      code: 'DIAGNOSTIC_CONFIG_MISSING',
      tone: 'info',
      noteKey: 'statusSemantics.note.collectionErrorDiagnosticConfigMissing',
    },
    {
      // 配置歧义：配置存在但有多条，运行期拒绝执行、采集跳过。与"缺配置"一样不是失败。
      code: 'DIAGNOSTIC_CONFIG_AMBIGUOUS',
      tone: 'info',
      noteKey: 'statusSemantics.note.collectionErrorDiagnosticConfigAmbiguous',
    },
    { code: 'UNSUPPORTED_TARGET', tone: 'info', noteKey: 'statusSemantics.note.collectionErrorUnsupportedTarget' },
    {
      // 准入已变更：同样不是失败，既不能说目标不可达，也不推进连续失败计数。
      // tone 用 info 而不是 danger/ warning —— 它描述的是"这次采集被取消了"，不是"目标出问题了"。
      code: 'ADMISSION_CHANGED',
      tone: 'info',
      noteKey: 'statusSemantics.note.collectionErrorAdmissionChanged',
    },
    { code: 'AUTH_FAILED', tone: 'danger', noteKey: NOTE_ERROR_FAILURE },
    { code: 'NETWORK_TIMEOUT', tone: 'warning', noteKey: NOTE_ERROR_FAILURE },
    { code: 'TARGET_UNREACHABLE', tone: 'danger', noteKey: NOTE_ERROR_FAILURE },
    { code: 'PROBE_ERROR', tone: 'danger', noteKey: NOTE_ERROR_FAILURE },
    {
      // 采集侧的失败：线程池被上一轮卡住的探测占满，本轮根本没调度这个目标。
      // 用 danger 与其它真失败同级 —— 它同样会推进连续失败计数，最终把目标状态复位成"无法确认"，
      // 对使用者是真实可见的后果；单独一条文案是为了让人一眼看出"不是目标的问题"。
      code: 'PROBE_SCHEDULING_REJECTED',
      tone: 'danger',
      noteKey: 'statusSemantics.note.collectionErrorProbeSchedulingRejected',
    },
    { code: 'PROCESS_ABSENT', tone: 'warning', noteKey: 'statusSemantics.note.collectionErrorProcessAbsent' },
    {
      code: 'HEALTH_ENDPOINT_ERROR',
      tone: 'warning',
      noteKey: 'statusSemantics.note.collectionErrorHealthEndpointError',
    },
    { code: 'UNKNOWN', tone: 'info', noteKey: 'statusSemantics.note.collectionErrorUnknown' },
  ],
};

/** 字段级说明：数据来源。是否实时不按字段分，统一由 legendNote 说明。 */
export const STATUS_KIND_META: Record<StatusKind, { nameKey: string; sourceKey: string }> = {
  hostStatus: {
    nameKey: 'statusSemantics.kind.hostStatus',
    sourceKey: 'statusSemantics.kind.hostStatusSource',
  },
  agentStatus: {
    nameKey: 'statusSemantics.kind.agentStatus',
    sourceKey: 'statusSemantics.kind.agentStatusSource',
  },
  hostHealthStatus: {
    nameKey: 'statusSemantics.kind.hostHealthStatus',
    sourceKey: 'statusSemantics.kind.hostHealthStatusSource',
  },
  instanceStatus: {
    nameKey: 'statusSemantics.kind.instanceStatus',
    sourceKey: 'statusSemantics.kind.instanceStatusSource',
  },
  instanceHealthStatus: {
    nameKey: 'statusSemantics.kind.instanceHealthStatus',
    sourceKey: 'statusSemantics.kind.instanceHealthStatusSource',
  },
  deployStatus: {
    nameKey: 'statusSemantics.kind.deployStatus',
    sourceKey: 'statusSemantics.kind.deployStatusSource',
  },
  monitorStatus: {
    nameKey: 'statusSemantics.kind.monitorStatus',
    sourceKey: 'statusSemantics.kind.monitorStatusSource',
  },
  collectionSource: {
    nameKey: 'statusSemantics.kind.collectionSource',
    sourceKey: 'statusSemantics.kind.collectionSourceSource',
  },
  collectionFreshness: {
    nameKey: 'statusSemantics.kind.collectionFreshness',
    sourceKey: 'statusSemantics.kind.collectionFreshnessSource',
  },
  collectionResult: {
    nameKey: 'statusSemantics.kind.collectionResult',
    sourceKey: 'statusSemantics.kind.collectionResultSource',
  },
  collectionErrorCategory: {
    nameKey: 'statusSemantics.kind.collectionErrorCategory',
    sourceKey: 'statusSemantics.kind.collectionErrorCategorySource',
  },
};

export const STATUS_KINDS: StatusKind[] = [
  'hostStatus',
  'agentStatus',
  'hostHealthStatus',
  'instanceStatus',
  'instanceHealthStatus',
  'deployStatus',
  'monitorStatus',
  'collectionSource',
  'collectionFreshness',
  'collectionResult',
  'collectionErrorCategory',
];

export const UNKNOWN_STATUS_CODE = 'UNKNOWN';

/** 是否真的拿到了一条状态记录（空串、null、纯空白都算没有记录） */
export function hasStatusValue(raw?: string | null): boolean {
  return raw != null && String(raw).trim() !== '';
}

function valueLabelKey(kind: StatusKind, code: string) {
  return `statusSemantics.value.${kind}.${code}`;
}

/**
 * 解析一条状态记录。
 *
 * 取值匹配是逐字匹配（区分大小写）：`online` 这类大小写不同的取值不会被静默当成 `ONLINE`，
 * 而是走"未登记取值"分支按原样展示——宁可显示得朴素一点，也不猜用户想看到什么。
 */
export function describeStatus(kind: StatusKind, raw?: string | null): StatusDescriptor {
  const code = raw == null ? '' : String(raw).trim();

  if (!code) {
    return {
      kind,
      code: '',
      labelKey: '',
      tone: 'info',
      known: false,
      unconfirmed: true,
      legacy: false,
      noteKey: '',
    };
  }

  const spec = REGISTRY[kind].find((item) => item.code === code);

  if (!spec) {
    return {
      kind,
      code,
      labelKey: '',
      tone: 'info',
      known: false,
      unconfirmed: true,
      legacy: false,
      noteKey: 'statusSemantics.note.unrecognized',
    };
  }

  return {
    kind,
    code,
    labelKey: valueLabelKey(kind, spec.code),
    tone: spec.tone,
    known: true,
    // UNKNOWN 表示尚未确认；历史遗留取值不在现行枚举内，其现时含义同样无法确认，
    // 因此一律按「未确认」展示（朴素样式），不让它看起来像一条已确认的当前状态。
    unconfirmed: spec.code === UNKNOWN_STATUS_CODE || spec.legacy === true,
    legacy: spec.legacy === true,
    noteKey: spec.noteKey ?? '',
  };
}

/** 展示文案：登记在册的取值走文案，认不出的取值原样显示，无记录时为空串 */
export function statusLabel(descriptor: StatusDescriptor, t: TranslateFn): string {
  if (!descriptor.code) return '';
  if (!descriptor.labelKey) return descriptor.code;
  const label = t(descriptor.labelKey);
  // 文案缺失时回落到原始码，避免页面上出现 key 本身
  return label === descriptor.labelKey ? descriptor.code : label;
}

/** 单元格 title：原始状态码 + 该取值的补充说明 + 字段数据来源 */
export function statusTitle(descriptor: StatusDescriptor, t: TranslateFn): string {
  if (!descriptor.code) return '';

  const parts: string[] = [];
  const rawCode = t('statusSemantics.rawCodeTitle', { code: descriptor.code });
  parts.push(descriptor.known ? rawCode : `${t('statusSemantics.unknownValue')} · ${rawCode}`);
  if (descriptor.noteKey) parts.push(t(descriptor.noteKey));
  parts.push(t(STATUS_KIND_META[descriptor.kind].sourceKey));
  return parts.join(' · ');
}

/** 状态说明弹窗用：列出某个字段的所有登记取值（供"这个字段都有哪些值"一览） */
export function listStatusValues(kind: StatusKind): StatusDescriptor[] {
  return REGISTRY[kind].map((spec) => describeStatus(kind, spec.code));
}

/**
 * 时间展示：有记录就按当前语言格式化，没有记录返回空串（由调用方渲染"未记录"）。
 *
 * 解析不了的字符串按原样返回，不猜成 0 时间、也不造一个"刚刚"。绝不伪造时间。
 */
export function formatRecordedTime(value?: string | null, locale = 'zh-CN'): string {
  if (value == null) return '';
  const text = String(value).trim();
  if (!text) return '';
  const date = new Date(text);
  if (Number.isNaN(date.getTime())) return text;
  return date.toLocaleString(locale);
}
