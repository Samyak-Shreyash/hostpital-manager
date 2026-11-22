import { execFileSync } from 'node:child_process'
import { randomBytes } from 'node:crypto'

/**
 * Setup helpers for the real-stack journey (tests/m3-opd-journey.spec.ts). They talk to the API of the Compose stack with
 * plain fetch and their own tiny cookie jar (the session cookie is `Secure`, which a Node cookie jar would refuse over
 * http://localhost). Everything created here is synthetic and unique per run, so reruns on the same stack do not clash.
 */

export interface RealStackEnv {
  baseURL: string
  adminEmail: string
  adminPassword: string
  /** Only used when the admin still has the temporary bootstrap password (first run on a fresh stack). */
  adminNewPassword: string | undefined
}

export function realStackEnv(): RealStackEnv {
  const need = (name: string): string => {
    const value = process.env[name]
    if (!value) throw new Error(`${name} is required for the real-stack project (see e2e/README.md)`)
    return value
  }
  return {
    baseURL: (process.env.E2E_BASE_URL ?? 'http://localhost:8080').replace(/\/$/, ''),
    adminEmail: need('E2E_ADMIN_EMAIL'),
    adminPassword: need('E2E_ADMIN_PASSWORD'),
    adminNewPassword: process.env.E2E_ADMIN_NEW_PASSWORD || undefined,
  }
}

export class ApiError extends Error {
  constructor(readonly status: number, readonly path: string, readonly body: string) {
    super(`${status} ${path}: ${body.slice(0, 300)}`)
  }
}

interface LoginResult {
  user: { id: string; fullName: string; mustChangePassword: boolean }
  permissions: string[]
}

export class Api {
  private readonly cookies = new Map<string, string>()
  constructor(private readonly baseURL: string) {}

  private async send(method: string, path: string, body?: unknown, tolerate401 = false): Promise<unknown> {
    const headers: Record<string, string> = { accept: 'application/json' }
    if (body !== undefined) headers['content-type'] = 'application/json'
    const xsrf = this.cookies.get('XSRF-TOKEN')
    if (xsrf) headers['x-xsrf-token'] = xsrf
    if (this.cookies.size > 0) headers.cookie = [...this.cookies].map(([k, v]) => `${k}=${v}`).join('; ')
    const response = await fetch(this.baseURL + path, { method, headers, body: body === undefined ? undefined : JSON.stringify(body) })
    for (const line of response.headers.getSetCookie()) {
      const [pair] = line.split(';')
      const index = pair.indexOf('=')
      this.cookies.set(pair.slice(0, index).trim(), pair.slice(index + 1))
    }
    const text = await response.text()
    if (!response.ok && !(tolerate401 && response.status === 401)) throw new ApiError(response.status, path, text)
    return text ? JSON.parse(text) : undefined
  }

  /** Fetches the CSRF cookie (set on any /api response, also a 401), then signs in. */
  async login(email: string, password: string): Promise<LoginResult> {
    await this.send('GET', '/api/v1/auth/me', undefined, true)
    return (await this.send('POST', '/api/v1/auth/login', { email, password })) as LoginResult
  }
  get<T>(path: string): Promise<T> { return this.send('GET', path) as Promise<T> }
  post<T>(path: string, body?: unknown): Promise<T> { return this.send('POST', path, body ?? {}) as Promise<T> }
  put<T>(path: string, body: unknown): Promise<T> { return this.send('PUT', path, body) as Promise<T> }
}

export const uniqueSuffix = (): string => randomBytes(4).toString('hex')

/**
 * Creates a catalogue service (as the clinic user) and gives it a price valid from two days ago. Room rates are priced at
 * 00:00 of each charged day (BR-B6) and the API refuses past prices, so a fresh stack needs this test-only fixture: one
 * INSERT through psql in the Compose `db` container (superuser, so RLS does not apply). Never run against real data.
 */
export async function backdatedService(clinic: { email: string; password: string }, env: RealStackEnv, name: string, amount: string): Promise<void> {
  const api = new Api(env.baseURL)
  await api.login(clinic.email, clinic.password)
  const code = `E2E_${uniqueSuffix().toUpperCase()}`
  const service = await api.post<{ id: string }>('/api/v1/services', { code, name })
  if (!/^[0-9a-f-]{36}$/.test(service.id) || !/^\d+\.\d{2}$/.test(amount)) throw new Error('unexpected id or amount')
  const sql = `INSERT INTO hms.service_price (id, organization_id, service_id, amount, effective_from, created_by, created_at)
    SELECT gen_random_uuid(), organization_id, id, ${amount}, now() - interval '2 days', '00000000-0000-0000-0000-000000000000', now()
    FROM hms.service_catalog_item WHERE id = '${service.id}';`
  const compose = (process.env.E2E_COMPOSE ?? 'docker compose -f ../infra/compose/compose.yml --env-file ../.env').split(' ')
  execFileSync(compose[0], [...compose.slice(1), 'exec', '-T', 'db', 'sh', '-c', 'psql -q -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB"'],
    { input: sql, stdio: ['pipe', 'ignore', 'inherit'], env: { ...process.env, MSYS_NO_PATHCONV: '1' } })
}

