import { zodResolver } from '@hookform/resolvers/zod'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { useForm, useWatch } from 'react-hook-form'
import { useNavigate } from 'react-router'
import type { PatientSummary } from '../../api/patients'
import { Field, inputClass, primaryButton } from '../../components/ui'
import { DoctorSelect, PatientPicker } from '../appointments/shared'
import { admitPatient, ipdKeys } from './api'
import { admitSchema, type AdmitValues } from './schemas'
import { BedSelect, IpdProblem, isBedTaken, useRefreshAvailability } from './shared'

/** Admit a patient to an available bed ([E8-8]). A bed taken meanwhile refreshes the list and asks for another. */
export function AdmitPage() {
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const refreshAvailability = useRefreshAvailability()
  const [patient, setPatient] = useState<PatientSummary | null>(null)
  const form = useForm<AdmitValues>({
    resolver: zodResolver(admitSchema),
    defaultValues: { patientId: '', admittingDoctorId: '', bedId: '', admissionDiagnosis: '' },
  })
  const admit = useMutation({
    mutationFn: (v: AdmitValues) => admitPatient({ ...v, admissionDiagnosis: v.admissionDiagnosis.trim() }),
    onSuccess: (a) => {
      void queryClient.invalidateQueries({ queryKey: ipdKeys.admissions })
      refreshAvailability()
      navigate(`/admissions/${a.id}`)
    },
    onError: (error) => {
      if (isBedTaken(error)) { form.setValue('bedId', ''); refreshAvailability() }
    },
  })
  const { errors } = form.formState
  const admittingDoctorIdValue = useWatch({ control: form.control, name: 'admittingDoctorId' })
  const bedIdValue = useWatch({ control: form.control, name: 'bedId' })
  return (
    <section className="max-w-xl">
      <h1 className="mb-4 text-2xl font-semibold">Admit patient</h1>
      <form noValidate onSubmit={form.handleSubmit((v) => admit.mutate(v))}>
        <PatientPicker selected={patient} error={errors.patientId?.message} onSelect={(p) => { setPatient(p); form.setValue('patientId', p.id, { shouldValidate: true }) }} />
        <div className="mb-4">
          <DoctorSelect value={admittingDoctorIdValue} onChange={(id) => form.setValue('admittingDoctorId', id, { shouldValidate: true })} />
          {errors.admittingDoctorId && <p role="alert" className="mt-1 text-sm text-danger">{errors.admittingDoctorId.message}</p>}
        </div>
        <BedSelect id="admit-bed" value={bedIdValue} onChange={(id) => form.setValue('bedId', id, { shouldValidate: true })} error={errors.bedId?.message} />
        <Field label="Admission diagnosis" htmlFor="admit-diagnosis" error={errors.admissionDiagnosis?.message}>
          <textarea id="admit-diagnosis" rows={3} className={inputClass} {...form.register('admissionDiagnosis')} />
        </Field>
        <IpdProblem error={admit.error} what="record" onReload={refreshAvailability} />
        <button type="submit" className={primaryButton} disabled={admit.isPending}>Admit</button>
      </form>
    </section>
  )
}
