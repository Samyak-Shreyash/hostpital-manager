import { randomInt } from 'node:crypto'
import { expect, test } from '@playwright/test'
import { backdatedService, prepareClinic, realStackEnv, uniqueSuffix, type Clinic } from '../support/real-stack'

// [E8-9] IPD admission to discharge against the REAL Compose stack (project `real-stack`, see e2e/README.md): ward and two
// beds, register, admit to bed 1, transfer to bed 2, discharge summary, FINAL invoice (total = preview), issue, pay,
// discharge, both beds free again. No API mocking. Synthetic data only, unique per run.

let clinic: Clinic
const rateName = `E2E Room ${uniqueSuffix()}`
// 300.00 in a GENERAL ward is GST-exempt (<= 5,000/day).
const rate = '300.00'

test.beforeAll(async () => {
  const env = realStackEnv()
  clinic = await prepareClinic(env)
  await backdatedService(clinic, env, rateName, rate)
})

/** `yyyy-MM-ddTHH:mm` in Asia/Kolkata (the facility time zone) for a datetime-local input. */
const localIst = (at: Date): string =>
  new Intl.DateTimeFormat('sv-SE', { timeZone: 'Asia/Kolkata', year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' })
    .format(at).replace(' ', 'T')

test('IPD journey: ward and beds, admit, transfer, summary, final invoice, pay, discharge', async ({ page }) => {
  const suffix = uniqueSuffix()
  const wardCode = `E2E${suffix.toUpperCase()}`
  const wardName = `E2E Ward ${suffix}`
  const patientName = `E2eTest Inpatient${suffix}`
  const phone = `0000${String(randomInt(0, 1_000_000)).padStart(6, '0')}` // placeholder range, never a real mobile number
  const dob = `${randomInt(1950, 2005)}-${String(randomInt(1, 13)).padStart(2, '0')}-${String(randomInt(1, 29)).padStart(2, '0')}`

  // Login
  await page.goto('/')
  await page.getByLabel('Email').fill(clinic.email)
  await page.getByLabel('Password').fill(clinic.password)
  await page.getByRole('button', { name: 'Sign in' }).click()
  await expect(page.getByRole('heading', { name: /^Welcome/ })).toBeVisible()

  // Ward with two beds
  await page.goto('/wards')
  const newWard = page.getByRole('form', { name: 'New ward' })
  await newWard.getByLabel('Code').fill(wardCode)
  await newWard.getByLabel('Name').fill(wardName)
  await newWard.getByRole('button', { name: 'Create ward' }).click()
  await page.getByRole('button', { name: `Manage ${wardName}` }).click()
  const newBed = page.getByRole('form', { name: 'New bed' })
  for (const bedNo of ['B1', 'B2']) {
    await newBed.getByLabel('Bed number').fill(bedNo)
    await newBed.getByLabel('Daily rate service').selectOption({ label: `${rateName} (${rate})` })
    await newBed.getByRole('button', { name: 'Add bed' }).click()
    await expect(page.getByRole('button', { name: `Take bed ${bedNo} out of service` })).toBeVisible()
  }

  // Register the patient
  await page.goto('/patients/new')
  await page.getByLabel('Full name', { exact: true }).fill(patientName)
  await page.getByLabel('Sex', { exact: true }).selectOption('M')
  await page.getByLabel('Date of birth', { exact: true }).fill(dob)
  await page.getByLabel('Phone', { exact: true }).fill(phone)
  await page.getByRole('button', { name: 'Register' }).click()
  await expect(page.getByRole('region', { name: 'Patient banner' })).toContainText(patientName)

  // Admit to bed 1
  await page.goto('/admissions/new')
  await page.getByLabel('Find patient').fill(patientName)
  await page.getByRole('button', { name: 'Search', exact: true }).click()
  await page.getByRole('button', { name: new RegExp(`^${patientName} \\(`) }).click()
  await page.getByLabel('Doctor', { exact: true }).selectOption({ label: clinic.doctorName })
  await page.getByLabel('Bed', { exact: true }).selectOption({ label: `${wardName} — B1` })
  await page.getByLabel('Admission diagnosis').fill('Acute gastroenteritis')
  await page.getByRole('button', { name: 'Admit', exact: true }).click()
  await expect(page.getByRole('heading', { name: /^Admission ADM-\d{4}-\d{4,}$/ })).toBeVisible()
  await expect(page.getByTestId('current-bed')).toHaveText(`${wardName} — B1`)

  // Transfer to bed 2
  await page.getByRole('button', { name: 'Transfer bed' }).click()
  const transfer = page.getByRole('dialog', { name: 'Transfer to another bed' })
  await transfer.getByLabel('Bed', { exact: true }).selectOption({ label: `${wardName} — B2` })
  await transfer.getByLabel('Reason').fill('Closer to nursing station')
  await transfer.getByRole('button', { name: 'Confirm transfer' }).click()
  await expect(page.getByTestId('current-bed')).toHaveText(`${wardName} — B2`)

  // Discharge summary. The planned time must not precede the transfer (minute precision, so the next minute) and the
  // discharge must not be more than 5 minutes ahead of the server clock, so the next minute fits both.
  await page.getByLabel('Final diagnosis').fill('Acute gastroenteritis, resolved')
  await page.getByLabel('Condition at discharge').fill('Stable')
  await page.getByLabel('Planned discharge').fill(localIst(new Date(Date.now() + 60_000)))
  await page.getByRole('button', { name: 'Save summary' }).click()
  await expect(page.getByText('Summary saved.')).toBeVisible()
  // Same-day stay: one day at the rate of the bed held at discharge.
  await expect(page.getByTestId('room-total')).toHaveText(rate)
  const preview = await page.getByTestId('room-total').innerText()
  const admissionUrl = page.url()

  // FINAL invoice: total equals the preview, issue, pay in full
  await page.getByRole('button', { name: 'Create final invoice' }).click()
  await expect(page.getByRole('heading', { name: 'Draft invoice' })).toBeVisible()
  await expect(page.getByTestId('total')).toHaveText(preview)
  await page.getByRole('button', { name: 'Issue invoice' }).click()
  await expect(page.getByRole('heading', { name: /^Invoice \S+/ })).toBeVisible()
  await page.getByRole('button', { name: 'Take payment' }).click()
  const pay = page.getByRole('dialog', { name: 'Record payment' })
  await pay.getByLabel('Amount').fill(preview)
  await pay.getByRole('button', { name: 'Record payment' }).click()
  await expect(pay.getByTestId('outstanding')).toHaveText('0.00')

  // Discharge at the planned time
  await page.goto(admissionUrl)
  await expect(page.getByRole('link', { name: /^Final invoice \S+/ })).toBeVisible()
  await page.getByRole('button', { name: 'Discharge patient' }).click()
  const discharge = page.getByRole('dialog', { name: 'Discharge patient' })
  await discharge.getByRole('button', { name: 'Confirm discharge' }).click()
  await expect(page.getByText('Discharged', { exact: true }).first()).toBeVisible()

  // Both beds are free again
  await page.goto('/beds')
  await expect(page.getByTestId(`available-${wardCode}`)).toHaveText('Available: 2')
  await expect(page.getByTestId(`occupied-${wardCode}`)).toHaveText('Occupied: 0')
})
