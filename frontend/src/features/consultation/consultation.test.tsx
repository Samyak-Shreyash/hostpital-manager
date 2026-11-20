import { fireEvent, screen, waitFor, within } from '@testing-library/react'
import type { Encounter, Prescription } from '../../api/clinical'
import { json, mockApi, renderApp, session } from '../../test/helpers'

// Synthetic data only.
const DOCTOR_PERMS = [
  'appointment:read', 'practitioner:read', 'encounter:read', 'encounter:create', 'encounter:update', 'encounter:finalize',
  'encounter:addendum', 'observation:write', 'prescription:create', 'prescription:issue', 'prescription:read',
]
const DOCTORS = { items: [{ id: 'd-1', userId: 'u-9', fullName: 'Dr. Test Example', registrationNo: 'DEMO-0001', status: 'ACTIVE', version: 1 }], page: 0, size: 100, totalItems: 1 }
const ENC: Encounter = {
  id: 'e-1', patient: { id: 'p-1', uhid: 'DEMO-000001' }, practitionerId: 'd-1', appointmentId: 'a-1', status: 'OPEN',
  chiefComplaint: 'Fever for two days', version: 3, startedAt: '2026-11-20T04:00:00Z', finalizedAt: null,
  observations: [], warnings: [], notes: [], diagnoses: [], treatmentPlan: null, followUp: null, prescriptions: [],
}
const RX: Prescription = {
  id: 'rx-1', encounterId: 'e-1', patientId: 'p-1', status: 'DRAFT', versionNo: 1, supersedesId: null, issuedAt: null,
  issuedBy: null, version: 0, items: [], allergyWarnings: [],
}
const HISTORY = {
  items: [
    { id: 'e-1', practitionerId: 'd-1', appointmentId: 'a-1', status: 'OPEN', chiefComplaint: 'Fever for two days', startedAt: '2026-11-20T04:00:00Z', finalizedAt: null },
    { id: 'e-0', practitionerId: 'd-1', appointmentId: null, status: 'FINALIZED', chiefComplaint: 'Old sprain', startedAt: '2026-10-01T04:00:00Z', finalizedAt: '2026-10-01T04:20:00Z' },
  ],
  page: 0, size: 20, totalItems: 2,
}
// What the server lists as the encounter's prescriptions; the create/revise handlers below move it like the real one.
let refs: Encounter['prescriptions'] = []
const ref = (rx: Prescription) => ({ id: rx.id, status: rx.status, versionNo: rx.versionNo })
const created = (rx: Prescription) => () => { refs = [ref(rx)]; return json(201, rx) }
const bodyOf = (init?: RequestInit) => JSON.parse(String(init?.body))

function consultRoutes(encounter: Encounter, extra: Parameters<typeof mockApi>[0] = {}, permissions = DOCTOR_PERMS) {
  return mockApi({
    'GET /api/v1/auth/me': json(200, session(permissions)),
    [`GET /api/v1/encounters/${encounter.id}`]: () => json(200, { ...encounter, prescriptions: refs }),
    'GET /api/v1/patients/p-1/encounters?page=0&size=20': json(200, HISTORY),
    ...extra,
  })
}

async function openConsult(encounter: Encounter = ENC) {
  renderApp(`/consult/${encounter.id}`)
  await screen.findByRole('region', { name: 'Encounter summary' })
}

beforeEach(() => { refs = [] })

