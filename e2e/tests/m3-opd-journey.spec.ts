import { randomInt } from 'node:crypto'
import { expect, test } from '@playwright/test'
import { prepareClinic, realStackEnv, uniqueSuffix, type Clinic } from '../support/real-stack'

// [E7-M3] The whole OPD slice against the REAL Compose stack (project `real-stack`, see e2e/README.md): sign in, register a
// patient, book, check in, consult (vitals, diagnosis, prescription, finalize), invoice (draft, issue), pay in full and
// download the receipt PDF. No API mocking. Synthetic data only, unique per run (patient name and phone).

let clinic: Clinic

test.beforeAll(async () => {
  clinic = await prepareClinic(realStackEnv())
})

test('OPD journey: register, book, check in, consult, invoice, pay, receipt PDF', async ({ page }) => {
  const suffix = uniqueSuffix()
  const patientName = `E2eTest Patient${suffix}`
  const phone = `0000${String(randomInt(0, 1_000_000)).padStart(6, '0')}` // placeholder range, never a real mobile number
  // A random date of birth per run keeps the duplicate check (similar name + same DOB) from stopping reruns on one stack.
  const dob = `${randomInt(1950, 2005)}-${String(randomInt(1, 13)).padStart(2, '0')}-${String(randomInt(1, 29)).padStart(2, '0')}`
  const price = clinic.servicePrice

  // Login
  await page.goto('/')
  await expect(page).toHaveURL(/\/login$/)
  await page.getByLabel('Email').fill(clinic.email)
  await page.getByLabel('Password').fill(clinic.password)
  await page.getByRole('button', { name: 'Sign in' }).click()
  await expect(page.getByRole('heading', { name: /^Welcome/ })).toBeVisible()

  // Register the patient
  await page.goto('/patients/new')
  await page.getByLabel('Full name', { exact: true }).fill(patientName)
  await page.getByLabel('Sex', { exact: true }).selectOption('F')
  await page.getByLabel('Date of birth', { exact: true }).fill(dob)
  await page.getByLabel('Phone', { exact: true }).fill(phone)
  await page.getByRole('button', { name: 'Register' }).click()
  await expect(page.getByRole('region', { name: 'Patient banner' })).toContainText(patientName)

  // Book the first free slot of tomorrow with the doctor of this run
  await page.goto('/appointments')
  await page.getByLabel('Date', { exact: true }).fill(clinic.appointmentDate)
  await page.getByLabel('Doctor', { exact: true }).selectOption({ label: clinic.doctorName })
  await page.getByRole('button', { name: /^Book \d\d:\d\d$/ }).first().click()
  const dialog = page.getByRole('dialog')
  await dialog.getByLabel('Find patient').fill(patientName)
  await dialog.getByRole('button', { name: 'Search', exact: true }).click()
  await dialog.getByRole('button', { name: new RegExp(`^${patientName} \\(`) }).click()
  await dialog.getByLabel('Reason').fill('Fever')
  await dialog.getByRole('button', { name: 'Confirm booking' }).click()
  await expect(dialog).toBeHidden()

  // Check in: the patient gets a token
  const row = page.getByRole('listitem').filter({ hasText: patientName })
  await expect(row).toContainText('Scheduled')
  await row.getByRole('button', { name: 'Check in' }).click()
  await expect(page.getByRole('status', { name: 'Check-in result' })).toContainText('Token')

  // Queue: start the consultation
  await page.goto('/queue')
  await page.getByLabel('Doctor', { exact: true }).selectOption({ label: clinic.doctorName })
  const queued = page.getByRole('listitem').filter({ hasText: patientName })
  await queued.getByRole('button', { name: 'Start consult' }).click()
  await expect(page.getByRole('heading', { name: 'Consultation' })).toBeVisible()

  // Vitals
  await page.getByLabel('Pulse (bpm)').fill('78')
  await page.getByLabel('Temperature (°C)').fill('37.8')
  await page.getByRole('button', { name: 'Save vitals' }).click()
  await expect(page.getByLabel('Pulse (bpm)')).toHaveValue('78')

  // Diagnosis
  await page.getByRole('button', { name: 'Add diagnosis' }).click()
  await page.getByLabel('Diagnosis description 1').fill('Viral fever')
  await page.getByLabel('Primary diagnosis 1').check()
  await page.getByRole('button', { name: 'Save diagnoses' }).click()
  await expect(page.getByLabel('Diagnosis description 1')).toHaveValue('Viral fever')

  // Prescription: create, add a medicine, save, issue
  await page.getByRole('button', { name: 'Start prescription' }).click()
  await page.getByRole('button', { name: 'Add medicine' }).click()
  await page.getByLabel('Drug name 1').fill('Paracetamol')
  await page.getByLabel('Dose 1').fill('500 mg')
  await page.getByLabel('Frequency 1').fill('Twice daily')
  await page.getByLabel('Duration (days) 1').fill('3')
  await page.getByRole('button', { name: 'Save medicines' }).click()
  await page.getByRole('button', { name: 'Issue prescription' }).click()
  await expect(page.getByRole('link', { name: 'Open prescription PDF' })).toBeVisible()

  // Finalize
  await page.getByRole('button', { name: 'Finalize encounter' }).click()
  await page.getByRole('button', { name: 'Confirm finalize' }).click()
  await expect(page.getByRole('region', { name: 'Encounter summary' })).toContainText('Finalized')

  // Invoice: draft, add the catalogue service, save, issue
  await page.getByRole('button', { name: 'Create invoice' }).click()
  await expect(page.getByRole('heading', { name: 'Draft invoice' })).toBeVisible()
  await page.getByLabel('Service', { exact: true }).selectOption({ label: `${clinic.serviceName} (${price})` })
  await page.getByRole('button', { name: 'Add service' }).click()
  await page.getByRole('button', { name: 'Save items' }).click()
  await expect(page.getByTestId('total')).toHaveText(price)
  await page.getByRole('button', { name: 'Issue invoice' }).click()
  await expect(page.getByRole('heading', { name: /^Invoice \S+/ })).toBeVisible()
  await expect(page.getByRole('link', { name: 'Open invoice PDF' })).toBeVisible()

  // Pay in full
  await page.getByRole('button', { name: 'Take payment' }).click()
  const pay = page.getByRole('dialog', { name: 'Record payment' })
  await pay.getByLabel('Amount').fill(price)
  await pay.getByRole('button', { name: 'Record payment' }).click()
  await expect(pay.getByTestId('outstanding')).toHaveText('0.00')
  const receipt = pay.getByRole('link', { name: 'Open receipt PDF' })
  await expect(receipt).toBeVisible()

  // The receipt is a PDF (fetched with the browser session's cookies)
  const href = await receipt.getAttribute('href')
  expect(href).toMatch(/^\/api\/v1\/payments\/[^/]+\/receipt$/)
  const response = await page.request.get(href!)
  expect(response.status()).toBe(200)
  expect(response.headers()['content-type']).toContain('application/pdf')
  expect((await response.body()).subarray(0, 4).toString()).toBe('%PDF')
})
