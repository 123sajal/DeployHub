import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { environment } from '../../../../environments/environment';
import { ApplicationType, Page, Project, ProjectRequest } from '../../models/api.models';

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

  update(id: number, request: ProjectRequest): Observable<Project> {
    return this.http.put<Project>(`${this.baseUrl}/${id}`, request);
  }

  delete(id: number): Observable<void> {
    return this.http.delete<void>(`${this.baseUrl}/${id}`);
  }
}
