import { fireEvent, screen, waitFor } from '@testing-library/react'
import { json, mockApi, renderApp, session } from '../../test/helpers'

const CARDIO = { id: 'd-1', code: 'CARDIO', name: 'Cardiology', active: true, version: 0 }
const page = (items: unknown[]) => json(200, { items, page: 0, size: 100, totalItems: items.length })

describe('catalog page', () => {
  it('creates a department and deactivates one', async () => {
    const { calls } = mockApi({
      'GET /api/v1/auth/me': json(200, session(['catalog:read', 'catalog:manage'])),
      'GET /api/v1/departments?size=100': page([CARDIO]),
      'GET /api/v1/specialties?size=100': page([]),
      'POST /api/v1/departments': json(201, { ...CARDIO, id: 'd-2', code: 'OPD', name: 'Outpatients' }),
      'PATCH /api/v1/departments/d-1': json(200, { ...CARDIO, active: false, version: 1 }),
    })
    renderApp('/catalog')
    expect(await screen.findByText('Cardiology')).toBeInTheDocument()
    expect(screen.getByText('No specialties yet.')).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: 'New department' }))
    fireEvent.change(screen.getByLabelText('Code'), { target: { value: 'OPD' } })
    fireEvent.change(screen.getByLabelText('Name'), { target: { value: 'Outpatients' } })
    fireEvent.click(screen.getByRole('button', { name: 'Create department' }))
    await waitFor(() => expect(calls.some((c) => c.key === 'POST /api/v1/departments')).toBe(true))
    expect(JSON.parse(String(calls.find((c) => c.key === 'POST /api/v1/departments')!.init!.body))).toEqual({ code: 'OPD', name: 'Outpatients' })

    fireEvent.click(screen.getByRole('button', { name: 'Deactivate Cardiology' }))
    await waitFor(() => expect(calls.some((c) => c.key === 'PATCH /api/v1/departments/d-1')).toBe(true))
    expect(JSON.parse(String(calls.find((c) => c.key === 'PATCH /api/v1/departments/d-1')!.init!.body))).toEqual({ version: 0, active: false })
  })

  it('rejects a lower-case code before calling the API', async () => {
    const { calls } = mockApi({
      'GET /api/v1/auth/me': json(200, session(['catalog:read', 'catalog:manage'])),
      'GET /api/v1/departments?size=100': page([]),
      'GET /api/v1/specialties?size=100': page([]),
    })
    renderApp('/catalog')
    fireEvent.click(await screen.findByRole('button', { name: 'New specialty' }))
    fireEvent.change(screen.getByLabelText('Code'), { target: { value: 'cardio' } })
    fireEvent.change(screen.getByLabelText('Name'), { target: { value: 'Cardiology' } })
    fireEvent.click(screen.getByRole('button', { name: 'Create specialty' }))
    expect(await screen.findByText(/2-20 characters/)).toBeInTheDocument()
    expect(calls.some((c) => c.key.startsWith('POST'))).toBe(false)
  })

  it('is read-only without catalog:manage', async () => {
    mockApi({
      'GET /api/v1/auth/me': json(200, session(['catalog:read'])),
      'GET /api/v1/departments?size=100': page([CARDIO]),
      'GET /api/v1/specialties?size=100': page([]),
    })
    renderApp('/catalog')
    expect(await screen.findByText('Cardiology')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /New|Edit|Deactivate/ })).not.toBeInTheDocument()
  })
})
