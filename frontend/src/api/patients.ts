import { api } from './client'
import type { Page } from './types'

/** api-design.md section 6. Ids are opaque; the UHID is the business key shown to people. */
export type Sex = 'M' | 'F' | 'O' | 'U'

export interface Address {
  line1: string
  line2?: string | null
  city: string
  state: string
  pincode: string
}

export interface EmergencyContact {
  name: string
  relationship: string
  phone: string
}

export interface PatientSummary {
  id: string
  uhid: string
  fullName: string
  sex: Sex
  dateOfBirth: string | null
  phoneMasked: string
  abhaLast4: string | null
  status: string
}

/** Detail view: full phone; the ABHA number is never returned, only its last 4 digits. */
export interface Patient {
  id: string
  uhid: string
  fullName: string
  sex: Sex
  dateOfBirth: string | null
  dobEstimated: boolean
  phone: string
  abhaLast4: string | null
  address: Address | null
  emergencyContact: EmergencyContact | null
  status: string
  version: number
  createdAt: string
  updatedAt: string
}

export interface DuplicateCandidate {
  id: string
  uhid: string
  fullName: string
  dateOfBirth: string | null
  phoneMasked: string
  score: number | string
}

export interface RegisterPatientBody {
  fullName: string
  sex: Sex
  dateOfBirth?: string
  ageYears?: number
  dobEstimated?: boolean
  phone: string
  address?: Address
  abhaNumber?: string
  confirmNotDuplicate?: boolean
  overrideReason?: string
}

export interface UpdatePatientBody {
  version: number
  fullName?: string
  sex?: Sex
  dateOfBirth?: string
  phone?: string
  address?: Address
  abhaNumber?: string
  identityChangeReason?: string
}

export type Severity = 'MILD' | 'MODERATE' | 'SEVERE'
export type EntryStatus = 'ACTIVE' | 'ENTERED_IN_ERROR'

export interface Allergy {
  id: string
  substance: string
  reaction: string | null
  severity: Severity
  status: EntryStatus
  recordedAt: string
  enteredInErrorAt: string | null
}

export interface HistoryEntry {
  id: string
  condition: string
  notes: string | null
  status: EntryStatus
  recordedAt: string
  enteredInErrorAt: string | null
}

export interface PatientSearch {
  q?: string
  uhid?: string
  phone?: string
  dob?: string
  page?: number
  size?: number
}

const enc = encodeURIComponent

export function searchPatients(filters: PatientSearch) {
  const params = new URLSearchParams()
  for (const [key, value] of Object.entries(filters)) {
    if (value !== undefined && value !== '') params.set(key, String(value))
  }
  return api.get<Page<PatientSummary>>(`/patients?${params.toString()}`)
}

/** ABHA goes in the body, never the URL. */
export const searchByAbha = (abhaNumber: string, page = 0, size = 20) =>
  api.post<Page<PatientSummary>>(`/patients/search-by-abha?page=${page}&size=${size}`, { abhaNumber })

export const registerPatient = (body: RegisterPatientBody) => api.post<Patient>('/patients', body)
export const getPatient = (id: string) => api.get<Patient>(`/patients/${enc(id)}`)
export const updatePatient = (id: string, body: UpdatePatientBody) => api.patch<Patient>(`/patients/${enc(id)}`, body)

export const listAllergies = (id: string) => api.get<Allergy[]>(`/patients/${enc(id)}/allergies`)
export const addAllergy = (id: string, body: { substance: string; reaction?: string; severity: Severity }) =>
  api.post<Allergy>(`/patients/${enc(id)}/allergies`, body)
export const markAllergyInError = (id: string, entryId: string) =>
  api.patch<Allergy>(`/patients/${enc(id)}/allergies/${enc(entryId)}`, { status: 'ENTERED_IN_ERROR' })

export const listHistory = (id: string) => api.get<HistoryEntry[]>(`/patients/${enc(id)}/medical-history`)
export const addHistory = (id: string, body: { condition: string; notes?: string }) =>
  api.post<HistoryEntry>(`/patients/${enc(id)}/medical-history`, body)
export const markHistoryInError = (id: string, entryId: string) =>
  api.patch<HistoryEntry>(`/patients/${enc(id)}/medical-history/${enc(entryId)}`, { status: 'ENTERED_IN_ERROR' })

export const patientKeys = {
  all: ['patients'] as const,
  search: (filters: unknown) => ['patients', 'search', filters] as const,
  detail: (id: string) => ['patients', 'detail', id] as const,
  allergies: (id: string) => ['patients', 'detail', id, 'allergies'] as const,
  history: (id: string) => ['patients', 'detail', id, 'history'] as const,
}
