import { zodResolver } from '@hookform/resolvers/zod'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { useForm } from 'react-hook-form'
import { Link, useNavigate, useParams } from 'react-router'
import { ApiError } from '../../api/client'
import {
  getStaff, practitionerKeys, registerStaff, STAFF_TYPES, updateStaff, type RegisterStaffBody, type Staff, type UpdateStaffBody,
} from '../../api/practitioners'
import { Field, FormError, inputClass, primaryButton, secondaryButton } from '../../components/ui'
import { errorMessage } from '../auth/messages'
import { useCan } from '../auth/useSession'
import { STAFF_TYPE_LABELS, staffSchema, type StaffValues } from './schemas'
import { ConflictNotice, StatusBadge, UserPicker, useCatalog } from './shared'

export function StaffRegisterPage() {
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const save = useMutation({
    mutationFn: registerStaff,
    onSuccess: (s) => {
      void queryClient.invalidateQueries({ queryKey: ['staff'] })
      navigate(`/staff/${encodeURIComponent(s.id)}`)
    },
  })
  return (
    <section className="max-w-xl">
      <h1 className="mb-4 text-2xl font-semibold">Register staff</h1>
      <StaffForm
        submitting={save.isPending}
        serverError={save.isError ? errorMessage(save.error) : null}
        onSubmit={(v) => {
          if (!v.userId) return false
          const body: RegisterStaffBody = { userId: v.userId, fullName: v.fullName, staffType: v.staffType }
          if (v.designation) body.designation = v.designation
          if (v.departmentId) body.departmentId = v.departmentId
          save.mutate(body)
          return true
        }}
      />
    </section>
  )
}

export function StaffDetailPage() {
  const { id = '' } = useParams()
  const queryClient = useQueryClient()
  const canUpdate = useCan('staff:update')
  const canUsers = useCan('user:read')
  const catalog = useCatalog()
  const [saved, setSaved] = useState(false)
  const staff = useQuery({ queryKey: practitionerKeys.staff(id), queryFn: () => getStaff(id) })
  const save = useMutation({
    mutationFn: (body: UpdateStaffBody) => updateStaff(id, body),
    onSuccess: (s) => {
      queryClient.setQueryData(practitionerKeys.staff(id), s)
      void queryClient.invalidateQueries({ queryKey: ['staff', 'list'] })
      setSaved(true)
    },
  })
  if (staff.isPending) return <p role="status">Loading...</p>
  if (staff.isError) {
    const notFound = staff.error instanceof ApiError && staff.error.status === 404
    return <p role="alert" className="text-red-700">{notFound ? 'Staff record not found.' : errorMessage(staff.error)}</p>
  }
  const s = staff.data
  const conflict = save.error instanceof ApiError && save.error.code === 'CONCURRENT_MODIFICATION'
  const active = s.status === 'ACTIVE'
  const mutate = (body: UpdateStaffBody) => { setSaved(false); save.mutate(body) }

  return (
    <section className="max-w-xl">
      <div className="mb-4 flex items-center gap-3">
        <h1 className="text-2xl font-semibold">{s.fullName}</h1>
        <StatusBadge status={s.status} />
      </div>
      <dl className="mb-4 grid grid-cols-[max-content_1fr] gap-x-6 gap-y-1 text-sm">
        <dt className="font-medium">Type</dt><dd>{STAFF_TYPE_LABELS[s.staffType] ?? s.staffType}</dd>
        <dt className="font-medium">Designation</dt><dd>{s.designation ?? '—'}</dd>
        <dt className="font-medium">Department</dt><dd>{catalog.departmentName(s.departmentId)}</dd>
      </dl>
      <p className="mb-4 text-sm text-slate-600">
        Access roles belong to the linked user account and are managed on the user screen.{' '}
        {canUsers && <Link className="text-blue-700 underline" to={`/users/${encodeURIComponent(s.userId)}`}>Open linked user account</Link>}
      </p>
      {saved && <p role="status" className="mb-4 text-sm text-green-800">Changes saved.</p>}
      {conflict && <ConflictNotice what="staff record" onReload={() => { save.reset(); void staff.refetch() }} />}
      {canUpdate && (
        <>
          <StaffForm
            key={s.version}
            staff={s}
            submitting={save.isPending}
            serverError={save.isError && !conflict ? errorMessage(save.error) : null}
            onSubmit={(v) => { mutate(changes(s, v)); return true }}
          />
          <button
            type="button"
            className={`${secondaryButton} mt-3`}
            disabled={save.isPending}
            onClick={() => mutate({ version: s.version, status: active ? 'INACTIVE' : 'ACTIVE' })}
          >
            {active ? 'Deactivate' : 'Reactivate'}
          </button>
        </>
      )}
      <Link className="mt-6 block text-blue-700 underline" to="/staff">Back to staff</Link>
    </section>
  )
}

