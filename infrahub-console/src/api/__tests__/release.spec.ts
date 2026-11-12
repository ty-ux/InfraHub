import { describe, expect, it } from 'vitest';
import {
  normalizeExecutorConfig,
  normalizeExecutorConfigs,
  type ReleaseExecutorConfig,
} from '../release';

function stored(configPayload: string | Record<string, unknown>): ReleaseExecutorConfig {
  return {
    id: 1,
    executorName: 'k8s-prod',
    executorType: 'KUBERNETES',
    envId: 1,
    configPayload,
    enabled: true,
  };
}

describe('normalizeExecutorConfig', () => {
  it('parses a stored JSON object', () => {
    const config = normalizeExecutorConfig(stored('{"namespace": "prod", "logTailLines": 100}'));

    expect(config.configPayload).toEqual({ namespace: 'prod', logTailLines: 100 });
    expect(config.configPayloadValid).toBe(true);
    expect(config.configPayloadRaw).toBeUndefined();
  });

  it('keeps text that is not valid JSON verbatim and marks it as needing correction', () => {
    const config = normalizeExecutorConfig(stored('kubeconfigPath: /x'));

    expect(config.configPayloadValid).toBe(false);
    expect(config.configPayloadRaw).toBe('kubeconfigPath: /x');
    expect(config.configPayload).toEqual({});
    // No synthetic marker replaces the stored text
    expect(config.configPayload._raw).toBeUndefined();
  });

  it('keeps JSON that is not an object as text instead of turning it into a marker object', () => {
    const config = normalizeExecutorConfig(stored('[1, 2, 3]'));

    expect(config.configPayloadValid).toBe(false);
    expect(config.configPayloadRaw).toBe('[1, 2, 3]');
    expect(config.configPayload).toEqual({});
  });

  it('accepts a payload that already is an object', () => {
    const config = normalizeExecutorConfig(stored({ namespace: 'prod' }));

    expect(config.configPayload).toEqual({ namespace: 'prod' });
    expect(config.configPayloadValid).toBe(true);
    expect(config.configPayloadRaw).toBeUndefined();
  });

  it('keeps the other fields of the configuration as the backend returned them', () => {
    const config = normalizeExecutorConfig(stored('{}'));

    expect(config.id).toBe(1);
    expect(config.executorName).toBe('k8s-prod');
    expect(config.executorType).toBe('KUBERNETES');
    expect(config.envId).toBe(1);
    expect(config.enabled).toBe(true);
  });

  it('normalizes a list', () => {
    const configs = normalizeExecutorConfigs([stored('{}'), stored('not json')]);

    expect(configs).toHaveLength(2);
    expect(configs[0].configPayloadValid).toBe(true);
    expect(configs[1].configPayloadValid).toBe(false);
    expect(configs[1].configPayloadRaw).toBe('not json');
  });
});