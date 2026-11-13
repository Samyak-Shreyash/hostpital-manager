import { zodResolver } from '@hookform/resolvers/zod'
import { useForm } from 'react-hook-form'
import { z } from 'zod'
import { Field, FormError, inputClass, primaryButton } from '../../components/ui'

export const userFormSchema = z.object({
  email: z.string().trim().min(1, 'Email is required').max(254).refine((v) => z.email().safeParse(v).success, 'Enter a valid email address'),
  fullName: z.string().trim().min(1, 'Full name is required').max(200, 'Full name is too long'),
  roleCodes: z.array(z.string()).min(1, 'Select at least one role').max(20, 'Select at most 20 roles'),
})
export type UserFormValues = z.infer<typeof userFormSchema>

interface Props {
  mode: 'create' | 'edit'
  roleOptions: { code: string; name: string }[]
  initial?: Partial<UserFormValues>
  submitting?: boolean
  /** Message for a failure not tied to a field (409, 422...). */
  serverError?: string | null
  /** Shown under the email field (DUPLICATE_EMAIL). */
  emailError?: string | null
  onSubmit: (values: UserFormValues) => void
}

export function UserForm({ mode, roleOptions, initial, submitting, serverError, emailError, onSubmit }: Props) {
  const form = useForm<UserFormValues>({
    resolver: zodResolver(userFormSchema),
    defaultValues: { email: '', fullName: '', roleCodes: [], ...initial },
  })
  const { errors } = form.formState
  return (
    <form noValidate onSubmit={form.handleSubmit(onSubmit)} className="max-w-md">
      <FormError message={serverError} />
      <Field label="Email" htmlFor="email" error={errors.email?.message ?? emailError ?? undefined}>
        <input
          id="email"
          type="email"
          className={inputClass}
          readOnly={mode === 'edit'}
          autoComplete="off"
          {...form.register('email')}
        />
      </Field>
      <Field label="Full name" htmlFor="fullName" error={errors.fullName?.message}>
        <input id="fullName" className={inputClass} autoComplete="off" {...form.register('fullName')} />
      </Field>
      <fieldset className="mb-4">
        <legend className="text-sm font-medium">Roles</legend>
        {roleOptions.length === 0 && <p className="text-sm text-slate-600">No roles available.</p>}
        {roleOptions.map((role) => (
          <label key={role.code} className="mt-1 flex items-center gap-2 text-sm">
            <input type="checkbox" value={role.code} {...form.register('roleCodes')} />
            {role.name} <span className="text-slate-500">({role.code})</span>
          </label>
        ))}
        {errors.roleCodes?.message && <p role="alert" className="mt-1 text-sm text-red-700">{errors.roleCodes.message}</p>}
      </fieldset>
      <button type="submit" className={primaryButton} disabled={submitting}>
        {mode === 'create' ? 'Create user' : 'Save changes'}
      </button>
    </form>
  )
}
