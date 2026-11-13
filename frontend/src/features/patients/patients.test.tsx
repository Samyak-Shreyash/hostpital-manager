import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import type { Patient } from '../../api/patients'
import { json, mockApi, renderApp, session } from '../../test/helpers'
import { ageSex, maskedAbha } from './format'
import { PatientBanner } from './PatientBanner'
import { patientSearchSchema } from './PatientSearchPage'
import { registerSchema } from './schemas'

// Synthetic data only.
const PATIENT: Patient = {
  id: 'p-1',
  uhid: 'DEMO-000001',
  fullName: 'Asha Testpatient',
  sex: 'F',
  dateOfBirth: '1990-05-10',
  dobEstimated: false,
  phone: '+919800000001',
  abhaLast4: '1234',
  address: null,
  emergencyContact: null,
  status: 'ACTIVE',
  version: 3,
  createdAt: '2026-01-01T00:00:00Z',
  updatedAt: '2026-01-01T00:00:00Z',
}
const ALL = ['patient:search', 'patient:read', 'patient:create', 'patient:update', 'patient:update-identity', 'patient:update-clinical', 'patient:override-duplicate']
const ALLERGY = { id: 'a-1', substance: 'Penicillin', reaction: 'Rash', severity: 'SEVERE', status: 'ACTIVE', recordedAt: '2026-01-01T00:00:00Z', enteredInErrorAt: null }

function detailRoutes(permissions: string[], extra: Record<string, ReturnType<typeof json> | ((i?: RequestInit) => Response)> = {}) {
  return mockApi({
    'GET /api/v1/auth/me': json(200, session(permissions)),
    'GET /api/v1/patients/p-1': json(200, PATIENT),
    'GET /api/v1/patients/p-1/allergies': json(200, [ALLERGY]),
    'GET /api/v1/patients/p-1/medical-history': json(200, []),
    ...extra,
  })
}

describe('patient formatting and banner', () => {
  it('masks the ABHA number to its last 4 digits', () => {
    expect(maskedAbha('1234')).toBe('••••••••••1234')
    expect(maskedAbha(null)).toBe('Not recorded')
  })

  it('computes age from the birth date', () => {
    expect(ageSex('1990-05-10', 'F', new Date(2026, 4, 9))).toBe('35 y / Female')
    expect(ageSex('1990-05-10', 'F', new Date(2026, 4, 10))).toBe('36 y / Female')
  })

  it('shows UHID, name, age/sex and an allergy indicator, never the internal id', () => {
    render(<PatientBanner patient={PATIENT} allergies={[ALLERGY as never]} />)
    const banner = screen.getByRole('region', { name: 'Patient banner' })
    expect(banner).toHaveTextContent('DEMO-000001')
    expect(banner).toHaveTextContent('Asha Testpatient')
    expect(banner).toHaveTextContent('Allergies: 1')
    expect(banner).not.toHaveTextContent('p-1')
  })
})

describe('patient search', () => {
  it('rejects a name shorter than 3 characters and an empty search', () => {
    expect(patientSearchSchema.safeParse({ q: 'as', uhid: '', phone: '', dob: '' }).success).toBe(false)
    expect(patientSearchSchema.safeParse({ q: '', uhid: '', phone: '', dob: '' }).success).toBe(false)
    expect(patientSearchSchema.safeParse({ q: 'ash', uhid: '', phone: '', dob: '' }).success).toBe(true)
  })

  it('shows the validation error without calling the API, then lists masked results', async () => {
    const { calls } = mockApi({
      'GET /api/v1/auth/me': json(200, session(ALL)),
      'GET /api/v1/patients?q=Asha&page=0&size=20': json(200, {
        items: [{ id: 'p-1', uhid: 'DEMO-000001', fullName: 'Asha Testpatient', sex: 'F', dateOfBirth: '1990-05-10', phoneMasked: '+91******0001', abhaLast4: '1234', status: 'ACTIVE' }],
        page: 0, size: 20, totalItems: 1,
      }),
    })
    renderApp('/patients')
    fireEvent.change(await screen.findByLabelText('Name'), { target: { value: 'As' } })
    fireEvent.click(screen.getByRole('button', { name: 'Search' }))
    expect(await screen.findByText('Enter at least 3 characters of the name')).toBeInTheDocument()
    expect(calls.some((c) => c.key.startsWith('GET /api/v1/patients?'))).toBe(false)
    fireEvent.change(screen.getByLabelText('Name'), { target: { value: 'Asha' } })
    fireEvent.click(screen.getByRole('button', { name: 'Search' }))
    expect(await screen.findByText('+91******0001')).toBeInTheDocument()
    expect(screen.getByText('••••••••••1234')).toBeInTheDocument()
  })

  it('sends the ABHA number in the POST body, never the URL', async () => {
    const { calls } = mockApi({
      'GET /api/v1/auth/me': json(200, session(ALL)),
      'POST /api/v1/patients/search-by-abha?page=0&size=20': json(200, { items: [], page: 0, size: 20, totalItems: 0 }),
    })
    document.cookie = 'XSRF-TOKEN=t'
    renderApp('/patients')
    fireEvent.change(await screen.findByLabelText('ABHA number'), { target: { value: '12-3456-7890-1234' } })
    fireEvent.click(screen.getByRole('button', { name: 'Search by ABHA' }))
    expect(await screen.findByText('No patients found.')).toBeInTheDocument()
    const call = calls.find((c) => c.key.includes('search-by-abha'))!
    expect(call.key).not.toContain('1234')
    expect(JSON.parse(String(call.init?.body))).toEqual({ abhaNumber: '12-3456-7890-1234' })
  })
})

