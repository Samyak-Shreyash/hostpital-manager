import { expect, test } from '@playwright/test'

// Synthetic data only; the API is mocked. Money strings are shown exactly as the "server" sends them.
const session = {
  user: { id: 'user-1', fullName: 'Test Cashier', roles: ['CASHIER'], mustChangePassword: false },
  permissions: [
    'encounter:read', 'invoice:read', 'invoice:create', 'invoice:update', 'invoice:issue', 'payment:create', 'payment:read', 'catalog:read',
  ],
  organization: { id: 'org-1', name: 'Demo Clinic', timezone: 'Asia/Kolkata' },
}
const encounter = {
  id: 'e-1', patient: { id: 'p-1', uhid: 'DEMO-000001' }, practitionerId: 'd-1', appointmentId: 'a-1', status: 'FINALIZED',
  chiefComplaint: null, version: 3, startedAt: '2026-11-20T04:00:00Z', finalizedAt: '2026-11-20T04:30:00Z',
  observations: [], warnings: [], notes: [], diagnoses: [], treatmentPlan: null, followUp: null, prescriptions: [],
}
const services = {
  items: [{ id: 's-1', code: 'CONS', name: 'Consultation', active: true, currentPrice: '0.10', version: 0 }],
  page: 0, size: 100, totalItems: 1,
}
const empty = (over: Record<string, unknown> = {}) => ({
  id: 'inv-1', type: 'OPD', patientId: 'p-1', encounterId: 'e-1', admissionId: null, status: 'DRAFT', invoiceNo: null, issuedAt: null,
  subtotal: '0.00', discountTotal: '0.00', taxTotal: '0.00', total: '0.00', version: 0, items: [],
  createdAt: '2026-11-20T05:00:00Z', updatedAt: '2026-11-20T05:00:00Z', ...over,
})
const item = {
  id: 'it-1', serviceId: 's-1', description: 'Consultation', quantity: '1.00', unitPrice: '0.10', discount: '0.00', discountReason: null,
  taxRate: '0.00', gross: '0.10', taxable: '0.10', tax: '0.00', total: '0.10',
}

test('consult, create the invoice, add a service, issue, take a payment, open the receipt (API mocked)', async ({ page }) => {
  let invoice: Record<string, unknown> | null = null
  const keys: Record<string, string> = {}
  await page.route('**/api/v1/**', async (route) => {
    const request = route.request()
    const url = new URL(request.url())
    const path = url.pathname
    const method = request.method()
    const respond = (status: number, body?: unknown) =>
      route.fulfill({ status, headers: { 'set-cookie': 'XSRF-TOKEN=e2e-token; Path=/' }, contentType: 'application/json', body: body && JSON.stringify(body) })
    if (path === '/api/v1/auth/me') return respond(200, session)
    if (path === '/api/v1/encounters/e-1') return respond(200, encounter)
    if (path === '/api/v1/patients/p-1/encounters') return respond(200, { items: [], page: 0, size: 20, totalItems: 0 })
    if (path === '/api/v1/services') return respond(200, services)
    if (path === '/api/v1/invoices' && method === 'GET') return respond(200, { items: invoice ? [{ ...invoice, items: [] }] : [], page: 0, size: 100, totalItems: invoice ? 1 : 0 })
    if (path === '/api/v1/invoices' && method === 'POST') { invoice = empty(); return respond(201, invoice) }
    if (path === '/api/v1/invoices/inv-1' && method === 'GET') return respond(200, invoice)
    if (path === '/api/v1/invoices/inv-1/items') {
      invoice = { ...empty({ version: 1 }), subtotal: '0.10', total: '0.10', items: [item] }
      return respond(200, invoice)
    }
    if (path === '/api/v1/invoices/inv-1/issue') {
      keys.issue = request.headers()['idempotency-key']
      invoice = { ...(invoice as object), status: 'ISSUED', invoiceNo: '2026-27/000031', issuedAt: '2026-11-20T05:10:00Z', version: 2 }
      return respond(200, invoice)
    }
    if (path === '/api/v1/invoices/inv-1/payments') {
      keys.payment = request.headers()['idempotency-key']
      invoice = { ...(invoice as object), status: 'PAID', version: 3 }
      return respond(201, {
        id: 'pay-1', invoiceId: 'inv-1', amount: '0.10', mode: 'CASH', referenceNo: null, receiptNo: 'RCT-2026-27/000044',
        receivedAt: '2026-11-20T05:20:00Z', receivedBy: 'user-1', outstanding: '0.00', invoiceStatus: 'PAID',
      })
    }
    return respond(404, { code: 'NOT_FOUND' })
  })

  await page.goto('/consult/e-1')
  await page.getByRole('button', { name: 'Create invoice' }).click()
  await expect(page.getByRole('heading', { name: 'Draft invoice' })).toBeVisible()

  await page.getByLabel('Service', { exact: true }).selectOption({ label: 'Consultation (0.10)' })
  await page.getByRole('button', { name: 'Add service' }).click()
  await page.getByRole('button', { name: 'Save items' }).click()
  await expect(page.getByTestId('total')).toHaveText('0.10')

  await page.getByRole('button', { name: 'Issue invoice' }).click()
  await expect(page.getByRole('heading', { name: 'Invoice 2026-27/000031' })).toBeVisible()
  await expect(page.getByRole('link', { name: 'Open invoice PDF' })).toBeVisible()
  expect(keys.issue).toBeTruthy()

  await page.getByRole('button', { name: 'Take payment' }).click()
  const dialog = page.getByRole('dialog', { name: 'Record payment' })
  await dialog.getByLabel('Amount').fill('0.10')
  await dialog.getByRole('button', { name: 'Record payment' }).click()
  await expect(dialog.getByText('RCT-2026-27/000044')).toBeVisible()
  await expect(dialog.getByTestId('outstanding')).toHaveText('0.00')
  await expect(dialog.getByRole('link', { name: 'Open receipt PDF' })).toHaveAttribute('href', '/api/v1/payments/pay-1/receipt')
  expect(keys.payment).toBeTruthy()
  expect(keys.payment).not.toBe(keys.issue)
})
