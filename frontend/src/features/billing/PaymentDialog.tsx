import { useMutation, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { billingKeys, PAYMENT_MODES, receiptPdfUrl, recordPayment, type PaymentMode, type RecordedPayment } from '../../api/billing'
import { Dialog } from '../../components/Dialog'
import { Field, FormError, inputClass, primaryButton, secondaryButton } from '../../components/ui'
import { isMoneyAmount } from '../../lib/money'
import { useCan } from '../auth/useSession'
import { AMOUNT_HINT, billingErrorMessage, RETRY_ON_NETWORK_ERROR, STATUS_LABELS, useIdempotencyKey } from './shared'

const MODE_LABELS: Record<PaymentMode, string> = { CASH: 'Cash', CARD: 'Card', UPI: 'UPI', BANK_TRANSFER: 'Bank transfer', CHEQUE: 'Cheque' }

/**
 * Records one payment. The amount is checked by pattern only; whether it exceeds what is outstanding is the server's
 * answer (409 EXCEEDS_OUTSTANDING, "Outstanding is X."). The result shows the server's outstanding and receipt.
 */
export function PaymentDialog({ invoiceId, onRecorded, onClose }: { invoiceId: string; onRecorded: (p: RecordedPayment) => void; onClose: () => void }) {
  const queryClient = useQueryClient()
  const canReceipt = useCan('payment:read')
  const key = useIdempotencyKey()
  const [amount, setAmount] = useState('')
  const [mode, setMode] = useState<PaymentMode>('CASH')
  const [referenceNo, setReferenceNo] = useState('')
  const [errors, setErrors] = useState<{ amount?: string; referenceNo?: string }>({})
  const [result, setResult] = useState<RecordedPayment | null>(null)

  const pay = useMutation({
    mutationFn: () => {
      const reference = referenceNo.trim()
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
    if (referenceNo.trim().length > 50) next.referenceNo = 'Reference is at most 50 characters'
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
            {canReceipt && <a className="text-blue-700 underline" href={receiptPdfUrl(result.id)} target="_blank" rel="noreferrer">Open receipt PDF</a>}
            <button type="button" className={primaryButton} onClick={onClose}>Done</button>
          </div>
        </div>
      ) : (
        <form noValidate onSubmit={(e) => { e.preventDefault(); submit() }}>
          <Field label="Amount" htmlFor="pay-amount" error={errors.amount}>
            <input id="pay-amount" inputMode="decimal" className={inputClass} value={amount} onChange={(e) => setAmount(e.target.value)} />
          </Field>
          <Field label="Mode" htmlFor="pay-mode">
            <select id="pay-mode" className={inputClass} value={mode} onChange={(e) => setMode(e.target.value as PaymentMode)}>
              {PAYMENT_MODES.map((m) => <option key={m} value={m}>{MODE_LABELS[m]}</option>)}
            </select>
          </Field>
          <Field label="Reference no." htmlFor="pay-ref" error={errors.referenceNo}>
            <input id="pay-ref" className={inputClass} value={referenceNo} onChange={(e) => setReferenceNo(e.target.value)} />
          </Field>
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
