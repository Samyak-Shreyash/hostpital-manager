import { fireEvent, screen, waitFor, within } from '@testing-library/react'
import type { Availability, Doctor, Staff } from '../../api/practitioners'
import { json, mockApi, renderApp, session } from '../../test/helpers'
import { daysInclusive, formatInstant, slotRangeSchema, specialtiesSchema, windowProblems } from './schemas'

// Synthetic data only.
const SPECIALTIES = {
  items: [
    { id: 'sp-1', code: 'GEN', name: 'General Medicine', active: true, version: 0 },
    { id: 'sp-2', code: 'CARD', name: 'Cardiology', active: true, version: 0 },
  ],
  page: 0, size: 100, totalItems: 2,
}
const DEPARTMENTS = { items: [{ id: 'dp-1', code: 'OPD', name: 'Outpatients', active: true, version: 0 }], page: 0, size: 100, totalItems: 1 }
const USERS = {
  items: [{ id: 'u-9', email: 'test.doctor@example.test', fullName: 'Test Doctor', status: 'ACTIVE', mustChangePassword: false, roleCodes: [], version: 0, createdAt: '', updatedAt: '' }],
  page: 0, size: 100, totalItems: 1,
}
const DOCTOR: Doctor = {
  id: 'd-1', userId: 'u-9', fullName: 'Dr. Test Example', registrationNo: 'DEMO-0001', registrationCouncil: 'DEMO',
  qualifications: ['MBBS'], defaultSlotMinutes: 15, status: 'ACTIVE', primarySpecialtyId: 'sp-1', specialtyIds: ['sp-1'],
  departmentIds: ['dp-1'], version: 4, createdAt: '2026-01-01T00:00:00Z', updatedAt: '2026-01-01T00:00:00Z',
}
const AVAILABILITY: Availability = { practitionerId: 'd-1', timezone: 'Asia/Kolkata', windows: [{ weekday: 1, start: '09:00', end: '13:00', slotMinutes: 15 }], version: 4 }
const LEAVE = { id: 'l-1', practitionerId: 'd-1', date: '2026-11-20', start: null, end: null, wholeDay: true, status: 'ACTIVE', version: 0 }
const STAFF: Staff = {
  id: 's-1', userId: 'u-9', fullName: 'Test Nurse', staffType: 'NURSE', designation: 'Staff Nurse', departmentId: 'dp-1',
  status: 'ACTIVE', version: 2, createdAt: '2026-01-01T00:00:00Z', updatedAt: '2026-01-01T00:00:00Z',
}
const DOCTOR_PERMS = ['practitioner:read', 'practitioner:create', 'practitioner:update', 'practitioner:schedule', 'appointment:read', 'catalog:read', 'user:read']
const STAFF_PERMS = ['staff:read', 'staff:create', 'staff:update', 'catalog:read', 'user:read']

type Routes = Parameters<typeof mockApi>[0]
const catalogRoutes: Routes = {
  'GET /api/v1/specialties?size=100': json(200, SPECIALTIES),
  'GET /api/v1/departments?size=100': json(200, DEPARTMENTS),
  'GET /api/v1/users?status=ACTIVE&size=100': json(200, USERS),
}
function doctorRoutes(permissions: string[], extra: Routes = {}) {
  return mockApi({
    'GET /api/v1/auth/me': json(200, session(permissions)),
    ...catalogRoutes,
    'GET /api/v1/doctors/d-1': json(200, DOCTOR),
    'GET /api/v1/doctors/d-1/availability': json(200, AVAILABILITY),
    'GET /api/v1/doctors/d-1/availability-exceptions': json(200, [LEAVE]),
    ...extra,
  })
}
const bodyOf = (init?: RequestInit) => JSON.parse(String(init?.body))

