import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { Link, useParams } from 'react-router'
import { billingKeys, getInvoice, invoicePdfUrl, receiptPdfUrl, type Invoice, type RecordedPayment } from '../../api/billing'
import { primaryButton } from '../../components/ui'
import { errorMessage } from '../auth/messages'
import { useCan } from '../auth/useSession'
import { DraftEditor } from './DraftEditor'
import { PaymentDialog } from './PaymentDialog'
import { STATUS_LABELS, useIdempotencyKey } from './shared'

const ISSUED_STATUSES = ['ISSUED', 'PARTIALLY_PAID', 'PAID', 'CANCELLED']

/** Totals exactly as the server calculated them; never summed or rounded in the browser. */
function Totals({ invoice }: { invoice: Invoice }) {
  return (
    <section aria-label="Invoice totals" className="mb-4 rounded-xl border border-border bg-surface p-4">
      <dl className="grid grid-cols-[1fr_auto] gap-x-6 gap-y-2 text-sm">
        <dt>Subtotal</dt><dd className="text-right tabular-nums" data-testid="subtotal">{invoice.subtotal}</dd>
        <dt>Discount</dt><dd className="text-right tabular-nums" data-testid="discount">{invoice.discountTotal}</dd>
        <dt>Tax</dt><dd className="text-right tabular-nums" data-testid="tax">{invoice.taxTotal}</dd>
        <dt className="border-t border-border pt-3 font-display text-lg font-semibold">Total (₹)</dt>
        <dd className="border-t border-border pt-3 text-right font-display text-3xl font-bold tabular-nums" data-testid="total">{invoice.total}</dd>
      </dl>
    </section>
  )
}

function ReadOnlyItems({ invoice }: { invoice: Invoice }) {
  return (
    <table className="mb-3 w-full text-left text-sm">
      <caption className="sr-only">Invoice lines</caption>
      <thead>
        <tr className="border-b"><th className="py-1">Item</th><th>Qty</th><th className="text-right">Unit price</th><th className="text-right">Discount</th><th className="text-right">Tax</th><th className="text-right">Total</th></tr>
      </thead>
      <tbody>
        {invoice.items.map((i) => (
          <tr key={i.id} className="border-b">
            <td className="py-1">{i.description}</td>
            <td>{i.quantity}</td>
            <td className="text-right tabular-nums">{i.unitPrice}</td>
            <td className="text-right tabular-nums">{i.discount}</td>
            <td className="text-right tabular-nums">{i.tax}</td>
            <td className="text-right tabular-nums">{i.total}</td>
          </tr>
        ))}
      </tbody>
    </table>
  )
}

/** One invoice ([E7-6]): the DRAFT editor, then the issued view with payments and PDF links. */
export function InvoiceEditorPage() {
  const { id = '' } = useParams()
  const queryClient = useQueryClient()
  const canPay = useCan('payment:create')
  const canReceipt = useCan('payment:read')
  const canOpenEncounter = useCan('encounter:read')
  const canOpenPatient = useCan('patient:read')
  const [paying, setPaying] = useState(false)
  // Lives above the dialog: closing and reopening after a network error must not mint a new key (double payment).
  const paymentKey = useIdempotencyKey()
  // Payments taken on this screen only: the API has no per-invoice payment list yet.
  const [payments, setPayments] = useState<RecordedPayment[]>([])
  const invoice = useQuery({ queryKey: billingKeys.detail(id), queryFn: () => getInvoice(id), enabled: !!id })
  const data = invoice.data
  const reload = () => { void queryClient.invalidateQueries({ queryKey: billingKeys.detail(id) }) }
  const latest = payments.length ? payments[payments.length - 1] : null

  return (
    <section>
      {invoice.isPending && <p role="status">Loading...</p>}
      {invoice.isError && <p role="alert" className="text-danger">{errorMessage(invoice.error)}</p>}
      {data && (
        <>
          <h1 className="mb-1 text-3xl font-semibold">{data.invoiceNo ? `Invoice ${data.invoiceNo}` : 'Draft invoice'}</h1>
          <p className="mb-4 flex flex-wrap items-center gap-3 text-sm">
            <span className="rounded bg-muted px-2 py-0.5 text-xs">{STATUS_LABELS[data.status]}</span>
            {canOpenEncounter && data.encounterId && <Link className="text-primary underline" to={`/consult/${data.encounterId}`}>Consultation</Link>}
            {canOpenPatient && <Link className="text-primary underline" to={`/patients/${data.patientId}`}>Patient record</Link>}
            {ISSUED_STATUSES.includes(data.status) && (
              <a className="text-primary underline" href={invoicePdfUrl(data.id)} target="_blank" rel="noreferrer">Open invoice PDF</a>
            )}
          </p>
          <div className="flex flex-wrap items-start gap-5">
          <div className="min-w-0 flex-[999_1_560px]">
          {data.status === 'DRAFT' ? (
            <section aria-label="Invoice lines" className="mb-6 rounded-xl border border-border bg-surface p-4">
              <DraftEditor key={data.version} invoice={data} onReload={reload} />
            </section>
          ) : (
            <section aria-label="Invoice lines" className="mb-6 rounded-xl border border-border bg-surface p-4">
              <ReadOnlyItems invoice={data} />
              {data.status === 'DISCARDED' && <p className="text-sm text-ink-muted">This draft was discarded.</p>}
            </section>
          )}
          </div>
          <aside aria-label="Totals and payment" className="min-w-0 flex-[1_1_320px]">
          <Totals invoice={data} />
          {(data.status === 'ISSUED' || data.status === 'PARTIALLY_PAID') && canPay && (
            <section aria-label="Payment" className="mb-6 rounded-xl border border-border bg-surface p-4">
              <button type="button" className={`${primaryButton} min-h-13 w-full text-base`} onClick={() => setPaying(true)}>Take payment</button>
              {latest && <p className="mt-2 text-sm">Outstanding after the last payment: <span data-testid="last-outstanding">{latest.outstanding}</span></p>}
            </section>
          )}
          {payments.length > 0 && (
            <section aria-label="Payments taken here" className="mb-6 rounded-xl border border-border bg-surface p-4">
              <h2 className="mb-2 text-lg font-semibold">Payments taken on this screen</h2>
              <ul className="text-sm">
                {payments.map((p) => (
                  <li key={p.id}>
                    {p.receiptNo}: {p.amount}{' '}
                    {canReceipt && <a className="text-primary underline" href={receiptPdfUrl(p.id)} target="_blank" rel="noreferrer">Receipt PDF {p.receiptNo}</a>}
                  </li>
                ))}
              </ul>
            </section>
          )}
          </aside>
          </div>
          {paying && (
            <PaymentDialog invoiceId={data.id} idempotencyKey={paymentKey} onRecorded={(p) => setPayments((current) => [...current, p])} onClose={() => setPaying(false)} />
          )}
        </>
      )}
    </section>
  )
}
