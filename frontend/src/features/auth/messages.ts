import { ApiError } from '../../api/client'

const MESSAGES: Record<string, string> = {
  INVALID_CREDENTIALS: 'Email or password is incorrect.',
  ACCOUNT_LOCKED: 'Too many failed attempts. Try again later.',
  ACCOUNT_INACTIVE: 'This account is not active.',
  RATE_LIMITED: 'Too many requests. Wait a moment and try again.',
  INVALID_CURRENT_PASSWORD: 'The current password is incorrect.',
  PASSWORD_POLICY_VIOLATION: 'The new password does not meet the password policy.',
  DUPLICATE_EMAIL: 'This email address is already in use.',
  CONCURRENT_MODIFICATION: 'Someone else changed this record. Reload and try again.',
  CANNOT_DEACTIVATE_SELF: 'You cannot deactivate your own account.',
  LAST_ADMINISTRATOR: 'This would remove the last system administrator.',
  PRIVILEGE_ESCALATION: 'You cannot grant permissions or assign roles beyond your own access.',
  UNKNOWN_ROLE: 'One of the selected roles does not exist.',
  DUPLICATE_ROLE_CODE: 'A role with this code already exists.',
  UNKNOWN_PERMISSION: 'One of the selected permissions does not exist.',
  DUPLICATE_ABHA: 'This ABHA number is already linked to another patient.',
  DUPLICATE_SUSPECTED: 'This patient may already be registered.',
  UHID_IMMUTABLE: 'The UHID cannot be changed.',
  ENTRY_ALREADY_ENTERED_IN_ERROR: 'This entry is already marked as entered in error.',
  USER_ALREADY_LINKED: 'This user account is already linked to a doctor or staff record.',
  USER_NOT_AVAILABLE: 'The selected user account is not an active user of this facility.',
  DUPLICATE_REGISTRATION_NO: 'Another doctor already has this registration number.',
  SPECIALTY_NOT_AVAILABLE: 'One of the selected specialties is unknown or inactive.',
  DEPARTMENT_NOT_AVAILABLE: 'One of the selected departments is unknown or inactive.',
  AVAILABILITY_OVERLAP: 'Two availability windows overlap on the same day.',
  FORBIDDEN: 'You do not have permission to do this.',
}

/** User-facing text for a failed call; never includes request values such as passwords. */
export function errorMessage(error: unknown): string {
  if (error instanceof ApiError) return MESSAGES[error.code] ?? error.message
  return 'Something went wrong. Check your connection and try again.'
}
