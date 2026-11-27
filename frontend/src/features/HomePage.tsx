import { Link, useLocation } from 'react-router'
import { hasPermission } from '../lib/permissions'
import { useSession } from './auth/useSession'

/** Same entries as the main menu, so Home only offers what the signed-in role can open. */
const SHORTCUTS = [
  { permission: 'patient:search', to: '/patients', label: 'Patients', hint: 'Search and register patients' },
  { permission: 'bed:read', to: '/beds', label: 'Bed board', hint: 'Available and occupied beds' },
  { permission: 'admission:read', to: '/admissions', label: 'Admissions', hint: 'Inpatients, transfers and discharge' },
  { permission: 'ward:read', to: '/wards', label: 'Wards and beds', hint: 'Ward and bed administration' },
  { permission: 'practitioner:read', to: '/doctors', label: 'Doctors', hint: 'Doctors, availability and leave' },
  { permission: 'staff:read', to: '/staff', label: 'Staff', hint: 'Non-doctor staff records' },
  { permission: 'user:read', to: '/users', label: 'Users', hint: 'Sign-in accounts and roles' },
  { permission: 'role:read', to: '/roles', label: 'Roles', hint: 'Roles and their permissions' },
  { permission: 'catalog:read', to: '/catalog', label: 'Departments & specialties', hint: 'Departments and specialties' },
  { permission: 'audit:read', to: '/audit', label: 'Audit', hint: 'Who did what, and when' },
]

export function HomePage() {
  const { data } = useSession()
  const notice = (useLocation().state as { notice?: string } | null)?.notice
  const shortcuts = SHORTCUTS.filter((s) => hasPermission(data?.permissions, s.permission))
  return (
    <section>
      <h1 className="font-display text-2xl font-bold">Welcome, {data?.user.fullName}</h1>
      {notice && <p role="status" className="mt-4 rounded bg-success-soft p-3 text-sm text-success">{notice}</p>}
      <p className="mt-2 text-ink-muted">Signed in to {data?.organization.name}.</p>
      {shortcuts.length > 0 ? (
        <ul className="mt-6 grid gap-3 sm:grid-cols-2">
          {shortcuts.map((s) => (
            <li key={s.to}>
              <Link to={s.to} className="block rounded-xl border border-border bg-surface p-4 hover:bg-canvas">
                <span className="font-medium text-primary">{s.label}</span>
                <span className="mt-1 block text-sm text-ink-muted">{s.hint}</span>
              </Link>
            </li>
          ))}
        </ul>
      ) : (
        <p className="mt-4 text-ink-muted">
          No screens are available for your role. Ask your administrator if you expected to see something here.
        </p>
      )}
    </section>
  )
}
