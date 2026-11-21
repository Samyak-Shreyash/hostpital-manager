import { fireEvent, screen, waitFor, within } from '@testing-library/react'
import { json, mockApi, renderApp, session } from '../../test/helpers'
import { ADMISSION, AVAILABILITY } from './fixtures'

const ASSIGNMENTS = [{
  id: 'as-1', bedId: 'b-1', bedNo: 'G-1', wardName: 'General Ward', dailyRateServiceId: 's-1', assignedAt: '2026-11-20T04:00:00Z',
  releasedAt: null, reason: null, criticalCare: false,
}]
const CHARGES = {
  asOf: '2026-11-21T04:00:00Z',
  lines: [{ description: 'Room charges — General Ward G-1 (2026-11-20–2026-11-20)', quantity: '1.00', unitPrice: '6000.00', amount: '6000.00', taxRate: '5.00', tax: '300.00', from: '2026-11-20', to: '2026-11-20' }],
  roomTotal: '6300.00',
}
const EMPTY_INVOICES = json(200, { items: [], page: 0, size: 100, totalItems: 0 })
const ALL = ['admission:read', 'admission:update', 'admission:cancel', 'bed:read', 'bed:assign', 'discharge:write', 'discharge:complete', 'invoice:read', 'invoice:create']

function routes(permissions: string[], admission = ADMISSION, extra: Parameters<typeof mockApi>[0] = {}) {
  return mockApi({
    'GET /api/v1/auth/me': json(200, session(permissions)),
    'GET /api/v1/admissions/adm-1': json(200, admission),
    'GET /api/v1/admissions/adm-1/bed-assignments': json(200, ASSIGNMENTS),
    'GET /api/v1/admissions/adm-1/discharge-summary': json(404, { code: 'NOT_FOUND' }),
    'GET /api/v1/admissions/adm-1/charges-preview': json(200, CHARGES),
    'GET /api/v1/invoices?patientId=p-1&size=100': EMPTY_INVOICES,
    'GET /api/v1/beds/availability?': json(200, AVAILABILITY),
    ...extra,
  })
}
const bodyOf = (init?: RequestInit) => JSON.parse(String(init?.body))

