import { describe, expect, it } from 'vitest';
import {
  CAPACITY_GB_MAX,
  CODE_PATTERN,
  CPU_CORES_MAX,
  HOSTNAME_PATTERN,
  isIpLiteral,
  isSpecValueAllowed,
} from '@/utils/hostValidation';

/**
 * 主机登记的前端校验单测。
 *
 * 边界样本与后端 AssetHostServiceTest 用的是同一组：两边对"什么是合法地址/合法主机名/合法规格"
 * 必须给出相同答案，否则会出现"前端放过、后端拒绝"或更糟的相反情况。
 */

describe('isIpLiteral：与后端同一套字面量判定', () => {
  const VALID = [
    '192.0.2.3',
    '0.0.0.0',
    '255.255.255.255',
    '2001:db8::1',
    '::1',
    'fe80::1',
    '1:2:3:4:5:6:7:8',
    // 压缩写法：全压缩、前导压缩、尾随压缩、压缩后不足八组
    '::',
    '1::',
    'abcd::1234:5678',
    '1:2:3:4:5:6:7::',
  ];

  const INVALID = [
    '999.1.1.1',
    '10.10.1',
    '10.10.1.40.5',
    '010.1.1.1',
    'host.example.com',
    '::1::2',
    '1:2:3:4:5:6:7:8:9',
    'fe80::1%eth0',
    '',
    ' 192.0.2.3 ',
    '12345::1',
    // 本次修正的四类漏洞样本：单冒号出现在首尾、以及"压缩语法"外衣下的非法写法
    ':1:2:3:4:5:6:7:8',
    '1:2:3:4:5:6:7:8:',
    ':1::2',
    '1::2:',
    // 压缩后仍凑满八组、超过八组、空分组
    '1:2:3:4:5:6:7:8::',
    '1:2:3:4:5:6:7',
    ':::',
    ':',
    '2001:db8::zz',
  ];

  it.each(VALID)('接受合法地址：%s', (value) => {
    expect(isIpLiteral(value)).toBe(true);
  });

  it.each(INVALID)('拒绝非法地址：%s', (value) => {
    expect(isIpLiteral(value)).toBe(false);
  });

  it('带 zone id 的 IPv6 明确不支持（与后端一致，不把语义含糊的值写进台账）', () => {
    expect(isIpLiteral('fe80::1%eth0')).toBe(false);
  });

  it('单冒号只能出现在两组之间：首尾单冒号一律拒绝，不会因为"看起来像压缩"而放行', () => {
    for (const value of [':1:2:3:4:5:6:7:8', '1:2:3:4:5:6:7:8:', ':1::2', '1::2:']) {
      expect(isIpLiteral(value), value).toBe(false);
    }
  });
});

describe('主机名与类型码格式', () => {
  it('主机名允许字母数字开头的点/下划线/连字符组合', () => {
    for (const value of ['web-01', 'host.example.com', 'A1', 'db_01.internal']) {
      expect(HOSTNAME_PATTERN.test(value), value).toBe(true);
    }
  });

  it('主机名不允许以符号开头、不允许空格与中文', () => {
    for (const value of ['-web-01', '.host', 'web 01', '主机一', 'web/01']) {
      expect(HOSTNAME_PATTERN.test(value), value).toBe(false);
    }
  });

  it('系统类型/主机类型码允许斜杠与加号（如 linux/amd64）', () => {
    for (const value of ['LINUX', 'CENTOS7', 'linux/amd64', 'k8s+containerd']) {
      expect(CODE_PATTERN.test(value), value).toBe(true);
    }
    for (const value of [' LINUX', 'linux amd64', '系统']) {
      expect(CODE_PATTERN.test(value), value).toBe(false);
    }
  });
});

describe('isSpecValueAllowed：规格范围与小数位契约', () => {
  it('留空表示未填写，放行（提交时会省略该字段，而不是当成 0）', () => {
    for (const value of [undefined, null, '']) {
      expect(isSpecValueAllowed(value, CPU_CORES_MAX), String(value)).toBe(true);
    }
  });

  it('显式 0 被拒绝，不会被静默改写成"未填写"', () => {
    expect(isSpecValueAllowed(0, CPU_CORES_MAX)).toBe(false);
    expect(isSpecValueAllowed(0, CAPACITY_GB_MAX)).toBe(false);
  });

  it('CPU 上限 999999.99：等于上限通过，1000000 被拒绝', () => {
    expect(isSpecValueAllowed(999999.99, CPU_CORES_MAX)).toBe(true);
    expect(isSpecValueAllowed(1000000, CPU_CORES_MAX)).toBe(false);
  });

  it('内存/磁盘上限 99999999.99：等于上限通过，越界被拒绝', () => {
    expect(isSpecValueAllowed(99999999.99, CAPACITY_GB_MAX)).toBe(true);
    expect(isSpecValueAllowed(100000000, CAPACITY_GB_MAX)).toBe(false);
  });

  it('超过 2 位小数被拒绝，包括四舍五入后会变成 0 的正数（0.001）', () => {
    expect(isSpecValueAllowed(0.001, CPU_CORES_MAX)).toBe(false);
    expect(isSpecValueAllowed(1.234, CAPACITY_GB_MAX)).toBe(false);
  });

  it('两位小数与最小可表示正数通过（0.01 是合法规格，不是"未填写"）', () => {
    expect(isSpecValueAllowed(0.01, CPU_CORES_MAX)).toBe(true);
    expect(isSpecValueAllowed(0.5, CAPACITY_GB_MAX)).toBe(true);
  });

  it('非数字、负数、非有限值一律拒绝', () => {
    for (const value of ['4', -1, NaN, Infinity, {}, []]) {
      expect(isSpecValueAllowed(value, CPU_CORES_MAX), String(value)).toBe(false);
    }
  });
});
