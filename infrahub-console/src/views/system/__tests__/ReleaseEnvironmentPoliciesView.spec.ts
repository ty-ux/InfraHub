import { afterEach, beforeEach, describe, expect, it, vi, type MockInstance } from 'vitest';
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils';
import ElementPlus, { ElMessage } from 'element-plus';
import { i18n, setLocale } from '@/i18n';
import zhCN from '@/i18n/locales/zh-CN';
import ReleaseEnvironmentPoliciesView from '../ReleaseEnvironmentPoliciesView.vue';
import { releaseEnvironmentPolicyApi, type ReleaseEnvironmentPolicy } from '@/api/releaseEnvironmentPolicy';
import { envApi } from '@/api/resource';

vi.mock('@/api/releaseEnvironmentPolicy', () => ({
  releaseEnvironmentPolicyApi: {
    list: vi.fn(),
    saveAll: vi.fn(),
    getEffective: vi.fn(),
  },
}));

vi.mock('@/api/resource', () => ({
  envApi: { page: vi.fn() },
}));

const mockedList = vi.mocked(releaseEnvironmentPolicyApi.list);
const mockedSaveAll = vi.mocked(releaseEnvironmentPolicyApi.saveAll);
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

/** One stored policy as the list endpoint answers it, in the array it answers with. */
function policyRow(overrides: Partial<ReleaseEnvironmentPolicy> = {}): ReleaseEnvironmentPolicy {
  return {
    id: 11,
    envId: 1,
    action: 'EXECUTE_RELEASE',
    riskLevel: 'HIGH',
    enabled: true,
    approvalRequired: true,
    precheckRequired: false,
    blocked: false,
    freezeWindowEnabled: true,
    freezeWindowStart: '09:00',
    freezeWindowEnd: '18:00',
    freezeWindowDays: '1,2,3',
    description: 'release guard',
    ...overrides,
  };
}

/** The three rows the list endpoint answers with: prod release, staging release, staging rollback. */
function storedPolicies(): ReleaseEnvironmentPolicy[] {
  return [
    policyRow(),
    policyRow({ id: 12, envId: 2, riskLevel: 'ALL', freezeWindowEnabled: false }),
    policyRow({ id: 13, envId: 2, action: 'EXECUTE_ROLLBACK', riskLevel: 'CRITICAL' }),
  ];
}

/**
 * The write endpoint answers 200 with an envelope that carries no data at all, so a resolved
 * request is a save and the value it resolves with is never read.
 */
function acceptWriteWithoutData() {
  mockedSaveAll.mockResolvedValue(null as unknown as void);
}

/** The identity a stored row is written under, as the page itself identifies rows. */
function storedKey(policy: ReleaseEnvironmentPolicy): string {
  return `id:${policy.id}`;
}

/**
 * Both endpoints over one set of stored policies, with every write held open until the test settles
 * it. A write that is accepted reaches the store, so the read that follows an accepted write answers
 * with what that write put there - which is how the row's confirmed value is meant to be observed.
 * A write that is refused leaves the store untouched, and so is the row on screen.
 */
function policyEndpoint(rows: ReleaseEnvironmentPolicy[]) {
  const stored = rows.map(row => ({ ...row }));
  const writes: Array<{
    policy: ReleaseEnvironmentPolicy;
    accept: () => void;
    refuse: (err: unknown) => void;
  }> = [];
  mockedList.mockImplementation(async () => stored.map(row => ({ ...row })));
  mockedSaveAll.mockImplementation(
    (policies: ReleaseEnvironmentPolicy[]) =>
      new Promise<void>((resolve, reject) => {
        const policy = policies[0];
        writes.push({
          policy,
          accept: () => {
            const index = stored.findIndex(row => storedKey(row) === storedKey(policy));
            if (index >= 0) stored[index] = { ...stored[index], ...policy };
            resolve();
          },
          refuse: (err: unknown) => reject(err),
        });
      }),
  );
  return { stored, writes };
}

let wrapper: VueWrapper | undefined;
let successToast: MockInstance;
let errorToast: MockInstance;
let consoleError: MockInstance;
let consoleLog: MockInstance;

/** An error as axios hands one to the caller: already reported by the shared interceptor. */
function axiosLikeError(message: string) {
  return Object.assign(new Error(message), { isAxiosError: true });
}

