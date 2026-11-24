import { useState } from 'react'
import { putDiagnoses, putFollowUp, putNote, putObservations, putTreatmentPlan } from '../../api/clinical'
import { inputClass, primaryButton, secondaryButton } from '../../components/ui'
import { useCan } from '../auth/useSession'
import { ConsultProblem, currentNote, Section, useEncounterSave, type SectionProps } from './shared'

const VITALS = [
  { kind: 'BP_SYS', label: 'Systolic BP', unit: 'mmHg' },
  { kind: 'BP_DIA', label: 'Diastolic BP', unit: 'mmHg' },
  { kind: 'PULSE', label: 'Pulse', unit: 'bpm' },
  { kind: 'TEMP_C', label: 'Temperature', unit: '°C' },
  { kind: 'SPO2', label: 'SpO2', unit: '%' },
  { kind: 'RESP_RATE', label: 'Respiratory rate', unit: '/min' },
  { kind: 'WEIGHT_KG', label: 'Weight', unit: 'kg' },
  { kind: 'HEIGHT_CM', label: 'Height', unit: 'cm' },
] as const

const NUMBER = /^\d{1,3}(\.\d{1,2})?$/

/** Vitals. Values outside the plausible range come back as non-blocking warnings; hard limits are a 422 message. */
export function VitalsSection(props: SectionProps) {
  const { encounter } = props
  const canWrite = useCan('observation:write') && encounter.status === 'OPEN'
  const [values, setValues] = useState<Record<string, string>>(() =>
    Object.fromEntries(encounter.observations.map((o) => [o.kind, String(o.value)])))
  const [errors, setErrors] = useState<Record<string, string>>({})
  const save = useEncounterSave(props, (body: { kind: string; value: number; unit: string }[]) =>
    putObservations(encounter.id, encounter.version, body))

  const submit = () => {
    const next: Record<string, string> = {}
    const body: { kind: string; value: number; unit: string }[] = []
    for (const v of VITALS) {
      const raw = (values[v.kind] ?? '').trim()
      if (!raw) continue
      if (!NUMBER.test(raw)) next[v.kind] = 'Enter a number with at most 2 decimals'
      else body.push({ kind: v.kind, value: Number(raw), unit: v.unit })
    }
    setErrors(next)
    if (Object.keys(next).length === 0) save.mutate(body)
  }

  return (
    <Section title="Vitals">
      <fieldset disabled={!canWrite}>
        <div className="grid grid-cols-2 gap-3 sm:grid-cols-4">
          {VITALS.map((v) => {
            const id = `vital-${v.kind}`
            return (
              <div key={v.kind} className="text-sm">
                <label htmlFor={id} className="block font-medium">{`${v.label} (${v.unit})`}</label>
                <input id={id} inputMode="decimal" className={inputClass} value={values[v.kind] ?? ''}
                  onChange={(e) => setValues({ ...values, [v.kind]: e.target.value })} />
                {errors[v.kind] && <p role="alert" className="mt-1 text-danger">{errors[v.kind]}</p>}
              </div>
            )
          })}
        </div>
      </fieldset>
      {encounter.warnings.length > 0 && (
        <div role="status" aria-label="Vitals warnings" className="mt-3 rounded bg-warning-soft p-3 text-sm">
          <p className="font-medium">Check these values (saved as entered)</p>
          <ul className="list-disc pl-5">{encounter.warnings.map((w, i) => <li key={`${w.kind}-${i}`}>{w.message}</li>)}</ul>
        </div>
      )}
      <ConsultProblem error={save.error} onReload={props.onReload} />
      {canWrite && (
        <button type="button" className={`${primaryButton} mt-3`} disabled={save.isPending} onClick={submit}>Save vitals</button>
      )}
    </Section>
  )
}

const NOTE_KINDS = [
  { kind: 'SYMPTOMS', label: 'Symptoms' },
  { kind: 'EXAMINATION', label: 'Examination' },
  { kind: 'ASSESSMENT', label: 'Assessment' },
] as const

