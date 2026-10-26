import { ApplicationType, DeploymentStatus } from '../models/api.models';

export type TagSeverity = 'success' | 'info' | 'warn' | 'danger' | 'secondary' | 'contrast';

const SEVERITY: Record<DeploymentStatus, TagSeverity> = {
  QUEUED: 'secondary',
  BUILDING: 'info',
  STARTING: 'info',
  LIVE: 'success',
  SUPERSEDED: 'contrast',
  FAILED: 'danger',
  CANCELLED: 'warn',
};

export const DEPLOYMENT_STATUSES: DeploymentStatus[] = [
  'QUEUED',
  'BUILDING',
  'STARTING',
  'LIVE',
  'SUPERSEDED',
  'FAILED',
  'CANCELLED',
];

/** The happy path, used to draw the progress timeline. */
export const PIPELINE: DeploymentStatus[] = ['QUEUED', 'BUILDING', 'STARTING', 'LIVE'];

export function statusSeverity(status: DeploymentStatus): TagSeverity {
  return SEVERITY[status];
}

export function isInProgress(status: DeploymentStatus): boolean {
  return status === 'QUEUED' || status === 'BUILDING' || status === 'STARTING';
}

export function statusLabel(status: DeploymentStatus): string {
  return status.charAt(0) + status.slice(1).toLowerCase();
}

export const APP_TYPE_OPTIONS: { label: string; value: ApplicationType }[] = [
  { label: 'Java / Spring Boot', value: 'JAVA_SPRING_BOOT' },
  { label: 'Node.js', value: 'NODE_JS' },
  { label: 'Static site', value: 'STATIC_SITE' },
  { label: 'Python', value: 'PYTHON' },
  { label: 'Dockerfile', value: 'DOCKERFILE' },
];

export function appTypeLabel(type: ApplicationType): string {
  return APP_TYPE_OPTIONS.find((o) => o.value === type)?.label ?? type;
}
