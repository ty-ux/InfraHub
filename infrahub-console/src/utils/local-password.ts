export function validLocalPassword(password: string) {
  return password.trim().length > 0 && [...password].length >= 15 && new TextEncoder().encode(password).length <= 72;
}
