import { keepPreviousData, useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import { Link } from 'react-router'
import { listAuditEvents, type AuditFilters } from '../../api/audit'
import { listUsers } from '../../api/users'
import { secondaryButton } from '../../components/ui'
import { useCan } from '../auth/useSession'
import { errorMessage } from '../auth/messages'
import { AuditFilterForm } from './AuditFilterForm'

const PAGE_SIZE = 25

export function AuditPage() {
  const [filters, setFilters] = useState<AuditFilters>({})
  const [page, setPage] = useState(0)
  const query = { ...filters, page, size: PAGE_SIZE }
  const events = useQuery({
    queryKey: ['audit-events', query],
    queryFn: () => listAuditEvents(query),
    placeholderData: keepPreviousData,
  })
  // Audit rows hold ids only; with user:read the names are looked up in one call per page.
  const canReadUsers = useCan('user:read')
  const actorIds = [...new Set((events.data?.items ?? []).flatMap((e) => (e.actorId ? [e.actorId] : [])))].sort()
  const actors = useQuery({
    queryKey: ['users', { ids: actorIds }],
    queryFn: () => listUsers({ ids: actorIds, size: 100 }),
    enabled: canReadUsers && actorIds.length > 0,
  })
  const actorNames = new Map((actors.data?.items ?? []).map((u) => [u.id, u]))
  const resolveEmail = async (email: string) => {
    const found = await listUsers({ q: email, size: 100 })
    return found.items.find((u) => u.email.toLowerCase() === email.trim().toLowerCase())?.id ?? null
  }
  const totalPages = events.data ? Math.max(1, Math.ceil(events.data.totalItems / events.data.size)) : 1

  return (
    <section>
      <h1 className="mb-1 text-2xl font-semibold">Audit log</h1>
      <p className="mb-4 text-sm text-ink-muted">
        Newest first. Viewing this log is itself recorded. Times are shown in your local time zone.
      </p>
      <AuditFilterForm
        resolveEmail={canReadUsers ? resolveEmail : undefined}
        onApply={(applied) => {
          setFilters(applied)
          setPage(0)
        }}
      />
      {events.isError && <p role="alert" className="text-danger">{errorMessage(events.error)}</p>}
      {events.isPending && <p role="status">Loading...</p>}
      {events.data && (
        <>
          <div className="overflow-x-auto">
            <table className="w-full border-collapse bg-surface text-left text-sm">
              <thead>
                <tr className="border-b bg-muted">
                  <th className="p-2">Time</th><th className="p-2">Action</th><th className="p-2">Outcome</th>
                  <th className="p-2">Actor</th><th className="p-2">Entity</th><th className="p-2">Fields</th>
                  <th className="p-2">Correlation id</th>
                </tr>
              </thead>
              <tbody>
                {events.data.items.map((e) => (
                  <tr key={e.id} className="border-b align-top">
                    <td className="p-2 whitespace-nowrap">{new Date(e.occurredAt).toLocaleString('en-IN')}</td>
                    <td className="p-2">{e.action}</td>
                    <td className="p-2">{e.outcome}</td>
                    <td className="p-2">
                      {!e.actorId ? 'System' : actorNames.has(e.actorId) ? (
                        <Link className="text-primary underline" to={`/users/${encodeURIComponent(e.actorId)}`} title={e.actorId}>
                          {actorNames.get(e.actorId)?.fullName}
                          <span className="block text-xs text-ink-muted">{actorNames.get(e.actorId)?.email}</span>
                        </Link>
                      ) : (
                        <span className="font-mono text-xs">{e.actorId}</span>
                      )}
                    </td>
                    <td className="p-2">
                      {e.entityType}
                      {e.entityId && <span className="block font-mono text-xs">{e.entityId}</span>}
                    </td>
                    <td className="p-2">
                      {e.changedFields.join(', ')}
                      {(e.changedValues ?? []).map((v) => (
                        <span key={v.field} className="mt-1 block text-xs">
                          <span className="font-medium">{v.field}:</span>{' '}
                          <span className="text-ink-muted">{v.oldValue ?? '—'}</span> → <span>{v.newValue ?? '—'}</span>
                        </span>
                      ))}
                    </td>
                    <td className="p-2 font-mono text-xs">{e.correlationId ?? ''}</td>
                  </tr>
                ))}
                {events.data.items.length === 0 && <tr><td className="p-2" colSpan={7}>No audit events found.</td></tr>}
              </tbody>
            </table>
          </div>
          <div className="mt-3 flex items-center gap-3 text-sm">
            <button type="button" className={secondaryButton} disabled={page === 0} onClick={() => setPage(page - 1)}>Previous</button>
            <span>Page {page + 1} of {totalPages}</span>
            <button type="button" className={secondaryButton} disabled={page + 1 >= totalPages} onClick={() => setPage(page + 1)}>Next</button>
          </div>
        </>
      )}
    </section>
  )
}
