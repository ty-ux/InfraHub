export function resolveGuardRedirect(params: {
  isLoginPage: boolean;
  isLoggedIn: boolean;
  hasPermission: boolean;
  redirectPath: string;
}) {
  if (params.isLoginPage && params.isLoggedIn) {
    return '/workspace';
  }
  if (!params.isLoginPage && !params.isLoggedIn) {
    return `/login?redirect=${encodeURIComponent(params.redirectPath)}`;
  }
  if (!params.isLoginPage && !params.hasPermission) {
    return '/workspace';
  }
  return true;
}
