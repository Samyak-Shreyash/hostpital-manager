import { zodResolver } from '@hookform/resolvers/zod'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { useForm, useWatch } from 'react-hook-form'
import { Link, useNavigate, useParams } from 'react-router'
import { billingKeys, listInvoices, type Invoice } from '../../api/billing'
import { Dialog } from '../../components/Dialog'
import { Field, inputClass, primaryButton, secondaryButton } from '../../components/ui'
import { STATUS_LABELS as INVOICE_STATUS_LABELS } from '../billing/shared'
import { useCan, useSession } from '../auth/useSession'
import {
  cancelAdmission, createIpdInvoice, dischargeAdmission, getAdmission, getChargesPreview, ipdKeys, listBedAssignments, transferBed, updateAdmission,
  type Admission,
} from './api'
import { dischargeSchema, transferSchema, type DischargeValues, type TransferValues } from './schemas'
import {
  ADMISSION_STATUS_LABELS, BedSelect, formatDateTime, instantToLocal, IpdProblem, ipdErrorMessage, isBedTaken, localToInstant, useRefreshAvailability,
} from './shared'
import { SummaryForm } from './SummaryForm'

function Section({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <section aria-label={title} className="mb-6 rounded bg-white p-4 shadow">
      <h2 className="mb-3 text-lg font-semibold">{title}</h2>
      {children}
    </section>
  )
}

function TransferDialog({ admission, onClose, onDone }: { admission: Admission; onClose: () => void; onDone: () => void }) {
  const refreshAvailability = useRefreshAvailability()
  const form = useForm<TransferValues>({ resolver: zodResolver(transferSchema), defaultValues: { toBedId: '', reason: '' } })
  const transfer = useMutation({
    mutationFn: (v: TransferValues) => transferBed(admission.id, { toBedId: v.toBedId, reason: v.reason.trim() }),
    onSuccess: () => { refreshAvailability(); onDone(); onClose() },
    onError: (error) => { if (isBedTaken(error)) { form.setValue('toBedId', ''); refreshAvailability() } },
  })
  const { errors } = form.formState
  const toBedIdValue = useWatch({ control: form.control, name: 'toBedId' })
  return (
    <Dialog title="Transfer to another bed" onClose={onClose}>
      <form noValidate onSubmit={form.handleSubmit((v) => transfer.mutate(v))}>
        <BedSelect id="transfer-bed" value={toBedIdValue} excludeBedId={admission.currentBed?.id}
          onChange={(id) => form.setValue('toBedId', id, { shouldValidate: true })} error={errors.toBedId?.message} />
        <Field label="Reason" htmlFor="transfer-reason" error={errors.reason?.message}>
          <textarea id="transfer-reason" rows={2} className={inputClass} {...form.register('reason')} />
        </Field>
        <IpdProblem error={transfer.error} what="admission" onReload={() => { onDone(); onClose() }} />
        <div className="flex gap-2">
          <button type="submit" className={primaryButton} disabled={transfer.isPending}>Confirm transfer</button>
          <button type="button" className={secondaryButton} onClick={onClose}>Close</button>
        </div>
      </form>
    </Dialog>
  )
}

