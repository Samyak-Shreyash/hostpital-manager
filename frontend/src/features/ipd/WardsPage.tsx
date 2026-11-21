import { zodResolver } from '@hookform/resolvers/zod'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { useForm, type UseFormRegisterReturn } from 'react-hook-form'
import { billingKeys, listServices } from '../../api/billing'
import { Field, inputClass, primaryButton, secondaryButton } from '../../components/ui'
import { useCan } from '../auth/useSession'
import { StatusBadge } from '../practitioners/shared'
import {
  createBed, createWard, ipdKeys, listBeds, listWards, updateBed, updateWard, type Bed, type CareType, type Ward,
} from './api'
import { bedSchema, wardSchema, type BedValues, type WardValues } from './schemas'
import { IpdProblem, ipdErrorMessage } from './shared'

export const CRITICAL_CARE_HELP = 'Critical care = ICU, CCU, ICCU or NICU. Room charges in critical-care wards are GST-exempt.'
const CARE_LABELS: Record<CareType, string> = { GENERAL: 'General', CRITICAL_CARE: 'Critical care' }

function CareTypeSelect({ id, registration }: { id: string; registration: UseFormRegisterReturn }) {
  return (
    <>
      <select id={id} aria-describedby={`${id}-help`} className={inputClass} {...registration}>
        <option value="GENERAL">General</option>
        <option value="CRITICAL_CARE">Critical care</option>
      </select>
      <p id={`${id}-help`} className="mt-1 text-sm text-slate-600">{CRITICAL_CARE_HELP}</p>
    </>
  )
}

function NewWardForm() {
  const queryClient = useQueryClient()
  const form = useForm<WardValues>({ resolver: zodResolver(wardSchema), defaultValues: { code: '', name: '', careType: 'GENERAL' } })
  const create = useMutation({
    mutationFn: createWard,
    onSuccess: () => { form.reset(); void queryClient.invalidateQueries({ queryKey: ipdKeys.wards }); void queryClient.invalidateQueries({ queryKey: ipdKeys.availability }) },
  })
  const { errors } = form.formState
  return (
    <form noValidate onSubmit={form.handleSubmit((v) => create.mutate(v))} className="mb-6 rounded bg-white p-4 shadow" aria-label="New ward">
      <h2 className="mb-2 font-semibold">New ward</h2>
      <Field label="Code" htmlFor="ward-code" error={errors.code?.message}>
        <input id="ward-code" className={inputClass} {...form.register('code')} />
      </Field>
      <Field label="Name" htmlFor="ward-name" error={errors.name?.message}>
        <input id="ward-name" className={inputClass} {...form.register('name')} />
      </Field>
      <Field label="Care type" htmlFor="ward-care">
        <CareTypeSelect id="ward-care" registration={form.register('careType')} />
      </Field>
      <IpdProblem error={create.error} what="ward" onReload={() => void queryClient.invalidateQueries({ queryKey: ipdKeys.wards })} />
      <button type="submit" className={primaryButton} disabled={create.isPending}>Create ward</button>
    </form>
  )
}

