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
  FORBIDDEN: 'You do not have permission to do this.',
}

/** User-facing text for a failed call; never includes request values such as passwords. */
export function errorMessage(error: unknown): string {
  if (error instanceof ApiError) return MESSAGES[error.code] ?? error.message
  return 'Something went wrong. Check your connection and try again.'
}