describe('practitioner validation rules', () => {
  it('requires exactly one primary among the selected specialties', () => {
    expect(specialtiesSchema.safeParse({ specialtyIds: [], primarySpecialtyId: '' }).success).toBe(false)
    expect(specialtiesSchema.safeParse({ specialtyIds: ['sp-1', 'sp-2'], primarySpecialtyId: '' }).success).toBe(false)
    expect(specialtiesSchema.safeParse({ specialtyIds: ['sp-1'], primarySpecialtyId: 'sp-2' }).success).toBe(false)
    expect(specialtiesSchema.safeParse({ specialtyIds: ['sp-1', 'sp-2'], primarySpecialtyId: 'sp-2' }).success).toBe(true)
  })

  it('rejects overlapping windows on the same weekday but allows touching and other days', () => {
    const w = (weekday: number, start: string, end: string) => ({ weekday, start, end, slotMinutes: 15 })
    expect(windowProblems([w(1, '09:00', '13:00'), w(1, '12:00', '14:00')])).toEqual(['Windows 1 and 2 overlap on the same day'])
    expect(windowProblems([w(1, '09:00', '13:00'), w(1, '13:00', '14:00'), w(2, '09:00', '13:00')])).toEqual([])
    expect(windowProblems([w(1, '13:00', '09:00')])).toEqual(['Window 1: end must be after start'])
    expect(windowProblems([{ ...w(1, '09:00', '09:10'), slotMinutes: 15 }])).toEqual(['Window 1: must hold at least one slot'])
  })

  it('limits the free-slot range to 31 days inclusive', () => {
    expect(daysInclusive('2026-11-01', '2026-12-01')).toBe(31)
    expect(slotRangeSchema.safeParse({ from: '2026-11-01', to: '2026-12-01' }).success).toBe(true)
    expect(slotRangeSchema.safeParse({ from: '2026-11-01', to: '2026-12-02' }).success).toBe(false)
    expect(slotRangeSchema.safeParse({ from: '2026-11-02', to: '2026-11-01' }).success).toBe(false)
  })

  it('formats UTC instants in the given timezone', () => {
    expect(formatInstant('2026-11-16T03:30:00Z', 'Asia/Kolkata')).toContain('09:00')
    expect(formatInstant('2026-11-16T03:30:00Z', 'UTC')).toContain('03:30')
  })
})