describe('patient registration', () => {
  const base = { fullName: 'Asha Testpatient', sex: 'F', dateOfBirth: '', ageYears: '', phone: '9800000001', abhaNumber: '', line1: '', line2: '', city: '', state: '', pincode: '' }

  it('requires a birth date or age and validates ABHA format', () => {
    expect(registerSchema.safeParse(base).success).toBe(false)
    expect(registerSchema.safeParse({ ...base, ageYears: '35' }).success).toBe(true)
    expect(registerSchema.safeParse({ ...base, ageYears: '35', abhaNumber: '123' }).success).toBe(false)
    expect(registerSchema.safeParse({ ...base, ageYears: '35', abhaNumber: '12-3456-7890-1234' }).success).toBe(true)
  })

  const candidate = { id: 'p-9', uhid: 'DEMO-000009', fullName: 'Asha Testpatient', dateOfBirth: '1990-05-10', phoneMasked: '+91******0001', score: 0.92 }

  async function submitDuplicate(permissions: string[], onPost: (init?: RequestInit) => Response) {
    document.cookie = 'XSRF-TOKEN=t'
    const api = mockApi({
      'GET /api/v1/auth/me': json(200, session(permissions)),
      'POST /api/v1/patients': onPost,
      'GET /api/v1/patients/p-1': json(200, PATIENT),
      'GET /api/v1/patients/p-1/allergies': json(200, []),
      'GET /api/v1/patients/p-1/medical-history': json(200, []),
    })
    renderApp('/patients/new')
    fireEvent.change(await screen.findByLabelText('Full name'), { target: { value: 'Asha Testpatient' } })
    fireEvent.change(screen.getByLabelText('Sex'), { target: { value: 'F' } })
    fireEvent.change(screen.getByLabelText('Date of birth'), { target: { value: '1990-05-10' } })
    fireEvent.change(screen.getByLabelText('Phone'), { target: { value: '9800000001' } })
    fireEvent.click(screen.getByRole('button', { name: 'Register' }))
    expect(await screen.findByText('This patient may already be registered')).toBeInTheDocument()
    expect(screen.getByText(/DEMO-000009/)).toBeInTheDocument()
    expect(screen.getByText(/\+91\*{6}0001/)).toBeInTheDocument()
    return api
  }

  const dup = () => json(409, { code: 'DUPLICATE_SUSPECTED', detail: 'may exist', candidates: [candidate] })

  it('lets a user with override permission register anyway with a reason', async () => {
    let n = 0
    const { calls } = await submitDuplicate(['patient:create', 'patient:read', 'patient:override-duplicate'], () => (++n === 1 ? dup() : json(201, PATIENT)))
    fireEvent.click(screen.getByRole('button', { name: 'Register anyway' }))
    expect(await screen.findByText('Enter a reason for registering a new patient')).toBeInTheDocument()
    fireEvent.change(screen.getByLabelText('Reason this is a different person'), { target: { value: 'Different father name' } })
    fireEvent.click(screen.getByRole('button', { name: 'Register anyway' }))
    expect(await screen.findByRole('region', { name: 'Patient banner' })).toHaveTextContent('DEMO-000001')
    const posts = calls.filter((c) => c.key === 'POST /api/v1/patients')
    expect(JSON.parse(String(posts[1].init?.body))).toMatchObject({ confirmNotDuplicate: true, overrideReason: 'Different father name' })
  })

  it('hides the override for users without patient:override-duplicate', async () => {
    await submitDuplicate(['patient:create', 'patient:read'], dup)
    expect(screen.queryByRole('button', { name: 'Register anyway' })).not.toBeInTheDocument()
  })

  it('shows DUPLICATE_ABHA under the ABHA field', async () => {
    document.cookie = 'XSRF-TOKEN=t'
    mockApi({
      'GET /api/v1/auth/me': json(200, session(['patient:create'])),
      'POST /api/v1/patients': json(409, { code: 'DUPLICATE_ABHA', detail: 'x' }),
    })
    renderApp('/patients/new')
    fireEvent.change(await screen.findByLabelText('Full name'), { target: { value: 'Asha Testpatient' } })
    fireEvent.change(screen.getByLabelText('Sex'), { target: { value: 'F' } })
    fireEvent.change(screen.getByLabelText('Age in years (if date of birth unknown)'), { target: { value: '35' } })
    fireEvent.change(screen.getByLabelText('Phone'), { target: { value: '9800000001' } })
    fireEvent.change(screen.getByLabelText('ABHA number (optional)'), { target: { value: '12345678901234' } })
    fireEvent.click(screen.getByRole('button', { name: 'Register' }))
    expect(await screen.findByText('This ABHA number is already linked to another patient.')).toBeInTheDocument()
  })
})

