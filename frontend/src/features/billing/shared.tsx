import type { QueryClient } from '@tanstack/react-query'
import { useRef } from 'react'
import { billingKeys, type Invoice, type InvoiceStatus } from '../../api/billing'
import { ApiError } from '../../api/client'
import { newIdempotencyKey } from '../../lib/money'
import { errorMessage } from '../auth/messages'

export const STATUS_LABELS: Record<InvoiceStatus, string> = {
  DRAFT: 'Draft', ISSUED: 'Issued', PARTIALLY_PAID: 'Partly paid', PAID: 'Paid', CANCELLED: 'Cancelled', DISCARDED: 'Discarded',
}

export const AMOUNT_HINT = 'Enter an amount such as 150 or 150.50'

/** Billing wording for the codes that the appointment-oriented shared map words differently. */
export function billingErrorMessage(error: unknown): string {
  if (error instanceof ApiError) {
    if (error.status === 403) return 'You do not have permission to do this.'
    if (error.code === 'INVALID_STATE_TRANSITION') return 'The invoice is not in a state that allows this. Reload to see its current status.'
    if (error.code === 'INVOICE_IMMUTABLE') return 'This invoice has been issued and can no longer be changed. Corrections are made with a credit note.'
    if (error.code === 'IDEMPOTENCY_KEY_REUSED') return 'This request clashes with an earlier one. Close this and start again.'
  }
  return errorMessage(error)
}

/**
 * One Idempotency-Key per click-intent. The same key is handed out again while the request (its fingerprint) is
 * unchanged, so a retry after a network error or a refusal replays the same intent instead of repeating it. `done()`
 * after success, or a different fingerprint, starts a new intent with a new key.
 */
export function useIdempotencyKey() {
  const current = useRef<{ fingerprint: string; key: string } | null>(null)
  return {
    keyFor(fingerprint: string): string {
      if (!current.current || current.current.fingerprint !== fingerprint) current.current = { fingerprint, key: newIdempotencyKey() }
      return current.current.key
    },
    done() { current.current = null },
  }
}

/**
 * Money-moving calls retry a network failure (no answer from the server) automatically with the same key; a server
 * answer, even an error, is never retried automatically.
 */
export const RETRY_ON_NETWORK_ERROR = {
  retry: (failureCount: number, error: unknown) => !(error instanceof ApiError) && failureCount < 2,
  retryDelay: 300,
}

/** A mutation answered with the full invoice: use it as the detail and refresh only the lists that include it. */
export function applyInvoice(queryClient: QueryClient, view: Invoice) {
  queryClient.setQueryData(billingKeys.detail(view.id), view)
  void queryClient.invalidateQueries({ queryKey: billingKeys.outstanding })
  void queryClient.invalidateQueries({ queryKey: billingKeys.forPatient(view.patientId) })
}
