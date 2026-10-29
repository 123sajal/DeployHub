import { AbstractControl, ValidationErrors } from '@angular/forms';

/** Mirrors the backend's PersonName: letters (any language) and single spaces only; no numbers or special characters. */
const PERSON_NAME = /^\p{L}[\p{L}\p{M}]*(?: [\p{L}\p{M}]+)*$/u;

export function normalizePersonName(value: string): string {
  return value.trim().replace(/\s+/g, ' ');
}

export function personName(control: AbstractControl): ValidationErrors | null {
  const value = normalizePersonName(control.value ?? '');
  if (!value) return null;
  return PERSON_NAME.test(value) ? null : { personName: 'Use only letters and spaces (no numbers or special characters)' };
}
