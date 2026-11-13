import { zodResolver } from '@hookform/resolvers/zod'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { useForm } from 'react-hook-form'
import { Navigate, useLocation, useNavigate } from 'react-router'
import { z } from 'zod'
import { login } from '../../api/auth'
import { Field, FormError, inputClass, primaryButton } from '../../components/ui'
import { errorMessage } from './messages'
import { SESSION_KEY, useSession } from './useSession'

export const loginSchema = z.object({
  email: z.string().trim().min(1, 'Email is required').max(254).refine((v) => z.email().safeParse(v).success, 'Enter a valid email address'),
  // Password policy applies when a password is set, not at login: only presence and the 128 limit are checked.
  password: z.string().min(1, 'Password is required').max(128, 'Password is too long'),
})
export type LoginValues = z.infer<typeof loginSchema>

export function LoginPage() {
  const queryClient = useQueryClient()
  const navigate = useNavigate()
  const location = useLocation()
  const session = useSession()
  const form = useForm<LoginValues>({ resolver: zodResolver(loginSchema), defaultValues: { email: '', password: '' } })
  const mutation = useMutation({
    mutationFn: (values: LoginValues) => login(values.email, values.password),
    onSuccess: (data) => {
      queryClient.clear() // nothing cached for a previous user may leak into this session
      queryClient.setQueryData(SESSION_KEY, data)
      const from = (location.state as { from?: string } | null)?.from
      navigate(from && from !== '/login' ? from : '/', { replace: true })
    },
  })

  if (session.data && !mutation.isPending && !mutation.isSuccess) return <Navigate to="/" replace />

  const { errors } = form.formState
  return (
    <main className="flex min-h-screen items-center justify-center bg-slate-50 p-4">
      <form
        className="w-full max-w-sm rounded bg-white p-6 shadow"
        onSubmit={form.handleSubmit((values) => mutation.mutate(values))}
        noValidate
      >
        <h1 className="mb-4 text-xl font-semibold">Sign in</h1>
        <FormError message={mutation.isError ? errorMessage(mutation.error) : null} />
        <Field label="Email" htmlFor="email" error={errors.email?.message}>
          <input id="email" type="email" autoComplete="username" className={inputClass} {...form.register('email')} />
        </Field>
        <Field label="Password" htmlFor="password" error={errors.password?.message}>
          <input id="password" type="password" autoComplete="current-password" className={inputClass} {...form.register('password')} />
        </Field>
        <button type="submit" className={primaryButton} disabled={mutation.isPending}>Sign in</button>
      </form>
    </main>
  )
}
