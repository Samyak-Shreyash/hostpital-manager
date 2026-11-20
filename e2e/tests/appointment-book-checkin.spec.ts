import { expect, test } from '@playwright/test'

// Synthetic data only; the API is mocked.
const DATE = '2026-11-20'
const session = {
  user: { id: 'user-1', fullName: 'Test Clerk', roles: ['RECEPTION'], mustChangePassword: false },
  permissions: ['appointment:read', 'appointment:create', 'appointment:check-in', 'appointment:cancel', 'appointment:update', 'practitioner:read', 'patient:search', 'patient:read'],
  organization: { id: 'org-1', name: 'Demo Clinic', timezone: 'Asia/Kolkata' },
}
const doctor = { id: 'd-1', userId: 'u-9', fullName: 'Dr. Test Example', registrationNo: 'DEMO-0001', status: 'ACTIVE', version: 1 }
const patient = { id: 'p-1', uhid: 'DEMO-000001', fullName: 'Asha Testpatient', sex: 'F', dateOfBirth: '1990-05-10', phone: '+919800000001', phoneMasked: '+91******0001', abhaLast4: null, status: 'ACTIVE', version: 0 }
const appointment = {
  id: 'a-1', patientId: 'p-1', doctorId: 'd-1', startAt: '2026-11-20T04:00:00Z', endAt: '2026-11-20T04:15:00Z', durationMinutes: 15,
  type: 'SCHEDULED', status: 'SCHEDULED', reason: 'Fever', outsideAvailability: false, version: 0, rescheduledFromId: null,
  cancelReason: null, cancelledAt: null, tokenNo: null, tokenDate: null,
  patient: { id: 'p-1', uhid: 'DEMO-000001', name: 'Asha Testpatient' },
}

test('book a slot, then check the patient in and see the token (API mocked)', async ({ page }) => {
  let booked = false
  let checkedIn = false
  await page.route('**/api/v1/**', async (route) => {
    const request = route.request()
    const url = new URL(request.url())
    const respond = (status: number, body?: unknown) =>
      route.fulfill({ status, headers: { 'set-cookie': 'XSRF-TOKEN=e2e-token; Path=/' }, contentType: 'application/json', body: body && JSON.stringify(body) })
    const path = url.pathname
    const method = request.method()
    if (path === '/api/v1/auth/me') return respond(200, session)
    if (path === '/api/v1/doctors') return respond(200, { items: [doctor], page: 0, size: 100, totalItems: 1 })
    if (path === '/api/v1/doctors/d-1/slots') {
      const slots = booked ? [] : [{ startAt: appointment.startAt, endAt: appointment.endAt }]
      return respond(200, { practitionerId: 'd-1', timezone: 'Asia/Kolkata', from: DATE, to: DATE, slots })
    }
    if (path === '/api/v1/patients' && method === 'GET') return respond(200, { items: [patient], page: 0, size: 10, totalItems: 1 })
    if (path === '/api/v1/appointments' && method === 'POST') { booked = true; return respond(201, appointment) }
    if (path === '/api/v1/appointments' && method === 'GET') {
      const status = checkedIn ? 'CHECKED_IN' : 'SCHEDULED'
      return respond(200, { items: booked ? [{ ...appointment, status, tokenNo: checkedIn ? 7 : null, version: checkedIn ? 1 : 0 }] : [], page: 0, size: 100, totalItems: booked ? 1 : 0 })
    }
    if (path === '/api/v1/appointments/a-1/check-in') {
      checkedIn = true
      return respond(200, { ...appointment, status: 'CHECKED_IN', tokenNo: 7, tokenDate: DATE, version: 1 })
    }
    return respond(404, { code: 'NOT_FOUND' })
  })

  await page.goto('/appointments')
  await page.getByLabel('Date', { exact: true }).fill(DATE)
  await page.getByLabel('Doctor', { exact: true }).selectOption({ label: 'Dr. Test Example' })
  await page.getByRole('button', { name: 'Book 09:30' }).click()

  const dialog = page.getByRole('dialog')
  await dialog.getByLabel('Find patient').fill('Asha')
  await dialog.getByRole('button', { name: 'Search', exact: true }).click()
  await dialog.getByRole('button', { name: 'Asha Testpatient (DEMO-000001)' }).click()
  await dialog.getByLabel('Reason').fill('Fever')
  await dialog.getByRole('button', { name: 'Confirm booking' }).click()
  await expect(dialog).toBeHidden()

  const row = page.getByRole('listitem').filter({ hasText: 'Asha Testpatient' })
  await expect(row).toContainText('Scheduled')
  await row.getByRole('button', { name: 'Check in' }).click()
  await expect(page.getByRole('status', { name: 'Check-in result' })).toContainText('Token 7')
})
