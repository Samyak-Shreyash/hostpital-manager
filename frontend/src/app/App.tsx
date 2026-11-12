import { MutationCache, QueryCache, QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { BrowserRouter, Route, Routes } from 'react-router'
import { ApiError } from '../api/client'
import { AppLayout } from '../components/AppLayout'
import { NotFound } from '../components/NotFound'
import { ChangePasswordPage } from '../features/auth/ChangePasswordPage'
import { RequireAuth, RequirePermission } from '../features/auth/guards'
import { LoginPage } from '../features/auth/LoginPage'
import { SESSION_KEY } from '../features/auth/useSession'
import { HomePage } from '../features/HomePage'
import { RolesPage } from '../features/roles/RolesPage'
import { UserCreatePage } from '../features/users/UserCreatePage'
import { UserEditPage } from '../features/users/UserEditPage'
import { UserListPage } from '../features/users/UserListPage'

export function createQueryClient(): QueryClient {
  // While signed in, a 401 (session ended) or 403 PASSWORD_CHANGE_REQUIRED (admin reset) re-reads /auth/me so
  // the route guards redirect. Failed logins (401 with no session yet) are left alone.
  const onError = (error: unknown) => {
    if (!(error instanceof ApiError)) return
    if ((error.status === 401 || error.code === 'PASSWORD_CHANGE_REQUIRED') && client.getQueryData(SESSION_KEY)) {
      void client.invalidateQueries({ queryKey: SESSION_KEY })
    }
  }
  const client: QueryClient = new QueryClient({
    queryCache: new QueryCache({ onError }),
    mutationCache: new MutationCache({ onError }),
    defaultOptions: { queries: { retry: false, refetchOnWindowFocus: false } },
  })
  return client
}

/** Routes without a router, so tests can supply a MemoryRouter. */
export function AppRoutes() {
  return (
    <Routes>
      <Route path="/login" element={<LoginPage />} />
      <Route element={<RequireAuth allowPasswordChange />}>
        <Route path="/change-password" element={<ChangePasswordPage />} />
      </Route>
      <Route element={<RequireAuth />}>
        <Route element={<AppLayout />}>
          <Route index element={<HomePage />} />
          <Route element={<RequirePermission permission="user:read" />}>
            <Route path="users" element={<UserListPage />} />
            <Route path="users/:id" element={<UserEditPage />} />
          </Route>
          <Route element={<RequirePermission permission="user:create" />}>
            <Route path="users/new" element={<UserCreatePage />} />
          </Route>
          <Route element={<RequirePermission permission="role:read" />}>
            <Route path="roles" element={<RolesPage />} />
          </Route>
          <Route path="*" element={<NotFound />} />
        </Route>
      </Route>
    </Routes>
  )
}

const queryClient = createQueryClient()

export function App() {
  return (
    <QueryClientProvider client={queryClient}>
      <BrowserRouter>
        <AppRoutes />
      </BrowserRouter>
    </QueryClientProvider>
  )
}
