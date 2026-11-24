import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { ApiError, type FieldError } from '../../api/client'
import {
  clinicalKeys, createPrescription, getPrescription, issuePrescription, prescriptionPdfUrl, putPrescriptionItems,
  revisePrescription, type Encounter, type Prescription, type PrescriptionItem,
} from '../../api/clinical'
import { FormError, inputClass, primaryButton, secondaryButton } from '../../components/ui'
import { errorMessage } from '../auth/messages'
import { MutationProblem } from '../appointments/BookAppointmentDialog'
import { useCan } from '../auth/useSession'
import { consultErrorMessage, Section } from './shared'

interface ItemDraft {
  drugName: string; strength: string; form: string; route: string; dose: string; frequency: string; durationDays: string; instructions: string
}
const EMPTY: ItemDraft = { drugName: '', strength: '', form: '', route: '', dose: '', frequency: '', durationDays: '', instructions: '' }

const toDraft = (i: PrescriptionItem): ItemDraft => ({
  drugName: i.drugName, strength: i.strength ?? '', form: i.form ?? '', route: i.route ?? '', dose: i.dose, frequency: i.frequency,
  durationDays: String(i.durationDays), instructions: i.instructions ?? '',
})
const blank = (v: string) => (v.trim() === '' ? null : v.trim())
const toItem = (d: ItemDraft): PrescriptionItem => ({
  drugName: d.drugName.trim(), strength: blank(d.strength), form: blank(d.form), route: blank(d.route), dose: d.dose.trim(),
  frequency: d.frequency.trim(), durationDays: Number(d.durationDays), instructions: blank(d.instructions),
})

const FIELDS: { key: keyof ItemDraft; label: string; wide?: boolean; placeholder?: string }[] = [
  { key: 'drugName', label: 'Drug name', wide: true }, { key: 'strength', label: 'Strength' }, { key: 'form', label: 'Form' },
  { key: 'route', label: 'Route' }, { key: 'dose', label: 'Dose' }, { key: 'frequency', label: 'Frequency', placeholder: '1-0-1 or SOS' },
  { key: 'durationDays', label: 'Duration (days)' }, { key: 'instructions', label: 'Instructions', wide: true },
]

/** Server field errors (`items[2].dose`) by row and field. */
function itemErrors(error: unknown): { byField: Record<string, string>; other: string | null } {
  const byField: Record<string, string> = {}
  if (!(error instanceof ApiError) || error.status !== 422) return { byField, other: error ? consultErrorMessage(error) : null }
  const unmatched: FieldError[] = []
  for (const e of error.fieldErrors) {
    const m = /^items\[(\d+)\]\.(\w+)$/.exec(e.field)
    if (m) byField[`${m[1]}.${m[2]}`] = e.message
    else unmatched.push(e)
  }
  const other = error.fieldErrors.length === 0 ? errorMessage(error) : unmatched[0]?.message ?? null
  return { byField, other }
}

