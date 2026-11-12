import { Navigate, Outlet, useLocation } from 'react-router'
import { Forbidden } from '../../components/Forbidden'
import { hasPermission } from '../../lib/permissions'
import { useSession } from './useSession'

function Loading() {
  return <p role="status" className="p-8 text-slate-600">Loading...</p>
}

/**
 * Unauthenticated users go to the login page. With `allowPasswordChange` the guard serves the change-password
 * screen (session required, flag set); otherwise a user who must change their password is sent there first.
 */
export function RequireAuth({ allowPasswordChange = false }: { allowPasswordChange?: boolean }) {
  const { data, isPending, isError } = useSession()
  const location = useLocation()
  if (isPending) return <Loading />
  if (isError) return <p role="alert" className="p-8 text-red-700">The server could not be reached. Reload to try again.</p>
  if (!data) return <Navigate to="/login" replace state={{ from: location.pathname }} />
  if (data.user.mustChangePassword && !allowPasswordChange) return <Navigate to="/change-password" replace />
  if (!data.user.mustChangePassword && allowPasswordChange) return <Navigate to="/" replace />
  return <Outlet />
}

/** Missing permission renders the forbidden page; it must sit inside RequireAuth. */
export function RequirePermission({ permission }: { permission: string }) {
  const { data } = useSession()
  return hasPermission(data?.permissions, permission) ? <Outlet /> : <Forbidden />
}
