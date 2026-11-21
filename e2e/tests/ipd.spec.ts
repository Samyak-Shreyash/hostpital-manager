import { expect, test } from '@playwright/test'

// Synthetic data only; the API is mocked with a small stateful fake of the IPD endpoints.
const session = {
  user: { id: 'user-1', fullName: 'Test Admitter', roles: ['ADMITTER'], mustChangePassword: false },
  permissions: [
    'patient:search', 'practitioner:read', 'bed:read', 'bed:assign', 'ward:read', 'admission:create', 'admission:read', 'admission:update',
    'discharge:write', 'discharge:complete', 'invoice:read', 'invoice:create', 'invoice:update', 'catalog:read',
  ],
  organization: { id: 'org-1', name: 'Demo Clinic', timezone: 'Asia/Kolkata' },
}
const doctor = {
  id: 'd-1', userId: 'u-9', fullName: 'Dr. Test Example', registrationNo: 'DEMO-0001', registrationCouncil: 'DEMO', qualifications: [],
  defaultSlotMinutes: 15, status: 'ACTIVE', primarySpecialtyId: 'sp-1', specialtyIds: ['sp-1'], departmentIds: [], version: 1, createdAt: '', updatedAt: '',
}
const patient = { id: 'p-1', uhid: 'DEMO-000001', fullName: 'Asha Testpatient', sex: 'F', dateOfBirth: '1990-05-10', phoneMasked: '+91******0001', abhaLast4: null, status: 'ACTIVE' }
const ward = { id: 'w-1', name: 'General Ward', careType: 'GENERAL' }
const bed = (id: string, bedNo: string, occupied: boolean) => ({ id, bedNo, bedClass: null, ward, status: 'ACTIVE', occupied, admissionId: occupied ? 'adm-1' : null })

