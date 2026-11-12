import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { Link, useParams } from 'react-router'
import { ApiError } from '../../api/client'
import { getUser, listRoles, resetUserPassword, setUserActive, updateUser } from '../../api/users'
import { FormError, secondaryButton } from '../../components/ui'
import { errorMessage } from '../auth/messages'
import { useCan, useSession } from '../auth/useSession'
import { TemporaryPassword } from './TemporaryPassword'
import { UserForm } from './UserForm'

export function UserEditPage() {
  const { id = '' } = useParams()
  const queryClient = useQueryClient()
  const canUpdate = useCan('user:update')
  const canReset = useCan('user:reset-password')
  const session = useSession()
  const user = useQuery({ queryKey: ['users', 'detail', id], queryFn: () => getUser(id) })
  const roles = useQuery({ queryKey: ['roles'], queryFn: listRoles })
  const [temporaryPassword, setTemporaryPassword] = useState<string | null>(null)
  const [saved, setSaved] = useState(false)

  const refresh = () => {
    void queryClient.invalidateQueries({ queryKey: ['users'] })
  }
  const save = useMutation({
    mutationFn: (v: { fullName: string; roleCodes: string[] }) =>
      updateUser(id, { version: user.data!.version, fullName: v.fullName, roleCodes: v.roleCodes }),
    onSuccess: (updated) => {
      queryClient.setQueryData(['users', 'detail', id], updated)
      setSaved(true)
      refresh()
    },
  })
  const lifecycle = useMutation({
    mutationFn: (active: boolean) => setUserActive(id, active),
    onSuccess: (updated) => {
      queryClient.setQueryData(['users', 'detail', id], updated)
      refresh()
    },
  })
  const reset = useMutation({ mutationFn: () => resetUserPassword(id) })

  if (user.isPending) return <p role="status">Loading...</p>
  if (user.isError) {
    const notFound = user.error instanceof ApiError && user.error.status === 404
    return <p role="alert" className="text-red-700">{notFound ? 'User not found.' : errorMessage(user.error)}</p>
  }
  const u = user.data
  const isSelf = session.data?.user.id === u.id
  const conflict = save.isError && save.error instanceof ApiError && save.error.code === 'CONCURRENT_MODIFICATION'

  return (
    <section>
      <h1 className="mb-1 text-2xl font-semibold">{u.fullName}</h1>
      <p className="mb-4 text-sm text-slate-600">Status: {u.status}</p>
      {temporaryPassword && <TemporaryPassword password={temporaryPassword} onDismiss={() => setTemporaryPassword(null)} />}
      <FormError message={lifecycle.isError ? errorMessage(lifecycle.error) : reset.isError ? errorMessage(reset.error) : null} />
      {saved && <p role="status" className="mb-4 text-sm text-green-800">Changes saved.</p>}
      {canUpdate ? (
        <UserForm
          key={`${u.id}-${u.version}`}
          mode="edit"
          roleOptions={(roles.data ?? []).map((r) => ({ code: r.code, name: r.name }))}
          initial={{ email: u.email, fullName: u.fullName, roleCodes: u.roleCodes }}
          submitting={save.isPending}
          serverError={save.isError ? errorMessage(save.error) : null}
          onSubmit={(values) => {
            setSaved(false)
            save.mutate({ fullName: values.fullName, roleCodes: values.roleCodes })
          }}
        />
      ) : (
        <dl className="mb-4 text-sm">
          <dt className="font-medium">Email</dt><dd>{u.email}</dd>
          <dt className="mt-2 font-medium">Roles</dt><dd>{u.roleCodes.join(', ') || 'None'}</dd>
        </dl>
      )}
      {conflict && (
        <button type="button" className="mb-4 text-sm text-blue-700 underline" onClick={() => { save.reset(); void user.refetch() }}>
          Reload latest version
        </button>
      )}
      <div className="mt-6 flex flex-wrap gap-3">
        {canUpdate && u.status === 'ACTIVE' && !isSelf && (
          <button type="button" className={secondaryButton} disabled={lifecycle.isPending} onClick={() => lifecycle.mutate(false)}>Deactivate</button>
        )}
        {canUpdate && u.status === 'INACTIVE' && (
          <button type="button" className={secondaryButton} disabled={lifecycle.isPending} onClick={() => lifecycle.mutate(true)}>Activate</button>
        )}
        {canReset && (
          <button
            type="button"
            className={secondaryButton}
            disabled={reset.isPending}
            onClick={() =>
              reset.mutate(undefined, {
                onSuccess: (result) => {
                  const { temporaryPassword: shownOnce, ...summary } = result
                  setTemporaryPassword(shownOnce)
                  reset.reset() // keep the temporary password out of the mutation cache
                  queryClient.setQueryData(['users', 'detail', id], summary)
                  refresh()
                },
              })
            }
          >
            Reset password
          </button>
        )}
      </div>
      <Link className="mt-6 inline-block text-blue-700 underline" to="/users">Back to users</Link>
    </section>
  )
}
