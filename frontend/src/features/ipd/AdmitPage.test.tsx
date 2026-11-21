import { fireEvent, screen, waitFor } from '@testing-library/react'
import { json, mockApi, renderApp, session } from '../../test/helpers'
import { ADMISSION, AVAILABILITY } from './fixtures'

const DOCTOR = {
  id: 'd-1', userId: 'u-9', fullName: 'Dr. Test Example', registrationNo: 'DEMO-0001', registrationCouncil: 'DEMO', qualifications: [],
  defaultSlotMinutes: 15, status: 'ACTIVE', primarySpecialtyId: 'sp-1', specialtyIds: ['sp-1'], departmentIds: [], version: 1, createdAt: '', updatedAt: '',
}
const PATIENT_HIT = { id: 'p-1', uhid: 'DEMO-000001', fullName: 'Asha Testpatient', sex: 'F', dateOfBirth: '1990-05-10', phoneMasked: '+91******0001', abhaLast4: null, status: 'ACTIVE' }
const PERMS = ['admission:create', 'admission:read', 'bed:read', 'practitioner:read', 'patient:search']

function routes(extra: Parameters<typeof mockApi>[0] = {}) {
  return mockApi({
    'GET /api/v1/auth/me': json(200, session(PERMS)),
    'GET /api/v1/doctors?status=ACTIVE&size=100': json(200, { items: [DOCTOR], page: 0, size: 100, totalItems: 1 }),
    'GET /api/v1/patients?q=Asha&size=10': json(200, { items: [PATIENT_HIT], page: 0, size: 10, totalItems: 1 }),
    'GET /api/v1/beds/availability?': json(200, AVAILABILITY),
    ...extra,
  })
}

async function fillForm() {
  fireEvent.change(await screen.findByLabelText('Find patient'), { target: { value: 'Asha' } })
  fireEvent.click(screen.getByRole('button', { name: 'Search' }))
  fireEvent.click(await screen.findByRole('button', { name: 'Asha Testpatient (DEMO-000001)' }))
  await screen.findByRole('option', { name: 'Dr. Test Example' })
  fireEvent.change(screen.getByLabelText('Doctor'), { target: { value: 'd-1' } })
  await screen.findByRole('option', { name: 'General Ward — G-2' })
  fireEvent.change(screen.getByLabelText('Bed'), { target: { value: 'b-2' } })
  fireEvent.change(screen.getByLabelText('Admission diagnosis'), { target: { value: 'Acute gastroenteritis' } })
}

describe('admit page', () => {
  it('offers only available beds and admits the patient', async () => {
    const { calls } = routes({ 'POST /api/v1/admissions': json(201, { ...ADMISSION, id: 'adm-2' }) })
    renderApp('/admissions/new')
    await fillForm()
    expect(screen.queryByRole('option', { name: 'General Ward — G-1' })).not.toBeInTheDocument()
    expect(screen.queryByRole('option', { name: 'General Ward — G-4' })).not.toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Admit' }))
    await waitFor(() => expect(calls.some((c) => c.key === 'POST /api/v1/admissions')).toBe(true))
    expect(JSON.parse(String(calls.find((c) => c.key === 'POST /api/v1/admissions')!.init!.body))).toEqual({
      patientId: 'p-1', admittingDoctorId: 'd-1', bedId: 'b-2', admissionDiagnosis: 'Acute gastroenteritis',
    })
  })

  it('on BED_NOT_AVAILABLE says the bed was taken and refetches availability', async () => {
    const { calls } = routes({ 'POST /api/v1/admissions': json(409, { code: 'BED_NOT_AVAILABLE', detail: 'x' }) })
    renderApp('/admissions/new')
    await fillForm()
    const before = calls.filter((c) => c.key === 'GET /api/v1/beds/availability?').length
    fireEvent.click(screen.getByRole('button', { name: 'Admit' }))
    expect(await screen.findByText('Bed was just taken — pick another')).toBeInTheDocument()
    await waitFor(() => expect(calls.filter((c) => c.key === 'GET /api/v1/beds/availability?').length).toBeGreaterThan(before))
    expect((screen.getByLabelText('Bed') as HTMLSelectElement).value).toBe('')
  })

  it('requires a patient, doctor, bed and diagnosis', async () => {
    const { calls } = routes()
    renderApp('/admissions/new')
    fireEvent.click(await screen.findByRole('button', { name: 'Admit' }))
    expect(await screen.findByText('Select a patient')).toBeInTheDocument()
    expect(screen.getAllByText('Select a doctor')).toHaveLength(2)
    expect(screen.getAllByText('Select an available bed')).toHaveLength(2)
    expect(screen.getByText('Enter the admission diagnosis')).toBeInTheDocument()
    expect(calls.some((c) => c.key.startsWith('POST'))).toBe(false)
  })
})
