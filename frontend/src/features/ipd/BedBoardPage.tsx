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

/** States differ in fill and outline, not hue alone. */
const TILE: Record<ReturnType<typeof bedState>, string> = {
  occupied: 'border-nav bg-nav text-white',
  out: 'border-dashed border-border-strong bg-muted text-ink-muted',
  available: 'border-primary bg-surface text-primary',
}
const TILE_LABEL: Record<ReturnType<typeof bedState>, string> = { occupied: 'Occupied', out: 'Out of service', available: 'Available' }

function Legend() {
  return (
    <ul aria-label="Legend" className="flex flex-wrap gap-4 text-sm">
      {(['available', 'occupied', 'out'] as const).map((k) => (
        <li key={k} className="flex items-center gap-2"><span aria-hidden="true" className={`size-4 rounded border-2 ${TILE[k]}`} />{TILE_LABEL[k]}</li>
      ))}
    </ul>
  )
}

function WardBoard({ w, canOpenAdmission }: { w: WardAvailability; canOpenAdmission: boolean }) {
  return (
    <section aria-label={`Ward ${w.ward.name}`} className="mb-5 rounded-xl border border-border bg-surface p-5">
      <div className="mb-3 flex flex-wrap items-baseline gap-x-4 gap-y-1">
        <h2 className="text-lg font-semibold">
          {w.ward.name} <span className="text-sm font-normal text-ink-muted">{w.code}{w.ward.careType === 'CRITICAL_CARE' ? ' · critical care' : ''}</span>
        </h2>
        <p className="text-sm text-ink-muted" aria-label={`${w.ward.name} counts`}>
          <span data-testid={`occupied-${w.code}`}>Occupied: {w.occupied}</span>{' · '}
          <span data-testid={`available-${w.code}`}>Available: {w.available}</span>{' · '}
          <span data-testid={`out-${w.code}`}>Out of service: {w.outOfService}</span>
        </p>
      </div>
      <ul className="grid grid-cols-[repeat(auto-fill,minmax(140px,1fr))] gap-2.5">
        {w.beds.map((b) => {
          const state = bedState(b)
          const body = (
            <>
              <span className="font-display text-base font-bold">{b.bedNo}</span>
              <span className="text-sm">{TILE_LABEL[state]}</span>
            </>
          )
          const tile = `flex min-h-18 flex-col gap-0.5 rounded-lg border-2 px-3 py-2.5 ${TILE[state]}`
          return (
            <li key={b.id}>
              {state === 'occupied' && canOpenAdmission && b.admissionId ? (
                <Link className={`${tile} hover:opacity-90`} to={`/admissions/${b.admissionId}`} aria-label={`Open admission for bed ${b.bedNo}`}>{body}</Link>
              ) : (
                <div className={tile}>{body}</div>
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
        <h1 className="text-3xl font-semibold">Bed board</h1>
        {board.data && <Occupancy wards={board.data.wards} />}
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
      <div className="mb-4"><Legend /></div>
      {board.isPending && <p role="status">Loading...</p>}
      {board.isError && <p role="alert" className="text-danger">{ipdErrorMessage(board.error)}</p>}
      {board.data && board.data.wards.length === 0 && <p className="text-ink-muted">No active wards.</p>}
      {board.data?.wards.map((w) => <WardBoard key={w.ward.id} w={w} canOpenAdmission={canOpenAdmission} />)}
    </section>
  )
}

function Occupancy({ wards }: { wards: WardAvailability[] }) {
  const occupied = wards.reduce((n, w) => n + w.occupied, 0)
  const usable = wards.reduce((n, w) => n + w.occupied + w.available, 0)
  if (usable === 0) return null
  return <span className="text-sm text-ink-muted">{occupied} of {usable} beds occupied · {Math.round((occupied * 100) / usable)}%</span>
}
