import { QueryClientProvider } from '@tanstack/react-query'
import { render } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { AppRoutes, createQueryClient } from '../app/App'
import type { Session } from '../api/types'

export function session(permissions: string[], mustChangePassword = false): Session {
  return {
    user: { id: 'u-1', fullName: 'Test Admin', roles: ['ADMIN'], mustChangePassword },
    permissions,
    organization: { id: 'o-1', name: 'Demo Clinic', timezone: 'Asia/Kolkata' },
  }
}

export function json(status: number, body?: unknown): Response {
  return new Response(body === undefined ? null : JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

type Handler = (init: RequestInit | undefined) => Response | Promise<Response>

/** Stubs fetch with "METHOD /path" handlers; an unhandled call fails the test loudly. */
export function mockApi(routes: Record<string, Handler | Response>) {
  const calls: { key: string; init?: RequestInit }[] = []
  const fn = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const key = `${init?.method ?? 'GET'} ${String(input)}`
    calls.push({ key, init })
    const handler = routes[key]
    if (!handler) throw new Error(`unhandled fetch: ${key}`)
    return typeof handler === 'function' ? handler(init) : handler.clone()
  })
  vi.stubGlobal('fetch', fn)
  return { calls }
}

export function renderApp(path: string) {
  return render(
    <QueryClientProvider client={createQueryClient()}>
      <MemoryRouter initialEntries={[path]}>
        <AppRoutes />
      </MemoryRouter>
    </QueryClientProvider>,
  )
}
