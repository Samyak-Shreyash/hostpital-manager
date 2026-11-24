import { useEffect, useId, type ReactNode } from 'react'

/** Minimal modal: labelled by its title, closes on Escape or the Close button. */
export function Dialog({ title, onClose, children }: { title: string; onClose: () => void; children: ReactNode }) {
  const titleId = useId()
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') onClose() }
    document.addEventListener('keydown', onKey)
    return () => document.removeEventListener('keydown', onKey)
  }, [onClose])
  return (
    <div className="fixed inset-0 z-10 flex items-center justify-center bg-black/40 p-4">
      <div role="dialog" aria-modal="true" aria-labelledby={titleId} className="max-h-full w-full max-w-lg overflow-auto rounded-xl border border-border bg-surface p-6-lg">
        <div className="mb-4 flex items-start justify-between gap-4">
          <h2 id={titleId} className="text-lg font-semibold">{title}</h2>
          <button type="button" aria-label="Close dialog" className="text-ink-muted hover:text-ink" onClick={onClose}>×</button>
        </div>
        {children}
      </div>
    </div>
  )
}
