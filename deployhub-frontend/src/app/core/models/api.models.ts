// Mirrors the backend DTOs in deployhub-backend/.../models/DTO. Keep the two in sync.

export type ApplicationType = 'JAVA_SPRING_BOOT' | 'NODE_JS' | 'STATIC_SITE' | 'PYTHON' | 'DOCKERFILE';

export type DeploymentStatus = 'QUEUED' | 'BUILDING' | 'STARTING' | 'LIVE' | 'SUPERSEDED' | 'FAILED' | 'CANCELLED';

/** SIMULATED means nothing was actually built or run. */
export type ExecutionMode = 'SIMULATED' | 'REAL';

export type LogLevel = 'INFO' | 'WARN' | 'ERROR';

export interface Page<T> {
  items: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

export interface Project {
  id: number;
  name: string;
  repositoryUrl: string;
  branch: string;
  appType: ApplicationType;
  buildCommand: string | null;
  startCommand: string;
  createdAt: string;
  updatedAt: string;
}

export interface ProjectRequest {
  name: string;
  repositoryUrl: string;
  branch: string;
  appType: ApplicationType;
  buildCommand: string | null;
  startCommand: string;
}

export interface Deployment {
  id: number;
  projectId: number;
  projectName: string;
  status: DeploymentStatus;
  executionMode: ExecutionMode;
  commitRef: string | null;
  failureReason: string | null;
  cancellable: boolean;
  /** True when this deployment was live and has been replaced, so it can be rolled back to. */
  rollbackable: boolean;
  /** Set when this deployment is a rollback: the earlier deployment it redeploys. */
  rollbackOfId: number | null;
  createdAt: string;
  statusChangedAt: string;
  startedAt: string | null;
  finishedAt: string | null;
}

export interface DeploymentLog {
  id: number;
  level: LogLevel;
  message: string;
  occurredAt: string;
}

export interface LogPage {
  items: DeploymentLog[];
  nextCursor: number;
  hasMore: boolean;
  deploymentStatus: DeploymentStatus;
}

export interface DashboardSummary {
  totalProjects: number;
  queued: number;
  running: number;
  successful: number;
  failed: number;
  cancelled: number;
  recentDeployments: Deployment[];
}

export interface Health {
  status: 'UP' | 'DOWN';
  database: 'UP' | 'DOWN';
  databaseProduct: string | null;
  activeProfiles: string[];
  deploymentExecutionMode: ExecutionMode;
  timestamp: string;
}

/** Normalised error produced by apiErrorInterceptor from an RFC 9457 problem response. */
export interface ApiError {
  status: number;
  title: string;
  detail: string;
  fieldErrors: Record<string, string>;
}

// ---- Authentication (Milestone 3) ----

/** Who the server-side session belongs to. Never contains a password or token. */
export interface CurrentUser {
  id: number;
  email: string;
  displayName: string;
}

export interface LoginRequest {
  email: string;
  password: string;
}

export interface RegisterRequest {
  displayName: string;
  email: string;
  password: string;
}

// ---- Durable deployment jobs (Milestone 4) ----

export type JobStatus = 'PENDING' | 'RUNNING' | 'SUCCEEDED' | 'FAILED' | 'DEAD' | 'CANCELLED';

export interface DeploymentJobInfo {
  status: JobStatus;
  attempt: number;
  maxAttempts: number;
  /** Set while a retry is scheduled. */
  nextAttemptAt: string | null;
  lastError: string | null;
  /** The worker currently running the job, if any. */
  workerId: string | null;
}

export interface DeploymentTransition {
  fromStatus: DeploymentStatus | null;
  toStatus: DeploymentStatus;
  reason: string | null;
  /** "user:42", "worker:<id>" or "system". */
  actor: string;
  attempt: number | null;
  occurredAt: string;
}

export interface DeploymentHistory {
  job: DeploymentJobInfo | null;
  transitions: DeploymentTransition[];
}

// ---- Observability (Milestone 5) ----

/**
 * The last `windowMinutes` of the current user's own deployments, plus the shared queue's depth.
 * Wait = from request until a worker first picked the deployment up.
 */
export interface DeploymentMetrics {
  windowMinutes: number;
  platformQueue: { waiting: number; retryScheduled: number; running: number };
  avgQueueWaitMs: number | null;
  maxQueueWaitMs: number | null;
  finished: number;
  succeeded: number;
  failed: number;
  cancelled: number;
  /** 0..1; null when nothing succeeded or failed in the window. */
  successRate: number | null;
}
