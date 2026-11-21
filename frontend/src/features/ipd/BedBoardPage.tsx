import { useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import { Link } from 'react-router'
import { inputClass, primaryButton } from '../../components/ui'
import { useCan } from '../auth/useSession'
import { getAvailability, ipdKeys, listWards, type AvailabilityBed, type WardAvailability } from './api'
import { ipdErrorMessage } from './shared'

function bedState(bed: AvailabilityBed): 'occupied' | 'out' | 'available' {
  if (bed.status === 'OUT_OF_SERVICE') return 'out'
  return bed.occupied ? 'occupied' : 'available'
}

const TILE: Record<ReturnType<typeof bedState>, string> = {
  occupied: 'border-red-300 bg-red-50 text-red-900',
  out: 'border-amber-300 bg-amber-50 text-amber-900',
  available: 'border-green-300 bg-green-50 text-green-900',
}
const TILE_LABEL: Record<ReturnType<typeof bedState>, string> = { occupied: 'Occupied', out: 'Out of service', available: 'Available' }

function WardBoard({ w, canOpenAdmission }: { w: WardAvailability; canOpenAdmission: boolean }) {
  return (
    <section aria-label={`Ward ${w.ward.name}`} className="mb-6 rounded bg-white p-4 shadow">
      <h2 className="font-semibold">
        {w.ward.name} <span className="text-sm font-normal text-slate-600">{w.code}{w.ward.careType === 'CRITICAL_CARE' ? ' · critical care' : ''}</span>
      </h2>
      <p className="mb-3 text-sm" aria-label={`${w.ward.name} counts`}>
        <span data-testid={`occupied-${w.code}`}>Occupied: {w.occupied}</span>{' · '}
        <span data-testid={`available-${w.code}`}>Available: {w.available}</span>{' · '}
        <span data-testid={`out-${w.code}`}>Out of service: {w.outOfService}</span>
      </p>
      <ul className="grid grid-cols-2 gap-2 sm:grid-cols-4">
        {w.beds.map((b) => {
          const state = bedState(b)
          return (
            <li key={b.id} className={`rounded border p-2 text-sm ${TILE[state]}`}>
              <span className="font-medium">{b.bedNo}</span> <span>{TILE_LABEL[state]}</span>
              {state === 'occupied' && canOpenAdmission && b.admissionId && (
                <Link className="ml-2 underline" to={`/admissions/${b.admissionId}`}>Open admission for bed {b.bedNo}</Link>
              )}
            </li>
          )
        })}
      </ul>
    </section>
  )
}

/** Availability grid per ward ([E8-8]). */
export function BedBoardPage() {
  const [wardId, setWardId] = useState('')
  const canCreate = useCan('admission:create')
  const canOpenAdmission = useCan('admission:read')
  const canWards = useCan('ward:read')
  const wards = useQuery({ queryKey: ipdKeys.wards, queryFn: listWards, enabled: canWards })
  const board = useQuery({ queryKey: [...ipdKeys.availability, wardId], queryFn: () => getAvailability(wardId || undefined) })
  return (
    <section>
      <div className="mb-4 flex flex-wrap items-center gap-4">
        <h1 className="text-2xl font-semibold">Bed board</h1>
        {canCreate && <Link to="/admissions/new" className={`${primaryButton} ml-auto`}>Admit patient</Link>}
      </div>
      {canWards && (
        <div className="mb-4 max-w-xs">
          <label htmlFor="board-ward" className="block text-sm font-medium">Ward</label>
          <select id="board-ward" className={inputClass} value={wardId} onChange={(e) => setWardId(e.target.value)}>
            <option value="">All wards</option>
            {wards.data?.items.filter((w) => w.status === 'ACTIVE').map((w) => <option key={w.id} value={w.id}>{w.name}</option>)}
          </select>
        </div>
      )}
      {board.isPending && <p role="status">Loading...</p>}
      {board.isError && <p role="alert" className="text-red-700">{ipdErrorMessage(board.error)}</p>}
      {board.data && board.data.wards.length === 0 && <p className="text-slate-600">No active wards.</p>}
      {board.data?.wards.map((w) => <WardBoard key={w.ward.id} w={w} canOpenAdmission={canOpenAdmission} />)}
    </section>
  )
}