/** One Save for the three notes: only changed, non-empty ones are written, each with the version the previous save returned. */
export function NotesSection(props: SectionProps) {
  const { encounter } = props
  const canEdit = useCan('encounter:update') && encounter.status === 'OPEN'
  const initial = Object.fromEntries(NOTE_KINDS.map((n) => [n.kind, currentNote(encounter, n.kind)]))
  const [texts, setTexts] = useState<Record<string, string>>(initial)
  const changed = NOTE_KINDS.filter((n) => texts[n.kind].trim() !== '' && texts[n.kind] !== initial[n.kind])
  const save = useEncounterSave(props, async () => {
    let version = encounter.version
    let last = encounter
    for (const n of changed) {
      last = await putNote(encounter.id, n.kind, version, texts[n.kind])
      version = last.version
    }
    return last
  })
  return (
    <Section title="Clinical notes">
      <fieldset disabled={!canEdit}>
        {NOTE_KINDS.map((n) => (
          <div key={n.kind} className="mb-3 text-sm">
            <label htmlFor={`note-${n.kind}`} className="block font-medium">{n.label}</label>
            <textarea id={`note-${n.kind}`} rows={3} maxLength={10000} className={inputClass} value={texts[n.kind]}
              onChange={(e) => setTexts({ ...texts, [n.kind]: e.target.value })} />
          </div>
        ))}
      </fieldset>
      <ConsultProblem error={save.error} onReload={props.onReload} />
      {canEdit && <button type="button" className={primaryButton} disabled={save.isPending || changed.length === 0} onClick={() => save.mutate(undefined)}>Save notes</button>}
    </Section>
  )
}

interface DiagnosisDraft { code: string; description: string; primary: boolean }

export function DiagnosesSection(props: SectionProps) {
  const { encounter } = props
  const canEdit = useCan('encounter:update') && encounter.status === 'OPEN'
  const [rows, setRows] = useState<DiagnosisDraft[]>(() =>
    encounter.diagnoses.map((d) => ({ code: d.code ?? '', description: d.description, primary: d.primary })))
  const [errors, setErrors] = useState<Record<number, string>>({})
  const save = useEncounterSave(props, (body: { code?: string; description: string; primary: boolean }[]) =>
    putDiagnoses(encounter.id, encounter.version, body))
  const update = (i: number, patch: Partial<DiagnosisDraft>) => setRows(rows.map((r, j) => (j === i ? { ...r, ...patch } : r)))

  const submit = () => {
    const next: Record<number, string> = {}
    rows.forEach((r, i) => { if (!r.description.trim()) next[i] = `Describe diagnosis ${i + 1}` })
    setErrors(next)
    if (Object.keys(next).length > 0) return
    save.mutate(rows.map((r) => ({ ...(r.code.trim() ? { code: r.code.trim() } : {}), description: r.description.trim(), primary: r.primary })))
  }

  return (
    <Section title="Diagnoses">
      <fieldset disabled={!canEdit}>
        {rows.length === 0 && <p className="mb-2 text-sm text-ink-muted">No diagnoses recorded.</p>}
        {rows.map((r, i) => (
          <div key={i} className="mb-3 grid grid-cols-1 gap-2 text-sm sm:grid-cols-[8rem_1fr_auto_auto] sm:items-end">
            <div>
              <label htmlFor={`dx-code-${i}`} className="block font-medium">{`Diagnosis code ${i + 1}`}</label>
              <input id={`dx-code-${i}`} className={inputClass} maxLength={20} value={r.code} onChange={(e) => update(i, { code: e.target.value })} />
            </div>
            <div>
              <label htmlFor={`dx-desc-${i}`} className="block font-medium">{`Diagnosis description ${i + 1}`}</label>
              <input id={`dx-desc-${i}`} className={inputClass} maxLength={500} value={r.description} onChange={(e) => update(i, { description: e.target.value })} />
              {errors[i] && <p role="alert" className="mt-1 text-danger">{errors[i]}</p>}
            </div>
            <label className="flex items-center gap-1 pb-2">
              <input type="checkbox" aria-label={`Primary diagnosis ${i + 1}`} checked={r.primary} onChange={(e) => update(i, { primary: e.target.checked })} /> Primary
            </label>
            <button type="button" className={secondaryButton} aria-label={`Remove diagnosis ${i + 1}`} onClick={() => setRows(rows.filter((_, j) => j !== i))}>Remove</button>
          </div>
        ))}
        {canEdit && rows.length < 20 && (
          <button type="button" className={`${secondaryButton} mb-2`} onClick={() => setRows([...rows, { code: '', description: '', primary: false }])}>Add diagnosis</button>
        )}
      </fieldset>
      <ConsultProblem error={save.error} onReload={props.onReload} />
      {canEdit && <div><button type="button" className={primaryButton} disabled={save.isPending} onClick={submit}>Save diagnoses</button></div>}
    </Section>
  )
}

