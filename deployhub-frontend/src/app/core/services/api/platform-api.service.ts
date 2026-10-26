import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { environment } from '../../../../environments/environment';
import { DashboardSummary, DeploymentMetrics, Health } from '../../models/api.models';

@Injectable({ providedIn: 'root' })
export class PlatformApiService {
  private readonly http = inject(HttpClient);

  dashboardSummary(): Observable<DashboardSummary> {
    return this.http.get<DashboardSummary>(`${environment.apiUrl}/dashboard/summary`);
  }

  deploymentMetrics(): Observable<DeploymentMetrics> {
    return this.http.get<DeploymentMetrics>(`${environment.apiUrl}/dashboard/metrics`);
  }

  health(): Observable<Health> {
    return this.http.get<Health>(`${environment.apiUrl}/health`);
  }
}
