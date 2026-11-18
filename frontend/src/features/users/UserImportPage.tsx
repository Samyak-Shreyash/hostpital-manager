import { useMutation, useQueryClient } from '@tanstack/react-query'
import { useEffect, useState } from 'react'
import { Link } from 'react-router'
import { ApiError } from '../../api/client'
import type { UserWithTemporaryPassword } from '../../api/types'
import { importUsers } from '../../api/users'
import { FormError, primaryButton, secondaryButton } from '../../components/ui'
import { errorMessage } from '../auth/messages'
import { type ImportRow, parseUserImport, toCsv } from './csv'

interface RowError {
  row: number
  message: string
}

/** IMPORT_REJECTED carries `rows`; bean validation reports fields like `users[2].email` (0-based). */
function rowErrors(error: unknown): RowError[] {
  if (!(error instanceof ApiError)) return []
  if (error.code === 'IMPORT_REJECTED' && Array.isArray(error.properties.rows)) {
    return (error.properties.rows as { row: number; message: string }[]).map((r) => ({ row: r.row, message: r.message }))
  }
  return error.fieldErrors.flatMap((f) => {
    const match = /^users\[(\d+)\]/.exec(f.field)
    return match ? [{ row: Number(match[1]) + 1, message: f.message }] : []
  })
}

export function UserImportPage() {
  const queryClient = useQueryClient()
  const [rows, setRows] = useState<ImportRow[] | null>(null)
  const [parseErrors, setParseErrors] = useState<string[]>([])
  const [created, setCreated] = useState<UserWithTemporaryPassword[] | null>(null)
  const mutation = useMutation({ mutationFn: importUsers })

  // Temporary passwords exist only in this component: warn before a reload or tab close loses them.
  useEffect(() => {
    if (!created) return
    const warn = (e: BeforeUnloadEvent) => e.preventDefault()
    window.addEventListener('beforeunload', warn)
    return () => window.removeEventListener('beforeunload', warn)
  }, [created])

  const readFile = async (file: File | undefined) => {
    mutation.reset()
    if (!file) return
    const parsed = parseUserImport(await file.text())
    setRows(parsed.errors.length === 0 ? parsed.rows : null)
    setParseErrors(parsed.errors)
  }

  if (created) {
    const download = () => {
      const csv = toCsv([['email', 'fullName', 'roles', 'temporaryPassword'],
        ...created.map((u) => [u.email, u.fullName, u.roleCodes.join(';'), u.temporaryPassword])])
      const url = URL.createObjectURL(new Blob([csv], { type: 'text/csv' }))
      const a = document.createElement('a')
      a.href = url
      a.download = 'imported-users.csv'
      a.click()
      URL.revokeObjectURL(url)
    }
    return (
      <section>
        <h1 className="mb-2 text-2xl font-semibold">{created.length} users created</h1>
        <p role="status" className="mb-4 rounded border border-amber-400 bg-amber-50 p-3 text-sm">
          These temporary passwords are shown once. Download them now, pass each one on securely, then delete the file.
          Every user must change the password at first sign-in.
        </p>
        <div className="mb-4 flex gap-3">
          <button type="button" className={primaryButton} onClick={download}>Download CSV</button>
          <Link className={secondaryButton} to="/users">Done</Link>
        </div>
        <div className="overflow-x-auto">
          <table className="w-full border-collapse bg-white text-left text-sm shadow">
            <thead>
              <tr className="border-b bg-slate-100">
                <th className="p-2">Name</th><th className="p-2">Email</th><th className="p-2">Roles</th><th className="p-2">Temporary password</th>
              </tr>
            </thead>
            <tbody>
              {created.map((u) => (
                <tr key={u.id} className="border-b">
                  <td className="p-2">{u.fullName}</td>
                  <td className="p-2">{u.email}</td>
                  <td className="p-2">{u.roleCodes.join(', ')}</td>
                  <td className="p-2 font-mono select-all">{u.temporaryPassword}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </section>
    )
  }

  const serverRows = rowErrors(mutation.error)
  return (
    <section className="max-w-3xl">
      <h1 className="mb-2 text-2xl font-semibold">Import users</h1>
      <p className="mb-4 text-sm text-slate-600">
        Upload a CSV file with the header <code>email,fullName,roles</code>. Separate several role codes with
        <code> ;</code> (for example <code>NURSE;RECEPTIONIST</code>). Up to 200 users per file. If any row has a
        problem, nothing is created.
      </p>
      <label className="mb-4 block text-sm font-medium">
        CSV file
        <input type="file" accept=".csv,text/csv" className="mt-1 block text-sm" onChange={(e) => void readFile(e.target.files?.[0])} />
      </label>
      {parseErrors.length > 0 && (
        <ul role="alert" className="mb-4 list-disc rounded bg-red-50 p-3 pl-8 text-sm text-red-800">
          {parseErrors.map((m) => <li key={m}>{m}</li>)}
        </ul>
      )}
      {serverRows.length > 0 ? (
        <div role="alert" className="mb-4 rounded bg-red-50 p-3 text-sm text-red-800">
          <p className="font-medium">{errorMessage(mutation.error)}</p>
          <ul className="mt-2 list-disc pl-5">
            {serverRows.map((r, i) => <li key={i}>Row {r.row}{rows?.[r.row - 1] ? ` (${rows[r.row - 1].email})` : ''}: {r.message}</li>)}
          </ul>
        </div>
      ) : (
        <FormError message={mutation.isError ? errorMessage(mutation.error) : null} />
      )}
      {rows && (
        <>
          <p className="mb-2 text-sm">{rows.length} user(s) ready to import.</p>
          <div className="mb-4 max-h-80 overflow-auto">
            <table className="w-full border-collapse bg-white text-left text-sm shadow">
              <thead>
                <tr className="border-b bg-slate-100"><th className="p-2">#</th><th className="p-2">Name</th><th className="p-2">Email</th><th className="p-2">Roles</th></tr>
              </thead>
              <tbody>
                {rows.map((r, i) => (
                  <tr key={i} className="border-b">
                    <td className="p-2">{i + 1}</td><td className="p-2">{r.fullName}</td><td className="p-2">{r.email}</td><td className="p-2">{r.roleCodes.join(', ')}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          <button
            type="button"
            className={primaryButton}
            disabled={mutation.isPending}
            onClick={() =>
              mutation.mutate(rows, {
                onSuccess: (result) => {
                  setCreated(result.items)
                  mutation.reset() // do not keep the temporary passwords in the mutation cache
                  void queryClient.invalidateQueries({ queryKey: ['users'] })
                },
              })
            }
          >
            {mutation.isPending ? 'Importing…' : `Import ${rows.length} user(s)`}
          </button>
        </>
      )}
      <Link className="ml-4 text-sm text-blue-700 underline" to="/users">Cancel</Link>
    </section>
  )
}
