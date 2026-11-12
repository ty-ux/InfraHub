/**
 * 主机登记的前端即时校验规则。
 *
 * 这里的规则与后端 CreateAssetHostRequest 的注解、IpLiterals / AssetHostService 的入库前检查保持一致，
 * 目的只是让用户在提交前就看到反馈；权威校验始终在后端，前端放行不等于后端接受。
 *
 * IP 判定按同一份契约独立实现（RFC 4291 文本形式），不与后端共享代码，但两侧用同一组边界样本互证：
 * 单冒号只能出现在两组之间（首尾或连续的单冒号一律拒绝）、压缩只允许一次且至少代表一组 0、
 * 非压缩时必须正好 8 组、每组 1~4 位十六进制。
 */

/** 主机名：字母或数字开头，其后允许字母、数字、点、下划线、连字符 */
export const HOSTNAME_PATTERN = /^[A-Za-z0-9][A-Za-z0-9._-]*$/

/** 系统类型 / 主机类型：字母或数字开头，其后允许字母、数字、下划线、点、斜杠、加号、连字符 */
export const CODE_PATTERN = /^[A-Za-z0-9][A-Za-z0-9_./+-]*$/

export const HOSTNAME_MAX_LENGTH = 128
export const IP_MAX_LENGTH = 64
export const CODE_MAX_LENGTH = 32

/**
 * 规格上限，与库表列宽一致：
 * cpu_cores NUMERIC(8,2) → 999999.99；memory_gb / disk_gb NUMERIC(10,2) → 99999999.99。
 */
export const CPU_CORES_MAX = 999999.99
export const CAPACITY_GB_MAX = 99999999.99

/** 规格最多 2 位小数（与 @Digits(fraction = 2) 一致） */
export const SPEC_MAX_DECIMALS = 2

export function isIpLiteral(value: string): boolean {
  return isIpv4Literal(value) || isIpv6Literal(value)
}

function isIpv4Literal(value: string): boolean {
  const parts = value.split('.')
  if (parts.length !== 4) {
    return false
  }
  return parts.every((part) => {
    if (part.length === 0 || part.length > 3) {
      return false
    }
    if (part.length > 1 && part.startsWith('0')) {
      return false
    }
    if (!/^\d+$/.test(part)) {
      return false
    }
    return Number(part) <= 255
  })
}

function isIpv6Literal(value: string): boolean {
  if (value.length === 0 || !value.includes(':') || value.includes('%')) {
    return false
  }

  const compression = value.indexOf('::')
  const compressed = compression >= 0
  // "::" 最多出现一次
  if (compressed && value.indexOf('::', compression + 2) >= 0) {
    return false
  }

  const high = compressed ? value.slice(0, compression) : value
  const low = compressed ? value.slice(compression + 2) : ''

  const highGroups = countIpv6Groups(high)
  if (highGroups < 0) {
    return false
  }
  if (!compressed) {
    return highGroups === 8
  }
  const lowGroups = countIpv6Groups(low)
  if (lowGroups < 0) {
    return false
  }
  // "::" 至少代表一组 0，所以显式分组必须少于 8 组
  return highGroups + lowGroups < 8
}

/**
 * 统计以 ':' 分隔的十六进制分组数。
 *
 * @returns 分组数；出现空分组（首尾冒号、连续单冒号）或非法分组时返回 -1
 */
function countIpv6Groups(section: string): number {
  if (section.length === 0) {
    return 0
  }
  let count = 0
  for (const group of section.split(':')) {
    if (group.length === 0 || group.length > 4 || !/^[0-9A-Fa-f]+$/.test(group)) {
      return -1
    }
    count += 1
  }
  return count
}

/**
 * 规格值是否符合契约。
 *
 * 留空（undefined / null / 空串）表示"未填写"，放行并由提交逻辑省略该字段——绝不用 0 冒充已知规格；
 * 一旦填写就必须是明确的正数：大于 0、不超过 max、最多 2 位小数。
 * 显式 0、负数、越界、0.001 这类"四舍五入后为 0"的写法都会被判为不允许，不会静默变成"未填写"。
 */
export function isSpecValueAllowed(value: unknown, max: number): boolean {
  if (value === undefined || value === null || value === '') {
    return true
  }
  if (typeof value !== 'number' || !Number.isFinite(value)) {
    return false
  }
  if (value <= 0 || value > max) {
    return false
  }
  return hasAtMostTwoDecimals(value)
}

function hasAtMostTwoDecimals(value: number): boolean {
  const text = value.toString()
  if (/e/i.test(text)) {
    // 科学计数法说明有效小数位远超 2 位（如 1e-7）
    return false
  }
  const dot = text.indexOf('.')
  return dot < 0 || text.length - dot - 1 <= SPEC_MAX_DECIMALS
}