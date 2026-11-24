import { fireEvent, screen, waitFor, within } from '@testing-library/react'
import type { Encounter } from '../../api/clinical'
import type { Invoice, InvoiceItem } from '../../api/billing'
import { json, mockApi, renderApp, session } from '../../test/helpers'

// Synthetic data only. Amounts are chosen so a float round trip would show: 0.10 + 0.20, and a 14-digit value.
const CASHIER = ['invoice:read', 'invoice:create', 'invoice:update', 'invoice:issue', 'payment:create', 'payment:read', 'catalog:read']
const bodyOf = (init?: RequestInit) => JSON.parse(String(init?.body))
const headersOf = (init?: RequestInit) => (init?.headers ?? {}) as Record<string, string>

const line = (over: Partial<InvoiceItem> = {}): InvoiceItem => ({
  id: 'it-1', serviceId: 's-1', description: 'Consultation', quantity: '1.00', unitPrice: '0.10', discount: '0.00', discountReason: null,
  taxRate: '0.00', gross: '0.10', taxable: '0.10', tax: '0.00', total: '0.10', ...over,
})
const invoice = (over: Partial<Invoice> = {}): Invoice => ({
  id: 'inv-1', type: 'OPD', patientId: 'p-1', encounterId: 'e-1', admissionId: null, status: 'DRAFT', invoiceNo: null, issuedAt: null,
  subtotal: '0.30', discountTotal: '0.00', taxTotal: '0.00', total: '0.30', version: 1,
  items: [line(), line({ id: 'it-2', serviceId: 's-2', description: 'Dressing', unitPrice: '0.20', gross: '0.20', taxable: '0.20', total: '0.20' })],
  createdAt: '2026-11-20T04:00:00Z', updatedAt: '2026-11-20T04:00:00Z', ...over,
})
const SERVICES = {
  items: [
    { id: 's-1', code: 'CONS', name: 'Consultation', active: true, currentPrice: '0.10', version: 0 },
    { id: 's-3', code: 'XRAY', name: 'Chest X-ray', active: true, currentPrice: '350.00', version: 0 },
    { id: 's-9', code: 'NOPR', name: 'Unpriced service', active: true, currentPrice: null, version: 0 },
  ], page: 0, size: 100, totalItems: 3,
}
const problem = (status: number, code: string, detail: string, errors?: unknown[]) => json(status, { code, detail, title: code, status, ...(errors ? { errors } : {}) })

function editorRoutes(inv: Invoice, perms: string[] = CASHIER, extra: Parameters<typeof mockApi>[0] = {}) {
  return mockApi({
    'GET /api/v1/auth/me': json(200, session(perms)),
    [`GET /api/v1/invoices/${inv.id}`]: json(200, inv),
    'GET /api/v1/services?active=true&size=100': json(200, SERVICES),
    ...extra,
  })
}
async function openEditor(path = '/invoices/inv-1') {
  renderApp(path)
  await screen.findByRole('region', { name: 'Invoice totals' })
}

