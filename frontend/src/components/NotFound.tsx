import { Link } from 'react-router'

export function NotFound() {
  return (
    <section className="p-8">
      <h1 className="text-xl font-semibold">Page not found</h1>
      <Link className="mt-4 inline-block text-blue-700 underline" to="/">Back to home</Link>
    </section>
  )
}
