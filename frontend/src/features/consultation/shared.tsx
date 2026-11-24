import { useMutation, useQueryClient } from '@tanstack/react-query'
import type { ReactNode } from 'react'
import { ApiError } from '../../api/client'
import { clinicalKeys, type Encounter } from '../../api/clinical'
import { FormError } from '../../components/ui'
import { errorMessage } from '../auth/messages'
import { MutationProblem } from '../appointments/BookAppointmentDialog'

/** What every section of the consult screen receives. */
export interface SectionProps {
  encounter: Encounter
  /** The server's latest full encounter after a successful save. */
  onSaved: (view: Encounter) => void
  /** Re-read the encounter and reset the forms to it. */
  onReload: () => void
}

export const TREATING_DOCTOR_ONLY = 'Only the treating doctor (or a user allowed to edit any encounter) can do this.'

/** 403 on a clinical write is the treating-practitioner rule; everything else is mapped as usual. */
export function consultErrorMessage(error: unknown): string {
  if (error instanceof ApiError && error.status === 403) return TREATING_DOCTOR_ONLY
  return errorMessage(error)
}

/** A failed save: reload prompt for a version conflict, treating-doctor text for 403, else the mapped message. */
export function ConsultProblem({ error, onReload }: { error: unknown; onReload: () => void }) {
  if (!error) return null
  if (error instanceof ApiError && error.status === 403) return <FormError message={TREATING_DOCTOR_ONLY} />
  return <MutationProblem error={error} what="encounter" onReload={onReload} />
}

/** Saves one part of the encounter; a finalized-elsewhere answer refreshes the page into read-only. */
export function useEncounterSave<V>({ encounter, onSaved }: SectionProps, save: (variables: V) => Promise<Encounter>) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: save,
    onSuccess: onSaved,
    onError: (error) => {
      if (error instanceof ApiError && error.code === 'ENCOUNTER_FINALIZED') {
        void queryClient.invalidateQueries({ queryKey: clinicalKeys.encounter(encounter.id) })
      }
    },
  })
}

export function Section({ title, children }: { title: string; children: ReactNode }) {
  return (
    <section className="mb-5 rounded-xl border border-border bg-surface p-5">
      <h2 className="mb-3 text-lg font-semibold">{title}</h2>
      {children}
    </section>
  )
}

/** Latest text of a note kind (the server keeps earlier text as history). */
export function currentNote(encounter: Encounter, kind: string): string {
  const notes = encounter.notes.filter((n) => n.kind === kind)
  return notes.length ? notes[notes.length - 1].text : ''
}
