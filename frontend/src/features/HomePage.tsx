import { useSession } from './auth/useSession'

export function HomePage() {
  const { data } = useSession()
  return (
    <section>
      <h1 className="text-2xl font-semibold">Welcome, {data?.user.fullName}</h1>
      <p className="mt-2 text-slate-600">Signed in to {data?.organization.name}. Use the menu to manage users and roles.</p>
    </section>
  )
}