function DischargeDialog({ admission, finalInvoice, timeZone, onClose, onDone }: {
  admission: Admission; finalInvoice: Invoice | undefined; timeZone: string; onClose: () => void; onDone: () => void
}) {
  const canDefer = useCan('discharge:defer-billing')
  const form = useForm<DischargeValues>({
    resolver: zodResolver(dischargeSchema),
    defaultValues: { dischargedAt: admission.plannedDischargeAt ? instantToLocal(admission.plannedDischargeAt, timeZone) : '', deferBilling: false },
  })
  const discharge = useMutation({
    mutationFn: (v: DischargeValues) => dischargeAdmission(admission.id, {
      version: admission.version,
      dischargedAt: localToInstant(v.dischargedAt, timeZone),
      deferBilling: canDefer && v.deferBilling,
      ...(!(canDefer && v.deferBilling) && finalInvoice ? { invoiceId: finalInvoice.id } : {}),
    }),
    onSuccess: () => { onDone(); onClose() },
  })
  const issued = finalInvoice && ['ISSUED', 'PARTIALLY_PAID', 'PAID'].includes(finalInvoice.status)
  const { errors } = form.formState
  return (
    <Dialog title="Discharge patient" onClose={onClose}>
      <form noValidate onSubmit={form.handleSubmit((v) => discharge.mutate(v))}>
        <Field label="Discharged at" htmlFor="discharge-at" error={errors.dischargedAt?.message}>
          <input id="discharge-at" type="datetime-local" className={inputClass} {...form.register('dischargedAt')} />
        </Field>
        <p className="mb-3 text-sm text-slate-600">
          {issued ? `Final invoice ${finalInvoice.invoiceNo ?? ''} is issued.` : 'No issued final invoice yet: issue one first'}
          {!issued && canDefer ? ', or defer billing below.' : !issued ? '.' : ''}
        </p>
        {canDefer && (
          <label className="mb-4 flex items-center gap-2 text-sm">
            <input type="checkbox" {...form.register('deferBilling')} /> Defer billing (discharge without an issued final invoice)
          </label>
        )}
        <IpdProblem error={discharge.error} what="admission" onReload={() => { onDone(); onClose() }} />
        <div className="flex gap-2">
          <button type="submit" className={primaryButton} disabled={discharge.isPending}>Confirm discharge</button>
          <button type="button" className={secondaryButton} onClick={onClose}>Close</button>
        </div>
      </form>
    </Dialog>
  )
}

function DiagnosisEditor({ admission, onDone }: { admission: Admission; onDone: () => void }) {
  const [editing, setEditing] = useState(false)
  const form = useForm<{ admissionDiagnosis: string }>({ defaultValues: { admissionDiagnosis: admission.admissionDiagnosis } })
  const save = useMutation({
    mutationFn: (v: { admissionDiagnosis: string }) => updateAdmission(admission.id, { version: admission.version, admissionDiagnosis: v.admissionDiagnosis.trim() }),
    onSuccess: () => { setEditing(false); onDone() },
  })
  if (!editing) {
    return <p className="text-sm">Admission diagnosis: {admission.admissionDiagnosis}{' '}
      <button type="button" className="text-blue-700 underline" onClick={() => setEditing(true)}>Edit diagnosis</button></p>
  }
  return (
    <form noValidate onSubmit={form.handleSubmit((v) => save.mutate(v))}>
      <Field label="Admission diagnosis" htmlFor="edit-diagnosis">
        <textarea id="edit-diagnosis" rows={2} className={inputClass} {...form.register('admissionDiagnosis', { required: true, maxLength: 1000 })} />
      </Field>
      <IpdProblem error={save.error} what="admission" onReload={onDone} />
      <div className="flex gap-2">
        <button type="submit" className={primaryButton} disabled={save.isPending}>Save diagnosis</button>
        <button type="button" className={secondaryButton} onClick={() => setEditing(false)}>Cancel edit</button>
      </div>
    </form>
  )
}

