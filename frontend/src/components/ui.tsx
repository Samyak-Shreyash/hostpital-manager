import type { ReactNode } from 'react'

export const inputClass =
  'mt-1 block min-h-11 w-full rounded-lg border border-border-strong bg-surface px-3 py-2 text-[15px] text-ink focus:border-primary'
export const primaryButton =
  'inline-flex min-h-11 items-center justify-center gap-1.5 rounded-lg bg-primary px-4 text-sm font-semibold text-white hover:bg-primary-hover disabled:opacity-50'
export const secondaryButton =
  'inline-flex min-h-11 items-center justify-center gap-1.5 rounded-lg border border-border-strong bg-surface px-4 text-sm font-semibold text-ink hover:bg-muted disabled:opacity-50'

export function Field({ label, htmlFor, error, children }: { label: string; htmlFor: string; error?: string; children: ReactNode }) {
  return (
    <div className="mb-4">
      <label htmlFor={htmlFor} className="block text-[13px] font-semibold text-ink-muted">{label}</label>
      {children}
      {error && <p role="alert" className="mt-1 text-sm text-danger">{error}</p>}
    </div>
  )
}

export function FormError({ message }: { message?: string | null }) {
  return message ? <p role="alert" className="mb-4 rounded-lg bg-danger-soft p-3 text-sm text-danger">{message}</p> : null
}
