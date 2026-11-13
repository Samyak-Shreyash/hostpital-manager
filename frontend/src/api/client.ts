import { csrfHeaders, readCookie, CSRF_COOKIE } from '../lib/csrf'

const BASE = '/api/v1'

export interface FieldError {
  field: string
  code: string
  message: string
}

/** RFC 7807 problem returned by the backend (api-design.md section 1). */
export class ApiError extends Error {
  readonly status: number
  readonly code: string
  readonly fieldErrors: FieldError[]
  /** Extra problem properties (e.g. `candidates` on DUPLICATE_SUSPECTED). */
  readonly properties: Record<string, unknown>

  constructor(status: number, code: string, message: string, fieldErrors: FieldError[] = [], properties: Record<string, unknown> = {}) {
    super(message)
    this.name = 'ApiError'
    this.status = status
    this.code = code
    this.fieldErrors = fieldErrors
    this.properties = properties
  }
}

async function toApiError(response: Response): Promise<ApiError> {
  let body: { code?: string; detail?: string; title?: string; errors?: FieldError[]; [key: string]: unknown } = {}
  try {
    body = await response.json()
  } catch {
    // non-JSON error body (e.g. proxy page): fall through to the generic error
  }
  return new ApiError(
    response.status,
    body.code ?? `HTTP_${response.status}`,
    body.detail ?? body.title ?? `Request failed (${response.status})`,
    body.errors ?? [],
    body,
  )
}

/**
 * The XSRF-TOKEN cookie is issued with any earlier response; when it is missing before a state-changing call
 * (first visit, login), one harmless GET gets it, even if that GET answers 401.
 */
async function ensureCsrfCookie(): Promise<void> {
  if (readCookie(CSRF_COOKIE)) return
  try {
    await fetch(`${BASE}/auth/me`, { credentials: 'same-origin', headers: { Accept: 'application/json' } })
  } catch {
    // the real request will report the network problem
  }
}

export async function request<T>(method: string, path: string, body?: unknown): Promise<T> {
  const headers: Record<string, string> = { Accept: 'application/json' }
  if (body !== undefined) headers['Content-Type'] = 'application/json'
  if (method !== 'GET') {
    await ensureCsrfCookie()
    Object.assign(headers, csrfHeaders())
  }
  const response = await fetch(`${BASE}${path}`, {
    method,
    headers,
    credentials: 'same-origin',
    body: body === undefined ? undefined : JSON.stringify(body),
  })
  if (!response.ok) throw await toApiError(response)
  if (response.status === 204) return undefined as T
  return (await response.json()) as T
}

export const api = {
  get: <T>(path: string) => request<T>('GET', path),
  post: <T>(path: string, body?: unknown) => request<T>('POST', path, body),
  patch: <T>(path: string, body: unknown) => request<T>('PATCH', path, body),
  put: <T>(path: string, body: unknown) => request<T>('PUT', path, body),
}