function WardEditor({ ward, onChanged }: { ward: Ward; onChanged: () => void }) {
  const form = useForm<{ name: string; careType: CareType }>({ defaultValues: { name: ward.name, careType: ward.careType } })
  const save = useMutation({
    mutationFn: (v: { name: string; careType: CareType }) => updateWard(ward.id, { version: ward.version, name: v.name.trim(), careType: v.careType }),
    onSuccess: onChanged,
  })
  const toggle = useMutation({
    mutationFn: () => updateWard(ward.id, { version: ward.version, status: ward.status === 'ACTIVE' ? 'INACTIVE' : 'ACTIVE' }),
    onSuccess: onChanged,
  })
  return (
    <form noValidate onSubmit={form.handleSubmit((v) => save.mutate(v))} className="mb-4" aria-label="Edit ward">
      <Field label="Ward name" htmlFor="edit-ward-name">
        <input id="edit-ward-name" className={inputClass} {...form.register('name', { required: true })} />
      </Field>
      <Field label="Ward care type" htmlFor="edit-ward-care">
        <select id="edit-ward-care" aria-describedby="edit-ward-care-help" className={inputClass} {...form.register('careType')}>
          <option value="GENERAL">General</option>
          <option value="CRITICAL_CARE">Critical care</option>
        </select>
        <p id="edit-ward-care-help" className="mt-1 text-sm text-slate-600">{CRITICAL_CARE_HELP}</p>
      </Field>
      <IpdProblem error={save.error ?? toggle.error} what="ward" onReload={onChanged} />
      <div className="flex gap-2">
        <button type="submit" className={primaryButton} disabled={save.isPending}>Save ward</button>
        <button type="button" className={secondaryButton} disabled={toggle.isPending} onClick={() => toggle.mutate()}>
          {ward.status === 'ACTIVE' ? 'Deactivate ward' : 'Activate ward'}
        </button>
      </div>
    </form>
  )
}

function NewBedForm({ ward }: { ward: Ward }) {
  const queryClient = useQueryClient()
  const canCatalog = useCan('catalog:read')
  const services = useQuery({ queryKey: billingKeys.services, queryFn: () => listServices({ active: true, size: 100 }), enabled: canCatalog })
  const form = useForm<BedValues>({ resolver: zodResolver(bedSchema), defaultValues: { bedNo: '', bedClass: '', dailyRateServiceId: '' } })
  const create = useMutation({
    mutationFn: (v: BedValues) => createBed(ward.id, { bedNo: v.bedNo, dailyRateServiceId: v.dailyRateServiceId, ...(v.bedClass ? { bedClass: v.bedClass } : {}) }),
    onSuccess: () => { form.reset(); void queryClient.invalidateQueries({ queryKey: ipdKeys.beds(ward.id) }); void queryClient.invalidateQueries({ queryKey: ipdKeys.availability }) },
  })
  const { errors } = form.formState
  return (
    <form noValidate onSubmit={form.handleSubmit((v) => create.mutate(v))} aria-label="New bed" className="rounded border border-slate-200 p-3">
      <h3 className="mb-2 font-medium">Add a bed</h3>
      <Field label="Bed number" htmlFor="bed-no" error={errors.bedNo?.message}>
        <input id="bed-no" className={inputClass} {...form.register('bedNo')} />
      </Field>
      <Field label="Bed class" htmlFor="bed-class" error={errors.bedClass?.message}>
        <input id="bed-class" className={inputClass} {...form.register('bedClass')} />
      </Field>
      <Field label="Daily rate service" htmlFor="bed-rate" error={errors.dailyRateServiceId?.message}>
        <select id="bed-rate" className={inputClass} {...form.register('dailyRateServiceId')}>
          <option value="">Select a service</option>
          {services.data?.items.map((s) => <option key={s.id} value={s.id}>{s.name}{s.currentPrice ? ` (${s.currentPrice})` : ''}</option>)}
        </select>
        {!canCatalog && <p className="mt-1 text-sm text-slate-600">You need permission to read the service catalog to choose a rate.</p>}
      </Field>
      <IpdProblem error={create.error} what="ward" onReload={() => void queryClient.invalidateQueries({ queryKey: ipdKeys.beds(ward.id) })} />
      <button type="submit" className={primaryButton} disabled={create.isPending}>Add bed</button>
    </form>
  )
}

