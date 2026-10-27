import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { environment } from '../../../../environments/environment';
import { ApplicationType, DetectResult, EnvRequirements, EnvVar, Page, Project, ProjectDatabase, ProjectRequest, ProjectRoute, PublicLink } from '../../models/api.models';

export interface ProjectQuery {
  search?: string;
  appType?: ApplicationType | null;
  page?: number;
  size?: number;
}

@Injectable({ providedIn: 'root' })
export class ProjectApiService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = `${environment.apiUrl}/projects`;

  list(query: ProjectQuery = {}): Observable<Page<Project>> {
    let params = new HttpParams().set('page', query.page ?? 0).set('size', query.size ?? 20);
    if (query.search?.trim()) params = params.set('search', query.search.trim());
    if (query.appType) params = params.set('appType', query.appType);
    return this.http.get<Page<Project>>(this.baseUrl, { params });
  }

  get(id: number): Observable<Project> {
    return this.http.get<Project>(`${this.baseUrl}/${id}`);
  }

  create(request: ProjectRequest): Observable<Project> {
    return this.http.post<Project>(this.baseUrl, request);
  }

  /** Looks at the repository's files and suggests the type and commands; nothing is saved. */
  detect(repositoryUrl: string, branch: string, rootDirectory: string | null): Observable<DetectResult> {
    return this.http.post<DetectResult>(`${this.baseUrl}/detect`, { repositoryUrl, branch, rootDirectory });
  }

  update(id: number, request: ProjectRequest): Observable<Project> {
    return this.http.put<Project>(`${this.baseUrl}/${id}`, request);
  }

  delete(id: number): Observable<void> {
    return this.http.delete<void>(`${this.baseUrl}/${id}`);
  }

  /** Names only: values are write-only. */
  listEnv(projectId: number): Observable<EnvVar[]> {
    return this.http.get<EnvVar[]>(`${this.baseUrl}/${projectId}/env`);
  }

  /** Creates or replaces a variable; it is encrypted on the server and applies at the next deployment. */
  /** The variables the app needs, read from its repository, and which are still missing. */
  envRequirements(projectId: number): Observable<EnvRequirements> {
    return this.http.get<EnvRequirements>(`${this.baseUrl}/${projectId}/env/requirements`);
  }

  /** Fills in these missing variables where DeployHub can (generated secrets, example values, database values). */
  fillEnvRequirements(projectId: number, names: string[]): Observable<EnvRequirements> {
    return this.http.post<EnvRequirements>(`${this.baseUrl}/${projectId}/env/requirements/fill`, { names });
  }

  setEnv(projectId: number, name: string, value: string): Observable<EnvVar> {
    return this.http.put<EnvVar>(`${this.baseUrl}/${projectId}/env/${encodeURIComponent(name)}`, { value });
  }

  deleteEnv(projectId: number, name: string): Observable<void> {
    return this.http.delete<void>(`${this.baseUrl}/${projectId}/env/${encodeURIComponent(name)}`);
  }

  getPublicLink(projectId: number): Observable<PublicLink> {
    return this.http.get<PublicLink>(`${this.baseUrl}/${projectId}/public-link`);
  }

  /** Makes the (live) project reachable from the internet; can take up to a minute. */
  enablePublicLink(projectId: number): Observable<PublicLink> {
    return this.http.post<PublicLink>(`${this.baseUrl}/${projectId}/public-link`, null);
  }

  disablePublicLink(projectId: number): Observable<PublicLink> {
    return this.http.delete<PublicLink>(`${this.baseUrl}/${projectId}/public-link`);
  }

  /** The project's database, or null when it has none (204). */
  getDatabase(projectId: number): Observable<ProjectDatabase | null> {
    return this.http.get<ProjectDatabase | null>(`${this.baseUrl}/${projectId}/database`);
  }

  /** Creates it (the first one can take a minute or two) and returns the password once. */
  createDatabase(projectId: number): Observable<ProjectDatabase> {
    return this.http.post<ProjectDatabase>(`${this.baseUrl}/${projectId}/database`, null);
  }

  resetDatabasePassword(projectId: number): Observable<ProjectDatabase> {
    return this.http.post<ProjectDatabase>(`${this.baseUrl}/${projectId}/database/reset-password`, null);
  }

  /** Deletes the database with all its data. */
  removeDatabase(projectId: number): Observable<void> {
    return this.http.delete<void>(`${this.baseUrl}/${projectId}/database`);
  }

  listRoutes(projectId: number): Observable<ProjectRoute[]> {
    return this.http.get<ProjectRoute[]>(`${this.baseUrl}/${projectId}/routes`);
  }

  /** Replaces all routes; the project's address is updated right away. */
  replaceRoutes(projectId: number, routes: ProjectRoute[]): Observable<ProjectRoute[]> {
    return this.http.put<ProjectRoute[]>(`${this.baseUrl}/${projectId}/routes`, {
      routes: routes.map(({ pathPrefix, targetProjectId }) => ({ pathPrefix, targetProjectId })),
    });
  }
}
