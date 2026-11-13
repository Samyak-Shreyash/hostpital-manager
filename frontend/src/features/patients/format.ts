import type { Sex } from '../../api/patients'

const SEX_LABELS: Record<Sex, string> = { M: 'Male', F: 'Female', O: 'Other', U: 'Unknown' }

export function sexLabel(sex: Sex): string {
  return SEX_LABELS[sex] ?? sex
}

/** Whole years between an ISO date (yyyy-mm-dd) and `today`; null when the date is missing. */
export function ageYears(dateOfBirth: string | null, today: Date = new Date()): number | null {
  if (!dateOfBirth) return null
  const [y, m, d] = dateOfBirth.split('-').map(Number)
  let age = today.getFullYear() - y
  const beforeBirthday = today.getMonth() + 1 < m || (today.getMonth() + 1 === m && today.getDate() < d)
  if (beforeBirthday) age -= 1
  return age
}

/** "34 y / Female"; never shows the raw birth date in the banner. */
export function ageSex(dateOfBirth: string | null, sex: Sex, today?: Date): string {
  const age = ageYears(dateOfBirth, today)
  return `${age === null ? '?' : `${age} y`} / ${sexLabel(sex)}`
}

/** The raw ABHA number is never shown after entry, only its last 4 digits. */
export function maskedAbha(abhaLast4: string | null): string {
  return abhaLast4 ? `••••••••••${abhaLast4}` : 'Not recorded'
}
