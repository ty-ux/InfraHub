import type { MenuLayout } from '@/router/navigation';
import { get, put } from '@/utils/http';

export interface MenuLayoutState {
  revision: number;
  layout: MenuLayout;
}

const url = '/api/system/menu-layout';

export const getMenuLayout = () => get<MenuLayoutState>(url, undefined, { silent: true });
export const saveMenuLayout = (state: MenuLayoutState) => put<MenuLayoutState>(url, state);
