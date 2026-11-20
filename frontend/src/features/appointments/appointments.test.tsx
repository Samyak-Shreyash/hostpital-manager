import { act, fireEvent, screen, waitFor, within } from '@testing-library/react'
import type { Appointment, Queue } from '../../api/appointments'
import { json, mockApi, renderApp, session } from '../../test/helpers'

// Synthetic data only.
const DOCTOR = {
  id: 'd-1', userId: 'u-9', fullName: 'Dr. Test Example', registrationNo: 'DEMO-0001', registrationCouncil: 'DEMO', qualifications: [],
  defaultSlotMinutes: 15, status: 'ACTIVE', primarySpecialtyId: 'sp-1', specialtyIds: ['sp-1'], departmentIds: [], version: 1,
  createdAt: '2026-01-01T00:00:00Z', updatedAt: '2026-01-01T00:00:00Z',
}
const DOCTORS = { items: [DOCTOR], page: 0, size: 100, totalItems: 1 }
const PATIENT = {
  id: 'p-1', uhid: 'DEMO-000001', fullName: 'Asha Testpatient', sex: 'F', dateOfBirth: '1990-05-10', dobEstimated: false,
  phone: '+919800000001', abhaLast4: null, address: null, emergencyContact: null, status: 'ACTIVE', version: 0,
  createdAt: '2026-01-01T00:00:00Z', updatedAt: '2026-01-01T00:00:00Z',
}
const PATIENT_HIT = { id: 'p-1', uhid: 'DEMO-000001', fullName: 'Asha Testpatient', sex: 'F', dateOfBirth: '1990-05-10', phoneMasked: '+91******0001', abhaLast4: null, status: 'ACTIVE' }
const DATE = '2026-11-20'
// 04:00Z = 09:30 and 04:15Z = 09:45 in Asia/Kolkata.
const SLOTS = {
  practitionerId: 'd-1', timezone: 'Asia/Kolkata', from: DATE, to: DATE,
  slots: [
    { startAt: '2026-11-20T04:00:00Z', endAt: '2026-11-20T04:15:00Z' },
    { startAt: '2026-11-20T04:30:00Z', endAt: '2026-11-20T04:45:00Z' },
  ],
}
const APPT: Appointment = {
  id: 'a-1', patientId: 'p-1', doctorId: 'd-1', startAt: '2026-11-20T04:15:00Z', endAt: '2026-11-20T04:30:00Z', durationMinutes: 15,
  type: 'SCHEDULED', status: 'SCHEDULED', reason: 'Fever', outsideAvailability: false, version: 2, createdAt: '', updatedAt: '',
  rescheduledFromId: null, cancelReason: null, cancelledAt: null, tokenNo: null, tokenDate: null,
}
const LIST = (items: Appointment[]) => json(200, { items, page: 0, size: 100, totalItems: items.length })
const FRONT_DESK = ['appointment:read', 'appointment:create', 'appointment:update', 'appointment:cancel', 'appointment:check-in', 'practitioner:read', 'patient:search', 'patient:read']

type Routes = Parameters<typeof mockApi>[0]
function dayRoutes(permissions: string[], extra: Routes = {}) {
  return mockApi({
    'GET /api/v1/auth/me': json(200, session(permissions)),
    'GET /api/v1/doctors?status=ACTIVE&size=100': json(200, DOCTORS),
    [`GET /api/v1/doctors/d-1/slots?from=${DATE}&to=${DATE}`]: json(200, SLOTS),
    [`GET /api/v1/appointments?doctorId=d-1&date=${DATE}&size=100`]: LIST([APPT]),
    'GET /api/v1/patients/p-1': json(200, PATIENT),
    'GET /api/v1/patients?q=Asha&size=10': json(200, { items: [PATIENT_HIT], page: 0, size: 10, totalItems: 1 }),
    ...extra,
  })
}
const bodyOf = (init?: RequestInit) => JSON.parse(String(init?.body))

async function openDay() {
  renderApp('/appointments')
  fireEvent.change(await screen.findByLabelText('Date'), { target: { value: DATE } })
  await screen.findByRole('option', { name: 'Dr. Test Example' })
  fireEvent.change(screen.getByLabelText('Doctor'), { target: { value: 'd-1' } })
  await screen.findByRole('button', { name: 'Book 09:30' })
}

