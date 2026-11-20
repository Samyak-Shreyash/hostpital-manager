import { expect, test } from '@playwright/test'

// Synthetic data only; the API is mocked.
const session = {
  user: { id: 'user-1', fullName: 'Dr. Test Example', roles: ['DOCTOR'], mustChangePassword: false },
  permissions: [
    'appointment:read', 'practitioner:read', 'encounter:read', 'encounter:create', 'encounter:update', 'encounter:finalize',
    'encounter:addendum', 'observation:write', 'prescription:create', 'prescription:issue', 'prescription:read',
  ],
  organization: { id: 'org-1', name: 'Demo Clinic', timezone: 'Asia/Kolkata' },
}
const doctor = { id: 'd-1', userId: 'user-1', fullName: 'Dr. Test Example', registrationNo: 'DEMO-0001', status: 'ACTIVE', version: 1 }
const entry = { appointmentId: 'a-1', patient: { id: 'p-1', uhid: 'DEMO-000001', name: 'Asha Testpatient' }, tokenNo: 3, status: 'CHECKED_IN' }
const encounter = {
  id: 'e-1', patient: { id: 'p-1', uhid: 'DEMO-000001' }, practitionerId: 'd-1', appointmentId: 'a-1', status: 'OPEN',
  chiefComplaint: null, version: 0, startedAt: '2026-11-20T04:00:00Z', finalizedAt: null,
  observations: [], warnings: [], notes: [], diagnoses: [], treatmentPlan: null, followUp: null, prescriptions: [],
}
const draft = { id: 'rx-1', encounterId: 'e-1', patientId: 'p-1', status: 'DRAFT', versionNo: 1, supersedesId: null, issuedAt: null, issuedBy: null, version: 0, items: [], allergyWarnings: [] }
const item = { drugName: 'Paracetamol', strength: null, form: null, route: null, dose: '500 mg', frequency: 'Twice daily', durationDays: 3, instructions: null }

test('queue, start consult, vitals, issue a prescription, finalize (API mocked)', async ({ page }) => {
  let current = { ...encounter } as Record<string, unknown>
  let prescriptions: { id: string; status: string; versionNo: number }[] = []
  await page.route('**/api/v1/**', async (route) => {
    const request = route.request()
    const path = new URL(request.url()).pathname
    const method = request.method()
    const respond = (status: number, body?: unknown) =>
      route.fulfill({ status, headers: { 'set-cookie': 'XSRF-TOKEN=e2e-token; Path=/' }, contentType: 'application/json', body: body && JSON.stringify(body) })
    if (path === '/api/v1/auth/me') return respond(200, session)
    if (path === '/api/v1/doctors') return respond(200, { items: [doctor], page: 0, size: 100, totalItems: 1 })
    if (path === '/api/v1/queue') return respond(200, { doctorId: 'd-1', date: '2026-11-20', items: [entry] })
    if (path === '/api/v1/encounters' && method === 'POST') return respond(201, current)
    if (path === '/api/v1/encounters/e-1' && method === 'GET') return respond(200, { ...current, prescriptions })
    if (path === '/api/v1/patients/p-1/encounters') return respond(200, { items: [], page: 0, size: 20, totalItems: 0 })
    if (path === '/api/v1/encounters/e-1/observations') {
      const body = request.postDataJSON()
      current = {
        ...current, version: 1, observations: body.observations,
        warnings: [{ kind: 'PULSE', message: 'PULSE 190 is outside the usual range 40 to 180.' }],
      }
      return respond(200, current)
    }
    if (path === '/api/v1/encounters/e-1/prescriptions') { prescriptions = [{ id: 'rx-1', status: 'DRAFT', versionNo: 1 }]; return respond(201, draft) }
    if (path === '/api/v1/prescriptions/rx-1' && method === 'PUT') return respond(200, { ...draft, version: 1, items: [item] })
    if (path === '/api/v1/prescriptions/rx-1/issue') {
      prescriptions = [{ id: 'rx-1', status: 'ISSUED', versionNo: 1 }]
      return respond(200, { ...draft, status: 'ISSUED', version: 2, issuedAt: '2026-11-20T04:30:00Z', items: [item] })
    }
    if (path === '/api/v1/encounters/e-1/finalize') {
      current = { ...current, status: 'FINALIZED', version: 2, finalizedAt: '2026-11-20T04:40:00Z' }
      return respond(200, current)
    }
    return respond(404, { code: 'NOT_FOUND' })
  })

  await page.goto('/queue')
  await page.getByLabel('Doctor', { exact: true }).selectOption({ label: 'Dr. Test Example' })
  await page.getByRole('button', { name: 'Start consult' }).click()
  await expect(page.getByRole('heading', { name: 'Consultation' })).toBeVisible()
  await expect(page.getByRole('region', { name: 'Encounter summary' })).toContainText('DEMO-000001')

  await page.getByLabel('Pulse (bpm)').fill('190')
  await page.getByRole('button', { name: 'Save vitals' }).click()
  await expect(page.getByRole('status', { name: 'Vitals warnings' })).toContainText('outside the usual range')

  await page.getByRole('button', { name: 'Start prescription' }).click()
  await page.getByRole('button', { name: 'Add medicine' }).click()
  await page.getByLabel('Drug name 1').fill('Paracetamol')
  await page.getByLabel('Dose 1').fill('500 mg')
  await page.getByLabel('Frequency 1').fill('Twice daily')
  await page.getByLabel('Duration (days) 1').fill('3')
  await page.getByRole('button', { name: 'Save medicines' }).click()
  await page.getByRole('button', { name: 'Issue prescription' }).click()
  await expect(page.getByRole('link', { name: 'Open prescription PDF' })).toBeVisible()

  await page.getByRole('button', { name: 'Finalize encounter' }).click()
  await page.getByRole('button', { name: 'Confirm finalize' }).click()
  await expect(page.getByRole('region', { name: 'Encounter summary' })).toContainText('Finalized')
  await expect(page.getByLabel('Symptoms')).toBeDisabled()
  await expect(page.getByRole('button', { name: 'Save vitals' })).toHaveCount(0)
})
