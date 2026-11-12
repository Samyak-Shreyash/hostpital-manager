import { screen } from '@testing-library/react'
import { json, mockApi, renderApp, session } from '../../test/helpers'

afterEach(() => vi.unstubAllGlobals())

describe('route guards', () => {
  it('sends an unauthenticated visitor to the login page', async () => {
    mockApi({ 'GET /api/v1/auth/me': json(401, { code: 'UNAUTHENTICATED' }) })
    renderApp('/users')
    expect(await screen.findByRole('heading', { name: 'Sign in' })).toBeInTheDocument()
  })

  it('sends a user who must change their password to the change-password screen', async () => {
    mockApi({ 'GET /api/v1/auth/me': json(200, session(['user:read'], true)) })
    renderApp('/users')
    expect(await screen.findByRole('heading', { name: 'Change your password' })).toBeInTheDocument()
  })

  it('shows the forbidden page when the permission is missing', async () => {
    mockApi({ 'GET /api/v1/auth/me': json(200, session(['patient:read'])) })
    renderApp('/users')
    expect(await screen.findByRole('heading', { name: 'Access denied' })).toBeInTheDocument()
  })

  it('guards by permission, not role: an ADMIN role without the permission is denied', async () => {
    mockApi({ 'GET /api/v1/auth/me': json(200, session([])) })
    renderApp('/roles')
    expect(await screen.findByRole('heading', { name: 'Access denied' })).toBeInTheDocument()
  })

  it('requires user:create for the create screen even with user:read', async () => {
    mockApi({ 'GET /api/v1/auth/me': json(200, session(['user:read'])) })
    renderApp('/users/new')
    expect(await screen.findByRole('heading', { name: 'Access denied' })).toBeInTheDocument()
  })

  it('renders the page when the permission is held and hides nav links it does not', async () => {
    mockApi({
      'GET /api/v1/auth/me': json(200, session(['user:read'])),
      'GET /api/v1/users?page=0&size=20': json(200, { items: [], page: 0, size: 20, totalItems: 0 }),
    })
    renderApp('/users')
    expect(await screen.findByRole('heading', { name: 'Users' })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Users' })).toBeInTheDocument()
    expect(screen.queryByRole('link', { name: 'Roles' })).not.toBeInTheDocument()
  })
})
