import { useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import { errorMessage } from '../auth/messages'
import { useSession } from '../auth/useSession'
import { hasPermission } from '../../lib/permissions'
import { primaryButton, inputClass } from '../../components/ui'
import { downloadCsv, fetchReport, REPORTS } from './api'

const iso = (d: Date) => `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
const header = (c: string) => {
  const s = c.replace(/([A-Z])/g, ' $1').toLowerCase()
  return s.charAt(0).toUpperCase() + s.slice(1)
}
const isNumeric = (v: unknown) => typeof v === 'number' || (typeof v === 'string' && /^-?\d+(\.\d+)?$/.test(v))

export function ReportsPage() {
  const { data: session } = useSession()
  const allowed = REPORTS.filter((r) => hasPermission(session?.permissions, r.permission))
  const now = new Date()
  const [key, setKey] = useState<string>(allowed[0]?.key ?? '')
  const [from, setFrom] = useState(iso(new Date(now.getFullYear(), now.getMonth(), 1)))
  const [to, setTo] = useState(iso(now))
  const [csvError, setCsvError] = useState<unknown>(null)
  const report = allowed.find((r) => r.key === key) ?? allowed[0]
  const range = report?.ranged ? { from, to } : undefined
  const result = useQuery({
    queryKey: ['report', report?.key, range],
    queryFn: () => fetchReport(report!.path, range),
    enabled: !!report && (!range || (!!from && !!to)),
  })
  if (!report) return <p role="alert">You do not have access to any report.</p>
  const error = result.error ?? csvError

  return (
    <section>
      <h1 className="mb-4 text-2xl font-semibold">Reports</h1>
      <div className="mb-4 flex flex-wrap items-end gap-3">
        <label className="text-sm font-medium">Report
          <select className={inputClass} value={report.key} onChange={(e) => { setKey(e.target.value); setCsvError(null) }}>
            {allowed.map((r) => <option key={r.key} value={r.key}>{r.label}</option>)}
          </select>
        </label>
        {report.ranged && (
          <>
            <label className="text-sm font-medium">From
              <input type="date" className={inputClass} value={from} max={to} onChange={(e) => setFrom(e.target.value)} />
            </label>
            <label className="text-sm font-medium">To
              <input type="date" className={inputClass} value={to} min={from} onChange={(e) => setTo(e.target.value)} />
            </label>
          </>
        )}
        <button type="button" className={primaryButton}
          onClick={() => { setCsvError(null); downloadCsv(report.path, range).catch(setCsvError) }}>
          Download CSV
        </button>
      </div>
      {error != null && <p role="alert" className="text-red-700">{errorMessage(error)}</p>}
      {result.isPending && result.fetchStatus !== 'idle' && <p role="status">Loading...</p>}
      {result.data && (result.data.rows.length === 0 ? <p>No data for this range.</p> : (
        <div className="overflow-x-auto">
          <table className="w-full border-collapse bg-white text-left text-sm shadow">
            <thead>
              <tr className="border-b bg-slate-100">
                {result.data.columns.map((c) => <th key={c} className="p-2">{header(c)}</th>)}
              </tr>
            </thead>
            <tbody>
              {result.data.rows.map((row, i) => (
                <tr key={i} className="border-b">
                  {result.data.columns.map((c) => (
                    <td key={c} className={`p-2 ${isNumeric(row[c]) && !/date|no$|id$/i.test(c) ? 'text-right' : ''}`}>{row[c] ?? ''}</td>
                  ))}
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      ))}
    </section>
  )
}
