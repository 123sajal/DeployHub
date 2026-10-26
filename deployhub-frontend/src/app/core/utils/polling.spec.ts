import { ApiError } from '../models/api.models';
import { isTransient } from './polling';

const err = (status: number): ApiError => ({ status, title: '', detail: '', fieldErrors: {} });

describe('isTransient', () => {
  it('retries when the API is unreachable, overloaded or failing', () => {
    for (const status of [0, 429, 500, 502, 503]) expect(isTransient(err(status))).toBe(true);
  });

  it('does not retry errors that asking again will not fix', () => {
    for (const status of [400, 401, 403, 404, 409, 422]) expect(isTransient(err(status))).toBe(false);
  });
});
