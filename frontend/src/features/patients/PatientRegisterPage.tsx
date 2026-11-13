import { zodResolver } from '@hookform/resolvers/zod'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { useForm } from 'react-hook-form'
import { Link, useNavigate } from 'react-router'
import { ApiError } from '../../api/client'
import { patientKeys, registerPatient, type DuplicateCandidate, type RegisterPatientBody } from '../../api/patients'
import { Field, FormError, inputClass, primaryButton } from '../../components/ui'
import { errorMessage } from '../auth/messages'
import { useCan } from '../auth/useSession'
import { AddressFields } from './AddressFields'
import { DuplicateCandidates } from './DuplicateCandidates'
import { registerSchema, toAddress, type RegisterValues } from './schemas'

function toBody(v: RegisterValues): RegisterPatientBody {
  const body: RegisterPatientBody = { fullName: v.fullName, sex: v.sex, phone: v.phone }
  if (v.dateOfBirth) body.dateOfBirth = v.dateOfBirth
  else {
    body.ageYears = Number(v.ageYears)
    body.dobEstimated = true
  }
  const address = toAddress(v)
  if (address) body.address = address
  if (v.abhaNumber) body.abhaNumber = v.abhaNumber
  return body
}

export function PatientRegisterPage() {
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const canOverride = useCan('patient:override-duplicate')
  const [pending, setPending] = useState<RegisterPatientBody | null>(null)
  const [candidates, setCandidates] = useState<DuplicateCandidate[] | null>(null)
  const form = useForm<RegisterValues>({
    resolver: zodResolver(registerSchema),
    defaultValues: { fullName: '', sex: undefined, dateOfBirth: '', ageYears: '', phone: '', abhaNumber: '', line1: '', line2: '', city: '', state: '', pincode: '' },
  })
  const { errors } = form.formState

  const register = useMutation({
    mutationFn: registerPatient,
    onSuccess: (patient) => {
      queryClient.setQueryData(patientKeys.detail(patient.id), patient)
      void queryClient.invalidateQueries({ queryKey: ['patients', 'search'] })
      navigate(`/patients/${encodeURIComponent(patient.id)}`, { replace: true })
    },
    onError: (error) => {
      if (error instanceof ApiError && error.code === 'DUPLICATE_SUSPECTED') {
        setCandidates((error.properties.candidates as DuplicateCandidate[] | undefined) ?? [])
      }
    },
  })
  const apiError = register.error instanceof ApiError ? register.error : null
  const abhaTaken = apiError?.code === 'DUPLICATE_ABHA'
  const generalError = register.isError && apiError?.code !== 'DUPLICATE_SUSPECTED' && !abhaTaken ? errorMessage(register.error) : null


  return (
    <section>
      <h1 className="mb-4 text-2xl font-semibold">Register patient</h1>
      <form
        noValidate
        className="max-w-xl"
        onSubmit={form.handleSubmit((v) => {
          const body = toBody(v)
          setPending(body)
          setCandidates(null)
          register.mutate(body)
        })}
      >
        <FormError message={generalError} />
        <Field label="Full name" htmlFor="fullName" error={errors.fullName?.message}>
          <input id="fullName" className={inputClass} autoComplete="off" {...form.register('fullName')} />
        </Field>
        <Field label="Sex" htmlFor="sex" error={errors.sex?.message}>
          <select id="sex" className={inputClass} {...form.register('sex')}>
            <option value="">Select...</option>
            <option value="F">Female</option>
            <option value="M">Male</option>
            <option value="O">Other</option>
            <option value="U">Unknown</option>
          </select>
        </Field>
        <div className="grid grid-cols-2 gap-3">
          <Field label="Date of birth" htmlFor="dateOfBirth" error={errors.dateOfBirth?.message}>
            <input id="dateOfBirth" type="date" className={inputClass} {...form.register('dateOfBirth')} />
          </Field>
          <Field label="Age in years (if date of birth unknown)" htmlFor="ageYears" error={errors.ageYears?.message}>
            <input id="ageYears" inputMode="numeric" className={inputClass} autoComplete="off" {...form.register('ageYears')} />
          </Field>
        </div>
        <Field label="Phone" htmlFor="phone" error={errors.phone?.message}>
          <input id="phone" type="tel" className={inputClass} autoComplete="off" {...form.register('phone')} />
        </Field>
        <Field
          label="ABHA number (optional)"
          htmlFor="abhaNumber"
          error={errors.abhaNumber?.message ?? (abhaTaken ? errorMessage(register.error) : undefined)}
        >
          <input id="abhaNumber" inputMode="numeric" className={inputClass} autoComplete="off" {...form.register('abhaNumber')} />
        </Field>
        <AddressFields register={form.register} errors={errors} />
        <button type="submit" className={primaryButton} disabled={register.isPending}>Register</button>
        <Link className="ml-4 text-blue-700 underline" to="/patients">Cancel</Link>
      </form>

      {candidates && (
        <DuplicateCandidates
          candidates={candidates}
          canOverride={canOverride}
          submitting={register.isPending}
          actionLabel="Register anyway"
          onOverride={(reason) => pending && register.mutate({ ...pending, confirmNotDuplicate: true, overrideReason: reason })}
        />
      )}
    </section>
  )
}
