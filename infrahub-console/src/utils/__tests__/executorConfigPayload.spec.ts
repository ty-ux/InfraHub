import { describe, expect, it } from 'vitest';
import { parse as parseYaml } from 'yaml';
import {
  emptyFormValues,
  extractKubernetesForm,
  isJsonObject,
  isRecognizableAbsolutePath,
  mergeFormIntoPayload,
  parsePayloadText,
  serializePayloadObject,
  validateKubernetesForm,
  validateKubernetesPayload,
  validateKubernetesStoredForm,
  type KubernetesFormValues,
} from '../executorConfigPayload';

function form(overrides: Partial<KubernetesFormValues> = {}): KubernetesFormValues {
  return { ...emptyFormValues(), ...overrides };
}

/** A payload the form can represent in full. */
function validPayload(): Record<string, unknown> {
  return {
    kubeconfigPath: 'C:/k8s/kubeconfig',
    kubeContext: 'bound-context',
    namespace: 'prod',
    manifestPath: 'C:/manifests/app.yaml',
    workloadName: 'web',
    rolloutTimeoutSeconds: 300,
    logTailLines: 100,
  };
}

describe('parsePayloadText', () => {
  it('reads empty text as an empty object', () => {
    expect(parsePayloadText('')).toEqual({ ok: true, value: {}, empty: true });
    expect(parsePayloadText('   ')).toEqual({ ok: true, value: {}, empty: true });
  });

  it('refuses text that is not JSON without rewriting it', () => {
    expect(parsePayloadText('kubeconfigPath: /x')).toEqual({ ok: false, code: 'INVALID_JSON' });
    expect(parsePayloadText('{"namespace": "prod"} trailing')).toEqual({ ok: false, code: 'INVALID_JSON' });
  });

  it('keeps JSON that is not an object readable, so the caller can decide', () => {
    expect(parsePayloadText('[1, 2, 3]')).toEqual({ ok: true, value: [1, 2, 3], empty: false });
    expect(parsePayloadText('123')).toEqual({ ok: true, value: 123, empty: false });
    expect(parsePayloadText('{"namespace": "prod"}')).toEqual({
      ok: true,
      value: { namespace: 'prod' },
      empty: false,
    });
  });
});

describe('isJsonObject', () => {
  it('accepts objects only', () => {
    expect(isJsonObject({})).toBe(true);
    expect(isJsonObject({ a: 1 })).toBe(true);
    expect(isJsonObject([])).toBe(false);
    expect(isJsonObject(null)).toBe(false);
    expect(isJsonObject('text')).toBe(false);
    expect(isJsonObject(1)).toBe(false);
    expect(isJsonObject(true)).toBe(false);
  });
});

describe('isRecognizableAbsolutePath', () => {
  it('accepts absolute path forms and refuses relative ones', () => {
    expect(isRecognizableAbsolutePath('/data/k8s/config')).toBe(true);
    expect(isRecognizableAbsolutePath('C:/k8s/config')).toBe(true);
    expect(isRecognizableAbsolutePath('C:\\k8s\\config')).toBe(true);
    expect(isRecognizableAbsolutePath('\\\\host\\share\\config')).toBe(true);
    expect(isRecognizableAbsolutePath('configs/kubeconfig')).toBe(false);
    expect(isRecognizableAbsolutePath('./kubeconfig')).toBe(false);
    expect(isRecognizableAbsolutePath('\\kubeconfig')).toBe(false);
    expect(isRecognizableAbsolutePath('')).toBe(false);
  });
});

describe('extractKubernetesForm', () => {
  it('reads every representable field', () => {
    const { values, unrepresentable } = extractKubernetesForm(validPayload());

    expect(unrepresentable).toEqual([]);
    expect(values).toEqual({
      kubeconfigPath: 'C:/k8s/kubeconfig',
      kubeContext: 'bound-context',
      namespace: 'prod',
      manifestPath: 'C:/manifests/app.yaml',
      manifestContent: '',
      workloadName: 'web',
      rolloutTimeoutSeconds: '300',
      logTailLines: '100',
    });
  });

  it('treats null and missing values as unset', () => {
    const { values, unrepresentable } = extractKubernetesForm({ namespace: 'prod', manifestPath: null });

    expect(unrepresentable).toEqual([]);
    expect(values.kubeconfigPath).toBe('');
    expect(values.manifestPath).toBe('');
  });

  it('reports values the form cannot show instead of coercing them', () => {
    const { values, unrepresentable } = extractKubernetesForm({
      namespace: 123,
      kubeconfigPath: true,
      kubeContext: ['c'],
      workloadName: { a: 1 },
      rolloutTimeoutSeconds: '300',
      logTailLines: 1.5,
    });

    expect(unrepresentable).toEqual([
      'kubeconfigPath',
      'kubeContext',
      'namespace',
      'workloadName',
      'rolloutTimeoutSeconds',
      'logTailLines',
    ]);
    expect(values.namespace).toBe('');
    expect(values.rolloutTimeoutSeconds).toBe('');
  });
});

