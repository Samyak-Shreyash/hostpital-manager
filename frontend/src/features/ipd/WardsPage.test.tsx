import { fireEvent, screen, waitFor } from '@testing-library/react'
import { json, mockApi, renderApp, session } from '../../test/helpers'
import { CRITICAL_CARE_HELP } from './WardsPage'

const WARD = { id: 'w-1', code: 'GEN', name: 'General Ward', departmentId: null, status: 'ACTIVE', careType: 'GENERAL', version: 0 }
const page = (items: unknown[]) => json(200, { items, page: 0, size: 100, totalItems: items.length })

describe('wards page', () => {
  it('creates a critical-care ward and shows the GST help text', async () => {
    const { calls } = mockApi({
      'GET /api/v1/auth/me': json(200, session(['ward:read', 'ward:manage', 'bed:read'])),
      'GET /api/v1/wards?size=100': page([WARD]),
      'POST /api/v1/wards': json(201, { ...WARD, id: 'w-2', code: 'ICU', name: 'ICU', careType: 'CRITICAL_CARE' }),
    })
    renderApp('/wards')
    expect(await screen.findByText('General Ward')).toBeInTheDocument()
    expect(screen.getByText(CRITICAL_CARE_HELP)).toBeInTheDocument()
    expect(CRITICAL_CARE_HELP).toBe('Critical care = ICU, CCU, ICCU or NICU. Room charges in critical-care wards are GST-exempt.')
    fireEvent.change(screen.getByLabelText('Code'), { target: { value: 'ICU' } })
    fireEvent.change(screen.getByLabelText('Name'), { target: { value: 'ICU' } })
    fireEvent.change(screen.getByLabelText('Care type'), { target: { value: 'CRITICAL_CARE' } })
    fireEvent.click(screen.getByRole('button', { name: 'Create ward' }))
    await waitFor(() => expect(calls.some((c) => c.key === 'POST /api/v1/wards')).toBe(true))
    expect(JSON.parse(String(calls.find((c) => c.key === 'POST /api/v1/wards')!.init!.body))).toEqual({ code: 'ICU', name: 'ICU', careType: 'CRITICAL_CARE' })
  })

  it('rejects a lower-case code before calling the API', async () => {
    const { calls } = mockApi({
      'GET /api/v1/auth/me': json(200, session(['ward:read', 'ward:manage'])),
      'GET /api/v1/wards?size=100': page([]),
    })
    renderApp('/wards')
    fireEvent.change(await screen.findByLabelText('Code'), { target: { value: 'icu' } })
    fireEvent.change(screen.getByLabelText('Name'), { target: { value: 'ICU' } })
    fireEvent.click(screen.getByRole('button', { name: 'Create ward' }))
    expect(await screen.findByText(/capital letters/)).toBeInTheDocument()
    expect(calls.some((c) => c.key.startsWith('POST'))).toBe(false)
  })

  it('shows a BED_OCCUPIED refusal when taking a bed out of service', async () => {
    mockApi({
      'GET /api/v1/auth/me': json(200, session(['ward:read', 'bed:read', 'bed:manage'])),
      'GET /api/v1/wards?size=100': page([WARD]),
      'GET /api/v1/wards/w-1/beds?size=100': page([{ id: 'b-1', wardId: 'w-1', bedNo: 'G-1', bedClass: null, dailyRateServiceId: 's-1', status: 'ACTIVE', version: 0 }]),
      'PATCH /api/v1/beds/b-1': json(409, { code: 'BED_OCCUPIED', detail: 'occupied' }),
    })
    renderApp('/wards')
    fireEvent.click(await screen.findByRole('button', { name: 'Manage General Ward' }))
    fireEvent.click(await screen.findByRole('button', { name: 'Take bed G-1 out of service' }))
    expect(await screen.findByText(/occupied by an admitted patient/)).toBeInTheDocument()
  })
})
