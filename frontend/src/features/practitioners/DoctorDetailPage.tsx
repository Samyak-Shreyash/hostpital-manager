import { zodResolver } from '@hookform/resolvers/zod'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { useForm } from 'react-hook-form'
import { Link, useParams } from 'react-router'
import { ApiError } from '../../api/client'
import {
  getDoctor, practitionerKeys, replaceDepartments, replaceSpecialties, updateDoctor, type Doctor, type UpdateDoctorBody,
} from '../../api/practitioners'
import { Field, FormError, inputClass, primaryButton, secondaryButton } from '../../components/ui'
import { errorMessage } from '../auth/messages'
import { useCan } from '../auth/useSession'
import { AvailabilityEditor } from './AvailabilityEditor'
import { DepartmentPicker, SpecialtyPicker } from './CatalogPickers'
import { FreeSlots } from './FreeSlots'
import { LeaveList } from './LeaveList'
import { doctorProfileSchema, specialtiesSchema, splitList, type DoctorProfileValues } from './schemas'
import { ConflictNotice, StatusBadge, useCatalog } from './shared'

const isConflict = (e: unknown) => e instanceof ApiError && e.code === 'CONCURRENT_MODIFICATION'

export function DoctorDetailPage() {
  const { id = '' } = useParams()
  const queryClient = useQueryClient()
  const canUpdate = useCan('practitioner:update')
  const canSchedule = useCan('practitioner:schedule')
  const canSlots = useCan('appointment:read')
  const catalog = useCatalog()
  const doctor = useQuery({ queryKey: practitionerKeys.doctor(id), queryFn: () => getDoctor(id) })
  const [saved, setSaved] = useState<string | null>(null)

  const onSaved = (updated: Doctor, what: string) => {
    queryClient.setQueryData(practitionerKeys.doctor(id), updated)
    // The availability template shares the doctor's version.
    void queryClient.invalidateQueries({ queryKey: practitionerKeys.availability(id) })
    void queryClient.invalidateQueries({ queryKey: ['doctors', 'list'] })
    setSaved(what)
  }
  const profile = useMutation({ mutationFn: (body: UpdateDoctorBody) => updateDoctor(id, body), onSuccess: (d) => onSaved(d, 'Profile saved.') })
  const specialties = useMutation({
    mutationFn: (b: { version: number; specialtyIds: string[]; primarySpecialtyId: string }) => replaceSpecialties(id, b),
    onSuccess: (d) => onSaved(d, 'Specialties saved.'),
  })
  const departments = useMutation({
    mutationFn: (b: { version: number; departmentIds: string[] }) => replaceDepartments(id, b),
    onSuccess: (d) => onSaved(d, 'Departments saved.'),
  })

  if (doctor.isPending) return <p role="status">Loading...</p>
  if (doctor.isError) {
    const notFound = doctor.error instanceof ApiError && doctor.error.status === 404
    return <p role="alert" className="text-red-700">{notFound ? 'Doctor not found.' : errorMessage(doctor.error)}</p>
  }
  const d = doctor.data
  const conflict = [profile, specialties, departments].some((m) => isConflict(m.error))
  const reload = () => { profile.reset(); specialties.reset(); departments.reset(); setSaved(null); void doctor.refetch() }

  return (
    <section>
      <div className="mb-4 flex items-center gap-3">
        <h1 className="text-2xl font-semibold">{d.fullName}</h1>
        <StatusBadge status={d.status} />
      </div>
      <dl className="mb-6 grid grid-cols-[max-content_1fr] gap-x-6 gap-y-1 text-sm">
        <dt className="font-medium">Registration no.</dt><dd>{d.registrationNo}{d.registrationCouncil ? ` (${d.registrationCouncil})` : ''}</dd>
        <dt className="font-medium">Qualifications</dt><dd>{d.qualifications.join(', ') || '—'}</dd>
        <dt className="font-medium">Primary specialty</dt><dd>{catalog.specialtyName(d.primarySpecialtyId)}</dd>
        <dt className="font-medium">Specialties</dt><dd>{d.specialtyIds.map(catalog.specialtyName).join(', ')}</dd>
        <dt className="font-medium">Departments</dt><dd>{d.departmentIds.map(catalog.departmentName).join(', ') || '—'}</dd>
        <dt className="font-medium">Default slot</dt><dd>{d.defaultSlotMinutes} min</dd>
      </dl>
      {saved && <p role="status" className="mb-4 text-sm text-green-800">{saved}</p>}
      {conflict && <ConflictNotice what="doctor" onReload={reload} />}
      {canUpdate && (
        <div className="mb-8 grid gap-6 md:grid-cols-2">
          <ProfileForm
            key={`p-${d.version}`}
            doctor={d}
            submitting={profile.isPending}
            serverError={profile.isError && !isConflict(profile.error) ? errorMessage(profile.error) : null}
            onSubmit={(body) => { setSaved(null); profile.mutate(body) }}
          />
          <div>
            <SpecialtiesForm
              key={`s-${d.version}`}
              doctor={d}
              submitting={specialties.isPending}
              serverError={specialties.isError && !isConflict(specialties.error) ? errorMessage(specialties.error) : null}
              onSubmit={(b) => { setSaved(null); specialties.mutate(b) }}
            />
            <DepartmentsForm
              key={`d-${d.version}`}
              doctor={d}
              submitting={departments.isPending}
              serverError={departments.isError && !isConflict(departments.error) ? errorMessage(departments.error) : null}
              onSubmit={(b) => { setSaved(null); departments.mutate(b) }}
            />
          </div>
        </div>
      )}
      <AvailabilityEditor doctorId={id} canEdit={canSchedule} />
      <LeaveList doctorId={id} canEdit={canSchedule} />
      {canSlots && <FreeSlots doctorId={id} />}
      <Link className="mt-6 inline-block text-blue-700 underline" to="/doctors">Back to doctors</Link>
    </section>
  )
}

