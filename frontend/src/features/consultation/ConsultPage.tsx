import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { Link, useParams } from 'react-router'
import { clinicalKeys, getEncounter, type Encounter } from '../../api/clinical'
import { errorMessage } from '../auth/messages'
import { useCan } from '../auth/useSession'
import { DiagnosesSection, NotesSection, PlanSection, VitalsSection } from './ClinicalSections'
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

  const onSaved = (view: Encounter) => {
    queryClient.setQueryData(clinicalKeys.encounter(view.id), view)
    void queryClient.invalidateQueries({ queryKey: clinicalKeys.history(view.patient.id) })
  }
  const onReload = () => {
    void queryClient.invalidateQueries({ queryKey: clinicalKeys.encounter(id) }).then(() => setEpoch((n) => n + 1))
  }

  return (
    <section>
      <h1 className="mb-4 text-2xl font-semibold">Consultation</h1>
      {encounter.isPending && <p role="status">Loading...</p>}
      {encounter.isError && <p role="alert" className="text-red-700">{errorMessage(encounter.error)}</p>}
      {data && (
        <div key={epoch}>
          <section aria-label="Encounter summary" className="mb-6 rounded bg-white p-4 shadow">
            <p className="flex flex-wrap items-center gap-3">
              <span className="text-lg font-medium">{data.patient.uhid ?? 'Patient'}</span>
              <span className="rounded bg-slate-200 px-2 py-0.5 text-xs">{data.status === 'OPEN' ? 'Open' : 'Finalized'}</span>
              {canOpenPatient && <Link className="text-sm text-blue-700 underline" to={`/patients/${data.patient.id}`}>Patient record</Link>}
            </p>
            {data.chiefComplaint && <p className="mt-2 text-sm"><span className="font-medium">Chief complaint:</span> {data.chiefComplaint}</p>}
            {data.status === 'FINALIZED' && <p className="mt-2 text-sm text-slate-600">This encounter is finalized and read-only. Corrections are added as addenda.</p>}
          </section>
          <VitalsSection encounter={data} onSaved={onSaved} onReload={onReload} />
          <NotesSection encounter={data} onSaved={onSaved} onReload={onReload} />
          <DiagnosesSection encounter={data} onSaved={onSaved} onReload={onReload} />
          <PlanSection encounter={data} onSaved={onSaved} onReload={onReload} />
          <PrescriptionSection encounter={data} onReload={onReload} />
          <FinalizeSection encounter={data} onSaved={onSaved} onReload={onReload} />
          <AddendaSection encounter={data} onSaved={onSaved} onReload={onReload} />
          <PatientTimeline patientId={data.patient.id} currentId={data.id} />
        </div>
      )}
    </section>
  )
}
