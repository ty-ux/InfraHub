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