test('admit, transfer, save the summary, create the final invoice, discharge (API mocked)', async ({ page }) => {
  let admission: Record<string, unknown> | null = null
  let currentBed = 'b-1'
  let invoice: Record<string, unknown> | null = null
  let summary: Record<string, unknown> | null = null
  const bodies: Record<string, unknown> = {}
  const bedNo = () => (currentBed === 'b-1' ? 'G-1' : 'G-2')
  const assignments = () => [
    { id: 'as-1', bedId: 'b-1', bedNo: 'G-1', wardName: 'General Ward', dailyRateServiceId: 's-1', assignedAt: '2026-11-20T04:00:00Z', releasedAt: currentBed === 'b-1' ? null : '2026-11-20T06:00:00Z', reason: null, criticalCare: false },
    ...(currentBed === 'b-2' ? [{ id: 'as-2', bedId: 'b-2', bedNo: 'G-2', wardName: 'General Ward', dailyRateServiceId: 's-1', assignedAt: '2026-11-20T06:00:00Z', releasedAt: null, reason: 'Higher acuity', criticalCare: false }] : []),
  ]
  const makeAdmission = (over: Record<string, unknown> = {}) => ({
    id: 'adm-1', admissionNo: 'ADM-2026-0001', patientId: 'p-1', patient: { id: 'p-1', uhid: 'DEMO-000001', name: 'Asha Testpatient' },
    admittingDoctorId: 'd-1', sourceEncounterId: null, admissionDiagnosis: 'Acute gastroenteritis', status: 'ADMITTED',
    admittedAt: '2026-11-20T04:00:00Z', plannedDischargeAt: null, dischargedAt: null,
    currentBed: { id: currentBed, bedNo: bedNo(), ward: 'General Ward' }, version: 0, ...over,
  })

  await page.route('**/api/v1/**', async (route) => {
    const request = route.request()
    const url = new URL(request.url())
    const path = url.pathname
    const method = request.method()
    const body = () => request.postDataJSON()
    const respond = (status: number, payload?: unknown) =>
      route.fulfill({ status, headers: { 'set-cookie': 'XSRF-TOKEN=e2e-token; Path=/' }, contentType: 'application/json', body: payload === undefined ? undefined : JSON.stringify(payload) })

    if (path === '/api/v1/auth/me') return respond(200, session)
    if (path === '/api/v1/doctors') return respond(200, { items: [doctor], page: 0, size: 100, totalItems: 1 })
    if (path === '/api/v1/patients') return respond(200, { items: [patient], page: 0, size: 10, totalItems: 1 })
    if (path === '/api/v1/beds/availability') {
      return respond(200, { wards: [{ ward, code: 'GEN', status: 'ACTIVE', total: 2, occupied: admission ? 1 : 0, available: admission ? 1 : 2, outOfService: 0, beds: [bed('b-1', 'G-1', !!admission && currentBed === 'b-1'), bed('b-2', 'G-2', !!admission && currentBed === 'b-2')] }] })
    }
    if (path === '/api/v1/admissions' && method === 'POST') { bodies.admit = body(); admission = makeAdmission(); return respond(201, admission) }
    if (path === '/api/v1/admissions/adm-1' && method === 'GET') return respond(200, admission)
    if (path === '/api/v1/admissions/adm-1/bed-assignments' && method === 'GET') return respond(200, assignments())
    if (path === '/api/v1/admissions/adm-1/bed-assignments/transfer') {
      bodies.transfer = body()
      currentBed = 'b-2'
      admission = makeAdmission({ version: 1 })
      return respond(200, { currentAssignment: assignments()[1], previousAssignment: assignments()[0] })
    }
    if (path === '/api/v1/admissions/adm-1/discharge-summary' && method === 'GET') return summary ? respond(200, summary) : respond(404, { code: 'NOT_FOUND' })
    if (path === '/api/v1/admissions/adm-1/discharge-summary' && method === 'PUT') {
      bodies.summary = body()
      const b = body()
      summary = { id: 'sm-1', admissionId: 'adm-1', courseInHospital: null, procedures: null, followUp: null, medications: [], ...b, version: 0 }
      admission = makeAdmission({ status: 'DISCHARGE_INITIATED', plannedDischargeAt: b.plannedDischargeAt, version: 2 })
      return respond(200, summary)
    }
    if (path === '/api/v1/admissions/adm-1/charges-preview') {
      return respond(200, {
        asOf: '2026-11-22T04:30:00Z', roomTotal: '12600.00',
        lines: [{ description: 'Room charges — General Ward G-2 (2026-11-20–2026-11-21)', quantity: '2.00', unitPrice: '6000.00', amount: '12000.00', taxRate: '5.00', tax: '600.00', from: '2026-11-20', to: '2026-11-21' }],
      })
    }
    if (path === '/api/v1/services') return respond(200, { items: [], page: 0, size: 100, totalItems: 0 })
    if (path === '/api/v1/invoices' && method === 'GET') return respond(200, { items: invoice ? [{ ...invoice, items: [] }] : [], page: 0, size: 100, totalItems: invoice ? 1 : 0 })
    if (path === '/api/v1/invoices' && method === 'POST') {
      bodies.invoice = body()
      invoice = {
        id: 'inv-1', type: 'IPD', patientId: 'p-1', encounterId: null, admissionId: 'adm-1', kind: 'FINAL', status: 'DRAFT', invoiceNo: null, issuedAt: null,
        subtotal: '12000.00', discountTotal: '0.00', taxTotal: '600.00', total: '12600.00', version: 0, items: [], createdAt: '', updatedAt: '',
      }
      return respond(201, invoice)
    }
    if (path === '/api/v1/invoices/inv-1' && method === 'GET') return respond(200, invoice)
    if (path === '/api/v1/admissions/adm-1/discharge' && method === 'POST') {
      bodies.discharge = body()
      admission = makeAdmission({ status: 'DISCHARGED', dischargedAt: body().dischargedAt, plannedDischargeAt: (admission as Record<string, unknown>).plannedDischargeAt, version: 3 })
      return respond(200, { status: 'DISCHARGED', bedReleased: true })
    }
    return respond(404, { code: 'NOT_FOUND' })
  })

  // Admit
  await page.goto('/admissions/new')
  await page.getByLabel('Find patient').fill('Asha')
  await page.getByRole('button', { name: 'Search' }).click()
  await page.getByRole('button', { name: 'Asha Testpatient (DEMO-000001)' }).click()
  await page.getByLabel('Doctor', { exact: true }).selectOption({ label: 'Dr. Test Example' })
  await page.getByLabel('Bed', { exact: true }).selectOption({ label: 'General Ward — G-1' })
  await page.getByLabel('Admission diagnosis').fill('Acute gastroenteritis')
  await page.getByRole('button', { name: 'Admit', exact: true }).click()
  await expect(page.getByRole('heading', { name: 'Admission ADM-2026-0001' })).toBeVisible()
  expect(bodies.admit).toEqual({ patientId: 'p-1', admittingDoctorId: 'd-1', bedId: 'b-1', admissionDiagnosis: 'Acute gastroenteritis' })
  await expect(page.getByTestId('current-bed')).toHaveText('General Ward — G-1')

  // Transfer (G-1 is held now; G-2 is the free bed)
  await page.getByRole('button', { name: 'Transfer bed' }).click()
  const transfer = page.getByRole('dialog', { name: 'Transfer to another bed' })
  await transfer.getByLabel('Bed', { exact: true }).selectOption({ label: 'General Ward — G-2' })
  await transfer.getByLabel('Reason').fill('Higher acuity')
  await transfer.getByRole('button', { name: 'Confirm transfer' }).click()
  await expect(page.getByTestId('current-bed')).toHaveText('General Ward — G-2')
  expect(bodies.transfer).toEqual({ toBedId: 'b-2', reason: 'Higher acuity' })

  // Summary
  await page.getByLabel('Final diagnosis').fill('Recovered')
  await page.getByLabel('Condition at discharge').fill('Stable')
  await page.getByLabel('Planned discharge').fill('2026-11-22T10:00')
  await page.getByRole('button', { name: 'Save summary' }).click()
  await expect(page.getByText('Summary saved.')).toBeVisible()
  expect((bodies.summary as Record<string, unknown>).plannedDischargeAt).toBe('2026-11-22T04:30:00.000Z')
  await expect(page.getByTestId('room-total')).toHaveText('12600.00')

  // Final invoice, then back to the admission once it is issued
  await page.getByRole('button', { name: 'Create final invoice' }).click()
  await expect(page.getByRole('heading', { name: 'Draft invoice' })).toBeVisible()
  expect(bodies.invoice).toEqual({ type: 'IPD', patientId: 'p-1', admissionId: 'adm-1', kind: 'FINAL' })
  invoice = { ...(invoice as object), status: 'ISSUED', invoiceNo: '2026-27/000050', version: 1 }
  await page.goto('/admissions/adm-1')
  await expect(page.getByRole('link', { name: 'Final invoice 2026-27/000050' })).toBeVisible()

  // Discharge
  await page.getByRole('button', { name: 'Discharge patient' }).click()
  const dialog = page.getByRole('dialog', { name: 'Discharge patient' })
  await expect(dialog.getByLabel(/Defer billing/)).toHaveCount(0)
  await dialog.getByRole('button', { name: 'Confirm discharge' }).click()
  await expect(page.getByText('Discharged', { exact: true }).first()).toBeVisible()
  expect(bodies.discharge).toEqual({ version: 2, dischargedAt: '2026-11-22T04:30:00.000Z', deferBilling: false, invoiceId: 'inv-1' })
})
