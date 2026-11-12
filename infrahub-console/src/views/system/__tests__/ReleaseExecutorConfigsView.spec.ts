import { afterEach, beforeEach, describe, expect, it, vi, type MockInstance } from 'vitest';
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils';
import ElementPlus, { ElMessage, ElTooltip } from 'element-plus';
import { i18n, setLocale } from '@/i18n';
import zhCN from '@/i18n/locales/zh-CN';
import ReleaseExecutorConfigsView from '../ReleaseExecutorConfigsView.vue';
import {
  createExecutorConfig,
  listExecutorConfigs,
  normalizeExecutorConfigs,
  toggleExecutorConfig,
  updateExecutorConfig,
  type NormalizedExecutorConfig,
} from '@/api/release';
import { envApi } from '@/api/resource';

vi.mock('@/api/release', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/release')>();
  return {
    ...actual,
    listExecutorConfigs: vi.fn(),
    createExecutorConfig: vi.fn(),
    updateExecutorConfig: vi.fn(),
    toggleExecutorConfig: vi.fn(),
  };
});

vi.mock('@/api/resource', () => ({
  envApi: { page: vi.fn() },
}));

const mockedList = vi.mocked(listExecutorConfigs);
const mockedCreate = vi.mocked(createExecutorConfig);
const mockedUpdate = vi.mocked(updateExecutorConfig);
const mockedToggle = vi.mocked(toggleExecutorConfig);
const mockedEnvPage = vi.mocked(envApi.page);

/**
 * The environments the backend lists: identified by id, shown by envName, with envCode as the
 * fallback for a record whose name is missing.
 */
const envRecords = [
  { id: 1, envCode: 'prod', envName: '生产' },
  { id: 2, envCode: 'staging', envName: '预发' },
  { id: 3, envCode: 'dev', envName: '' },
];

/** The real page shape: envApi.page resolves to the unwrapped PageResponse, records included. */
function envPage(records: Record<string, unknown>[]) {
  return { pageNum: 1, pageSize: 20, total: records.length, records };
}

let wrapper: VueWrapper | undefined;
let successToast: MockInstance;
let warningToast: MockInstance;
let errorToast: MockInstance;
let consoleError: MockInstance;
let consoleLog: MockInstance;

/** An error as axios hands one to the caller: already reported by the shared interceptor. */
function axiosLikeError(message: string) {
  return Object.assign(new Error(message), { isAxiosError: true });
}

/** A request that is held open on purpose: the test decides when and how it settles. */
function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (reason?: unknown) => void;
  const promise = new Promise<T>((res, rej) => {
    resolve = res;
    reject = rej;
  });
  return { promise, resolve, reject };
}

function storedSsh(id: number, name: string, enabled: boolean): NormalizedExecutorConfig {
  return normalizeExecutorConfigs([
    { id, executorName: name, executorType: 'SSH', envId: 1, configPayload: '{"host": "192.0.2.12"}', enabled },
  ])[0];
}

/** The enable switch of a table row, in the order the table shows its rows. */
function rowSwitch(row: number): HTMLElement {
  return q<HTMLElement>('.el-switch', tableRows()[row]);
}

/** The value the switch shows: the confirmed one, until a request is accepted. */
function rowSwitchIsChecked(row: number): boolean {
  return rowSwitch(row).classList.contains('is-checked');
}

function storedKubernetes(id: number, name: string, configPayload: string, enabled: boolean) {
  return { id, executorName: name, executorType: 'KUBERNETES' as const, envId: 1, configPayload, enabled };
}

/** A stored Kubernetes payload the form can represent in full. */
function kubePayload(overrides: Record<string, unknown> = {}): string {
  return JSON.stringify({
    kubeconfigPath: 'C:/k8s/kubeconfig',
    kubeContext: 'bound-context',
    namespace: 'prod',
    manifestContent: 'apiVersion: v1',
    ...overrides,
  });
}

async function mountView() {
  wrapper = mount(ReleaseExecutorConfigsView, {
    global: { plugins: [ElementPlus, i18n] },
    attachTo: document.body,
  });
  await flushPromises();
}

function q<T extends Element>(selector: string, root: ParentNode = document): T {
  const el = root.querySelector(selector);
  if (!el) throw new Error(`element not found: ${selector}`);
  return el as unknown as T;
}

function buttonByText(root: ParentNode, text: string): HTMLButtonElement {
  const found = Array.from(root.querySelectorAll('button')).find(
    button => (button.textContent || '').trim() === text,
  );
  if (!found) throw new Error(`button not found: ${text}`);
  return found as HTMLButtonElement;
}

/** The create/edit dialog: the only one that carries the payload editor. */
function editDialog(): HTMLElement {
  const found = Array.from(document.querySelectorAll('.el-dialog')).find(dialog => dialog.querySelector('.payload-section'));
  if (!found) throw new Error('edit dialog is not open');
  return found as HTMLElement;
}

function viewDialog(): HTMLElement {
  const found = Array.from(document.querySelectorAll('.el-dialog')).find(dialog => dialog.querySelector('.payload-preview'));
  if (!found) throw new Error('payload view dialog is not open');
  return found as HTMLElement;
}

/** Whether the create/edit dialog is on screen right now. */
function editDialogOpen(): boolean {
  const dialog = Array.from(document.querySelectorAll('.el-dialog')).find(entry => entry.querySelector('.payload-section'));
  if (!dialog) return false;
  const overlay = dialog.closest('.el-overlay') as HTMLElement | null;
  return !!overlay && overlay.style.display !== 'none';
}

function tableRows(): HTMLElement[] {
  return Array.from(document.querySelectorAll('.el-table__row')) as HTMLElement[];
}

/** The form pane is first, the JSON pane second, in template order. */
function pane(index: number): HTMLElement {
  return q<HTMLElement>(`.payload-section .el-tab-pane:nth-of-type(${index + 1})`, editDialog());
}

function tabsItem(text: string): HTMLElement {
  const found = Array.from(editDialog().querySelectorAll('.el-tabs__item')).find(
    item => (item.textContent || '').trim() === text,
  );
  if (!found) throw new Error(`tab not found: ${text}`);
  return found as HTMLElement;
}

/** The seven text inputs of the Kubernetes form, in template order. */
function formInputs(): HTMLInputElement[] {
  return Array.from(pane(0).querySelectorAll('input')) as HTMLInputElement[];
}

/** The JSON tab's payload editor. */
function jsonTextarea(): HTMLTextAreaElement {
  return q<HTMLTextAreaElement>('textarea', pane(1));
}

/** The payload editor of the SSH / DOCKER dialog, which has no structured form. */
function payloadTextarea(): HTMLTextAreaElement {
  return q<HTMLTextAreaElement>('.payload-section textarea', editDialog());
}

function saveButton(): HTMLButtonElement {
  return buttonByText(editDialog(), zhCN.common.save);
}

async function click(el: HTMLElement) {
  el.click();
  await flushPromises();
  await flushPromises();
}

async function type(el: HTMLInputElement | HTMLTextAreaElement, value: string) {
  el.value = value;
  el.dispatchEvent(new Event('input', { bubbles: true }));
  await flushPromises();
}

/** el-form-item shows its message 100ms after the field's state changes. */
async function waitForFormError() {
  await new Promise(resolve => setTimeout(resolve, 150));
  await flushPromises();
}

/** Element Plus mounts its dialogs and dropdowns on a later frame, so give them one. */
async function waitUntil(predicate: () => boolean, message: string) {
  for (let attempt = 0; attempt < 25; attempt += 1) {
    await flushPromises();
    if (predicate()) return;
    await new Promise(resolve => setTimeout(resolve, 20));
  }
  throw new Error(message);
}

/** The draft dialog that is on screen right now; a closed one stays in the DOM, only hidden. */
function visibleDraftBox(): HTMLElement | undefined {
  return (Array.from(document.querySelectorAll('.el-message-box')) as HTMLElement[]).find(box => {
    const overlay = box.closest('.el-overlay') as HTMLElement | null;
    return !!overlay && overlay.style.display !== 'none';
  });
}