describe('invoice editor', () => {
  it('shows the server totals and amounts exactly as sent, never re-computed from numbers', async () => {
    editorRoutes(invoice({ total: '999999999999.99', subtotal: '999999999999.99' }))
    await openEditor()
    expect(screen.getByTestId('total')).toHaveTextContent('999999999999.99')
    expect(screen.getByTestId('subtotal')).toHaveTextContent('999999999999.99')
    expect(screen.getByLabelText('Unit price 1')).toHaveValue('0.10')
    expect(screen.getByLabelText('Unit price 2')).toHaveValue('0.20')
  })

  it('adds a catalog service without a price (catalog price) and shows the totals the server answers with', async () => {
    const saved = invoice({
      version: 2, total: '350.30', subtotal: '350.30',
      items: [...invoice().items, line({ id: 'it-3', serviceId: 's-3', description: 'Chest X-ray', unitPrice: '350.00', gross: '350.00', taxable: '350.00', total: '350.00' })],
    })
    const { calls } = editorRoutes(invoice(), CASHIER, { 'PUT /api/v1/invoices/inv-1/items': json(200, saved) })
    await openEditor()
    await screen.findByRole('option', { name: /Chest X-ray/ })
    fireEvent.change(screen.getByLabelText('Service'), { target: { value: 's-3' } })
    fireEvent.click(screen.getByRole('button', { name: 'Add service' }))
    expect(screen.getByLabelText('Unit price 3')).toHaveValue('350.00')
    fireEvent.click(screen.getByRole('button', { name: 'Save items' }))
    await waitFor(() => expect(screen.getByTestId('total')).toHaveTextContent('350.30'))
    const put = bodyOf(calls.find((c) => c.key === 'PUT /api/v1/invoices/inv-1/items')?.init)
    expect(put.version).toBe(1)
    expect(put.items).toHaveLength(3)
    // Untouched service lines carry no unitPrice, so the server prices them from the catalog.
    expect(put.items[2]).toEqual({ serviceId: 's-3', quantity: '1', taxRate: '0.00' })
    expect(put.items[0].unitPrice).toBeUndefined()
  })

  it('does not offer a service that has no price yet', async () => {
    editorRoutes(invoice())
    await openEditor()
    expect(await screen.findByRole('option', { name: /Unpriced service/ })).toBeDisabled()
  })

  it('sends a changed price and shows the price-override message on 403', async () => {
    const { calls } = editorRoutes(invoice(), CASHIER, {
      'PUT /api/v1/invoices/inv-1/items': problem(403, 'FORBIDDEN', 'Access is denied'),
    })
    await openEditor()
    fireEvent.change(screen.getByLabelText('Unit price 1'), { target: { value: '0.15' } })
    fireEvent.click(screen.getByRole('button', { name: 'Save items' }))
    expect(await screen.findByText(/permission to change prices/)).toBeInTheDocument()
    expect(bodyOf(calls.find((c) => c.key.startsWith('PUT'))?.init).items[0].unitPrice).toBe('0.15')
  })

  it('rejects a malformed amount before calling the server', async () => {
    const { calls } = editorRoutes(invoice())
    await openEditor()
    fireEvent.change(screen.getByLabelText('Unit price 1'), { target: { value: '1.234' } })
    fireEvent.click(screen.getByRole('button', { name: 'Save items' }))
    expect(await screen.findByText('Enter an amount such as 150 or 150.50')).toBeInTheDocument()
    expect(calls.some((c) => c.key.startsWith('PUT'))).toBe(false)
  })

  it('shows 422 field errors beside the line they belong to', async () => {
    editorRoutes(invoice(), CASHIER, {
      'PUT /api/v1/invoices/inv-1/items': problem(422, 'VALIDATION_ERROR', 'Invalid', [{ field: 'items[1].quantity', code: 'RANGE', message: 'quantity must be 0.01 to 9999.' }]),
    })
    await openEditor()
    fireEvent.click(screen.getByRole('button', { name: 'Save items' }))
    expect(await screen.findByText('quantity must be 0.01 to 9999.')).toBeInTheDocument()
  })

  it('needs a reason for a line discount', async () => {
    const { calls } = editorRoutes(invoice(), [...CASHIER, 'invoice:discount'], {
      'PUT /api/v1/invoices/inv-1/items': json(200, invoice({ version: 2, discountTotal: '0.05' })),
    })
    await openEditor()
    fireEvent.change(screen.getByLabelText('Discount 1'), { target: { value: '0.05' } })
    fireEvent.click(screen.getByRole('button', { name: 'Save items' }))
    expect(await screen.findByText('Give a reason for the discount')).toBeInTheDocument()
    expect(calls.some((c) => c.key.startsWith('PUT'))).toBe(false)
    fireEvent.change(screen.getByLabelText('Discount reason 1'), { target: { value: 'Staff family' } })
    fireEvent.click(screen.getByRole('button', { name: 'Save items' }))
    await waitFor(() => expect(calls.some((c) => c.key.startsWith('PUT'))).toBe(true))
    const first = bodyOf(calls.find((c) => c.key.startsWith('PUT'))?.init).items[0]
    expect(first.discount).toBe('0.05')
    expect(first.discountReason).toBe('Staff family')
  })

  it('has no discount inputs for a user without invoice:discount', async () => {
    editorRoutes(invoice())
    await openEditor()
    expect(screen.queryByLabelText('Discount 1')).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Apply percent discount' })).not.toBeInTheDocument()
  })

  it('applies a percent discount with a reason', async () => {
    const { calls } = editorRoutes(invoice(), [...CASHIER, 'invoice:discount'], {
      'PUT /api/v1/invoices/inv-1/discount': json(200, invoice({ version: 2, discountTotal: '0.03', total: '0.27' })),
    })
    await openEditor()
    fireEvent.click(screen.getByRole('button', { name: 'Apply percent discount' }))
    expect(await screen.findByText('Enter a percentage such as 10 or 12.5')).toBeInTheDocument()
    fireEvent.change(screen.getByLabelText('Discount percent'), { target: { value: '10' } })
    fireEvent.change(screen.getByLabelText('Reason for all lines'), { target: { value: 'Senior citizen' } })
    fireEvent.click(screen.getByRole('button', { name: 'Apply percent discount' }))
    await waitFor(() => expect(screen.getByTestId('total')).toHaveTextContent('0.27'))
    expect(bodyOf(calls.find((c) => c.key === 'PUT /api/v1/invoices/inv-1/discount')?.init)).toEqual({ version: 1, percent: '10', reason: 'Senior citizen' })
  })

  it('shows a reload prompt on a version conflict', async () => {
    editorRoutes(invoice(), CASHIER, { 'PUT /api/v1/invoices/inv-1/items': problem(409, 'CONCURRENT_MODIFICATION', 'stale') })
    await openEditor()
    fireEvent.click(screen.getByRole('button', { name: 'Save items' }))
    expect(await screen.findByRole('button', { name: 'Reload latest version' })).toBeInTheDocument()
  })

  it('is read-only once issued and links the invoice PDF', async () => {
    editorRoutes(invoice({ status: 'ISSUED', invoiceNo: '2026-27/000031', issuedAt: '2026-11-20T05:00:00Z' }))
    await openEditor()
    expect(screen.queryByRole('button', { name: 'Save items' })).not.toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Open invoice PDF' })).toHaveAttribute('href', '/api/v1/invoices/inv-1/pdf')
    expect(screen.getByRole('heading', { name: /2026-27\/000031/ })).toBeInTheDocument()
  })
})

