/**
 * Payload editing helpers for the executor configuration dialog.
 *
 * The payload text is the single source of truth: whatever is stored is what the operator sees, and
 * the structured form reads and writes that same text rather than keeping a second copy of the
 * configuration. Nothing here checks that a path exists or that a cluster answers - the backend host
 * decides that, and it stays an execution-time question.
 */

export type KubernetesPayloadField =
  | 'kubeconfigPath'
  | 'kubeContext'
  | 'namespace'
  | 'manifestPath'
  | 'manifestContent'
  | 'workloadName'
  | 'rolloutTimeoutSeconds'
  | 'logTailLines';

export type KubernetesFormValues = Record<KubernetesPayloadField, string>;

export type KubernetesIssueCode =
  | 'REQUIRED'
  | 'MANIFEST_REQUIRED'
  | 'NOT_ABSOLUTE_PATH'
  | 'NOT_POSITIVE_INTEGER'
  | 'NOT_REPRESENTABLE';

export interface KubernetesIssue {
  field: KubernetesPayloadField;
  code: KubernetesIssueCode;
}

export type PayloadTextParse =
  | { ok: true; value: unknown; empty: boolean }
  | { ok: false; code: 'INVALID_JSON' };

/** Fields the form edits as text; the payload keys are the same names. */
const TEXT_KEYS = [
  'kubeconfigPath',
  'kubeContext',
  'namespace',
  'manifestPath',
  'manifestContent',
  'workloadName',
] as const satisfies readonly KubernetesPayloadField[];

/** Fields the form edits as positive integers. */
const INTEGER_KEYS = ['rolloutTimeoutSeconds', 'logTailLines'] as const satisfies readonly KubernetesPayloadField[];

/** Integer fields are read by the backend as a Java int, so this is the largest value it accepts. */
const MAX_INT = 2147483647;

/** Fields a configuration cannot be executed without. */
const REQUIRED_KEYS = ['kubeconfigPath', 'kubeContext', 'namespace'] as const satisfies readonly KubernetesPayloadField[];

/** Fields that name a path on the backend host. */
const PATH_KEYS = ['kubeconfigPath', 'manifestPath'] as const satisfies readonly KubernetesPayloadField[];

export function emptyFormValues(): KubernetesFormValues {
  return {
    kubeconfigPath: '',
    kubeContext: '',
    namespace: '',
    manifestPath: '',
    manifestContent: '',
    workloadName: '',
    rolloutTimeoutSeconds: '',
    logTailLines: '',
  };
}

/**
 * Reads the editor text. Empty text carries no meaning and is read as an empty object, which is what
 * the dialog has always stored for an untouched field; text that is not JSON is refused as such and
 * is never rewritten.
 */
export function parsePayloadText(text: string): PayloadTextParse {
  if (text.trim() === '') {
    return { ok: true, value: {}, empty: true };
  }
  try {
    return { ok: true, value: JSON.parse(text) as unknown, empty: false };
  } catch {
    return { ok: false, code: 'INVALID_JSON' };
  }
}

