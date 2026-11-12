import { afterEach, beforeEach, describe, expect, it, vi, type MockInstance } from 'vitest';
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils';
import ElementPlus, { ElMessage, ElTooltip } from 'element-plus';
import elementEn from 'element-plus/es/locale/lang/en';
import elementZhCN from 'element-plus/es/locale/lang/zh-cn';
import { createPinia, setActivePinia } from 'pinia';
import { i18n, setLocale } from '@/i18n';
import zhCN from '@/i18n/locales/zh-CN';
import ApprovalCenterView from '../ApprovalCenterView.vue';
import { useAuthStore } from '@/store/auth';
import type { PageResponse } from '@/types/api';
import {
  listApprovalRequests,
  approveRequest,
  rejectRequest,
  cancelRequest,
  listApprovalPolicies,
  upsertApprovalPolicy,
  type ApprovalPolicy,
  type ApprovalRequest,
  type ApprovalRequestQuery,
} from '@/api/approval';

/**
 * 审批中心的操作反馈与上下文自测：
 * - 审批、拒绝、取消共用一个在途控制：同一申请不会被打出两次请求；
 * - 在途期间弹窗冻结（不可改备注、不可取消、不可关闭），列表刷新也不会移动被决策的对象；
 * - 失败只由共享拦截器提示一次，页面按服务端重新读取的结果决定下一步，绝不自动重发；
 * - 状态未确认的申请先刷新再操作，未确认期间不提供操作入口；
 * - 环境名/枚举未知值保持可读，筛选与重置职责分明且不丢草稿，空列表与筛选无结果分开表达。
 * 只做组件级自测：网络被 mock，不连接真实后端。
 *
 * 拒绝必须带原因这一条，断言的是「没有请求发出」而不是表单项的提示文案：本环境的
 * 表单校验不生效（见下），提示文案需要在浏览器里核对，属于未验证项。
 */
vi.mock('@/api/approval', () => ({
  listApprovalRequests: vi.fn(),
  approveRequest: vi.fn(),
  rejectRequest: vi.fn(),
  cancelRequest: vi.fn(),
  listApprovalPolicies: vi.fn(),
  upsertApprovalPolicy: vi.fn(),
}));

const mockedList = vi.mocked(listApprovalRequests);
const mockedApprove = vi.mocked(approveRequest);
const mockedReject = vi.mocked(rejectRequest);
const mockedCancel = vi.mocked(cancelRequest);
const mockedPolicies = vi.mocked(listApprovalPolicies);
const mockedUpsertPolicy = vi.mocked(upsertApprovalPolicy);

/** One approval as the list endpoint answers it. */
function requestRow(overrides: Partial<ApprovalRequest> = {}): ApprovalRequest {
  return {
    id: 31,
    bizType: 'RELEASE_ORDER',
    bizId: 900,
    action: 'EXECUTE_RELEASE',
    status: 'PENDING',
    applicantId: 7,
    applicantName: '张三',
    requestPayload: JSON.stringify({ orderNo: 'RO-2026-001' }),
    createdAt: '2026-10-01T09:00:00',
    ...overrides,
  };
}

/**
 * The approvals the backend lists: a release order and a rollback order to decide on, one already
 * handled, and one whose business type and action this build does not name.
 */
function storedRequests(): ApprovalRequest[] {
  return [
    requestRow(),
    requestRow({
      id: 32,
      bizType: 'ROLLBACK_ORDER',
      bizId: 901,
      action: 'EXECUTE_ROLLBACK',
      requestPayload: JSON.stringify({ rollbackNo: 'RB-2026-002' }),
    }),
    requestRow({
      id: 33,
      bizId: 902,
      status: 'APPROVED',
      approverId: 9,
      approverName: '李四',
      approvedAt: '2026-10-02T10:30:00',
      approveNote: '同意',
      requestPayload: JSON.stringify({ orderNo: 'RO-2026-003' }),
    }),
    requestRow({
      id: 34,
      bizType: 'CUSTOM_BIZ',
      bizId: 903,
      action: 'CUSTOM_ACTION',
      applicantId: 8,
      applicantName: undefined,
      requestPayload: undefined,
    }),
  ];
}

function pageOf(rows: ApprovalRequest[]): PageResponse<ApprovalRequest> {
  return { pageNum: 1, pageSize: 10, total: rows.length, records: rows.map(row => ({ ...row })) };
}

/**
 * The list endpoint over one set of stored approvals. Every read answers with the state the store
 * holds at that moment, and the params of each read are kept as they were sent - the page binds a
 * live object, so what it sent can only be read from a copy taken when it sent it.
 */
function approvalEndpoint(rows: ApprovalRequest[]) {
  const stored = rows.map(row => ({ ...row }));
  const reads: ApprovalRequestQuery[] = [];
  mockedList.mockImplementation(async (params: ApprovalRequestQuery) => {
    reads.push({ ...params });
    return pageOf(stored);
  });
  return { stored, reads };
}

/** A write the test holds open and then settles, the way a slow endpoint does. */
function heldWrite() {
  let accept!: () => void;
  let refuse!: (err: unknown) => void;
  const promise = new Promise<void>((resolve, reject) => {
    accept = resolve;
    refuse = reject;
  });
  return { promise, accept, refuse };
}

/**
 * A list read the test holds open and then answers with the state the server holds at that moment.
 * A read that is out is a read that has answered nothing yet, so the page cannot count it as an answer.
 */
function heldRead() {
  let settleRead!: (rows: ApprovalRequest[]) => void;
  let refuse!: (err: unknown) => void;
  const promise = new Promise<PageResponse<ApprovalRequest>>((resolve, reject) => {
    settleRead = (rows: ApprovalRequest[]) => resolve(pageOf(rows));
    refuse = reject;
  });
  return { promise, answerWith: (rows: ApprovalRequest[]) => settleRead(rows), refuse };
}

/** An error as axios hands one to the caller: already reported by the shared interceptor. */
function axiosLikeError(message: string) {
  return Object.assign(new Error(message), { isAxiosError: true });
}

