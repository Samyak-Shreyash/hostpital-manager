import { keepPreviousData, useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import { listAuditEvents, type AuditFilters } from '../../api/audit'
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
  const totalPages = events.data ? Math.max(1, Math.ceil(events.data.totalItems / events.data.size)) : 1

  return (
    <section>
      <h1 className="mb-1 text-2xl font-semibold">Audit log</h1>
      <p className="mb-4 text-sm text-slate-600">
        Newest first. Viewing this log is itself recorded. Times are shown in your local time zone.
      </p>
      <AuditFilterForm
        onApply={(applied) => {
          setFilters(applied)
          setPage(0)
        }}
      />
      {events.isError && <p role="alert" className="text-red-700">{errorMessage(events.error)}</p>}
      {events.isPending && <p role="status">Loading...</p>}
      {events.data && (
        <>
          <div className="overflow-x-auto">
            <table className="w-full border-collapse bg-white text-left text-sm shadow">
              <thead>
                <tr className="border-b bg-slate-100">
                  <th className="p-2">Time</th><th className="p-2">Action</th><th className="p-2">Outcome</th>
                  <th className="p-2">Actor</th><th className="p-2">Entity</th><th className="p-2">Fields</th>
                  <th className="p-2">Correlation id</th>
                </tr>
              </thead>
              <tbody>
                {events.data.items.map((e) => (
                  <tr key={e.id} className="border-b align-top">
                    <td className="p-2 whitespace-nowrap">{new Date(e.occurredAt).toLocaleString()}</td>
                    <td className="p-2">{e.action}</td>
                    <td className="p-2">{e.outcome}</td>
                    <td className="p-2 font-mono text-xs">{e.actorId ?? 'System'}</td>
                    <td className="p-2">
                      {e.entityType}
                      {e.entityId && <span className="block font-mono text-xs">{e.entityId}</span>}
                    </td>
                    <td className="p-2">{e.changedFields.join(', ')}</td>
                    <td className="p-2 font-mono text-xs">{e.correlationId ?? ''}</td>
                  </tr>
                ))}
                {events.data.items.length === 0 && <tr><td className="p-2" colSpan={7}>No audit events found.</td></tr>}
              </tbody>
            </table>
          </div>
          <div className="mt-3 flex items-center gap-3 text-sm">
            <button type="button" className="underline disabled:opacity-40" disabled={page === 0} onClick={() => setPage(page - 1)}>Previous</button>
            <span>Page {page + 1} of {totalPages}</span>
            <button type="button" className="underline disabled:opacity-40" disabled={page + 1 >= totalPages} onClick={() => setPage(page + 1)}>Next</button>
          </div>
        </>
      )}
    </section>
  )
}
