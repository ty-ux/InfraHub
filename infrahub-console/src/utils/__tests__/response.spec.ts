import { describe, expect, it } from 'vitest';
import { unwrapApiResponse } from '../response';

describe('unwrapApiResponse', () => {
  it('returns data when response is success', () => {
    expect(
      unwrapApiResponse({
        code: 'SUCCESS',
        message: 'success',
        traceId: 'trace-1',
        data: { id: 1 },
      }),
    ).toEqual({ id: 1 });
  });

  it('throws when response code is not success', () => {
    expect(() =>
      unwrapApiResponse({
        code: 'AUTH_403',
        message: 'forbidden',
        traceId: 'trace-2',
        data: null,
      }),
    ).toThrow('forbidden');
  });
});