async function mountView() {
  wrapper = mount(ReleaseEnvironmentPoliciesView, {
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

/** Element Plus mounts its dialogs and dropdowns on a later frame, so give them one. */
async function waitUntil(predicate: () => boolean, message: string) {
  for (let attempt = 0; attempt < 25; attempt += 1) {
    await flushPromises();
    if (predicate()) return;
    await new Promise(resolve => setTimeout(resolve, 20));
  }
  throw new Error(message);
}

function tableRows(): HTMLElement[] {
  return Array.from(document.querySelectorAll('.el-table__row')) as HTMLElement[];
}

function rowCell(rowIndex: number, columnIndex: number): string {
  const cell = tableRows()[rowIndex].querySelectorAll('td')[columnIndex];
  if (!cell) throw new Error(`cell ${rowIndex}/${columnIndex} is not on screen`);
  return (cell.textContent || '').trim();
}

/** Every row on screen as envId|action|risk, read from the table itself. */
function visibleRows(): string[] {
  return tableRows().map((_row, index) => `${rowCell(index, 0)}|${rowCell(index, 1)}|${rowCell(index, 2)}`);
}

/** The column headers of the table, in template order. */
function headerLabels(): string[] {
  return Array.from(document.querySelectorAll('.el-table__header th .cell')).map(
    cell => (cell.textContent || '').trim(),
  );
}

/** The four in-place switches of a row, in template order. */
function rowSwitches(rowIndex: number): HTMLElement[] {
  return Array.from(tableRows()[rowIndex].querySelectorAll<HTMLElement>('.el-switch'));
}

/**
 * The edit entry of a row, the only way into that policy's dialog.
 */
function editButton(rowIndex: number): HTMLButtonElement {
  return buttonByText(tableRows()[rowIndex], zhCN.releaseEnvironmentPolicy.edit);
}

/**
 * What a filter select shows while nothing is chosen. Element Plus renders that text as its own
 * element rather than as an input placeholder, so it is read from where it is rendered.
 */
function filterPlaceholder(index: number): string {
  const placeholder = filterSelect(index).querySelector('.el-select__placeholder');
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

/** What a switch reports it is showing right now. */
function switchOn(switchEl: HTMLElement): boolean {
  return q<HTMLInputElement>('input', switchEl).getAttribute('aria-checked') === 'true';
}

/** The three filter selects, in template order: environment, action and risk level. */
function filterSelect(index: number): HTMLElement {
  const selects = Array.from(document.querySelectorAll<HTMLElement>('.filter-card .el-select'));
  const found = selects[index];
  if (!found) throw new Error(`filter select ${index} is not on screen`);
  return found;
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

function optionLabel(item: HTMLElement): string {
  return (item.textContent || '').trim();
}

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

/** The policy dialog: the only one that carries the description editor. */
function policyDialog(): HTMLElement {
  const found = Array.from(document.querySelectorAll('.el-dialog')).find(
    dialog => !!dialog.querySelector('textarea'),
  );
  if (!found) throw new Error('the policy dialog is not open');
  return found as HTMLElement;
}

/** Whether the policy dialog is on screen right now; a closed dialog stays in the DOM, only hidden. */
function dialogOpen(): boolean {
  const dialog = Array.from(document.querySelectorAll('.el-dialog')).find(
    entry => !!entry.querySelector('textarea'),
  );
  if (!dialog) return false;
  const overlay = dialog.closest('.el-overlay') as HTMLElement | null;
  return !!overlay && overlay.style.display !== 'none';
}

/** The element a click outside the dialog lands on: the dialog carries its mask handler here. */
function dialogMask(): HTMLElement {
  const parent = policyDialog().parentElement as HTMLElement | null;
  if (!parent) throw new Error('the dialog has no mask to click');
  return parent;
}

function descriptionTextarea(): HTMLTextAreaElement {
  return q<HTMLTextAreaElement>('textarea', policyDialog());
}

function dialogSaveButton(): HTMLButtonElement {
  return buttonByText(policyDialog(), zhCN.releaseEnvironmentPolicy.save);
}

function dialogCancelButton(): HTMLButtonElement {
  return buttonByText(policyDialog(), zhCN.releaseEnvironmentPolicy.cancel);
}

/** The dialog's own close control; the dialog carries none while a write freezes it. */
function dialogCloseButton(): HTMLElement | null {
  return policyDialog().querySelector('.el-dialog__headerbtn');
}

/** The five switches of the dialog, in template order. */
function dialogSwitches(): HTMLElement[] {
  return Array.from(policyDialog().querySelectorAll<HTMLElement>('.el-switch'));
}

/** The seven freeze-window day checkboxes of the dialog, in template order. */
function dayCheckboxes(): HTMLInputElement[] {
  return Array.from(policyDialog().querySelectorAll<HTMLInputElement>('.el-checkbox input'));
}

/** The two freeze-window time selects of the dialog, in template order. */
function dialogTimeSelects(): HTMLElement[] {
  return Array.from(policyDialog().querySelectorAll<HTMLElement>('.el-select'));
}

/** Whether a control Element Plus owns reports itself disabled, wherever it puts the mark. */
function controlDisabled(control: HTMLElement): boolean {
  return control.classList.contains('is-disabled') || !!control.querySelector('.is-disabled');
}

/** Opens the dialog for a row the way the operator does, and waits for it to arrive. */
async function openDialogFor(rowIndex: number) {
  await click(editButton(rowIndex));
  await waitUntil(() => dialogOpen(), 'the policy dialog did not open');
}

/** Closes the dialog the way the operator does by clicking outside it. */
async function clickDialogMask() {
  const mask = dialogMask();
  // Element Plus only reads a mask click when press and release both landed on the mask
  mask.dispatchEvent(new MouseEvent('mousedown', { bubbles: true }));
  mask.dispatchEvent(new MouseEvent('mouseup', { bubbles: true }));
  mask.dispatchEvent(new MouseEvent('click', { bubbles: true }));
  await flushPromises();
}

/** Presses Escape, the way the operator closes a dialog from the keyboard. */
async function pressEscape() {
  document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', code: 'Escape', bubbles: true }));
  await flushPromises();
}

beforeEach(() => {
  setLocale('zh-CN');
  (globalThis as unknown as { ResizeObserver: unknown }).ResizeObserver = class {
    observe() {}
    unobserve() {}
    disconnect() {}
  };
  mockedList.mockReset();
  mockedSaveAll.mockReset();
  mockedEnvPage.mockReset();
  mockedEnvPage.mockResolvedValue(envPage(envRecords));
  successToast = vi.spyOn(ElMessage, 'success').mockImplementation(() => ({}) as never);
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

describe('ReleaseEnvironmentPoliciesView', () => {
  it('shows the page records as environments and the answered array as rows, in the shapes those endpoints use', async () => {
    mockedList.mockResolvedValue(storedPolicies());
    await mountView();

    // The list endpoint answers with the policy array itself: every stored policy is a row
    expect(visibleRows()).toEqual(['生产|执行发布|高', '预发|执行发布|全部', '预发|执行回滚|严重']);
    expect(rowCell(1, 7)).toBe(zhCN.common.no);
    // Every header reads from the vocabulary the console keeps, including the actions column
    expect(headerLabels()).toEqual([
      zhCN.releaseEnvironmentPolicy.env,
      zhCN.releaseEnvironmentPolicy.action,
      zhCN.releaseEnvironmentPolicy.riskLevel,
      zhCN.releaseEnvironmentPolicy.enabled,
      zhCN.releaseEnvironmentPolicy.approvalRequired,
      zhCN.releaseEnvironmentPolicy.precheckRequired,
      zhCN.releaseEnvironmentPolicy.blocked,
      zhCN.releaseEnvironmentPolicy.freezeWindowEnabled,
      zhCN.common.id,
      zhCN.common.actions,
    ]);

    // The page is the thing that carries the records: what it lists is what the select offers
    await click(q<HTMLElement>('.el-select__wrapper', filterSelect(0)));
    await waitUntil(() => selectOptionsOnScreen().length === envRecords.length, 'the environment options did not open');
    expect(selectOptionsOnScreen().map(optionLabel)).toEqual(['生产', '预发', 'dev']);
  });

  it('filters the rows by environment, action and risk level', async () => {
    mockedList.mockResolvedValue(storedPolicies());
    await mountView();

    await chooseOption(filterSelect(0), '预发');
    await click(buttonByText(document, zhCN.releaseEnvironmentPolicy.search));
    expect(visibleRows()).toEqual(['预发|执行发布|全部', '预发|执行回滚|严重']);

    await chooseOption(filterSelect(1), zhCN.releaseEnvironmentPolicy.actionEnum.EXECUTE_ROLLBACK);
    await click(buttonByText(document, zhCN.releaseEnvironmentPolicy.search));
    expect(visibleRows()).toEqual(['预发|执行回滚|严重']);

    await chooseOption(filterSelect(2), zhCN.releaseEnvironmentPolicy.riskLevelEnum.CRITICAL);
    await click(buttonByText(document, zhCN.releaseEnvironmentPolicy.search));
    expect(visibleRows()).toEqual(['预发|执行回滚|严重']);
  });

  it('offers the ALL risk level the backend supports and filters by it', async () => {
    mockedList.mockResolvedValue(storedPolicies());
    await mountView();

    await chooseOption(filterSelect(2), zhCN.releaseEnvironmentPolicy.riskLevelAllOption);
    await click(buttonByText(document, zhCN.releaseEnvironmentPolicy.search));

    expect(visibleRows()).toEqual(['预发|执行发布|全部']);
  });

  it('leaves the table and the environment select empty when the endpoints answer with nothing', async () => {
    mockedList.mockResolvedValue([]);
    mockedEnvPage.mockResolvedValue(envPage([]));
    await mountView();

    // Nothing is listed, and nothing is invented to fill the gap
    expect(tableRows()).toHaveLength(0);
    await click(q<HTMLElement>('.el-select__wrapper', filterSelect(0)));
    expect(selectOptionsOnScreen()).toHaveLength(0);
  });

  it('treats a write the endpoint accepts without data as a save, and sends the whole row back', async () => {
    const stored = policyRow({ id: 21, envId: 1 });
    mockedList.mockResolvedValue([stored]);
    acceptWriteWithoutData();
    await mountView();

    await click(buttonByText(tableRows()[0], zhCN.releaseEnvironmentPolicy.edit));
    await waitUntil(() => dialogOpen(), 'the policy dialog did not open');
    await type(descriptionTextarea(), 'edited guard');
    await click(dayCheckboxes()[4]);
    await click(dialogSaveButton());

    expect(mockedSaveAll).toHaveBeenCalledTimes(1);
    const payload = mockedSaveAll.mock.calls[0][0];
    expect(payload).toHaveLength(1);
    expect(payload[0]).toMatchObject({
      id: 21,
      envId: 1,
      action: 'EXECUTE_RELEASE',
      riskLevel: 'HIGH',
      enabled: true,
      approvalRequired: true,
      precheckRequired: false,
      blocked: false,
      freezeWindowEnabled: true,
      freezeWindowStart: '09:00',
      freezeWindowEnd: '18:00',
      freezeWindowDays: '1,2,3,5',
      description: 'edited guard',
    });

    expect(successToast).toHaveBeenCalledWith(zhCN.releaseEnvironmentPolicy.saveSuccess);
    expect(dialogOpen()).toBe(false);
    // The accepted write is followed by one read of the list
    expect(mockedList).toHaveBeenCalledTimes(2);
    expect(errorToast).not.toHaveBeenCalled();
    expect(consoleError).not.toHaveBeenCalled();
    expect(consoleLog).not.toHaveBeenCalled();
  });

  it('moves an in-place switch to the asked value only once the write is accepted', async () => {
    const endpoint = policyEndpoint([policyRow({ id: 101, enabled: false })]);
    await mountView();

    expect(switchOn(rowSwitches(0)[0])).toBe(false);
    await click(rowSwitches(0)[0]);

    // The request carries what the operator asked for, and the row is not moved ahead of it
    expect(endpoint.writes).toHaveLength(1);
    expect(endpoint.writes[0].policy.enabled).toBe(true);
    expect(endpoint.stored[0].enabled).toBe(false);
    expect(switchOn(rowSwitches(0)[0])).toBe(false);
    expect(successToast).not.toHaveBeenCalled();

    endpoint.writes[0].accept();
    await flushPromises();

    // The accepted value is the one the row now shows
    expect(endpoint.stored[0].enabled).toBe(true);
    expect(switchOn(rowSwitches(0)[0])).toBe(true);
    expect(successToast).toHaveBeenCalledWith(zhCN.releaseEnvironmentPolicy.saveSuccess);
  });

  it('hands the API a snapshot of the draft, never the draft the operator is still editing', async () => {
    const endpoint = policyEndpoint([policyRow({ id: 102, description: 'release guard' })]);
    await mountView();
    await openDialogFor(0);

    await type(descriptionTextarea(), 'first draft');
    await click(dialogSaveButton());
    expect(endpoint.writes).toHaveLength(1);
    expect(endpoint.writes[0].policy.description).toBe('first draft');
    expect(endpoint.writes[0].policy.freezeWindowDays).toBe('1,2,3');

    // The refused write releases the dialog with the draft still there to be changed
    endpoint.writes[0].refuse(axiosLikeError('policy is frozen'));
    await flushPromises();
    expect(dialogOpen()).toBe(true);
    expect(descriptionTextarea().disabled).toBe(false);

    await type(descriptionTextarea(), 'second draft');
    await click(dayCheckboxes()[4]);
    await click(dialogSaveButton());
    expect(endpoint.writes).toHaveLength(2);
    expect(endpoint.writes[1].policy.description).toBe('second draft');
    expect(endpoint.writes[1].policy.freezeWindowDays).toBe('1,2,3,5');

    // What was submitted the first time is what the first request carries, edits and all since
    expect(endpoint.writes[0].policy.description).toBe('first draft');
    expect(endpoint.writes[0].policy.freezeWindowDays).toBe('1,2,3');
  });

  it('gives a policy being written in place no edit entry, so one policy never holds two writes', async () => {
    const endpoint = policyEndpoint([
      policyRow({ id: 103, enabled: false }),
      policyRow({ id: 104, envId: 2, enabled: false }),
    ]);
    await mountView();

    await click(rowSwitches(0)[0]);
    expect(endpoint.writes).toHaveLength(1);

    // The policy whose write is in flight has no way into its dialog, and takes no second request
    expect(editButton(0).disabled).toBe(true);
    await click(editButton(0));
    expect(dialogOpen()).toBe(false);
    expect(endpoint.writes).toHaveLength(1);

    // The other policy is not held back by that write
    expect(editButton(1).disabled).toBe(false);
  });

  it('refuses the in-place write of a policy while the dialog is writing it', async () => {
    const endpoint = policyEndpoint([
      policyRow({ id: 105, enabled: true }),
      policyRow({ id: 106, envId: 2, enabled: true }),
    ]);
    await mountView();

    await openDialogFor(0);
    await type(descriptionTextarea(), 'written from the dialog');
    await click(dialogSaveButton());
    expect(endpoint.writes).toHaveLength(1);

    // The very same policy cannot be written from the table while that write is in flight
    await click(rowSwitches(0)[0]);
    await click(rowSwitches(0)[1]);
    expect(endpoint.writes).toHaveLength(1);
    expect(endpoint.writes[0].policy.description).toBe('written from the dialog');

    // Every other policy is free to be written while it is: only the one being written is held
    await click(rowSwitches(1)[0]);
    expect(endpoint.writes).toHaveLength(2);
    expect(endpoint.writes[1].policy.id).toBe(106);
  });

  it('freezes the dialog while its write is in flight: no field, no cancel, no close, no ESC, no mask, no swap', async () => {
    const endpoint = policyEndpoint([
      policyRow({ id: 107, enabled: true }),
      policyRow({ id: 108, envId: 2, enabled: true }),
    ]);
    await mountView();
    await openDialogFor(0);
    await type(descriptionTextarea(), 'draft kept through the write');
    await click(dialogSaveButton());
    expect(endpoint.writes).toHaveLength(1);

    // Nothing on the form can be edited while the write is in flight
    expect(descriptionTextarea().disabled).toBe(true);
    expect(dayCheckboxes().every(box => box.disabled)).toBe(true);
    expect(dialogTimeSelects().every(select => controlDisabled(select))).toBe(true);
    expect(dialogSwitches().every(entry => controlDisabled(entry))).toBe(true);

    // The cancel is refused, and the close control is gone from the header
    expect(dialogCancelButton().disabled).toBe(true);
    await click(dialogCancelButton());
    expect(dialogOpen()).toBe(true);
    expect(dialogCloseButton()).toBeNull();

    // Neither Escape nor a click outside the dialog closes it
    await pressEscape();
    expect(dialogOpen()).toBe(true);
    await clickDialogMask();
    expect(dialogOpen()).toBe(true);

    // Nor does another policy take the dialog over
    expect(editButton(1).disabled).toBe(true);
    await click(editButton(1));
    expect(dialogOpen()).toBe(true);
    expect(descriptionTextarea().value).toBe('draft kept through the write');
    expect(endpoint.writes).toHaveLength(1);

    // The freeze lasts exactly as long as the write does
    endpoint.writes[0].accept();
    await flushPromises();
    expect(dialogOpen()).toBe(false);
    expect(editButton(1).disabled).toBe(false);
  });

  it('closes the dialog by its cancel when no write is in flight', async () => {
    policyEndpoint([policyRow({ id: 109 }), policyRow({ id: 110, envId: 2 })]);
    await mountView();

    await openDialogFor(0);
    // The close control is there to be used, and the cancel closes the dialog
    expect(dialogCloseButton()).not.toBeNull();
    expect(dialogCancelButton().disabled).toBe(false);
    await click(dialogCancelButton());
    expect(dialogOpen()).toBe(false);
  });

  it('closes the dialog by Escape when no write is in flight', async () => {
    policyEndpoint([policyRow({ id: 115 }), policyRow({ id: 116, envId: 2 })]);
    await mountView();

    await openDialogFor(0);
    await pressEscape();
    expect(dialogOpen()).toBe(false);
  });

  it('closes the dialog by a click outside it when no write is in flight', async () => {
    policyEndpoint([policyRow({ id: 117 }), policyRow({ id: 118, envId: 2 })]);
    await mountView();

    await openDialogFor(0);
    await clickDialogMask();
    expect(dialogOpen()).toBe(false);
  });

  it('answers one policy without closing or emptying the dialog another policy has opened', async () => {
    const endpoint = policyEndpoint([
      policyRow({ id: 111, enabled: false }),
      policyRow({ id: 112, envId: 2, description: 'untouched' }),
    ]);
    await mountView();

    // One policy's write is started from the table...
    await click(rowSwitches(0)[0]);
    expect(endpoint.writes).toHaveLength(1);

    // ...another policy's dialog opens while it is in flight, with a draft and a write of its own
    await openDialogFor(1);
    await type(descriptionTextarea(), 'draft of the other policy');
    await click(dialogSaveButton());
    expect(endpoint.writes).toHaveLength(2);
    expect(endpoint.writes[1].policy.id).toBe(112);

    // The first policy's answer lands on the first policy alone
    endpoint.writes[0].accept();
    await flushPromises();
    expect(switchOn(rowSwitches(0)[0])).toBe(true);
    expect(dialogOpen()).toBe(true);
    expect(descriptionTextarea().value).toBe('draft of the other policy');
    expect(successToast).toHaveBeenCalledTimes(1);

    // ...and the second policy's own write still settles on its own terms
    endpoint.writes[1].accept();
    await flushPromises();
    expect(dialogOpen()).toBe(false);
    expect(successToast).toHaveBeenCalledTimes(2);
    expect(errorToast).not.toHaveBeenCalled();
  });

  it('writes a row once while its write is pending, and leaves the confirmed value when the write is refused', async () => {
    const endpoint = policyEndpoint([policyRow({ id: 31, enabled: false })]);
    await mountView();

    await click(rowSwitches(0)[0]);
    expect(endpoint.writes).toHaveLength(1);
    // Nothing is confirmed yet, so the row has not moved
    expect(switchOn(rowSwitches(0)[0])).toBe(false);

    // The same row is not written twice while its write is still in flight
    await click(rowSwitches(0)[0]);
    expect(endpoint.writes).toHaveLength(1);

    endpoint.writes[0].refuse(axiosLikeError('policy is frozen'));
    await flushPromises();

    // The refused write changes nothing, here or in the store
    expect(switchOn(rowSwitches(0)[0])).toBe(false);
    expect(endpoint.stored[0].enabled).toBe(false);
    expect(successToast).not.toHaveBeenCalled();
    // The rejection is reported by the shared interceptor, not a second time here
    expect(errorToast).not.toHaveBeenCalled();
    expect(consoleError).not.toHaveBeenCalled();
    // A refused write is never refreshed around
    expect(mockedList).toHaveBeenCalledTimes(1);

    // The refusal releases the row: it can be written again
    await click(rowSwitches(0)[0]);
    expect(endpoint.writes).toHaveLength(2);
  });

  it('never puts a stale snapshot back over data a refresh has read since the write was sent', async () => {
    const endpoint = policyEndpoint([policyRow({ id: 41, enabled: false })]);
    await mountView();

    await click(rowSwitches(0)[0]);
    expect(endpoint.writes).toHaveLength(1);
    expect(switchOn(rowSwitches(0)[0])).toBe(false);

    // A read taken after the write was sent answers with the row already enabled, and the table takes it
    endpoint.stored[0] = { ...endpoint.stored[0], enabled: true };
    await click(buttonByText(document, zhCN.releaseEnvironmentPolicy.search));
    expect(switchOn(rowSwitches(0)[0])).toBe(true);

    // The write that was sent before that read is refused
    endpoint.writes[0].refuse(axiosLikeError('policy is frozen'));
    await flushPromises();

    // The row keeps what the read brought in: no older snapshot is put back over it
    expect(switchOn(rowSwitches(0)[0])).toBe(true);
    expect(endpoint.writes).toHaveLength(1);
    expect(successToast).not.toHaveBeenCalled();
    expect(errorToast).not.toHaveBeenCalled();
  });

  it('never puts back a row the filters have taken away while its write was in flight', async () => {
    const endpoint = policyEndpoint([
      policyRow({ id: 121, envId: 1, enabled: false }),
      policyRow({ id: 122, envId: 2, enabled: false }),
    ]);
    await mountView();

    await click(rowSwitches(0)[0]);
    expect(endpoint.writes).toHaveLength(1);

    // The filters take that row off the table while its write is still in flight
    await chooseOption(filterSelect(0), '预发');
    await click(buttonByText(document, zhCN.releaseEnvironmentPolicy.search));
    expect(visibleRows()).toEqual(['预发|执行发布|高']);

    endpoint.writes[0].accept();
    await flushPromises();

    // The accepted value is stored, and lands nowhere on screen: a row the filters took away is not
    // put back under them
    expect(endpoint.stored.find(row => row.id === 121)?.enabled).toBe(true);
    expect(visibleRows()).toEqual(['预发|执行发布|高']);
    expect(successToast).toHaveBeenCalledWith(zhCN.releaseEnvironmentPolicy.saveSuccess);
    expect(errorToast).not.toHaveBeenCalled();
  });

  it('writes two different rows independently', async () => {
    const endpoint = policyEndpoint([
      policyRow({ id: 51, enabled: false }),
      policyRow({ id: 52, envId: 2, enabled: false }),
    ]);
    await mountView();

    await click(rowSwitches(0)[0]);
    await click(rowSwitches(1)[0]);

    // Each row is written on its own, with its own state, while the other one is still pending
    expect(endpoint.writes).toHaveLength(2);
    expect(endpoint.writes[0].policy.id).toBe(51);
    expect(endpoint.writes[1].policy.id).toBe(52);
    expect(endpoint.writes[0].policy.enabled).toBe(true);
    expect(endpoint.writes[1].policy.enabled).toBe(true);
    // Neither row has moved yet: both writes are still unconfirmed
    expect(switchOn(rowSwitches(0)[0])).toBe(false);
    expect(switchOn(rowSwitches(1)[0])).toBe(false);

    // Each one is confirmed by its own answer, and only then
    endpoint.writes[0].accept();
    await flushPromises();
    expect(switchOn(rowSwitches(0)[0])).toBe(true);
    expect(switchOn(rowSwitches(1)[0])).toBe(false);

    endpoint.writes[1].accept();
    await flushPromises();
    expect(switchOn(rowSwitches(1)[0])).toBe(true);

    expect(successToast).toHaveBeenCalledTimes(2);
    expect(errorToast).not.toHaveBeenCalled();
    // Each accepted write re-reads the list
    expect(mockedList).toHaveBeenCalledTimes(3);
  });

  it('keeps the dialog and the draft when the write is refused, and does not submit it twice', async () => {
    const stored = policyRow({ id: 61 });
    mockedList.mockResolvedValue([stored]);
    let refuse: (err: unknown) => void = () => {};
    mockedSaveAll.mockImplementation(
      () => new Promise<void>((_resolve, reject) => {
        refuse = reject;
      }),
    );
    await mountView();

    await click(buttonByText(tableRows()[0], zhCN.releaseEnvironmentPolicy.edit));
    await waitUntil(() => dialogOpen(), 'the policy dialog did not open');
    await type(descriptionTextarea(), 'kept draft');

    await click(dialogSaveButton());
    await click(dialogSaveButton());
    expect(mockedSaveAll).toHaveBeenCalledTimes(1);

    refuse(axiosLikeError('policy is frozen'));
    await flushPromises();

    expect(dialogOpen()).toBe(true);
    expect(descriptionTextarea().value).toBe('kept draft');
    // The refusal lifts the freeze, draft and controls intact
    expect(descriptionTextarea().disabled).toBe(false);
    expect(dialogCancelButton().disabled).toBe(false);
    expect(dialogCloseButton()).not.toBeNull();
    expect(successToast).not.toHaveBeenCalled();
    expect(errorToast).not.toHaveBeenCalled();
    // A refused write is never refreshed around
    expect(mockedList).toHaveBeenCalledTimes(1);
    expect(consoleError).not.toHaveBeenCalled();
  });

  it('reports a refresh that fails after an accepted write without sending the write again', async () => {
    mockedList.mockResolvedValueOnce([policyRow({ id: 71, enabled: false })]);
    mockedList.mockRejectedValueOnce(axiosLikeError('list unavailable'));
    acceptWriteWithoutData();
    await mountView();

    await click(rowSwitches(0)[0]);
    await flushPromises();

    expect(mockedSaveAll).toHaveBeenCalledTimes(1);
    expect(successToast).toHaveBeenCalledWith(zhCN.releaseEnvironmentPolicy.saveSuccess);
    // The refresh failure is told apart from a save failure, and is not a second save
    expect(errorToast).toHaveBeenCalledWith(zhCN.releaseEnvironmentPolicy.saveRefreshFailed);
    expect(errorToast).toHaveBeenCalledTimes(1);
    expect(mockedSaveAll).toHaveBeenCalledTimes(1);
    // The row that was saved stays on screen, in the state the write gave it
    expect(tableRows()).toHaveLength(1);
    expect(switchOn(rowSwitches(0)[0])).toBe(true);
    expect(consoleError).not.toHaveBeenCalled();
  });

  it('shows a policy under the environment name the page lists, and marks an id nothing names', async () => {
    mockedEnvPage.mockResolvedValue(envPage([...envRecords, { id: 7, envCode: '', envName: '' }]));
    mockedList.mockResolvedValue([
      policyRow({ id: 31, envId: 1 }),
      policyRow({ id: 32, envId: 2 }),
      policyRow({ id: 33, envId: 99 }),
    ]);
    await mountView();

    // The name the page lists leads; an environment the page does not carry still stays recognizable
    // as the id it is, and no environment is invented for it
    expect(visibleRows().map(row => row.split('|')[0])).toEqual(['生产', '预发', '环境 #99']);

    // The select names an environment by its name, falls back to its code, and marks an id it has
    // no name or code for rather than leaving the option nameless
    await click(q<HTMLElement>('.el-select__wrapper', filterSelect(0)));
    await waitUntil(() => selectOptionsOnScreen().length === 4, 'the environment options did not open');
    expect(selectOptionsOnScreen().map(optionLabel)).toEqual(['生产', '预发', 'dev', '环境 #7']);
  });

  it('labels every filter, and the reset entry clears the filters alone', async () => {
    mockedList.mockResolvedValue(storedPolicies());
    await mountView();

    // Every filter says what it filters, before anything is chosen
    expect(filterPlaceholder(0)).toBe(zhCN.releaseEnvironmentPolicy.allEnvironments);
    expect(filterPlaceholder(1)).toBe(zhCN.releaseEnvironmentPolicy.allActions);
    expect(filterPlaceholder(2)).toBe(zhCN.releaseEnvironmentPolicy.allRiskLevels);

    await chooseOption(filterSelect(0), '预发');
    await click(buttonByText(document, zhCN.releaseEnvironmentPolicy.search));
    expect(visibleRows()).toEqual(['预发|执行发布|全部', '预发|执行回滚|严重']);

    // A draft the operator is editing is not the reset entry's business
    await openDialogFor(0);
    await type(descriptionTextarea(), 'kept draft');

    await click(buttonByText(document, zhCN.common.resetFilters));

    // The filters are off the screen, the read they apply to has been taken again, and the draft and
    // its dialog are untouched
    expect(filterPlaceholder(0)).toBe(zhCN.releaseEnvironmentPolicy.allEnvironments);
    expect(filterPlaceholder(1)).toBe(zhCN.releaseEnvironmentPolicy.allActions);
    expect(filterPlaceholder(2)).toBe(zhCN.releaseEnvironmentPolicy.allRiskLevels);
    expect(visibleRows()).toEqual(['生产|执行发布|高', '预发|执行发布|全部', '预发|执行回滚|严重']);
    expect(dialogOpen()).toBe(true);
    expect(descriptionTextarea().value).toBe('kept draft');
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
    mockedList.mockResolvedValue(storedPolicies());
    mockedEnvPage.mockResolvedValue(envPage(envRecords));
    await mountView();
    await chooseOption(filterSelect(0), 'dev');
    await click(buttonByText(document, zhCN.releaseEnvironmentPolicy.search));

    expect(tableRows()).toHaveLength(0);
    expect(emptyText('.empty-title')).toBe(zhCN.common.noMatch);
    expect(emptyText('.empty-hint')).toBe(zhCN.common.noMatchHint);
    expect(emptyActionLabel()).toBe(zhCN.common.clearFilters);

    // Its way out is clearing the filters, and the rows come back with them
    await click(emptyStateButton());
    expect(visibleRows()).toEqual(['生产|执行发布|高', '预发|执行发布|全部', '预发|执行回滚|严重']);
  });

  it('shows a failed read as a read failure, keeps the rows it had, and never calls it empty data', async () => {
    mockedList.mockResolvedValueOnce(storedPolicies());
    await mountView();
    expect(tableRows()).toHaveLength(3);

    mockedList.mockRejectedValueOnce(axiosLikeError('list unavailable'));
    await click(buttonByText(document, zhCN.common.refresh));

    // The rows that were read stay on screen, and the failure is told apart from an empty table
    expect(tableRows()).toHaveLength(3);
    expect(q<HTMLElement>('.read-error .el-alert__title').textContent?.trim()).toBe(zhCN.common.readFailed);
    expect(readErrorBody('.read-error-hint')).toBe(zhCN.common.readFailedHint);
    expect(document.querySelector('.empty-state')).toBeNull();
    // The shared interceptor has reported it: the page does not report it a second time
    expect(errorToast).not.toHaveBeenCalled();

    // Retry is a read, and a read that answers takes the failure off the screen
    mockedList.mockResolvedValue(storedPolicies());
    await click(buttonByText(document, zhCN.common.retry));
    expect(document.querySelector('.read-error')).toBeNull();
    expect(tableRows()).toHaveLength(3);
  });

  it('names every gate switch after the policy it belongs to, and explains the gates beside them', async () => {
    mockedList.mockResolvedValue(storedPolicies());
    await mountView();

    const gates = [
      zhCN.releaseEnvironmentPolicy.enabled,
      zhCN.releaseEnvironmentPolicy.approvalRequired,
      zhCN.releaseEnvironmentPolicy.precheckRequired,
      zhCN.releaseEnvironmentPolicy.blocked,
    ];
    const switches = rowSwitches(0);
    expect(switches).toHaveLength(gates.length);
    switches.forEach((entry, index) => {
      const expected = zhCN.releaseEnvironmentPolicy.gateSwitchAria
        .replace('{env}', '生产')
        .replace('{action}', zhCN.releaseEnvironmentPolicy.actionEnum.EXECUTE_RELEASE)
        .replace('{gate}', gates[index]);
      expect(entry.querySelector('[role="switch"]')?.getAttribute('aria-label')).toBe(expected);
    });

    // Every gate column carries what turning it on means, and the tag column reads as text, not colour
    expect(document.querySelectorAll('.el-table__header .gate-help')).toHaveLength(5);
    expect(rowCell(0, 7)).toBe(zhCN.common.yes);
  });

  it('leads with the policy itself and keeps the id queryable as its own column', async () => {
    mockedList.mockResolvedValue(storedPolicies());
    await mountView();

    const headers = headerLabels();
    expect(headers.indexOf(zhCN.common.id)).toBeGreaterThan(headers.indexOf(zhCN.releaseEnvironmentPolicy.freezeWindowEnabled));
    expect(headers.indexOf(zhCN.common.id)).toBeLessThan(headers.indexOf(zhCN.common.actions));

    // The policy itself leads the table, and the id every row is written under is still on screen
    expect(visibleRows()).toEqual(['生产|执行发布|高', '预发|执行发布|全部', '预发|执行回滚|严重']);
    expect([rowCell(0, 8), rowCell(1, 8), rowCell(2, 8)]).toEqual(['11', '12', '13']);
  });
});