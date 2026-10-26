import { DatePipe } from '@angular/common';
import { Component, DestroyRef, OnInit, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormControl, ReactiveFormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { ButtonModule } from 'primeng/button';
import { IconFieldModule } from 'primeng/iconfield';
import { InputIconModule } from 'primeng/inputicon';
import { InputTextModule } from 'primeng/inputtext';
import { SelectModule } from 'primeng/select';
import { Subject, debounceTime, distinctUntilChanged, merge, switchMap, tap } from 'rxjs';

import { PageStateComponent } from '../../../core/components/shared/page-state.component';
import { ApiError, ApplicationType, Page, Project } from '../../../core/models/api.models';
import { ProjectApiService } from '../../../core/services/api/project-api.service';
import { APP_TYPE_OPTIONS, appTypeLabel } from '../../../core/utils/deployment-status';

const PAGE_SIZE = 10;

@Component({
  selector: 'app-project-list',
  imports: [
    RouterLink,
    DatePipe,
    ReactiveFormsModule,
    ButtonModule,
    InputTextModule,
    IconFieldModule,
    InputIconModule,
    SelectModule,
    PageStateComponent,
  ],
  templateUrl: './project-list.component.html',
  styleUrl: './project-list.component.scss',
})
export class ProjectListComponent implements OnInit {
  private readonly projectApi = inject(ProjectApiService);
  private readonly destroyRef = inject(DestroyRef);
  private readonly reload$ = new Subject<void>();

  protected readonly search = new FormControl('', { nonNullable: true });
  protected readonly appType = new FormControl<ApplicationType | null>(null);
  protected readonly appTypeOptions = APP_TYPE_OPTIONS;
  protected readonly appTypeLabel = appTypeLabel;

  protected readonly page = signal(0);
  protected readonly result = signal<Page<Project> | null>(null);
  protected readonly loading = signal(true);
  protected readonly error = signal<ApiError | null>(null);

  ngOnInit(): void {
    const filtersChanged$ = merge(
      this.search.valueChanges.pipe(debounceTime(300), distinctUntilChanged()),
      this.appType.valueChanges,
    ).pipe(tap(() => this.page.set(0)));

    // switchMap cancels an in-flight request when the user keeps typing.
    merge(filtersChanged$, this.reload$)
      .pipe(
        tap(() => {
          this.loading.set(true);
          this.error.set(null);
        }),
        switchMap(() =>
          this.projectApi.list({
            search: this.search.value,
            appType: this.appType.value,
            page: this.page(),
            size: PAGE_SIZE,
          }),
        ),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe({
        next: (page) => {
          this.result.set(page);
          this.loading.set(false);
        },
        error: (err: ApiError) => {
          this.error.set(err);
          this.loading.set(false);
        },
      });
    this.reload();
  }

  protected reload(): void {
    this.reload$.next();
  }

  protected goTo(page: number): void {
    this.page.set(page);
    this.reload();
  }

  protected get hasFilters(): boolean {
    return !!this.search.value || !!this.appType.value;
  }
}