describe('issue', () => {
  it('sends an Idempotency-Key and reuses it when a network error is retried', async () => {
    let attempts = 0
    const keys: string[] = []
    editorRoutes(invoice(), CASHIER, {
      'POST /api/v1/invoices/inv-1/issue': (init) => {
        keys.push(headersOf(init)['Idempotency-Key'])
        attempts += 1
        if (attempts === 1) throw new TypeError('Failed to fetch')
        return json(200, invoice({ status: 'ISSUED', invoiceNo: '2026-27/000031', version: 2 }))
      },
    })
    await openEditor()
    fireEvent.click(screen.getByRole('button', { name: 'Issue invoice' }))
    await waitFor(() => expect(attempts).toBe(2), { timeout: 4000 })
    expect(keys[0]).toBeTruthy()
    expect(keys[1]).toBe(keys[0])
  })

  it('keeps the key after a server refusal (same intent) so a replay is answered, not duplicated', async () => {
    const keys: string[] = []
    let refuse = true
    editorRoutes(invoice(), CASHIER, {
      'POST /api/v1/invoices/inv-1/issue': (init) => {
        keys.push(headersOf(init)['Idempotency-Key'])
        if (refuse) return problem(422, 'BUSINESS_RULE_VIOLATION', 'An invoice needs at least one item.')
        return json(200, invoice({ status: 'ISSUED', invoiceNo: '2026-27/000031', version: 2 }))
      },
    })
    await openEditor()
    fireEvent.click(screen.getByRole('button', { name: 'Issue invoice' }))
    expect(await screen.findByText('An invoice needs at least one item.')).toBeInTheDocument()
    refuse = false
    fireEvent.click(screen.getByRole('button', { name: 'Issue invoice' }))
    await waitFor(() => expect(keys).toHaveLength(2))
    expect(keys[1]).toBe(keys[0])
  })

  it('is disabled while there are unsaved changes', async () => {
    editorRoutes(invoice())
    await openEditor()
    expect(screen.getByRole('button', { name: 'Issue invoice' })).toBeEnabled()
    fireEvent.change(screen.getByLabelText('Quantity 1'), { target: { value: '2' } })
    expect(screen.getByRole('button', { name: 'Issue invoice' })).toBeDisabled()
    expect(screen.getByText(/Save your changes before issuing/)).toBeInTheDocument()
  })

  it('hides Issue without invoice:issue', async () => {
    editorRoutes(invoice(), ['invoice:read', 'invoice:update', 'catalog:read'])
    await openEditor()
    expect(screen.queryByRole('button', { name: 'Issue invoice' })).not.toBeInTheDocument()
  })
})

