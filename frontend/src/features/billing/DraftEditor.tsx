import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { applyPercentDiscount, billingKeys, discardInvoice, listServices, putInvoiceItems, type Invoice, type ItemInput } from '../../api/billing'
import { ApiError } from '../../api/client'
import { FormError, inputClass, primaryButton, secondaryButton } from '../../components/ui'
import { isMoneyAmount, isNonZero, isPercent, isQuantity } from '../../lib/money'
import { errorMessage } from '../auth/messages'
import { useCan } from '../auth/useSession'
import { ConflictNotice } from '../practitioners/shared'
import { IssueButton } from './IssueButton'
import { AMOUNT_HINT, applyInvoice, billingErrorMessage } from './shared'

/** One editable line. Every value is the string the user typed or the server sent; nothing is parsed to a number. */
interface Row {
  key: number
  serviceId: string | null
  description: string
  quantity: string
  unitPrice: string
  /** Only an edited price is sent for a catalog line; otherwise the server uses the catalog price. */
  priceEdited: boolean
  discount: string
  discountReason: string
  taxRate: string
}
type RowErrors = Partial<Record<'description' | 'quantity' | 'unitPrice' | 'discount' | 'discountReason' | 'taxRate' | 'line', string>>

let nextKey = 1
const fromServer = (invoice: Invoice): Row[] => invoice.items.map((i) => ({
  key: nextKey++, serviceId: i.serviceId, description: i.description, quantity: i.quantity, unitPrice: i.unitPrice, priceEdited: false,
  discount: i.discount, discountReason: i.discountReason ?? '', taxRate: i.taxRate,
}))

const DISCOUNT_REASON_MAX = 200

/** What the server needs for one line; omits the price of an untouched catalog line. */
function toInput(row: Row, canDiscount: boolean): ItemInput {
  const discounted = canDiscount && isNonZero(row.discount)
  return {
    ...(row.serviceId ? { serviceId: row.serviceId } : { description: row.description.trim() }),
    quantity: row.quantity.trim(),
    ...(!row.serviceId || row.priceEdited ? { unitPrice: row.unitPrice.trim() } : {}),
    ...(discounted ? { discount: row.discount.trim(), discountReason: row.discountReason.trim() } : {}),
    taxRate: row.taxRate.trim(),
  }
}

function validate(rows: Row[], canDiscount: boolean): RowErrors[] {
  return rows.map((r) => {
    const e: RowErrors = {}
    if (!r.serviceId && !r.description.trim()) e.description = 'Enter a description'
    if (!isQuantity(r.quantity.trim())) e.quantity = 'Enter a quantity such as 1 or 2.5'
    if ((!r.serviceId || r.priceEdited) && !isMoneyAmount(r.unitPrice.trim())) e.unitPrice = AMOUNT_HINT
    if (!isPercent(r.taxRate.trim())) e.taxRate = 'Enter a rate such as 0 or 18'
    if (canDiscount) {
      if (!isMoneyAmount(r.discount.trim())) e.discount = AMOUNT_HINT
      else if (isNonZero(r.discount) && !r.discountReason.trim()) e.discountReason = 'Give a reason for the discount'
      else if (r.discountReason.trim().length > DISCOUNT_REASON_MAX) e.discountReason = `Reason is at most ${DISCOUNT_REASON_MAX} characters`
    }
    return e
  })
}

/** Server `items[i].field` errors, placed on the row they name. */
function serverRowErrors(error: unknown, count: number): { rows: RowErrors[]; other: string[] } {
  const rows: RowErrors[] = Array.from({ length: count }, () => ({}))
  const other: string[] = []
  if (!(error instanceof ApiError)) return { rows, other }
  for (const fe of error.fieldErrors) {
    const m = /^items\[(\d+)\]\.?(\w*)$/.exec(fe.field)
    const index = m ? Number(m[1]) : -1
    if (m && index >= 0 && index < count) {
      const field = (m[2] || 'line') as keyof RowErrors
      rows[index][field] = fe.message
    } else other.push(fe.message)
  }
  return { rows, other }
}

