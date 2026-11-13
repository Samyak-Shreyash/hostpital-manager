import { api } from './client'
import type { Page } from './types'

/** api-design.md sections 4 and 5. Ids are opaque strings; people see names and registration numbers. */
export type ActiveStatus = 'ACTIVE' | 'INACTIVE'

export interface CatalogItem {
  id: string
  code: string
  name: string
  active: boolean
  version: number
}

export interface Doctor {
  id: string
  userId: string
  fullName: string
  registrationNo: string
  registrationCouncil: string | null
  qualifications: string[]
  defaultSlotMinutes: number
  status: ActiveStatus
  primarySpecialtyId: string
  specialtyIds: string[]
  departmentIds: string[]
  version: number
  createdAt: string
  updatedAt: string
}

export interface RegisterDoctorBody {
  userId: string
  fullName: string
  registrationNo: string
  registrationCouncil?: string
  qualifications?: string[]
  defaultSlotMinutes?: number
  specialtyIds: string[]
  primarySpecialtyId: string
  departmentIds?: string[]
}

export interface UpdateDoctorBody {
  version: number
  fullName?: string
  registrationNo?: string
  registrationCouncil?: string
  qualifications?: string[]
  defaultSlotMinutes?: number
  status?: ActiveStatus
}

export const STAFF_TYPES = ['NURSE', 'RECEPTIONIST', 'ACCOUNTANT', 'LAB_TECHNICIAN', 'PHARMACIST', 'ADMINISTRATIVE', 'OTHER'] as const
export type StaffType = (typeof STAFF_TYPES)[number]

/** Roles are deliberately absent: they belong to the linked user (PATCH /users/{id}, review notes Q8). */
export interface Staff {
  id: string
  userId: string
  fullName: string
  staffType: StaffType
  designation: string | null
  departmentId: string | null
  status: ActiveStatus
  version: number
  createdAt: string
  updatedAt: string
}

export interface RegisterStaffBody {
  userId: string
  fullName: string
  staffType: StaffType
  designation?: string
  departmentId?: string
}

export interface UpdateStaffBody {
  version: number
  fullName?: string
  staffType?: StaffType
  designation?: string
  departmentId?: string
  clearDepartment?: boolean
  status?: ActiveStatus
}

/** Weekday 1=Mon..7=Sun; times "HH:mm" local to the organization timezone. */
export interface Window {
  weekday: number
  start: string
  end: string
  slotMinutes: number
}

export interface Availability {
  practitionerId: string
  timezone: string
  windows: Window[]
  /** The doctor's version. */
  version: number
}

export interface LeaveException {
  id: string
  practitionerId: string
  date: string
  start: string | null
  end: string | null
  wholeDay: boolean
  status: 'ACTIVE' | 'CANCELLED'
  version: number
}

export interface Slots {
  practitionerId: string
  timezone: string
  from: string
  to: string
  slots: { startAt: string; endAt: string }[]
}

const enc = encodeURIComponent

function query(filters: object): string {
  const params = new URLSearchParams()
  for (const [key, value] of Object.entries(filters)) {
    if (value !== undefined && value !== '') params.set(key, String(value))
  }
  return params.toString()
}

/** At most 100 per page (api-design.md section 1); enough for a clinic's catalogue. */
export const listSpecialties = () => api.get<Page<CatalogItem>>('/specialties?size=100')
export const listDepartments = () => api.get<Page<CatalogItem>>('/departments?size=100')

export interface DoctorFilters { specialtyId?: string; departmentId?: string; q?: string; status?: string; page?: number; size?: number }
export const listDoctors = (f: DoctorFilters) => api.get<Page<Doctor>>(`/doctors?${query(f)}`)
export const getDoctor = (id: string) => api.get<Doctor>(`/doctors/${enc(id)}`)
export const registerDoctor = (body: RegisterDoctorBody) => api.post<Doctor>('/doctors', body)
export const updateDoctor = (id: string, body: UpdateDoctorBody) => api.patch<Doctor>(`/doctors/${enc(id)}`, body)
export const replaceSpecialties = (id: string, body: { version: number; specialtyIds: string[]; primarySpecialtyId: string }) =>
  api.put<Doctor>(`/doctors/${enc(id)}/specialties`, body)
export const replaceDepartments = (id: string, body: { version: number; departmentIds: string[] }) =>
  api.put<Doctor>(`/doctors/${enc(id)}/departments`, body)

export const getAvailability = (id: string) => api.get<Availability>(`/doctors/${enc(id)}/availability`)
export const replaceAvailability = (id: string, body: { version: number; windows: Window[] }) =>
  api.put<Availability>(`/doctors/${enc(id)}/availability`, body)
export const listLeave = (id: string) => api.get<LeaveException[]>(`/doctors/${enc(id)}/availability-exceptions`)
export const addLeave = (id: string, body: { date: string; start?: string; end?: string }) =>
  api.post<LeaveException>(`/doctors/${enc(id)}/availability-exceptions`, body)
export const cancelLeave = (id: string, leaveId: string, version: number) =>
  api.post<LeaveException>(`/doctors/${enc(id)}/availability-exceptions/${enc(leaveId)}/cancel`, { version })
export const getSlots = (id: string, from: string, to: string) => api.get<Slots>(`/doctors/${enc(id)}/slots?${query({ from, to })}`)

export interface StaffFilters { staffType?: string; departmentId?: string; q?: string; status?: string; page?: number; size?: number }
export const listStaff = (f: StaffFilters) => api.get<Page<Staff>>(`/staff?${query(f)}`)
export const getStaff = (id: string) => api.get<Staff>(`/staff/${enc(id)}`)
export const registerStaff = (body: RegisterStaffBody) => api.post<Staff>('/staff', body)
export const updateStaff = (id: string, body: UpdateStaffBody) => api.patch<Staff>(`/staff/${enc(id)}`, body)

export const practitionerKeys = {
  specialties: ['catalog', 'specialties'] as const,
  departments: ['catalog', 'departments'] as const,
  doctors: (f: unknown) => ['doctors', 'list', f] as const,
  doctor: (id: string) => ['doctors', 'detail', id] as const,
  availability: (id: string) => ['doctors', 'detail', id, 'availability'] as const,
  leave: (id: string) => ['doctors', 'detail', id, 'leave'] as const,
  slots: (id: string, from: string, to: string) => ['doctors', 'detail', id, 'slots', from, to] as const,
  staffList: (f: unknown) => ['staff', 'list', f] as const,
  staff: (id: string) => ['staff', 'detail', id] as const,
}
