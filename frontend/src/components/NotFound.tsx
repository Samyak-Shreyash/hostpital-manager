import { Link } from 'react-router'

export function NotFound() {
  return (
    <section className="p-8">
      <h1 className="text-xl font-semibold">Page not found</h1>
      <p className="mt-2 text-ink-muted">This address does not exist. Use the menu to reach the pages your role can open.</p>
      <Link className="mt-4 inline-block text-primary underline" to="/">Back to home</Link>
    </section>
  )
}
