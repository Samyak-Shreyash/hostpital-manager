import { useMutation, useQueryClient } from '@tanstack/react-query'
import { useNavigate } from 'react-router'
import { appointmentKeys, type QueueEntry } from '../../api/appointments'
import { patientEncounters, startEncounter } from '../../api/clinical'
import { primaryButton, secondaryButton } from '../../components/ui'
import { useCan } from '../auth/useSession'
import { consultErrorMessage } from './shared'

/**
 * Queue-row action: a checked-in visit starts its encounter (the appointment moves to IN_CONSULTATION); a visit already
 * in consultation resumes the open encounter found through the patient's history.
 */
export function StartConsult({ entry }: { entry: QueueEntry }) {
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const canStart = useCan('encounter:create')
  const canRead = useCan('encounter:read')
  const start = useMutation({
    mutationFn: () => startEncounter({ appointmentId: entry.appointmentId }),
    onSuccess: (encounter) => {
      void queryClient.invalidateQueries({ queryKey: appointmentKeys.all })
      navigate(`/consult/${encounter.id}`)
    },
  })
  const resume = useMutation({
    mutationFn: async () => {
      const history = await patientEncounters(entry.patient.id)
      const open = history.items.find((e) => e.status === 'OPEN' && e.appointmentId === entry.appointmentId)
      if (!open) throw new Error('No open encounter found for this visit.')
      return open
    },
    onSuccess: (open) => navigate(`/consult/${open.id}`),
  })
  const error = start.error ?? resume.error
  return (
    <>
      {entry.status === 'CHECKED_IN' && canStart && (
        <button type="button" className={primaryButton} disabled={start.isPending} onClick={() => start.mutate()}>Start consult</button>
      )}
      {entry.status === 'IN_CONSULTATION' && canRead && (
        <button type="button" className={secondaryButton} disabled={resume.isPending} onClick={() => resume.mutate()}>Resume consult</button>
      )}
      {error && <p role="alert" className="basis-full text-sm text-danger">{error instanceof Error && !('status' in error) ? error.message : consultErrorMessage(error)}</p>}
    </>
  )
}
