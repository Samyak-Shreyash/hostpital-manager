import { Link } from 'react-router'

/** Shown when a signed-in user opens a screen their roles do not allow; names the screen and the missing access. */
export function Forbidden({ screen, permission }: { screen?: string; permission?: string }) {
  return (
    <section role="alert" className="p-8">
      <h1 className="text-xl font-semibold">{screen ? `${screen} is not available to you` : 'Access denied'}</h1>
      <p className="mt-2 text-slate-600">
        Your roles do not include access to this screen
        {permission ? <> (requires <code className="font-mono text-sm">{permission}</code>)</> : null}. Ask an administrator
        if you need it.
      </p>
      <Link className="mt-4 inline-block text-blue-700 underline" to="/">Back to home</Link>
    </section>
  )
}
