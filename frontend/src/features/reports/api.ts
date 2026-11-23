import { api, toApiError } from '../../api/client'

export interface ReportResult {
  report: string
  from: string | null
  to: string | null
  generatedAt: string
  columns: string[]
  rows: Record<string, string | number | null>[]
}
export interface Range { from: string; to: string }

export const REPORTS = [
  { key: 'registrations', label: 'Daily registrations', path: '/reports/registrations/daily', permission: 'report:clinical', ranged: true },
  { key: 'appointments', label: 'Appointments', path: '/reports/appointments', permission: 'report:clinical', ranged: true },
  { key: 'opd', label: 'OPD consultations', path: '/reports/opd-consultations', permission: 'report:clinical', ranged: true },
  { key: 'admissions', label: 'Active admissions', path: '/reports/admissions/active', permission: 'report:clinical', ranged: false },
  { key: 'beds', label: 'Bed occupancy', path: '/reports/beds/occupancy', permission: 'report:clinical', ranged: false },
  { key: 'discharges', label: 'Discharges', path: '/reports/discharges', permission: 'report:clinical', ranged: true },
  { key: 'revenue', label: 'Daily revenue', path: '/reports/revenue/daily', permission: 'report:finance', ranged: true },
  { key: 'outstanding', label: 'Outstanding payments', path: '/reports/payments/outstanding', permission: 'report:finance', ranged: false },
] as const

const withRange = (path: string, range?: Range) =>
  range ? `${path}?from=${encodeURIComponent(range.from)}&to=${encodeURIComponent(range.to)}` : path

export const fetchReport = (path: string, range?: Range) => api.get<ReportResult>(withRange(path, range))

export async function downloadCsv(path: string, range?: Range): Promise<void> {
  const response = await fetch(`/api/v1${withRange(path, range)}`, {
    credentials: 'same-origin',
    headers: { Accept: 'text/csv' },
  })
  if (!response.ok) throw await toApiError(response)
  const filename = /filename="([^"]+)"/.exec(response.headers.get('Content-Disposition') ?? '')?.[1] ?? 'report.csv'
  const url = URL.createObjectURL(await response.blob())
  const a = document.createElement('a')
  a.href = url
  a.download = filename
  a.click()
  URL.revokeObjectURL(url)
}