export function isJsonObject(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

/**
 * Reads the Kubernetes fields out of a payload object.
 *
 * @returns the editable values, plus the fields whose stored value the form cannot represent - a
 * numeric namespace, a text timeout, an array - which the form leaves untouched until the JSON is
 * corrected, so editing through the form never rewrites a value it could not show.
 */
export function extractKubernetesForm(payload: Record<string, unknown>): {
  values: KubernetesFormValues;
  unrepresentable: KubernetesPayloadField[];
} {
  const values = emptyFormValues();
  const unrepresentable: KubernetesPayloadField[] = [];

  for (const key of TEXT_KEYS) {
    const raw = payload[key];
    if (raw === undefined || raw === null) continue;
    if (typeof raw === 'string') {
      values[key] = raw;
    } else {
      unrepresentable.push(key);
    }
  }
  for (const key of INTEGER_KEYS) {
    const raw = payload[key];
    if (raw === undefined || raw === null) continue;
    if (typeof raw === 'number' && Number.isInteger(raw)) {
      values[key] = String(raw);
    } else {
      unrepresentable.push(key);
    }
  }
  return { values, unrepresentable };
}

/**
 * Writes the form values back into a payload object. Keys the form does not own - fields this
 * iteration does not know about - are copied through unchanged, and a field the form was left empty
 * on is removed rather than stored as an empty string, which is how the executor reads absence.
 *
 * manifestContent is stored exactly as it was typed: it is a document, and trimming it or touching
 * its line endings would change the value a YAML block scalar parses to. The other text fields are
 * single identifiers, so the form still writes them as the single value they name.
 */
export function mergeFormIntoPayload(
  payload: Record<string, unknown>,
  values: KubernetesFormValues,
): Record<string, unknown> {
  const merged: Record<string, unknown> = { ...payload };

  for (const key of TEXT_KEYS) {
    const text = key === 'manifestContent' ? values[key] : values[key].trim();
    if (text.trim() === '') {
      delete merged[key];
    } else {
      merged[key] = text;
    }
  }
  for (const key of INTEGER_KEYS) {
    const text = values[key].trim();
    if (text === '') {
      delete merged[key];
    } else if (isStorableInteger(text)) {
      merged[key] = Number(text);
    }
    // Text that did not pass the range rule leaves the field as it stands rather than becoming a
    // number: Number(text) turns digits beyond its range into Infinity, which JSON.stringify writes
    // as null. Validation refuses that text before either editor gets here.
  }
  return merged;
}

/**
 * Whether this looks like an absolute path on a host the backend could be running on. Only the form
 * of the path is read: whether the file is there is decided by the backend, and it accepts neither a
 * relative path nor a path that is not absolute on the host it runs on.
 */
export function isRecognizableAbsolutePath(value: string): boolean {
  return /^[A-Za-z]:[\\/]/.test(value) || /^\//.test(value) || /^\\\\/.test(value);
}

/** The pure rules the backend applies to a Kubernetes configuration, applied to form values. */
export function validateKubernetesForm(values: KubernetesFormValues): KubernetesIssue[] {
  const issues: KubernetesIssue[] = [];

  for (const key of REQUIRED_KEYS) {
    if (values[key].trim() === '') {
      issues.push({ field: key, code: 'REQUIRED' });
    }
  }

  for (const key of PATH_KEYS) {
    const text = values[key].trim();
    if (text !== '' && !isRecognizableAbsolutePath(text)) {
      issues.push({ field: key, code: 'NOT_ABSOLUTE_PATH' });
    }
  }

  if (values.manifestPath.trim() === '' && values.manifestContent.trim() === '') {
    issues.push({ field: 'manifestPath', code: 'MANIFEST_REQUIRED' });
  }

  for (const key of INTEGER_KEYS) {
    const text = values[key].trim();
    if (text !== '' && !isStorableInteger(text)) {
      issues.push({ field: key, code: 'NOT_POSITIVE_INTEGER' });
    }
  }

  return issues;
}

/**
 * The rules for a configuration being edited through the form: a stored value the form cannot show is
 * reported once as itself rather than also as a missing field, since its type is what has to be fixed.
 */
export function validateKubernetesStoredForm(
  unrepresentable: KubernetesPayloadField[],
  values: KubernetesFormValues,
): KubernetesIssue[] {
  const typeIssues = unrepresentable.map((field): KubernetesIssue => ({ field, code: 'NOT_REPRESENTABLE' }));
  const shadowed = new Set(unrepresentable);
  return [...typeIssues, ...validateKubernetesForm(values).filter(issue => !shadowed.has(issue.field))];
}

/** The same rules read straight off a payload object, so the JSON editor is held to them too. */
export function validateKubernetesPayload(payload: Record<string, unknown>): KubernetesIssue[] {
  const { values, unrepresentable } = extractKubernetesForm(payload);
  return validateKubernetesStoredForm(unrepresentable, values);
}

export function serializePayloadObject(payload: Record<string, unknown>): string {
  return JSON.stringify(payload, null, 2);
}

/**
 * Whether this text is an integer the backend can store: digits only, positive, and within the range
 * of a Java int. Digits beyond what a double can hold exactly - let alone the int range - are refused
 * here rather than being read as Infinity and written as null.
 */
function isStorableInteger(text: string): boolean {
  if (!/^\d+$/.test(text)) return false;
  const value = Number(text);
  return Number.isSafeInteger(value) && value >= 1 && value <= MAX_INT;
}