import { useMutation, useQueryClient } from '@tanstack/react-query'
import { issueInvoice, type Invoice } from '../../api/billing'
import { FormError, primaryButton } from '../../components/ui'
import { applyInvoice, billingErrorMessage, RETRY_ON_NETWORK_ERROR, useIdempotencyKey } from './shared'

/** Issues the draft. One Idempotency-Key per click-intent: reused for retries of this invoice, dropped after success. */
export function IssueButton({ invoice, disabled, hint }: { invoice: Invoice; disabled: boolean; hint?: string }) {
  const queryClient = useQueryClient()
  const key = useIdempotencyKey()
  const issue = useMutation({
    mutationFn: () => issueInvoice(invoice.id, key.keyFor(invoice.id)),
    ...RETRY_ON_NETWORK_ERROR,
    onSuccess: (issued) => {
      key.done()
      applyInvoice(queryClient, issued)
    },
  })
  return (
    <div>
      <button type="button" className={primaryButton} disabled={disabled || issue.isPending} onClick={() => issue.mutate()}>Issue invoice</button>
      {hint && <p className="mt-1 text-sm text-ink-muted">{hint}</p>}
      {issue.isError && <div className="mt-2"><FormError message={billingErrorMessage(issue.error)} /></div>}
    </div>
  )
}
