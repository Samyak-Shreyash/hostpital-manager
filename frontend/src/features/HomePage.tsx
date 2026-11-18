import { Link, useLocation } from 'react-router'
import { hasPermission } from '../lib/permissions'
import { useSession } from './auth/useSession'

/** Same entries as the main menu, so Home only offers what the signed-in role can open. */
const SHORTCUTS = [
  { permission: 'patient:search', to: '/patients', label: 'Patients', hint: 'Search and register patients' },
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
      <h1 className="text-2xl font-semibold">Welcome, {data?.user.fullName}</h1>
      {notice && <p role="status" className="mt-4 rounded bg-green-50 p-3 text-sm text-green-800">{notice}</p>}
      <p className="mt-2 text-slate-600">Signed in to {data?.organization.name}.</p>
      {shortcuts.length > 0 ? (
        <ul className="mt-6 grid gap-3 sm:grid-cols-2">
          {shortcuts.map((s) => (
            <li key={s.to}>
              <Link to={s.to} className="block rounded bg-white p-4 shadow hover:bg-slate-50">
                <span className="font-medium text-blue-800">{s.label}</span>
                <span className="mt-1 block text-sm text-slate-600">{s.hint}</span>
              </Link>
            </li>
          ))}
        </ul>
      ) : (
        <p className="mt-4 text-slate-600">
          No screens are available for your role yet. Appointments, consultations, admissions and billing arrive in later
          releases; ask your administrator if you expected to see something now.
        </p>
      )}
    </section>
  )
}
