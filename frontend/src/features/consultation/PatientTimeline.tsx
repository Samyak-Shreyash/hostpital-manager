import { useQuery } from '@tanstack/react-query'
import { Link } from 'react-router'
import { clinicalKeys, patientEncounters } from '../../api/clinical'
import { errorMessage } from '../auth/messages'
import { useCan, useSession } from '../auth/useSession'
import { Section } from './shared'

/** The patient's encounters, newest first (patient-scoped history endpoint); each other visit opens its consult screen. */
export function PatientTimeline({ patientId, currentId }: { patientId: string; currentId: string }) {
  const canRead = useCan('encounter:read')
  const timeZone = useSession().data?.organization.timezone ?? 'UTC'
  const history = useQuery({ queryKey: clinicalKeys.history(patientId), queryFn: () => patientEncounters(patientId), enabled: canRead })
  const when = (iso: string) => new Intl.DateTimeFormat('en-IN', { timeZone, dateStyle: 'medium' }).format(new Date(iso))
  return (
    <Section title="Patient history">
      {history.isPending && <p role="status">Loading...</p>}
      {history.isError && <p role="alert" className="text-sm text-danger">{errorMessage(history.error)}</p>}
      {history.data && history.data.items.length === 0 && <p className="text-sm text-ink-muted">No earlier visits.</p>}
      {history.data && history.data.items.length > 0 && (
        <ul aria-label="Patient timeline" className="space-y-1 text-sm">
          {history.data.items.map((e) => {
            const body = (
              <>
                <span className="font-medium">{when(e.startedAt)}</span>{' '}
                <span className="rounded bg-muted px-1.5 py-0.5 text-xs">{e.status === 'OPEN' ? 'Open' : 'Finalized'}</span>{' '}
                <span>{e.chiefComplaint ?? 'No complaint recorded'}</span>
              </>
            )
            return (
              <li key={e.id}>
                {e.id === currentId ? <span className="text-ink-muted">{body} (this visit)</span> : <Link className="text-primary hover:underline" to={`/consult/${e.id}`}>{body}</Link>}
              </li>
            )
          })}
        </ul>
      )}
    </Section>
  )
}
