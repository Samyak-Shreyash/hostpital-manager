import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useEffect, useState } from 'react'
import { Link } from 'react-router'
import { ApiError } from '../../api/client'
import { createUser, listRoles } from '../../api/users'
import { primaryButton } from '../../components/ui'
import { errorMessage } from '../auth/messages'
import { TemporaryPassword } from './TemporaryPassword'
import { UserForm } from './UserForm'

interface Created {
  password: string
  account: string
}

export function UserCreatePage() {
  const queryClient = useQueryClient()
  const roles = useQuery({ queryKey: ['roles'], queryFn: listRoles })
  const [created, setCreated] = useState<Created | null>(null)
  const mutation = useMutation({ mutationFn: createUser })

  // The one-time password lives only in this component: warn before a reload or tab close loses it.
  useEffect(() => {
    if (!created) return
    const warn = (e: BeforeUnloadEvent) => e.preventDefault()
    window.addEventListener('beforeunload', warn)
    return () => window.removeEventListener('beforeunload', warn)
  }, [created])

  if (created) {
    return (
      <section>
        <h1 className="mb-4 text-2xl font-semibold">User created</h1>
        <TemporaryPassword password={created.password} account={created.account} onDismiss={() => setCreated(null)} />
        <div className="flex items-center gap-4">
          <button type="button" className={primaryButton} onClick={() => setCreated(null)}>Create another user</button>
          <Link className="text-blue-700 underline" to="/users">Back to users</Link>
        </div>
      </section>
    )
  }

  const duplicate = mutation.error instanceof ApiError && mutation.error.code === 'DUPLICATE_EMAIL'
  return (
    <section>
      <h1 className="mb-4 text-2xl font-semibold">New user</h1>
      {roles.isError && <p role="alert" className="mb-4 text-red-700">{errorMessage(roles.error)}</p>}
      <UserForm
        mode="create"
        roleOptions={(roles.data ?? []).map((r) => ({ code: r.code, name: r.name }))}
        submitting={mutation.isPending}
        emailError={duplicate ? errorMessage(mutation.error) : null}
        serverError={mutation.isError && !duplicate ? errorMessage(mutation.error) : null}
        onSubmit={(values) =>
          mutation.mutate(values, {
            onSuccess: (user) => {
              setCreated({ password: user.temporaryPassword, account: `${values.fullName.trim()} (${values.email.trim()})` })
              mutation.reset() // do not keep the temporary password in the mutation cache
              void queryClient.invalidateQueries({ queryKey: ['users'] })
            },
          })
        }
      />
    </section>
  )
}
