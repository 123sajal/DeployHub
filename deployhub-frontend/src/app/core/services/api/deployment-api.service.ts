import { HttpClient, HttpHeaders, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { environment } from '../../../../environments/environment';
import { Deployment, DeploymentHistory, DeploymentStatus, LogPage, Page } from '../../models/api.models';

export interface DeploymentQuery {
  projectId?: number | null;
  status?: DeploymentStatus | null;
  page?: number;
  size?: number;
}

@Injectable({ providedIn: 'root' })
export class DeploymentApiService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = environment.apiUrl;

  list(query: DeploymentQuery = {}): Observable<Page<Deployment>> {
    let params = new HttpParams().set('page', query.page ?? 0).set('size', query.size ?? 20);
    if (query.projectId) params = params.set('projectId', query.projectId);
    if (query.status) params = params.set('status', query.status);
    return this.http.get<Page<Deployment>>(`${this.baseUrl}/deployments`, { params });
  }

  get(id: number): Observable<Deployment> {
    return this.http.get<Deployment>(`${this.baseUrl}/deployments/${id}`);
  }

  /**
   * Queues a deployment. The Idempotency-Key makes retries safe: the same key always maps to the
   * same deployment, so a double click or a retried request never creates a second one.
   */
  create(projectId: number, idempotencyKey: string, commitRef?: string): Observable<Deployment> {
    const body = commitRef ? { commitRef } : null;
    const headers = new HttpHeaders({ 'Idempotency-Key': idempotencyKey });
    return this.http.post<Deployment>(`${this.baseUrl}/projects/${projectId}/deployments`, body, { headers });
  }

  /** Status changes (when, why, by whom) plus the queue job's attempts and retry schedule. */
  history(id: number): Observable<DeploymentHistory> {
    return this.http.get<DeploymentHistory>(`${this.baseUrl}/deployments/${id}/history`);
  }

  /**
   * Queues a NEW deployment that redeploys deployment `id` (it must have been live and since replaced).
   * Takes an Idempotency-Key exactly like create().
   */
  rollback(id: number, idempotencyKey: string): Observable<Deployment> {
    const headers = new HttpHeaders({ 'Idempotency-Key': idempotencyKey });
    return this.http.post<Deployment>(`${this.baseUrl}/deployments/${id}/rollback`, null, { headers });
  }

  /** Only finished deployments that aren't the running app (see Deployment.deletable); 409 otherwise. */
  delete(id: number): Observable<void> {
    return this.http.delete<void>(`${this.baseUrl}/deployments/${id}`);
  }

  cancel(id: number): Observable<Deployment> {
    return this.http.post<Deployment>(`${this.baseUrl}/deployments/${id}/cancel`, null);
  }

  /**
   * URL for EventSource (Server-Sent Events): `log` events carry the cursor as their id, so a browser that
   * reconnects by itself resumes via Last-Event-ID. Same origin, so the session cookie is sent.
   */
  logStreamUrl(id: number, after = 0): string {
    return `${this.baseUrl}/deployments/${id}/logs/stream?after=${after}`;
  }

  /** Cursor pagination: pass the previous page's nextCursor as `after` to continue. */
  logs(id: number, after = 0, limit = 500): Observable<LogPage> {
    const params = new HttpParams().set('after', after).set('limit', limit);
    return this.http.get<LogPage>(`${this.baseUrl}/deployments/${id}/logs`, { params });
  }
}
