import { zodResolver } from '@hookform/resolvers/zod'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { useForm } from 'react-hook-form'
import { useNavigate } from 'react-router'
import { z } from 'zod'
import { changePassword } from '../../api/auth'
import { Field, FormError, inputClass, primaryButton } from '../../components/ui'
import { errorMessage } from './messages'
import { SESSION_KEY } from './useSession'

export const changePasswordSchema = z
  .object({
    currentPassword: z.string().min(1, 'Current password is required').max(128),
    newPassword: z.string().min(1, 'New password is required').max(1000),
    confirmPassword: z.string().min(1, 'Confirm the new password'),
  })
  .refine((v) => v.newPassword === v.confirmPassword, { path: ['confirmPassword'], message: 'Passwords do not match' })
  .refine((v) => v.newPassword !== v.currentPassword, { path: ['newPassword'], message: 'Choose a different password' })
type Values = z.infer<typeof changePasswordSchema>

export function ChangePasswordPage() {
  const queryClient = useQueryClient()
  const navigate = useNavigate()
  const form = useForm<Values>({
    resolver: zodResolver(changePasswordSchema),
    defaultValues: { currentPassword: '', newPassword: '', confirmPassword: '' },
  })
  const mutation = useMutation({
    mutationFn: (v: Values) => changePassword(v.currentPassword, v.newPassword),
    onSuccess: async () => {
      form.reset()
      await queryClient.invalidateQueries({ queryKey: SESSION_KEY })
      navigate('/', { replace: true })
    },
  })
  const { errors } = form.formState
  return (
    <main className="flex min-h-screen items-center justify-center bg-slate-50 p-4">
      <form className="w-full max-w-sm rounded bg-white p-6 shadow" noValidate onSubmit={form.handleSubmit((v) => mutation.mutate(v))}>
        <h1 className="mb-2 text-xl font-semibold">Change your password</h1>
        <p className="mb-4 text-sm text-slate-600">You must set a new password before continuing.</p>
        <FormError message={mutation.isError ? errorMessage(mutation.error) : null} />
        <Field label="Current password" htmlFor="currentPassword" error={errors.currentPassword?.message}>
          <input id="currentPassword" type="password" autoComplete="current-password" className={inputClass} {...form.register('currentPassword')} />
        </Field>
        <Field label="New password" htmlFor="newPassword" error={errors.newPassword?.message}>
          <input id="newPassword" type="password" autoComplete="new-password" className={inputClass} {...form.register('newPassword')} />
        </Field>
        <Field label="Confirm new password" htmlFor="confirmPassword" error={errors.confirmPassword?.message}>
          <input id="confirmPassword" type="password" autoComplete="new-password" className={inputClass} {...form.register('confirmPassword')} />
        </Field>
        <button type="submit" className={primaryButton} disabled={mutation.isPending}>Change password</button>
      </form>
    </main>
  )
}