describe('payment', () => {
  const ISSUED = invoice({ status: 'ISSUED', invoiceNo: '2026-27/000031', issuedAt: '2026-11-20T05:00:00Z' })
  const RECORDED = {
    id: 'pay-1', invoiceId: 'inv-1', amount: '0.10', mode: 'CASH', referenceNo: null, receiptNo: 'RCT-2026-27/000044',
    receivedAt: '2026-11-20T05:10:00Z', receivedBy: 'u-1', outstanding: '0.20', invoiceStatus: 'PARTIALLY_PAID',
  }
  async function openDialog() {
    await openEditor()
    fireEvent.click(screen.getByRole('button', { name: 'Take payment' }))
    return screen.findByRole('dialog', { name: 'Record payment' })
  }

  it('records a payment with an Idempotency-Key and shows the server outstanding and receipt link', async () => {
    const { calls } = editorRoutes(ISSUED, CASHIER, { 'POST /api/v1/invoices/inv-1/payments': json(201, RECORDED) })
    const dialog = await openDialog()
    fireEvent.click(within(dialog).getByRole('button', { name: 'Cash' }))
    fireEvent.change(within(dialog).getByLabelText('Amount'), { target: { value: '0.10' } })
    fireEvent.change(within(dialog).getByLabelText('Cash received'), { target: { value: '1.00' } })
    expect(within(dialog).getByText('0.90')).toBeInTheDocument()
    fireEvent.click(within(dialog).getByRole('button', { name: 'Record payment' }))
    expect(await within(dialog).findByText('RCT-2026-27/000044')).toBeInTheDocument()
    expect(within(dialog).getByTestId('outstanding')).toHaveTextContent('0.20')
    expect(within(dialog).getByRole('link', { name: 'Open receipt PDF' })).toHaveAttribute('href', '/api/v1/payments/pay-1/receipt')
    const post = calls.find((c) => c.key === 'POST /api/v1/invoices/inv-1/payments')
    expect(bodyOf(post?.init)).toEqual({ amount: '0.10', mode: 'CASH' })
    expect(headersOf(post?.init)['Idempotency-Key']).toBeTruthy()
  })

  it('shows the server outstanding when the amount is too high', async () => {
    editorRoutes(ISSUED, CASHIER, { 'POST /api/v1/invoices/inv-1/payments': problem(409, 'EXCEEDS_OUTSTANDING', 'Outstanding is 120.00.') })
    const dialog = await openDialog()
    fireEvent.change(within(dialog).getByLabelText('Amount'), { target: { value: '500' } })
    fireEvent.click(within(dialog).getByRole('button', { name: 'Record payment' }))
    expect(await within(dialog).findByText('Outstanding is 120.00.')).toBeInTheDocument()
  })

  it('checks the amount by pattern only and sends nothing when it is malformed', async () => {
    const { calls } = editorRoutes(ISSUED)
    const dialog = await openDialog()
    fireEvent.change(within(dialog).getByLabelText('Amount'), { target: { value: '12.345' } })
    fireEvent.click(within(dialog).getByRole('button', { name: 'Record payment' }))
    expect(await within(dialog).findByText('Enter an amount such as 150 or 150.50')).toBeInTheDocument()
    expect(calls.some((c) => c.key.startsWith('POST'))).toBe(false)
  })

  it('reuses the key for the same request and takes a new one when the request changes', async () => {
    const keys: string[] = []
    let fail = true
    editorRoutes(ISSUED, CASHIER, {
      'POST /api/v1/invoices/inv-1/payments': (init) => {
        keys.push(headersOf(init)['Idempotency-Key'])
        return fail ? problem(500, 'INTERNAL_ERROR', 'boom') : json(201, RECORDED)
      },
    })
    const dialog = await openDialog()
    const submit = () => fireEvent.click(within(dialog).getByRole('button', { name: 'Record payment' }))
    fireEvent.change(within(dialog).getByLabelText('Amount'), { target: { value: '0.10' } })
    submit()
    await waitFor(() => expect(keys).toHaveLength(1))
    await within(dialog).findByRole('alert')
    submit()
    await waitFor(() => expect(keys).toHaveLength(2))
    expect(keys[1]).toBe(keys[0])
    await waitFor(() => expect(within(dialog).getByRole('button', { name: 'Record payment' })).toBeEnabled())
    fireEvent.change(within(dialog).getByLabelText('Amount'), { target: { value: '0.20' } })
    fail = false
    submit()
    await waitFor(() => expect(keys).toHaveLength(3))
    expect(keys[2]).not.toBe(keys[0])
  })

  it('keeps the key across close and reopen after a network error, so a retry cannot pay twice', async () => {
    const keys: string[] = []
    let offline = true
    editorRoutes(ISSUED, CASHIER, {
      'POST /api/v1/invoices/inv-1/payments': (init) => {
        keys.push(headersOf(init)['Idempotency-Key'])
        if (offline) throw new TypeError('Failed to fetch')
        return json(201, RECORDED)
      },
    })
    let dialog = await openDialog()
    fireEvent.change(within(dialog).getByLabelText('Amount'), { target: { value: '0.10' } })
    fireEvent.click(within(dialog).getByRole('button', { name: 'Record payment' }))
    await within(dialog).findByRole('alert')
    const firstKey = keys[0]
    expect(keys.every((k) => k === firstKey)).toBe(true)
    fireEvent.click(within(dialog).getByRole('button', { name: 'Cancel' }))
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())

    offline = false
    fireEvent.click(screen.getByRole('button', { name: 'Take payment' }))
    dialog = await screen.findByRole('dialog', { name: 'Record payment' })
    fireEvent.change(within(dialog).getByLabelText('Amount'), { target: { value: '0.10' } })
    fireEvent.click(within(dialog).getByRole('button', { name: 'Record payment' }))
    expect(await within(dialog).findByText('RCT-2026-27/000044')).toBeInTheDocument()
    expect(keys[keys.length - 1]).toBe(firstKey)
  })

  it('is not offered on a paid invoice', async () => {
    editorRoutes(invoice({ status: 'PAID' }))
    await openEditor()
    expect(screen.queryByRole('button', { name: 'Take payment' })).not.toBeInTheDocument()
  })

  it('is not offered without payment:create', async () => {
    editorRoutes(ISSUED, ['invoice:read'])
    await openEditor()
    expect(screen.queryByRole('button', { name: 'Take payment' })).not.toBeInTheDocument()
  })
})