function changes(s: Staff, v: StaffValues): UpdateStaffBody {
  const body: UpdateStaffBody = { version: s.version }
  if (v.fullName !== s.fullName) body.fullName = v.fullName
  if (v.staffType !== s.staffType) body.staffType = v.staffType
  if (v.designation !== (s.designation ?? '')) body.designation = v.designation // "" clears
  if (v.departmentId !== (s.departmentId ?? '')) {
    if (v.departmentId) body.departmentId = v.departmentId
    else body.clearDepartment = true
  }
  return body
}

/** Register (with user picker) or edit. No role fields by design (review notes Q8). */
function StaffForm({ staff, submitting, serverError, onSubmit }: {
  staff?: Staff; submitting: boolean; serverError: string | null; onSubmit: (v: StaffValues) => boolean
}) {
  const catalog = useCatalog()
  const form = useForm<StaffValues>({
    resolver: zodResolver(staffSchema),
    defaultValues: {
      userId: '',
      fullName: staff?.fullName ?? '',
      staffType: staff?.staffType ?? ('' as StaffValues['staffType']),
      designation: staff?.designation ?? '',
      departmentId: staff?.departmentId ?? '',
    },
  })
  const { errors } = form.formState
  const departments = catalog.departments.filter((d) => d.active || d.id === staff?.departmentId)
  const submit = (v: StaffValues) => {
    if (!onSubmit(v)) form.setError('userId', { message: 'Select the user account to link' })
  }
  return (
    <form noValidate onSubmit={form.handleSubmit(submit)}>
      <FormError message={serverError} />
      {!staff && <UserPicker id="staff-userId" registration={form.register('userId')} error={errors.userId?.message} />}
      <Field label="Full name" htmlFor="staff-fullName" error={errors.fullName?.message}>
        <input id="staff-fullName" className={inputClass} autoComplete="off" {...form.register('fullName')} />
      </Field>
      <Field label="Staff type" htmlFor="staff-type" error={errors.staffType?.message}>
        <select id="staff-type" className={inputClass} {...form.register('staffType')}>
          <option value="">Select a type</option>
          {STAFF_TYPES.map((t) => <option key={t} value={t}>{STAFF_TYPE_LABELS[t]}</option>)}
        </select>
      </Field>
      <Field label="Designation" htmlFor="staff-designation" error={errors.designation?.message}>
        <input id="staff-designation" className={inputClass} autoComplete="off" {...form.register('designation')} />
      </Field>
      <Field label="Department" htmlFor="staff-department">
        <select id="staff-department" className={inputClass} {...form.register('departmentId')}>
          <option value="">None</option>
          {departments.map((d) => <option key={d.id} value={d.id}>{d.name}{!d.active && ' (inactive)'}</option>)}
        </select>
      </Field>
      <button type="submit" className={primaryButton} disabled={submitting}>{staff ? 'Save changes' : 'Register staff'}</button>
      {!staff && <Link className="ml-4 text-blue-700 underline" to="/staff">Cancel</Link>}
    </form>
  )
}
