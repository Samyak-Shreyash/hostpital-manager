import { api } from './client'
import type { Page } from './types'

/** api-design.md section 7. Ids are opaque strings; people see names, UHIDs and queue tokens. */
export type AppointmentStatus = 'SCHEDULED' | 'CHECKED_IN' | 'IN_CONSULTATION' | 'COMPLETED' | 'CANCELLED' | 'NO_SHOW' | 'RESCHEDULED'

/** UHID and name only, resolved by the server (no patient-read audit); null parts when the patient is unreadable. */
export interface PatientBrief {
  id: string
  uhid: string | null
  name: string | null
}

export interface Appointment {
  id: string
  patientId: string
  /** The practitioner id. */
  doctorId: string
  startAt: string
  endAt: string
  durationMinutes: number
  type: string
  status: AppointmentStatus
  reason: string | null
  outsideAvailability: boolean
  version: number
  createdAt: string
  updatedAt: string
  rescheduledFromId: string | null
  cancelReason: string | null
  cancelledAt: string | null
  tokenNo: number | null
  tokenDate: string | null
  patient: PatientBrief
}

export interface BookBody {
  patientId: string
  doctorId: string
  startAt: string
  durationMinutes: number
  reason?: string
  outsideAvailability?: boolean
}

export interface QueueEntry {
  appointmentId: string
  patient: PatientBrief
  tokenNo: number
  status: AppointmentStatus
}

export interface Queue {
  doctorId: string
  date: string
  items: QueueEntry[]
}

const enc = encodeURIComponent

function query(filters: object): string {
  const params = new URLSearchParams()
  for (const [key, value] of Object.entries(filters)) {
    if (value !== undefined && value !== '') params.set(key, String(value))
  }
  return params.toString()
}

export const listAppointments = (f: { doctorId?: string; patientId?: string; date?: string; status?: string; page?: number; size?: number }) =>
  api.get<Page<Appointment>>(`/appointments?${query(f)}`)
export const bookAppointment = (body: BookBody) => api.post<Appointment>('/appointments', body)
export const rescheduleAppointment = (id: string, body: { version: number; startAt: string; durationMinutes: number }) =>
  api.post<Appointment>(`/appointments/${enc(id)}/reschedule`, body)
export const cancelAppointment = (id: string, body: { version: number; reason: string }) =>
  api.post<Appointment>(`/appointments/${enc(id)}/cancel`, body)
export const checkInAppointment = (id: string, version: number) => api.post<Appointment>(`/appointments/${enc(id)}/check-in`, { version })
export const markNoShow = (id: string, version: number) => api.post<Appointment>(`/appointments/${enc(id)}/no-show`, { version })
export const walkIn = (body: { patientId: string; doctorId: string; reason?: string }) => api.post<Appointment>('/appointments/walk-in', body)
export const getQueue = (doctorId: string, date?: string) => api.get<Queue>(`/queue?${query({ doctorId, date })}`)

export const appointmentKeys = {
  all: ['appointments'] as const,
  day: (doctorId: string, date: string) => ['appointments', 'day', doctorId, date] as const,
  queue: (doctorId: string, date: string) => ['appointments', 'queue', doctorId, date] as const,
}
