import { api } from '../../api/client'
import type { Page } from '../../api/types'

/** api-design.md sections 10 and 11. Ids are opaque strings; money is a decimal string, never a number. */
export type CareType = 'GENERAL' | 'CRITICAL_CARE'
export type ActiveStatus = 'ACTIVE' | 'INACTIVE'
export type BedStatus = 'ACTIVE' | 'OUT_OF_SERVICE'
export type AdmissionStatus = 'ADMITTED' | 'DISCHARGE_INITIATED' | 'DISCHARGED' | 'CANCELLED'

export interface Ward {
  id: string
  code: string
  name: string
  departmentId: string | null
  status: ActiveStatus
  careType: CareType
  version: number
}

export interface Bed {
  id: string
  wardId: string
  bedNo: string
  bedClass: string | null
  dailyRateServiceId: string
  status: BedStatus
  version: number
}

export interface AvailabilityBed {
  id: string
  bedNo: string
  bedClass: string | null
  ward: { id: string; name: string; careType: CareType }
  status: BedStatus
  occupied: boolean
  admissionId: string | null
}

export interface WardAvailability {
  ward: { id: string; name: string; careType: CareType }
  code: string
  status: ActiveStatus
  total: number
  occupied: number
  available: number
  outOfService: number
  beds: AvailabilityBed[]
}

export interface Admission {
  id: string
  admissionNo: string
  patientId: string
  patient: { id: string; uhid: string | null; name: string | null }
  admittingDoctorId: string
  sourceEncounterId: string | null
  admissionDiagnosis: string
  status: AdmissionStatus
  admittedAt: string
  plannedDischargeAt: string | null
  dischargedAt: string | null
  currentBed: { id: string; bedNo: string; ward: string } | null
  version: number
}

export interface BedAssignment {
  id: string
  bedId: string
  bedNo: string
  wardName: string
  dailyRateServiceId: string
  assignedAt: string
  releasedAt: string | null
  reason: string | null
  criticalCare: boolean
}

export interface Medication {
  name: string
  dose?: string | null
  frequency?: string | null
  duration?: string | null
}

export interface DischargeSummary {
  id: string
  admissionId: string
  finalDiagnosis: string
  courseInHospital: string | null
  procedures: string | null
  conditionAtDischarge: string
  medications: Medication[]
  followUp: string | null
  plannedDischargeAt: string
  version: number
}

export interface SaveSummaryBody {
  version?: number
  finalDiagnosis: string
  courseInHospital?: string
  procedures?: string
  conditionAtDischarge: string
  medications: Medication[]
  followUp?: string
  plannedDischargeAt: string
}

export interface ChargeLine {
  description: string
  quantity: string
  unitPrice: string
  amount: string
  taxRate: string
  tax: string
  from: string
  to: string
}

export interface ChargesPreview {
  asOf: string
  lines: ChargeLine[]
  roomTotal: string
}

const enc = encodeURIComponent

function query(filters: object): string {
  const params = new URLSearchParams()
  for (const [key, value] of Object.entries(filters)) {
    if (value !== undefined && value !== '') params.set(key, String(value))
  }
  return params.toString()
}

export const listWards = () => api.get<Page<Ward>>('/wards?size=100')
export const createWard = (body: { code: string; name: string; careType: CareType }) => api.post<Ward>('/wards', body)
export const updateWard = (id: string, body: { version: number; name?: string; status?: ActiveStatus; careType?: CareType }) =>
  api.patch<Ward>(`/wards/${enc(id)}`, body)
export const listBeds = (wardId: string) => api.get<Page<Bed>>(`/wards/${enc(wardId)}/beds?size=100`)
export const createBed = (wardId: string, body: { bedNo: string; bedClass?: string; dailyRateServiceId: string }) =>
  api.post<Bed>(`/wards/${enc(wardId)}/beds`, body)
export const updateBed = (id: string, body: { version: number; bedClass?: string; dailyRateServiceId?: string; status?: BedStatus }) =>
  api.patch<Bed>(`/beds/${enc(id)}`, body)
export const getAvailability = (wardId?: string) => api.get<{ wards: WardAvailability[] }>(`/beds/availability?${query({ wardId })}`)

export const listAdmissions = (f: { status?: string; page?: number; size?: number }) => api.get<Page<Admission>>(`/admissions?${query(f)}`)
export const getAdmission = (id: string) => api.get<Admission>(`/admissions/${enc(id)}`)
export const admitPatient = (body: { patientId: string; admittingDoctorId: string; bedId: string; admissionDiagnosis: string }) =>
  api.post<Admission>('/admissions', body)
export const updateAdmission = (id: string, body: { version: number; admissionDiagnosis: string }) => api.patch<Admission>(`/admissions/${enc(id)}`, body)
export const cancelAdmission = (id: string, version: number) => api.post<Admission>(`/admissions/${enc(id)}/cancel`, { version })
export const listBedAssignments = (id: string) => api.get<BedAssignment[]>(`/admissions/${enc(id)}/bed-assignments`)
export const transferBed = (id: string, body: { toBedId: string; reason: string }) =>
  api.post<{ currentAssignment: BedAssignment; previousAssignment: BedAssignment }>(`/admissions/${enc(id)}/bed-assignments/transfer`, body)
export const getDischargeSummary = (id: string) => api.get<DischargeSummary>(`/admissions/${enc(id)}/discharge-summary`)
export const saveDischargeSummary = (id: string, body: SaveSummaryBody) => api.put<DischargeSummary>(`/admissions/${enc(id)}/discharge-summary`, body)
export const dischargeAdmission = (id: string, body: { version: number; dischargedAt: string; invoiceId?: string; deferBilling: boolean }) =>
  api.post<{ status: string; bedReleased: boolean }>(`/admissions/${enc(id)}/discharge`, body)
export const getChargesPreview = (id: string) => api.get<ChargesPreview>(`/admissions/${enc(id)}/charges-preview`)
export const createIpdInvoice = (body: { patientId: string; admissionId: string; kind: 'INTERIM' | 'FINAL' }) =>
  api.post<{ id: string }>('/invoices', { type: 'IPD', ...body })

export const ipdKeys = {
  wards: ['ipd', 'wards'] as const,
  beds: (wardId: string) => ['ipd', 'beds', wardId] as const,
  availability: ['ipd', 'availability'] as const,
  admissions: ['ipd', 'admissions'] as const,
  admission: (id: string) => ['ipd', 'admission', id] as const,
  assignments: (id: string) => ['ipd', 'admission', id, 'assignments'] as const,
  summary: (id: string) => ['ipd', 'admission', id, 'summary'] as const,
  charges: (id: string) => ['ipd', 'admission', id, 'charges'] as const,
}