const PRICE_403 = 'You do not have permission to change prices. Put the catalog price back (or remove the custom line), or ask someone who can override prices.'
const DISCOUNT_403 = 'You do not have permission to give discounts.'

/** The editable DRAFT: lines, catalog picker, discounts, save (totals come back from the server), issue, discard. */
export function DraftEditor({ invoice, onReload }: { invoice: Invoice; onReload: () => void }) {
  const queryClient = useQueryClient()
  const canUpdate = useCan('invoice:update')
  const canIssue = useCan('invoice:issue')
  const canDiscount = useCan('invoice:discount')
  const canOverride = useCan('invoice:price-override')
  const canCatalog = useCan('catalog:read')
  const [initial] = useState(() => fromServer(invoice))
  const [rows, setRows] = useState<Row[]>(initial)
  const [serviceId, setServiceId] = useState('')
  const [rowErrors, setRowErrors] = useState<RowErrors[]>([])
  const [formError, setFormError] = useState<string | null>(null)
  const [confirmDiscard, setConfirmDiscard] = useState(false)
  const [percent, setPercent] = useState('')
  const [percentReason, setPercentReason] = useState('')
  const [percentErrors, setPercentErrors] = useState<{ percent?: string; reason?: string }>({})

  const services = useQuery({ queryKey: billingKeys.services, queryFn: () => listServices({ active: true, size: 100 }), enabled: canCatalog && canUpdate })
  const dirty = JSON.stringify(rows) !== JSON.stringify(initial)
  const lockedByDiscount = !canDiscount && rows.some((r) => isNonZero(r.discount))

  const refresh = (view: Invoice) => applyInvoice(queryClient, view)
  const save = useMutation({
    mutationFn: () => putInvoiceItems(invoice.id, { version: invoice.version, items: rows.map((r) => toInput(r, canDiscount)) }),
    onSuccess: refresh,
    onError: (error) => {
      const { rows: fromServer, other } = serverRowErrors(error, rows.length)
      setRowErrors(fromServer)
      if (error instanceof ApiError && error.status === 403) {
        setFormError(rows.some((r) => !r.serviceId || r.priceEdited) ? PRICE_403 : rows.some((r) => isNonZero(r.discount)) ? DISCOUNT_403 : billingErrorMessage(error))
      } else if (error instanceof ApiError && error.code === 'CONCURRENT_MODIFICATION') setFormError(null)
      else setFormError(other.length ? other.join(' ') : fromServer.some((e) => Object.keys(e).length) ? null : billingErrorMessage(error))
    },
  })
  const percentMutation = useMutation({
    mutationFn: () => applyPercentDiscount(invoice.id, { version: invoice.version, percent: percent.trim(), reason: percentReason.trim() }),
    onSuccess: refresh,
  })
  const discard = useMutation({
    mutationFn: () => discardInvoice(invoice.id, invoice.version),
    onSuccess: refresh,
  })

  const edit = (key: number, patch: Partial<Row>) => setRows((current) => current.map((r) => (r.key === key ? { ...r, ...patch } : r)))
  const addService = () => {
    const service = services.data?.items.find((s) => s.id === serviceId)
    if (!service || service.currentPrice === null) return
    setRows([...rows, { key: nextKey++, serviceId: service.id, description: service.name, quantity: '1', unitPrice: service.currentPrice, priceEdited: false, discount: '0.00', discountReason: '', taxRate: '0.00' }])
    setServiceId('')
  }
  const addCustom = () => setRows([...rows, { key: nextKey++, serviceId: null, description: '', quantity: '1', unitPrice: '', priceEdited: true, discount: '0.00', discountReason: '', taxRate: '0.00' }])

  const submitSave = () => {
    setFormError(null)
    if (rows.length === 0) { setFormError('Add at least one line'); return }
    const errors = validate(rows, canDiscount)
    setRowErrors(errors)
    if (errors.some((e) => Object.keys(e).length)) return
    save.mutate()
  }
  const submitPercent = () => {
    const next: typeof percentErrors = {}
    if (!isPercent(percent.trim())) next.percent = 'Enter a percentage such as 10 or 12.5'
    if (!percentReason.trim()) next.reason = 'Give a reason for the discount'
    else if (percentReason.trim().length > DISCOUNT_REASON_MAX) next.reason = `Reason is at most ${DISCOUNT_REASON_MAX} characters`
    setPercentErrors(next)
    if (Object.keys(next).length === 0) percentMutation.mutate()
  }

  if (!canUpdate) return <ReadOnlyHint />

  const conflict = [save.error, percentMutation.error, discard.error].find((e) => e instanceof ApiError && e.code === 'CONCURRENT_MODIFICATION')
  const rowError = (index: number, field: keyof RowErrors) => rowErrors[index]?.[field]
  const err = (id: string, message?: string) => message ? <p id={id} role="alert" className="text-xs text-danger">{message}</p> : null

  return (
    <div>
      {conflict != null && <ConflictNotice what="invoice" onReload={onReload} />}
      <table className="mb-3 w-full text-left text-sm">
        <caption className="sr-only">Invoice lines</caption>
        <thead>
          <tr className="border-b">
            <th className="py-1">Item</th><th>Qty</th><th>Unit price</th>{canDiscount && <th>Discount</th>}<th>Tax %</th><th className="text-right">Line total</th><th />
          </tr>
        </thead>
        <tbody>
          {rows.map((r, i) => {
            const n = i + 1
            const server = invoice.items.find((it, index) => index === i && it.serviceId === r.serviceId)
            return (
              <tr key={r.key} className="border-b align-top">
                <td className="py-1 pr-2">
                  {r.serviceId ? <span className="font-medium">{r.description}</span> : (
                    <input aria-label={`Description ${n}`} className={inputClass} value={r.description} onChange={(e) => edit(r.key, { description: e.target.value })} />
                  )}
                  {err(`d${n}`, rowError(i, 'description') ?? rowError(i, 'line'))}
                </td>
                <td className="pr-2">
                  <input aria-label={`Quantity ${n}`} inputMode="decimal" className={`${inputClass} w-20`} value={r.quantity} onChange={(e) => edit(r.key, { quantity: e.target.value })} />
                  {err(`q${n}`, rowError(i, 'quantity'))}
                </td>
                <td className="pr-2">
                  <input aria-label={`Unit price ${n}`} inputMode="decimal" className={`${inputClass} w-28`} value={r.unitPrice} onChange={(e) => edit(r.key, { unitPrice: e.target.value, priceEdited: true })} />
                  {err(`p${n}`, rowError(i, 'unitPrice'))}
                </td>
                {canDiscount && (
                  <td className="pr-2">
                    <input aria-label={`Discount ${n}`} inputMode="decimal" className={`${inputClass} w-24`} value={r.discount} onChange={(e) => edit(r.key, { discount: e.target.value })} />
                    {err(`x${n}`, rowError(i, 'discount'))}
                    {isNonZero(r.discount) && (
                      <>
                        <input aria-label={`Discount reason ${n}`} placeholder="Reason" className={inputClass} value={r.discountReason} onChange={(e) => edit(r.key, { discountReason: e.target.value })} />
                        {err(`r${n}`, rowError(i, 'discountReason'))}
                      </>
                    )}
                  </td>
                )}
                <td className="pr-2">
                  <input aria-label={`Tax rate ${n}`} inputMode="decimal" className={`${inputClass} w-20`} value={r.taxRate} onChange={(e) => edit(r.key, { taxRate: e.target.value })} />
                  {err(`t${n}`, rowError(i, 'taxRate'))}
                </td>
                <td className="text-right tabular-nums">{server && !dirty ? server.total : ''}</td>
                <td className="text-right">
                  <button type="button" className="text-danger underline" aria-label={`Remove line ${n}`} onClick={() => setRows(rows.filter((x) => x.key !== r.key))}>Remove</button>
                </td>
              </tr>
            )
          })}
        </tbody>
      </table>

      <div className="mb-3 flex flex-wrap items-end gap-3">
        {canCatalog && (
          <div className="text-sm">
            <label htmlFor="invoice-service" className="block font-medium">Service</label>
            <select id="invoice-service" className={inputClass} value={serviceId} onChange={(e) => setServiceId(e.target.value)}>
              <option value="">Select a service</option>
              {services.data?.items.map((s) => (
                <option key={s.id} value={s.id} disabled={s.currentPrice === null}>{s.name} ({s.currentPrice ?? 'no price yet'})</option>
              ))}
            </select>
          </div>
        )}
        {canCatalog && <button type="button" className={secondaryButton} disabled={!serviceId} onClick={addService}>Add service</button>}
        {canOverride && <button type="button" className={secondaryButton} onClick={addCustom}>Add custom line</button>}
      </div>
      {services.isError && <FormError message={errorMessage(services.error)} />}
      {!canCatalog && <p className="mb-3 text-sm text-ink-muted">You need access to the service catalog to add services.</p>}

      <FormError message={formError} />
      {lockedByDiscount && <p role="status" className="mb-2 text-sm text-ink-muted">This invoice carries a discount; only users who may give discounts can change its lines.</p>}
      <div className="mb-4 flex flex-wrap items-start gap-4">
        <button type="button" className={primaryButton} disabled={save.isPending || lockedByDiscount} onClick={submitSave}>Save items</button>
        {canIssue && (
          <IssueButton invoice={invoice} disabled={dirty || invoice.items.length === 0} hint={dirty ? 'Save your changes before issuing.' : invoice.items.length === 0 ? 'Add and save at least one line to issue.' : undefined} />
        )}
        {!confirmDiscard ? (
          <button type="button" className={secondaryButton} onClick={() => setConfirmDiscard(true)}>Discard draft</button>
        ) : (
          <span className="flex items-center gap-2 text-sm">
            Discard this draft?
            <button type="button" className={primaryButton} disabled={discard.isPending} onClick={() => discard.mutate()}>Confirm discard</button>
            <button type="button" className={secondaryButton} onClick={() => setConfirmDiscard(false)}>Keep</button>
          </span>
        )}
      </div>
      {discard.isError && !conflict && <FormError message={billingErrorMessage(discard.error)} />}

      {canDiscount && (
        <fieldset className="rounded border border-border p-3" disabled={dirty}>
          <legend className="px-1 text-sm font-medium">Percent discount on all lines</legend>
          <div className="flex flex-wrap items-start gap-3 text-sm">
            <div>
              <label htmlFor="disc-percent" className="block">Discount percent</label>
              <input id="disc-percent" inputMode="decimal" className={`${inputClass} w-28`} value={percent} onChange={(e) => setPercent(e.target.value)} />
              {percentErrors.percent && <p role="alert" className="text-xs text-danger">{percentErrors.percent}</p>}
            </div>
            <div>
              <label htmlFor="disc-reason" className="block">Reason for all lines</label>
              <input id="disc-reason" className={inputClass} value={percentReason} onChange={(e) => setPercentReason(e.target.value)} />
              {percentErrors.reason && <p role="alert" className="text-xs text-danger">{percentErrors.reason}</p>}
            </div>
            <button type="button" className={`${secondaryButton} mt-6`} disabled={percentMutation.isPending} onClick={submitPercent}>Apply percent discount</button>
          </div>
          {dirty && <p className="mt-1 text-xs text-ink-muted">Save your line changes first.</p>}
          {percentMutation.isError && !conflict && <div className="mt-2"><FormError message={billingErrorMessage(percentMutation.error)} /></div>}
        </fieldset>
      )}
    </div>
  )
}

function ReadOnlyHint() {
  return <p className="text-sm text-ink-muted">You can view this draft but not change it.</p>
}