describe('admission detail', () => {
  it('shows the current bed, history and charges preview with the money exactly as sent', async () => {
    routes(ALL)
    renderApp('/admissions/adm-1')
    expect(await screen.findByTestId('current-bed')).toHaveTextContent('General Ward — G-1')
    expect(await screen.findByTestId('room-total')).toHaveTextContent('6300.00')
    expect(screen.getByText('300.00')).toBeInTheDocument()
    expect(screen.getByText('Current')).toBeInTheDocument()
  })

  it('transfers with a required reason', async () => {
    const { calls } = routes(ALL, ADMISSION, { 'POST /api/v1/admissions/adm-1/bed-assignments/transfer': json(200, { currentAssignment: ASSIGNMENTS[0], previousAssignment: ASSIGNMENTS[0] }) })
    renderApp('/admissions/adm-1')
    fireEvent.click(await screen.findByRole('button', { name: 'Transfer bed' }))
    const dialog = screen.getByRole('dialog')
    await within(dialog).findByRole('option', { name: 'General Ward — G-2' })
    expect(within(dialog).queryByRole('option', { name: 'General Ward — G-1' })).not.toBeInTheDocument()
    fireEvent.change(within(dialog).getByLabelText('Bed'), { target: { value: 'b-2' } })
    fireEvent.click(within(dialog).getByRole('button', { name: 'Confirm transfer' }))
    expect(await within(dialog).findByText('Enter a reason for the transfer')).toBeInTheDocument()
    fireEvent.change(within(dialog).getByLabelText('Reason'), { target: { value: 'Higher acuity' } })
    fireEvent.click(within(dialog).getByRole('button', { name: 'Confirm transfer' }))
    await waitFor(() => expect(calls.some((c) => c.key.endsWith('/transfer'))).toBe(true))
    expect(bodyOf(calls.find((c) => c.key.endsWith('/transfer'))!.init)).toEqual({ toBedId: 'b-2', reason: 'Higher acuity' })
  })

  it('on BED_NOT_AVAILABLE during a transfer says the bed was taken and refetches availability', async () => {
    const { calls } = routes(ALL, ADMISSION, { 'POST /api/v1/admissions/adm-1/bed-assignments/transfer': json(409, { code: 'BED_NOT_AVAILABLE' }) })
    renderApp('/admissions/adm-1')
    fireEvent.click(await screen.findByRole('button', { name: 'Transfer bed' }))
    const dialog = screen.getByRole('dialog')
    await within(dialog).findByRole('option', { name: 'General Ward — G-2' })
    fireEvent.change(within(dialog).getByLabelText('Bed'), { target: { value: 'b-2' } })
    fireEvent.change(within(dialog).getByLabelText('Reason'), { target: { value: 'Move' } })
    const before = calls.filter((c) => c.key === 'GET /api/v1/beds/availability?').length
    fireEvent.click(within(dialog).getByRole('button', { name: 'Confirm transfer' }))
    expect(await within(dialog).findByText('Bed was just taken — pick another')).toBeInTheDocument()
    await waitFor(() => expect(calls.filter((c) => c.key === 'GET /api/v1/beds/availability?').length).toBeGreaterThan(before))
  })

  it('shows the reload prompt on CONCURRENT_MODIFICATION when saving the summary', async () => {
    routes(ALL, ADMISSION, { 'PUT /api/v1/admissions/adm-1/discharge-summary': json(409, { code: 'CONCURRENT_MODIFICATION' }) })
    renderApp('/admissions/adm-1')
    fireEvent.change(await screen.findByLabelText('Final diagnosis'), { target: { value: 'Recovered' } })
    fireEvent.change(screen.getByLabelText('Condition at discharge'), { target: { value: 'Stable' } })
    fireEvent.change(screen.getByLabelText('Planned discharge'), { target: { value: '2026-11-22T10:00' } })
    fireEvent.click(screen.getByRole('button', { name: 'Save summary' }))
    expect(await screen.findByText(/Someone else changed this summary/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Reload latest version' })).toBeInTheDocument()
  })

  it('requires the summary fields before calling the API, then saves with the facility-time instant', async () => {
    const { calls } = routes(ALL, ADMISSION, {
      'PUT /api/v1/admissions/adm-1/discharge-summary': json(200, { id: 'sm-1', admissionId: 'adm-1', finalDiagnosis: 'Recovered', courseInHospital: null, procedures: null, conditionAtDischarge: 'Stable', medications: [], followUp: null, plannedDischargeAt: '2026-11-22T04:30:00Z', version: 0 }),
    })
    renderApp('/admissions/adm-1')
    fireEvent.click(await screen.findByRole('button', { name: 'Save summary' }))
    expect(await screen.findByText('Enter the final diagnosis')).toBeInTheDocument()
    expect(screen.getByText('Enter the condition at discharge')).toBeInTheDocument()
    expect(calls.some((c) => c.key.startsWith('PUT'))).toBe(false)
    fireEvent.change(screen.getByLabelText('Final diagnosis'), { target: { value: 'Recovered' } })
    fireEvent.change(screen.getByLabelText('Condition at discharge'), { target: { value: 'Stable' } })
    fireEvent.change(screen.getByLabelText('Planned discharge'), { target: { value: '2026-11-22T10:00' } })
    fireEvent.click(screen.getByRole('button', { name: 'Save summary' }))
    await waitFor(() => expect(calls.some((c) => c.key.startsWith('PUT'))).toBe(true))
    expect(bodyOf(calls.find((c) => c.key.startsWith('PUT'))!.init)).toEqual({
      finalDiagnosis: 'Recovered', conditionAtDischarge: 'Stable', plannedDischargeAt: '2026-11-22T04:30:00.000Z', medications: [],
    })
  })

  it('creates an interim invoice and links to the billing page', async () => {
    const { calls } = routes(ALL, ADMISSION, {
      'POST /api/v1/invoices': json(201, { id: 'inv-1' }),
      'GET /api/v1/invoices/inv-1': json(404, { code: 'NOT_FOUND' }),
    })
    renderApp('/admissions/adm-1')
    fireEvent.click(await screen.findByRole('button', { name: 'Create interim invoice' }))
    await waitFor(() => expect(calls.some((c) => c.key === 'POST /api/v1/invoices')).toBe(true))
    expect(bodyOf(calls.find((c) => c.key === 'POST /api/v1/invoices')!.init)).toEqual({ type: 'IPD', patientId: 'p-1', admissionId: 'adm-1', kind: 'INTERIM' })
    await waitFor(() => expect(calls.some((c) => c.key === 'GET /api/v1/invoices/inv-1')).toBe(true))
  })

  const INITIATED = { ...ADMISSION, status: 'DISCHARGE_INITIATED' as const, plannedDischargeAt: '2026-11-22T04:30:00Z', version: 5 }
  const FINAL = { id: 'inv-9', type: 'IPD', patientId: 'p-1', encounterId: null, admissionId: 'adm-1', kind: 'FINAL', status: 'ISSUED', invoiceNo: '2026-27/000050', items: [] }

  it('offers defer billing only with discharge:defer-billing and discharges with the final invoice', async () => {
    const without = routes(ALL, INITIATED, {
      'GET /api/v1/invoices?patientId=p-1&size=100': json(200, { items: [FINAL], page: 0, size: 100, totalItems: 1 }),
      'GET /api/v1/admissions/adm-1/discharge-summary': json(404, {}),
      'POST /api/v1/admissions/adm-1/discharge': json(200, { status: 'DISCHARGED', bedReleased: true }),
    })
    renderApp('/admissions/adm-1')
    fireEvent.click(await screen.findByRole('button', { name: 'Discharge patient' }))
    const dialog = screen.getByRole('dialog')
    expect(within(dialog).queryByLabelText(/Defer billing/)).not.toBeInTheDocument()
    expect((within(dialog).getByLabelText('Discharged at') as HTMLInputElement).value).toBe('2026-11-22T10:00')
    fireEvent.click(within(dialog).getByRole('button', { name: 'Confirm discharge' }))
    await waitFor(() => expect(without.calls.some((c) => c.key === 'POST /api/v1/admissions/adm-1/discharge')).toBe(true))
    expect(bodyOf(without.calls.find((c) => c.key === 'POST /api/v1/admissions/adm-1/discharge')!.init)).toEqual({
      version: 5, dischargedAt: '2026-11-22T04:30:00.000Z', deferBilling: false, invoiceId: 'inv-9',
    })
  })

  it('shows the defer-billing checkbox with permission and sends it', async () => {
    const { calls } = routes([...ALL, 'discharge:defer-billing'], INITIATED, {
      'POST /api/v1/admissions/adm-1/discharge': json(200, { status: 'DISCHARGED', bedReleased: true }),
    })
    renderApp('/admissions/adm-1')
    fireEvent.click(await screen.findByRole('button', { name: 'Discharge patient' }))
    const dialog = screen.getByRole('dialog')
    fireEvent.click(within(dialog).getByLabelText(/Defer billing/))
    fireEvent.click(within(dialog).getByRole('button', { name: 'Confirm discharge' }))
    await waitFor(() => expect(calls.some((c) => c.key === 'POST /api/v1/admissions/adm-1/discharge')).toBe(true))
    expect(bodyOf(calls.find((c) => c.key === 'POST /api/v1/admissions/adm-1/discharge')!.init)).toEqual({
      version: 5, dischargedAt: '2026-11-22T04:30:00.000Z', deferBilling: true,
    })
  })

  it('hides actions the user lacks permission for', async () => {
    routes(['admission:read', 'bed:read'])
    renderApp('/admissions/adm-1')
    await screen.findByTestId('current-bed')
    expect(screen.queryByRole('button', { name: 'Transfer bed' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Cancel admission' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Create interim invoice' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Save summary' })).not.toBeInTheDocument()
  })
})
