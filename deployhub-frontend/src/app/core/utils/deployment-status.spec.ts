import { isInProgress, statusLabel, statusSeverity } from './deployment-status';

describe('deployment status helpers', () => {
  it('treats only QUEUED, BUILDING and STARTING as in progress', () => {
    expect(['QUEUED', 'BUILDING', 'STARTING'].every((s) => isInProgress(s as never))).toBe(true);
    expect(['LIVE', 'SUPERSEDED', 'FAILED', 'CANCELLED'].some((s) => isInProgress(s as never))).toBe(false);
  });

  it('maps statuses to labels and severities', () => {
    expect(statusLabel('FAILED')).toBe('Failed');
    expect(statusSeverity('LIVE')).toBe('success');
    expect(statusSeverity('FAILED')).toBe('danger');
  });
});