export function PrescriptionSection({ encounter, onReload }: { encounter: Encounter; onReload: () => void }) {
  const queryClient = useQueryClient()
  const canCreate = useCan('prescription:create')
  const canRead = useCan('prescription:read')
  // The server lists the encounter's prescriptions (newest first); the live one (DRAFT or ISSUED) is the one to work on.
  const rxId = (encounter.prescriptions ?? []).find((p) => p.status !== 'SUPERSEDED')?.id ?? null
  const rxQuery = useQuery({
    queryKey: clinicalKeys.prescription(rxId ?? ''), queryFn: () => getPrescription(rxId as string), enabled: !!rxId && canRead, staleTime: 30_000,
  })
  const encounterKey = clinicalKeys.encounter(encounter.id)
  const adopt = (rx: Prescription) => {
    queryClient.setQueryData(clinicalKeys.prescription(rx.id), rx)
    // Keep the encounter's summary in step until the next read: this one first, the one it replaced marked superseded.
    queryClient.setQueryData<Encounter>(encounterKey, (e) => e && ({
      ...e,
      prescriptions: [{ id: rx.id, status: rx.status, versionNo: rx.versionNo },
        ...(e.prescriptions ?? []).filter((p) => p.id !== rx.id).map((p) => (p.id === rx.supersedesId ? { ...p, status: 'SUPERSEDED' as const } : p))],
    }))
  }
  const refresh = () => { void queryClient.invalidateQueries({ queryKey: encounterKey }) }
  const start = useMutation({
    mutationFn: () => createPrescription(encounter.id),
    onSuccess: (rx) => { adopt(rx); refresh() },
    // One live prescription per encounter: someone (or another tab) already made it, so read it from the server.
    onError: (error) => { if (error instanceof ApiError && error.status === 409) refresh() },
  })
  const rx = rxQuery.data

  return (
    <Section title="Prescription">
      {rxId && rxQuery.isPending && canRead && <p role="status">Loading prescription...</p>}
      {rxQuery.isError && <FormError message={consultErrorMessage(rxQuery.error)} />}
      {rx && <PrescriptionEditor key={rx.id} rx={rx} onChange={adopt} onRevised={refresh} onReload={onReload} />}
      {!rx && !rxId && canCreate && (
        <>
          <p className="mb-2 text-sm text-ink-muted">No prescription started for this visit.</p>
          <button type="button" className={primaryButton} disabled={start.isPending} onClick={() => start.mutate()}>Start prescription</button>
        </>
      )}
      {!rx && !rxId && !canCreate && <p className="text-sm text-ink-muted">No prescription.</p>}
      {start.error instanceof ApiError && start.error.status === 409
        ? <FormError message="A prescription already exists for this visit. It is being loaded." />
        : <MutationProblem error={start.error} what="prescription" onReload={onReload} />}
    </Section>
  )
}

const STATUS_TEXT = { DRAFT: 'Draft', ISSUED: 'Issued', SUPERSEDED: 'Superseded' } as const

