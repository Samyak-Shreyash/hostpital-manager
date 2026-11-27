import { useState } from 'react'

/** Shown once, straight after create / reset; held in component state only, never cached or stored. */
export function TemporaryPassword({ password, account, onDismiss }: { password: string; account?: string; onDismiss: () => void }) {
  const [copied, setCopied] = useState(false)
  const copy = async () => {
    try {
      await navigator.clipboard.writeText(password)
      setCopied(true)
    } catch {
      setCopied(false) // clipboard blocked (insecure origin); the text stays selectable
    }
  }
  return (
    <div role="status" className="mb-4 rounded-md border border-warning/30 bg-warning-soft p-4">
      <p className="text-sm font-medium">
        Temporary password{account ? <> for <strong>{account}</strong></> : null} (shown once, the user must change it at first sign-in)
      </p>
      <div className="mt-2 flex items-center gap-2">
        <code data-testid="temporary-password" className="block flex-1 select-all rounded bg-surface p-2 font-mono">{password}</code>
        <button type="button" className="rounded border border-border-strong bg-surface px-3 py-2 text-sm hover:bg-muted" onClick={() => void copy()}>
          {copied ? 'Copied' : 'Copy'}
        </button>
      </div>
      <p className="mt-2 text-sm text-ink-muted">Copy it now and pass it on securely. It cannot be shown again.</p>
      <button type="button" className="mt-2 text-sm text-primary underline" onClick={onDismiss}>Hide password</button>
    </div>
  )
}
