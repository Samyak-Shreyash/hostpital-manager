import { zodResolver } from '@hookform/resolvers/zod'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { useFieldArray, useForm } from 'react-hook-form'
import { ApiError } from '../../api/client'
import { Field, inputClass, primaryButton, secondaryButton } from '../../components/ui'
import { getDischargeSummary, ipdKeys, saveDischargeSummary, type Admission, type DischargeSummary } from './api'
import { summarySchema, type SummaryValues } from './schemas'
import { instantToLocal, IpdProblem, ipdErrorMessage, localToInstant } from './shared'

const EMPTY: SummaryValues = {
  finalDiagnosis: '', courseInHospital: '', procedures: '', conditionAtDischarge: '', followUp: '', plannedDischargeAt: '', medications: [],
}

function toValues(s: DischargeSummary | null, timeZone: string): SummaryValues {
  if (!s) return EMPTY
  return {
    finalDiagnosis: s.finalDiagnosis, courseInHospital: s.courseInHospital ?? '', procedures: s.procedures ?? '',
    conditionAtDischarge: s.conditionAtDischarge, followUp: s.followUp ?? '', plannedDischargeAt: instantToLocal(s.plannedDischargeAt, timeZone),
    medications: s.medications.map((m) => ({ name: m.name, dose: m.dose ?? '', frequency: m.frequency ?? '', duration: m.duration ?? '' })),
  }
}

function Form({ admission, summary, timeZone, canWrite, saved, onSaved }: {
  admission: Admission; summary: DischargeSummary | null; timeZone: string; canWrite: boolean; saved: boolean; onSaved: () => void
}) {
  const queryClient = useQueryClient()
  const editable = canWrite && (admission.status === 'ADMITTED' || admission.status === 'DISCHARGE_INITIATED')
  const form = useForm<SummaryValues>({ resolver: zodResolver(summarySchema), defaultValues: toValues(summary, timeZone) })
  const meds = useFieldArray({ control: form.control, name: 'medications' })
  const reload = () => {
    void queryClient.invalidateQueries({ queryKey: ipdKeys.summary(admission.id) })
    void queryClient.invalidateQueries({ queryKey: ipdKeys.admission(admission.id) })
  }
  const save = useMutation({
    mutationFn: (v: SummaryValues) => saveDischargeSummary(admission.id, {
      ...(summary ? { version: summary.version } : {}),
      finalDiagnosis: v.finalDiagnosis,
      ...(v.courseInHospital ? { courseInHospital: v.courseInHospital } : {}),
      ...(v.procedures ? { procedures: v.procedures } : {}),
      conditionAtDischarge: v.conditionAtDischarge,
      ...(v.followUp ? { followUp: v.followUp } : {}),
      plannedDischargeAt: localToInstant(v.plannedDischargeAt, timeZone),
      medications: v.medications.map((m) => ({
        name: m.name, ...(m.dose ? { dose: m.dose } : {}), ...(m.frequency ? { frequency: m.frequency } : {}), ...(m.duration ? { duration: m.duration } : {}),
      })),
    }),
    onSuccess: () => { onSaved(); reload() },
  })
  const { errors } = form.formState
  return (
    <form noValidate onSubmit={form.handleSubmit((v) => save.mutate(v))} aria-label="Discharge summary">
      <fieldset disabled={!editable}>
        <Field label="Final diagnosis" htmlFor="sum-final" error={errors.finalDiagnosis?.message}>
          <textarea id="sum-final" rows={2} className={inputClass} {...form.register('finalDiagnosis')} />
        </Field>
        <Field label="Course in hospital" htmlFor="sum-course" error={errors.courseInHospital?.message}>
          <textarea id="sum-course" rows={2} className={inputClass} {...form.register('courseInHospital')} />
        </Field>
        <Field label="Procedures" htmlFor="sum-procedures" error={errors.procedures?.message}>
          <textarea id="sum-procedures" rows={2} className={inputClass} {...form.register('procedures')} />
        </Field>
        <Field label="Condition at discharge" htmlFor="sum-condition" error={errors.conditionAtDischarge?.message}>
          <textarea id="sum-condition" rows={2} className={inputClass} {...form.register('conditionAtDischarge')} />
        </Field>
        <Field label="Follow-up" htmlFor="sum-follow" error={errors.followUp?.message}>
          <textarea id="sum-follow" rows={2} className={inputClass} {...form.register('followUp')} />
        </Field>
        <Field label="Planned discharge" htmlFor="sum-planned" error={errors.plannedDischargeAt?.message}>
          <input id="sum-planned" type="datetime-local" className={inputClass} {...form.register('plannedDischargeAt')} />
        </Field>
        <h3 className="mb-2 font-medium">Medicines at discharge</h3>
        {meds.fields.map((f, i) => (
          <div key={f.id} className="mb-2 grid grid-cols-2 gap-2 sm:grid-cols-5">
            <div>
              <input aria-label={`Medicine ${i + 1} name`} placeholder="Name" className={inputClass} {...form.register(`medications.${i}.name`)} />
              {errors.medications?.[i]?.name && <p role="alert" className="text-sm text-red-700">{errors.medications[i]?.name?.message}</p>}
            </div>
            <input aria-label={`Medicine ${i + 1} dose`} placeholder="Dose" className={inputClass} {...form.register(`medications.${i}.dose`)} />
            <input aria-label={`Medicine ${i + 1} frequency`} placeholder="Frequency" className={inputClass} {...form.register(`medications.${i}.frequency`)} />
            <input aria-label={`Medicine ${i + 1} duration`} placeholder="Duration" className={inputClass} {...form.register(`medications.${i}.duration`)} />
            <button type="button" className={secondaryButton} onClick={() => meds.remove(i)}>Remove medicine {i + 1}</button>
          </div>
        ))}
        <button type="button" className={`${secondaryButton} mb-4`} onClick={() => meds.append({ name: '', dose: '', frequency: '', duration: '' })}>Add medicine</button>
      </fieldset>
      <IpdProblem error={save.error} what="summary" onReload={reload} />
      {saved && !save.isError && <p role="status" className="mb-2 text-sm text-green-800">Summary saved.</p>}
      {editable && <button type="submit" className={primaryButton} disabled={save.isPending}>Save summary</button>}
    </form>
  )
}

/** The discharge summary ([E8-7]): the first save moves the admission to DISCHARGE_INITIATED. */
export function SummaryForm({ admission, timeZone, canWrite }: { admission: Admission; timeZone: string; canWrite: boolean }) {
  const [saved, setSaved] = useState(false)
  const summary = useQuery({
    queryKey: ipdKeys.summary(admission.id),
    queryFn: async () => {
      try { return await getDischargeSummary(admission.id) } catch (e) {
        if (e instanceof ApiError && e.status === 404) return null
        throw e
      }
    },
  })
  if (summary.isPending) return <p role="status">Loading...</p>
  if (summary.isError) return <p role="alert" className="text-red-700">{ipdErrorMessage(summary.error)}</p>
  return <Form key={summary.data?.version ?? 'new'} admission={admission} summary={summary.data} timeZone={timeZone} canWrite={canWrite} saved={saved} onSaved={() => setSaved(true)} />
}
