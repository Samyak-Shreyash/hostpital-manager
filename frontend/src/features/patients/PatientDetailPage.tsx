import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { Link, useParams } from 'react-router'
import { ApiError } from '../../api/client'
import { getPatient, listAllergies, listHistory, patientKeys, updatePatient, type DuplicateCandidate, type UpdatePatientBody } from '../../api/patients'
import { errorMessage } from '../auth/messages'
import { useCan } from '../auth/useSession'
import { DuplicateCandidates } from './DuplicateCandidates'
import { AllergyList, HistoryList } from './ClinicalEntries'
import { maskedAbha } from './format'
import { PatientBanner } from './PatientBanner'
import { PatientEditForm } from './PatientEditForm'

export function PatientDetailPage() {
  const { id = '' } = useParams()
  const queryClient = useQueryClient()
  const canUpdate = useCan('patient:update')
  const canIdentity = useCan('patient:update-identity')
  const canClinical = useCan('patient:update-clinical')
  const canOverride = useCan('patient:override-duplicate')
  const [pending, setPending] = useState<UpdatePatientBody | null>(null)
  const [candidates, setCandidates] = useState<DuplicateCandidate[] | null>(null)
  const [saved, setSaved] = useState(false)
  const patient = useQuery({ queryKey: patientKeys.detail(id), queryFn: () => getPatient(id) })
  const allergies = useQuery({ queryKey: patientKeys.allergies(id), queryFn: () => listAllergies(id), enabled: patient.isSuccess })
  const history = useQuery({ queryKey: patientKeys.history(id), queryFn: () => listHistory(id), enabled: patient.isSuccess })
  const save = useMutation({
    mutationFn: (body: UpdatePatientBody) => updatePatient(id, body),
    onSuccess: (updated) => {
      queryClient.setQueryData(patientKeys.detail(id), updated)
      void queryClient.invalidateQueries({ queryKey: ['patients', 'search'] })
      setSaved(true)
      setCandidates(null)
    },
    onError: (error) => {
      if (error instanceof ApiError && error.code === 'DUPLICATE_SUSPECTED') {
        setCandidates((error.properties.candidates as DuplicateCandidate[] | undefined) ?? [])
      }
    },
  })

  if (patient.isPending) return <p role="status">Loading...</p>
  if (patient.isError) {
    const notFound = patient.error instanceof ApiError && patient.error.status === 404
    return <p role="alert" className="text-red-700">{notFound ? 'Patient not found.' : errorMessage(patient.error)}</p>
  }
  const p = patient.data
  const conflict = save.error instanceof ApiError && save.error.code === 'CONCURRENT_MODIFICATION'
  const duplicate = save.error instanceof ApiError && save.error.code === 'DUPLICATE_SUSPECTED'
  const addr = p.address

  return (
    <section>
      <PatientBanner patient={p} allergies={allergies.data} />
      <dl className="mb-6 grid grid-cols-[max-content_1fr] gap-x-6 gap-y-1 text-sm">
        <dt className="font-medium">Date of birth</dt><dd>{p.dateOfBirth ?? 'Unknown'}{p.dobEstimated ? ' (estimated)' : ''}</dd>
        <dt className="font-medium">Phone</dt><dd>{p.phone}</dd>
        <dt className="font-medium">ABHA</dt><dd data-testid="abha">{maskedAbha(p.abhaLast4)}</dd>
        <dt className="font-medium">Address</dt>
        <dd>{addr ? [addr.line1, addr.line2, addr.city, addr.state, addr.pincode].filter(Boolean).join(', ') : 'Not recorded'}</dd>
        <dt className="font-medium">Status</dt><dd>{p.status}</dd>
      </dl>
      {saved && <p role="status" className="mb-4 text-sm text-green-800">Changes saved.</p>}
      {conflict && (
        <div role="alert" className="mb-4 rounded bg-amber-50 p-3 text-sm">
          Someone else changed this patient. Reload to see the latest version, then reapply your change.{' '}
          <button type="button" className="text-blue-700 underline" onClick={() => { save.reset(); void patient.refetch() }}>
            Reload latest version
          </button>
        </div>
      )}
      {canUpdate && (
        <div className="mb-8">
          <PatientEditForm
            key={`${p.id}-${p.version}`}
            patient={p}
            canEditIdentity={canIdentity}
            submitting={save.isPending}
            serverError={save.isError && !conflict && !duplicate ? errorMessage(save.error) : null}
            onSubmit={(body) => { setSaved(false); setCandidates(null); setPending(body); save.mutate(body) }}
          />
          {candidates && duplicate && (
            <DuplicateCandidates
              candidates={candidates}
              canOverride={canOverride}
              submitting={save.isPending}
              actionLabel="Save anyway"
              onOverride={(reason) => pending && save.mutate({ ...pending, confirmNotDuplicate: true, overrideReason: reason })}
            />
          )}
        </div>
      )}
      {allergies.isError && <p role="alert" className="text-red-700">{errorMessage(allergies.error)}</p>}
      {allergies.data && <AllergyList patientId={id} allergies={allergies.data} canEdit={canClinical} />}
      {history.isError && <p role="alert" className="text-red-700">{errorMessage(history.error)}</p>}
      {history.data && <HistoryList patientId={id} entries={history.data} canEdit={canClinical} />}
      <Link className="mt-6 inline-block text-blue-700 underline" to="/patients">Back to patients</Link>
    </section>
  )
}
