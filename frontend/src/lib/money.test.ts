import { isMoneyAmount, isNonZero, isPercent, isQuantity, newIdempotencyKey } from './money'

describe('money patterns', () => {
  it('accepts plain decimal strings and rejects everything else', () => {
    for (const ok of ['0', '10', '10.5', '0.10', '999999999999.99']) expect(isMoneyAmount(ok)).toBe(true)
    for (const bad of ['', '-1', '1e3', '1.234', '1,000', ' 1', '.5', '1.', '9999999999999']) expect(isMoneyAmount(bad)).toBe(false)
  })
  it('checks quantity and percent shapes', () => {
    expect(isQuantity('2.50')).toBe(true)
    expect(isQuantity('12345')).toBe(false)
    expect(isPercent('12.5')).toBe(true)
    expect(isPercent('1000')).toBe(false)
  })
  it('knows a zero amount without converting it', () => {
    expect(isNonZero('0.00')).toBe(false)
    expect(isNonZero('0.01')).toBe(true)
  })
  it('makes distinct idempotency keys', () => {
    expect(newIdempotencyKey()).not.toBe(newIdempotencyKey())
    expect(newIdempotencyKey()).toMatch(/^[0-9a-f-]{32,36}$/)
  })
})