/** The draft dialog shown while an unwritten form draft is undecided. */
async function draftBox(): Promise<HTMLElement> {
  await waitUntil(() => !!visibleDraftBox(), 'the draft dialog did not open');
  return visibleDraftBox() as HTMLElement;
}

async function draftBoxClosed() {
  await waitUntil(() => !visibleDraftBox(), 'the draft dialog did not close');
}

/** The executor type select of the edit dialog; the environment select follows it. */
function typeSelect(): HTMLElement {
  return q<HTMLElement>('.el-select', editDialog());
}

function displayedExecutorType(): string {
  return (typeSelect().textContent || '').trim();
}

/** Options of the select that is open right now; a closed select keeps its list in the DOM, only hidden. */
function selectOptionsOnScreen(): HTMLElement[] {
  return (Array.from(document.querySelectorAll('.el-select-dropdown__item')) as HTMLElement[]).filter(
    item => {
      const popper = item.closest('.el-popper') as HTMLElement | null;
      return !!popper && popper.style.display !== 'none';
    },
  );
}

async function chooseExecutorType(label: string) {
  await click(q<HTMLElement>('.el-select__wrapper', typeSelect()));
  const options = () => selectOptionsOnScreen();
  await waitUntil(() => options().some(item => (item.textContent || '').trim() === label), `no ${label} option`);
  await click(options().find(item => (item.textContent || '').trim() === label) as HTMLElement);
}

/** The environment select of the edit dialog; the executor type select precedes it. */
function envSelect(): HTMLElement {
  const selects = Array.from(editDialog().querySelectorAll<HTMLElement>('.el-select'));
  const found = selects[1];
  if (!found) throw new Error('the environment select is not in the edit dialog');
  return found;
}

/** The environment select of the filter card; the executor type select precedes it. */
function filterEnvSelect(): HTMLElement {
  const selects = Array.from(document.querySelectorAll<HTMLElement>('.filter-card .el-select'));
  const found = selects[1];
  if (!found) throw new Error('the environment filter select is not on screen');
  return found;
}

/** The executor type select of the filter card, the one that precedes the environment select. */
function filterTypeSelect(): HTMLElement {
  const selects = Array.from(document.querySelectorAll<HTMLElement>('.filter-card .el-select'));
  const found = selects[0];
  if (!found) throw new Error('the executor type filter select is not on screen');
  return found;
}

/** One cell of a table row, read from the table itself in template order. */
function rowCell(rowIndex: number, columnIndex: number): string {
  const cell = tableRows()[rowIndex].querySelectorAll('td')[columnIndex];
  if (!cell) throw new Error(`cell ${rowIndex}/${columnIndex} is not on screen`);
  return (cell.textContent || '').trim();
}

/**
 * What a filter select shows while nothing is chosen. Element Plus renders that text as its own
 * element rather than as an input placeholder, so it is read from where it is rendered.
 */
function filterPlaceholder(index: number): string {
  const select = document.querySelectorAll<HTMLElement>('.filter-card .el-select')[index];
  const placeholder = select?.querySelector('.el-select__placeholder');
  if (!placeholder) throw new Error(`filter select ${index} shows no placeholder`);
  return (placeholder.textContent || '').trim();
}

/** The empty state the table renders in place of rows. */
function emptyState(): HTMLElement {
  const found = document.querySelector('.empty-state');
  if (!found) throw new Error('the table renders no empty state');
  return found as HTMLElement;
}

function emptyText(selector: string): string {
  const found = emptyState().querySelector(selector);
  if (!found) throw new Error(`the empty state has no ${selector}`);
  return (found.textContent || '').trim();
}

function emptyStateButton(): HTMLButtonElement {
  return q<HTMLButtonElement>('button', emptyState());
}

function emptyActionLabel(): string {
  return (emptyStateButton().textContent || '').trim();
}

function readErrorBody(selector: string): string {
  const found = q<HTMLElement>('.read-error').querySelector(selector);
  if (!found) throw new Error(`the read failure carries no ${selector}`);
  return (found.textContent || '').trim();
}

function optionLabel(item: HTMLElement): string {
  return (item.textContent || '').trim();
}

/** The option carrying that label among the ones the open dropdown shows right now. */
function optionByLabel(label: string): HTMLElement {
  const found = selectOptionsOnScreen().find(item => optionLabel(item) === label);
  if (!found) throw new Error(`option not found: ${label}`);
  return found;
}

/** Opens the given select and takes the option carrying that label. */
async function chooseOption(select: HTMLElement, label: string) {
  await click(q<HTMLElement>('.el-select__wrapper', select));
  await waitUntil(
    () => selectOptionsOnScreen().some(item => optionLabel(item) === label),
    `no ${label} option`,
  );
  await click(optionByLabel(label));
}

beforeEach(() => {
  setLocale('zh-CN');
  (globalThis as unknown as { ResizeObserver: unknown }).ResizeObserver = class {
    observe() {}
    unobserve() {}
    disconnect() {}
  };
  mockedList.mockReset();
  mockedCreate.mockReset();
  mockedUpdate.mockReset();
  mockedToggle.mockReset();
  mockedEnvPage.mockReset();
  mockedEnvPage.mockResolvedValue(envPage(envRecords));
  successToast = vi.spyOn(ElMessage, 'success').mockImplementation(() => ({}) as never);
  warningToast = vi.spyOn(ElMessage, 'warning').mockImplementation(() => ({}) as never);
  errorToast = vi.spyOn(ElMessage, 'error').mockImplementation(() => ({}) as never);
  consoleError = vi.spyOn(console, 'error').mockImplementation(() => {});
  consoleLog = vi.spyOn(console, 'log').mockImplementation(() => {});
});

afterEach(() => {
  wrapper?.unmount();
  wrapper = undefined;
  document.body.innerHTML = '';
  vi.restoreAllMocks();
});