async function pickPatient() {
  const dialog = screen.getByRole('dialog')
  fireEvent.change(within(dialog).getByLabelText('Find patient'), { target: { value: 'Asha' } })
  fireEvent.click(within(dialog).getByRole('button', { name: 'Search' }))
  fireEvent.click(await within(dialog).findByRole('button', { name: 'Asha Testpatient (DEMO-000001)' }))
  return dialog
}

describe('day calendar', () => {
  it('overlays appointments on the free slots in the facility timezone, never showing ids', async () => {
    dayRoutes(FRONT_DESK)
    await openDay()
    expect(screen.getByRole('button', { name: 'Book 10:00' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Book 09:45' })).not.toBeInTheDocument()
    const row = (await screen.findByText('Asha Testpatient')).closest('li')!
    expect(row).toHaveTextContent('09:45')
    expect(row).toHaveTextContent('Scheduled')
    expect(document.body).not.toHaveTextContent('a-1')
    expect(document.body).not.toHaveTextContent('d-1')
  })

  it('books a free slot for a searched patient', async () => {
    const { calls } = dayRoutes(FRONT_DESK, { 'POST /api/v1/appointments': json(201, { ...APPT, id: 'a-2', startAt: '2026-11-20T04:00:00Z' }) })
    await openDay()
    fireEvent.click(screen.getByRole('button', { name: 'Book 09:30' }))
    const dialog = await pickPatient()
    fireEvent.change(within(dialog).getByLabelText('Reason'), { target: { value: 'Fever for 2 days' } })
    fireEvent.click(within(dialog).getByRole('button', { name: 'Confirm booking' }))
    await waitFor(() => expect(calls.some((c) => c.key === 'POST /api/v1/appointments')).toBe(true))
    expect(bodyOf(calls.find((c) => c.key === 'POST /api/v1/appointments')!.init)).toEqual({
      patientId: 'p-1', doctorId: 'd-1', startAt: '2026-11-20T04:00:00Z', durationMinutes: 15, reason: 'Fever for 2 days',
    })
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
    await waitFor(() => expect(calls.filter((c) => c.key.startsWith('GET /api/v1/appointments?')).length).toBeGreaterThan(1))
  })

  it('requires a patient before booking', async () => {
    const { calls } = dayRoutes(FRONT_DESK)
    await openDay()
    fireEvent.click(screen.getByRole('button', { name: 'Book 09:30' }))
    fireEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Confirm booking' }))
    expect(await screen.findByText('Select a patient')).toBeInTheDocument()
    expect(calls.some((c) => c.key === 'POST /api/v1/appointments')).toBe(false)
  })

  it('shows SLOT_CONFLICT inline and keeps the dialog open', async () => {
    dayRoutes(FRONT_DESK, { 'POST /api/v1/appointments': json(409, { code: 'SLOT_CONFLICT', detail: 'overlaps' }) })
    await openDay()
    fireEvent.click(screen.getByRole('button', { name: 'Book 09:30' }))
    const dialog = await pickPatient()
    fireEvent.click(within(dialog).getByRole('button', { name: 'Confirm booking' }))
    expect(await within(dialog).findByText('That time was just taken by another booking. Close this and pick another slot.')).toBeInTheDocument()
    expect(screen.getByRole('dialog')).toBeInTheDocument()
  })

  it('cancels with a required reason', async () => {
    const { calls } = dayRoutes(FRONT_DESK, { 'POST /api/v1/appointments/a-1/cancel': json(200, { ...APPT, status: 'CANCELLED', version: 3 }) })
    await openDay()
    fireEvent.click(await screen.findByRole('button', { name: 'Cancel appointment' }))
    const dialog = screen.getByRole('dialog')
    fireEvent.click(within(dialog).getByRole('button', { name: 'Confirm cancellation' }))
    expect(await within(dialog).findByText('Enter a reason for cancelling')).toBeInTheDocument()
    expect(calls.some((c) => c.key.endsWith('/cancel'))).toBe(false)
    fireEvent.change(within(dialog).getByLabelText('Reason'), { target: { value: 'Patient called to cancel' } })
    fireEvent.click(within(dialog).getByRole('button', { name: 'Confirm cancellation' }))
    await waitFor(() => expect(calls.some((c) => c.key === 'POST /api/v1/appointments/a-1/cancel')).toBe(true))
    expect(bodyOf(calls.find((c) => c.key === 'POST /api/v1/appointments/a-1/cancel')!.init)).toEqual({ version: 2, reason: 'Patient called to cancel' })
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
  })

  it('offers a reload when the appointment changed under the user (CONCURRENT_MODIFICATION)', async () => {
    const { calls } = dayRoutes(FRONT_DESK, { 'POST /api/v1/appointments/a-1/cancel': json(409, { code: 'CONCURRENT_MODIFICATION', detail: 'stale' }) })
    await openDay()
    fireEvent.click(await screen.findByRole('button', { name: 'Cancel appointment' }))
    const dialog = screen.getByRole('dialog')
    fireEvent.change(within(dialog).getByLabelText('Reason'), { target: { value: 'Duplicate booking' } })
    fireEvent.click(within(dialog).getByRole('button', { name: 'Confirm cancellation' }))
    const before = calls.filter((c) => c.key.startsWith('GET /api/v1/appointments?')).length
    fireEvent.click(await within(dialog).findByRole('button', { name: 'Reload latest version' }))
    await waitFor(() => expect(calls.filter((c) => c.key.startsWith('GET /api/v1/appointments?')).length).toBeGreaterThan(before))
  })

  it('reschedules to another free slot with the appointment version', async () => {
    const { calls } = dayRoutes(FRONT_DESK, { 'POST /api/v1/appointments/a-1/reschedule': json(201, { ...APPT, id: 'a-3', startAt: '2026-11-20T04:30:00Z', rescheduledFromId: 'a-1' }) })
    await openDay()
    fireEvent.click(await screen.findByRole('button', { name: 'Reschedule' }))
    const dialog = screen.getByRole('dialog')
    fireEvent.change(within(dialog).getByLabelText('New time'), { target: { value: '2026-11-20T04:30:00Z' } })
    fireEvent.click(within(dialog).getByRole('button', { name: 'Confirm reschedule' }))
    await waitFor(() => expect(calls.some((c) => c.key === 'POST /api/v1/appointments/a-1/reschedule')).toBe(true))
    expect(bodyOf(calls.find((c) => c.key.endsWith('/reschedule'))!.init)).toEqual({ version: 2, startAt: '2026-11-20T04:30:00Z', durationMinutes: 15 })
  })

  it('checks a patient in and shows the token', async () => {
    const { calls } = dayRoutes(FRONT_DESK, { 'POST /api/v1/appointments/a-1/check-in': json(200, { ...APPT, status: 'CHECKED_IN', tokenNo: 7, tokenDate: DATE, version: 3 }) })
    await openDay()
    fireEvent.click(await screen.findByRole('button', { name: 'Check in' }))
    expect(await screen.findByRole('status', { name: 'Check-in result' })).toHaveTextContent('Token 7')
    expect(bodyOf(calls.find((c) => c.key.endsWith('/check-in'))!.init)).toEqual({ version: 2 })
  })

  it('registers a walk-in and shows the token', async () => {
    const { calls } = dayRoutes(FRONT_DESK, {
      'POST /api/v1/appointments/walk-in': json(201, { ...APPT, id: 'a-4', type: 'WALK_IN', status: 'CHECKED_IN', tokenNo: 3, tokenDate: DATE }),
    })
    await openDay()
    fireEvent.click(screen.getByRole('button', { name: 'Walk-in' }))
    const dialog = await pickPatient()
    fireEvent.change(within(dialog).getByLabelText('Reason'), { target: { value: 'Cough' } })
    fireEvent.click(within(dialog).getByRole('button', { name: 'Register walk-in' }))
    expect(await screen.findByRole('status', { name: 'Check-in result' })).toHaveTextContent('Token 3')
    expect(bodyOf(calls.find((c) => c.key === 'POST /api/v1/appointments/walk-in')!.init)).toEqual({ patientId: 'p-1', doctorId: 'd-1', reason: 'Cough' })
  })

  it('hides action buttons the user lacks permission for', async () => {
    dayRoutes(['appointment:read', 'practitioner:read'])
    renderApp('/appointments')
    fireEvent.change(await screen.findByLabelText('Date'), { target: { value: DATE } })
    await screen.findByRole('option', { name: 'Dr. Test Example' })
    fireEvent.change(screen.getByLabelText('Doctor'), { target: { value: 'd-1' } })
    await screen.findByText('Scheduled')
    expect(screen.queryByRole('button', { name: /^Book/ })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Walk-in' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Check in' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Cancel appointment' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Reschedule' })).not.toBeInTheDocument()
  })

  it('forbids the screens without appointment:read and hides their nav links', async () => {
    mockApi({ 'GET /api/v1/auth/me': json(200, session(['patient:search'])) })
    renderApp('/queue')
    expect(await screen.findByRole('heading', { name: /is not available to you/ })).toBeInTheDocument()
    expect(screen.queryByRole('link', { name: 'Queue' })).not.toBeInTheDocument()
    expect(screen.queryByRole('link', { name: 'Appointments' })).not.toBeInTheDocument()
  })
})

describe('queue board', () => {
  afterEach(() => vi.useRealTimers())

  it('polls the queue every 15 seconds', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    const queue = (items: Queue['items']): Queue => ({ doctorId: 'd-1', date: DATE, items })
    let n = 0
    const { calls } = mockApi({
      'GET /api/v1/auth/me': json(200, session(['appointment:read', 'practitioner:read'])),
      'GET /api/v1/doctors?status=ACTIVE&size=100': json(200, DOCTORS),
      'GET /api/v1/queue?doctorId=d-1': () => {
        n += 1
        return json(200, queue(n === 1 ? [{ appointmentId: 'a-1', patient: { id: 'p-1', uhid: 'DEMO-000001', name: 'Asha Testpatient' }, tokenNo: 1, status: 'CHECKED_IN' }]
          : [
            { appointmentId: 'a-1', patient: { id: 'p-1', uhid: 'DEMO-000001', name: 'Asha Testpatient' }, tokenNo: 1, status: 'IN_CONSULTATION' },
            { appointmentId: 'a-5', patient: { id: 'p-2', uhid: 'DEMO-000002', name: 'Ravi Testpatient' }, tokenNo: 2, status: 'CHECKED_IN' },
          ]))
      },
    })
    renderApp('/queue')
    await screen.findByRole('option', { name: 'Dr. Test Example' })
    fireEvent.change(screen.getByLabelText('Doctor'), { target: { value: 'd-1' } })
    const board = await screen.findByRole('list', { name: 'Queue' })
    expect(await within(board).findByText('Asha Testpatient')).toBeInTheDocument()
    expect(board).toHaveTextContent('1')
    expect(board).toHaveTextContent('DEMO-000001')
    const queueCalls = () => calls.filter((c) => c.key.startsWith('GET /api/v1/queue')).length
    expect(queueCalls()).toBe(1)
    await act(async () => { await vi.advanceTimersByTimeAsync(14_000) })
    expect(queueCalls()).toBe(1)
    await act(async () => { await vi.advanceTimersByTimeAsync(1_500) })
    await waitFor(() => expect(queueCalls()).toBe(2))
    expect(await within(board).findByText('Ravi Testpatient')).toBeInTheDocument()
    expect(board).toHaveTextContent('In consultation')
  })

  it('shows an empty message when nobody is waiting and passes a chosen date', async () => {
    const { calls } = mockApi({
      'GET /api/v1/auth/me': json(200, session(['appointment:read', 'practitioner:read'])),
      'GET /api/v1/doctors?status=ACTIVE&size=100': json(200, DOCTORS),
      'GET /api/v1/queue?doctorId=d-1': json(200, { doctorId: 'd-1', date: DATE, items: [] }),
      [`GET /api/v1/queue?doctorId=d-1&date=${DATE}`]: json(200, { doctorId: 'd-1', date: DATE, items: [] }),
    })
    renderApp('/queue')
    await screen.findByRole('option', { name: 'Dr. Test Example' })
    fireEvent.change(screen.getByLabelText('Doctor'), { target: { value: 'd-1' } })
    expect(await screen.findByText('Nobody is waiting.')).toBeInTheDocument()
    fireEvent.change(screen.getByLabelText('Date'), { target: { value: DATE } })
    await waitFor(() => expect(calls.some((c) => c.key.endsWith(`date=${DATE}`))).toBe(true))
  })
})
