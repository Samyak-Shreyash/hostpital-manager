import { csrfHeaders, readCookie } from './csrf'

describe('csrf helpers', () => {
  it('reads a named cookie from a cookie string', () => {
    expect(readCookie('XSRF-TOKEN', 'a=1; XSRF-TOKEN=tok%3D1; b=2')).toBe('tok=1')
  })

  it('returns undefined when the cookie is absent', () => {
    expect(readCookie('XSRF-TOKEN', 'a=1; b=2')).toBeUndefined()
    expect(readCookie('XSRF-TOKEN', '')).toBeUndefined()
  })

  it('does not match a cookie whose name merely ends the same way', () => {
    expect(readCookie('XSRF-TOKEN', 'OTHER-XSRF-TOKEN=nope')).toBeUndefined()
  })

  it('builds the X-XSRF-TOKEN header, or nothing without a token', () => {
    expect(csrfHeaders('XSRF-TOKEN=abc')).toEqual({ 'X-XSRF-TOKEN': 'abc' })
    expect(csrfHeaders('')).toEqual({})
  })
})
