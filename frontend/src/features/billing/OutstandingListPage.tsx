import { useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import { Link } from 'react-router'
import { billingKeys, listInvoices } from '../../api/billing'
import { secondaryButton } from '../../components/ui'
import { errorMessage } from '../auth/messages'
import { STATUS_LABELS } from './shared'

const PAGE_SIZE = 50
const OUTSTANDING = 'ISSUED,PARTIALLY_PAID'

/** Issued and partly paid invoices (`GET /invoices?status=ISSUED,PARTIALLY_PAID`), totals exactly as the server sends them. */
export function OutstandingListPage() {
  const [page, setPage] = useState(0)
  const list = useQuery({
    queryKey: [...billingKeys.outstanding, page],
    queryFn: () => listInvoices({ status: OUTSTANDING, page, size: PAGE_SIZE }),
  })
  const data = list.data
  const lastPage = data ? (page + 1) * PAGE_SIZE >= data.totalItems : true
  return (
    <section>
      <h1 className="mb-4 text-2xl font-semibold">Invoices to collect</h1>
      <p className="mb-3 text-sm text-ink-muted">Issued and partly paid invoices. Open one to take a payment; the outstanding amount is shown after each payment.</p>
      {list.isPending && <p role="status">Loading...</p>}
      {list.isError && <p role="alert" className="text-danger">{errorMessage(list.error)}</p>}
      {data && data.items.length === 0 && <p className="text-ink-muted">Nothing is waiting for payment.</p>}
      {data && data.items.length > 0 && (
        <table className="w-full text-left text-sm">
          <caption className="sr-only">Invoices to collect</caption>
          <thead>
            <tr className="border-b"><th className="py-2">Invoice</th><th>Issued</th><th>Status</th><th className="text-right">Total</th><th /></tr>
          </thead>
          <tbody>
            {data.items.map((inv) => (
              <tr key={inv.id} className="border-b">
                <td className="py-2 font-medium">{inv.invoiceNo}</td>
                <td>{inv.issuedAt ? new Date(inv.issuedAt).toLocaleDateString() : ''}</td>
                <td>{STATUS_LABELS[inv.status]}</td>
                <td className="text-right tabular-nums">{inv.total}</td>
                <td className="text-right">
                  <Link className="text-primary underline" to={`/invoices/${inv.id}`} aria-label={`Open ${inv.invoiceNo}`}>Open</Link>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
      {data && data.totalItems > PAGE_SIZE && (
        <div className="mt-3 flex gap-2">
          <button type="button" className={secondaryButton} disabled={page === 0} onClick={() => setPage(page - 1)}>Previous</button>
          <button type="button" className={secondaryButton} disabled={lastPage} onClick={() => setPage(page + 1)}>Next</button>
        </div>
      )}
    </section>
  )
}