function BedRow({ bed, wardId, canManage }: { bed: Bed; wardId: string; canManage: boolean }) {
  const queryClient = useQueryClient()
  const refresh = () => { void queryClient.invalidateQueries({ queryKey: ipdKeys.beds(wardId) }); void queryClient.invalidateQueries({ queryKey: ipdKeys.availability }) }
  const toggle = useMutation({
    mutationFn: () => updateBed(bed.id, { version: bed.version, status: bed.status === 'ACTIVE' ? 'OUT_OF_SERVICE' : 'ACTIVE' }),
    onSuccess: refresh,
  })
  return (
    <li className="border-b py-2 text-sm">
      <div className="flex items-center gap-3">
        <span className="font-medium">{bed.bedNo}</span>
        {bed.bedClass && <span className="text-slate-600">{bed.bedClass}</span>}
        <span className={`rounded px-2 py-0.5 text-xs ${bed.status === 'ACTIVE' ? 'bg-green-100 text-green-900' : 'bg-amber-100 text-amber-900'}`}>
          {bed.status === 'ACTIVE' ? 'Active' : 'Out of service'}
        </span>
        {canManage && (
          <button type="button" className={`${secondaryButton} ml-auto`} disabled={toggle.isPending} onClick={() => toggle.mutate()}>
            {bed.status === 'ACTIVE' ? `Take bed ${bed.bedNo} out of service` : `Return bed ${bed.bedNo} to service`}
          </button>
        )}
      </div>
      {toggle.error != null && <p role="alert" className="mt-1 text-red-700">{ipdErrorMessage(toggle.error)}</p>}
    </li>
  )
}

function WardBeds({ ward }: { ward: Ward }) {
  const canManage = useCan('bed:manage')
  const canRead = useCan('bed:read')
  const beds = useQuery({ queryKey: ipdKeys.beds(ward.id), queryFn: () => listBeds(ward.id), enabled: canRead })
  return (
    <div>
      <h3 className="mb-2 font-medium">Beds in {ward.name}</h3>
      {beds.isError && <p role="alert" className="text-red-700">{ipdErrorMessage(beds.error)}</p>}
      {beds.data && beds.data.items.length === 0 && <p className="text-sm text-slate-600">No beds yet.</p>}
      <ul className="mb-4">{beds.data?.items.map((b) => <BedRow key={b.id} bed={b} wardId={ward.id} canManage={canManage} />)}</ul>
      {canManage && <NewBedForm ward={ward} />}
    </div>
  )
}

/** Ward and bed administration ([E8-8]). */
export function WardsPage() {
  const queryClient = useQueryClient()
  const canManage = useCan('ward:manage')
  const [selectedId, setSelectedId] = useState<string | null>(null)
  const wards = useQuery({ queryKey: ipdKeys.wards, queryFn: listWards })
  const selected = wards.data?.items.find((w) => w.id === selectedId) ?? null
  const changed = () => { void queryClient.invalidateQueries({ queryKey: ipdKeys.wards }); void queryClient.invalidateQueries({ queryKey: ipdKeys.availability }) }
  return (
    <section>
      <h1 className="mb-4 text-2xl font-semibold">Wards and beds</h1>
      {canManage && <NewWardForm />}
      {wards.isPending && <p role="status">Loading...</p>}
      {wards.isError && <p role="alert" className="text-red-700">{ipdErrorMessage(wards.error)}</p>}
      {wards.data && wards.data.items.length === 0 && <p className="text-slate-600">No wards yet.</p>}
      <ul className="mb-6 space-y-2" aria-label="Wards">
        {wards.data?.items.map((w) => (
          <li key={w.id} className="flex items-center gap-3 rounded bg-white p-3 shadow">
            <span className="font-medium">{w.name}</span>
            <span className="text-sm text-slate-600">{w.code}</span>
            <span className="text-sm">{CARE_LABELS[w.careType]}</span>
            <StatusBadge status={w.status} />
            <button type="button" className={`${secondaryButton} ml-auto`} onClick={() => setSelectedId(w.id)}>Manage {w.name}</button>
          </li>
        ))}
      </ul>
      {selected && (
        <div className="rounded bg-white p-4 shadow">
          {canManage && <WardEditor key={`${selected.id}-${selected.version}`} ward={selected} onChanged={changed} />}
          <WardBeds ward={selected} />
        </div>
      )}
    </section>
  )
}
