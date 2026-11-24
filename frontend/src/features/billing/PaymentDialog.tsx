import { useMutation, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { billingKeys, PAYMENT_MODES, receiptPdfUrl, recordPayment, type PaymentMode, type RecordedPayment } from '../../api/billing'
import { Dialog } from '../../components/Dialog'
import { Field, FormError, inputClass, primaryButton, secondaryButton } from '../../components/ui'
import { isMoneyAmount } from '../../lib/money'
import { useCan } from '../auth/useSession'
import { AMOUNT_HINT, billingErrorMessage, RETRY_ON_NETWORK_ERROR, STATUS_LABELS, type IdempotencyKey } from './shared'

const MODE_LABELS: Record<PaymentMode, string> = { CASH: 'Cash', CARD: 'Card', UPI: 'UPI', BANK_TRANSFER: 'Bank transfer', CHEQUE: 'Cheque' }
/** UPI first: the most common mode at Indian counters. */
const MODE_ORDER: PaymentMode[] = ['UPI', 'CASH', 'CARD', 'BANK_TRANSFER', 'CHEQUE']
const REFERENCE_LABELS: Record<PaymentMode, string> = {
  UPI: 'UPI reference (UTR)', CASH: 'Reference no.', CARD: 'Card transaction reference', BANK_TRANSFER: 'Bank reference (UTR)', CHEQUE: 'Cheque number',
}

/** Change to return for cash, display only (never sent). Paise arithmetic avoids float drift; null when not computable. */
export function changeDue(received: string, amount: string): string | null {
  if (!isMoneyAmount(received.trim()) || !isMoneyAmount(amount.trim())) return null
  const paise = (v: string) => Math.round(Number(v.trim()) * 100)
  const diff = paise(received) - paise(amount)
  return diff < 0 ? null : (diff / 100).toFixed(2)
}

/**
 * Records one payment. The amount is checked by pattern only; whether it exceeds what is outstanding is the server's
 * answer (409 EXCEEDS_OUTSTANDING, "Outstanding is X."). The result shows the server's outstanding and receipt.
 * The idempotency key is owned by the caller (one per invoice, kept across close/reopen) so a retry after a network
 * error that closed the dialog reuses the same key until the payment succeeds or the request changes.
 */
export function PaymentDialog({ invoiceId, idempotencyKey: key, onRecorded, onClose }: { invoiceId: string; idempotencyKey: IdempotencyKey; onRecorded: (p: RecordedPayment) => void; onClose: () => void }) {
  const queryClient = useQueryClient()
  const canReceipt = useCan('payment:read')
  const [amount, setAmount] = useState('')
  const [mode, setMode] = useState<PaymentMode>('UPI')
  const [referenceNo, setReferenceNo] = useState('')
  const [received, setReceived] = useState('')
  const change = mode === 'CASH' ? changeDue(received, amount) : null
  const [errors, setErrors] = useState<{ amount?: string; referenceNo?: string }>({})
  const [result, setResult] = useState<RecordedPayment | null>(null)

  const pay = useMutation({
    mutationFn: () => {
      const reference = mode === 'CASH' ? '' : referenceNo.trim()
      const body = { amount: amount.trim(), mode, ...(reference ? { referenceNo: reference } : {}) }
      return recordPayment(invoiceId, body, key.keyFor(`${invoiceId}|${body.amount}|${mode}|${reference}`))
    },
    ...RETRY_ON_NETWORK_ERROR,
    onSuccess: (payment) => {
      key.done()
      setResult(payment)
      onRecorded(payment)
      void queryClient.invalidateQueries({ queryKey: billingKeys.all })
    },
  })

  const submit = () => {
    const next: typeof errors = {}
    if (!isMoneyAmount(amount.trim())) next.amount = AMOUNT_HINT
    if (mode !== 'CASH' && referenceNo.trim().length > 50) next.referenceNo = 'Reference is at most 50 characters'
    setErrors(next)
    if (Object.keys(next).length === 0) pay.mutate()
  }

  return (
    <Dialog title="Record payment" onClose={onClose}>
      {result ? (
        <div>
          <p className="mb-2 text-sm">Payment recorded. Receipt <strong>{result.receiptNo}</strong></p>
          <dl className="mb-3 grid grid-cols-[auto_1fr] gap-x-4 text-sm">
            <dt>Amount received</dt><dd data-testid="paid">{result.amount}</dd>
            <dt>Outstanding</dt><dd data-testid="outstanding">{result.outstanding}</dd>
            <dt>Invoice status</dt><dd>{STATUS_LABELS[result.invoiceStatus]}</dd>
          </dl>
          <div className="flex items-center gap-3">
            {canReceipt && <a className="text-primary underline" href={receiptPdfUrl(result.id)} target="_blank" rel="noreferrer">Open receipt PDF</a>}
            <button type="button" className={primaryButton} onClick={onClose}>Done</button>
          </div>
        </div>
      ) : (
        <form noValidate onSubmit={(e) => { e.preventDefault(); submit() }}>
          <fieldset className="mb-4">
            <legend className="mb-1.5 text-[13px] font-semibold text-ink-muted">Mode</legend>
            <div className="grid grid-cols-2 gap-2 sm:grid-cols-5">
              {MODE_ORDER.filter((m) => PAYMENT_MODES.includes(m)).map((m) => (
                <button key={m} type="button" aria-pressed={mode === m} onClick={() => setMode(m)}
                  className={`min-h-12 rounded-lg border px-2 text-sm font-semibold ${mode === m ? 'border-nav bg-nav text-white' : 'border-border-strong bg-surface hover:bg-muted'}`}>
                  {MODE_LABELS[m]}
                </button>
              ))}
            </div>
          </fieldset>
          <Field label="Amount" htmlFor="pay-amount" error={errors.amount}>
            <input id="pay-amount" inputMode="decimal" className={`${inputClass} text-lg font-semibold`} value={amount} onChange={(e) => setAmount(e.target.value)} />
          </Field>
          {mode === 'CASH' ? (
            <div className="mb-4 grid grid-cols-2 gap-3">
              <Field label="Cash received" htmlFor="pay-received">
                <input id="pay-received" inputMode="decimal" className={inputClass} value={received} onChange={(e) => setReceived(e.target.value)} />
              </Field>
              <div>
                <span className="block text-[13px] font-semibold text-ink-muted">Return change</span>
                <output htmlFor="pay-received pay-amount" className="mt-1 flex min-h-11 items-center rounded-lg bg-primary-soft px-3 font-bold">{change ?? '–'}</output>
              </div>
            </div>
          ) : (
            <Field label={REFERENCE_LABELS[mode]} htmlFor="pay-ref" error={errors.referenceNo}>
              <input id="pay-ref" className={inputClass} value={referenceNo} onChange={(e) => setReferenceNo(e.target.value)} />
            </Field>
          )}
          <p className="mb-4 text-xs text-ink-muted">Part payment is allowed, up to the outstanding balance. Payments are never edited: reversals are refunds.</p>
          <FormError message={pay.isError ? billingErrorMessage(pay.error) : null} />
          <div className="flex gap-2">
            <button type="submit" className={primaryButton} disabled={pay.isPending}>Record payment</button>
            <button type="button" className={secondaryButton} onClick={onClose}>Cancel</button>
          </div>
        </form>
      )}
    </Dialog>
  )
}
