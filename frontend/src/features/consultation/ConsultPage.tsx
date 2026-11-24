import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { Link, useParams } from 'react-router'
import { clinicalKeys, getEncounter, type Encounter } from '../../api/clinical'
import { getPatient, listAllergies, patientKeys } from '../../api/patients'
import { ageSex } from '../patients/format'
import { errorMessage } from '../auth/messages'
import { useCan } from '../auth/useSession'
import { DiagnosesSection, NotesSection, PlanSection, VitalsSection } from './ClinicalSections'
import { ConsultBilling } from '../billing/ConsultBilling'
import { AddendaSection, FinalizeSection } from './FinalizeSections'
import { PatientTimeline } from './PatientTimeline'
import { PrescriptionSection } from './PrescriptionSection'

/**
 * The consultation screen for one encounter ([E6-6]). OPEN: sections editable by the permission each endpoint needs.
 * FINALIZED: everything read-only; addenda (and prescriptions) stay possible for permitted users.
 */
export function ConsultPage() {
  const { id = '' } = useParams()
  const queryClient = useQueryClient()
  const canOpenPatient = useCan('patient:read')
  // Bumped on "Reload latest version" so the section forms start again from the server's copy.
  const [epoch, setEpoch] = useState(0)
  const encounter = useQuery({ queryKey: clinicalKeys.encounter(id), queryFn: () => getEncounter(id), enabled: !!id })
  const data = encounter.data

  const patientId = data?.patient.id ?? ''
  // Name, age and allergies in the banner: allergy safety while prescribing. Read only with patient:read (audited).
  const patient = useQuery({ queryKey: patientKeys.detail(patientId), queryFn: () => getPatient(patientId), enabled: canOpenPatient && !!patientId })
  const allergies = useQuery({ queryKey: patientKeys.allergies(patientId), queryFn: () => listAllergies(patientId), enabled: patient.isSuccess })
  const activeAllergies = allergies.data?.filter((a) => a.status === 'ACTIVE') ?? []

  const onSaved = (view: Encounter) => {
    queryClient.setQueryData(clinicalKeys.encounter(view.id), view)
    void queryClient.invalidateQueries({ queryKey: clinicalKeys.history(view.patient.id) })
  }
  const onReload = () => {
    void queryClient.invalidateQueries({ queryKey: clinicalKeys.encounter(id) }).then(() => setEpoch((n) => n + 1))
  }

  return (
    <section>
      <h1 className="sr-only">Consultation</h1>
      {encounter.isPending && <p role="status">Loading...</p>}
      {encounter.isError && <p role="alert" className="text-danger">{errorMessage(encounter.error)}</p>}
      {data && (
        <div key={epoch}>
          <section aria-label="Encounter summary" className="mb-5 flex flex-wrap items-center gap-x-5 gap-y-2 rounded-xl bg-nav px-5 py-4 text-white">
            <div className="min-w-0">
              <p className="font-display text-2xl font-semibold leading-tight">
                {patient.data?.fullName ?? data.patient.uhid ?? 'Patient'}
                {patient.data && <span className="text-base font-medium text-nav-ink-muted"> · {ageSex(patient.data.dateOfBirth, patient.data.sex)}</span>}
              </p>
              {patient.data && <p className="text-sm text-nav-ink-muted">UHID <span className="font-mono">{data.patient.uhid}</span></p>}
            </div>
            {activeAllergies.map((a) => (
              <span key={a.id} className="rounded-full bg-danger-soft px-3 py-1 text-sm font-semibold text-danger">Allergy: {a.substance}</span>
            ))}
            {allergies.data && activeAllergies.length === 0 && <span className="rounded-full bg-nav-hover px-3 py-1 text-sm">No allergies recorded</span>}
            <span className="ml-auto rounded-full bg-nav-hover px-3 py-1 text-sm font-semibold">{data.status === 'OPEN' ? 'Open' : 'Finalized'}</span>
            {canOpenPatient && <Link className="text-sm font-semibold text-white underline" to={`/patients/${data.patient.id}`}>Patient record</Link>}
          </section>
          {data.chiefComplaint && <p className="mb-4 text-sm"><span className="font-semibold">Chief complaint:</span> {data.chiefComplaint}</p>}
          {data.status === 'FINALIZED' && <p className="mb-4 rounded-lg bg-muted p-3 text-sm text-ink-muted">This encounter is finalized and read-only. Corrections are added as addenda.</p>}
          <div className="flex flex-wrap items-start gap-5">
            <div className="min-w-0 flex-[999_1_560px]">
              <VitalsSection encounter={data} onSaved={onSaved} onReload={onReload} />
              <NotesSection encounter={data} onSaved={onSaved} onReload={onReload} />
              <DiagnosesSection encounter={data} onSaved={onSaved} onReload={onReload} />
              <PlanSection encounter={data} onSaved={onSaved} onReload={onReload} />
              <PrescriptionSection encounter={data} onReload={onReload} />
              <AddendaSection encounter={data} onSaved={onSaved} onReload={onReload} />
            </div>
            <aside aria-label="Visit actions" className="min-w-0 flex-[1_1_300px]">
              <FinalizeSection encounter={data} onSaved={onSaved} onReload={onReload} />
              <ConsultBilling encounter={data} />
              <PatientTimeline patientId={data.patient.id} currentId={data.id} />
            </aside>
          </div>
        </div>
      )}
    </section>
  )
}
