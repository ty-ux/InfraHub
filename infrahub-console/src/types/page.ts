export type FieldType = 'text' | 'textarea' | 'number' | 'switch' | 'select';

export interface CrudFieldOption {
  label: string;
  value: string | number | boolean;
  disabled?: boolean;
}

export interface CrudColumn {
  prop: string;
  label: string;
  minWidth?: number;
  width?: number;
  align?: 'left' | 'center' | 'right';
  headerAlign?: 'left' | 'center' | 'right';
  resizable?: boolean;
  formatter?: (value: unknown, row: Record<string, unknown>) => string;
  headerTooltip?: string;
  overflowTooltip?: boolean;
  slot?: string;
}

export interface CrudField {
  prop: string;
  label: string;
  type?: FieldType;
  required?: boolean;
  placeholder?: string;
  options?: CrudFieldOption[];
  loadOptions?: (formState: Record<string, unknown>) => Promise<CrudFieldOption[]>;
  dependsOn?: string[];
  clearOnDependencyChange?: boolean;
}

export interface CrudApi<T extends Record<string, unknown>> {
  page: (params: Record<string, unknown>) => Promise<import('./api').PageResponse<T>>;
  create?: (payload: T) => Promise<boolean>;
  update?: (id: number | string, payload: T) => Promise<boolean>;
  remove?: (id: number | string) => Promise<boolean>;
}

export interface CrudPageConfig<T extends Record<string, unknown>> {
  title: string;
  description: string;
  api: CrudApi<T>;
  columns: CrudColumn[];
  fields?: CrudField[];
  defaultForm?: Partial<T>;
  createAuthority?: string[];
  editAuthority?: string[];
  deleteAuthority?: string[];
  rowKey?: string;
  extraQuery?: Record<string, unknown>;
  actionColumnWidth?: number;
  emptyText?: string;
  rowClassName?: (data: { row: Record<string, unknown>; rowIndex: number }) => string;
}
