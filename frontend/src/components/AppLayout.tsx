import { useMutation, useQueryClient } from '@tanstack/react-query'
import { useEffect } from 'react'
import { Link, NavLink, Outlet, useLocation, useNavigate } from 'react-router'
import { logout } from '../api/auth'
import { SESSION_KEY, useSession } from '../features/auth/useSession'
import { hasPermission } from '../lib/permissions'

const linkClass = ({ isActive }: { isActive: boolean }) =>
  `flex min-h-11 items-center rounded-lg px-3 text-sm ${isActive ? 'bg-primary font-semibold text-white' : 'text-nav-ink hover:bg-nav-hover hover:text-white'}`

/** Sidebar groups; each link is shown only with its permission, and an empty group is hidden. */
type NavItem = { to: string; label: string; show: boolean; end?: boolean }
function NavGroup({ title, items }: { title?: string; items: NavItem[] }) {
  const visible = items.filter((i) => i.show)
  if (visible.length === 0) return null
  return (
    <div className="flex flex-col gap-0.5">
      {title && <span className="px-3 pt-4 pb-1 text-[11px] font-semibold uppercase tracking-wider text-nav-ink-muted">{title}</span>}
      {visible.map((i) => <NavLink key={i.to} to={i.to} end={i.end} className={linkClass}>{i.label}</NavLink>)}
    </div>
  )
}

/** Browser tab titles per section, so tabs and history entries are distinguishable. */
const TITLES: [string, string][] = [
  ['/users/new', 'New user'], ['/users/import', 'Import users'], ['/users', 'Users'], ['/roles', 'Roles'], ['/patients', 'Patients'],
  ['/appointments', 'Appointments'], ['/queue', 'Queue'], ['/consult', 'Consultation'], ['/invoices', 'Billing'],
  ['/beds', 'Bed board'], ['/wards', 'Wards'], ['/admissions', 'Admissions'], ['/doctors', 'Doctors'], ['/staff', 'Staff'], ['/catalog', 'Departments & specialties'], ['/audit', 'Audit'], ['/reports', 'Reports'], ['/', 'Home'],
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
    <div className="flex min-h-screen flex-wrap bg-canvas text-ink">
      <nav aria-label="Main" className="flex w-full flex-col gap-1 bg-nav px-3.5 py-5 md:sticky md:top-0 md:h-screen md:w-60 md:overflow-y-auto">
        <Link to="/" className="mb-3 flex items-center gap-2.5 rounded-lg px-2 py-1 text-white">
          <span aria-hidden="true" className="flex size-9 items-center justify-center rounded-lg bg-accent font-display text-lg font-bold text-nav">H</span>
          <span className="font-display text-[17px] font-bold leading-tight">{data?.organization.name}</span>
        </Link>
        <NavGroup items={[{ to: '/', label: 'Home', show: true, end: true }]} />
        <NavGroup title="Front desk" items={[
          { to: '/patients', label: 'Patients', show: can('patient:search') },
          { to: '/appointments', label: 'Appointments', show: can('appointment:read') },
          { to: '/queue', label: 'OPD queue', show: can('appointment:read') },
        ]} />
        <NavGroup title="IPD" items={[
          { to: '/beds', label: 'Bed board', show: can('bed:read') },
          { to: '/admissions', label: 'Admissions', show: can('admission:read') },
          { to: '/wards', label: 'Wards', show: can('ward:read') },
        ]} />
        <NavGroup title="Accounts" items={[
          { to: '/invoices', label: 'Billing', show: can('invoice:read') },
          { to: '/reports', label: 'Reports', show: can('report:clinical') || can('report:finance') },
        ]} />
        <NavGroup title="Administration" items={[
          { to: '/doctors', label: 'Doctors', show: can('practitioner:read') },
          { to: '/staff', label: 'Staff', show: can('staff:read') },
          { to: '/users', label: 'Users', show: can('user:read') },
          { to: '/roles', label: 'Roles', show: can('role:read') },
          { to: '/catalog', label: 'Departments & specialties', show: can('catalog:read') },
          { to: '/audit', label: 'Audit', show: can('audit:read') },
        ]} />
        <div className="mt-auto flex flex-col gap-2 border-t border-nav-hover px-2 pt-4">
          <span className="text-sm font-semibold text-white">{data?.user.fullName}</span>
          <button type="button" className="min-h-11 rounded-lg border border-nav-ink-muted px-3 text-sm font-semibold text-nav-ink hover:bg-nav-hover disabled:opacity-50"
            onClick={() => signOut.mutate()} disabled={signOut.isPending}>
            Sign out
          </button>
        </div>
      </nav>
      <main className="min-w-0 flex-1 basis-[560px] px-4 py-6 md:px-7">
        <div className="mx-auto max-w-7xl">
          <Outlet />
        </div>
      </main>
    </div>
  )
}