/** A password that meets the policy (length) and is random per run; never printed. */
export const randomPassword = (): string => `E2e-${randomBytes(12).toString('hex')}`

/** Calendar date (yyyy-MM-dd) of tomorrow in the given time zone. */
export function tomorrowIn(timeZone: string): string {
  return new Intl.DateTimeFormat('en-CA', { timeZone, year: 'numeric', month: '2-digit', day: '2-digit' })
    .format(new Date(Date.now() + 24 * 3600 * 1000))
}

export interface Clinic {
  email: string
  password: string
  doctorName: string
  serviceName: string
  servicePrice: string
  appointmentDate: string
}

/**
 * Prepares the clinic through the API: signs in as the System Administrator (changing the bootstrap temporary password
 * on the first run), creates ONE user holding the Doctor, Receptionist, Accountant and Facility Administrator roles,
 * then as that user a specialty, a priced catalogue service, the user's own practitioner profile and weekly
 * availability on every weekday. The journey signs in as that user in the browser.
 */
export async function prepareClinic(env: RealStackEnv): Promise<Clinic> {
  const suffix = uniqueSuffix()
  const admin = new Api(env.baseURL)
  // An earlier spec of the same run may already have replaced the temporary password with adminNewPassword.
  let session = await admin.login(env.adminEmail, env.adminPassword).catch(async (error: unknown) => {
    if (!(error instanceof ApiError && error.status === 401 && env.adminNewPassword)) throw error
    return admin.login(env.adminEmail, env.adminNewPassword)
  })
  if (session.user.mustChangePassword) {
    if (!env.adminNewPassword) throw new Error('The admin still has the temporary password: set E2E_ADMIN_NEW_PASSWORD (see e2e/README.md)')
    await admin.post('/api/v1/auth/change-password', { currentPassword: env.adminPassword, newPassword: env.adminNewPassword })
    session = await admin.login(env.adminEmail, env.adminNewPassword)
  }

  const email = `e2e-${suffix}@example.test`
  const created = await admin.post<{ temporaryPassword: string }>('/api/v1/users', {
    email, fullName: `E2E Clinician ${suffix}`, roleCodes: ['DOCTOR', 'RECEPTIONIST', 'ACCOUNTANT', 'FACILITY_ADMIN'],
  })
  const password = randomPassword()
  const clinician = new Api(env.baseURL)
  await clinician.login(email, created.temporaryPassword)
  await clinician.post('/api/v1/auth/change-password', { currentPassword: created.temporaryPassword, newPassword: password })
  const me = await clinician.login(email, password)

  const specialty = await clinician.post<{ id: string }>('/api/v1/specialties', { code: `E2E${suffix.toUpperCase()}`, name: `E2E Specialty ${suffix}` })
  const serviceName = `E2E Consultation ${suffix}`
  const servicePrice = '300.00'
  const service = await clinician.post<{ id: string }>('/api/v1/services', { code: `E2E_${suffix.toUpperCase()}`, name: serviceName })
  await clinician.post(`/api/v1/services/${service.id}/prices`, { amount: servicePrice })

  const doctorName = `Dr. E2E ${suffix}`
  const doctor = await clinician.post<{ id: string }>('/api/v1/doctors', {
    userId: me.user.id, fullName: doctorName, registrationNo: `E2E-${suffix}`, defaultSlotMinutes: 15,
    specialtyIds: [specialty.id], primarySpecialtyId: specialty.id,
  })
  const availability = await clinician.get<{ version: number }>(`/api/v1/doctors/${doctor.id}/availability`)
  await clinician.put(`/api/v1/doctors/${doctor.id}/availability`, {
    version: availability.version,
    windows: [1, 2, 3, 4, 5, 6, 7].map((weekday) => ({ weekday, start: '09:00', end: '17:00', slotMinutes: 15 })),
  })

  return { email, password, doctorName, serviceName, servicePrice, appointmentDate: tomorrowIn('Asia/Kolkata') }
}
