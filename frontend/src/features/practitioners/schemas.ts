import { z } from 'zod'
import type { Window } from '../../api/practitioners'

/** Mirrors the backend rules (api-design.md section 5); the server stays authoritative. */
export const REGISTRATION_NO = /^[A-Za-z0-9/.-]{1,50}$/
export const HH_MM = /^([01]\d|2[0-3]):[0-5]\d$/
export const MAX_SLOT_RANGE_DAYS = 31

const slotMinutes = z
  .string()
  .trim()
  .refine((v) => /^\d{1,3}$/.test(v) && Number(v) >= 5 && Number(v) <= 120, 'Slot length must be 5 to 120 minutes')

/** Comma-separated in the form; up to 10 entries of 1-50 characters. */
const qualifications = z
  .string()
  .refine((v) => splitList(v).length <= 10, 'At most 10 qualifications')
  .refine((v) => splitList(v).every((q) => q.length <= 50), 'Each qualification must be at most 50 characters')

export function splitList(v: string): string[] {
  return v.split(',').map((s) => s.trim()).filter(Boolean)
}

/** Exactly one primary: the primary must be set and be one of the selected specialties. */
export function checkSpecialties(v: { specialtyIds: string[]; primarySpecialtyId: string }, ctx: z.RefinementCtx) {
  if (v.specialtyIds.length === 0) {
    ctx.addIssue({ code: 'custom', path: ['specialtyIds'], message: 'Select at least one specialty' })
  } else if (!v.primarySpecialtyId || !v.specialtyIds.includes(v.primarySpecialtyId)) {
    ctx.addIssue({ code: 'custom', path: ['primarySpecialtyId'], message: 'Mark exactly one selected specialty as primary' })
  }
}

export const specialtiesSchema = z
  .object({ specialtyIds: z.array(z.string()).max(20, 'At most 20 specialties'), primarySpecialtyId: z.string() })
  .superRefine(checkSpecialties)
export type SpecialtiesValues = z.infer<typeof specialtiesSchema>

const profileFields = {
  fullName: z.string().trim().min(1, 'Full name is required').max(120, 'Full name is too long'),
  registrationNo: z.string().trim().regex(REGISTRATION_NO, 'Registration number: up to 50 letters, digits, / . -'),
  registrationCouncil: z.string().trim().max(100, 'Council is at most 100 characters'),
  qualifications,
  defaultSlotMinutes: slotMinutes,
}

export const doctorProfileSchema = z.object(profileFields)
export type DoctorProfileValues = z.infer<typeof doctorProfileSchema>

export const doctorRegisterSchema = z
  .object({
    userId: z.string().min(1, 'Select the user account to link'),
    ...profileFields,
    specialtyIds: z.array(z.string()).max(20, 'At most 20 specialties'),
    primarySpecialtyId: z.string(),
    departmentIds: z.array(z.string()).max(20, 'At most 20 departments'),
  })
  .superRefine(checkSpecialties)
export type DoctorRegisterValues = z.infer<typeof doctorRegisterSchema>

export const staffSchema = z.object({
  userId: z.string(),
  fullName: z.string().trim().min(1, 'Full name is required').max(120, 'Full name is too long'),
  staffType: z.enum(['NURSE', 'RECEPTIONIST', 'ACCOUNTANT', 'LAB_TECHNICIAN', 'PHARMACIST', 'ADMINISTRATIVE', 'OTHER'], { message: 'Select a staff type' }),
  designation: z.string().trim().max(80, 'Designation is at most 80 characters'),
  departmentId: z.string(),
})
export type StaffValues = z.infer<typeof staffSchema>

const minutes = (t: string) => Number(t.slice(0, 2)) * 60 + Number(t.slice(3, 5))

