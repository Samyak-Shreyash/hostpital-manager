import { api } from './client'
import type { Page } from './types'

/** api-design.md sections 8 and 9. Ids are opaque strings. Clinical free text is rendered, never logged. */
export type EncounterStatus = 'OPEN' | 'FINALIZED'
export type NoteKind = 'SYMPTOMS' | 'EXAMINATION' | 'ASSESSMENT' | 'ADDENDUM'
export type ObservationKind = 'BP_SYS' | 'BP_DIA' | 'PULSE' | 'TEMP_C' | 'SPO2' | 'RESP_RATE' | 'WEIGHT_KG' | 'HEIGHT_CM'

export interface Observation { kind: ObservationKind; value: string | number; unit: string }
export interface Warning { kind: string; message: string }
export interface Note { kind: NoteKind; text: string; authorId: string; createdAt: string }
export interface Diagnosis { code: string | null; description: string; primary: boolean }

export interface Encounter {
  id: string
  patient: { id: string; uhid: string | null }
  practitionerId: string
  appointmentId: string | null
  status: EncounterStatus
  chiefComplaint: string | null
  version: number
  startedAt: string
  finalizedAt: string | null
  observations: Observation[]
  warnings: Warning[]
  notes: Note[]
  diagnoses: Diagnosis[]
  treatmentPlan: string | null
  followUp: { date: string; instructions: string | null } | null
}

export interface EncounterSummary {
  id: string
  practitionerId: string
  appointmentId: string | null
  status: EncounterStatus
  chiefComplaint: string | null
  startedAt: string
  finalizedAt: string | null
}

export interface PrescriptionItem {
  drugName: string
  strength: string | null
  form: string | null
  route: string | null
  dose: string
  frequency: string
  durationDays: number
  instructions: string | null
}

export type PrescriptionStatus = 'DRAFT' | 'ISSUED' | 'SUPERSEDED'

export interface Prescription {
  id: string
  encounterId: string
  patientId: string
  status: PrescriptionStatus
  versionNo: number
  supersedesId: string | null
  issuedAt: string | null
  issuedBy: string | null
  version: number
  items: PrescriptionItem[]
  allergyWarnings: { substance: string; severity: string }[]
}

const enc = encodeURIComponent

export const startEncounter = (body: { appointmentId?: string; patientId?: string; chiefComplaint?: string }) => api.post<Encounter>('/encounters', body)
export const getEncounter = (id: string) => api.get<Encounter>(`/encounters/${enc(id)}`)
export const patientEncounters = (patientId: string, page = 0, size = 20) =>
  api.get<Page<EncounterSummary>>(`/patients/${enc(patientId)}/encounters?page=${page}&size=${size}`)
/** Without `encounter:read` the server answers with a reduced body; the consult screen requires the read permission. */
export const putObservations = (id: string, version: number, observations: { kind: string; value: number; unit: string }[]) =>
  api.put<Encounter>(`/encounters/${enc(id)}/observations`, { version, observations })
export const putNote = (id: string, kind: string, version: number, text: string) => api.put<Encounter>(`/encounters/${enc(id)}/notes/${kind}`, { version, text })
export const putDiagnoses = (id: string, version: number, diagnoses: { code?: string; description: string; primary: boolean }[]) =>
  api.put<Encounter>(`/encounters/${enc(id)}/diagnoses`, { version, diagnoses })
export const putTreatmentPlan = (id: string, version: number, text: string) => api.put<Encounter>(`/encounters/${enc(id)}/treatment-plan`, { version, text })
export const putFollowUp = (id: string, version: number, date: string, instructions: string) =>
  api.put<Encounter>(`/encounters/${enc(id)}/follow-up`, { version, date, instructions })
export const finalizeEncounter = (id: string, version: number) => api.post<Encounter>(`/encounters/${enc(id)}/finalize`, { version })
export const addAddendum = (id: string, text: string) => api.post<Encounter>(`/encounters/${enc(id)}/addenda`, { text })

export const createPrescription = (encounterId: string) => api.post<Prescription>(`/encounters/${enc(encounterId)}/prescriptions`)
export const getPrescription = (id: string) => api.get<Prescription>(`/prescriptions/${enc(id)}`)
export const putPrescriptionItems = (id: string, version: number, items: PrescriptionItem[]) =>
  api.put<Prescription>(`/prescriptions/${enc(id)}`, { version, items })
export const issuePrescription = (id: string, version: number, acknowledgeAllergyWarnings: boolean) =>
  api.post<Prescription>(`/prescriptions/${enc(id)}/issue`, { version, acknowledgeAllergyWarnings })
export const revisePrescription = (id: string) => api.post<Prescription>(`/prescriptions/${enc(id)}/revise`)
export const prescriptionPdfUrl = (id: string) => `/api/v1/prescriptions/${enc(id)}/pdf`

export const clinicalKeys = {
  encounter: (id: string) => ['encounters', id] as const,
  history: (patientId: string) => ['encounters', 'history', patientId] as const,
  prescription: (id: string) => ['prescriptions', id] as const,
}
