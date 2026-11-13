import { expect, test } from '@playwright/test'

// Synthetic data only; the API is mocked.
const session = {
  user: { id: 'user-1', fullName: 'Test Clerk', roles: ['RECEPTION'], mustChangePassword: false },
  permissions: ['patient:search', 'patient:read', 'patient:create'],
  organization: { id: 'org-1', name: 'Demo Clinic', timezone: 'Asia/Kolkata' },
}
const patient = {
  id: 'p-1', uhid: 'DEMO-000001', fullName: 'Asha Testpatient', sex: 'F', dateOfBirth: '1990-05-10', dobEstimated: false,
  phone: '+919800000001', abhaLast4: null, address: null, emergencyContact: null, status: 'ACTIVE', version: 0,
  createdAt: '2026-01-01T00:00:00Z', updatedAt: '2026-01-01T00:00:00Z',
}

test('register a patient, then find them by name (API mocked)', async ({ page }) => {
  let registered = false
  await page.route('**/api/v1/**', async (route) => {
    const request = route.request()
    const url = new URL(request.url())
    const respond = (status: number, body?: unknown) =>
      route.fulfill({ status, headers: { 'set-cookie': 'XSRF-TOKEN=e2e-token; Path=/' }, contentType: 'application/json', body: body && JSON.stringify(body) })
    if (url.pathname === '/api/v1/auth/me') return respond(200, session)
    if (url.pathname === '/api/v1/patients' && request.method() === 'POST') {
      registered = true
      return respond(201, patient)
    }
    if (url.pathname === '/api/v1/patients' && request.method() === 'GET') {
      const { id, uhid, fullName, sex, dateOfBirth, abhaLast4, status } = patient
      const items = registered && url.searchParams.get('q') === 'Asha'
        ? [{ id, uhid, fullName, sex, dateOfBirth, abhaLast4, status, phoneMasked: '+91******0001' }] : []
      return respond(200, { items, page: 0, size: 20, totalItems: items.length })
    }
    if (url.pathname === '/api/v1/patients/p-1') return respond(200, patient)
    if (url.pathname.startsWith('/api/v1/patients/p-1/')) return respond(200, [])
    return respond(404, { code: 'NOT_FOUND' })
  })

  await page.goto('/patients/new')
  await page.getByLabel('Full name', { exact: true }).fill('Asha Testpatient')
  await page.getByLabel('Sex', { exact: true }).selectOption('F')
  await page.getByLabel('Date of birth', { exact: true }).fill('1990-05-10')
  await page.getByLabel('Phone', { exact: true }).fill('9800000001')
  await page.getByRole('button', { name: 'Register' }).click()
  await expect(page.getByRole('region', { name: 'Patient banner' })).toContainText('DEMO-000001')

  await page.getByRole('link', { name: 'Back to patients' }).click()
  await page.getByLabel('Name', { exact: true }).fill('Asha')
  await page.getByRole('button', { name: 'Search', exact: true }).click()
  await expect(page.getByText('+91******0001')).toBeVisible()
  await expect(page.getByRole('link', { name: 'Asha Testpatient' })).toBeVisible()
})