describe('ReleaseExecutorConfigsView', () => {
  it('does not send a create request while the payload text is invalid, and sends the text verbatim once it is valid', async () => {
    mockedList.mockResolvedValue([]);
    await mountView();

    await click(buttonByText(document, zhCN.common.create));
    const nameInput = q<HTMLInputElement>('.el-form-item input', editDialog());
    await type(nameInput, 'ssh-a');
    await type(payloadTextarea(), '{"host": "CANARY-PAYLOAD"');

    await click(saveButton());
    expect(mockedCreate).not.toHaveBeenCalled();
    expect(mockedUpdate).not.toHaveBeenCalled();
    expect(editDialogOpen()).toBe(true);
    expect(successToast).not.toHaveBeenCalled();
    expect(consoleError).not.toHaveBeenCalled();

    await type(payloadTextarea(), '{"host": "192.0.2.12"}');
    await click(saveButton());

    expect(mockedCreate).toHaveBeenCalledTimes(1);
    expect(mockedCreate.mock.calls[0][0].configPayload).toBe('{"host": "192.0.2.12"}');
    expect(successToast).toHaveBeenCalledTimes(1);
    expect(editDialogOpen()).toBe(false);
    expect(consoleLog).not.toHaveBeenCalled();
    expect(consoleError).not.toHaveBeenCalled();
  });

  it('keeps the dialog and everything typed when the backend rejects the save', async () => {
    mockedList.mockResolvedValue([]);
    mockedCreate.mockRejectedValue(axiosLikeError('kubeconfigPath must be configured'));
    await mountView();

    await click(buttonByText(document, zhCN.common.create));
    await type(q<HTMLInputElement>('.el-form-item input', editDialog()), 'ssh-a');
    await type(payloadTextarea(), '{"host": "192.0.2.12"}');
    await click(saveButton());

    expect(editDialogOpen()).toBe(true);
    expect(payloadTextarea().value).toBe('{"host": "192.0.2.12"}');
    expect(q<HTMLInputElement>('.el-form-item input', editDialog()).value).toBe('ssh-a');
    expect(successToast).not.toHaveBeenCalled();
    // The rejection is reported by the shared interceptor, not a second time here
    expect(errorToast).not.toHaveBeenCalled();
    expect(consoleError).not.toHaveBeenCalled();
  });

  it('restores the enable switch when the backend refuses to enable the configuration', async () => {
    const configs: NormalizedExecutorConfig[] = normalizeExecutorConfigs([
      storedKubernetes(5, 'legacy', '{"namespace": "prod"}', false),
    ]);
    mockedList.mockResolvedValue(configs);
    mockedToggle.mockRejectedValue(axiosLikeError('executor config cannot be enabled'));

    await mountView();
    await click(q<HTMLElement>('.el-table__row .el-switch'));

    expect(mockedToggle).toHaveBeenCalledWith(5, true);
    expect(configs[0].enabled).toBe(false);
    expect(successToast).not.toHaveBeenCalled();
    expect(errorToast).not.toHaveBeenCalled();
    expect(consoleError).not.toHaveBeenCalled();
  });

  it('shows a stored config that is not valid JSON exactly as stored, and refuses to save it uncorrected', async () => {
    const configs: NormalizedExecutorConfig[] = normalizeExecutorConfigs([
      storedKubernetes(5, 'legacy', 'kubeconfigPath: /x', false),
    ]);
    mockedList.mockResolvedValue(configs);
    mockedUpdate.mockResolvedValue(configs[0]);

    await mountView();

    await click(buttonByText(tableRows()[0], zhCN.executorConfig.viewPayload));
    expect(q<HTMLElement>('.payload-preview', viewDialog()).textContent).toBe('kubeconfigPath: /x');
    expect(viewDialog().textContent).toContain(zhCN.executorConfig.legacyInvalidNotice);
    await click(buttonByText(viewDialog(), zhCN.common.close));

    await click(buttonByText(tableRows()[0], zhCN.common.edit));
    expect(jsonTextarea().value).toBe('kubeconfigPath: /x');
    expect(editDialog().textContent).toContain(zhCN.executorConfig.legacyInvalidNotice);

    // The form editor stays unavailable until the text is a JSON object
    await click(tabsItem(zhCN.executorConfig.payloadTabForm));
    expect(tabsItem(zhCN.executorConfig.payloadTabJson).classList.contains('is-active')).toBe(true);
    expect(warningToast).toHaveBeenCalledWith(zhCN.executorConfig.formNeedsJson);

    await click(saveButton());
    expect(mockedUpdate).not.toHaveBeenCalled();
    expect(editDialogOpen()).toBe(true);

    const corrected = '{"kubeconfigPath": "C:/k8s/kubeconfig", "kubeContext": "c", "namespace": "prod", "manifestContent": "apiVersion: v1"}';
    await type(jsonTextarea(), corrected);
    await click(saveButton());

    expect(mockedUpdate).toHaveBeenCalledTimes(1);
    expect(mockedUpdate.mock.calls[0][0]).toBe(5);
    expect(mockedUpdate.mock.calls[0][1].configPayload).toBe(corrected);
  });

  it('keeps the form and the JSON editor in step and preserves fields the form does not own', async () => {
    const payloadText = JSON.stringify({
      kubeconfigPath: 'C:/k8s/kubeconfig',
      kubeContext: 'bound-context',
      namespace: 'prod',
      manifestContent: 'apiVersion: v1',
      futureField: { keep: [1, 2] },
    });
    const configs: NormalizedExecutorConfig[] = normalizeExecutorConfigs([
      storedKubernetes(7, 'k8s-prod', payloadText, true),
    ]);
    mockedList.mockResolvedValue(configs);
    mockedUpdate.mockResolvedValue(configs[0]);

    await mountView();
    await click(buttonByText(tableRows()[0], zhCN.common.edit));

    // A storeable payload opens on the form, seeded from the text
    expect(tabsItem(zhCN.executorConfig.payloadTabForm).classList.contains('is-active')).toBe(true);
    expect(formInputs()[0].value).toBe('C:/k8s/kubeconfig');
    expect(formInputs()[2].value).toBe('prod');

    await type(formInputs()[2], 'prod-2');
    await click(buttonByText(editDialog(), zhCN.executorConfig.commitForm));

    await click(tabsItem(zhCN.executorConfig.payloadTabJson));
    const committed = JSON.parse(jsonTextarea().value) as Record<string, unknown>;
    expect(committed).toEqual({
      kubeconfigPath: 'C:/k8s/kubeconfig',
      kubeContext: 'bound-context',
      namespace: 'prod-2',
      manifestContent: 'apiVersion: v1',
      futureField: { keep: [1, 2] },
    });

    // An edit made in JSON is read back into the form instead of being overwritten
    await type(jsonTextarea(), JSON.stringify({ ...committed, kubeContext: 'other-context' }));
    await click(tabsItem(zhCN.executorConfig.payloadTabForm));
    expect(formInputs()[1].value).toBe('other-context');

    await click(saveButton());

    expect(mockedUpdate).toHaveBeenCalledTimes(1);
    expect(JSON.parse(mockedUpdate.mock.calls[0][1].configPayload as string)).toEqual({
      kubeconfigPath: 'C:/k8s/kubeconfig',
      kubeContext: 'other-context',
      namespace: 'prod-2',
      manifestContent: 'apiVersion: v1',
      futureField: { keep: [1, 2] },
    });
  });

  it('does not submit form values that are invalid, and does not rewrite the payload text for them', async () => {
    const payloadText = JSON.stringify({
      kubeconfigPath: 'C:/k8s/kubeconfig',
      kubeContext: 'bound-context',
      namespace: 'prod',
      manifestContent: 'apiVersion: v1',
    });
    const configs: NormalizedExecutorConfig[] = normalizeExecutorConfigs([
      storedKubernetes(7, 'k8s-prod', payloadText, true),
    ]);
    mockedList.mockResolvedValue(configs);
    mockedUpdate.mockResolvedValue(configs[0]);

    await mountView();
    await click(buttonByText(tableRows()[0], zhCN.common.edit));

    await type(formInputs()[2], '');
    await click(saveButton());

    expect(mockedUpdate).not.toHaveBeenCalled();
    await waitForFormError();
    expect(pane(0).textContent).toContain(zhCN.executorConfig.issue.REQUIRED);
    expect(successToast).not.toHaveBeenCalled();

    // Leaving the invalid form is a decision too: drop the draft, and the JSON text is untouched
    await click(tabsItem(zhCN.executorConfig.payloadTabJson));
    await click(buttonByText(await draftBox(), zhCN.executorConfig.draftDiscard));
    await waitUntil(
      () => tabsItem(zhCN.executorConfig.payloadTabJson).classList.contains('is-active'),
      'the tab did not switch',
    );
    expect(JSON.parse(jsonTextarea().value)).toEqual(JSON.parse(payloadText));
  });

  it('sends nothing when a stored value has a type the form cannot edit', async () => {
    const payloadText = JSON.stringify({
      kubeconfigPath: 'C:/k8s/kubeconfig',
      kubeContext: 'bound-context',
      namespace: 123,
      manifestContent: 'apiVersion: v1',
    });
    const configs: NormalizedExecutorConfig[] = normalizeExecutorConfigs([
      storedKubernetes(7, 'k8s-prod', payloadText, true),
    ]);
    mockedList.mockResolvedValue(configs);
    mockedUpdate.mockResolvedValue(configs[0]);

    await mountView();
    await click(buttonByText(tableRows()[0], zhCN.common.edit));

    // The numeric namespace is not shown as text and is not silently rewritten
    expect(formInputs()[2].value).toBe('');
    await waitForFormError();
    expect(pane(0).textContent).toContain(zhCN.executorConfig.issue.NOT_REPRESENTABLE);

    await click(saveButton());
    expect(mockedUpdate).not.toHaveBeenCalled();

    await click(tabsItem(zhCN.executorConfig.payloadTabJson));
    expect(JSON.parse(jsonTextarea().value)).toEqual(JSON.parse(payloadText));
  });

  it('keeps the payload editor working for SSH configurations', async () => {
    const configs: NormalizedExecutorConfig[] = normalizeExecutorConfigs([
      { id: 4, executorName: 'ssh-a', executorType: 'SSH', envId: 1, configPayload: '{"host": "192.0.2.12", "user": "deploy"}', enabled: true },
    ]);
    mockedList.mockResolvedValue(configs);
    mockedUpdate.mockResolvedValue(configs[0]);

    await mountView();
    await click(buttonByText(tableRows()[0], zhCN.common.edit));

    // SSH keeps the plain text editor, with no structured form and no tabs
    expect(editDialog().querySelectorAll('.el-tabs__item')).toHaveLength(0);
    expect(JSON.parse(payloadTextarea().value)).toEqual({ host: '192.0.2.12', user: 'deploy' });

    await click(saveButton());

    expect(mockedUpdate).toHaveBeenCalledTimes(1);
    expect(JSON.parse(mockedUpdate.mock.calls[0][1].configPayload as string)).toEqual({ host: '192.0.2.12', user: 'deploy' });
  });

  it('does not carry an unwritten form edit into the JSON text on its own when the tab is switched', async () => {
    const payloadText = kubePayload();
    const configs: NormalizedExecutorConfig[] = normalizeExecutorConfigs([
      storedKubernetes(7, 'k8s-prod', payloadText, true),
    ]);
    mockedList.mockResolvedValue(configs);
    mockedUpdate.mockResolvedValue(configs[0]);

    await mountView();
    await click(buttonByText(tableRows()[0], zhCN.common.edit));

    await type(formInputs()[2], 'prod-draft');
    await click(tabsItem(zhCN.executorConfig.payloadTabJson));

    // The switch waits for a decision: still on the form, and nothing was written into the JSON text
    const box = await draftBox();
    expect(tabsItem(zhCN.executorConfig.payloadTabForm).classList.contains('is-active')).toBe(true);
    expect(JSON.parse(jsonTextarea().value)).toEqual(JSON.parse(payloadText));

    await click(buttonByText(box, zhCN.executorConfig.draftDiscard));
    await waitUntil(
      () => tabsItem(zhCN.executorConfig.payloadTabJson).classList.contains('is-active'),
      'the tab did not switch',
    );

    expect(JSON.parse(jsonTextarea().value)).toEqual(JSON.parse(payloadText));

    // The dropped edit is gone: the form reads the configuration content again
    await click(tabsItem(zhCN.executorConfig.payloadTabForm));
    expect(formInputs()[2].value).toBe('prod');
  });

  it('keeps a newer JSON edit when the form is saved over it afterwards', async () => {
    const payloadText = kubePayload();
    const configs: NormalizedExecutorConfig[] = normalizeExecutorConfigs([
      storedKubernetes(7, 'k8s-prod', payloadText, true),
    ]);
    mockedList.mockResolvedValue(configs);
    mockedUpdate.mockResolvedValue(configs[0]);

    await mountView();
    await click(buttonByText(tableRows()[0], zhCN.common.edit));

    await type(formInputs()[2], 'prod-draft');
    await click(tabsItem(zhCN.executorConfig.payloadTabJson));
    await click(buttonByText(await draftBox(), zhCN.executorConfig.draftCommit));
    await waitUntil(
      () => tabsItem(zhCN.executorConfig.payloadTabJson).classList.contains('is-active'),
      'the tab did not switch',
    );

    // The written draft is the JSON text now
    expect(JSON.parse(jsonTextarea().value).namespace).toBe('prod-draft');

    // A later JSON edit goes back into the form instead of being overwritten by the old draft
    await type(jsonTextarea(), JSON.stringify({ ...JSON.parse(jsonTextarea().value), namespace: 'prod-json' }));
    await click(tabsItem(zhCN.executorConfig.payloadTabForm));
    expect(formInputs()[2].value).toBe('prod-json');

    await click(saveButton());

    expect(mockedUpdate).toHaveBeenCalledTimes(1);
    expect(JSON.parse(mockedUpdate.mock.calls[0][1].configPayload as string).namespace).toBe('prod-json');
  });

  it('does not carry an unwritten form edit along when the executor type is switched', async () => {
    const payloadText = kubePayload();
    const configs: NormalizedExecutorConfig[] = normalizeExecutorConfigs([
      storedKubernetes(7, 'k8s-prod', payloadText, true),
    ]);
    mockedList.mockResolvedValue(configs);
    mockedUpdate.mockResolvedValue(configs[0]);

    await mountView();
    await click(buttonByText(tableRows()[0], zhCN.common.edit));

    await type(formInputs()[2], 'prod-draft');
    await chooseExecutorType(zhCN.executorConfig.typeEnum.SSH);

    // Still Kubernetes while the draft is undecided
    await draftBox();
    expect(displayedExecutorType()).toContain(zhCN.executorConfig.typeEnum.KUBERNETES);

    await click(buttonByText(await draftBox(), zhCN.executorConfig.draftCommit));
    await waitUntil(
      () => displayedExecutorType().includes(zhCN.executorConfig.typeEnum.SSH),
      'the type did not switch',
    );

    expect(editDialog().querySelectorAll('.el-tabs__item')).toHaveLength(0);
    expect(JSON.parse(payloadTextarea().value).namespace).toBe('prod-draft');
  });

  it('keeps the type, the tab and the edit untouched when the switch is cancelled', async () => {
    const payloadText = kubePayload();
    const configs: NormalizedExecutorConfig[] = normalizeExecutorConfigs([
      storedKubernetes(7, 'k8s-prod', payloadText, true),
    ]);
    mockedList.mockResolvedValue(configs);
    mockedUpdate.mockResolvedValue(configs[0]);

    await mountView();
    await click(buttonByText(tableRows()[0], zhCN.common.edit));

    await type(formInputs()[2], 'prod-draft');
    await chooseExecutorType(zhCN.executorConfig.typeEnum.DOCKER);

    const box = await draftBox();
    await click(q<HTMLElement>('.el-message-box__headerbtn', box));
    await draftBoxClosed();

    expect(displayedExecutorType()).toContain(zhCN.executorConfig.typeEnum.KUBERNETES);
    expect(tabsItem(zhCN.executorConfig.payloadTabForm).classList.contains('is-active')).toBe(true);
    expect(formInputs()[2].value).toBe('prod-draft');
    expect(JSON.parse(jsonTextarea().value)).toEqual(JSON.parse(payloadText));
  });

  it('refuses to write an invalid form, and can still be left by dropping the edits', async () => {
    const payloadText = kubePayload();
    const configs: NormalizedExecutorConfig[] = normalizeExecutorConfigs([
      storedKubernetes(7, 'k8s-prod', payloadText, true),
    ]);
    mockedList.mockResolvedValue(configs);
    mockedUpdate.mockResolvedValue(configs[0]);

    await mountView();
    await click(buttonByText(tableRows()[0], zhCN.common.edit));

    await type(formInputs()[2], '');
    await click(tabsItem(zhCN.executorConfig.payloadTabJson));
    await click(buttonByText(await draftBox(), zhCN.executorConfig.draftCommit));
    await draftBoxClosed();

    // An invalid form is not written back around its validation
    expect(warningToast).toHaveBeenCalledWith(zhCN.executorConfig.formInvalid);
    expect(tabsItem(zhCN.executorConfig.payloadTabForm).classList.contains('is-active')).toBe(true);
    await waitForFormError();
    expect(pane(0).textContent).toContain(zhCN.executorConfig.issue.REQUIRED);
    expect(JSON.parse(jsonTextarea().value)).toEqual(JSON.parse(payloadText));

    // Dropping the draft is what actually leaves the form
    await click(tabsItem(zhCN.executorConfig.payloadTabJson));
    await click(buttonByText(await draftBox(), zhCN.executorConfig.draftDiscard));
    await waitUntil(
      () => tabsItem(zhCN.executorConfig.payloadTabJson).classList.contains('is-active'),
      'the tab did not switch',
    );

    expect(JSON.parse(jsonTextarea().value)).toEqual(JSON.parse(payloadText));
  });

  it('carries fields the form does not own through both draft decisions', async () => {
    const payloadText = kubePayload({ futureField: { keep: [1, 2] } });
    const configs: NormalizedExecutorConfig[] = normalizeExecutorConfigs([
      storedKubernetes(7, 'k8s-prod', payloadText, true),
    ]);
    mockedList.mockResolvedValue(configs);
    mockedUpdate.mockResolvedValue(configs[0]);

    await mountView();
    await click(buttonByText(tableRows()[0], zhCN.common.edit));
    expect(formInputs()[2].value).toBe('prod');

    // Dropped: the configuration content is untouched
    await type(formInputs()[2], 'prod-draft');
    await click(tabsItem(zhCN.executorConfig.payloadTabJson));
    await click(buttonByText(await draftBox(), zhCN.executorConfig.draftDiscard));
    await waitUntil(
      () => tabsItem(zhCN.executorConfig.payloadTabJson).classList.contains('is-active'),
      'the tab did not switch',
    );
    expect(JSON.parse(jsonTextarea().value).futureField).toEqual({ keep: [1, 2] });

    // Written: the unknown field is carried through the merge
    await click(tabsItem(zhCN.executorConfig.payloadTabForm));
    await type(formInputs()[2], 'prod-draft-2');
    await click(tabsItem(zhCN.executorConfig.payloadTabJson));
    await click(buttonByText(await draftBox(), zhCN.executorConfig.draftCommit));
    await waitUntil(
      () => tabsItem(zhCN.executorConfig.payloadTabJson).classList.contains('is-active'),
      'the tab did not switch',
    );

    expect(JSON.parse(jsonTextarea().value)).toEqual({
      ...JSON.parse(payloadText),
      namespace: 'prod-draft-2',
    });
  });

  it('never submits an integer outside the backend range and writes the largest accepted one as a number', async () => {
    const payloadText = kubePayload();
    const configs: NormalizedExecutorConfig[] = normalizeExecutorConfigs([
      storedKubernetes(7, 'k8s-prod', payloadText, true),
    ]);
    mockedList.mockResolvedValue(configs);
    mockedUpdate.mockResolvedValue(configs[0]);

    await mountView();
    await click(buttonByText(tableRows()[0], zhCN.common.edit));

    await type(formInputs()[5], '2147483648');
    await click(saveButton());
    expect(mockedUpdate).not.toHaveBeenCalled();
    await waitForFormError();
    expect(pane(0).textContent).toContain(zhCN.executorConfig.issue.NOT_POSITIVE_INTEGER);

    await type(formInputs()[5], '9'.repeat(400));
    await click(saveButton());
    expect(mockedUpdate).not.toHaveBeenCalled();

    await type(formInputs()[5], '2147483647');
    await click(saveButton());

    expect(mockedUpdate).toHaveBeenCalledTimes(1);
    const sent = mockedUpdate.mock.calls[0][1].configPayload as string;
    expect(JSON.parse(sent).rolloutTimeoutSeconds).toBe(2147483647);
    expect(sent).not.toContain('null');
  });

  it('holds the JSON editor to the same integer range', async () => {
    const payloadText = kubePayload();
    const configs: NormalizedExecutorConfig[] = normalizeExecutorConfigs([
      storedKubernetes(7, 'k8s-prod', payloadText, true),
    ]);
    mockedList.mockResolvedValue(configs);
    mockedUpdate.mockResolvedValue(configs[0]);

    await mountView();
    await click(buttonByText(tableRows()[0], zhCN.common.edit));
    await click(tabsItem(zhCN.executorConfig.payloadTabJson));

    await type(jsonTextarea(), kubePayload({ logTailLines: 2147483648 }));
    await click(saveButton());
    expect(mockedUpdate).not.toHaveBeenCalled();
    expect(pane(1).textContent).toContain(zhCN.executorConfig.issue.NOT_POSITIVE_INTEGER);

    await type(jsonTextarea(), kubePayload({ logTailLines: 2147483647 }));
    await click(saveButton());

    expect(mockedUpdate).toHaveBeenCalledTimes(1);
    expect(JSON.parse(mockedUpdate.mock.calls[0][1].configPayload as string).logTailLines).toBe(2147483647);
  });

  it('saves an untouched manifest content exactly as it was stored', async () => {
    const manifestContent = 'apiVersion: v1\nkind: Deployment\nitems: |+\n  line1\n\n\n';
    const payloadText = kubePayload({ manifestContent });
    const configs: NormalizedExecutorConfig[] = normalizeExecutorConfigs([
      storedKubernetes(7, 'k8s-prod', payloadText, true),
    ]);
    mockedList.mockResolvedValue(configs);
    mockedUpdate.mockResolvedValue(configs[0]);

    await mountView();
    await click(buttonByText(tableRows()[0], zhCN.common.edit));

    await click(saveButton());

    expect(mockedUpdate).toHaveBeenCalledTimes(1);
    const sent = JSON.parse(mockedUpdate.mock.calls[0][1].configPayload as string) as { manifestContent: string };
    expect(sent.manifestContent).toBe(manifestContent);
  });

  it('loads the environment options from the page records themselves, showing envName and falling back to envCode', async () => {
    mockedList.mockResolvedValue([]);
    await mountView();

    // The page is the thing that carries the records: what it lists is what the select offers
    await click(q<HTMLElement>('.el-select__wrapper', filterEnvSelect()));

    await waitUntil(
      () => selectOptionsOnScreen().length === envRecords.length,
      'the environment options did not open',
    );
    expect(selectOptionsOnScreen().map(optionLabel)).toEqual(['生产', '预发', 'dev']);
  });

  it('keeps the environment options empty when the page lists none, without inventing one', async () => {
    mockedList.mockResolvedValue([]);
    mockedEnvPage.mockResolvedValue(envPage([]));

    await mountView();
    await click(buttonByText(document, zhCN.common.create));
    await click(q<HTMLElement>('.el-select__wrapper', envSelect()));

    expect(selectOptionsOnScreen()).toHaveLength(0);
  });

  it('shows the environment a stored configuration points at, read by its id', async () => {
    const configs: NormalizedExecutorConfig[] = normalizeExecutorConfigs([
      { id: 4, executorName: 'ssh-a', executorType: 'SSH', envId: 2, configPayload: '{}', enabled: true },
    ]);
    mockedList.mockResolvedValue(configs);

    await mountView();
    await click(buttonByText(tableRows()[0], zhCN.common.edit));

    // envId 2 is the environment the page listed as 预发, not a name carried by the row itself
    expect(optionLabel(envSelect())).toContain('预发');
  });

  it('creates the configuration with the envId of the environment the operator chose', async () => {
    mockedList.mockResolvedValue([]);
    const created = normalizeExecutorConfigs([storedKubernetes(9, 'ssh-a', '{}', true)])[0];
    mockedCreate.mockResolvedValue(created);

    await mountView();
    await click(buttonByText(document, zhCN.common.create));
    await chooseOption(envSelect(), '预发');
    await type(q<HTMLInputElement>('.el-form-item input', editDialog()), 'ssh-a');
    await type(payloadTextarea(), '{"host": "192.0.2.12"}');
    await click(saveButton());

    expect(mockedCreate).toHaveBeenCalledTimes(1);
    expect(mockedCreate.mock.calls[0][0].envId).toBe(2);
    expect(mockedCreate.mock.calls[0][0].configPayload).toBe('{"host": "192.0.2.12"}');
  });

  it('holds the confirmed value while its toggle is in flight and refuses the other write entries of that configuration', async () => {
    const configs = [storedSsh(5, 'ssh-a', true)];
    mockedList.mockResolvedValue(configs);
    const pending = deferred<boolean>();
    mockedToggle.mockReturnValue(pending.promise);

    await mountView();

    await click(rowSwitch(0));
    expect(mockedToggle).toHaveBeenCalledTimes(1);
    expect(mockedToggle).toHaveBeenCalledWith(5, false);
    // Nothing is confirmed yet: neither the list object nor the switch shows the requested value
    expect(configs[0].enabled).toBe(true);
    expect(rowSwitchIsChecked(0)).toBe(true);
    expect(successToast).not.toHaveBeenCalled();

    // The other entries of the same configuration are held back, not the switch alone
    await click(rowSwitch(0));
    expect(mockedToggle).toHaveBeenCalledTimes(1);
    expect(rowSwitch(0).classList.contains('is-disabled')).toBe(true);
    const disableButton = buttonByText(tableRows()[0], zhCN.common.disable);
    expect(disableButton.disabled).toBe(true);
    await click(disableButton);
    expect(mockedToggle).toHaveBeenCalledTimes(1);
    const editButton = buttonByText(tableRows()[0], zhCN.common.edit);
    expect(editButton.disabled).toBe(true);
    await click(editButton);
    expect(editDialogOpen()).toBe(false);
    expect(mockedToggle).toHaveBeenCalledTimes(1);

    // Only the accepted response confirms the change, on the row the list holds by that id
    pending.resolve(true);
    await flushPromises();
    await flushPromises();

    expect(configs[0].enabled).toBe(false);
    expect(rowSwitchIsChecked(0)).toBe(false);
    expect(successToast).toHaveBeenCalledWith(zhCN.common.disableSuccess);
    expect(mockedList).toHaveBeenCalledTimes(2);
  });

  it('lets a write on another configuration proceed while one is still in flight', async () => {
    const configs = [storedSsh(5, 'ssh-a', false), storedSsh(6, 'ssh-b', false)];
    mockedList.mockResolvedValue(configs);
    const pending = deferred<boolean>();
    mockedToggle.mockReturnValueOnce(pending.promise).mockResolvedValueOnce(true);

    await mountView();

    await click(rowSwitch(0));
    expect(mockedToggle).toHaveBeenCalledTimes(1);
    expect(rowSwitchIsChecked(1)).toBe(false);

    // A different configuration is not held back by the first one's unfinished write
    await click(rowSwitch(1));
    expect(mockedToggle).toHaveBeenCalledTimes(2);
    expect(mockedToggle.mock.calls[1]).toEqual([6, true]);
    expect(configs[1].enabled).toBe(true);
    expect(rowSwitchIsChecked(1)).toBe(true);
    // …and the first row is untouched by it
    expect(configs[0].enabled).toBe(false);
    expect(rowSwitchIsChecked(0)).toBe(false);

    pending.resolve(true);
    await flushPromises();
    await flushPromises();

    expect(configs[0].enabled).toBe(true);
    expect(rowSwitchIsChecked(0)).toBe(true);
  });

  it('writes nothing into the list when a toggle is refused, even after newer data arrived', async () => {
    const before = [storedSsh(5, 'ssh-a', false)];
    const newer = [storedSsh(5, 'ssh-a', true)];
    mockedList.mockResolvedValueOnce(before).mockResolvedValueOnce(newer);
    const pending = deferred<boolean>();
    mockedToggle.mockReturnValue(pending.promise);

    await mountView();
    await click(rowSwitch(0));
    expect(mockedToggle).toHaveBeenCalledWith(5, true);

    // A newer read arrives while the write is still open: that configuration is enabled now
    await click(buttonByText(document, zhCN.executorConfig.search));
    expect(mockedList).toHaveBeenCalledTimes(2);
    expect(rowSwitchIsChecked(0)).toBe(true);

    pending.reject(axiosLikeError('executor config cannot be enabled'));
    await flushPromises();
    await flushPromises();

    // The refusal inverts nothing and restores nothing from before the request
    expect(newer[0].enabled).toBe(true);
    expect(rowSwitchIsChecked(0)).toBe(true);
    expect(before[0].enabled).toBe(false);
    expect(successToast).not.toHaveBeenCalled();
    expect(errorToast).not.toHaveBeenCalled();
    expect(consoleError).not.toHaveBeenCalled();
  });

  it('re-checks a disable after its confirmation returns instead of writing behind a newer write', async () => {
    const configs = [storedSsh(5, 'ssh-a', true)];
    mockedList.mockResolvedValue(configs);
    const pending = deferred<boolean>();
    mockedToggle.mockReturnValue(pending.promise);

    await mountView();

    // The confirmation is opened first, and waits on the operator
    await click(buttonByText(tableRows()[0], zhCN.common.disable));
    const box = await draftBox();

    // While it waits, a switch write on that same configuration starts
    await click(rowSwitch(0));
    expect(mockedToggle).toHaveBeenCalledTimes(1);

    // Confirming afterwards does not start a second write on that configuration
    await click(q<HTMLElement>('.el-message-box__btns .el-button--primary', box));
    await flushPromises();
    expect(mockedToggle).toHaveBeenCalledTimes(1);
    expect(configs[0].enabled).toBe(true);

    pending.resolve(true);
    await flushPromises();
    await flushPromises();
    expect(configs[0].enabled).toBe(false);
    expect(mockedToggle).toHaveBeenCalledTimes(1);
  });

  it('freezes the edit dialog while saving: one request, no close, no context switch, and a snapshot no later edit can reach', async () => {
    const configs = [storedSsh(5, 'ssh-a', true), storedSsh(6, 'ssh-b', true)];
    mockedList.mockResolvedValue(configs);
    const pending = deferred<NormalizedExecutorConfig>();
    mockedUpdate.mockReturnValue(pending.promise);

    await mountView();
    await click(buttonByText(tableRows()[0], zhCN.common.edit));

    const nameInput = q<HTMLInputElement>('.el-form-item input', editDialog());
    expect(nameInput.value).toBe('ssh-a');
    await type(nameInput, 'renamed');
    await type(payloadTextarea(), '{"host": "192.0.2.13"}');

    // Two clicks in the same task: the guard is claimed before the form validation resolves
    saveButton().click();
    saveButton().click();
    await flushPromises();
    await flushPromises();

    expect(mockedUpdate).toHaveBeenCalledTimes(1);
    expect(mockedCreate).not.toHaveBeenCalled();
    // The id of the path and the id of the payload are the same, and the payload is a snapshot
    expect(mockedUpdate.mock.calls[0][0]).toBe(5);
    expect(mockedUpdate.mock.calls[0][1].id).toBe(5);
    expect(mockedUpdate.mock.calls[0][1].executorName).toBe('renamed');
    expect(mockedUpdate.mock.calls[0][1].configPayload).toBe('{"host": "192.0.2.13"}');

    // The dialog cannot be closed while its save is unfinished
    const cancelButton = buttonByText(editDialog(), zhCN.common.cancel);
    expect(cancelButton.disabled).toBe(true);
    await click(cancelButton);
    expect(editDialogOpen()).toBe(true);
    expect(editDialog().querySelector('.el-dialog__headerbtn')).toBeNull();

    // Nor edited further: what was submitted stays what the request carries
    expect(nameInput.disabled).toBe(true);
    expect(payloadTextarea().disabled).toBe(true);
    await type(nameInput, 'typed-while-saving');
    expect(mockedUpdate.mock.calls[0][1].executorName).toBe('renamed');

    // Nor pointed at another target: neither another row nor a new configuration
    await click(buttonByText(tableRows()[1], zhCN.common.edit));
    expect(q<HTMLInputElement>('.el-form-item input', editDialog()).value).toBe('typed-while-saving');
    await click(buttonByText(document, zhCN.common.create));
    expect(q<HTMLInputElement>('.el-form-item input', editDialog()).value).toBe('typed-while-saving');
    expect(mockedUpdate).toHaveBeenCalledTimes(1);
    expect(mockedCreate).not.toHaveBeenCalled();

    // The rows of that configuration are held back by the same write control
    expect(rowSwitch(0).classList.contains('is-disabled')).toBe(true);
    expect(buttonByText(tableRows()[0], zhCN.common.disable).disabled).toBe(true);

    pending.resolve(configs[0]);
    await flushPromises();
    await flushPromises();

    // The save closes the dialog it was started from, and nothing else is sent
    expect(editDialogOpen()).toBe(false);
    expect(mockedUpdate).toHaveBeenCalledTimes(1);
    expect(mockedList).toHaveBeenCalledTimes(2);
  });

  it('does not disturb the open edit dialog of another configuration while a row write settles', async () => {
    const configs = [storedSsh(5, 'ssh-a', true), storedSsh(6, 'ssh-b', true)];
    mockedList.mockResolvedValue(configs);
    const pending = deferred<boolean>();
    mockedToggle.mockReturnValue(pending.promise);

    await mountView();

    // A row write on the first configuration is left unfinished
    await click(rowSwitch(0));
    expect(mockedToggle).toHaveBeenCalledWith(5, false);

    // The second configuration is edited meanwhile, with a payload of its own
    await click(buttonByText(tableRows()[1], zhCN.common.edit));
    expect(q<HTMLInputElement>('.el-form-item input', editDialog()).value).toBe('ssh-b');
    await type(payloadTextarea(), '{"host": "192.0.2.14"}');

    pending.resolve(true);
    await flushPromises();
    await flushPromises();

    // What the first configuration's write did stays with that configuration
    expect(configs[0].enabled).toBe(false);
    expect(rowSwitchIsChecked(0)).toBe(false);
    expect(editDialogOpen()).toBe(true);
    expect(q<HTMLInputElement>('.el-form-item input', editDialog()).value).toBe('ssh-b');
    expect(payloadTextarea().value).toBe('{"host": "192.0.2.14"}');
    expect(mockedUpdate).not.toHaveBeenCalled();
  });

  it('lifts the freeze and keeps the dialog and its draft when a save is rejected', async () => {
    const configs = [storedSsh(5, 'ssh-a', true)];
    mockedList.mockResolvedValue(configs);
    mockedUpdate
      .mockRejectedValueOnce(axiosLikeError('executor config rejected'))
      .mockResolvedValueOnce(configs[0]);

    await mountView();
    await click(buttonByText(tableRows()[0], zhCN.common.edit));
    await type(payloadTextarea(), '{"host": "192.0.2.15"}');
    await click(saveButton());

    expect(mockedUpdate).toHaveBeenCalledTimes(1);
    expect(editDialogOpen()).toBe(true);
    expect(payloadTextarea().value).toBe('{"host": "192.0.2.15"}');
    expect(q<HTMLInputElement>('.el-form-item input', editDialog()).value).toBe('ssh-a');
    expect(errorToast).not.toHaveBeenCalled();
    expect(consoleError).not.toHaveBeenCalled();

    // The save can be taken up again
    expect(saveButton().disabled).toBe(false);
    expect(buttonByText(editDialog(), zhCN.common.cancel).disabled).toBe(false);
    await click(saveButton());
    expect(mockedUpdate).toHaveBeenCalledTimes(2);
    expect(editDialogOpen()).toBe(false);
  });

  it('keeps the confirmed value and the list on screen when the refresh after a write fails', async () => {
    const configs = [storedSsh(5, 'ssh-a', false)];
    mockedList.mockResolvedValueOnce(configs).mockRejectedValueOnce(axiosLikeError('list failed'));
    mockedToggle.mockResolvedValue(true);

    await mountView();
    await click(rowSwitch(0));

    expect(mockedToggle).toHaveBeenCalledTimes(1);
    expect(mockedList).toHaveBeenCalledTimes(2);
    // The write was accepted, so the failure of the refresh neither rolls it back nor resends it
    expect(configs[0].enabled).toBe(true);
    expect(rowSwitchIsChecked(0)).toBe(true);
    expect(tableRows()).toHaveLength(1);
    expect(successToast).toHaveBeenCalledWith(zhCN.common.enableSuccess);
    expect(warningToast).toHaveBeenCalledWith(zhCN.executorConfig.saveRefreshFailed);
    expect(errorToast).not.toHaveBeenCalled();
    expect(consoleError).not.toHaveBeenCalled();
  });

  it('does not resend a save or drop the list when the refresh after it fails', async () => {
    const configs = [storedSsh(5, 'ssh-a', true)];
    mockedList.mockResolvedValueOnce(configs).mockRejectedValueOnce(axiosLikeError('list failed'));
    mockedUpdate.mockResolvedValue(configs[0]);

    await mountView();
    await click(buttonByText(tableRows()[0], zhCN.common.edit));
    await type(payloadTextarea(), '{"host": "192.0.2.16"}');
    await click(saveButton());

    expect(mockedUpdate).toHaveBeenCalledTimes(1);
    expect(editDialogOpen()).toBe(false);
    expect(tableRows()).toHaveLength(1);
    expect(warningToast).toHaveBeenCalledWith(zhCN.executorConfig.saveRefreshFailed);
    expect(errorToast).not.toHaveBeenCalled();
    expect(consoleError).not.toHaveBeenCalled();
  });

  it('does not let an older list response overwrite a newer one', async () => {
    const older = [storedSsh(5, 'older', true)];
    const newer = [storedSsh(6, 'newer', true)];
    const first = deferred<NormalizedExecutorConfig[]>();
    mockedList.mockReturnValueOnce(first.promise).mockResolvedValueOnce(newer);

    await mountView();
    expect(tableRows()).toHaveLength(0);

    await click(buttonByText(document, zhCN.executorConfig.search));
    expect(tableRows()).toHaveLength(1);
    expect(tableRows()[0].textContent).toContain('newer');

    first.resolve(older);
    await flushPromises();
    await flushPromises();

    expect(tableRows()).toHaveLength(1);
    expect(tableRows()[0].textContent).toContain('newer');
    expect(tableRows()[0].textContent).not.toContain('older');
  });

  it('does not treat a toggle response that only declines the change as a request failure', async () => {
    const configs = [storedSsh(5, 'ssh-a', false)];
    mockedList.mockResolvedValue(configs);
    mockedToggle.mockResolvedValue(false);

    await mountView();
    await click(rowSwitch(0));

    expect(mockedToggle).toHaveBeenCalledWith(5, true);
    // Nothing is confirmed, and no refresh of a change that did not happen is asked for
    expect(configs[0].enabled).toBe(false);
    expect(rowSwitchIsChecked(0)).toBe(false);
    expect(mockedList).toHaveBeenCalledTimes(1);
    expect(warningToast).toHaveBeenCalledWith(zhCN.common.operationFailed);
    expect(successToast).not.toHaveBeenCalled();
    expect(errorToast).not.toHaveBeenCalled();
    expect(consoleError).not.toHaveBeenCalled();

    // The configuration can be written to again afterwards
    expect(rowSwitch(0).classList.contains('is-disabled')).toBe(false);
    mockedToggle.mockResolvedValue(true);
    await click(rowSwitch(0));
    expect(mockedToggle).toHaveBeenCalledTimes(2);
    expect(configs[0].enabled).toBe(true);
  });

  it('leads with the configuration itself, names its environment, and keeps the id queryable', async () => {
    mockedEnvPage.mockResolvedValue(envPage([...envRecords, { id: 7, envCode: '', envName: '' }]));
    mockedList.mockResolvedValue(
      normalizeExecutorConfigs([
        { id: 41, executorName: 'ssh-prod', executorType: 'SSH', envId: 1, configPayload: '{}', enabled: true },
        { id: 42, executorName: 'k8s-staging', executorType: 'KUBERNETES', envId: 2, configPayload: '{}', enabled: false },
        { id: 43, executorName: 'ssh-unknown', executorType: 'SSH', envId: 99, configPayload: '{}', enabled: true },
        { id: 44, executorName: 'ssh-named', executorType: 'SSH', envId: 3, envName: '自报环境', configPayload: '{}', enabled: true },
      ]),
    );
    await mountView();

    // Name, type, environment and status lead the table
    expect(tableRows()).toHaveLength(4);
    expect([rowCell(0, 0), rowCell(1, 0), rowCell(2, 0), rowCell(3, 0)]).toEqual([
      'ssh-prod', 'k8s-staging', 'ssh-unknown', 'ssh-named',
    ]);
    expect([rowCell(0, 1), rowCell(1, 1)]).toEqual([
      zhCN.executorConfig.typeEnum.SSH, zhCN.executorConfig.typeEnum.KUBERNETES,
    ]);

    // The name the record carries leads, then the name the page lists for its id, then the id itself:
    // an environment nothing names stays recognizable as the id it is, and none is invented for it
    expect([rowCell(0, 2), rowCell(1, 2), rowCell(2, 2), rowCell(3, 2)]).toEqual([
      '生产', '预发', '环境 #99', '自报环境',
    ]);

    // The id is demoted behind the main columns rather than removed: it is still on screen, in its own column
    expect([rowCell(0, 5), rowCell(1, 5), rowCell(2, 5), rowCell(3, 5)]).toEqual(['41', '42', '43', '44']);

    // The select names every environment it lists, and marks an id it has no name or code for
    await click(q<HTMLElement>('.el-select__wrapper', filterEnvSelect()));
    await waitUntil(() => selectOptionsOnScreen().length === 4, 'the environment options did not open');
    expect(selectOptionsOnScreen().map(optionLabel)).toEqual(['生产', '预发', 'dev', '环境 #7']);
  });

  it('labels every filter, and reset, search and refresh keep to their own job', async () => {
    mockedList.mockResolvedValue(
      normalizeExecutorConfigs([
        { id: 61, executorName: 'ssh-a', executorType: 'SSH', envId: 1, configPayload: '{}', enabled: true },
        { id: 62, executorName: 'k8s-b', executorType: 'KUBERNETES', envId: 2, configPayload: '{}', enabled: true },
      ]),
    );
    await mountView();

    // Every filter says what it filters before anything is chosen
    expect(filterPlaceholder(0)).toBe(zhCN.executorConfig.allTypes);
    expect(filterPlaceholder(1)).toBe(zhCN.executorConfig.allEnvironments);

    await chooseOption(filterTypeSelect(), zhCN.executorConfig.typeEnum.KUBERNETES);
    expect([rowCell(0, 0)]).toEqual(['k8s-b']);

    // A draft the operator is editing is not the reset entry's business
    await click(buttonByText(tableRows()[0], zhCN.common.edit));
    await waitUntil(() => editDialogOpen(), 'the edit dialog did not open');
    await type(formInputs()[0], 'kept draft');

    await click(buttonByText(document, zhCN.common.resetFilters));

    // The filters come off the screen and the rows the read already answered with are shown under no
    // filter at all; the page filters what it has, so resetting is not a read
    expect(filterPlaceholder(0)).toBe(zhCN.executorConfig.allTypes);
    expect(filterPlaceholder(1)).toBe(zhCN.executorConfig.allEnvironments);
    expect(tableRows()).toHaveLength(2);
    expect(mockedList).toHaveBeenCalledTimes(1);
    expect(formInputs()[0].value).toBe('kept draft');

    // Refresh is the entry that reads again
    await click(buttonByText(document, zhCN.common.refresh));
    expect(mockedList).toHaveBeenCalledTimes(2);
  });

  it('tells an empty list apart from a filter that matched nothing, and each offers its own way out', async () => {
    mockedList.mockResolvedValue([]);
    mockedEnvPage.mockResolvedValue(envPage([]));
    await mountView();

    // Nothing is listed and nothing is filtered: the table says so, and offers a read
    expect(tableRows()).toHaveLength(0);
    expect(emptyText('.empty-title')).toBe(zhCN.common.noData);
    expect(emptyText('.empty-hint')).toBe(zhCN.common.noDataHint);
    expect(emptyActionLabel()).toBe(zhCN.common.refresh);

    // A filter that matched nothing is a result of the filters, not an empty list
    wrapper?.unmount();
    document.body.innerHTML = '';
    wrapper = undefined;
    mockedList.mockResolvedValue(
      normalizeExecutorConfigs([
        { id: 71, executorName: 'ssh-a', executorType: 'SSH', envId: 1, configPayload: '{}', enabled: true },
      ]),
    );
    mockedEnvPage.mockResolvedValue(envPage(envRecords));
    await mountView();
    await chooseOption(filterTypeSelect(), zhCN.executorConfig.typeEnum.DOCKER);

    expect(tableRows()).toHaveLength(0);
    expect(emptyText('.empty-title')).toBe(zhCN.common.noMatch);
    expect(emptyText('.empty-hint')).toBe(zhCN.common.noMatchHint);
    expect(emptyActionLabel()).toBe(zhCN.common.clearFilters);

    // Its way out is clearing the filters, and the rows come back with them
    await click(emptyStateButton());
    expect([rowCell(0, 0)]).toEqual(['ssh-a']);
  });

  it('shows a failed read as a read failure, keeps the rows it had, and never calls it empty data', async () => {
    mockedList.mockResolvedValueOnce([storedSsh(81, 'ssh-a', true)]);
    await mountView();
    expect(tableRows()).toHaveLength(1);

    mockedList.mockRejectedValueOnce(axiosLikeError('list unavailable'));
    await click(buttonByText(document, zhCN.common.refresh));

    // The row that was read stays on screen, and the failure is told apart from an empty table
    expect(tableRows()).toHaveLength(1);
    expect(q<HTMLElement>('.read-error .el-alert__title').textContent?.trim()).toBe(zhCN.common.readFailed);
    expect(readErrorBody('.read-error-hint')).toBe(zhCN.common.readFailedHint);
    expect(document.querySelector('.empty-state')).toBeNull();
    // The shared interceptor has reported it: the page does not report it a second time
    expect(errorToast).not.toHaveBeenCalled();

    // Retry is a read, and a read that answers takes the failure off the screen
    mockedList.mockResolvedValue([storedSsh(81, 'ssh-a', true)]);
    await click(buttonByText(document, zhCN.common.retry));
    expect(document.querySelector('.read-error')).toBeNull();
    expect(tableRows()).toHaveLength(1);
  });

  it('names the enable switch after its configuration, and gives a closed entry a reason', async () => {
    mockedList.mockResolvedValue([storedSsh(91, 'ssh-a', false)]);
    await mountView();

    // The switch says which configuration it enables, so it is not read out as an unnamed switch
    expect(rowSwitch(0).querySelector('[role="switch"]')?.getAttribute('aria-label')).toBe(
      zhCN.executorConfig.enabledSwitchAria.replace('{name}', 'ssh-a'),
    );

    const actions = tableRows()[0].querySelectorAll('td')[6];
    // One primary action per row, and the rest stay lightweight entries beside it
    expect(actions.querySelectorAll('button.action-primary')).toHaveLength(1);
    expect(buttonByText(actions, zhCN.common.edit).classList.contains('el-button--primary')).toBe(true);
    const disable = buttonByText(actions, zhCN.common.disable);
    expect(disable.classList.contains('el-button--primary')).toBe(false);
    expect(disable.classList.contains('is-link')).toBe(true);

    // A closed entry says why it is closed, rather than leaving the operator to guess
    expect(disable.disabled).toBe(true);
    const reason = wrapper
      ?.findAllComponents(ElTooltip)
      .find(tip => tip.props('content') === zhCN.executorConfig.alreadyDisabledHint);
    expect(reason).toBeTruthy();
    expect(reason?.props('disabled')).toBe(false);
  });
});