import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { auditQueryString } from '../../api/audit'
import { json, mockApi, renderApp, session } from '../../test/helpers'
import { AuditFilterForm, auditFilterSchema, toAuditFilters } from './AuditFilterForm'

const ACTOR = '123e4567-e89b-12d3-a456-426614174000'

describe('audit filter query params', () => {
  it('drops empty values and keeps the rest', () => {
    expect(auditQueryString({ action: 'PATIENT_VIEWED', actorId: '', page: 0, size: 25 })).toBe(
      'action=PATIENT_VIEWED&page=0&size=25',
    )
  })

  it('converts local date-times to UTC instants and omits empty fields', () => {
    const filters = toAuditFilters({ actorId: '', action: 'LOGIN', entityType: '', entityId: '', from: '2026-01-31T10:00', to: '' })
    expect(filters).toEqual({ action: 'LOGIN', from: new Date('2026-01-31T10:00').toISOString() })
  })
})

describe('AuditFilterForm', () => {
  const empty = { actorId: '', action: '', entityType: '', entityId: '', from: '', to: '' }

  it('rejects malformed values and a reversed range', () => {
    expect(auditFilterSchema.safeParse({ ...empty, action: 'lower' }).success).toBe(false)
    expect(auditFilterSchema.safeParse({ ...empty, actorId: 'x' }).success).toBe(false)
    expect(auditFilterSchema.safeParse({ ...empty, from: '2026-02-01T00:00', to: '2026-01-01T00:00' }).success).toBe(false)
    expect(auditFilterSchema.safeParse({ ...empty, actorId: ACTOR, action: 'PATIENT_VIEWED' }).success).toBe(true)
  })

  it('shows field errors and does not apply invalid filters', async () => {
    const onApply = vi.fn()
    render(<AuditFilterForm onApply={onApply} />)
    fireEvent.change(screen.getByLabelText('Action'), { target: { value: 'bad action' } })
    fireEvent.click(screen.getByRole('button', { name: 'Apply filters' }))
    expect(await screen.findByText(/Action must be UPPER_SNAKE_CASE/)).toBeInTheDocument()
    expect(onApply).not.toHaveBeenCalled()
  })

  it('applies trimmed filters', async () => {
    const onApply = vi.fn()
    render(<AuditFilterForm onApply={onApply} />)
    fireEvent.change(screen.getByLabelText('Entity type'), { target: { value: ' PATIENT ' } })
    fireEvent.change(screen.getByLabelText('Actor id'), { target: { value: ACTOR } })
    fireEvent.click(screen.getByRole('button', { name: 'Apply filters' }))
    await waitFor(() => expect(onApply).toHaveBeenCalledWith({ entityType: 'PATIENT', actorId: ACTOR }))
  })
})

describe('AuditPage', () => {
  const event = {
    id: 'e-1',
    occurredAt: '2026-01-31T10:00:00Z',
    actorId: ACTOR,
    action: 'PATIENT_VIEWED',
    entityType: 'PATIENT',
    entityId: 'p-1',
    outcome: 'SUCCESS',
    changedFields: ['fullName'],
    correlationId: 'corr-1',
  }

  it('is hidden from users without audit:read', async () => {
    mockApi({ 'GET /api/v1/auth/me': json(200, session(['user:read'])) })
    renderApp('/audit')
    expect(await screen.findByText('Access denied')).toBeInTheDocument()
    expect(screen.queryByRole('link', { name: 'Audit' })).not.toBeInTheDocument()
  })

  it('lists events and sends the applied filters as query parameters', async () => {
    const { calls } = mockApi({
      'GET /api/v1/auth/me': json(200, session(['audit:read'])),
      'GET /api/v1/audit-events?page=0&size=25': json(200, { items: [event], page: 0, size: 25, totalItems: 1 }),
      'GET /api/v1/audit-events?action=PATIENT_VIEWED&page=0&size=25': json(200, { items: [], page: 0, size: 25, totalItems: 0 }),
    })
    renderApp('/audit')
    expect(await screen.findByText('PATIENT_VIEWED')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Audit' })).toBeInTheDocument()
    fireEvent.change(screen.getByLabelText('Action'), { target: { value: 'PATIENT_VIEWED' } })
    fireEvent.click(screen.getByRole('button', { name: 'Apply filters' }))
    expect(await screen.findByText('No audit events found.')).toBeInTheDocument()
    expect(calls.map((c) => c.key)).toContain('GET /api/v1/audit-events?action=PATIENT_VIEWED&page=0&size=25')
  })
})
