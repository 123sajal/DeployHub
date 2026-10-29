import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { environment } from '../../../../environments/environment';
import {
  AssistantReply,
  AssistantStatus,
  AssistantTopic,
  ChatTurn,
  EmailProof,
  SignupCheck,
  StudentVerification,
} from '../../models/api.models';

/**
 * The AI assistant. The server decides whether the AI or a built-in answer replies; either way the answer is
 * shaped the same. Chats live only in the browser; the server doesn't store them.
 */
@Injectable({ providedIn: 'root' })
export class AssistantApiService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = `${environment.apiUrl}/assistant`;
  private readonly studentUrl = `${environment.apiUrl}/students/verify`;

  status(): Observable<AssistantStatus> {
    return this.http.get<AssistantStatus>(`${this.baseUrl}/status`);
  }

  checkSignup(field: 'NAME' | 'EMAIL', value: string): Observable<SignupCheck> {
    return this.http.post<SignupCheck>(`${this.baseUrl}/signup/check`, { field, value });
  }

  /** 409 when the email already has an account. */
  sendSignupCode(email: string): Observable<void> {
    return this.http.post<void>(`${this.baseUrl}/signup/email-code`, { email });
  }

  verifySignupCode(email: string, code: string): Observable<EmailProof> {
    return this.http.post<EmailProof>(`${this.baseUrl}/signup/email-code/verify`, { email, code });
  }

  /** The last turn must be the user's question; at most 12 turns. */
  chat(page: string, messages: ChatTurn[]): Observable<AssistantReply> {
    return this.http.post<AssistantReply>(`${this.baseUrl}/chat`, { page, messages: messages.slice(-12) });
  }

  explain(
    topic: AssistantTopic,
    details: { deploymentId?: number; projectId?: number; errorText?: string } = {},
  ): Observable<AssistantReply> {
    return this.http.post<AssistantReply>(`${this.baseUrl}/explain`, { topic, ...details });
  }

  /** The photo is checked and forgotten by the server, never stored. */
  verifyStudentIdCard(file: File): Observable<StudentVerification> {
    const form = new FormData();
    form.append('file', file);
    return this.http.post<StudentVerification>(`${this.studentUrl}/id-card`, form, { params: { consent: 'true' } });
  }

  sendCollegeEmailCode(email: string): Observable<void> {
    return this.http.post<void>(`${this.studentUrl}/college-email/code`, { email });
  }

  verifyCollegeEmail(email: string, code: string): Observable<StudentVerification> {
    return this.http.post<StudentVerification>(`${this.studentUrl}/college-email`, { email, code });
  }
}
