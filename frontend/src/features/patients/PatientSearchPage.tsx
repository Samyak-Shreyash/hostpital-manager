import { zodResolver } from '@hookform/resolvers/zod'
import { keepPreviousData, useMutation, useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import { useForm } from 'react-hook-form'
import { Link } from 'react-router'
import { z } from 'zod'
import type { Page } from '../../api/types'
import { patientKeys, searchByAbha, searchPatients, type PatientSearch, type PatientSummary } from '../../api/patients'
import { Field, FormError, inputClass, primaryButton, secondaryButton } from '../../components/ui'
import { errorMessage } from '../auth/messages'
import { useCan } from '../auth/useSession'
import { ABHA_PATTERN } from './schemas'
import { maskedAbha, sexLabel } from './format'

const PAGE_SIZE = 20

export const patientSearchSchema = z
  .object({
    q: z.string().trim().refine((v) => v === '' || v.length >= 3, 'Enter at least 3 characters of the name'),
    uhid: z.string().trim(),
    phone: z.string().trim(),
    dob: z.string(),
  })
  .refine((v) => v.q || v.uhid || v.phone || v.dob, { message: 'Enter at least one search value', path: ['q'] })
type SearchValues = z.infer<typeof patientSearchSchema>

const abhaSchema = z.object({ abhaNumber: z.string().trim().regex(ABHA_PATTERN, 'ABHA number must be 14 digits') })

function compact(v: SearchValues): PatientSearch {
  const out: PatientSearch = {}
  if (v.q) out.q = v.q
  if (v.uhid) out.uhid = v.uhid
  if (v.phone) out.phone = v.phone
  if (v.dob) out.dob = v.dob
  return out
}

export function PatientSearchPage() {
  const canCreate = useCan('patient:create')
  const [filters, setFilters] = useState<PatientSearch | null>(null)
  const [page, setPage] = useState(0)
  const form = useForm<SearchValues>({ resolver: zodResolver(patientSearchSchema), defaultValues: { q: '', uhid: '', phone: '', dob: '' } })
  const abhaForm = useForm<{ abhaNumber: string }>({ resolver: zodResolver(abhaSchema), defaultValues: { abhaNumber: '' } })
  const params = filters && { ...filters, page, size: PAGE_SIZE }
  const results = useQuery({
    queryKey: patientKeys.search(params),
    queryFn: () => searchPatients(params!),
    enabled: !!params,
    placeholderData: keepPreviousData,
  })
  // A mutation, not a query: the ABHA number never becomes a cache key.
  const abha = useMutation({ mutationFn: (n: string) => searchByAbha(n, 0, PAGE_SIZE) })

  const shown: Page<PatientSummary> | undefined = abha.data ?? (filters ? results.data : undefined)
  const error = abha.isError ? abha.error : results.isError ? results.error : null
  const totalPages = shown ? Math.max(1, Math.ceil(shown.totalItems / shown.size)) : 1
  const { errors } = form.formState

  return (
    <section className="flex flex-col gap-5">
      <div className="flex flex-wrap items-end gap-4">
        <div className="flex-1">
          <h1 className="text-3xl font-semibold">Patients</h1>
          <p className="text-sm text-ink-muted">Search by mobile number first: it is the fastest at the counter.</p>
        </div>
        {canCreate && <Link to="/patients/new" className={primaryButton}>+ Register patient</Link>}
      </div>
      <div className="rounded-xl border border-border bg-surface p-5">
        <form
          noValidate
          className="grid grid-cols-1 gap-x-4 sm:grid-cols-2 lg:grid-cols-4"
          onSubmit={form.handleSubmit((v) => { abha.reset(); setPage(0); setFilters(compact(v)) })}
        >
          <div className="sm:col-span-2 lg:col-span-4">
            <Field label="Phone" htmlFor="phone">
              <div className="mt-1 flex gap-2">
                <span aria-hidden="true" className="flex min-h-11 items-center rounded-lg border border-border-strong bg-canvas px-3 font-semibold">+91</span>
                <input id="phone" className={`${inputClass} mt-0 text-lg tracking-wide`} autoComplete="off" inputMode="tel" {...form.register('phone')} />
              </div>
            </Field>
          </div>
          <Field label="Name" htmlFor="q" error={errors.q?.message}>
            <input id="q" className={inputClass} autoComplete="off" {...form.register('q')} />
          </Field>
          <Field label="UHID" htmlFor="uhid">
            <input id="uhid" className={`${inputClass} font-mono`} autoComplete="off" {...form.register('uhid')} />
          </Field>
          <Field label="Date of birth" htmlFor="dob">
            <input id="dob" type="date" className={inputClass} {...form.register('dob')} />
          </Field>
          <div className="flex items-start pt-6">
            <button type="submit" className={`${primaryButton} w-full`}>Search</button>
          </div>
        </form>
        <form
          noValidate
          className="flex flex-wrap items-end gap-3 border-t border-dashed border-border-strong pt-4"
          onSubmit={abhaForm.handleSubmit((v) => {
            setFilters(null)
            abha.mutate(v.abhaNumber, { onSettled: () => abhaForm.reset({ abhaNumber: '' }) })
          })}
        >
          <div className="min-w-60 flex-1">
            <Field label="ABHA number" htmlFor="abhaNumber" error={abhaForm.formState.errors.abhaNumber?.message}>
              <input id="abhaNumber" className={inputClass} autoComplete="off" inputMode="numeric" placeholder="14 digits" {...abhaForm.register('abhaNumber')} />
            </Field>
          </div>
          <button type="submit" className={`${secondaryButton} mb-4`} disabled={abha.isPending}>Search by ABHA</button>
        </form>
      </div>
      <FormError message={error ? errorMessage(error) : null} />
      {results.isFetching && !shown && <p role="status">Loading...</p>}
      {shown && (
        <>
          {shown.items.length > 1 && filters?.phone && (
            <p className="text-sm font-semibold">{shown.totalItems} patients share this number. Pick the right family member.</p>
          )}
          <ul aria-label="Patients" className="flex flex-col gap-2">
            {shown.items.map((p) => (
              <li key={p.id} className="flex flex-wrap items-center gap-3 rounded-xl border border-border bg-surface px-4 py-3">
                <span aria-hidden="true" className="flex size-10 items-center justify-center rounded-full bg-primary-soft font-semibold text-primary">
                  {initials(p.fullName)}
                </span>
                <div className="min-w-0 flex-1 basis-52">
                  <Link className="text-[15px] font-semibold text-ink hover:text-primary hover:underline" to={`/patients/${encodeURIComponent(p.id)}`}>{p.fullName}</Link>
                  <span className="text-ink-muted"> · {sexLabel(p.sex)}{p.dateOfBirth ? ` · born ${p.dateOfBirth}` : ''}</span>
                  <div className="text-sm text-ink-muted">UHID <span className="font-mono">{p.uhid}</span> · <span>{p.phoneMasked}</span></div>
                </div>
                <span className={`rounded-full px-2.5 py-1 text-xs font-semibold ${p.abhaLast4 ? 'bg-primary-soft text-primary' : 'bg-muted text-ink-muted'}`}>
                  {p.abhaLast4 ? <>ABHA <span>{maskedAbha(p.abhaLast4)}</span></> : 'No ABHA'}
                </span>
              </li>
            ))}
            {shown.items.length === 0 && <li className="rounded-xl border border-border bg-surface p-4 text-ink-muted">No patients found.</li>}
          </ul>
          {!abha.data && (
            <div className="flex items-center gap-3 text-sm">
              <button type="button" className="underline disabled:opacity-40" disabled={page === 0} onClick={() => setPage(page - 1)}>Previous</button>
              <span>Page {page + 1} of {totalPages}</span>
              <button type="button" className="underline disabled:opacity-40" disabled={page + 1 >= totalPages} onClick={() => setPage(page + 1)}>Next</button>
            </div>
          )}
        </>
      )}
    </section>
  )
}

function initials(name: string): string {
  return name.split(/\s+/).filter(Boolean).slice(0, 2).map((w) => w[0]!.toUpperCase()).join('')
}
