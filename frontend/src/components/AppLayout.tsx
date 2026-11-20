import { useMutation, useQueryClient } from '@tanstack/react-query'
import { useEffect } from 'react'
import { Link, NavLink, Outlet, useLocation, useNavigate } from 'react-router'
import { logout } from '../api/auth'
import { SESSION_KEY, useSession } from '../features/auth/useSession'
import { hasPermission } from '../lib/permissions'
import { secondaryButton } from './ui'

const linkClass = ({ isActive }: { isActive: boolean }) =>
  `rounded px-3 py-1 text-sm ${isActive ? 'bg-blue-100 font-medium text-blue-900' : 'text-slate-700 hover:bg-slate-100'}`

/** Browser tab titles per section, so tabs and history entries are distinguishable. */
const TITLES: [string, string][] = [
  ['/users/new', 'New user'], ['/users/import', 'Import users'], ['/users', 'Users'], ['/roles', 'Roles'], ['/patients', 'Patients'],
  ['/appointments', 'Appointments'], ['/queue', 'Queue'],
  ['/doctors', 'Doctors'], ['/staff', 'Staff'], ['/catalog', 'Departments & specialties'], ['/audit', 'Audit'], ['/', 'Home'],
]

export function AppLayout() {
  const { data } = useSession()
  const queryClient = useQueryClient()
  const navigate = useNavigate()
  const signOut = useMutation({
    mutationFn: logout,
    onSettled: () => {
      queryClient.clear()
      queryClient.setQueryData(SESSION_KEY, null)
      navigate('/login', { replace: true })
    },
  })
  const can = (permission: string) => hasPermission(data?.permissions, permission)
  const { pathname } = useLocation()
  useEffect(() => {
    const section = TITLES.find(([prefix]) => pathname.startsWith(prefix))?.[1]
    document.title = section ? `${section} · HMS` : 'HMS'
  }, [pathname])

  return (
    <div className="min-h-screen bg-slate-50 text-slate-900">
      <header className="flex flex-wrap items-center gap-4 border-b bg-white px-6 py-3">
        <Link to="/" className="font-semibold hover:underline">{data?.organization.name}</Link>
        <nav aria-label="Main" className="flex gap-2">
          <NavLink to="/" end className={linkClass}>Home</NavLink>
          {can('patient:search') && <NavLink to="/patients" className={linkClass}>Patients</NavLink>}
          {can('appointment:read') && <NavLink to="/appointments" className={linkClass}>Appointments</NavLink>}
          {can('appointment:read') && <NavLink to="/queue" className={linkClass}>Queue</NavLink>}
          {can('practitioner:read') && <NavLink to="/doctors" className={linkClass}>Doctors</NavLink>}
          {can('staff:read') && <NavLink to="/staff" className={linkClass}>Staff</NavLink>}
          {can('user:read') && <NavLink to="/users" className={linkClass}>Users</NavLink>}
          {can('role:read') && <NavLink to="/roles" className={linkClass}>Roles</NavLink>}
          {can('catalog:read') && <NavLink to="/catalog" className={linkClass}>Departments &amp; specialties</NavLink>}
          {can('audit:read') && <NavLink to="/audit" className={linkClass}>Audit</NavLink>}
        </nav>
        <span className="ml-auto text-sm text-slate-600">{data?.user.fullName}</span>
        <button type="button" className={secondaryButton} onClick={() => signOut.mutate()} disabled={signOut.isPending}>
          Sign out
        </button>
      </header>
      <main className="mx-auto max-w-5xl p-6">
        <Outlet />
      </main>
    </div>
  )
}
