import type { Locator, Page } from '@playwright/test';
import { expect } from '@playwright/test';

export function sidebarLink(page: Page, name: string) {
  return page.locator('aside.layout-sidebar').getByRole('link', { name, exact: true });
}

export async function openPage(page: Page, name: string) {
  await sidebarLink(page, name).click();
  await expect(page.getByRole('heading', { name, exact: true })).toBeVisible();
}

export function activeDialog(page: Page) {
  return page.getByRole('dialog').last();
}

export async function openCreateDialog(page: Page, pageName: string) {
  await openPage(page, pageName);
  await page.getByRole('button', { name: '新建', exact: true }).click();
  await expect(activeDialog(page)).toBeVisible();
  return activeDialog(page);
}

function namePattern(label: string) {
  return new RegExp(label.replace(/[.*+?^${}()|[\]\\]/g, '\\$&'));
}

export async function fillTextField(dialog: Locator, label: string, value: string) {
  await dialog.getByLabel(namePattern(label)).fill(value);
}

export async function fillTextArea(dialog: Locator, label: string, value: string) {
  await dialog.getByLabel(namePattern(label)).fill(value);
}

export async function fillNumberField(dialog: Locator, label: string, value: string) {
  await dialog.getByRole('spinbutton', { name: namePattern(label) }).fill(value);
}

async function openSelect(dialog: Locator, label: string) {
  const combobox = dialog.getByRole('combobox', { name: namePattern(label) });
  const select = combobox.locator('xpath=ancestor::*[contains(concat(" ", normalize-space(@class), " "), " el-select ")][1]');
  await select.click({ force: true });
  await combobox.press('ArrowDown');
  return combobox;
}

async function currentSelectOptions(page: Page, combobox: Locator) {
  const controls = await combobox.getAttribute('aria-controls');
  if (!controls) {
    return page.locator('[role="option"]:visible');
  }
  const escapedControls = controls.replace(/\\/g, '\\\\').replace(/"/g, '\\"');
  return page.locator(`[id="${escapedControls}"] [role="option"]:visible`);
}

export async function chooseSelectOption(dialog: Locator, page: Page, label: string, option: string) {
  const combobox = await openSelect(dialog, label);
  await combobox.fill(option);
  let visibleOption = page.locator('[role="option"]:visible').filter({ hasText: option }).first();
  await expect(async () => {
    visibleOption = (await currentSelectOptions(page, combobox)).filter({ hasText: option }).first();
    await expect(visibleOption).toBeVisible({ timeout: 1000 });
  }).toPass({ timeout: 15000 });
  await visibleOption.evaluate((element: HTMLElement) => element.click());
  await expect(combobox.locator('xpath=ancestor::*[contains(concat(" ", normalize-space(@class), " "), " el-form-item ")][1]')).toContainText(option);
  return option;
}

export async function chooseFirstSelectOption(dialog: Locator, page: Page, label: string) {
  return chooseSelectOptionByIndex(dialog, page, label, 0);
}

export async function chooseSelectOptionByIndex(dialog: Locator, page: Page, label: string, index: number) {
  const combobox = await openSelect(dialog, label);
  let options = page.locator('[role="option"]:visible').filter({ hasNotText: 'No matching data' });
  let option = options.nth(index);
  await expect(async () => {
    options = (await currentSelectOptions(page, combobox)).filter({ hasNotText: 'No matching data' });
    if (await options.count() <= index) {
      options = page.locator('.el-select-dropdown:visible [role="option"]:visible').filter({ hasNotText: 'No matching data' });
    }
    option = options.nth(index);
    await expect(option).toBeVisible({ timeout: 1000 });
    const text = ((await option.textContent()) || '').trim();
    expect(text).not.toBe('');
  }).toPass({ timeout: 15000 });
  const optionLabel = ((await option.textContent()) || '').trim();
  await option.evaluate((element: HTMLElement) => element.click());
  await expect(combobox.locator('xpath=ancestor::*[contains(concat(" ", normalize-space(@class), " "), " el-form-item ")][1]')).toContainText(optionLabel);
  return optionLabel;
}

export async function saveDialog(page: Page) {
  const dialog = activeDialog(page);
  await dialog.getByRole('button', { name: '保存', exact: true }).click();
  try {
    await expect(dialog).toBeHidden();
  } catch (error) {
    const validationErrors = (await dialog.locator('.el-form-item__error').allTextContents())
      .map((text) => text.trim())
      .filter(Boolean);
    if (validationErrors.length) {
      throw new Error(`Dialog save was blocked by validation: ${validationErrors.join('; ')}`);
    }
    throw error;
  }
}

export function tableRow(page: Page, text: string) {
  return page.locator('tbody tr').filter({ hasText: text }).first();
}

export async function expectRowVisible(page: Page, text: string) {
  await expect(tableRow(page, text)).toBeVisible();
}

export async function deleteRow(page: Page, text: string) {
  const row = tableRow(page, text);
  await expect(row).toBeVisible();
  await row.getByRole('button', { name: '删除', exact: true }).click();
  const confirmDialog = activeDialog(page);
  await confirmDialog.getByRole('button', { name: '确认删除', exact: true }).click();
  await expect(tableRow(page, text)).toHaveCount(0);
}

export async function openEditDialog(page: Page, text: string) {
  const row = tableRow(page, text);
  await expect(row).toBeVisible();
  await row.getByRole('button', { name: '编辑', exact: true }).click();
  await expect(activeDialog(page)).toBeVisible();
  return activeDialog(page);
}
