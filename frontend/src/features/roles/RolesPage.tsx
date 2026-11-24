import { zodResolver } from '@hookform/resolvers/zod'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { useForm } from 'react-hook-form'
import { z } from 'zod'
import type { Role } from '../../api/types'
import { createRole, listPermissions, listRoles, updateRole } from '../../api/users'
import { Field, FormError, inputClass, primaryButton, secondaryButton } from '../../components/ui'
import { errorMessage } from '../auth/messages'
import { useCan } from '../auth/useSession'

export const roleFormSchema = z.object({
  code: z.string().regex(/^[A-Z][A-Z0-9_]{0,49}$/, 'Use upper snake case, e.g. LAB_TECH (max 50 characters)'),
  name: z.string().trim().min(1, 'Name is required').max(100, 'Name is too long'),
  permissionCodes: z.array(z.string()).max(200),
})
type RoleFormValues = z.infer<typeof roleFormSchema>

function RoleForm({ role, permissions, onDone }: { role: Role | null; permissions: string[]; onDone: () => void }) {
  const queryClient = useQueryClient()
  const form = useForm<RoleFormValues>({
    resolver: zodResolver(roleFormSchema),
    defaultValues: { code: role?.code ?? '', name: role?.name ?? '', permissionCodes: role?.permissions ?? [] },
  })
  const mutation = useMutation({
    mutationFn: (v: RoleFormValues) =>
      role
        ? updateRole(role.id, { version: role.version, name: v.name, permissionCodes: v.permissionCodes })
        : createRole(v),
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: ['roles'] })
      onDone()
    },
  })
  const { errors } = form.formState
  return (
    <form noValidate onSubmit={form.handleSubmit((v) => mutation.mutate(v))} className="mb-6 max-w-xl rounded-xl border border-border bg-surface p-4">
      <h2 className="mb-3 text-lg font-semibold">{role ? `Edit role ${role.code}` : 'New role'}</h2>
      <FormError message={mutation.isError ? errorMessage(mutation.error) : null} />
      <Field label="Code" htmlFor="code" error={errors.code?.message}>
        <input id="code" className={inputClass} readOnly={!!role} {...form.register('code')} />
      </Field>
      <Field label="Name" htmlFor="name" error={errors.name?.message}>
        <input id="name" className={inputClass} {...form.register('name')} />
      </Field>
      <fieldset className="mb-4">
        <legend className="text-sm font-medium">Permissions</legend>
        <div className="grid grid-cols-2 gap-1">
          {permissions.map((p) => (
            <label key={p} className="flex items-center gap-2 text-sm">
              <input type="checkbox" value={p} {...form.register('permissionCodes')} />
              {p}
            </label>
          ))}
        </div>
      </fieldset>
      <div className="flex gap-3">
        <button type="submit" className={primaryButton} disabled={mutation.isPending}>{role ? 'Save role' : 'Create role'}</button>
        <button type="button" className={secondaryButton} onClick={onDone}>Cancel</button>
      </div>
    </form>
  )
}

/** Permissions grouped by resource ("patient:read" -> patient), each action a chip that never wraps mid-token. */
function PermissionChips({ permissions }: { permissions: string[] }) {
  const groups = new Map<string, string[]>()
  for (const p of [...permissions].sort()) {
    const [resource, action = p] = p.split(':')
    groups.set(resource, [...(groups.get(resource) ?? []), action])
  }
  if (groups.size === 0) return <span className="text-ink-muted">None</span>
  return (
    <dl className="space-y-1">
      {[...groups].map(([resource, actions]) => (
        <div key={resource} className="flex flex-wrap items-baseline gap-1">
          <dt className="mr-1 font-medium">{resource}</dt>
          {actions.map((a) => (
            <dd key={a} className="whitespace-nowrap rounded bg-muted px-1.5 py-0.5 font-mono text-xs">{a}</dd>
          ))}
        </div>
      ))}
    </dl>
  )
}

export function RolesPage() {
  const canManage = useCan('role:manage')
  const roles = useQuery({ queryKey: ['roles'], queryFn: listRoles })
  const permissions = useQuery({ queryKey: ['permissions'], queryFn: listPermissions })
  // 'new' | the role being edited | null
  const [editing, setEditing] = useState<'new' | Role | null>(null)

  return (
    <section>
      <div className="mb-4 flex items-center justify-between">
        <h1 className="text-2xl font-semibold">Roles</h1>
        {canManage && editing === null && (
          <button type="button" className={primaryButton} onClick={() => setEditing('new')}>New role</button>
        )}
      </div>
      {canManage && editing !== null && permissions.data && (
        <RoleForm
          key={editing === 'new' ? 'new' : `${editing.id}-${editing.version}`}
          role={editing === 'new' ? null : editing}
          permissions={permissions.data}
          onDone={() => setEditing(null)}
        />
      )}
      {roles.isError && <p role="alert" className="text-danger">{errorMessage(roles.error)}</p>}
      {roles.isPending && <p role="status">Loading...</p>}
      {roles.data && (
        <table className="w-full border-collapse bg-surface text-left text-sm">
          <thead>
            <tr className="border-b bg-muted">
              <th className="p-2">Code</th><th className="p-2">Name</th><th className="p-2">Permissions</th>
              {canManage && <th className="p-2"><span className="sr-only">Actions</span></th>}
            </tr>
          </thead>
          <tbody>
            {roles.data.map((r) => (
              <tr key={r.id} className="border-b align-top">
                <td className="p-2 font-mono">{r.code}</td>
                <td className="p-2">{r.name}</td>
                <td className="p-2"><PermissionChips permissions={r.permissions} /></td>
                {canManage && (
                  <td className="p-2"><button type="button" className="text-primary underline" onClick={() => setEditing(r)}>Edit</button></td>
                )}
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </section>
  )
}
