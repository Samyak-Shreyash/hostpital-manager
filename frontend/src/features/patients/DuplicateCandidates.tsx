import { useState } from 'react'
import { Link } from 'react-router'
import type { DuplicateCandidate } from '../../api/patients'
import { Field, inputClass, secondaryButton } from '../../components/ui'
import { overrideReasonSchema } from './schemas'

interface Props {
  candidates: DuplicateCandidate[]
  canOverride: boolean
  submitting?: boolean
  /** Label of the override button, e.g. "Register anyway" or "Save anyway". */
  actionLabel: string
  onOverride: (reason: string) => void
}

/** DUPLICATE_SUSPECTED panel (BR-P3): masked candidates; override with a reason only for patient:override-duplicate. */
export function DuplicateCandidates({ candidates, canOverride, submitting, actionLabel, onOverride }: Props) {
  const [reason, setReason] = useState('')
  const [reasonError, setReasonError] = useState<string | null>(null)
  const submit = () => {
    const parsed = overrideReasonSchema.safeParse(reason)
    if (!parsed.success) {
      setReasonError(parsed.error.issues[0].message)
      return
    }
    onOverride(parsed.data)
  }
  return (
    <div role="region" aria-label="Possible duplicates" className="mt-6 max-w-xl rounded border border-amber-300 bg-amber-50 p-4">
      <h2 className="mb-2 font-semibold">This patient may already be registered</h2>
      <ul className="mb-3 text-sm">
        {candidates.map((c) => (
          <li key={c.id} className="mb-1">
            <Link className="text-blue-700 underline" to={`/patients/${encodeURIComponent(c.id)}`}>{c.fullName}</Link>
            {' '}<span className="font-mono">{c.uhid}</span> · {c.dateOfBirth ?? 'DOB unknown'} · {c.phoneMasked}
          </li>
        ))}
      </ul>
      {canOverride ? (
        <>
          <Field label="Reason this is a different person" htmlFor="overrideReason" error={reasonError ?? undefined}>
            <textarea
              id="overrideReason"
              className={inputClass}
              maxLength={500}
              value={reason}
              onChange={(e) => { setReason(e.target.value); setReasonError(null) }}
            />
          </Field>
          <button type="button" className={secondaryButton} disabled={submitting} onClick={submit}>{actionLabel}</button>
        </>
      ) : (
        <p className="text-sm">Open the existing record, or ask a user allowed to override duplicates to do this.</p>
      )}
    </div>
  )
}
