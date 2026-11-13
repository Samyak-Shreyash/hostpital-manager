import { Link } from 'react-router'

export function Forbidden() {
  return (
    <section className="p-8">
      <h1 className="text-xl font-semibold">Access denied</h1>
      <p className="mt-2 text-slate-600">You do not have permission to view this page.</p>
      <Link className="mt-4 inline-block text-blue-700 underline" to="/">Back to home</Link>
    </section>
  )
}