/** Treatment plan and follow-up are two independent saves (two endpoints). */
export function PlanSection(props: SectionProps) {
  const { encounter } = props
  const canEdit = useCan('encounter:update') && encounter.status === 'OPEN'
  const [plan, setPlan] = useState(encounter.treatmentPlan ?? '')
  const [planError, setPlanError] = useState('')
  const [date, setDate] = useState(encounter.followUp?.date ?? '')
  const [instructions, setInstructions] = useState(encounter.followUp?.instructions ?? '')
  const [dateError, setDateError] = useState('')
  const savePlan = useEncounterSave(props, (text: string) => putTreatmentPlan(encounter.id, encounter.version, text))
  const saveFollowUp = useEncounterSave(props, (v: { date: string; instructions: string }) => putFollowUp(encounter.id, encounter.version, v.date, v.instructions))
  return (
    <Section title="Plan and follow-up">
      <fieldset disabled={!canEdit}>
        <div className="mb-3 text-sm">
          <label htmlFor="plan-text" className="block font-medium">Treatment plan</label>
          <textarea id="plan-text" rows={3} maxLength={10000} className={inputClass} value={plan} onChange={(e) => setPlan(e.target.value)} />
          {planError && <p role="alert" className="mt-1 text-danger">{planError}</p>}
        </div>
      </fieldset>
      <ConsultProblem error={savePlan.error} onReload={props.onReload} />
      {canEdit && (
        <button type="button" className={`${primaryButton} mb-4`} disabled={savePlan.isPending}
          onClick={() => { if (!plan.trim()) setPlanError('Enter the treatment plan'); else { setPlanError(''); savePlan.mutate(plan) } }}>Save plan</button>
      )}
      <fieldset disabled={!canEdit}>
        <div className="grid grid-cols-1 gap-3 text-sm sm:grid-cols-[12rem_1fr]">
          <div>
            <label htmlFor="fu-date" className="block font-medium">Follow-up date</label>
            <input id="fu-date" type="date" className={inputClass} value={date} onChange={(e) => setDate(e.target.value)} />
            {dateError && <p role="alert" className="mt-1 text-danger">{dateError}</p>}
          </div>
          <div>
            <label htmlFor="fu-instructions" className="block font-medium">Follow-up instructions</label>
            <input id="fu-instructions" maxLength={2000} className={inputClass} value={instructions} onChange={(e) => setInstructions(e.target.value)} />
          </div>
        </div>
      </fieldset>
      <ConsultProblem error={saveFollowUp.error} onReload={props.onReload} />
      {canEdit && (
        <button type="button" className={`${primaryButton} mt-3`} disabled={saveFollowUp.isPending}
          onClick={() => { if (!date) setDateError('Choose a follow-up date'); else { setDateError(''); saveFollowUp.mutate({ date, instructions }) } }}>Save follow-up</button>
      )}
    </Section>
  )
}
