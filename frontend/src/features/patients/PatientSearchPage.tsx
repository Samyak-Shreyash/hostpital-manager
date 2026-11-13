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
    <section>
      <div className="mb-4 flex items-center justify-between">
        <h1 className="text-2xl font-semibold">Patients</h1>
        {canCreate && <Link to="/patients/new" className={primaryButton}>Register patient</Link>}
      </div>
      <form
        noValidate
        className="mb-4 grid grid-cols-1 gap-x-3 sm:grid-cols-4"
        onSubmit={form.handleSubmit((v) => { abha.reset(); setPage(0); setFilters(compact(v)) })}
      >
        <Field label="Name" htmlFor="q" error={errors.q?.message}>
          <input id="q" className={inputClass} autoComplete="off" {...form.register('q')} />
        </Field>
        <Field label="UHID" htmlFor="uhid">
          <input id="uhid" className={inputClass} autoComplete="off" {...form.register('uhid')} />
        </Field>
        <Field label="Phone" htmlFor="phone">
          <input id="phone" className={inputClass} autoComplete="off" {...form.register('phone')} />
        </Field>
        <Field label="Date of birth" htmlFor="dob">
          <input id="dob" type="date" className={inputClass} {...form.register('dob')} />
        </Field>
        <div className="sm:col-span-4">
          <button type="submit" className={primaryButton}>Search</button>
        </div>
      </form>
      <form
        noValidate
        className="mb-6 flex items-end gap-3"
        onSubmit={abhaForm.handleSubmit((v) => {
          setFilters(null)
          abha.mutate(v.abhaNumber, { onSettled: () => abhaForm.reset({ abhaNumber: '' }) })
        })}
      >
        <div className="flex-1">
          <Field label="ABHA number" htmlFor="abhaNumber" error={abhaForm.formState.errors.abhaNumber?.message}>
            <input id="abhaNumber" className={inputClass} autoComplete="off" inputMode="numeric" {...abhaForm.register('abhaNumber')} />
          </Field>
        </div>
        <button type="submit" className={`${secondaryButton} mb-4`} disabled={abha.isPending}>Search by ABHA</button>
      </form>
      <FormError message={error ? errorMessage(error) : null} />
      {results.isFetching && !shown && <p role="status">Loading...</p>}
      {shown && (
        <>
          <table className="w-full border-collapse bg-white text-left text-sm shadow">
            <thead>
              <tr className="border-b bg-slate-100">
                <th className="p-2">UHID</th><th className="p-2">Name</th><th className="p-2">Sex</th>
                <th className="p-2">Date of birth</th><th className="p-2">Phone</th><th className="p-2">ABHA</th>
              </tr>
            </thead>
            <tbody>
              {shown.items.map((p) => (
                <tr key={p.id} className="border-b">
                  <td className="p-2 font-mono">{p.uhid}</td>
                  <td className="p-2"><Link className="text-blue-700 underline" to={`/patients/${encodeURIComponent(p.id)}`}>{p.fullName}</Link></td>
                  <td className="p-2">{sexLabel(p.sex)}</td>
                  <td className="p-2">{p.dateOfBirth ?? '-'}</td>
                  <td className="p-2">{p.phoneMasked}</td>
                  <td className="p-2">{p.abhaLast4 ? maskedAbha(p.abhaLast4) : '-'}</td>
                </tr>
              ))}
              {shown.items.length === 0 && <tr><td className="p-2" colSpan={6}>No patients found.</td></tr>}
            </tbody>
          </table>
          {!abha.data && (
            <div className="mt-3 flex items-center gap-3 text-sm">
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
