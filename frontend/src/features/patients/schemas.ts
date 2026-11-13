import { z } from 'zod'

/** Mirrors the backend formats (CreatePatientRequest): 14 digits, optionally 2-4-4-4 with hyphens. */
export const ABHA_PATTERN = /^(\d{14}|\d{2}-\d{4}-\d{4}-\d{4})$/
/** E.164 or a bare 10-digit Indian number. */
export const PHONE_PATTERN = /^(\+[1-9]\d{7,14}|\d{10})$/

const optionalAbha = z.string().trim().refine((v) => v === '' || ABHA_PATTERN.test(v), 'ABHA number must be 14 digits')
const notFuture = (v: string) => v === '' || v <= new Date().toISOString().slice(0, 10)

/** Address is optional, but once any part is entered the required parts must all be present. */
const addressFields = {
  line1: z.string().trim().max(150),
  line2: z.string().trim().max(150),
  city: z.string().trim().max(80),
  state: z.string().trim().max(80),
  pincode: z.string().trim(),
}
type AddressValues = { line1: string; line2: string; city: string; state: string; pincode: string }

function checkAddress(v: AddressValues, ctx: z.RefinementCtx) {
  const any = v.line1 || v.line2 || v.city || v.state || v.pincode
  if (!any) return
  if (!v.line1) ctx.addIssue({ code: 'custom', path: ['line1'], message: 'Address line 1 is required' })
  if (!v.city) ctx.addIssue({ code: 'custom', path: ['city'], message: 'City is required' })
  if (!v.state) ctx.addIssue({ code: 'custom', path: ['state'], message: 'State is required' })
  if (!/^\d{6}$/.test(v.pincode)) ctx.addIssue({ code: 'custom', path: ['pincode'], message: 'Pincode must be 6 digits' })
}

export function toAddress(v: AddressValues) {
  if (!v.line1) return undefined
  return { line1: v.line1, line2: v.line2 || undefined, city: v.city, state: v.state, pincode: v.pincode }
}

export const registerSchema = z
  .object({
    fullName: z.string().trim().min(2, 'Full name must be at least 2 characters').max(120, 'Full name is too long'),
    sex: z.enum(['M', 'F', 'O', 'U'], { message: 'Select sex' }),
    dateOfBirth: z.string().refine(notFuture, 'Date of birth cannot be in the future'),
    ageYears: z.string().trim().refine((v) => v === '' || (/^\d{1,3}$/.test(v) && Number(v) <= 150), 'Enter age in whole years'),
    phone: z.string().trim().regex(PHONE_PATTERN, 'Enter a 10-digit mobile number or +<country><number>'),
    abhaNumber: optionalAbha,
    ...addressFields,
  })
  .superRefine((v, ctx) => {
    if (!v.dateOfBirth && !v.ageYears) {
      ctx.addIssue({ code: 'custom', path: ['dateOfBirth'], message: 'Enter the date of birth or the age' })
    }
    checkAddress(v, ctx)
  })
export type RegisterValues = z.infer<typeof registerSchema>

export const updateSchema = z
  .object({
    fullName: z.string().trim().min(2, 'Full name must be at least 2 characters').max(120, 'Full name is too long'),
    sex: z.enum(['M', 'F', 'O', 'U']),
    dateOfBirth: z.string().refine(notFuture, 'Date of birth cannot be in the future'),
    phone: z.string().trim().regex(PHONE_PATTERN, 'Enter a 10-digit mobile number or +<country><number>'),
    abhaNumber: optionalAbha,
    identityChangeReason: z.string().trim().max(500, 'Reason is too long'),
    ...addressFields,
  })
  .superRefine((v, ctx) => checkAddress(v, ctx))
export type UpdateValues = z.infer<typeof updateSchema>
