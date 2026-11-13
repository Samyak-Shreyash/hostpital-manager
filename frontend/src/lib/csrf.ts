export const CSRF_COOKIE = 'XSRF-TOKEN'
export const CSRF_HEADER = 'X-XSRF-TOKEN'

/** Reads one cookie value from a `document.cookie`-style string; undefined when absent. */
export function readCookie(name: string, source: string = document.cookie): string | undefined {
  for (const part of source.split(';')) {
    const index = part.indexOf('=')
    if (index < 0) continue
    if (part.slice(0, index).trim() === name) {
      try {
        return decodeURIComponent(part.slice(index + 1).trim())
      } catch {
        return part.slice(index + 1).trim()
      }
    }
  }
  return undefined
}

/** Headers carrying the CSRF token for a state-changing request; empty when the cookie is not there yet. */
export function csrfHeaders(source?: string): Record<string, string> {
  const token = readCookie(CSRF_COOKIE, source)
  return token ? { [CSRF_HEADER]: token } : {}
}