describe('mergeFormIntoPayload', () => {
  it('writes the form values and keeps unknown fields untouched', () => {
    const payload = { ...validPayload(), futureField: { keep: [1, 2] }, another: null };

    const merged = mergeFormIntoPayload(payload, form({
      kubeconfigPath: 'C:/k8s/kubeconfig',
      kubeContext: 'bound-context',
      namespace: 'prod-2',
      manifestPath: 'C:/manifests/app.yaml',
      workloadName: 'web-v2',
      rolloutTimeoutSeconds: '600',
      logTailLines: '50',
    }));

    expect(merged).toEqual({
      kubeconfigPath: 'C:/k8s/kubeconfig',
      kubeContext: 'bound-context',
      namespace: 'prod-2',
      manifestPath: 'C:/manifests/app.yaml',
      workloadName: 'web-v2',
      rolloutTimeoutSeconds: 600,
      logTailLines: 50,
      futureField: { keep: [1, 2] },
      another: null,
    });
  });

  it('removes fields the form was left empty on and trims what it writes', () => {
    const merged = mergeFormIntoPayload(validPayload(), form({
      kubeconfigPath: 'C:/k8s/kubeconfig',
      kubeContext: '  bound-context  ',
      namespace: 'prod',
      manifestContent: 'apiVersion: v1',
    }));

    expect(merged.manifestPath).toBeUndefined();
    expect(merged.workloadName).toBeUndefined();
    expect(merged.rolloutTimeoutSeconds).toBeUndefined();
    expect(merged.logTailLines).toBeUndefined();
    expect(merged.kubeContext).toBe('bound-context');
    expect(merged.manifestContent).toBe('apiVersion: v1');
  });

  it('round-trips a representable payload unchanged', () => {
    const payload = validPayload();
    const { values } = extractKubernetesForm(payload);

    expect(mergeFormIntoPayload(payload, values)).toEqual(payload);
    expect(parsePayloadText(serializePayloadObject(payload))).toEqual({
      ok: true,
      value: payload,
      empty: false,
    });
  });

  it('keeps manifestContent exactly as it is, including leading and trailing whitespace', () => {
    const contents = [
      '  apiVersion: v1\nkind: Deployment  ',
      'apiVersion: v1\nkind: Deployment\n',
      '\napiVersion: v1\n\n\n',
      '\tapiVersion: v1\t',
    ];

    for (const manifestContent of contents) {
      const payload = { ...validPayload(), manifestContent };
      const { values } = extractKubernetesForm(payload);

      expect(values.manifestContent).toBe(manifestContent);
      expect(mergeFormIntoPayload(payload, values).manifestContent).toBe(manifestContent);
    }
  });

  it('keeps CRLF line endings in manifestContent instead of rewriting them', () => {
    const manifestContent = 'apiVersion: v1\r\nkind: Deployment\r\n';
    const payload = { ...validPayload(), manifestContent };
    const { values } = extractKubernetesForm(payload);

    expect(mergeFormIntoPayload(payload, values).manifestContent).toBe(manifestContent);
  });

  it('keeps the value a YAML |+ block scalar parses to across an edit round trip', () => {
    const manifestContent = 'apiVersion: v1\nkind: Deployment\nitems: |+\n  line1\n\n\n';
    const payload = { ...validPayload(), manifestContent };
    const { values } = extractKubernetesForm(payload);
    const roundTripped = mergeFormIntoPayload(payload, values).manifestContent as string;

    expect(roundTripped).toBe(manifestContent);
    expect(parseYaml(roundTripped)).toEqual(parseYaml(manifestContent));
    expect((parseYaml(roundTripped) as { items: string }).items).toBe('line1\n\n\n');
  });

  it('drops a manifest that is only whitespace and still trims the other text fields', () => {
    const merged = mergeFormIntoPayload(validPayload(), form({
      kubeconfigPath: '  /data/kubeconfig  ',
      kubeContext: 'bound-context',
      namespace: 'prod',
      manifestPath: 'C:/manifests/app.yaml',
      manifestContent: ' \n\t ',
    }));

    expect(merged.manifestContent).toBeUndefined();
    expect(merged.kubeconfigPath).toBe('/data/kubeconfig');
  });
});

