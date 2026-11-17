import { zodResolver } from '@hookform/resolvers/zod'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { useForm } from 'react-hook-form'
import { z } from 'zod'
import {
  type CatalogItem,
  createCatalogItem,
  listDepartments,
  listSpecialties,
  practitionerKeys,
  updateCatalogItem,
} from '../../api/practitioners'
import { Field, FormError, inputClass, primaryButton, secondaryButton } from '../../components/ui'
import { errorMessage } from '../auth/messages'
import { useCan } from '../auth/useSession'

/** Mirrors Create/UpdateDepartmentRequest and the Specialty equivalents. */
export const catalogFormSchema = z.object({
  code: z.string().regex(/^[A-Z0-9_]{2,20}$/, 'Use 2-20 characters of A-Z, 0-9 or _, e.g. CARDIO'),
  name: z.string().trim().min(1, 'Name is required').max(100, 'Name is too long'),
})
type CatalogFormValues = z.infer<typeof catalogFormSchema>

const SECTIONS = [
  { path: 'departments', title: 'Departments', singular: 'department', list: listDepartments, key: practitionerKeys.departments },
  { path: 'specialties', title: 'Specialties', singular: 'specialty', list: listSpecialties, key: practitionerKeys.specialties },
] as const

type Section = (typeof SECTIONS)[number]

function CatalogForm({ section, item, onDone }: { section: Section; item: CatalogItem | null; onDone: () => void }) {
  const queryClient = useQueryClient()
  const form = useForm<CatalogFormValues>({
    resolver: zodResolver(catalogFormSchema),
    defaultValues: { code: item?.code ?? '', name: item?.name ?? '' },
  })
  const mutation = useMutation({
    mutationFn: (v: CatalogFormValues) =>
      item
        ? updateCatalogItem(section.path, item.id, { version: item.version, name: v.name })
        : createCatalogItem(section.path, v),
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: section.key })
      onDone()
    },
  })
  const { errors } = form.formState
  const prefix = section.path
  return (
    <form noValidate onSubmit={form.handleSubmit((v) => mutation.mutate(v))} className="mb-4 max-w-xl rounded bg-white p-4 shadow">
      <h3 className="mb-3 font-semibold">{item ? `Edit ${section.singular} ${item.code}` : `New ${section.singular}`}</h3>
      <FormError message={mutation.isError ? errorMessage(mutation.error) : null} />
      <Field label="Code" htmlFor={`${prefix}-code`} error={errors.code?.message}>
        <input id={`${prefix}-code`} className={inputClass} readOnly={!!item} {...form.register('code')} />
      </Field>
      <Field label="Name" htmlFor={`${prefix}-name`} error={errors.name?.message}>
        <input id={`${prefix}-name`} className={inputClass} {...form.register('name')} />
      </Field>
      <div className="flex gap-3">
        <button type="submit" className={primaryButton} disabled={mutation.isPending}>{item ? 'Save' : `Create ${section.singular}`}</button>
        <button type="button" className={secondaryButton} onClick={onDone}>Cancel</button>
      </div>
    </form>
  )
}

function CatalogSection({ section, canManage }: { section: Section; canManage: boolean }) {
  const queryClient = useQueryClient()
  const items = useQuery({ queryKey: section.key, queryFn: section.list })
  // 'new' | the item being edited | null
  const [editing, setEditing] = useState<'new' | CatalogItem | null>(null)
  const toggle = useMutation({
    mutationFn: (item: CatalogItem) => updateCatalogItem(section.path, item.id, { version: item.version, active: !item.active }),
    onSettled: () => queryClient.invalidateQueries({ queryKey: section.key }),
  })

  return (
    <section aria-labelledby={`${section.path}-heading`} className="mb-8">
      <div className="mb-3 flex items-center justify-between">
        <h2 id={`${section.path}-heading`} className="text-xl font-semibold">{section.title}</h2>
        {canManage && editing === null && (
          <button type="button" className={primaryButton} onClick={() => setEditing('new')}>New {section.singular}</button>
        )}
      </div>
      {canManage && editing !== null && (
        <CatalogForm
          key={editing === 'new' ? 'new' : `${editing.id}-${editing.version}`}
          section={section}
          item={editing === 'new' ? null : editing}
          onDone={() => setEditing(null)}
        />
      )}
      {toggle.isError && <p role="alert" className="text-red-700">{errorMessage(toggle.error)}</p>}
      {items.isError && <p role="alert" className="text-red-700">{errorMessage(items.error)}</p>}
      {items.isPending && <p role="status">Loading...</p>}
      {items.data && items.data.items.length === 0 && <p className="text-sm text-slate-600">No {section.title.toLowerCase()} yet.</p>}
      {items.data && items.data.items.length > 0 && (
        <table className="w-full border-collapse bg-white text-left text-sm shadow">
          <thead>
            <tr className="border-b bg-slate-100">
              <th className="p-2">Code</th><th className="p-2">Name</th><th className="p-2">Status</th>
              {canManage && <th className="p-2"><span className="sr-only">Actions</span></th>}
            </tr>
          </thead>
          <tbody>
            {items.data.items.map((item) => (
              <tr key={item.id} className="border-b">
                <td className="p-2 font-mono">{item.code}</td>
                <td className="p-2">{item.name}</td>
                <td className="p-2">{item.active ? 'Active' : 'Inactive'}</td>
                {canManage && (
                  <td className="space-x-3 p-2">
                    <button type="button" className="text-blue-700 underline" aria-label={`Edit ${item.name}`} onClick={() => setEditing(item)}>Edit</button>
                    <button
                      type="button"
                      className="text-blue-700 underline"
                      aria-label={`${item.active ? 'Deactivate' : 'Activate'} ${item.name}`}
                      disabled={toggle.isPending}
                      onClick={() => toggle.mutate(item)}
                    >
                      {item.active ? 'Deactivate' : 'Activate'}
                    </button>
                  </td>
                )}
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </section>
  )
}

export function CatalogPage() {
  const canManage = useCan('catalog:manage')
  return (
    <section>
      <h1 className="mb-4 text-2xl font-semibold">Departments &amp; specialties</h1>
      {SECTIONS.map((s) => <CatalogSection key={s.path} section={s} canManage={canManage} />)}
    </section>
  )
}
