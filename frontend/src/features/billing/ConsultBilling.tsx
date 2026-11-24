import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Link, useNavigate } from 'react-router'
import { billingKeys, createInvoice, listInvoices } from '../../api/billing'
import { ApiError } from '../../api/client'
import type { Encounter } from '../../api/clinical'
import { FormError, primaryButton } from '../../components/ui'
import { useCan } from '../auth/useSession'
import { Section } from '../consultation/shared'
import { billingErrorMessage, STATUS_LABELS } from './shared'

/** The consult-to-invoice entry point: opens this visit's live invoice, or creates its draft. */
export function ConsultBilling({ encounter }: { encounter: Encounter }) {
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const canRead = useCan('invoice:read')
  const canCreate = useCan('invoice:create')
  const patientId = encounter.patient.id
  const invoices = useQuery({ queryKey: billingKeys.forPatient(patientId), queryFn: () => listInvoices({ patientId, size: 100 }), enabled: canRead })
  const create = useMutation({
    mutationFn: () => createInvoice({ patientId, encounterId: encounter.id }),
    onSuccess: (invoice) => {
      void queryClient.invalidateQueries({ queryKey: billingKeys.all })
      navigate(`/invoices/${invoice.id}`)
    },
    onError: (error) => {
      if (error instanceof ApiError && error.status === 409) void queryClient.invalidateQueries({ queryKey: billingKeys.forPatient(patientId) })
    },
  })
  if (!canRead && !canCreate) return null
  // A discarded or cancelled invoice frees the encounter, so only the live one counts.
  const live = invoices.data?.items.find((i) => i.encounterId === encounter.id && i.status !== 'DISCARDED' && i.status !== 'CANCELLED')
  const exists = create.error instanceof ApiError && create.error.status === 409
  return (
    <Section title="Billing">
      {invoices.isError && <FormError message={billingErrorMessage(invoices.error)} />}
      {live ? (
        <p className="text-sm">
          <Link className="text-primary underline" to={`/invoices/${live.id}`}>Open invoice {live.invoiceNo ?? '(draft)'}</Link>
          <span className="ml-2 rounded bg-muted px-2 py-0.5 text-xs">{STATUS_LABELS[live.status]}</span>
        </p>
      ) : canCreate ? (
        <button type="button" className={primaryButton} disabled={create.isPending} onClick={() => create.mutate()}>Create invoice</button>
      ) : (
        <p className="text-sm text-ink-muted">No invoice has been created for this visit yet.</p>
      )}
      {exists && <p role="alert" className="mt-2 text-sm text-amber-800">An invoice already exists for this visit.{canRead ? ' It is listed above once loaded.' : ' Ask someone with invoice access to open it.'}</p>}
      {create.isError && !exists && <div className="mt-2"><FormError message={billingErrorMessage(create.error)} /></div>}
    </Section>
  )
}