describe('doctor screens', () => {
  it('lists doctors by name and specialty name, never ids', async () => {
    doctorRoutes(DOCTOR_PERMS, { 'GET /api/v1/doctors?page=0&size=20': json(200, { items: [DOCTOR], page: 0, size: 20, totalItems: 1 }) })
    renderApp('/doctors')
    const row = (await screen.findByRole('link', { name: 'Dr. Test Example' })).closest('tr')!
    await waitFor(() => expect(row).toHaveTextContent('General Medicine'))
    expect(row).not.toHaveTextContent('sp-1')
    expect(row).not.toHaveTextContent('d-1')
  })

  it('blocks registration until exactly one primary specialty is marked', async () => {
    const { calls } = doctorRoutes(DOCTOR_PERMS)
    renderApp('/doctors/new')
    await screen.findByRole('option', { name: 'Test Doctor (test.doctor@example.test)' })
    fireEvent.change(screen.getByLabelText('User account'), { target: { value: 'u-9' } })
    fireEvent.change(screen.getByLabelText('Full name'), { target: { value: 'Dr. Test Example' } })
    fireEvent.change(screen.getByLabelText('Registration number'), { target: { value: 'DEMO-0001' } })
    fireEvent.click(screen.getByRole('checkbox', { name: 'General Medicine' }))
    fireEvent.click(screen.getByRole('checkbox', { name: 'Cardiology' }))
    fireEvent.click(screen.getByRole('button', { name: 'Register doctor' }))
    expect(await screen.findByText('Mark exactly one selected specialty as primary')).toBeInTheDocument()
    expect(calls.some((c) => c.key === 'POST /api/v1/doctors')).toBe(false)
  })

  it('shows USER_ALREADY_LINKED from the server', async () => {
    const { calls } = doctorRoutes(DOCTOR_PERMS, {
      'POST /api/v1/doctors': json(409, { code: 'USER_ALREADY_LINKED', detail: 'User already linked' }),
    })
    renderApp('/doctors/new')
    await screen.findByRole('option', { name: 'Test Doctor (test.doctor@example.test)' })
    fireEvent.change(screen.getByLabelText('User account'), { target: { value: 'u-9' } })
    fireEvent.change(screen.getByLabelText('Full name'), { target: { value: 'Dr. Test Example' } })
    fireEvent.change(screen.getByLabelText('Registration number'), { target: { value: 'DEMO-0001' } })
    fireEvent.click(screen.getByRole('checkbox', { name: 'Cardiology' }))
    fireEvent.click(screen.getByRole('radio', { name: 'Primary: Cardiology' }))
    fireEvent.click(screen.getByRole('button', { name: 'Register doctor' }))
    expect(await screen.findByText('This user account is already linked to a doctor or staff record.')).toBeInTheDocument()
    const post = calls.find((c) => c.key === 'POST /api/v1/doctors')!
    expect(bodyOf(post.init)).toMatchObject({ userId: 'u-9', specialtyIds: ['sp-2'], primarySpecialtyId: 'sp-2', defaultSlotMinutes: 15 })
  })

  it('rejects overlapping windows client-side without calling the API', async () => {
    const { calls } = doctorRoutes(DOCTOR_PERMS)
    renderApp('/doctors/d-1')
    fireEvent.click(await screen.findByRole('button', { name: 'Add window' }))
    fireEvent.change(screen.getByLabelText('Start 2'), { target: { value: '12:00' } })
    fireEvent.change(screen.getByLabelText('End 2'), { target: { value: '14:00' } })
    fireEvent.click(screen.getByRole('button', { name: 'Save availability' }))
    expect(await screen.findByText('Windows 1 and 2 overlap on the same day')).toBeInTheDocument()
    expect(calls.some((c) => c.key === 'PUT /api/v1/doctors/d-1/availability')).toBe(false)
  })

  it('shows a server AVAILABILITY_OVERLAP and offers reload on CONCURRENT_MODIFICATION', async () => {
    let attempt = 0
    const { calls } = doctorRoutes(DOCTOR_PERMS, {
      'PUT /api/v1/doctors/d-1/availability': () => {
        attempt += 1
        return attempt === 1
          ? json(422, { code: 'AVAILABILITY_OVERLAP', detail: 'overlap' })
          : json(409, { code: 'CONCURRENT_MODIFICATION', detail: 'stale' })
      },
    })
    renderApp('/doctors/d-1')
    fireEvent.click(await screen.findByRole('button', { name: 'Save availability' }))
    expect(await screen.findByText('Two availability windows overlap on the same day.')).toBeInTheDocument()
    expect(bodyOf(calls.find((c) => c.key === 'PUT /api/v1/doctors/d-1/availability')!.init)).toEqual({ version: 4, windows: AVAILABILITY.windows })
    fireEvent.click(screen.getByRole('button', { name: 'Save availability' }))
    fireEvent.click(await screen.findByRole('button', { name: 'Reload latest version' }))
    await waitFor(() => expect(calls.filter((c) => c.key === 'GET /api/v1/doctors/d-1/availability')).toHaveLength(2))
  })

  it('cancels leave with its version', async () => {
    const { calls } = doctorRoutes(DOCTOR_PERMS, {
      'POST /api/v1/doctors/d-1/availability-exceptions/l-1/cancel': json(200, { ...LEAVE, status: 'CANCELLED', version: 1 }),
    })
    renderApp('/doctors/d-1')
    fireEvent.click(await screen.findByRole('button', { name: 'Cancel leave on 2026-11-20' }))
    await waitFor(() => expect(calls.some((c) => c.key === 'POST /api/v1/doctors/d-1/availability-exceptions/l-1/cancel')).toBe(true))
    const cancel = calls.find((c) => c.key === 'POST /api/v1/doctors/d-1/availability-exceptions/l-1/cancel')!
    expect(bodyOf(cancel.init)).toEqual({ version: 0 })
    await waitFor(() => expect(calls.filter((c) => c.key === 'GET /api/v1/doctors/d-1/availability-exceptions')).toHaveLength(2))
  })

  it('blocks a free-slot range over 31 days and renders slots in the organization timezone', async () => {
    const { calls } = doctorRoutes(DOCTOR_PERMS, {
      'GET /api/v1/doctors/d-1/slots?from=2026-11-16&to=2026-11-16': json(200, {
        practitionerId: 'd-1', timezone: 'Asia/Kolkata', from: '2026-11-16', to: '2026-11-16',
        slots: [{ startAt: '2026-11-16T03:30:00Z', endAt: '2026-11-16T03:45:00Z' }],
      }),
    })
    renderApp('/doctors/d-1')
    fireEvent.change(await screen.findByLabelText('From date'), { target: { value: '2026-11-01' } })
    fireEvent.change(screen.getByLabelText('To date'), { target: { value: '2026-12-15' } })
    fireEvent.click(screen.getByRole('button', { name: 'Show free slots' }))
    expect(await screen.findByText('The range can be at most 31 days')).toBeInTheDocument()
    expect(calls.some((c) => c.key.includes('/slots'))).toBe(false)
    fireEvent.change(screen.getByLabelText('From date'), { target: { value: '2026-11-16' } })
    fireEvent.change(screen.getByLabelText('To date'), { target: { value: '2026-11-16' } })
    fireEvent.click(screen.getByRole('button', { name: 'Show free slots' }))
    const list = await screen.findByRole('list', { name: 'Free slots' })
    expect(within(list).getByText(/09:00.*09:15/)).toBeInTheDocument()
    expect(list).not.toHaveTextContent('03:30')
  })

  it('hides editing controls without update/schedule permissions and the slot preview without appointment:read', async () => {
    doctorRoutes(['practitioner:read', 'catalog:read'])
    renderApp('/doctors/d-1')
    expect(await screen.findByRole('heading', { name: 'Weekly availability' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Save profile' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Save availability' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /Cancel leave/ })).not.toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: 'Free slots preview' })).not.toBeInTheDocument()
  })

  it('forbids the doctor screens without practitioner:read', async () => {
    mockApi({ 'GET /api/v1/auth/me': json(200, session(['patient:search'])) })
    renderApp('/doctors')
    expect(await screen.findByText(/permission/i)).toBeInTheDocument()
    expect(screen.queryByRole('link', { name: 'Doctors' })).not.toBeInTheDocument()
  })
})