describe('start consult from the queue', () => {
  const ENTRY = { appointmentId: 'a-1', patient: { id: 'p-1', uhid: 'DEMO-000001', name: 'Asha Testpatient' }, tokenNo: 4, status: 'CHECKED_IN' }
  async function openQueue() {
    renderApp('/queue')
    await screen.findByRole('option', { name: 'Dr. Test Example' })
    fireEvent.change(screen.getByLabelText('Doctor'), { target: { value: 'd-1' } })
    await screen.findByText('Asha Testpatient')
  }

  it('starts the encounter from the appointment and opens the consult screen', async () => {
    const { calls } = mockApi({
      'GET /api/v1/auth/me': json(200, session(DOCTOR_PERMS)),
      'GET /api/v1/doctors?status=ACTIVE&size=100': json(200, DOCTORS),
      'GET /api/v1/queue?doctorId=d-1': json(200, { doctorId: 'd-1', date: '2026-11-20', items: [ENTRY] }),
      'POST /api/v1/encounters': json(201, ENC),
      'GET /api/v1/encounters/e-1': json(200, ENC),
      'GET /api/v1/patients/p-1/encounters?page=0&size=20': json(200, HISTORY),
    })
    await openQueue()
    fireEvent.click(screen.getByRole('button', { name: 'Start consult' }))
    expect(await screen.findByRole('heading', { name: 'Consultation' })).toBeInTheDocument()
    expect(bodyOf(calls.find((c) => c.key === 'POST /api/v1/encounters')?.init)).toEqual({ appointmentId: 'a-1' })
    expect(await screen.findByText('DEMO-000001')).toBeInTheDocument()
  })

  it('hides Start consult without encounter:create', async () => {
    mockApi({
      'GET /api/v1/auth/me': json(200, session(['appointment:read', 'practitioner:read'])),
      'GET /api/v1/doctors?status=ACTIVE&size=100': json(200, DOCTORS),
      'GET /api/v1/queue?doctorId=d-1': json(200, { doctorId: 'd-1', date: '2026-11-20', items: [ENTRY] }),
    })
    await openQueue()
    expect(screen.queryByRole('button', { name: 'Start consult' })).not.toBeInTheDocument()
  })

  it('explains a 403 for a doctor who is not the treating one', async () => {
    mockApi({
      'GET /api/v1/auth/me': json(200, session(DOCTOR_PERMS)),
      'GET /api/v1/doctors?status=ACTIVE&size=100': json(200, DOCTORS),
      'GET /api/v1/queue?doctorId=d-1': json(200, { doctorId: 'd-1', date: '2026-11-20', items: [ENTRY] }),
      'POST /api/v1/encounters': json(403, { code: 'FORBIDDEN', detail: 'no' }),
    })
    await openQueue()
    fireEvent.click(screen.getByRole('button', { name: 'Start consult' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('Only the treating doctor')
  })

  it('resumes an in-consultation visit through the patient history', async () => {
    mockApi({
      'GET /api/v1/auth/me': json(200, session(DOCTOR_PERMS)),
      'GET /api/v1/doctors?status=ACTIVE&size=100': json(200, DOCTORS),
      'GET /api/v1/queue?doctorId=d-1': json(200, { doctorId: 'd-1', date: '2026-11-20', items: [{ ...ENTRY, status: 'IN_CONSULTATION' }] }),
      'GET /api/v1/patients/p-1/encounters?page=0&size=20': json(200, HISTORY),
      'GET /api/v1/encounters/e-1': json(200, ENC),
    })
    await openQueue()
    fireEvent.click(screen.getByRole('button', { name: 'Resume consult' }))
    expect(await screen.findByRole('heading', { name: 'Consultation' })).toBeInTheDocument()
  })
})

describe('vitals', () => {
  it('saves vitals and shows plausibility warnings without blocking', async () => {
    const saved: Encounter = {
      ...ENC, version: 4,
      observations: [{ kind: 'PULSE', value: 190, unit: 'bpm' }],
      warnings: [{ kind: 'PULSE', message: 'PULSE 190 is outside the usual range 40 to 180.' }],
    }
    const { calls } = consultRoutes(ENC, { 'PUT /api/v1/encounters/e-1/observations': json(200, saved) })
    await openConsult()
    fireEvent.change(screen.getByLabelText('Pulse (bpm)'), { target: { value: '190' } })
    fireEvent.click(screen.getByRole('button', { name: 'Save vitals' }))
    const warning = await screen.findByRole('status', { name: 'Vitals warnings' })
    expect(warning).toHaveTextContent('outside the usual range')
    expect(bodyOf(calls.find((c) => c.key.startsWith('PUT'))?.init)).toEqual({ version: 3, observations: [{ kind: 'PULSE', value: 190, unit: 'bpm' }] })
    expect(screen.getByLabelText('Pulse (bpm)')).toHaveValue('190')
  })

  it('shows the message of a hard-limit 422', async () => {
    consultRoutes(ENC, { 'PUT /api/v1/encounters/e-1/observations': json(422, { code: 'VALIDATION_ERROR', detail: 'PULSE must be 0 to 300.' }) })
    await openConsult()
    fireEvent.change(screen.getByLabelText('Pulse (bpm)'), { target: { value: '400' } })
    fireEvent.click(screen.getByRole('button', { name: 'Save vitals' }))
    expect(await screen.findByText('PULSE must be 0 to 300.')).toBeInTheDocument()
  })

  it('offers a reload on a version conflict', async () => {
    consultRoutes(ENC, { 'PUT /api/v1/encounters/e-1/observations': json(409, { code: 'CONCURRENT_MODIFICATION' }) })
    await openConsult()
    fireEvent.change(screen.getByLabelText('Pulse (bpm)'), { target: { value: '80' } })
    fireEvent.click(screen.getByRole('button', { name: 'Save vitals' }))
    expect(await screen.findByRole('button', { name: 'Reload latest version' })).toBeInTheDocument()
  })
})

describe('notes, diagnoses, plan and follow-up', () => {
  it('saves changed notes with the current version and chains the new version', async () => {
    const { calls } = consultRoutes(ENC, {
      'PUT /api/v1/encounters/e-1/notes/SYMPTOMS': json(200, { ...ENC, version: 4, notes: [{ kind: 'SYMPTOMS', text: 'Cough', authorId: 'u-9', createdAt: '' }] }),
      'PUT /api/v1/encounters/e-1/notes/ASSESSMENT': json(200, { ...ENC, version: 5 }),
    })
    await openConsult()
    fireEvent.change(screen.getByLabelText('Symptoms'), { target: { value: 'Cough' } })
    fireEvent.change(screen.getByLabelText('Assessment'), { target: { value: 'Viral' } })
    fireEvent.click(screen.getByRole('button', { name: 'Save notes' }))
    await waitFor(() => expect(calls.filter((c) => c.key.startsWith('PUT')).length).toBe(2))
    const puts = calls.filter((c) => c.key.startsWith('PUT'))
    expect(bodyOf(puts[0].init)).toEqual({ version: 3, text: 'Cough' })
    expect(bodyOf(puts[1].init)).toEqual({ version: 4, text: 'Viral' })
  })

  it('saves diagnoses and the treatment plan', async () => {
    const { calls } = consultRoutes(ENC, {
      'PUT /api/v1/encounters/e-1/diagnoses': json(200, { ...ENC, version: 4 }),
      'PUT /api/v1/encounters/e-1/treatment-plan': json(200, { ...ENC, version: 5 }),
    })
    await openConsult()
    fireEvent.click(screen.getByRole('button', { name: 'Add diagnosis' }))
    fireEvent.change(screen.getByLabelText('Diagnosis description 1'), { target: { value: 'Viral fever' } })
    fireEvent.change(screen.getByLabelText('Diagnosis code 1'), { target: { value: 'B34.9' } })
    fireEvent.click(screen.getByLabelText('Primary diagnosis 1'))
    fireEvent.click(screen.getByRole('button', { name: 'Save diagnoses' }))
    await waitFor(() => expect(calls.some((c) => c.key.endsWith('/diagnoses'))).toBe(true))
    expect(bodyOf(calls.find((c) => c.key.endsWith('/diagnoses'))?.init)).toEqual({ version: 3, diagnoses: [{ code: 'B34.9', description: 'Viral fever', primary: true }] })

    fireEvent.change(screen.getByLabelText('Treatment plan'), { target: { value: 'Rest and fluids' } })
    fireEvent.click(screen.getByRole('button', { name: 'Save plan' }))
    await waitFor(() => expect(calls.some((c) => c.key.endsWith('/treatment-plan'))).toBe(true))
    expect(bodyOf(calls.find((c) => c.key.endsWith('/treatment-plan'))?.init)).toEqual({ version: 4, text: 'Rest and fluids' })
  })

  it('requires a follow-up date and saves it', async () => {
    const { calls } = consultRoutes(ENC, { 'PUT /api/v1/encounters/e-1/follow-up': json(200, { ...ENC, version: 4 }) })
    await openConsult()
    fireEvent.click(screen.getByRole('button', { name: 'Save follow-up' }))
    expect(await screen.findByText('Choose a follow-up date')).toBeInTheDocument()
    fireEvent.change(screen.getByLabelText('Follow-up date'), { target: { value: '2026-11-27' } })
    fireEvent.change(screen.getByLabelText('Follow-up instructions'), { target: { value: 'Review reports' } })
    fireEvent.click(screen.getByRole('button', { name: 'Save follow-up' }))
    await waitFor(() => expect(calls.some((c) => c.key.endsWith('/follow-up'))).toBe(true))
    expect(bodyOf(calls.find((c) => c.key.endsWith('/follow-up'))?.init)).toEqual({ version: 3, date: '2026-11-27', instructions: 'Review reports' })
  })
})

describe('prescription', () => {
  const ITEM = { drugName: 'Paracetamol', strength: null, form: null, route: null, dose: '500 mg', frequency: 'Twice daily', durationDays: 3, instructions: null }
  const fill = () => {
    fireEvent.change(screen.getByLabelText('Drug name 1'), { target: { value: 'Paracetamol' } })
    fireEvent.change(screen.getByLabelText('Dose 1'), { target: { value: '500 mg' } })
    fireEvent.change(screen.getByLabelText('Frequency 1'), { target: { value: 'Twice daily' } })
    fireEvent.change(screen.getByLabelText('Duration (days) 1'), { target: { value: '3' } })
  }

  it('requires acknowledging allergy warnings before issuing, then offers the PDF', async () => {
    const warned: Prescription = { ...RX, version: 1, items: [ITEM], allergyWarnings: [{ substance: 'Paracetamol', severity: 'SEVERE' }] }
    const { calls } = consultRoutes(ENC, {
      'POST /api/v1/encounters/e-1/prescriptions': created(RX),
      'PUT /api/v1/prescriptions/rx-1': json(200, warned),
      'POST /api/v1/prescriptions/rx-1/issue': json(200, { ...warned, status: 'ISSUED', version: 2, issuedAt: '2026-11-20T04:30:00Z' }),
    })
    await openConsult()
    fireEvent.click(screen.getByRole('button', { name: 'Start prescription' }))
    fireEvent.click(await screen.findByRole('button', { name: 'Add medicine' }))
    fill()
    fireEvent.click(screen.getByRole('button', { name: 'Save medicines' }))
    const warning = await screen.findByRole('alert', { name: 'Allergy warnings' })
    expect(warning).toHaveTextContent('Paracetamol')
    expect(warning).toHaveTextContent('SEVERE')
    expect(bodyOf(calls.find((c) => c.key === 'PUT /api/v1/prescriptions/rx-1')?.init)).toEqual({ version: 0, items: [ITEM] })

    const issue = screen.getByRole('button', { name: 'Issue prescription' })
    expect(issue).toBeDisabled()
    fireEvent.click(screen.getByLabelText('I have reviewed these allergy warnings'))
    expect(issue).toBeEnabled()
    fireEvent.click(issue)
    await screen.findByText('Issued')
    expect(bodyOf(calls.find((c) => c.key.endsWith('/issue'))?.init)).toEqual({ version: 1, acknowledgeAllergyWarnings: true })
    expect(screen.getByRole('link', { name: 'Open prescription PDF' })).toHaveAttribute('href', '/api/v1/prescriptions/rx-1/pdf')
    expect(screen.queryByRole('button', { name: 'Save medicines' })).not.toBeInTheDocument()
  })

  it('shows 422 item errors beside the field', async () => {
    consultRoutes(ENC, {
      'POST /api/v1/encounters/e-1/prescriptions': created(RX),
      'PUT /api/v1/prescriptions/rx-1': json(422, { code: 'VALIDATION_ERROR', errors: [{ field: 'items[0].dose', code: 'REQUIRED', message: 'dose is required.' }] }),
    })
    await openConsult()
    fireEvent.click(screen.getByRole('button', { name: 'Start prescription' }))
    fireEvent.click(await screen.findByRole('button', { name: 'Add medicine' }))
    fireEvent.change(screen.getByLabelText('Drug name 1'), { target: { value: 'Paracetamol' } })
    fireEvent.change(screen.getByLabelText('Frequency 1'), { target: { value: 'Daily' } })
    fireEvent.change(screen.getByLabelText('Duration (days) 1'), { target: { value: '3' } })
    fireEvent.click(screen.getByRole('button', { name: 'Save medicines' }))
    expect(await screen.findByText('dose is required.')).toBeInTheDocument()
  })

  it('maps a missing acknowledgement from the server to a message', async () => {
    consultRoutes(ENC, {
      'POST /api/v1/encounters/e-1/prescriptions': created({ ...RX, version: 1, items: [ITEM] }),
      'POST /api/v1/prescriptions/rx-1/issue': json(422, { code: 'ALLERGY_NOT_ACKNOWLEDGED', detail: 'x' }),
    })
    await openConsult()
    fireEvent.click(screen.getByRole('button', { name: 'Start prescription' }))
    fireEvent.click(await screen.findByRole('button', { name: 'Issue prescription' }))
    expect(await screen.findByText(/acknowledge the allergy warnings/i)).toBeInTheDocument()
  })

  it('revises an issued prescription into a new draft', async () => {
    const issued: Prescription = { ...RX, status: 'ISSUED', version: 2, items: [ITEM], issuedAt: '2026-11-20T04:30:00Z' }
    consultRoutes(ENC, {
      'POST /api/v1/encounters/e-1/prescriptions': created(issued),
      'POST /api/v1/prescriptions/rx-1/revise': () => {
        const next = { ...RX, id: 'rx-2', versionNo: 2, supersedesId: 'rx-1', items: [ITEM] }
        refs = [ref(next), { ...ref(issued), status: 'SUPERSEDED' }]
        return json(201, next)
      },
    })
    await openConsult()
    fireEvent.click(screen.getByRole('button', { name: 'Start prescription' }))
    fireEvent.click(await screen.findByRole('button', { name: 'Revise prescription' }))
    expect(await screen.findByRole('button', { name: 'Save medicines' })).toBeInTheDocument()
    expect(screen.getByText('Version 2')).toBeInTheDocument()
  })
})

describe('prescription lookup from the server', () => {
  it('finds the existing prescription on a fresh render (no browser storage involved)', async () => {
    const issued: Prescription = { ...RX, status: 'ISSUED', version: 2, items: [{ drugName: 'Paracetamol', strength: null, form: null, route: null, dose: '500 mg', frequency: 'Twice daily', durationDays: 3, instructions: null }] }
    refs = [{ id: 'rx-2', status: 'DRAFT', versionNo: 2 }, { id: 'rx-1', status: 'SUPERSEDED', versionNo: 1 }]
    const { calls } = consultRoutes(ENC, { 'GET /api/v1/prescriptions/rx-2': json(200, { ...issued, id: 'rx-2', status: 'DRAFT', versionNo: 2 }) })
    await openConsult()
    expect(await screen.findByRole('button', { name: 'Save medicines' })).toBeInTheDocument()
    expect(screen.getByText('Version 2')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Start prescription' })).not.toBeInTheDocument()
    expect(calls.some((c) => c.key === 'GET /api/v1/prescriptions/rx-2')).toBe(true)
  })

  it('explains a 409 on create and reads the prescription from the server', async () => {
    const issued: Prescription = { ...RX, status: 'ISSUED', version: 2 }
    consultRoutes(ENC, {
      'POST /api/v1/encounters/e-1/prescriptions': () => { refs = [ref(issued)]; return json(409, { code: 'INVALID_STATE_TRANSITION' }) },
      'GET /api/v1/prescriptions/rx-1': json(200, issued),
    })
    await openConsult()
    fireEvent.click(screen.getByRole('button', { name: 'Start prescription' }))
    expect(await screen.findByText(/A prescription already exists for this visit/)).toBeInTheDocument()
    expect(await screen.findByRole('link', { name: 'Open prescription PDF' })).toBeInTheDocument()
  })
})

describe('finalize and read-only', () => {
  it('finalizes after confirmation and turns the record read-only', async () => {
    const finalized: Encounter = { ...ENC, status: 'FINALIZED', version: 4, finalizedAt: '2026-11-20T04:40:00Z', notes: [{ kind: 'SYMPTOMS', text: 'Cough', authorId: 'u-9', createdAt: '' }] }
    const { calls } = consultRoutes(ENC, { 'POST /api/v1/encounters/e-1/finalize': json(200, finalized) })
    await openConsult()
    fireEvent.click(screen.getByRole('button', { name: 'Finalize encounter' }))
    fireEvent.click(await screen.findByRole('button', { name: 'Confirm finalize' }))
    await waitFor(() => expect(within(screen.getByRole('region', { name: 'Encounter summary' })).getByText('Finalized')).toBeInTheDocument())
    expect(bodyOf(calls.find((c) => c.key.endsWith('/finalize'))?.init)).toEqual({ version: 3 })
    expect(screen.getByLabelText('Symptoms')).toBeDisabled()
    expect(screen.queryByRole('button', { name: 'Save vitals' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Save notes' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Finalize encounter' })).not.toBeInTheDocument()
  })

  it('hides Finalize without encounter:finalize', async () => {
    consultRoutes(ENC, {}, DOCTOR_PERMS.filter((p) => p !== 'encounter:finalize'))
    await openConsult()
    expect(screen.queryByRole('button', { name: 'Finalize encounter' })).not.toBeInTheDocument()
  })

  it('says so when the encounter was finalized elsewhere', async () => {
    consultRoutes(ENC, { 'PUT /api/v1/encounters/e-1/observations': json(409, { code: 'ENCOUNTER_FINALIZED' }) })
    await openConsult()
    fireEvent.change(screen.getByLabelText('Pulse (bpm)'), { target: { value: '80' } })
    fireEvent.click(screen.getByRole('button', { name: 'Save vitals' }))
    expect(await screen.findByText(/already finalized/i)).toBeInTheDocument()
  })
})

describe('addendum', () => {
  const FINAL: Encounter = {
    ...ENC, status: 'FINALIZED', version: 4, finalizedAt: '2026-11-20T04:40:00Z',
    notes: [{ kind: 'ADDENDUM', text: 'First addendum', authorId: 'u-9', createdAt: '2026-11-20T05:00:00Z' }],
  }

  it('adds an addendum to a finalized encounter and lists it', async () => {
    const after: Encounter = { ...FINAL, notes: [...FINAL.notes, { kind: 'ADDENDUM', text: 'Lab came back normal', authorId: 'u-9', createdAt: '2026-11-20T06:00:00Z' }] }
    const { calls } = consultRoutes(FINAL, { 'POST /api/v1/encounters/e-1/addenda': json(201, after) })
    await openConsult(FINAL)
    expect(screen.getByText('First addendum')).toBeInTheDocument()
    fireEvent.change(screen.getByLabelText('Addendum'), { target: { value: 'Lab came back normal' } })
    fireEvent.click(screen.getByRole('button', { name: 'Add addendum' }))
    expect(await screen.findByText('Lab came back normal')).toBeInTheDocument()
    expect(bodyOf(calls.find((c) => c.key.endsWith('/addenda'))?.init)).toEqual({ text: 'Lab came back normal' })
  })

  it('does not offer an addendum on an open encounter', async () => {
    consultRoutes(ENC)
    await openConsult()
    expect(screen.queryByLabelText('Addendum')).not.toBeInTheDocument()
  })

  it('hides the addendum form without encounter:addendum', async () => {
    consultRoutes(FINAL, {}, DOCTOR_PERMS.filter((p) => p !== 'encounter:addendum'))
    await openConsult(FINAL)
    expect(screen.queryByLabelText('Addendum')).not.toBeInTheDocument()
  })
})

describe('patient timeline', () => {
  it('lists the patient history and links to other encounters', async () => {
    consultRoutes(ENC)
    await openConsult()
    const timeline = await screen.findByRole('list', { name: 'Patient timeline' })
    expect(within(timeline).getByRole('link', { name: /Old sprain/ })).toHaveAttribute('href', '/consult/e-0')
  })
})

describe('access', () => {
  it('shows the forbidden page without encounter:read', async () => {
    mockApi({ 'GET /api/v1/auth/me': json(200, session(['appointment:read'])) })
    renderApp('/consult/e-1')
    expect(await screen.findByRole('heading', { name: /is not available to you/ })).toBeInTheDocument()
  })

  it('explains a 403 on reading the encounter', async () => {
    mockApi({
      'GET /api/v1/auth/me': json(200, session(DOCTOR_PERMS)),
      'GET /api/v1/encounters/e-1': json(403, { code: 'FORBIDDEN' }),
    })
    renderApp('/consult/e-1')
    expect(await screen.findByRole('alert')).toHaveTextContent('permission')
  })
})
