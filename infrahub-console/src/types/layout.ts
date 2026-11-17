import type { Component } from 'vue';
import type { RouteMeta } from 'vue-router';
import type { MenuGroup } from '@/router/navigation';

declare module 'vue-router' {
  interface RouteMeta {
    group?: MenuGroup;
    activeMenuPath?: string;
  }
}

export interface AppRouteMeta extends RouteMeta {
  title: string;
  order?: number;
  authorities?: string[];
  hidden?: boolean;
  icon?: Component | string;
  description?: string;
}
