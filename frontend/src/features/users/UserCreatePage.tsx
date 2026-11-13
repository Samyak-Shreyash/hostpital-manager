import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { Link } from 'react-router'
import { ApiError } from '../../api/client'
import { createUser, listRoles } from '../../api/users'
import { errorMessage } from '../auth/messages'
import { TemporaryPassword } from './TemporaryPassword'
import { UserForm } from './UserForm'

export function UserCreatePage() {
  const queryClient = useQueryClient()
  const roles = useQuery({ queryKey: ['roles'], queryFn: listRoles })
  const [temporaryPassword, setTemporaryPassword] = useState<string | null>(null)
  const mutation = useMutation({ mutationFn: createUser })

  if (temporaryPassword) {
    return (
      <section>
        <h1 className="mb-4 text-2xl font-semibold">User created</h1>
        <TemporaryPassword password={temporaryPassword} onDismiss={() => setTemporaryPassword(null)} />
        <Link className="text-blue-700 underline" to="/users">Back to users</Link>
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
            onSuccess: (created) => {
              setTemporaryPassword(created.temporaryPassword)
              mutation.reset() // do not keep the temporary password in the mutation cache
              void queryClient.invalidateQueries({ queryKey: ['users'] })
            },
          })
        }
      />
    </section>
  )
}
