import type { ReactNode } from 'react'

export const inputClass =
  'mt-1 block w-full rounded border border-slate-300 px-3 py-2 text-sm focus:border-blue-600 focus:outline-none'
export const primaryButton =
  'rounded bg-blue-700 px-4 py-2 text-sm font-medium text-white hover:bg-blue-800 disabled:opacity-50'
export const secondaryButton =
  'rounded border border-slate-300 px-4 py-2 text-sm font-medium hover:bg-slate-100 disabled:opacity-50'

export function Field({ label, htmlFor, error, children }: { label: string; htmlFor: string; error?: string; children: ReactNode }) {
  return (
    <div className="mb-4">
      <label htmlFor={htmlFor} className="block text-sm font-medium">{label}</label>
      {children}
      {error && <p role="alert" className="mt-1 text-sm text-red-700">{error}</p>}
    </div>
  )
}

export function FormError({ message }: { message?: string | null }) {
  return message ? <p role="alert" className="mb-4 rounded bg-red-50 p-3 text-sm text-red-800">{message}</p> : null
}
