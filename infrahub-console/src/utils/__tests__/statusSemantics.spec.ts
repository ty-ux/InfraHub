import { describe, expect, it } from 'vitest';
import { i18n, setLocale } from '@/i18n';
import {
  STATUS_KINDS,
  describeStatus,
  formatRecordedTime,
  hasStatusValue,
  listStatusValues,
  statusLabel,
  statusTitle,
  type StatusKind,
  type TranslateFn,
  type StatusDescriptor,
} from '@/utils/statusSemantics';

/**
 * 状态语义单测。
 *
 * 这里锁死的是「展示层不得擅自改写状态」这条底线：UNKNOWN / 认不出的取值一律中性且保留原值，
 * 历史 ONLINE 既不等于 RUNNING 也不被折算成 RUNNING，没有任何时间记录时不造时间。
 * 用例不连后端，只对纯函数负责。
 */

const t = ((key: string, named?: Record<string, unknown>) =>
  i18n.global.t(key, named ?? {})) as TranslateFn;

function label(kind: StatusKind, code: string) {
  return statusLabel(describeStatus(kind, code), t);
}

describe('describeStatus：原值保留与语义判定', () => {
  it('UNKNOWN 是「尚未确认」：中性色、非故障、用到中性说明', () => {
    for (const kind of STATUS_KINDS) {
      const d = describeStatus(kind, 'UNKNOWN');
      expect(d.known, `${kind} 的 UNKNOWN 应登记在册`).toBe(true);
      expect(d.tone, `${kind} 的 UNKNOWN 不能是 danger`).toBe('info');
      expect(d.unconfirmed).toBe(true);
      expect(d.legacy).toBe(false);
      expect(d.code).toBe('UNKNOWN');
      expect(label(kind, 'UNKNOWN')).toBeTruthy();
    }
  });

  it('认不出的取值原样保留，且不映射成成功或失败', () => {
    const d = describeStatus('instanceStatus', 'SOMETHING_NEW');
    expect(d.code).toBe('SOMETHING_NEW');
    expect(d.known).toBe(false);
    expect(d.tone).toBe('info');
    expect(d.unconfirmed).toBe(true);
    expect(d.labelKey).toBe('');
    // 文案回落到原始码，而不是空字符串或某个"看起来对"的已知取值
    expect(label('instanceStatus', 'SOMETHING_NEW')).toBe('SOMETHING_NEW');
    expect(statusTitle(d, t)).toContain('SOMETHING_NEW');
  });

  it('历史 ONLINE：登记为遗留取值，语义与 RUNNING 分开', () => {
    const online = describeStatus('instanceStatus', 'ONLINE');
    const running = describeStatus('instanceStatus', 'RUNNING');

    expect(online.known).toBe(true);
    expect(online.legacy).toBe(true);
    expect(online.tone).toBe('info');
    expect(online.unconfirmed).toBe(true);
    // 历史值不能被当成 RUNNING 展示
    expect(online.labelKey).not.toBe(running.labelKey);
    expect(label('instanceStatus', 'ONLINE')).not.toBe(label('instanceStatus', 'RUNNING'));
    expect(running.legacy).toBe(false);
    expect(running.unconfirmed).toBe(false);
  });

  it('hostStatus / agentStatus：ONLINE、OFFLINE 是已确认取值', () => {
    const online = describeStatus('hostStatus', 'ONLINE');
    expect(online.known).toBe(true);
    expect(online.unconfirmed).toBe(false);
    expect(online.tone).toBe('success');

    const offline = describeStatus('agentStatus', 'OFFLINE');
    expect(offline.known).toBe(true);
    expect(offline.tone).toBe('danger');
  });

  it('deployStatus 只是最近一次部署执行结果，失败不代表服务当前不健康', () => {
    const failed = describeStatus('deployStatus', 'FAILED');
    expect(failed.known).toBe(true);
    expect(failed.tone).toBe('danger');
    // 与健康检查是两个字段，取值集合不共享
    expect(STATUS_KINDS).toContain('instanceHealthStatus');
    expect(listStatusValues('instanceHealthStatus').map(v => v.code)).not.toContain('FAILED');
  });

  it('空值与空白：没有这条记录，不猜成任何取值', () => {
    for (const raw of [undefined, null, '', '   ']) {
      const d = describeStatus('hostStatus', raw);
      expect(d.code).toBe('');
      expect(d.known).toBe(false);
      expect(d.unconfirmed).toBe(true);
      expect(d.labelKey).toBe('');
      expect(label('hostStatus', raw as string)).toBe('');
    }
    expect(hasStatusValue('UNKNOWN')).toBe(true);
    expect(hasStatusValue('  ')).toBe(false);
    expect(hasStatusValue(null)).toBe(false);
  });

  it('原始状态码始终可读：title 里带原始码与数据来源', () => {
    const unknown = describeStatus('agentStatus', 'UNKNOWN');
    const title = statusTitle(unknown, t);
    expect(title).toContain('UNKNOWN');
    expect(title).toContain(t('statusSemantics.kind.agentStatusSource'));

    const raw = statusTitle(describeStatus('hostStatus', 'WEIRD_VALUE'), t);
    expect(raw).toContain('WEIRD_VALUE');
    expect(raw).toContain(t('statusSemantics.unknownValue'));
    expect(statusTitle(describeStatus('hostStatus', ''), t)).toBe('');
  });
});