function ProfileForm({ doctor, submitting, serverError, onSubmit }: { doctor: Doctor; submitting: boolean; serverError: string | null; onSubmit: (b: UpdateDoctorBody) => void }) {
  const form = useForm<DoctorProfileValues>({
    resolver: zodResolver(doctorProfileSchema),
    defaultValues: {
      fullName: doctor.fullName,
      registrationNo: doctor.registrationNo,
      registrationCouncil: doctor.registrationCouncil ?? '',
      qualifications: doctor.qualifications.join(', '),
      defaultSlotMinutes: String(doctor.defaultSlotMinutes),
    },
  })
  const { errors } = form.formState
  const submit = (v: DoctorProfileValues) => {
    const body: UpdateDoctorBody = { version: doctor.version }
    if (v.fullName !== doctor.fullName) body.fullName = v.fullName
    if (v.registrationNo !== doctor.registrationNo) body.registrationNo = v.registrationNo
    if (v.registrationCouncil !== (doctor.registrationCouncil ?? '')) body.registrationCouncil = v.registrationCouncil // "" clears
    const quals = splitList(v.qualifications)
    if (quals.join('\n') !== doctor.qualifications.join('\n')) body.qualifications = quals
    if (Number(v.defaultSlotMinutes) !== doctor.defaultSlotMinutes) body.defaultSlotMinutes = Number(v.defaultSlotMinutes)
    onSubmit(body)
  }
  const active = doctor.status === 'ACTIVE'
  return (
    <form noValidate onSubmit={form.handleSubmit(submit)}>
      <h2 className="mb-3 text-lg font-semibold">Edit profile</h2>
      <FormError message={serverError} />
      <Field label="Full name" htmlFor="edit-fullName" error={errors.fullName?.message}>
        <input id="edit-fullName" className={inputClass} autoComplete="off" {...form.register('fullName')} />
      </Field>
      <Field label="Registration number" htmlFor="edit-registrationNo" error={errors.registrationNo?.message}>
        <input id="edit-registrationNo" className={inputClass} autoComplete="off" {...form.register('registrationNo')} />
      </Field>
      <Field label="Registration council" htmlFor="edit-council" error={errors.registrationCouncil?.message}>
        <input id="edit-council" className={inputClass} autoComplete="off" {...form.register('registrationCouncil')} />
      </Field>
      <Field label="Qualifications (comma-separated)" htmlFor="edit-qualifications" error={errors.qualifications?.message}>
        <input id="edit-qualifications" className={inputClass} autoComplete="off" {...form.register('qualifications')} />
      </Field>
      <Field label="Default slot length (minutes)" htmlFor="edit-slot" error={errors.defaultSlotMinutes?.message}>
        <input id="edit-slot" inputMode="numeric" className={inputClass} {...form.register('defaultSlotMinutes')} />
      </Field>
      <div className="flex gap-3">
        <button type="submit" className={primaryButton} disabled={submitting}>Save profile</button>
        <button
          type="button"
          className={secondaryButton}
          disabled={submitting}
          onClick={() => onSubmit({ version: doctor.version, status: active ? 'INACTIVE' : 'ACTIVE' })}
        >
          {active ? 'Deactivate' : 'Reactivate'}
        </button>
      </div>
    </form>
  )
}

function SpecialtiesForm({ doctor, submitting, serverError, onSubmit }: {
  doctor: Doctor; submitting: boolean; serverError: string | null
  onSubmit: (b: { version: number; specialtyIds: string[]; primarySpecialtyId: string }) => void
}) {
  const catalog = useCatalog()
  const [selected, setSelected] = useState(doctor.specialtyIds)
  const [primary, setPrimary] = useState(doctor.primarySpecialtyId)
  const [error, setError] = useState<string | undefined>()
  const submit = () => {
    const parsed = specialtiesSchema.safeParse({ specialtyIds: selected, primarySpecialtyId: primary })
    if (!parsed.success) { setError(parsed.error.issues[0].message); return }
    setError(undefined)
    onSubmit({ version: doctor.version, ...parsed.data })
  }
  return (
    <div className="mb-4">
      <FormError message={serverError} />
      <SpecialtyPicker items={catalog.specialties} selected={selected} primary={primary} error={error} onChange={(s, p) => { setSelected(s); setPrimary(p) }} />
      <button type="button" className={secondaryButton} disabled={submitting} onClick={submit}>Save specialties</button>
    </div>
  )
}

function DepartmentsForm({ doctor, submitting, serverError, onSubmit }: {
  doctor: Doctor; submitting: boolean; serverError: string | null; onSubmit: (b: { version: number; departmentIds: string[] }) => void
}) {
  const catalog = useCatalog()
  const [selected, setSelected] = useState(doctor.departmentIds)
  return (
    <div>
      <FormError message={serverError} />
      <DepartmentPicker items={catalog.departments} selected={selected} onChange={setSelected} />
      <button type="button" className={secondaryButton} disabled={submitting} onClick={() => onSubmit({ version: doctor.version, departmentIds: selected })}>
        Save departments
      </button>
    </div>
  )
}
