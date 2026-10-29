import { HttpClient } from '@angular/common/http';
import { Injectable, computed, inject, signal } from '@angular/core';
import { Observable, catchError, finalize, of, tap } from 'rxjs';

import { environment } from '../../../../environments/environment';
import { CurrentUser, LoginRequest, Occupation, RegisterRequest, ResetPasswordRequest } from '../../models/api.models';

/**
 * Holds who is logged in. The login itself lives in an HttpOnly session cookie that JavaScript
 * cannot read; this service only keeps the public profile returned by the API, in memory.
 * Nothing is written to localStorage. After a page refresh, restore() asks the backend again.
 */
@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = `${environment.apiUrl}/auth`;
  private readonly currentUser = signal<CurrentUser | null>(null);

  readonly user = this.currentUser.asReadonly();
  readonly isAuthenticated = computed(() => this.currentUser() !== null);

  /** Runs once at startup (see app.config.ts) so guards know the answer before routing. */
  restore(): Observable<CurrentUser | null> {
    return this.http.get<CurrentUser>(`${this.baseUrl}/me`).pipe(
      tap((user) => this.currentUser.set(user)),
      // 401 = not logged in. If the API is unreachable we also start logged out; the login page
      // will then show the real error when the user tries to sign in.
      catchError(() => {
        this.currentUser.set(null);
        return of(null);
      }),
    );
  }

  login(request: LoginRequest): Observable<CurrentUser> {
    return this.http.post<CurrentUser>(`${this.baseUrl}/login`, request).pipe(tap((user) => this.currentUser.set(user)));
  }

  register(request: RegisterRequest): Observable<CurrentUser> {
    return this.http
      .post<CurrentUser>(`${this.baseUrl}/register`, request)
      .pipe(tap((user) => this.currentUser.set(user)));
  }

  /** Changes "student or working professional". */
  updateOccupation(occupation: Occupation): Observable<CurrentUser> {
    return this.http
      .patch<CurrentUser>(`${this.baseUrl}/me`, { occupation })
      .pipe(tap((user) => this.currentUser.set(user)));
  }

  /** Reloads the profile (e.g. after the student check changed it). Keeps the current one if the request fails. */
  refresh(): Observable<CurrentUser | null> {
    return this.http.get<CurrentUser>(`${this.baseUrl}/me`).pipe(
      tap((user) => this.currentUser.set(user)),
      catchError(() => of(this.currentUser())),
    );
  }

  /** Asks for a reset code by email. The answer is the same whether or not the email has an account. */
  forgotPassword(email: string): Observable<void> {
    return this.http.post<void>(`${this.baseUrl}/forgot-password`, { email });
  }

  /** Sets a new password with the emailed code. Does not log in: the user logs in with the new password. */
  resetPassword(request: ResetPasswordRequest): Observable<void> {
    return this.http.post<void>(`${this.baseUrl}/reset-password`, request);
  }

  /** Ends the server session. The local state is cleared even if the request fails. */
  logout(): Observable<void> {
    return this.http.post<void>(`${this.baseUrl}/logout`, null).pipe(
      catchError(() => of(undefined)),
      finalize(() => this.currentUser.set(null)),
    );
  }

  /** Called when the API says the session is gone (expired, or logged out in another tab). */
  clearSession(): void {
    this.currentUser.set(null);
  }
}