describe('patient detail', () => {
  it('shows the masked ABHA, never the raw number', async () => {
    detailRoutes(['patient:read'])
    renderApp('/patients/p-1')
    expect(await screen.findByTestId('abha')).toHaveTextContent('••••••••••1234')
    expect(screen.queryByRole('button', { name: 'Save changes' })).not.toBeInTheDocument()
  })

  it('hides identity fields without patient:update-identity', async () => {
    detailRoutes(['patient:read', 'patient:update'])
    renderApp('/patients/p-1')
    expect(await screen.findByLabelText('Phone')).toBeInTheDocument()
    expect(screen.queryByLabelText('Full name')).not.toBeInTheDocument()
    expect(screen.queryByLabelText('Reason for identity change')).not.toBeInTheDocument()
  })

  it('requires a reason for identity changes and sends only changed fields with the version', async () => {
    document.cookie = 'XSRF-TOKEN=t'
    const { calls } = detailRoutes(ALL, { 'PATCH /api/v1/patients/p-1': json(200, { ...PATIENT, fullName: 'Asha T. Testpatient', version: 4 }) })
    renderApp('/patients/p-1')
    fireEvent.change(await screen.findByLabelText('Full name'), { target: { value: 'Asha T. Testpatient' } })
    fireEvent.click(screen.getByRole('button', { name: 'Save changes' }))
    expect(await screen.findByText('A reason is required to change name, sex or date of birth')).toBeInTheDocument()
    fireEvent.change(screen.getByLabelText('Reason for identity change'), { target: { value: 'Spelling fix' } })
    fireEvent.click(screen.getByRole('button', { name: 'Save changes' }))
    expect(await screen.findByText('Changes saved.')).toBeInTheDocument()
    const patch = calls.find((c) => c.key === 'PATCH /api/v1/patients/p-1')!
    expect(JSON.parse(String(patch.init?.body))).toEqual({ version: 3, fullName: 'Asha T. Testpatient', identityChangeReason: 'Spelling fix' })
  })

  it('offers a reload on 409 CONCURRENT_MODIFICATION', async () => {
    document.cookie = 'XSRF-TOKEN=t'
    detailRoutes(['patient:read', 'patient:update'], { 'PATCH /api/v1/patients/p-1': json(409, { code: 'CONCURRENT_MODIFICATION', detail: 'stale' }) })
    renderApp('/patients/p-1')
    fireEvent.change(await screen.findByLabelText('Phone'), { target: { value: '9800000002' } })
    fireEvent.click(screen.getByRole('button', { name: 'Save changes' }))
    expect(await screen.findByText(/Someone else changed this patient/)).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Reload latest version' }))
    await waitFor(() => expect(screen.queryByText(/Someone else changed this patient/)).not.toBeInTheDocument())
  })

  it('marks an allergy as entered in error (patient:update-clinical only)', async () => {
    document.cookie = 'XSRF-TOKEN=t'
    let marked = false
    const { calls } = detailRoutes(['patient:read', 'patient:update-clinical'], {
      'GET /api/v1/patients/p-1/allergies': () => json(200, [{ ...ALLERGY, status: marked ? 'ENTERED_IN_ERROR' : 'ACTIVE' }]),
      'PATCH /api/v1/patients/p-1/allergies/a-1': () => { marked = true; return json(200, { ...ALLERGY, status: 'ENTERED_IN_ERROR' }) },
    })
    renderApp('/patients/p-1')
    fireEvent.click(await screen.findByRole('button', { name: 'Mark allergy Penicillin as entered in error' }))
    await waitFor(() => expect(screen.getByRole('region', { name: 'Patient banner' })).toHaveTextContent('No allergies recorded'))
    expect(screen.queryByRole('button', { name: /entered in error/ })).not.toBeInTheDocument()
    const patch = calls.find((c) => c.key === 'PATCH /api/v1/patients/p-1/allergies/a-1')!
    expect(JSON.parse(String(patch.init?.body))).toEqual({ status: 'ENTERED_IN_ERROR' })
  })

  it('hides the entered-in-error action without patient:update-clinical', async () => {
    detailRoutes(['patient:read'])
    renderApp('/patients/p-1')
    expect(await screen.findByText('Penicillin')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /entered in error/ })).not.toBeInTheDocument()
  })

  it('is forbidden without patient:read', async () => {
    mockApi({ 'GET /api/v1/auth/me': json(200, session(['patient:search'])) })
    renderApp('/patients/p-1')
    expect(await screen.findByText('Access denied')).toBeInTheDocument()
  })
})
