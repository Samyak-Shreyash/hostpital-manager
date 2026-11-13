import { zodResolver } from '@hookform/resolvers/zod'
import { useForm } from 'react-hook-form'
import type { Patient, UpdatePatientBody } from '../../api/patients'
import { Field, FormError, inputClass, primaryButton } from '../../components/ui'
import { AddressFields } from './AddressFields'
import { maskedAbha } from './format'
import { toAddress, updateSchema, type UpdateValues } from './schemas'

interface Props {
  patient: Patient
  canEditIdentity: boolean
  submitting?: boolean
  serverError?: string | null
  onSubmit: (body: UpdatePatientBody) => void
}

/** Builds a PATCH with only the changed fields; identity fields (name, sex, birth date) also need a reason. */
export function PatientEditForm({ patient, canEditIdentity, submitting, serverError, onSubmit }: Props) {
  const a = patient.address
  const form = useForm<UpdateValues>({
    resolver: zodResolver(updateSchema),
    defaultValues: {
      fullName: patient.fullName,
      sex: patient.sex,
      dateOfBirth: patient.dateOfBirth ?? '',
      phone: patient.phone,
      abhaNumber: '',
      identityChangeReason: '',
      line1: a?.line1 ?? '',
      line2: a?.line2 ?? '',
      city: a?.city ?? '',
      state: a?.state ?? '',
      pincode: a?.pincode ?? '',
    },
  })
  const { errors } = form.formState

  const submit = (v: UpdateValues) => {
    const body: UpdatePatientBody = { version: patient.version }
    if (canEditIdentity) {
      if (v.fullName !== patient.fullName) body.fullName = v.fullName
      if (v.sex !== patient.sex) body.sex = v.sex
      if (v.dateOfBirth && v.dateOfBirth !== (patient.dateOfBirth ?? '')) body.dateOfBirth = v.dateOfBirth
      if (body.fullName !== undefined || body.sex !== undefined || body.dateOfBirth !== undefined) {
        if (!v.identityChangeReason) {
          form.setError('identityChangeReason', { message: 'A reason is required to change name, sex or date of birth' })
          return
        }
        body.identityChangeReason = v.identityChangeReason
      }
    }
    if (v.phone !== patient.phone) body.phone = v.phone
    if (v.abhaNumber) body.abhaNumber = v.abhaNumber
    const address = toAddress(v)
    if (address && JSON.stringify(address) !== JSON.stringify(toAddress({ line1: a?.line1 ?? '', line2: a?.line2 ?? '', city: a?.city ?? '', state: a?.state ?? '', pincode: a?.pincode ?? '' }))) {
      body.address = address
    }
    onSubmit(body)
  }

  return (
    <form noValidate onSubmit={form.handleSubmit(submit)} className="max-w-xl">
      <h2 className="mb-3 text-lg font-semibold">Edit details</h2>
      <FormError message={serverError} />
      {canEditIdentity && (
        <fieldset className="mb-4 rounded border border-slate-200 p-3">
          <legend className="px-1 text-sm font-medium">Identity</legend>
          <Field label="Full name" htmlFor="edit-fullName" error={errors.fullName?.message}>
            <input id="edit-fullName" className={inputClass} autoComplete="off" {...form.register('fullName')} />
          </Field>
          <Field label="Sex" htmlFor="edit-sex" error={errors.sex?.message}>
            <select id="edit-sex" className={inputClass} {...form.register('sex')}>
              <option value="F">Female</option>
              <option value="M">Male</option>
              <option value="O">Other</option>
              <option value="U">Unknown</option>
            </select>
          </Field>
          <Field label="Date of birth" htmlFor="edit-dateOfBirth" error={errors.dateOfBirth?.message}>
            <input id="edit-dateOfBirth" type="date" className={inputClass} {...form.register('dateOfBirth')} />
          </Field>
          <Field label="Reason for identity change" htmlFor="edit-identityChangeReason" error={errors.identityChangeReason?.message}>
            <textarea id="edit-identityChangeReason" className={inputClass} maxLength={500} {...form.register('identityChangeReason')} />
          </Field>
        </fieldset>
      )}
      <Field label="Phone" htmlFor="edit-phone" error={errors.phone?.message}>
        <input id="edit-phone" type="tel" className={inputClass} autoComplete="off" {...form.register('phone')} />
      </Field>
      <Field label={`New ABHA number (current: ${maskedAbha(patient.abhaLast4)})`} htmlFor="edit-abhaNumber" error={errors.abhaNumber?.message}>
        <input id="edit-abhaNumber" inputMode="numeric" className={inputClass} autoComplete="off" placeholder="Leave blank to keep" {...form.register('abhaNumber')} />
      </Field>
      <AddressFields register={form.register} errors={errors} />
      <button type="submit" className={primaryButton} disabled={submitting}>Save changes</button>
    </form>
  )
}