describe('mergeFormIntoPayload integers', () => {
  it('never turns text that did not pass the range rule into null', () => {
    const payload = { ...validPayload(), rolloutTimeoutSeconds: 300 };

    for (const text of ['9'.repeat(400), '2147483648', '0', '-1', '1.5', 'abc']) {
      const merged = mergeFormIntoPayload(payload, form({
        kubeconfigPath: 'C:/k8s/kubeconfig',
        kubeContext: 'bound-context',
        namespace: 'prod',
        manifestPath: 'C:/manifests/app.yaml',
        rolloutTimeoutSeconds: text,
      }));

      expect(merged.rolloutTimeoutSeconds).toBe(300);
      expect(serializePayloadObject(merged)).not.toContain('null');
    }
  });

  it('writes an accepted integer as the number the backend reads', () => {
    const merged = mergeFormIntoPayload(validPayload(), form({
      kubeconfigPath: 'C:/k8s/kubeconfig',
      kubeContext: 'bound-context',
      namespace: 'prod',
      manifestPath: 'C:/manifests/app.yaml',
      workloadName: 'web',
      rolloutTimeoutSeconds: '2147483647',
      logTailLines: '1',
    }));

    expect(merged.rolloutTimeoutSeconds).toBe(2147483647);
    expect(merged.logTailLines).toBe(1);
    expect(serializePayloadObject(merged)).toContain('"rolloutTimeoutSeconds": 2147483647');
  });
});

describe('validateKubernetesForm', () => {
  it('accepts a complete configuration', () => {
    expect(validateKubernetesForm(form({
      kubeconfigPath: '/data/kubeconfig',
      kubeContext: 'bound-context',
      namespace: 'prod',
      manifestContent: 'apiVersion: v1',
    }))).toEqual([]);
  });

  it('names every required field that is missing', () => {
    expect(validateKubernetesForm(form())).toEqual([
      { field: 'kubeconfigPath', code: 'REQUIRED' },
      { field: 'kubeContext', code: 'REQUIRED' },
      { field: 'namespace', code: 'REQUIRED' },
      { field: 'manifestPath', code: 'MANIFEST_REQUIRED' },
    ]);
  });

  it('refuses relative paths', () => {
    expect(validateKubernetesForm(form({
      kubeconfigPath: 'configs/kubeconfig',
      kubeContext: 'c',
      namespace: 'prod',
      manifestPath: './app.yaml',
    }))).toEqual([
      { field: 'kubeconfigPath', code: 'NOT_ABSOLUTE_PATH' },
      { field: 'manifestPath', code: 'NOT_ABSOLUTE_PATH' },
    ]);
  });

  it('refuses optional numbers that are not positive integers', () => {
    expect(validateKubernetesForm(form({
      kubeconfigPath: '/data/kubeconfig',
      kubeContext: 'c',
      namespace: 'prod',
      manifestContent: 'apiVersion: v1',
      rolloutTimeoutSeconds: '0',
      logTailLines: '-1',
    }))).toEqual([
      { field: 'rolloutTimeoutSeconds', code: 'NOT_POSITIVE_INTEGER' },
      { field: 'logTailLines', code: 'NOT_POSITIVE_INTEGER' },
    ]);
  });

  it('accepts the smallest and the largest value a backend int holds', () => {
    expect(validateKubernetesForm(form({
      kubeconfigPath: '/data/kubeconfig',
      kubeContext: 'c',
      namespace: 'prod',
      manifestContent: 'apiVersion: v1',
      rolloutTimeoutSeconds: '1',
      logTailLines: '2147483647',
    }))).toEqual([]);
  });

  it('refuses the largest value plus one and digits past what a number can hold', () => {
    const base = {
      kubeconfigPath: '/data/kubeconfig',
      kubeContext: 'c',
      namespace: 'prod',
      manifestContent: 'apiVersion: v1',
    };

    expect(validateKubernetesForm(form({ ...base, rolloutTimeoutSeconds: '2147483648' }))).toEqual([
      { field: 'rolloutTimeoutSeconds', code: 'NOT_POSITIVE_INTEGER' },
    ]);
    expect(validateKubernetesForm(form({ ...base, logTailLines: '99999999999999999999' }))).toEqual([
      { field: 'logTailLines', code: 'NOT_POSITIVE_INTEGER' },
    ]);
    expect(validateKubernetesForm(form({ ...base, logTailLines: '9'.repeat(400) }))).toEqual([
      { field: 'logTailLines', code: 'NOT_POSITIVE_INTEGER' },
    ]);
  });

  it('refuses decimals and still allows the fields to be left empty', () => {
    const base = {
      kubeconfigPath: '/data/kubeconfig',
      kubeContext: 'c',
      namespace: 'prod',
      manifestContent: 'apiVersion: v1',
    };

    expect(validateKubernetesForm(form({ ...base, logTailLines: '1.5' }))).toEqual([
      { field: 'logTailLines', code: 'NOT_POSITIVE_INTEGER' },
    ]);
    expect(validateKubernetesForm(form({ ...base, rolloutTimeoutSeconds: '   ' }))).toEqual([]);
  });

  it('accepts either manifest source, and both together', () => {
    const base = { kubeconfigPath: '/data/kubeconfig', kubeContext: 'c', namespace: 'prod' };

    expect(validateKubernetesForm(form({ ...base, manifestPath: '/m/app.yaml' }))).toEqual([]);
    expect(validateKubernetesForm(form({ ...base, manifestContent: 'apiVersion: v1' }))).toEqual([]);
    expect(validateKubernetesForm(form({
      ...base,
      manifestPath: '/m/app.yaml',
      manifestContent: 'apiVersion: v1',
    }))).toEqual([]);
  });
});

