import { fireEvent, screen, waitFor } from '@testing-library/react'
import { json, mockApi, renderApp, session } from '../../test/helpers'

afterEach(() => {
  document.cookie = 'XSRF-TOKEN=; expires=Thu, 01 Jan 1970 00:00:00 GMT; path=/'
  vi.unstubAllGlobals()
})

function fill(email: string, password: string) {
  fireEvent.change(screen.getByLabelText('Email'), { target: { value: email } })
  fireEvent.change(screen.getByLabelText('Password'), { target: { value: password } })
  fireEvent.click(screen.getByRole('button', { name: 'Sign in' }))
}

describe('login page', () => {
  beforeEach(() => {
    document.cookie = 'XSRF-TOKEN=t; path=/'
  })

  it('validates required fields and email format without calling the login API', async () => {
    const { calls } = mockApi({ 'GET /api/v1/auth/me': json(401, { code: 'UNAUTHENTICATED' }) })
    renderApp('/login')
    await screen.findByRole('heading', { name: 'Sign in' })
    fireEvent.click(screen.getByRole('button', { name: 'Sign in' }))
    expect(await screen.findByText('Email is required')).toBeInTheDocument()
    expect(screen.getByText('Password is required')).toBeInTheDocument()

    fill('not-an-email', 'secret')
    expect(await screen.findByText('Enter a valid email address')).toBeInTheDocument()
    expect(calls.some((c) => c.key.startsWith('POST'))).toBe(false)
  })

  it('shows the message for invalid credentials', async () => {
    mockApi({
      'GET /api/v1/auth/me': json(401, { code: 'UNAUTHENTICATED' }),
      'POST /api/v1/auth/login': json(401, { code: 'INVALID_CREDENTIALS', detail: 'x' }),
    })
    renderApp('/login')
    await screen.findByRole('heading', { name: 'Sign in' })
    fill('admin@example.test', 'wrong')
    expect(await screen.findByText('Email or password is incorrect.')).toBeInTheDocument()
  })

  it('signs in and lands on the home page', async () => {
    let signedIn = false
    mockApi({
      'GET /api/v1/auth/me': () => (signedIn ? json(200, session(['user:read'])) : json(401, { code: 'UNAUTHENTICATED' })),
      'POST /api/v1/auth/login': () => {
        signedIn = true
        return json(200, session(['user:read']))
      },
    })
    renderApp('/login')
    await screen.findByRole('heading', { name: 'Sign in' })
    fill('admin@example.test', 'secret')
    expect(await screen.findByRole('heading', { name: 'Welcome, Test Admin' })).toBeInTheDocument()
  })

  it('does not echo the password after a failed attempt', async () => {
    mockApi({
      'GET /api/v1/auth/me': json(401, { code: 'UNAUTHENTICATED' }),
      'POST /api/v1/auth/login': json(401, { code: 'INVALID_CREDENTIALS' }),
    })
    const { container } = renderApp('/login')
    await screen.findByRole('heading', { name: 'Sign in' })
    fill('admin@example.test', 'Probe-value-1')
    await waitFor(() => expect(screen.getByRole('alert')).toBeInTheDocument())
    expect(container.textContent).not.toContain('Probe-value-1')
  })
})
