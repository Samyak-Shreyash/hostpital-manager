import { expect, test } from '@playwright/test'

const session = {
  user: { id: 'user-1', fullName: 'Test Admin', roles: ['ADMIN'], mustChangePassword: false },
  permissions: ['user:read', 'role:read'],
  organization: { id: 'org-1', name: 'Demo Clinic', timezone: 'Asia/Kolkata' },
}

test('login, landing page and logout (API mocked)', async ({ page }) => {
  let signedIn = false
  let loginCsrfHeader: string | undefined

  await page.route('**/api/v1/**', async (route) => {
    const request = route.request()
    const path = new URL(request.url()).pathname
    const csrfCookie = { 'set-cookie': 'XSRF-TOKEN=e2e-token; Path=/' }
    const respond = (status: number, body?: unknown) =>
      route.fulfill({ status, headers: csrfCookie, contentType: 'application/json', body: body && JSON.stringify(body) })

    if (path === '/api/v1/auth/me') {
      return signedIn ? respond(200, session) : respond(401, { code: 'UNAUTHENTICATED' })
    }
    if (path === '/api/v1/auth/login' && request.method() === 'POST') {
      loginCsrfHeader = request.headers()['x-xsrf-token']
      signedIn = true
      return respond(200, session)
    }
    if (path === '/api/v1/auth/logout' && request.method() === 'POST') {
      signedIn = false
      return respond(204)
    }
    return respond(404, { code: 'NOT_FOUND' })
  })

  await page.goto('/')
  await expect(page).toHaveURL(/\/login$/)

  await page.getByLabel('Email').fill('admin@example.test')
  await page.getByLabel('Password').fill('not-a-real-password')
  await page.getByRole('button', { name: 'Sign in' }).click()

  await expect(page.getByRole('heading', { name: 'Welcome, Test Admin' })).toBeVisible()
  await expect(page.getByRole('link', { name: 'Users' })).toBeVisible()
  expect(loginCsrfHeader).toBe('e2e-token')

  await page.getByRole('button', { name: 'Sign out' }).click()
  await expect(page).toHaveURL(/\/login$/)
  await page.goto('/users')
  await expect(page).toHaveURL(/\/login$/)
})