/** An error as axios hands one over when no answer arrived at all. */
function transportError(message: string) {
  return Object.assign(new Error(message), { isAxiosError: true, response: undefined });
}

let wrapper: VueWrapper | undefined;
let successToast: MockInstance;
let warningToast: MockInstance;
let errorToast: MockInstance;
let consoleError: MockInstance;
let consoleLog: MockInstance;

async function mountView(options: { authorities?: string[]; userId?: number } = {}) {
  setLocale('zh-CN');
  const pinia = createPinia();
  setActivePinia(pinia);
  const authStore = useAuthStore();
  authStore.currentUser = {
    userId: options.userId ?? 7,
    username: 'tester',
    displayName: 'tester',
    authorities: options.authorities ?? ['SYSTEM_ADMIN'],
  };

  wrapper = mount(ApprovalCenterView, {
    global: { plugins: [ElementPlus, i18n, pinia] },
    attachTo: document.body,
  });
  await flushPromises();
}

/** Puts the page back on a clean document, so a second case starts from the same place. */
async function remount(options: { authorities?: string[]; userId?: number } = {}) {
  wrapper?.unmount();
  wrapper = undefined;
  document.body.innerHTML = '';
  await mountView(options);
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

/**
 * Takes a control the way a keyboard does - focus, then the key that activates what holds the focus -
 * so what is asserted does not depend on where a pointer happens to land.
 */
async function activateByKeyboard(button: HTMLButtonElement) {
  button.focus();
  button.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', code: 'Enter', bubbles: true }));
  button.dispatchEvent(new KeyboardEvent('keyup', { key: 'Enter', code: 'Enter', bubbles: true }));
  button.click();
  await settle();
}

/** Lets every pending microtask and one macrotask run: Element Plus settles its form on the next tick. */
async function settle() {
  await flushPromises();
  await flushPromises();
  await new Promise(resolve => setTimeout(resolve, 0));
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

/**
 * The pane of one tab. Element Plus renders every tab pane at once, so each helper below reads
 * inside the pane it belongs to instead of matching a page-wide selector that the other pane's
 * markup would also satisfy.
 */
function paneNamed(name: 'requests' | 'policies'): HTMLElement {
  const found = document.querySelector<HTMLElement>(`#pane-${name}`);
  if (!found) throw new Error(`the ${name} pane is not on screen`);
  return found;
}

function requestsCard(): HTMLElement {
  return q<HTMLElement>('.table-card', paneNamed('requests'));
}

function policiesCard(): HTMLElement {
  return q<HTMLElement>('.table-card', paneNamed('policies'));
}

function rowsOf(card: HTMLElement): HTMLElement[] {
  return Array.from(card.querySelectorAll('.el-table__row')) as HTMLElement[];
}

function rows(): HTMLElement[] {
  return rowsOf(requestsCard());
}

function rowCellOf(card: HTMLElement, rowIndex: number, columnIndex: number): string {
  const cell = rowsOf(card)[rowIndex].querySelectorAll('td')[columnIndex];
  if (!cell) throw new Error(`cell ${rowIndex}/${columnIndex} is not on screen`);
  return (cell.textContent || '').trim();
}

function rowCell(rowIndex: number, columnIndex: number): string {
  return rowCellOf(requestsCard(), rowIndex, columnIndex);
}

function headersOf(card: HTMLElement): string[] {
  return Array.from(card.querySelectorAll('.el-table__header th .cell')).map(
    cell => (cell.textContent || '').trim(),
  );
}

/** The entries of a row's action column, read from the buttons the page renders there. */
function actionCell(rowIndex: number): HTMLElement {
  const cell = rows()[rowIndex].querySelectorAll('td')[10];
  if (!cell) throw new Error(`the action cell of row ${rowIndex} is not on screen`);
  return cell as HTMLElement;
}

function actionLabels(rowIndex: number): string[] {
  return Array.from(actionCell(rowIndex).querySelectorAll('button')).map(
    button => (button.textContent || '').trim(),
  );
}

function rowButton(rowIndex: number, label: string): HTMLButtonElement {
  return buttonByText(actionCell(rowIndex), label);
}

/** The status cell of a row: what it says in words, not only in colour. */
function statusTags(rowIndex: number): string[] {
  const cell = rows()[rowIndex].querySelectorAll('td')[5];
  if (!cell) throw new Error(`the status cell of row ${rowIndex} is not on screen`);
  return Array.from(cell.querySelectorAll('.el-tag')).map(tag => (tag.textContent || '').trim());
}

function filterBar(): HTMLElement {
  return q<HTMLElement>('.filter-bar', paneNamed('requests'));
}

/** The pagination bar of the requests list: the wording of it is not the page's own vocabulary. */
function paginationBar(): HTMLElement {
  return q<HTMLElement>('.ih-pagination-bar', paneNamed('requests'));
}

function paginationText(): string {
  return (paginationBar().textContent || '').replace(/\s+/g, ' ').trim();
}

/** What a pagination bar says in one language, read out of that language's own vocabulary. */
function paginationWording(
  locale: { el: { pagination: { total: string; goto: string } } },
  total: number,
) {
  return {
    total: locale.el.pagination.total.replace('{total}', String(total)),
    goto: locale.el.pagination.goto,
  };
}

/** The filter bar of the policy tab. */
function policiesBar(): HTMLElement {
  return q<HTMLElement>('.filter-bar', paneNamed('policies'));
}

function filterSelect(index: number): HTMLElement {
  const selects = Array.from(filterBar().querySelectorAll<HTMLElement>('.el-select'));
  const found = selects[index];
  if (!found) throw new Error(`filter select ${index} is not on screen`);
  return found;
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

/** The applicant filter, the only input of the requests filter bar that is not part of a select. */
function applicantInput(): HTMLInputElement {
  const found = Array.from(
    paneNamed('requests').querySelectorAll<HTMLInputElement>('input'),
  ).find(input => !!input.closest('.filter-bar') && !input.closest('.el-select'));
  if (!found) throw new Error('the applicant filter is not on screen');
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

/** Opens the given select and takes the option carrying that label. */
async function chooseOption(select: HTMLElement, label: string) {
  await click(q<HTMLElement>('.el-select__wrapper', select));
  await waitUntil(
    () => selectOptionsOnScreen().some(item => optionLabel(item) === label),
    `no ${label} option`,
  );
  const option = selectOptionsOnScreen().find(item => optionLabel(item) === label);
  if (!option) throw new Error(`option not found: ${label}`);
  await click(option);
}

/** The empty state the requests table renders in place of rows. */
function emptyState(): HTMLElement {
  return q<HTMLElement>('.empty-state', paneNamed('requests'));
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
  const found = q<HTMLElement>('.read-error', paneNamed('requests')).querySelector(selector);
  if (!found) throw new Error(`the read failure carries no ${selector}`);
  return (found.textContent || '').trim();
}

/** The decision dialog: the one whose title names the decision it is about to take. */
function actionDialog(): HTMLElement {
  const titles = [
    zhCN.approval.actionDialogTitle.approve,
    zhCN.approval.actionDialogTitle.reject,
    zhCN.approval.actionDialogTitle.cancel,
  ];
  const found = Array.from(document.querySelectorAll<HTMLElement>('.el-dialog')).find(dialog =>
    titles.includes((dialog.querySelector('.el-dialog__title')?.textContent || '').trim()),
  );
  if (!found) throw new Error('the decision dialog is not on screen');
  return found;
}

/** Whether the decision dialog is on screen right now; a closed dialog stays in the DOM, only hidden. */
function actionDialogOpen(): boolean {
  const dialog = Array.from(document.querySelectorAll<HTMLElement>('.el-dialog')).find(entry =>
    [zhCN.approval.actionDialogTitle.approve, zhCN.approval.actionDialogTitle.reject, zhCN.approval.actionDialogTitle.cancel]
      .includes((entry.querySelector('.el-dialog__title')?.textContent || '').trim()),
  );
  if (!dialog) return false;
  const overlay = dialog.closest('.el-overlay') as HTMLElement | null;
  return !!overlay && overlay.style.display !== 'none';
}

function dialogTitle(): string {
  return (actionDialog().querySelector('.el-dialog__title')?.textContent || '').trim();
}

/** The approval the dialog was opened for, as one line of label and value pairs. */
function actionContextText(): string {
  const context = actionDialog().querySelector('.action-context');
  if (!context) throw new Error('the decision dialog shows no context');
  return (context.textContent || '').replace(/\s+/g, ' ').trim();
}

function actionImpactText(): string {
  const impact = actionDialog().querySelector('.action-impact');
  if (!impact) throw new Error('the decision dialog explains no impact');
  return (impact.textContent || '').replace(/\s+/g, ' ').trim();
}

function actionNoticeText(): string {
  const notice = actionDialog().querySelector('.action-notice');
  if (!notice) throw new Error('the decision dialog carries no verdict');
  return (notice.textContent || '').replace(/\s+/g, ' ').trim();
}

/**
 * One line with every space removed. Element Plus renders a descriptions label and its value in two
 * adjacent cells with no space between them, so a phrase has to be compared without depending on
 * where the markup happens to put whitespace.
 */
function compact(value: string): string {
  return value.replace(/\s+/g, '');
}

function actionNoteTextarea(): HTMLTextAreaElement {
  const found = actionDialog().querySelector('textarea');
  if (!found) throw new Error('the decision dialog takes no note');
  return found as HTMLTextAreaElement;
}

/** The submit entry of the dialog right now, whatever decision it is about to take. */
function dialogSubmitButton(): HTMLButtonElement {
  const labels = [
    zhCN.approval.actionDialogSubmit.approve,
    zhCN.approval.actionDialogSubmit.reject,
    zhCN.approval.actionDialogSubmit.cancel,
  ];
  const found = Array.from(actionDialog().querySelectorAll('button')).find(button =>
    labels.includes((button.textContent || '').trim()),
  );
  if (!found) throw new Error('the decision dialog offers no submit entry');
  return found as HTMLButtonElement;
}

function dialogCancelButton(): HTMLButtonElement {
  return buttonByText(actionDialog(), zhCN.common.cancel);
}

/** The dialog's own close control; the dialog carries none while a write freezes it. */
function dialogCloseButton(): HTMLElement | null {
  return actionDialog().querySelector('.el-dialog__headerbtn');
}

/** The element a click outside the dialog lands on: the dialog carries its mask handler here. */
function dialogMask(): HTMLElement {
  const parent = actionDialog().parentElement as HTMLElement | null;
  if (!parent) throw new Error('the dialog has no mask to click');
  return parent;
}

async function clickDialogMask() {
  const mask = dialogMask();
  mask.dispatchEvent(new MouseEvent('mousedown', { bubbles: true }));
  mask.dispatchEvent(new MouseEvent('mouseup', { bubbles: true }));
  mask.dispatchEvent(new MouseEvent('click', { bubbles: true }));
  await flushPromises();
}

async function pressEscape() {
  document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', code: 'Escape', bubbles: true }));
  await flushPromises();
}

/** Opens the decision dialog for a row the way the operator does, and waits for it to arrive. */
async function openDecision(kind: 'approve' | 'reject' | 'cancel', rowIndex: number) {
  await click(rowButton(rowIndex, zhCN.approval.actions[kind]));
  await waitUntil(() => actionDialogOpen(), `the ${kind} dialog did not open`);
}

function tabLabels(): string[] {
  return Array.from(document.querySelectorAll('.el-tabs__item')).map(
    item => (item.textContent || '').trim(),
  );
}

async function openTab(label: string) {
  const tab = Array.from(document.querySelectorAll<HTMLElement>('.el-tabs__item')).find(
    item => (item.textContent || '').trim() === label,
  );
  if (!tab) throw new Error(`tab not found: ${label}`);
  await click(tab);
  await settle();
}

/** The enable switch of a policy row, read from where Element Plus puts its accessible name. */
function switchInput(card: HTMLElement, rowIndex: number): HTMLInputElement {
  const found = rowsOf(card)[rowIndex].querySelector<HTMLInputElement>('input[role="switch"]');
  if (!found) throw new Error(`the switch of policy row ${rowIndex} is not on screen`);
  return found;
}

beforeEach(() => {
  setLocale('zh-CN');
  (globalThis as unknown as { ResizeObserver: unknown }).ResizeObserver = class {
    observe() {}
    unobserve() {}
    disconnect() {}
  };
  mockedList.mockReset();
  mockedApprove.mockReset();
  mockedReject.mockReset();
  mockedCancel.mockReset();
  mockedPolicies.mockReset();
  mockedUpsertPolicy.mockReset();
  mockedPolicies.mockResolvedValue([]);
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

describe('ApprovalCenterView', () => {
  it('lists the approvals the endpoint answers with, in the shapes that endpoint uses', async () => {
    const store = approvalEndpoint(storedRequests());
    await mountView();

    expect(rows()).toHaveLength(4);
    // Every header reads from the vocabulary the console keeps, including the actions column
    expect(headersOf(requestsCard())).toEqual([
      zhCN.common.id,
      zhCN.approval.bizType,
      zhCN.approval.bizId,
      zhCN.approval.businessSummary,
      zhCN.approval.action,
      zhCN.approval.status,
      zhCN.approval.applicantName,
      zhCN.approval.approverName,
      zhCN.approval.createdAt,
      zhCN.approval.approvedAt,
      zhCN.common.actions,
    ]);

    // The business type, the action and the summary are read out of what the endpoint sent
    expect([rowCell(0, 1), rowCell(0, 2), rowCell(0, 3), rowCell(0, 4), rowCell(0, 5)]).toEqual([
      zhCN.approval.bizTypeEnum.RELEASE_ORDER,
      '900',
      'RO-2026-001',
      zhCN.approval.actionEnum.EXECUTE_RELEASE,
      zhCN.approval.approvalStatus.PENDING,
    ]);
    // A name nothing answers for stays the identifier it is, and a missing approver reads as none
    expect(rowCell(3, 6)).toBe('8');
    expect(rowCell(0, 7)).toBe('-');
    expect(rowCell(2, 7)).toBe('李四');

    // A business type and an action this build does not name stay readable as the values themselves
    expect(rowCell(3, 1)).toBe('CUSTOM_BIZ');
    expect(rowCell(3, 4)).toBe('CUSTOM_ACTION');
    expect(rowCell(3, 3)).toBe('-');

    // A handled approval shows when it was handled; a pending one shows none
    expect(rowCell(2, 9)).not.toBe('-');
    expect(rowCell(0, 9)).toBe('-');

    // The two entries only a pending approval carries are the ones a pending approval shows
    expect(actionLabels(0)).toEqual([
      zhCN.approval.viewDetail,
      zhCN.approval.actions.approve,
      zhCN.approval.actions.reject,
    ]);
    expect(actionLabels(2)).toEqual([zhCN.approval.viewDetail]);
    expect(store.reads[0]).toMatchObject({ pageNum: 1, pageSize: 10, status: '', bizType: '' });
  });

  it('words the pagination in the console language, and follows a switch to the other one at once', async () => {
    approvalEndpoint(storedRequests());
    await mountView();

    // Element Plus words its pagination out of its own vocabulary, so the page hands it the one that
    // matches the console: a Chinese console reads a Chinese bar, not the library's own default
    const zh = paginationWording(elementZhCN, 4);
    const en = paginationWording(elementEn, 4);
    expect(paginationText()).toContain(zh.total);
    expect(paginationText()).toContain(zh.goto);
    expect(paginationText()).not.toContain(en.goto);

    // Switching the language switches the bar with it - at once, and without reading the list again
    setLocale('en-US');
    await settle();
    expect(paginationText()).toContain(en.total);
    expect(paginationText()).toContain(en.goto);
    expect(paginationText()).not.toContain(zh.goto);
    expect(rowsOf(requestsCard())).toHaveLength(4);

    // And back, so neither language is the one the bar is stuck on
    setLocale('zh-CN');
    await settle();
    expect(paginationText()).toContain(zh.total);
    expect(paginationText()).toContain(zh.goto);
    expect(mockedList).toHaveBeenCalledTimes(1);
  });

  it('shows the real business the decision is about, and tells the operator what that decision does', async () => {
    approvalEndpoint(storedRequests());
    await mountView();

    await openDecision('approve', 0);
    expect(dialogTitle()).toBe(zhCN.approval.actionDialogTitle.approve);
    expect(dialogSubmitButton().textContent?.trim()).toBe(zhCN.approval.actionDialogSubmit.approve);

    // The context is the approval itself: what the API sent, and nothing invented about it
    const context = actionContextText();
    expect(context).toContain(compact(`${zhCN.approval.bizType} ${zhCN.approval.bizTypeEnum.RELEASE_ORDER}`));
    expect(context).toContain(compact(`${zhCN.approval.bizId} 900`));
    expect(context).toContain(compact(`${zhCN.approval.businessSummary} RO-2026-001`));
    expect(context).toContain(compact(`${zhCN.approval.action} ${zhCN.approval.actionEnum.EXECUTE_RELEASE}`));
    expect(context).toContain(compact(`${zhCN.approval.status} ${zhCN.approval.approvalStatus.PENDING}`));
    expect(context).not.toMatch(/环境|应用|版本|主机/);

    // Approving a release starts its execution, and that is said before it is confirmed
    expect(actionImpactText()).toContain(zhCN.approval.impactTitle);
    expect(actionImpactText()).toContain(zhCN.approval.impactApproveRelease);

    await click(dialogCancelButton());
    await openDecision('approve', 1);
    expect(actionImpactText()).toContain(zhCN.approval.impactApproveRollback);
    expect(actionContextText()).toContain(compact(`${zhCN.approval.businessSummary} RB-2026-002`));

    await click(dialogCancelButton());
    await openDecision('reject', 1);
    expect(dialogTitle()).toBe(zhCN.approval.actionDialogTitle.reject);
    expect(dialogSubmitButton().textContent?.trim()).toBe(zhCN.approval.actionDialogSubmit.reject);
    expect(q<HTMLElement>('.action-note .el-form-item__label', actionDialog()).textContent?.trim()).toBe(
      zhCN.approval.rejectNote,
    );
    expect(actionImpactText()).toContain(zhCN.approval.impactReject);
  });

  it('sends one request for one approval however fast submit is pressed, and freezes the dialog while it is out', async () => {
    const store = approvalEndpoint(storedRequests());
    const write = heldWrite();
    mockedApprove.mockReturnValue(write.promise);
    await mountView();

    await openDecision('approve', 0);
    const submit = dialogSubmitButton();
    submit.click();
    submit.click();
    await settle();

    // One approval, one request: the second press lands on a control that is already claimed
    expect(mockedApprove).toHaveBeenCalledTimes(1);
    expect(mockedApprove).toHaveBeenCalledWith(31, undefined);

    // While it is out the dialog belongs to the write: no note, no cancel, no close, no Escape, no mask
    expect(actionNoteTextarea().disabled).toBe(true);
    expect(dialogCancelButton().disabled).toBe(true);
    expect(dialogCloseButton()).toBeNull();
    expect(dialogSubmitButton().classList.contains('is-loading')).toBe(true);
    await pressEscape();
    expect(actionDialogOpen()).toBe(true);
    await clickDialogMask();
    expect(actionDialogOpen()).toBe(true);

    // The row explains why its other entry is closed instead of leaving it silently dead
    expect(rowButton(0, zhCN.approval.actions.reject).disabled).toBe(true);
    const busyHint = wrapper!.findAllComponents(ElTooltip).find(
      tip => tip.props('content') === zhCN.approval.actionBusyHint,
    );
    expect(busyHint).toBeTruthy();
    expect(busyHint!.props('disabled')).toBe(false);

    // The write is accepted, so the read that follows answers with the moved-on approval
    store.stored[0] = { ...store.stored[0], status: 'APPROVED', approverId: 9, approverName: '李四' };
    write.accept();
    await settle();

    expect(successToast).toHaveBeenCalledWith(zhCN.approval.messages.approveSuccess);
    expect(actionDialogOpen()).toBe(false);
    // The approval went through, so the entries that would send it again are gone with it
    expect(actionLabels(0)).toEqual([zhCN.approval.viewDetail]);
  });

  it('holds the page while a write is out, so no other approval is taken up', async () => {
    const store = approvalEndpoint(storedRequests());
    const write = heldWrite();
    mockedApprove.mockReturnValue(write.promise);
    await mountView();

    await openDecision('approve', 0);
    await click(dialogSubmitButton());
    expect(mockedApprove).toHaveBeenCalledTimes(1);

    // The objects behind the dialog cannot be switched while the one write is unfinished
    await click(rowButton(1, zhCN.approval.actions.approve));
    await click(rowButton(1, zhCN.approval.actions.reject));
    expect(actionDialogOpen()).toBe(true);
    expect(dialogTitle()).toBe(zhCN.approval.actionDialogTitle.approve);
    expect(actionContextText()).toContain(compact(`${zhCN.approval.bizId} 900`));

    store.stored[0] = { ...store.stored[0], status: 'APPROVED' };
    write.accept();
    await settle();
    expect(mockedApprove).toHaveBeenCalledTimes(1);

    // The control is released with the write, so the page is never left locked
    await openDecision('approve', 1);
    expect(actionContextText()).toContain(compact(`${zhCN.approval.bizId} 901`));
    expect(dialogSubmitButton().disabled).toBe(false);
  });

  it('reports only what the shared interceptor does not, and reads the list back after every refusal', async () => {
    const store = approvalEndpoint(storedRequests());
    mockedApprove.mockRejectedValue(axiosLikeError('后端拒绝：状态不允许'));
    await mountView();

    await openDecision('approve', 0);
    await type(actionNoteTextarea(), '先确认版本');
    await click(dialogSubmitButton());
    await settle();

    // The interceptor has reported this failure, so the page adds nothing to it
    expect(errorToast).not.toHaveBeenCalled();
    expect(store.reads).toHaveLength(2);
    // The server still answers pending, so nothing moved: the note stands and it can be sent again
    expect(actionDialogOpen()).toBe(true);
    expect(actionNoticeText()).toContain(zhCN.approval.messages.stillPending);
    expect(actionNoteTextarea().value).toBe('先确认版本');
    expect(dialogSubmitButton().disabled).toBe(false);
    expect(mockedApprove).toHaveBeenCalledTimes(1);

    // A failure the interceptor never saw is still reported, with the message it carried
    mockedApprove.mockRejectedValue(new Error('网络库之外的异常'));
    await click(dialogSubmitButton());
    await settle();
    expect(errorToast).toHaveBeenCalledTimes(1);
    expect(errorToast).toHaveBeenCalledWith('网络库之外的异常');
    expect(store.reads).toHaveLength(3);
  });

  it('reads the outcome out of the server, never out of the failure text', async () => {
    const store = approvalEndpoint(storedRequests());
    mockedApprove.mockRejectedValue(axiosLikeError('网关超时，结果未知'));
    await mountView();
    await openDecision('approve', 0);

    // The request failed on the way back, but the write itself landed: the read is what settles it
    store.stored[0] = { ...store.stored[0], status: 'APPROVED', approverId: 9, approverName: '李四' };
    await click(dialogSubmitButton());
    await settle();

    expect(warningToast).toHaveBeenCalledWith(zhCN.approval.messages.failedButHandled);
    expect(actionDialogOpen()).toBe(false);
    expect(actionLabels(0)).toEqual([zhCN.approval.viewDetail]);
    expect(mockedApprove).toHaveBeenCalledTimes(1);
  });

  it('closes the decision entries of an approval whose result is not confirmed, and reopens them only on a read that answers for it', async () => {
    approvalEndpoint(storedRequests());
    mockedApprove.mockRejectedValue(transportError('连接中断'));
    await mountView();
    await openDecision('approve', 0);

    // Nothing answered for that approval - not the write, and not the read that would settle it
    mockedList.mockRejectedValueOnce(axiosLikeError('列表不可用'));
    await click(dialogSubmitButton());
    await settle();

    expect(warningToast).toHaveBeenCalledWith(zhCN.approval.messages.resultUnconfirmedTimeout);
    expect(actionDialogOpen()).toBe(false);
    // The failed read is said out loud, and the state it left behind is not called confirmed
    expect(readErrorBody('.el-alert__title')).toBe(zhCN.common.readFailed);
    expect(statusTags(0)).toContain(zhCN.approval.statusUnconfirmed);
    expect(actionLabels(0)).toEqual([zhCN.approval.viewDetail, zhCN.approval.refreshStatus]);
    expect(mockedApprove).toHaveBeenCalledTimes(1);

    // Only a read that answers for it settles it - and the entries open again with that answer
    await click(rowButton(0, zhCN.approval.refreshStatus));
    await settle();
    expect(successToast).toHaveBeenCalledWith(zhCN.approval.messages.statusRefreshed);
    expect(statusTags(0)).not.toContain(zhCN.approval.statusUnconfirmed);
    expect(actionLabels(0)).toEqual([
      zhCN.approval.viewDetail,
      zhCN.approval.actions.approve,
      zhCN.approval.actions.reject,
    ]);
    // Nothing was sent on its behalf in the meantime
    expect(mockedApprove).toHaveBeenCalledTimes(1);
  });

  it('keeps an approval unconfirmed when a read does not answer for it', async () => {
    const store = approvalEndpoint(storedRequests());
    mockedApprove.mockRejectedValue(transportError('连接中断'));
    await mountView();
    await openDecision('approve', 0);

    mockedList.mockRejectedValueOnce(axiosLikeError('列表不可用'));
    await click(dialogSubmitButton());
    await settle();
    expect(statusTags(0)).toContain(zhCN.approval.statusUnconfirmed);

    // The read that follows succeeds but says nothing about that approval, so it stays unconfirmed
    store.stored.splice(0, 1);
    await click(rowButton(0, zhCN.approval.refreshStatus));
    await settle();
    expect(warningToast).toHaveBeenCalledWith(zhCN.approval.messages.statusStillUnconfirmed);
    expect(mockedApprove).toHaveBeenCalledTimes(1);
    expect(errorToast).not.toHaveBeenCalled();
  });

  it('marks an accepted write that could not be read back as unconfirmed', async () => {
    const store = approvalEndpoint(storedRequests());
    const write = heldWrite();
    mockedApprove.mockReturnValue(write.promise);
    await mountView();
    await openDecision('approve', 0);
    await click(dialogSubmitButton());

    // The write is accepted, but the read that would confirm it fails
    mockedList.mockRejectedValueOnce(axiosLikeError('列表不可用'));
    store.stored[0] = { ...store.stored[0], status: 'APPROVED' };
    write.accept();
    await settle();

    expect(successToast).toHaveBeenCalledWith(zhCN.approval.messages.approveSuccess);
    expect(warningToast).toHaveBeenCalledWith(zhCN.approval.messages.acceptedButRefreshFailed);
    expect(statusTags(0)).toContain(zhCN.approval.statusUnconfirmed);
    expect(actionLabels(0)).toEqual([zhCN.approval.viewDetail, zhCN.approval.refreshStatus]);
  });

  it('settles a landed write by a read alone, and holds every entry that could write it again until one answers', async () => {
    const store = approvalEndpoint(storedRequests());
    const write = heldWrite();
    const read = heldRead();
    mockedApprove.mockReturnValue(write.promise);
    await mountView();

    await openDecision('approve', 0);
    mockedList.mockImplementationOnce(() => read.promise);
    await click(dialogSubmitButton());
    write.accept();
    await settle();

    // The write landed and the read that would settle it is still out. Closing the dialog is not what
    // settles it: the approval is held as not confirmed, and its row carries no decision entry at all.
    expect(successToast).toHaveBeenCalledWith(zhCN.approval.messages.approveSuccess);
    expect(actionDialogOpen()).toBe(false);
    expect(mockedList).toHaveBeenCalledTimes(2);
    expect(statusTags(0)).toContain(zhCN.approval.statusUnconfirmed);
    expect(actionLabels(0)).toEqual([zhCN.approval.viewDetail, zhCN.approval.refreshStatus]);

    // Neither that approval nor any other can be taken up across that window
    await click(rowButton(1, zhCN.approval.actions.approve));
    expect(actionDialogOpen()).toBe(false);

    // Every entry the row does show is taken the way a keyboard takes one. The entries that decide it
    // are not among them, and the read it offers is the page's own read and not a way to write.
    for (const entry of Array.from(actionCell(0).querySelectorAll('button'))) {
      await activateByKeyboard(entry as HTMLButtonElement);
    }
    expect(mockedApprove).toHaveBeenCalledTimes(1);
    expect(mockedReject).not.toHaveBeenCalled();
    expect(mockedCancel).not.toHaveBeenCalled();
    expect(mockedList).toHaveBeenCalledTimes(2);
    expect(statusTags(0)).toContain(zhCN.approval.statusUnconfirmed);
    expect(actionLabels(0)).toEqual([zhCN.approval.viewDetail, zhCN.approval.refreshStatus]);
    expect(warningToast).not.toHaveBeenCalled();

    // The read answers at last, with the approval moved on: that answer is what closes the entries,
    // and what is left of the row is one entry that cannot write anything a second time.
    store.stored[0] = { ...store.stored[0], status: 'APPROVED', approverId: 9, approverName: '李四' };
    read.answerWith(store.stored);
    await settle();

    expect(statusTags(0)).not.toContain(zhCN.approval.statusUnconfirmed);
    expect(actionLabels(0)).toEqual([zhCN.approval.viewDetail]);
    expect(warningToast).not.toHaveBeenCalled();
    expect(mockedApprove).toHaveBeenCalledTimes(1);
  });

  it('never counts a read a newer query replaced as the answer that settles an approval', async () => {
    const store = approvalEndpoint(storedRequests());
    const write = heldWrite();
    const replaced = heldRead();
    mockedApprove.mockReturnValue(write.promise);
    await mountView();

    await openDecision('approve', 0);
    mockedList.mockImplementationOnce(() => replaced.promise);
    await click(dialogSubmitButton());
    write.accept();
    await settle();
    expect(mockedList).toHaveBeenCalledTimes(2);
    expect(statusTags(0)).toContain(zhCN.approval.statusUnconfirmed);

    // The operator reads the list again while that read is still out, and this newer read fails
    mockedList.mockRejectedValueOnce(axiosLikeError('列表不可用'));
    await click(buttonByText(filterBar(), zhCN.common.search));
    await settle();
    expect(mockedList).toHaveBeenCalledTimes(3);
    expect(readErrorBody('.el-alert__title')).toBe(zhCN.common.readFailed);
    expect(statusTags(0)).toContain(zhCN.approval.statusUnconfirmed);

    // The replaced read answers now - a state a newer query has already taken over from, and a state
    // that still says the approval is pending. A replaced response settles nothing: it is not applied
    // to the page, it is not read as a good read, and it is not the answer that confirms an approval.
    replaced.answerWith(storedRequests().slice(0, 2));
    await settle();

    expect(rowsOf(requestsCard())).toHaveLength(4);
    expect(readErrorBody('.el-alert__title')).toBe(zhCN.common.readFailed);
    expect(statusTags(0)).toContain(zhCN.approval.statusUnconfirmed);
    expect(actionLabels(0)).toEqual([zhCN.approval.viewDetail, zhCN.approval.refreshStatus]);
    expect(warningToast).toHaveBeenCalledWith(zhCN.approval.messages.acceptedButRefreshFailed);
    expect(mockedApprove).toHaveBeenCalledTimes(1);
  });

  it('requires the reason a rejection is refused for, and sends one request however fast submit is pressed', async () => {
    const store = approvalEndpoint(storedRequests());
    const write = heldWrite();
    await mountView();
    await openDecision('reject', 0);

    // A rejection carries a reason, so an empty note sends nothing. The rule is read out of the
    // draft rather than out of the validator, so what is asserted here is that no request leaves.
    await click(dialogSubmitButton());
    await settle();
    expect(mockedReject).not.toHaveBeenCalled();
    expect(store.reads).toHaveLength(1);

    // A note of spaces is still no reason
    await type(actionNoteTextarea(), '   ');
    await click(dialogSubmitButton());
    await settle();
    expect(mockedReject).not.toHaveBeenCalled();

    // A refused form leaves the dialog usable, not frozen, and it was never out of the page's hands
    expect(actionDialogOpen()).toBe(true);
    expect(dialogSubmitButton().disabled).toBe(false);
    expect(dialogCancelButton().disabled).toBe(false);
    expect(actionNoteTextarea().disabled).toBe(false);

    mockedReject.mockReturnValue(write.promise);
    await type(actionNoteTextarea(), '  版本有问题  ');
    const submit = dialogSubmitButton();
    submit.click();
    submit.click();
    await settle();

    expect(mockedReject).toHaveBeenCalledTimes(1);
    expect(mockedReject).toHaveBeenCalledWith(31, '版本有问题');

    store.stored[0] = { ...store.stored[0], status: 'REJECTED', approverId: 9, approverName: '李四' };
    write.accept();
    await settle();
    expect(successToast).toHaveBeenCalledWith(zhCN.approval.messages.rejectSuccess);
    expect(actionLabels(0)).toEqual([zhCN.approval.viewDetail]);
  });

  it('offers the cancel entry to the applicant who raised the request, and to nobody else', async () => {
    approvalEndpoint(storedRequests());
    await mountView({ authorities: [], userId: 7 });

    expect(actionLabels(0)).toEqual([zhCN.approval.viewDetail, zhCN.approval.actions.cancel]);
    // Another applicant's request is not the operator's to cancel, and a handled one carries no entry
    expect(actionLabels(3)).toEqual([zhCN.approval.viewDetail]);
    expect(actionLabels(2)).toEqual([zhCN.approval.viewDetail]);

    const write = heldWrite();
    mockedCancel.mockReturnValue(write.promise);
    await openDecision('cancel', 0);
    expect(dialogTitle()).toBe(zhCN.approval.actionDialogTitle.cancel);
    expect(dialogSubmitButton().textContent?.trim()).toBe(zhCN.approval.actionDialogSubmit.cancel);
    // Cancelling asks for no reason, so the dialog carries no note to fill in
    expect(actionDialog().querySelector('.action-note')).toBeNull();
    expect(actionImpactText()).toContain(zhCN.approval.impactCancel);

    await click(dialogSubmitButton());
    await settle();
    expect(mockedCancel).toHaveBeenCalledTimes(1);
    expect(mockedCancel).toHaveBeenCalledWith(31);
    expect(mockedApprove).not.toHaveBeenCalled();
    expect(mockedReject).not.toHaveBeenCalled();

    write.accept();
    await settle();
    expect(successToast).toHaveBeenCalledWith(zhCN.approval.cancelSuccess);
  });

  it('keeps the decision entries of an administrator only', async () => {
    approvalEndpoint(storedRequests());
    await mountView({ authorities: ['SYSTEM_ADMIN'], userId: 7 });

    expect(actionLabels(0)).toEqual([
      zhCN.approval.viewDetail,
      zhCN.approval.actions.approve,
      zhCN.approval.actions.reject,
    ]);
    expect(tabLabels()).toContain(zhCN.approval.policyManagement);
  });

  it('labels every filter, and reset clears the filters alone - never the draft', async () => {
    const store = approvalEndpoint(storedRequests());
    await mountView();

    expect(filterPlaceholder(0)).toBe(zhCN.approval.filterStatusPlaceholder);
    expect(filterPlaceholder(1)).toBe(zhCN.approval.filterBizTypePlaceholder);
    expect(applicantInput().placeholder).toBe(zhCN.approval.applicantId);

    // A modal holds the page behind it out of reach: its focus trap pulls focus back into itself, and
    // that blur closes a select's dropdown - so the filters are set before the decision is opened.
    await chooseOption(filterSelect(0), zhCN.approval.approvalStatus.PENDING);
    await type(applicantInput(), '7');
    await click(buttonByText(filterBar(), zhCN.common.search));
    expect(store.reads[store.reads.length - 1]).toMatchObject({
      pageNum: 1,
      status: 'PENDING',
      applicantId: '7',
    });

    await openDecision('approve', 0);
    await type(actionNoteTextarea(), '保留的草稿');

    // Search keeps its own job: it takes the filters as they are and leaves the decision alone
    await click(buttonByText(filterBar(), zhCN.common.resetFilters));
    await settle();
    expect(store.reads[store.reads.length - 1]).toMatchObject({
      pageNum: 1,
      status: '',
      bizType: '',
      applicantId: undefined,
    });
    expect(filterPlaceholder(0)).toBe(zhCN.approval.filterStatusPlaceholder);
    expect(filterPlaceholder(1)).toBe(zhCN.approval.filterBizTypePlaceholder);
    expect(applicantInput().value).toBe('');
    // Reset clears the filters and nothing else: the decision and its draft are untouched
    expect(actionDialogOpen()).toBe(true);
    expect(actionNoteTextarea().value).toBe('保留的草稿');
  });

  it('tells an empty list apart from a filter that matched nothing, and each offers its own way out', async () => {
    const reads: ApprovalRequestQuery[] = [];
    const rows = storedRequests();
    // The endpoint answers with no approvals until the list it is asked for carries rows again.
    let listHasRows = false;
    mockedList.mockImplementation(async (params: ApprovalRequestQuery) => {
      reads.push({ ...params });
      return pageOf(listHasRows ? rows : []);
    });
    await mountView();

    // Nothing was answered for this page at all, so it says so and offers a refresh
    expect(emptyText('.empty-title')).toBe(zhCN.common.noData);
    expect(emptyText('.empty-hint')).toBe(zhCN.common.noDataHint);
    expect(emptyActionLabel()).toBe(zhCN.common.refresh);
    await click(emptyStateButton());
    expect(reads).toHaveLength(2);

    // The same empty table behind a filter is a filter result, and it offers the way back
    await chooseOption(filterSelect(0), zhCN.approval.approvalStatus.PENDING);
    await settle();
    expect(emptyText('.empty-title')).toBe(zhCN.common.noMatch);
    expect(emptyText('.empty-hint')).toBe(zhCN.common.noMatchHint);
    expect(emptyActionLabel()).toBe(zhCN.common.clearFilters);

    // Back on the unfiltered list, where the approvals answer again
    listHasRows = true;
    await click(emptyStateButton());
    await settle();
    expect(rows).toHaveLength(4);
    expect(rowsOf(requestsCard())).toHaveLength(4);
    expect(filterPlaceholder(0)).toBe(zhCN.approval.filterStatusPlaceholder);
  });

  it('shows a failed read as a read failure, keeps the rows it had, and never calls it empty data', async () => {
    approvalEndpoint(storedRequests());
    await mountView();

    mockedList.mockRejectedValueOnce(axiosLikeError('列表不可用'));
    await click(buttonByText(filterBar(), zhCN.common.search));
    await settle();

    // The rows read before the failure stay on screen, and the failure is named as one
    expect(rowsOf(requestsCard())).toHaveLength(4);
    expect(readErrorBody('.el-alert__title')).toBe(zhCN.common.readFailed);
    expect(readErrorBody('.read-error-hint')).toBe(zhCN.common.readFailedHint);
    expect(paneNamed('requests').querySelector('.empty-state')).toBeNull();
    // Already reported by the shared interceptor
    expect(errorToast).not.toHaveBeenCalled();

    await click(buttonByText(q<HTMLElement>('.read-error', paneNamed('requests')), zhCN.common.retry));
    await settle();
    expect(paneNamed('requests').querySelector('.read-error')).toBeNull();
    expect(rowsOf(requestsCard())).toHaveLength(4);
  });

  it('shows the policies and their switches to an administrator alone', async () => {
    approvalEndpoint(storedRequests());
    const policy: ApprovalPolicy = {
      id: 51,
      bizType: 'RELEASE_ORDER',
      action: 'EXECUTE_RELEASE',
      enabled: true,
      createdAt: '2026-10-01T09:00:00',
      updatedAt: '2026-10-01T09:00:00',
    };
    mockedPolicies.mockResolvedValue([policy]);
    await mountView({ authorities: ['SYSTEM_ADMIN'] });

    await openTab(zhCN.approval.policyManagement);
    expect(mockedPolicies).toHaveBeenCalledTimes(1);
    const card = policiesCard();
    expect(rowsOf(card)).toHaveLength(1);
    expect(rowCellOf(card, 0, 2)).toBe(zhCN.approval.actionEnum.EXECUTE_RELEASE);
    // The switch carries the policy it belongs to, so it is not read out by colour or position alone
    expect(switchInput(card, 0).getAttribute('aria-label')).toBe(
      zhCN.approval.policySwitchAria
        .replace('{bizType}', zhCN.approval.bizTypeEnum.RELEASE_ORDER)
        .replace('{action}', zhCN.approval.actionEnum.EXECUTE_RELEASE),
    );

    // Refresh keeps its own job here too
    await click(buttonByText(policiesBar(), zhCN.common.refresh));
    expect(mockedPolicies).toHaveBeenCalledTimes(2);

    // A refused write changes nothing: the switch keeps the value it was confirmed with
    mockedUpsertPolicy.mockRejectedValue(axiosLikeError('策略更新失败'));
    const before = switchInput(card, 0).getAttribute('aria-checked');
    switchInput(card, 0).click();
    await settle();
    expect(mockedUpsertPolicy).toHaveBeenCalledWith({
      bizType: 'RELEASE_ORDER',
      action: 'EXECUTE_RELEASE',
      enabled: false,
    });
    expect(switchInput(card, 0).getAttribute('aria-checked')).toBe(before);
    // Already reported by the shared interceptor
    expect(errorToast).not.toHaveBeenCalled();

    // Without that authority the tab is not offered at all
    await remount({ authorities: [], userId: 7 });
    expect(tabLabels()).not.toContain(zhCN.approval.policyManagement);
    expect(mockedPolicies).toHaveBeenCalledTimes(2);
  });
});