describe('outstanding list', () => {
  it('lists issued and partly paid invoices with server totals and links', async () => {
    const { calls } = mockApi({
      'GET /api/v1/auth/me': json(200, session(['invoice:read'])),
      'GET /api/v1/invoices?status=ISSUED%2CPARTIALLY_PAID&page=0&size=50': json(200, {
        items: [
          invoice({ id: 'inv-7', status: 'PARTIALLY_PAID', invoiceNo: '2026-27/000007', total: '0.30', items: [] }),
          invoice({ id: 'inv-8', status: 'ISSUED', invoiceNo: '2026-27/000008', total: '999999999999.99', items: [] }),
        ], page: 0, size: 50, totalItems: 2,
      }),
    })
    renderApp('/invoices')
    expect(await screen.findByText('2026-27/000007')).toBeInTheDocument()
    expect(screen.getByText('999999999999.99')).toBeInTheDocument()
    expect(screen.getByText('Partly paid')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Open 2026-27/000008' })).toHaveAttribute('href', '/invoices/inv-8')
    expect(calls.some((c) => c.key.includes('status=ISSUED%2CPARTIALLY_PAID'))).toBe(true)
  })

  it('needs invoice:read', async () => {
    mockApi({ 'GET /api/v1/auth/me': json(200, session(['patient:search'])) })
    renderApp('/invoices')
    expect(await screen.findByText(/not available to you/)).toBeInTheDocument()
  })
})

describe('invoice from the consultation screen', () => {
  const ENC: Encounter = {
    id: 'e-1', patient: { id: 'p-1', uhid: 'DEMO-000001' }, practitionerId: 'd-1', appointmentId: 'a-1', status: 'FINALIZED',
    chiefComplaint: null, version: 3, startedAt: '2026-11-20T04:00:00Z', finalizedAt: '2026-11-20T04:30:00Z',
    observations: [], warnings: [], notes: [], diagnoses: [], treatmentPlan: null, followUp: null, prescriptions: [],
  }
  const base = (extra: Parameters<typeof mockApi>[0], perms = ['encounter:read', 'invoice:read', 'invoice:create', 'invoice:update', 'catalog:read']) => mockApi({
    'GET /api/v1/auth/me': json(200, session(perms)),
    'GET /api/v1/encounters/e-1': json(200, ENC),
    'GET /api/v1/patients/p-1/encounters?page=0&size=20': json(200, { items: [], page: 0, size: 20, totalItems: 0 }),
    ...extra,
  })

  it('creates the draft for this encounter and opens the editor', async () => {
    const empty = invoice({ items: [], total: '0.00', subtotal: '0.00' })
    const { calls } = base({
      'GET /api/v1/invoices?patientId=p-1&size=100': json(200, { items: [], page: 0, size: 100, totalItems: 0 }),
      'POST /api/v1/invoices': json(201, empty),
      'GET /api/v1/invoices/inv-1': json(200, empty),
      'GET /api/v1/services?active=true&size=100': json(200, SERVICES),
    })
    renderApp('/consult/e-1')
    fireEvent.click(await screen.findByRole('button', { name: 'Create invoice' }))
    await screen.findByRole('region', { name: 'Invoice totals' })
    expect(bodyOf(calls.find((c) => c.key === 'POST /api/v1/invoices')?.init)).toEqual({ type: 'OPD', patientId: 'p-1', encounterId: 'e-1' })
  })

  it('links the existing live invoice of this encounter instead of creating another', async () => {
    base({
      'GET /api/v1/invoices?patientId=p-1&size=100': json(200, {
        items: [
          invoice({ id: 'inv-old', encounterId: 'e-1', status: 'DISCARDED', items: [] }),
          invoice({ id: 'inv-live', encounterId: 'e-1', status: 'ISSUED', invoiceNo: '2026-27/000031', items: [] }),
          invoice({ id: 'inv-other', encounterId: 'e-9', status: 'DRAFT', items: [] }),
        ], page: 0, size: 100, totalItems: 3,
      }),
    })
    renderApp('/consult/e-1')
    const link = await screen.findByRole('link', { name: 'Open invoice 2026-27/000031' })
    expect(link).toHaveAttribute('href', '/invoices/inv-live')
    expect(screen.queryByRole('button', { name: 'Create invoice' })).not.toBeInTheDocument()
  })

  it('explains a 409 from create', async () => {
    base({
      'GET /api/v1/invoices?patientId=p-1&size=100': json(200, { items: [], page: 0, size: 100, totalItems: 0 }),
      'POST /api/v1/invoices': problem(409, 'INVALID_STATE_TRANSITION', 'Encounter already has an invoice.'),
    })
    renderApp('/consult/e-1')
    fireEvent.click(await screen.findByRole('button', { name: 'Create invoice' }))
    expect(await screen.findByText(/An invoice already exists for this visit/)).toBeInTheDocument()
  })

  it('shows no billing section without invoice permissions', async () => {
    base({}, ['encounter:read'])
    renderApp('/consult/e-1')
    await screen.findByRole('region', { name: 'Encounter summary' })
    expect(screen.queryByRole('heading', { name: 'Billing' })).not.toBeInTheDocument()
  })
})
