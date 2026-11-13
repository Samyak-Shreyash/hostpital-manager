import type { CatalogItem } from '../../api/practitioners'

interface SpecialtyProps {
  items: CatalogItem[]
  selected: string[]
  primary: string
  onChange: (selected: string[], primary: string) => void
  error?: string
}

/** Checkboxes pick the specialties; one radio marks the primary (exactly one, api-design.md section 5). */
export function SpecialtyPicker({ items, selected, primary, onChange, error }: SpecialtyProps) {
  const shown = items.filter((i) => i.active || selected.includes(i.id))
  const toggle = (id: string, on: boolean) => {
    const next = on ? [...selected, id] : selected.filter((s) => s !== id)
    onChange(next, next.includes(primary) ? primary : '')
  }
  return (
    <fieldset className="mb-4 rounded border border-slate-200 p-3">
      <legend className="px-1 text-sm font-medium">Specialties</legend>
      {shown.length === 0 && <p className="text-sm text-slate-600">No specialties defined.</p>}
      <table className="text-sm">
        <thead><tr><th className="pr-4 text-left font-medium">Specialty</th><th className="text-left font-medium">Primary</th></tr></thead>
        <tbody>
          {shown.map((i) => (
            <tr key={i.id}>
              <td className="pr-4">
                <label className="flex items-center gap-2">
                  <input type="checkbox" checked={selected.includes(i.id)} onChange={(e) => toggle(i.id, e.target.checked)} />
                  {i.name}{!i.active && ' (inactive)'}
                </label>
              </td>
              <td>
                <input
                  type="radio"
                  name="primarySpecialty"
                  aria-label={`Primary: ${i.name}`}
                  checked={primary === i.id}
                  disabled={!selected.includes(i.id)}
                  onChange={() => onChange(selected, i.id)}
                />
              </td>
            </tr>
          ))}
        </tbody>
      </table>
      {error && <p role="alert" className="mt-1 text-sm text-red-700">{error}</p>}
    </fieldset>
  )
}

export function DepartmentPicker({ items, selected, onChange, error }: { items: CatalogItem[]; selected: string[]; onChange: (s: string[]) => void; error?: string }) {
  const shown = items.filter((i) => i.active || selected.includes(i.id))
  return (
    <fieldset className="mb-4 rounded border border-slate-200 p-3">
      <legend className="px-1 text-sm font-medium">Departments</legend>
      {shown.length === 0 && <p className="text-sm text-slate-600">No departments defined.</p>}
      {shown.map((i) => (
        <label key={i.id} className="flex items-center gap-2 text-sm">
          <input
            type="checkbox"
            checked={selected.includes(i.id)}
            onChange={(e) => onChange(e.target.checked ? [...selected, i.id] : selected.filter((s) => s !== i.id))}
          />
          {i.name}{!i.active && ' (inactive)'}
        </label>
      ))}
      {error && <p role="alert" className="mt-1 text-sm text-red-700">{error}</p>}
    </fieldset>
  )
}
