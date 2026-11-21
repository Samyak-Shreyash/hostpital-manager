import { z } from 'zod'

/** Ids are opaque strings: required, never format-checked. */
export const wardSchema = z.object({
  code: z.string().trim().regex(/^[A-Z0-9_]{2,20}$/, 'Code is 2 to 20 capital letters, digits or underscores'),
  name: z.string().trim().min(1, 'Enter a name').max(100, 'Name is at most 100 characters'),
  careType: z.enum(['GENERAL', 'CRITICAL_CARE']),
})
export type WardValues = z.infer<typeof wardSchema>

export const bedSchema = z.object({
  bedNo: z.string().trim().min(1, 'Enter a bed number').max(20, 'Bed number is at most 20 characters'),
  bedClass: z.string().trim().max(50, 'Class is at most 50 characters'),
  dailyRateServiceId: z.string().min(1, 'Choose the daily rate service'),
})
export type BedValues = z.infer<typeof bedSchema>

export const admitSchema = z.object({
  patientId: z.string().min(1, 'Select a patient'),
  admittingDoctorId: z.string().min(1, 'Select a doctor'),
  bedId: z.string().min(1, 'Select an available bed'),
  admissionDiagnosis: z.string().trim().min(1, 'Enter the admission diagnosis').max(1000, 'Diagnosis is at most 1000 characters'),
})
export type AdmitValues = z.infer<typeof admitSchema>

export const transferSchema = z.object({
  toBedId: z.string().min(1, 'Select the new bed'),
  reason: z.string().trim().min(1, 'Enter a reason for the transfer').max(500, 'Reason is at most 500 characters'),
})
export type TransferValues = z.infer<typeof transferSchema>

const optionalText = (max: number) => z.string().trim().max(max, `At most ${max} characters`)

export const summarySchema = z.object({
  finalDiagnosis: z.string().trim().min(1, 'Enter the final diagnosis').max(2000, 'At most 2000 characters'),
  courseInHospital: optionalText(4000),
  procedures: optionalText(4000),
  conditionAtDischarge: z.string().trim().min(1, 'Enter the condition at discharge').max(2000, 'At most 2000 characters'),
  followUp: optionalText(2000),
  plannedDischargeAt: z.string().min(1, 'Enter the planned discharge time'),
  medications: z.array(z.object({
    name: z.string().trim().min(1, 'Enter the medicine name').max(200, 'At most 200 characters'),
    dose: optionalText(100),
    frequency: optionalText(100),
    duration: optionalText(100),
  })).max(50, 'At most 50 medicines'),
})
export type SummaryValues = z.infer<typeof summarySchema>

export const dischargeSchema = z.object({
  dischargedAt: z.string().min(1, 'Enter the discharge time'),
  deferBilling: z.boolean(),
})
export type DischargeValues = z.infer<typeof dischargeSchema>
