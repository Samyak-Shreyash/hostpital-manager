import { zodResolver } from '@hookform/resolvers/zod'
import { useForm } from 'react-hook-form'
import { z } from 'zod'
import type { AuditFilters } from '../../api/audit'
import { Field, inputClass, primaryButton, secondaryButton } from '../../components/ui'

const CODE = /^[A-Z][A-Z0-9_]{1,63}$/
const EMAIL = /^[^\s@]+@[^\s@]+$/
const UUID = /^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$/

const optional = (pattern: RegExp, message: string) =>
  z.string().trim().refine((v) => v === '' || pattern.test(v), message)

const optionalDateTime = z
  .string()
  .refine((v) => v === '' || !Number.isNaN(new Date(v).getTime()), 'Enter a valid date and time')

/** Date-time inputs hold local time; they are sent to the API as UTC instants. */
export const auditFilterSchema = z
  .object({
    actorId: z.string().trim().refine((v) => v === '' || UUID.test(v) || EMAIL.test(v), 'Enter the actor’s email or user id'),
    action: optional(CODE, 'Action must be UPPER_SNAKE_CASE, e.g. PATIENT_VIEWED'),
    entityType: optional(CODE, 'Entity type must be UPPER_SNAKE_CASE, e.g. PATIENT'),
    entityId: optional(UUID, 'Entity id must be an id like 123e4567-e89b-12d3-a456-426614174000'),
    from: optionalDateTime,
    to: optionalDateTime,
  })
  .refine((v) => !v.from || !v.to || new Date(v.from).getTime() <= new Date(v.to).getTime(), {
    path: ['to'],
    message: 'To must not be before From',
  })
export type AuditFilterValues = z.infer<typeof auditFilterSchema>

/** Common codes offered as suggestions; the fields stay free text so codes added later still work. */
const ACTIONS = [
  'LOGIN_SUCCEEDED', 'LOGIN_FAILED', 'LOGOUT', 'PASSWORD_CHANGED', 'USER_CREATED', 'USER_UPDATED', 'USER_VIEWED',
  'USER_ACTIVATED', 'USER_DEACTIVATED', 'USER_PASSWORD_RESET', 'ROLE_CREATED', 'ROLE_UPDATED', 'ORGANIZATION_UPDATED',
  'PATIENT_CREATED', 'PATIENT_UPDATED', 'PATIENT_VIEWED', 'PATIENT_SEARCH', 'PATIENT_IDENTITY_CHANGED',
  'PATIENT_DUPLICATE_OVERRIDDEN', 'PATIENT_ALLERGY_ADDED', 'PATIENT_HISTORY_ADDED', 'PRACTITIONER_CREATED',
  'PRACTITIONER_UPDATED', 'STAFF_CREATED', 'STAFF_UPDATED', 'SERVICE_CREATED', 'SERVICE_UPDATED', 'AUDIT_VIEWED',
]
const ENTITY_TYPES = ['APP_USER', 'ROLE', 'ORGANIZATION', 'PATIENT', 'PATIENT_ALLERGY', 'PATIENT_HISTORY', 'PRACTITIONER', 'STAFF', 'AVAILABILITY_EXCEPTION', 'SERVICE', 'SERVICE_PRICE', 'AUDIT_EVENT']

const EMPTY: AuditFilterValues = { actorId: '', action: '', entityType: '', entityId: '', from: '', to: '' }

/** Form values to query filters: empty fields dropped, local date-times converted to UTC instants. */
export function toAuditFilters(values: AuditFilterValues): AuditFilters {
  const filters: AuditFilters = {}
  if (values.actorId) filters.actorId = values.actorId
  if (values.action) filters.action = values.action
  if (values.entityType) filters.entityType = values.entityType
  if (values.entityId) filters.entityId = values.entityId
  if (values.from) filters.from = new Date(values.from).toISOString()
  if (values.to) filters.to = new Date(values.to).toISOString()
  return filters
}

interface Props {
  onApply: (filters: AuditFilters) => void
  /** Turns an actor email into a user id; null when no user of this organization has it. Absent = ids only. */
  resolveEmail?: (email: string) => Promise<string | null>
}

export function AuditFilterForm({ onApply, resolveEmail }: Props) {
  const form = useForm<AuditFilterValues>({ resolver: zodResolver(auditFilterSchema), defaultValues: EMPTY })
  const { errors } = form.formState
  const apply = async (values: AuditFilterValues) => {
    if (EMAIL.test(values.actorId)) {
      const id = resolveEmail ? await resolveEmail(values.actorId) : null
      if (!id) {
        form.setError('actorId', { message: resolveEmail ? 'No user has this email' : 'Enter the actor’s user id' })
        return
      }
      values = { ...values, actorId: id }
    }
    onApply(toAuditFilters(values))
  }
  return (
    <form
      noValidate
      aria-label="Audit filters"
      onSubmit={form.handleSubmit(apply)}
      className="mb-4 grid gap-x-4 sm:grid-cols-2 lg:grid-cols-3"
    >
      <Field label="Actor (email or id)" htmlFor="actorId" error={errors.actorId?.message}>
        <input id="actorId" className={inputClass} autoComplete="off" {...form.register('actorId')} />
      </Field>
      <Field label="Action" htmlFor="action" error={errors.action?.message}>
        <input id="action" className={inputClass} autoComplete="off" list="audit-actions" placeholder="Any action" {...form.register('action')} />
        <datalist id="audit-actions">{ACTIONS.map((a) => <option key={a} value={a} />)}</datalist>
      </Field>
      <Field label="Entity type" htmlFor="entityType" error={errors.entityType?.message}>
        <input id="entityType" className={inputClass} autoComplete="off" list="audit-entity-types" placeholder="Any type" {...form.register('entityType')} />
        <datalist id="audit-entity-types">{ENTITY_TYPES.map((t) => <option key={t} value={t} />)}</datalist>
      </Field>
      <Field label="Entity id" htmlFor="entityId" error={errors.entityId?.message}>
        <input id="entityId" className={inputClass} autoComplete="off" {...form.register('entityId')} />
      </Field>
      <Field label="From" htmlFor="from" error={errors.from?.message}>
        <input id="from" type="datetime-local" className={inputClass} {...form.register('from')} />
      </Field>
      <Field label="To" htmlFor="to" error={errors.to?.message}>
        <input id="to" type="datetime-local" className={inputClass} {...form.register('to')} />
      </Field>
      <div className="flex gap-3 sm:col-span-2 lg:col-span-3">
        <button type="submit" className={primaryButton}>Apply filters</button>
        <button
          type="button"
          className={secondaryButton}
          onClick={() => {
            form.reset(EMPTY)
            onApply({})
          }}
        >
          Clear
        </button>
      </div>
    </form>
  )
}
