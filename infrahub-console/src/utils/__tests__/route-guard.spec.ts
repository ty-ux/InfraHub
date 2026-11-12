import { describe, expect, it } from 'vitest';
import { resolveGuardRedirect } from '../route-guard';

describe('resolveGuardRedirect', () => {
  it('redirects authenticated users away from login', () => {
    expect(
      resolveGuardRedirect({
        isLoginPage: true,
        isLoggedIn: true,
        hasPermission: true,
        redirectPath: '/login',
      }),
    ).toBe('/workspace');
  });

  it('redirects anonymous users to login', () => {
    expect(
      resolveGuardRedirect({
        isLoginPage: false,
        isLoggedIn: false,
        hasPermission: true,
        redirectPath: '/release/orders',
      }),
    ).toBe('/login?redirect=%2Frelease%2Forders');
  });

  it('redirects unauthorized users to workspace', () => {
    expect(
      resolveGuardRedirect({
        isLoginPage: false,
        isLoggedIn: true,
        hasPermission: false,
        redirectPath: '/system/users',
      }),
    ).toBe('/workspace');
  });
});
