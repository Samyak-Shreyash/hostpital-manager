import { api } from './client'
import type { Page } from './types'

export interface AuditEvent {
  id: string
  occurredAt: string
  actorId: string | null
  action: string
  entityType: string
  entityId: string | null
  outcome: 'SUCCESS' | 'FAILURE' | 'DENIED'
  /** Field names only, never values. */
  changedFields: string[]
  correlationId: string | null
}

/** Applied filters, already normalized (UTC instants, trimmed); empty values are omitted from the query. */
export interface AuditFilters {
  actorId?: string
  action?: string
  entityType?: string
  entityId?: string
  from?: string
  to?: string
  page?: number
  size?: number
}

export function auditQueryString(filters: AuditFilters): string {
  const params = new URLSearchParams()
  for (const [key, value] of Object.entries(filters)) {
    if (value !== undefined && value !== '') params.set(key, String(value))
  }
  return params.toString()
}

export function listAuditEvents(filters: AuditFilters) {
  const query = auditQueryString(filters)
  return api.get<Page<AuditEvent>>(`/audit-events${query ? `?${query}` : ''}`)
}
