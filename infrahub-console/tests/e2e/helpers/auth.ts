import type { Page } from '@playwright/test';
import { expect } from '@playwright/test';

export async function loginAsAdmin(page: Page) {
  // Mock the login API before navigating to ensure consistent response
  // Response structure matches AuthTokenResponse interface
  await page.route('**/api/auth/login', async route => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        code: 'SUCCESS',
        message: '',
        traceId: 'trace-login',
        data: {
          accessToken: 'mock-jwt-token',
          tokenType: 'Bearer',
          expireSeconds: 3600
        }
      })
    });
  });

  // Mock the /me API to return authenticated user
  // Response structure matches CurrentUserResponse interface
  // Include RELEASE_VIEW and SYSTEM_ADMIN authorities for route access
  await page.route('**/api/auth/me', async route => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        code: 'SUCCESS',
        message: '',
        traceId: 'trace-me',
        data: {
          userId: 1,
          username: 'admin',
          displayName: '管理员',
          authorities: ['RELEASE_VIEW', 'SYSTEM_ADMIN', 'ADMIN', 'USER']
        }
      })
    });
  });

  await page.goto('/login');
  await expect(page.getByRole('heading', { name: '登录' })).toBeVisible({ timeout: 10000 });

  const username = page.locator('input').nth(0);
  const password = page.locator('input').nth(1);

  await username.fill('admin');
  await password.fill('admin123');
  await page.getByRole('button', { name: '登录' }).click();

  // Explicit timeout for URL change and console text - login can be slow under load
  await expect(page).toHaveURL(/\/workspace/, { timeout: 30000 });
  await expect(page.getByText('InfraHub 控制台')).toBeVisible({ timeout: 10000 });
}
