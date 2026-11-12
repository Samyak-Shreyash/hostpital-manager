import { zodResolver } from '@hookform/resolvers/zod'
import { useForm } from 'react-hook-form'
import { z } from 'zod'
import type { AuditFilters } from '../../api/audit'
import { Field, inputClass, primaryButton, secondaryButton } from '../../components/ui'

const CODE = /^[A-Z][A-Z0-9_]{1,63}$/
const UUID = /^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$/

const optional = (pattern: RegExp, message: string) =>
  z.string().trim().refine((v) => v === '' || pattern.test(v), message)

const optionalDateTime = z
  .string()
  .refine((v) => v === '' || !Number.isNaN(new Date(v).getTime()), 'Enter a valid date and time')

/** Date-time inputs hold local time; they are sent to the API as UTC instants. */
export const auditFilterSchema = z
  .object({
    actorId: optional(UUID, 'Actor must be an id like 123e4567-e89b-12d3-a456-426614174000'),
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
}

export function AuditFilterForm({ onApply }: Props) {
  const form = useForm<AuditFilterValues>({ resolver: zodResolver(auditFilterSchema), defaultValues: EMPTY })
  const { errors } = form.formState
  return (
    <form
      noValidate
      aria-label="Audit filters"
      onSubmit={form.handleSubmit((values) => onApply(toAuditFilters(values)))}
      className="mb-4 grid gap-x-4 sm:grid-cols-2 lg:grid-cols-3"
    >
      <Field label="Actor id" htmlFor="actorId" error={errors.actorId?.message}>
        <input id="actorId" className={inputClass} autoComplete="off" {...form.register('actorId')} />
      </Field>
      <Field label="Action" htmlFor="action" error={errors.action?.message}>
        <input id="action" className={inputClass} autoComplete="off" placeholder="PATIENT_VIEWED" {...form.register('action')} />
      </Field>
      <Field label="Entity type" htmlFor="entityType" error={errors.entityType?.message}>
        <input id="entityType" className={inputClass} autoComplete="off" placeholder="PATIENT" {...form.register('entityType')} />
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