function PrescriptionEditor({ rx, onChange, onRevised, onReload }: {
  rx: Prescription; onChange: (rx: Prescription) => void; onRevised: () => void; onReload: () => void
}) {
  const canCreate = useCan('prescription:create')
  const canIssue = useCan('prescription:issue')
  const canRead = useCan('prescription:read')
  const [items, setItems] = useState<ItemDraft[]>(() => rx.items.map(toDraft))
  const [durationError, setDurationError] = useState<Record<number, string>>({})
  const [acknowledged, setAcknowledged] = useState(false)
  const isDraft = rx.status === 'DRAFT'
  const editable = isDraft && canCreate
  const dirty = JSON.stringify(items.map(toItem)) !== JSON.stringify(rx.items.map((i) => toItem(toDraft(i))))
  const warnings = rx.allergyWarnings

  const save = useMutation({ mutationFn: (body: PrescriptionItem[]) => putPrescriptionItems(rx.id, rx.version, body), onSuccess: (r) => { setAcknowledged(false); onChange(r) } })
  const issue = useMutation({ mutationFn: () => issuePrescription(rx.id, rx.version, acknowledged), onSuccess: onChange })
  const revise = useMutation({ mutationFn: () => revisePrescription(rx.id), onSuccess: (r) => { onChange(r); onRevised() } })
  const saveErrors = itemErrors(save.error)

  const update = (i: number, key: keyof ItemDraft, value: string) => setItems(items.map((d, j) => (j === i ? { ...d, [key]: value } : d)))
  const submit = () => {
    const next: Record<number, string> = {}
    items.forEach((d, i) => { if (d.durationDays.trim() !== '' && !/^\d{1,3}$/.test(d.durationDays.trim())) next[i] = 'Enter whole days' })
    setDurationError(next)
    if (Object.keys(next).length === 0) save.mutate(items.map(toItem))
  }

  return (
    <div>
      <p className="mb-3 flex flex-wrap items-center gap-3 text-sm">
        <span className="rounded bg-muted px-2 py-0.5 text-xs">{STATUS_TEXT[rx.status]}</span>
        <span>Version {rx.versionNo}</span>
      </p>
      <fieldset disabled={!editable}>
        {items.length === 0 && <p className="mb-2 text-sm text-ink-muted">No medicines yet.</p>}
        {items.map((d, i) => (
          <div key={i} className="mb-3 rounded border border-border p-3">
            <div className="grid grid-cols-2 gap-2 text-sm sm:grid-cols-4">
              {FIELDS.map((f) => {
                const id = `rx-${i}-${f.key}`
                const error = saveErrors.byField[`${i}.${f.key}`] ?? (f.key === 'durationDays' ? durationError[i] : undefined)
                return (
                  <div key={f.key} className={f.wide ? 'col-span-2' : undefined}>
                    <label htmlFor={id} className="block font-medium">{`${f.label} ${i + 1}`}</label>
                    <input id={id} className={inputClass} inputMode={f.key === 'durationDays' ? 'numeric' : undefined} placeholder={f.placeholder} value={d[f.key]} onChange={(e) => update(i, f.key, e.target.value)} />
                    {error && <p role="alert" className="mt-1 text-danger">{error}</p>}
                  </div>
                )
              })}
            </div>
            {editable && (
              <button type="button" className={`${secondaryButton} mt-2`} aria-label={`Remove medicine ${i + 1}`} onClick={() => setItems(items.filter((_, j) => j !== i))}>Remove</button>
            )}
          </div>
        ))}
      </fieldset>
      {editable && items.length < 50 && <button type="button" className={`${secondaryButton} mb-3`} onClick={() => setItems([...items, { ...EMPTY }])}>Add medicine</button>}
      {saveErrors.other && !(save.error instanceof ApiError && save.error.code === 'CONCURRENT_MODIFICATION') && <FormError message={saveErrors.other} />}
      {save.error instanceof ApiError && save.error.code === 'CONCURRENT_MODIFICATION' && <MutationProblem error={save.error} what="prescription" onReload={onReload} />}
      {editable && <div><button type="button" className={primaryButton} disabled={save.isPending} onClick={submit}>Save medicines</button></div>}

      {warnings.length > 0 && isDraft && (
        <div role="alert" aria-label="Allergy warnings" className="mt-3 rounded bg-danger-soft p-3 text-sm text-danger">
          <p className="font-medium">This patient has recorded allergies that match these medicines</p>
          <ul className="list-disc pl-5">{warnings.map((w, i) => <li key={`${w.substance}-${i}`}>{w.substance} ({w.severity})</li>)}</ul>
          {canIssue && (
            <label className="mt-2 flex items-center gap-2">
              <input type="checkbox" checked={acknowledged} onChange={(e) => setAcknowledged(e.target.checked)} /> I have reviewed these allergy warnings
            </label>
          )}
        </div>
      )}
      {isDraft && canIssue && (
        <div className="mt-3">
          <button type="button" className={primaryButton} disabled={issue.isPending || dirty || items.length === 0 || (warnings.length > 0 && !acknowledged)} onClick={() => issue.mutate()}>Issue prescription</button>
          {dirty && <p className="mt-1 text-sm text-ink-muted">Save the medicines before issuing.</p>}
          <MutationProblem error={issue.error} what="prescription" onReload={onReload} />
        </div>
      )}
      {!isDraft && (
        <div className="mt-3 flex flex-wrap gap-2">
          {canRead && rx.status === 'ISSUED' && (
            <a className={secondaryButton} href={prescriptionPdfUrl(rx.id)} target="_blank" rel="noreferrer">Open prescription PDF</a>
          )}
          {canIssue && rx.status === 'ISSUED' && (
            <button type="button" className={secondaryButton} disabled={revise.isPending} onClick={() => revise.mutate()}>Revise prescription</button>
          )}
          <MutationProblem error={revise.error} what="prescription" onReload={onReload} />
        </div>
      )}
    </div>
  )
}
