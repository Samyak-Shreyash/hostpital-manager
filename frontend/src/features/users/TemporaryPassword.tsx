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
    <div role="status" className="mb-4 rounded border border-amber-400 bg-amber-50 p-4">
      <p className="text-sm font-medium">
        Temporary password{account ? <> for <strong>{account}</strong></> : null} (shown once, the user must change it at first sign-in)
      </p>
      <div className="mt-2 flex items-center gap-2">
        <code data-testid="temporary-password" className="block flex-1 select-all rounded bg-white p-2 font-mono">{password}</code>
        <button type="button" className="rounded border border-slate-300 bg-white px-3 py-2 text-sm hover:bg-slate-100" onClick={() => void copy()}>
          {copied ? 'Copied' : 'Copy'}
        </button>
      </div>
      <p className="mt-2 text-sm text-slate-700">Copy it now and pass it on securely. It cannot be shown again.</p>
      <button type="button" className="mt-2 text-sm text-blue-700 underline" onClick={onDismiss}>Hide password</button>
    </div>
  )
}
