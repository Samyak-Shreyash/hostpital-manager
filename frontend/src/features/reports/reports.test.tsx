import { fireEvent, screen } from '@testing-library/react'
import { json, mockApi, renderApp, session } from '../../test/helpers'

// Synthetic data only.
const today = new Date()
const pad = (n: number) => String(n).padStart(2, '0')
const TO = `${today.getFullYear()}-${pad(today.getMonth() + 1)}-${pad(today.getDate())}`
const FROM = `${today.getFullYear()}-${pad(today.getMonth() + 1)}-01`
const report = (rows: unknown[], columns: string[]) => ({ report: 'x', from: null, to: null, generatedAt: '2026-11-22T00:00:00Z', columns, rows })
const REVENUE = report([{ date: '2026-11-01', mode: 'CASH', collected: '500.00', refunded: '0.00', net: '500.00' }], ['date', 'mode', 'collected', 'refunded', 'net'])

describe('reports page', () => {
  it('lists only the reports the permissions allow and loads the ranged one with from/to', async () => {
    const { calls } = mockApi({
      'GET /api/v1/auth/me': json(200, session(['report:finance'])),
      [`GET /api/v1/reports/revenue/daily?from=${FROM}&to=${TO}`]: json(200, REVENUE),
    })
    renderApp('/reports')
    const picker = await screen.findByLabelText('Report')
    expect(Array.from((picker as HTMLSelectElement).options).map((o) => o.text)).toEqual(['Daily revenue', 'Outstanding payments'])
    fireEvent.change(picker, { target: { value: 'revenue' } })
    for (const h of ['Date', 'Mode', 'Collected', 'Refunded', 'Net']) expect(await screen.findByRole('columnheader', { name: h })).toBeInTheDocument()
    expect(screen.getAllByText('500.00').length).toBeGreaterThan(0)
    expect(calls.some((c) => c.key.includes('/reports/revenue/daily?from='))).toBe(true)
  })

  it('shows no date inputs for a snapshot report and an empty state for no rows', async () => {
    mockApi({
      'GET /api/v1/auth/me': json(200, session(['report:finance'])),
      [`GET /api/v1/reports/revenue/daily?from=${FROM}&to=${TO}`]: json(200, REVENUE),
      'GET /api/v1/reports/payments/outstanding': json(200, report([], ['invoiceNo', 'outstanding'])),
    })
    renderApp('/reports')
    fireEvent.change(await screen.findByLabelText('Report'), { target: { value: 'outstanding' } })
    expect(await screen.findByText('No data for this range.')).toBeInTheDocument()
    expect(screen.queryByLabelText('From')).toBeNull()
    expect(screen.getByRole('button', { name: 'Download CSV' })).toBeInTheDocument()
  })

  it('hides the nav link without a report permission', async () => {
    mockApi({ 'GET /api/v1/auth/me': json(200, session(['patient:read'])) })
    renderApp('/')
    await screen.findByRole('navigation')
    expect(screen.queryByRole('link', { name: 'Reports' })).toBeNull()
  })
})