/** One admission ([E8-8]): bed and history, transfer, summary, charges, invoices, discharge. */
export function AdmissionDetailPage() {
  const { id = '' } = useParams()
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const refreshAvailability = useRefreshAvailability()
  const timeZone = useSession().data?.organization.timezone ?? 'UTC'
  const canAssign = useCan('bed:assign')
  const canUpdate = useCan('admission:update')
  const canCancel = useCan('admission:cancel')
  const canWriteSummary = useCan('discharge:write')
  const canComplete = useCan('discharge:complete')
  const canInvoiceRead = useCan('invoice:read')
  const canInvoiceCreate = useCan('invoice:create')
  const [dialog, setDialog] = useState<'transfer' | 'discharge' | null>(null)
  const [confirmCancel, setConfirmCancel] = useState(false)

  const admissionQuery = useQuery({ queryKey: ipdKeys.admission(id), queryFn: () => getAdmission(id), enabled: !!id })
  const a = admissionQuery.data
  const open = a?.status === 'ADMITTED' || a?.status === 'DISCHARGE_INITIATED'
  const assignments = useQuery({ queryKey: ipdKeys.assignments(id), queryFn: () => listBedAssignments(id), enabled: !!a })
  const charges = useQuery({ queryKey: ipdKeys.charges(id), queryFn: () => getChargesPreview(id), enabled: !!a && canInvoiceRead && a.status !== 'CANCELLED' })
  const patientId = a?.patientId ?? ''
  const invoices = useQuery({ queryKey: billingKeys.forPatient(patientId), queryFn: () => listInvoices({ patientId, size: 100 }), enabled: !!a && canInvoiceRead })
  const live = (invoices.data?.items ?? []).filter((i) => i.admissionId === id && i.status !== 'DISCARDED' && i.status !== 'CANCELLED')
  const finalInvoice = live.find((i) => i.kind === 'FINAL')

  const reload = () => {
    void queryClient.invalidateQueries({ queryKey: ipdKeys.admission(id) })
    void queryClient.invalidateQueries({ queryKey: ipdKeys.admissions })
    refreshAvailability()
  }
  const refreshDetail = () => { void queryClient.invalidateQueries({ queryKey: ['ipd', 'admission', id] }); refreshAvailability() }
  const createInvoice = useMutation({
    mutationFn: (kind: 'INTERIM' | 'FINAL') => createIpdInvoice({ patientId, admissionId: id, kind }),
    onSuccess: (inv) => { void queryClient.invalidateQueries({ queryKey: billingKeys.all }); navigate(`/invoices/${inv.id}`) },
    onError: () => { void queryClient.invalidateQueries({ queryKey: billingKeys.forPatient(patientId) }) },
  })
  const cancel = useMutation({
    mutationFn: () => cancelAdmission(id, a!.version),
    onSuccess: () => { setConfirmCancel(false); reload() },
  })

  if (admissionQuery.isPending) return <p role="status">Loading...</p>
  if (admissionQuery.isError || !a) return <p role="alert" className="text-red-700">{ipdErrorMessage(admissionQuery.error)}</p>

  return (
    <section>
      <h1 className="mb-1 text-2xl font-semibold">Admission {a.admissionNo}</h1>
      <p className="mb-4 flex flex-wrap items-center gap-3 text-sm">
        <span className="rounded bg-slate-200 px-2 py-0.5 text-xs">{ADMISSION_STATUS_LABELS[a.status]}</span>
        <span><strong>{a.patient.name ?? 'Patient'}</strong> {a.patient.uhid}</span>
        <span>Admitted {formatDateTime(a.admittedAt, timeZone)}</span>
        {a.plannedDischargeAt && <span>Planned discharge {formatDateTime(a.plannedDischargeAt, timeZone)}</span>}
        {a.dischargedAt && <span>Discharged {formatDateTime(a.dischargedAt, timeZone)}</span>}
      </p>
      <Section title="Current bed">
        <p className="mb-2" data-testid="current-bed">{a.currentBed ? `${a.currentBed.ward} — ${a.currentBed.bedNo}` : 'No bed assigned'}</p>
        {open && canUpdate ? <DiagnosisEditor key={a.version} admission={a} onDone={reload} /> : <p className="text-sm">Admission diagnosis: {a.admissionDiagnosis}</p>}
        <div className="mt-3 flex flex-wrap gap-2">
          {open && canAssign && <button type="button" className={secondaryButton} onClick={() => setDialog('transfer')}>Transfer bed</button>}
          {a.status === 'ADMITTED' && canCancel && !confirmCancel && <button type="button" className={secondaryButton} onClick={() => setConfirmCancel(true)}>Cancel admission</button>}
          {confirmCancel && (
            <>
              <button type="button" className={primaryButton} disabled={cancel.isPending} onClick={() => cancel.mutate()}>Confirm cancel admission</button>
              <button type="button" className={secondaryButton} onClick={() => setConfirmCancel(false)}>Keep admission</button>
            </>
          )}
        </div>
        <div className="mt-2"><IpdProblem error={cancel.error} what="admission" onReload={reload} /></div>
        <h3 className="mb-1 mt-4 font-medium">Bed history</h3>
        {assignments.isError && <p role="alert" className="text-red-700">{ipdErrorMessage(assignments.error)}</p>}
        <table className="w-full text-left text-sm">
          <caption className="sr-only">Bed history</caption>
          <thead><tr className="border-b"><th className="py-1">Ward</th><th>Bed</th><th>From</th><th>To</th><th>Reason</th></tr></thead>
          <tbody>
            {assignments.data?.map((b) => (
              <tr key={b.id} className="border-b">
                <td className="py-1">{b.wardName}{b.criticalCare ? ' (critical care)' : ''}</td>
                <td>{b.bedNo}</td>
                <td>{formatDateTime(b.assignedAt, timeZone)}</td>
                <td>{b.releasedAt ? formatDateTime(b.releasedAt, timeZone) : 'Current'}</td>
                <td>{b.reason ?? '—'}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </Section>

      <Section title="Discharge summary">
        <SummaryForm admission={a} timeZone={timeZone} canWrite={canWriteSummary} />
      </Section>

      {canInvoiceRead && a.status !== 'CANCELLED' && (
        <Section title="Charges preview">
          {charges.isError && <p role="alert" className="text-red-700">{ipdErrorMessage(charges.error)}</p>}
          {charges.data && (
            <>
              <table className="mb-2 w-full text-left text-sm">
                <caption className="sr-only">Room charges</caption>
                <thead><tr className="border-b"><th className="py-1">Description</th><th>Days</th><th className="text-right">Rate</th><th className="text-right">Amount</th><th className="text-right">GST %</th><th className="text-right">GST</th></tr></thead>
                <tbody>
                  {charges.data.lines.map((l, i) => (
                    <tr key={i} className="border-b">
                      <td className="py-1">{l.description}</td><td>{l.quantity}</td>
                      <td className="text-right tabular-nums">{l.unitPrice}</td><td className="text-right tabular-nums">{l.amount}</td>
                      <td className="text-right tabular-nums">{l.taxRate}</td><td className="text-right tabular-nums">{l.tax}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
              <p className="text-sm font-semibold">Room total (with GST): <span data-testid="room-total" className="tabular-nums">{charges.data.roomTotal}</span></p>
            </>
          )}
        </Section>
      )}

      {canInvoiceRead && (
        <Section title="Invoices">
          {live.length === 0 && <p className="mb-2 text-sm text-slate-600">No invoices for this admission yet.</p>}
          <ul className="mb-3 space-y-1">
            {live.map((i) => (
              <li key={i.id} className="text-sm">
                <Link className="text-blue-700 underline" to={`/invoices/${i.id}`}>{i.kind === 'FINAL' ? 'Final' : 'Interim'} invoice {i.invoiceNo ?? '(draft)'}</Link>
                <span className="ml-2 rounded bg-slate-200 px-2 py-0.5 text-xs">{INVOICE_STATUS_LABELS[i.status]}</span>
              </li>
            ))}
          </ul>
          {open && canInvoiceCreate && (
            <div className="flex gap-2">
              <button type="button" className={secondaryButton} disabled={createInvoice.isPending} onClick={() => createInvoice.mutate('INTERIM')}>Create interim invoice</button>
              {a.status === 'DISCHARGE_INITIATED' && !finalInvoice && (
                <button type="button" className={secondaryButton} disabled={createInvoice.isPending} onClick={() => createInvoice.mutate('FINAL')}>Create final invoice</button>
              )}
            </div>
          )}
          <div className="mt-2"><IpdProblem error={createInvoice.error} what="admission" onReload={refreshDetail} /></div>
        </Section>
      )}

      {a.status === 'DISCHARGE_INITIATED' && canComplete && (
        <button type="button" className={primaryButton} onClick={() => setDialog('discharge')}>Discharge patient</button>
      )}

      {dialog === 'transfer' && <TransferDialog admission={a} onClose={() => setDialog(null)} onDone={refreshDetail} />}
      {dialog === 'discharge' && <DischargeDialog admission={a} finalInvoice={finalInvoice} timeZone={timeZone} onClose={() => setDialog(null)} onDone={reload} />}
    </section>
  )
}
