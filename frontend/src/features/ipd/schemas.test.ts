import { admitSchema, bedSchema, dischargeSchema, summarySchema, transferSchema, wardSchema } from './schemas'

const SUMMARY = {
  finalDiagnosis: 'Recovered', courseInHospital: '', procedures: '', conditionAtDischarge: 'Stable', followUp: '',
  plannedDischargeAt: '2026-11-22T10:00', medications: [],
}

describe('ipd schemas', () => {
  it('requires the final diagnosis, condition and planned discharge on the summary', () => {
    expect(summarySchema.safeParse(SUMMARY).success).toBe(true)
    for (const field of ['finalDiagnosis', 'conditionAtDischarge', 'plannedDischargeAt'] as const) {
      expect(summarySchema.safeParse({ ...SUMMARY, [field]: '' }).success).toBe(false)
    }
    expect(summarySchema.safeParse({ ...SUMMARY, medications: [{ name: '', dose: '', frequency: '', duration: '' }] }).success).toBe(false)
  })

  it('requires a reason and a bed to transfer', () => {
    expect(transferSchema.safeParse({ toBedId: 'b-2', reason: 'Higher acuity' }).success).toBe(true)
    expect(transferSchema.safeParse({ toBedId: 'b-2', reason: '   ' }).success).toBe(false)
    expect(transferSchema.safeParse({ toBedId: '', reason: 'x' }).success).toBe(false)
  })

  it('treats ids as opaque strings', () => {
    expect(admitSchema.safeParse({ patientId: 'not-a-uuid', admittingDoctorId: 'd-1', bedId: 'b-2', admissionDiagnosis: 'Fever' }).success).toBe(true)
    expect(admitSchema.safeParse({ patientId: 'p-1', admittingDoctorId: 'd-1', bedId: '', admissionDiagnosis: 'Fever' }).success).toBe(false)
    expect(bedSchema.safeParse({ bedNo: 'G-1', bedClass: '', dailyRateServiceId: 's-1' }).success).toBe(true)
  })

  it('validates ward codes and discharge time', () => {
    expect(wardSchema.safeParse({ code: 'icu', name: 'ICU', careType: 'CRITICAL_CARE' }).success).toBe(false)
    expect(wardSchema.safeParse({ code: 'ICU', name: 'ICU', careType: 'CRITICAL_CARE' }).success).toBe(true)
    expect(dischargeSchema.safeParse({ dischargedAt: '', deferBilling: false }).success).toBe(false)
  })
})
