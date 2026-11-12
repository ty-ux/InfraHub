import type { Component } from 'vue';

export type MenuGroup = 'workspace' | 'resource' | 'config' | 'release' | 'pipeline' | 'system';

export interface AppRouteMeta {
  title: string;
  group?: MenuGroup;
  order?: number;
  authorities?: string[];
  hidden?: boolean;
  icon?: Component | string;
  description?: string;
}
