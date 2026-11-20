/**
 * Money, quantities and rates are decimal strings from the keyboard to the server and back (ADR-0012). Nothing here
 * converts to a number: checks are by pattern only, and the server does every calculation and comparison.
 */

/** An amount: up to 12 integer digits and at most 2 decimals (NUMERIC(14,2)). */
export const isMoneyAmount = (v: string): boolean => /^\d{1,12}(\.\d{1,2})?$/.test(v)
/** A quantity as the server accepts it: up to 4 integer digits, at most 2 decimals. */
export const isQuantity = (v: string): boolean => /^\d{1,4}(\.\d{1,2})?$/.test(v)
/** A percentage or tax rate: up to 3 integer digits, at most 2 decimals. */
export const isPercent = (v: string): boolean => /^\d{1,3}(\.\d{1,2})?$/.test(v)
/** True when a decimal string has a digit other than zero ("0.00" is false). */
export const isNonZero = (v: string): boolean => /[1-9]/.test(v)

/** A fresh Idempotency-Key. crypto.randomUUID needs a secure context, so fall back to random bytes. */
export function newIdempotencyKey(): string {
  const c = globalThis.crypto
  if (typeof c?.randomUUID === 'function') return c.randomUUID()
  const bytes = new Uint8Array(16)
  c.getRandomValues(bytes)
  return Array.from(bytes, (b) => b.toString(16).padStart(2, '0')).join('')
}
