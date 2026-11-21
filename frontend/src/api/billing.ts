import { api } from './client'
import type { Page } from './types'

/** api-design.md section 12. Every amount, quantity and rate is a decimal string; ids are opaque strings. */
export type InvoiceStatus = 'DRAFT' | 'ISSUED' | 'PARTIALLY_PAID' | 'PAID' | 'CANCELLED' | 'DISCARDED'

export interface InvoiceItem {
  id: string
  serviceId: string | null
  description: string
  quantity: string
  unitPrice: string
  discount: string
  discountReason: string | null
  taxRate: string
  gross: string
  taxable: string
  tax: string
  total: string
}

export interface Invoice {
  id: string
  type: string
  patientId: string
  encounterId: string | null
  admissionId: string | null
  /** IPD invoices only. */
  kind?: 'INTERIM' | 'FINAL' | null
  status: InvoiceStatus
  invoiceNo: string | null
  issuedAt: string | null
  subtotal: string
  discountTotal: string
  taxTotal: string
  total: string
  version: number
  /** Empty in list results. */
  items: InvoiceItem[]
  createdAt: string
  updatedAt: string
}

/** One line sent to PUT items; unitPrice omitted means the catalog price. */
export interface ItemInput {
  serviceId?: string
  description?: string
  quantity: string
  unitPrice?: string
  discount?: string
  discountReason?: string
  taxRate?: string
}

export interface CatalogService {
  id: string
  code: string
  name: string
  active: boolean
  currentPrice: string | null
  version: number
}

export const PAYMENT_MODES = ['CASH', 'CARD', 'UPI', 'BANK_TRANSFER', 'CHEQUE'] as const
export type PaymentMode = (typeof PAYMENT_MODES)[number]

export interface RecordedPayment {
  id: string
  invoiceId: string
  amount: string
  mode: PaymentMode
  referenceNo: string | null
  receiptNo: string
  receivedAt: string
  receivedBy: string
  outstanding: string
  invoiceStatus: InvoiceStatus
}

const enc = encodeURIComponent
const IDEMPOTENCY = 'Idempotency-Key'

function query(filters: object): string {
  const params = new URLSearchParams()
  for (const [key, value] of Object.entries(filters)) {
    if (value !== undefined && value !== '') params.set(key, String(value))
  }
  return params.toString()
}

/** `status` may be several values separated by commas (ISSUED,PARTIALLY_PAID). */
export const listInvoices = (f: { patientId?: string; status?: string; page?: number; size?: number }) =>
  api.get<Page<Invoice>>(`/invoices?${query(f)}`)
export const getInvoice = (id: string) => api.get<Invoice>(`/invoices/${enc(id)}`)
export const createInvoice = (body: { patientId: string; encounterId: string }) => api.post<Invoice>('/invoices', { type: 'OPD', ...body })
export const putInvoiceItems = (id: string, body: { version: number; items: ItemInput[] }) => api.put<Invoice>(`/invoices/${enc(id)}/items`, body)
export const applyPercentDiscount = (id: string, body: { version: number; percent: string; reason: string }) =>
  api.put<Invoice>(`/invoices/${enc(id)}/discount`, body)
export const discardInvoice = (id: string, version: number) => api.post<Invoice>(`/invoices/${enc(id)}/discard`, { version })
export const issueInvoice = (id: string, idempotencyKey: string) => api.post<Invoice>(`/invoices/${enc(id)}/issue`, undefined, { [IDEMPOTENCY]: idempotencyKey })
export const recordPayment = (id: string, body: { amount: string; mode: PaymentMode; referenceNo?: string }, idempotencyKey: string) =>
  api.post<RecordedPayment>(`/invoices/${enc(id)}/payments`, body, { [IDEMPOTENCY]: idempotencyKey })
export const listServices = (f: { active?: boolean; size?: number }) => api.get<Page<CatalogService>>(`/services?${query(f)}`)

/** Same-origin PDF links (the session cookie authorizes them). */
export const invoicePdfUrl = (id: string) => `/api/v1/invoices/${enc(id)}/pdf`
export const receiptPdfUrl = (paymentId: string) => `/api/v1/payments/${enc(paymentId)}/receipt`

export const billingKeys = {
  all: ['invoices'] as const,
  detail: (id: string) => ['invoices', 'detail', id] as const,
  outstanding: ['invoices', 'outstanding'] as const,
  forPatient: (patientId: string) => ['invoices', 'patient', patientId] as const,
  services: ['services', 'active'] as const,
}
