import { zodResolver } from '@hookform/resolvers/zod'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { useForm, useWatch } from 'react-hook-form'
import { Link, useNavigate } from 'react-router'
import { registerDoctor, type RegisterDoctorBody } from '../../api/practitioners'
import { Field, FormError, inputClass, primaryButton } from '../../components/ui'
import { errorMessage } from '../auth/messages'
import { DepartmentPicker, SpecialtyPicker } from './CatalogPickers'
import { doctorRegisterSchema, splitList, type DoctorRegisterValues } from './schemas'
import { UserPicker, useCatalog } from './shared'

export function DoctorRegisterPage() {
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const catalog = useCatalog()
  const form = useForm<DoctorRegisterValues>({
    resolver: zodResolver(doctorRegisterSchema),
    defaultValues: {
      userId: '', fullName: '', registrationNo: '', registrationCouncil: '', qualifications: '', defaultSlotMinutes: '15',
      specialtyIds: [], primarySpecialtyId: '', departmentIds: [],
    },
  })
  const { errors } = form.formState
  const save = useMutation({
    mutationFn: registerDoctor,
    onSuccess: (doctor) => {
      void queryClient.invalidateQueries({ queryKey: ['doctors'] })
      navigate(`/doctors/${encodeURIComponent(doctor.id)}`)
    },
  })
  const [specialtyIds, primary, departmentIds] = useWatch({ control: form.control, name: ['specialtyIds', 'primarySpecialtyId', 'departmentIds'] })

  const submit = (v: DoctorRegisterValues) => {
    const body: RegisterDoctorBody = {
      userId: v.userId,
      fullName: v.fullName,
      registrationNo: v.registrationNo,
      defaultSlotMinutes: Number(v.defaultSlotMinutes),
      specialtyIds: v.specialtyIds,
      primarySpecialtyId: v.primarySpecialtyId,
      departmentIds: v.departmentIds,
      qualifications: splitList(v.qualifications),
    }
    if (v.registrationCouncil) body.registrationCouncil = v.registrationCouncil
    save.mutate(body)
  }

  return (
    <section className="max-w-xl">
      <h1 className="mb-4 text-2xl font-semibold">Register doctor</h1>
      <form noValidate onSubmit={form.handleSubmit(submit)}>
        <FormError message={save.isError ? errorMessage(save.error) : null} />
        {catalog.error && <FormError message={errorMessage(catalog.error)} />}
        <UserPicker id="doctor-userId" registration={form.register('userId')} error={errors.userId?.message} />
        <Field label="Full name" htmlFor="doctor-fullName" error={errors.fullName?.message}>
          <input id="doctor-fullName" className={inputClass} autoComplete="off" {...form.register('fullName')} />
        </Field>
        <Field label="Registration number" htmlFor="doctor-registrationNo" error={errors.registrationNo?.message}>
          <input id="doctor-registrationNo" className={inputClass} autoComplete="off" {...form.register('registrationNo')} />
        </Field>
        <Field label="Registration council" htmlFor="doctor-council" error={errors.registrationCouncil?.message}>
          <input id="doctor-council" className={inputClass} autoComplete="off" {...form.register('registrationCouncil')} />
        </Field>
        <Field label="Qualifications (comma-separated)" htmlFor="doctor-qualifications" error={errors.qualifications?.message}>
          <input id="doctor-qualifications" className={inputClass} autoComplete="off" {...form.register('qualifications')} />
        </Field>
        <Field label="Default slot length (minutes)" htmlFor="doctor-slot" error={errors.defaultSlotMinutes?.message}>
          <input id="doctor-slot" inputMode="numeric" className={inputClass} {...form.register('defaultSlotMinutes')} />
        </Field>
        <SpecialtyPicker
          items={catalog.specialties}
          selected={specialtyIds}
          primary={primary}
          error={errors.specialtyIds?.message ?? errors.primarySpecialtyId?.message}
          onChange={(s, p) => { form.setValue('specialtyIds', s); form.setValue('primarySpecialtyId', p) }}
        />
        <DepartmentPicker items={catalog.departments} selected={departmentIds} onChange={(d) => form.setValue('departmentIds', d)} error={errors.departmentIds?.message} />
        <button type="submit" className={primaryButton} disabled={save.isPending}>Register doctor</button>
        <Link className="ml-4 text-blue-700 underline" to="/doctors">Cancel</Link>
      </form>
    </section>
  )
}
