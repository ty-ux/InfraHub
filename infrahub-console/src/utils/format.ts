import dayjs from 'dayjs';

export function formatDateTime(value: unknown) {
  if (!value) {
    return '-';
  }
  return dayjs(String(value)).format('YYYY-MM-DD HH:mm:ss');
}

export function formatBoolean(value: unknown) {
  if (value === true) {
    return '是';
  }
  if (value === false) {
    return '否';
  }
  return '-';
}
