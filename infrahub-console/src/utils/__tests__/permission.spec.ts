import { describe, expect, it } from 'vitest';
import { hasAuthority } from '../permission';

describe('hasAuthority', () => {
  it('returns true when no authority is required', () => {
    expect(hasAuthority(['APP_VIEW'], undefined)).toBe(true);
  });

  it('allows system admin to pass any check', () => {
    expect(hasAuthority(['SYSTEM_ADMIN'], ['PIPELINE_EXECUTE'])).toBe(true);
  });

  it('matches one of required authorities', () => {
    expect(hasAuthority(['APP_VIEW'], ['APP_VIEW', 'SYSTEM_ADMIN'])).toBe(true);
    expect(hasAuthority(['APP_VIEW'], ['PIPELINE_EXECUTE'])).toBe(false);
  });
});
