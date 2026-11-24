import type { Allergy, Patient } from '../../api/patients'
import { ageSex } from './format'

interface Props {
  patient: Pick<Patient, 'uhid' | 'fullName' | 'sex' | 'dateOfBirth'>
  /** Undefined while loading or when the viewer may not see allergies; the indicator is then omitted. */
  allergies?: Allergy[]
}

/**
 * Patient identity strip for every patient-context screen (registration detail now; consultation, admission and
 * billing later). Shows the UHID, never the internal id.
 */
export function PatientBanner({ patient, allergies }: Props) {
  const active = allergies?.filter((a) => a.status === 'ACTIVE') ?? []
  return (
    <div role="region" aria-label="Patient banner" className="mb-4 flex flex-wrap items-center gap-x-6 gap-y-1 rounded border bg-surface px-4 py-3">
      <span className="text-lg font-semibold">{patient.fullName}</span>
      <span className="text-sm"><span className="text-ink-muted">UHID </span><span className="font-mono">{patient.uhid}</span></span>
      <span className="text-sm">{ageSex(patient.dateOfBirth, patient.sex)}</span>
      {allergies !== undefined &&
        (active.length > 0 ? (
          <span className="rounded bg-red-100 px-2 py-0.5 text-sm font-medium text-danger" title={active.map((a) => a.substance).join(', ')}>
            Allergies: {active.length}
          </span>
        ) : (
          <span className="rounded bg-muted px-2 py-0.5 text-sm text-ink-muted">No allergies recorded</span>
        ))}
    </div>
  )
}