/** Client-side check of a weekly template; returns one message per problem (empty = valid). */
export function windowProblems(windows: Window[]): string[] {
  const problems: string[] = []
  if (windows.length > 50) problems.push('At most 50 windows')
  windows.forEach((w, i) => {
    const label = `Window ${i + 1}`
    if (!HH_MM.test(w.start) || !HH_MM.test(w.end)) {
      problems.push(`${label}: enter start and end times`)
      return
    }
    if (!(w.slotMinutes >= 5 && w.slotMinutes <= 120)) problems.push(`${label}: slot length must be 5 to 120 minutes`)
    const length = minutes(w.end) - minutes(w.start)
    if (length <= 0) problems.push(`${label}: end must be after start`)
    else if (length < w.slotMinutes) problems.push(`${label}: must hold at least one slot`)
  })
  for (let i = 0; i < windows.length; i++) {
    for (let j = i + 1; j < windows.length; j++) {
      const a = windows[i]
      const b = windows[j]
      if (a.weekday !== b.weekday || !HH_MM.test(a.start) || !HH_MM.test(b.start) || !HH_MM.test(a.end) || !HH_MM.test(b.end)) continue
      if (minutes(a.start) < minutes(b.end) && minutes(b.start) < minutes(a.end)) {
        problems.push(`Windows ${i + 1} and ${j + 1} overlap on the same day`)
      }
    }
  }
  return problems
}

export const leaveSchema = z
  .object({ date: z.string().min(1, 'Enter the date'), start: z.string(), end: z.string() })
  .superRefine((v, ctx) => {
    if (!v.start && !v.end) return
    if (!HH_MM.test(v.start) || !HH_MM.test(v.end)) {
      ctx.addIssue({ code: 'custom', path: ['start'], message: 'Enter both times, or neither for a whole day' })
    } else if (minutes(v.end) <= minutes(v.start)) {
      ctx.addIssue({ code: 'custom', path: ['end'], message: 'End must be after start' })
    }
  })
export type LeaveValues = z.infer<typeof leaveSchema>

/** Inclusive day count between two ISO dates. */
export function daysInclusive(from: string, to: string): number {
  return Math.round((Date.parse(`${to}T00:00:00Z`) - Date.parse(`${from}T00:00:00Z`)) / 86_400_000) + 1
}

export const slotRangeSchema = z
  .object({ from: z.string().min(1, 'Enter the start date'), to: z.string().min(1, 'Enter the end date') })
  .superRefine((v, ctx) => {
    if (!v.from || !v.to) return
    const days = daysInclusive(v.from, v.to)
    if (days < 1) ctx.addIssue({ code: 'custom', path: ['to'], message: 'End date must not be before the start date' })
    else if (days > MAX_SLOT_RANGE_DAYS) ctx.addIssue({ code: 'custom', path: ['to'], message: `The range can be at most ${MAX_SLOT_RANGE_DAYS} days` })
  })
export type SlotRangeValues = z.infer<typeof slotRangeSchema>

/** A UTC instant shown in the organization timezone (e.g. "Mon 16 Nov 2026, 09:00"). */
export function formatInstant(instant: string, timeZone: string): string {
  return new Intl.DateTimeFormat('en-GB', {
    timeZone, weekday: 'short', day: '2-digit', month: 'short', year: 'numeric', hour: '2-digit', minute: '2-digit', hourCycle: 'h23',
  }).format(new Date(instant))
}

export function formatTime(instant: string, timeZone: string): string {
  return new Intl.DateTimeFormat('en-GB', { timeZone, hour: '2-digit', minute: '2-digit', hourCycle: 'h23' }).format(new Date(instant))
}

export const WEEKDAYS = ['Monday', 'Tuesday', 'Wednesday', 'Thursday', 'Friday', 'Saturday', 'Sunday']

export const STAFF_TYPE_LABELS: Record<string, string> = {
  NURSE: 'Nurse', RECEPTIONIST: 'Receptionist', ACCOUNTANT: 'Accountant', LAB_TECHNICIAN: 'Lab technician',
  PHARMACIST: 'Pharmacist', ADMINISTRATIVE: 'Administrative', OTHER: 'Other',
}
