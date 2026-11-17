export const menuGroupTitles = {
  workspace: 'sidebar.workspace',
  resource: 'sidebar.resource',
  config: 'sidebar.config',
  delivery: 'sidebar.delivery',
  monitor: 'sidebar.monitor',
  governance: 'sidebar.governance',
  system: 'sidebar.system',
} as const;

export type MenuGroup = keyof typeof menuGroupTitles;

export const menuLayoutPath = '/system/menu-layout';
export const menuGroupsInDefaultOrder = Object.keys(menuGroupTitles) as MenuGroup[];

export interface MenuEntry {
  path: string;
  group: MenuGroup;
  visible: boolean;
}

export interface MenuLayout {
  groupOrder: MenuGroup[];
  entries: MenuEntry[];
}

export function resolveMenuLayout(defaults: MenuLayout, saved?: MenuLayout): MenuLayout {
  const pending = new Map(defaults.entries.map((entry) => [entry.path, entry]));
  const entries: MenuEntry[] = [];
  for (const entry of saved?.entries ?? []) {
    if (!Object.hasOwn(menuGroupTitles, entry.group) || !pending.delete(entry.path)) continue;
    entries.push(entry.path === menuLayoutPath ? { ...entry, group: 'system', visible: true } : { ...entry });
  }
  entries.push(...Array.from(pending.values(), (entry) => ({ ...entry })));

  const preferredGroups = (saved?.groupOrder ?? []).filter((group) => Object.hasOwn(menuGroupTitles, group));
  return { groupOrder: [...new Set([...preferredGroups, ...defaults.groupOrder])], entries };
}