describe('validateKubernetesPayload', () => {
  it('accepts a valid object', () => {
    expect(validateKubernetesPayload(validPayload())).toEqual([]);
  });

  it('holds the JSON editor to the same rules as the form', () => {
    expect(validateKubernetesPayload({ namespace: 'prod' })).toEqual([
      { field: 'kubeconfigPath', code: 'REQUIRED' },
      { field: 'kubeContext', code: 'REQUIRED' },
      { field: 'manifestPath', code: 'MANIFEST_REQUIRED' },
    ]);

    expect(validateKubernetesPayload({
      kubeconfigPath: '/data/kubeconfig',
      kubeContext: 'c',
      namespace: 'prod',
      manifestContent: 'apiVersion: v1',
      rolloutTimeoutSeconds: 0,
    })).toEqual([{ field: 'rolloutTimeoutSeconds', code: 'NOT_POSITIVE_INTEGER' }]);
  });

  it('holds the JSON editor to the backend int range as well', () => {
    expect(validateKubernetesPayload({ ...validPayload(), rolloutTimeoutSeconds: 2147483648 })).toEqual([
      { field: 'rolloutTimeoutSeconds', code: 'NOT_POSITIVE_INTEGER' },
    ]);
    expect(validateKubernetesPayload({ ...validPayload(), logTailLines: 2147483647 })).toEqual([]);
    expect(validateKubernetesPayload({ ...validPayload(), logTailLines: 1e21 })).toEqual([
      { field: 'logTailLines', code: 'NOT_POSITIVE_INTEGER' },
    ]);
  });

  it('reports wrong types as unrepresentable rather than reading them as text', () => {
    expect(validateKubernetesPayload({
      kubeconfigPath: '/data/kubeconfig',
      kubeContext: 'c',
      namespace: 123,
      manifestContent: 'apiVersion: v1',
    })).toEqual([{ field: 'namespace', code: 'NOT_REPRESENTABLE' }]);
  });

  it('leaves unknown fields alone', () => {
    expect(validateKubernetesPayload({ ...validPayload(), futureField: { a: [1] } })).toEqual([]);
  });
});

describe('validateKubernetesStoredForm', () => {
  it('reports a value the form cannot show once, not also as a missing field', () => {
    expect(validateKubernetesStoredForm(['namespace'], form({
      kubeconfigPath: '/data/kubeconfig',
      kubeContext: 'c',
      manifestContent: 'apiVersion: v1',
    }))).toEqual([{ field: 'namespace', code: 'NOT_REPRESENTABLE' }]);
  });

  it('still reports the fields around an unrepresentable value', () => {
    expect(validateKubernetesStoredForm(['namespace'], form({
      kubeconfigPath: '/data/kubeconfig',
    }))).toEqual([
      { field: 'namespace', code: 'NOT_REPRESENTABLE' },
      { field: 'kubeContext', code: 'REQUIRED' },
      { field: 'manifestPath', code: 'MANIFEST_REQUIRED' },
    ]);
  });
});