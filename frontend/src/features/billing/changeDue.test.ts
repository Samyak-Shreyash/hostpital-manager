import { describe, expect, it } from 'vitest'
import { changeDue } from './PaymentDialog'

describe('changeDue', () => {
  it('returns the change in rupees with two decimals', () => {
    expect(changeDue('500', '320.50')).toBe('179.50')
    expect(changeDue('1.00', '0.10')).toBe('0.90')
    expect(changeDue('100', '100')).toBe('0.00')
  })

  it('returns null when the cash received does not cover the amount', () => {
    expect(changeDue('50', '100')).toBeNull()
  })

  it('returns null for values that are not money amounts', () => {
    expect(changeDue('', '100')).toBeNull()
    expect(changeDue('abc', '100')).toBeNull()
  })

  it('has no float drift', () => {
    expect(changeDue('0.30', '0.10')).toBe('0.20')
  })
})
