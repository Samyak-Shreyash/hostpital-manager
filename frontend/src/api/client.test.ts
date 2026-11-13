import { json, mockApi } from '../test/helpers'
import { api, ApiError } from './client'

afterEach(() => {
  document.cookie = 'XSRF-TOKEN=; expires=Thu, 01 Jan 1970 00:00:00 GMT; path=/'
  vi.unstubAllGlobals()
})

describe('api client', () => {
  it('sends the XSRF-TOKEN cookie as X-XSRF-TOKEN on non-GET requests', async () => {
    document.cookie = 'XSRF-TOKEN=tok-1; path=/'
    const { calls } = mockApi({ 'POST /api/v1/users/1/activate': json(200, {}) })
    await api.post('/users/1/activate')
    expect((calls[0].init?.headers as Record<string, string>)['X-XSRF-TOKEN']).toBe('tok-1')
    expect(calls[0].init?.credentials).toBe('same-origin')
  })

  it('does not send the header on GET', async () => {
    document.cookie = 'XSRF-TOKEN=tok-1; path=/'
    const { calls } = mockApi({ 'GET /api/v1/roles': json(200, []) })
    await api.get('/roles')
    expect((calls[0].init?.headers as Record<string, string>)['X-XSRF-TOKEN']).toBeUndefined()
  })

  it('fetches /auth/me first when no CSRF cookie exists yet, even if it answers 401', async () => {
    const { calls } = mockApi({
      'GET /api/v1/auth/me': () => {
        document.cookie = 'XSRF-TOKEN=fresh; path=/'
        return json(401, { code: 'UNAUTHENTICATED' })
      },
      'POST /api/v1/auth/login': json(200, {}),
    })
    await api.post('/auth/login', { email: 'a@example.test', password: 'x' })
    expect(calls.map((c) => c.key)).toEqual(['GET /api/v1/auth/me', 'POST /api/v1/auth/login'])
    expect((calls[1].init?.headers as Record<string, string>)['X-XSRF-TOKEN']).toBe('fresh')
  })

  it('turns a problem response into an ApiError with code and message', async () => {
    mockApi({
      'POST /api/v1/users': json(409, { code: 'DUPLICATE_EMAIL', detail: 'Email already used', errors: [] }),
    })
    document.cookie = 'XSRF-TOKEN=t; path=/'
    const error = await api.post('/users', {}).catch((e) => e)
    expect(error).toBeInstanceOf(ApiError)
    expect(error).toMatchObject({ status: 409, code: 'DUPLICATE_EMAIL', message: 'Email already used' })
  })
})