describe('staff screens', () => {
  function staffRoutes(permissions: string[], extra: Routes = {}) {
    return mockApi({
      'GET /api/v1/auth/me': json(200, session(permissions)),
      ...catalogRoutes,
      'GET /api/v1/staff/s-1': json(200, STAFF),
      ...extra,
    })
  }

  it('edits a staff record without any role controls, linking to the user screen instead', async () => {
    const { calls } = staffRoutes(STAFF_PERMS, { 'PATCH /api/v1/staff/s-1': json(200, { ...STAFF, departmentId: null, version: 3 }) })
    renderApp('/staff/s-1')
    expect(await screen.findByRole('heading', { name: 'Test Nurse' })).toBeInTheDocument()
    expect(screen.queryByText(/^role/i)).not.toBeInTheDocument()
    expect(screen.queryByLabelText(/role/i)).not.toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Open linked user account' })).toHaveAttribute('href', '/users/u-9')
    await screen.findByRole('option', { name: 'Outpatients' })
    fireEvent.change(screen.getByLabelText('Department'), { target: { value: '' } })
    fireEvent.click(screen.getByRole('button', { name: 'Save changes' }))
    await screen.findByText('Changes saved.')
    expect(bodyOf(calls.find((c) => c.key === 'PATCH /api/v1/staff/s-1')!.init)).toEqual({ version: 2, clearDepartment: true })
  })

  it('shows USER_ALREADY_LINKED when registering staff', async () => {
    const { calls } = staffRoutes(STAFF_PERMS, { 'POST /api/v1/staff': json(409, { code: 'USER_ALREADY_LINKED', detail: 'linked' }) })
    renderApp('/staff/new')
    await screen.findByRole('option', { name: 'Test Doctor (test.doctor@example.test)' })
    expect(screen.queryByLabelText(/role/i)).not.toBeInTheDocument()
    fireEvent.change(screen.getByLabelText('User account'), { target: { value: 'u-9' } })
    fireEvent.change(screen.getByLabelText('Full name'), { target: { value: 'Test Nurse' } })
    fireEvent.change(screen.getByLabelText('Staff type'), { target: { value: 'NURSE' } })
    fireEvent.click(screen.getByRole('button', { name: 'Register staff' }))
    expect(await screen.findByText('This user account is already linked to a doctor or staff record.')).toBeInTheDocument()
    expect(bodyOf(calls.find((c) => c.key === 'POST /api/v1/staff')!.init)).toEqual({ userId: 'u-9', fullName: 'Test Nurse', staffType: 'NURSE' })
  })

  it('shows staff read-only without staff:update and no register link without staff:create', async () => {
    staffRoutes(['staff:read'], { 'GET /api/v1/staff?page=0&size=20': json(200, { items: [STAFF], page: 0, size: 20, totalItems: 1 }) })
    renderApp('/staff')
    expect(await screen.findByRole('link', { name: 'Test Nurse' })).toBeInTheDocument()
    expect(screen.queryByRole('link', { name: 'Register staff' })).not.toBeInTheDocument()
  })

  it('forbids the staff screens without staff:read', async () => {
    mockApi({ 'GET /api/v1/auth/me': json(200, session(['practitioner:read'])) })
    renderApp('/staff/new')
    expect(await screen.findByText(/permission/i)).toBeInTheDocument()
  })
})
