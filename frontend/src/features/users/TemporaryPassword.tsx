/** Shown once, straight after create / reset; held in component state only, never cached or stored. */
export function TemporaryPassword({ password, onDismiss }: { password: string; onDismiss: () => void }) {
  return (
    <div role="status" className="mb-4 rounded border border-amber-400 bg-amber-50 p-4">
      <p className="text-sm font-medium">Temporary password (shown once, the user must change it at first sign-in)</p>
      <code data-testid="temporary-password" className="mt-2 block select-all rounded bg-white p-2 font-mono">{password}</code>
      <p className="mt-2 text-sm text-slate-700">Copy it now and pass it on securely. It cannot be shown again.</p>
      <button type="button" className="mt-2 text-sm text-blue-700 underline" onClick={onDismiss}>I have copied it</button>
    </div>
  )
}
