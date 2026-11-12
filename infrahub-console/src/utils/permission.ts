export function hasAuthority(authorities: string[] = [], required?: string[]) {
  if (!required || required.length === 0) {
    return true;
  }
  if (authorities.includes('SYSTEM_ADMIN')) {
    return true;
  }
  return required.some((item) => authorities.includes(item));
}