describe('取值清单与文案', () => {
  it('每个字段的取值清单都能给出文案，且与登记表一致', () => {
    for (const kind of STATUS_KINDS) {
      const values = listStatusValues(kind);
      expect(values.length, `${kind} 应有登记的取值`).toBeGreaterThan(0);
      for (const value of values) {
        expect(value.code).toBeTruthy();
        expect(label(kind, value.code)).toBeTruthy();
      }
    }
  });

  it('中英文下取值文案都会随语言切换，且不是 key 本身', () => {
    const descriptors = listStatusValues('instanceStatus');

    setLocale('zh-CN');
    const zh = descriptors.map(d => statusLabel(d, t));
    setLocale('en-US');
    const en = descriptors.map(d => statusLabel(d, t));
    setLocale('zh-CN');

    for (const text of zh) {
      expect(text).toBeTruthy();
      expect(text).not.toContain('statusSemantics.');
    }
    for (const text of en) {
      expect(text).toBeTruthy();
      expect(text).not.toContain('statusSemantics.');
    }
    expect(zh).not.toEqual(en);
  });

  it('所有用到的 i18n key 都存在（不出现回落成 key 的情况）', () => {
    setLocale('zh-CN');
    const keys = new Set<string>();
    for (const kind of STATUS_KINDS) {
      for (const value of listStatusValues(kind)) {
        keys.add(value.labelKey);
        keys.add(value.noteKey);
      }
      const descriptor = describeStatus(kind, 'UNKNOWN');
      keys.add(descriptor.labelKey);
      keys.add(descriptor.noteKey);
    }
    for (const key of keys) {
      expect(key, '登记表里不应出现空 key').toBeTruthy();
      expect(t(key), `缺少文案：${key}`).not.toBe(key);
    }
    // 弹窗标题里的原始码占位符也要能解析出来
    expect(statusTitle(describeStatus('hostStatus', 'X'), t)).not.toContain('statusSemantics.');
  });

  it('两类「未配置」分别登记：缺 SSH 连接配置 / 缺部署诊断参数，都不是失败，且 zh/en 都说得清补什么', () => {
    const sshMissing = describeStatus('collectionErrorCategory', 'CONFIG_MISSING');
    const diagnosticsMissing = describeStatus('collectionErrorCategory', 'DIAGNOSTIC_CONFIG_MISSING');

    expect(sshMissing.known).toBe(true);
    expect(diagnosticsMissing.known).toBe(true);
    // 两者都不是失败：不许用失败色把它们与「认证失败」混成一类
    expect(sshMissing.tone).toBe('info');
    expect(diagnosticsMissing.tone).toBe('info');
    expect(describeStatus('collectionErrorCategory', 'AUTH_FAILED').tone).toBe('danger');
    expect(diagnosticsMissing.noteKey).not.toBe(sshMissing.noteKey);

    // 两类共用「未配置（跳过）」这一个采集结果，但结果本身不是失败色
    const skipped = describeStatus('collectionResult', 'SKIPPED_UNCONFIGURED');
    expect(skipped.tone).toBe('info');

    for (const locale of ['zh-CN', 'en-US'] as const) {
      setLocale(locale);
      expect(label('collectionErrorCategory', 'DIAGNOSTIC_CONFIG_MISSING')).not.toBe(
        label('collectionErrorCategory', 'CONFIG_MISSING'),
      );
      for (const key of [
        sshMissing.labelKey,
        sshMissing.noteKey,
        diagnosticsMissing.labelKey,
        diagnosticsMissing.noteKey,
        skipped.noteKey,
      ]) {
        expect(t(key), `${locale} 缺少文案：${key}`).not.toBe(key);
      }
      // 采集结果这一列说的是"我这次为什么没采"，它不能替使用者断定"主机没有连接配置"：
      // 缺诊断参数时主机连接是好的。这里把那个误导性说法钉死。
      const resultLabel = label('collectionResult', 'SKIPPED_UNCONFIGURED');
      expect(resultLabel.toLowerCase()).not.toContain('connection');
      expect(resultLabel).not.toContain('连接配置');
      // 说明必须把两种缺失都讲出来，否则使用者只知道"未配置"却不知该补哪一处
      expect(t(skipped.noteKey)).toMatch(/SSH/);
      expect(t(skipped.noteKey)).toMatch(/部署诊断参数|deploy diagnostics/);
    }
    setLocale('zh-CN');
  });
});

describe('formatRecordedTime：有记录才显示时间', () => {
  const descriptor: StatusDescriptor = describeStatus('hostStatus', 'ONLINE');

  it('没有记录时返回空串，由调用方渲染「未记录」', () => {
    expect(formatRecordedTime(undefined)).toBe('');
    expect(formatRecordedTime(null)).toBe('');
    expect(formatRecordedTime('')).toBe('');
    expect(formatRecordedTime('   ')).toBe('');
    expect(descriptor.code).toBe('ONLINE');
  });

  it('有记录时按当前语言格式化，不伪造时间', () => {
    const zh = formatRecordedTime('2026-01-02T03:04:05', 'zh-CN');
    expect(zh).not.toBe('');
    expect(zh).toContain('2026');
    const en = formatRecordedTime('2026-01-02T03:04:05', 'en-US');
    expect(en).toContain('2026');
  });

  it('解析不了的字符串原样返回，不猜成 0 时间', () => {
    expect(formatRecordedTime('not-a-time')).toBe('not-a-time');
  });
});
