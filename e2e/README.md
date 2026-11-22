# e2e

Playwright browser tests, in two projects.

## Mocked specs (default, `npm run test:e2e`)

Run with `npm ci && npx playwright install chromium && npm run test:e2e`; `playwright.config.ts` starts the Vite dev
server of `../frontend` itself. **Not full-stack:** the backend is not started; each spec mocks `/api/v1/**` with
`page.route`, so they check the SPA's routing, forms, CSRF header and flows, not the real API. Test data is synthetic.
`tests/m3-opd-journey.spec.ts` and `tests/m4-ipd-journey.spec.ts` are excluded here.

## Real-stack journey (`real-stack` project, `npm run test:e2e:real-stack`) [E7-M3]

`tests/m3-opd-journey.spec.ts` drives the whole OPD slice through the browser against the **real Compose stack** (no
mocks): login, register patient, book, check in, queue, consult (vitals, diagnosis, prescription issue, finalize),
invoice (draft, add service, issue), pay in full, then fetches the receipt and asserts `content-type: application/pdf`.
Config: `playwright.real-stack.config.ts` (no dev server; targets `E2E_BASE_URL`).

Prerequisites:

1. A **fresh** stack with the first-run bootstrap (README "First organization"): in `.env` set `HMS_BOOTSTRAP_ORG_NAME`,
   `HMS_BOOTSTRAP_ADMIN_EMAIL` and `HMS_BOOTSTRAP_ADMIN_PASSWORD_FILE=/var/lib/hms/bootstrap/admin-password.txt`, then
   `docker compose -f infra/compose/compose.yml --env-file .env up -d --build --wait`. The stack must run the `dev` profile
   (the Compose default).
2. Read the temporary admin password (do not commit or log it):
   `docker compose -f infra/compose/compose.yml --env-file .env exec -T api cat /var/lib/hms/bootstrap/admin-password.txt`.
3. `npm ci && npx playwright install chromium`.

Environment variables (never commit real values):

| Variable | Meaning |
|---|---|
| `E2E_BASE_URL` | Stack URL (default `http://localhost:8080`; use `HMS_PROXY_PORT` from `.env`). |
| `E2E_ADMIN_EMAIL` | The bootstrap administrator (`HMS_BOOTSTRAP_ADMIN_EMAIL`). |
| `E2E_ADMIN_PASSWORD` | Its current password: the temporary one on the first run, afterwards the new one. |
| `E2E_ADMIN_NEW_PASSWORD` | Needed only while the admin still has the temporary password: the first run changes it to this value, so use it as `E2E_ADMIN_PASSWORD` on later runs. |

The seed task (`./gradlew seedData`) is not needed: its users have unknown passwords. The spec prepares its own data
through the API, unique per run, so it can be rerun on the same stack: as the administrator it creates one clinician user
(roles Doctor, Receptionist, Accountant, Facility Administrator, random password), then as that user a specialty, a
priced catalogue service (300.00), the clinician's own practitioner profile and 09:00-17:00 availability on every weekday.
The appointment is booked for **tomorrow** (facility time zone Asia/Kolkata) so a free slot always exists; check-in and
the queue are today. The patient name is `E2eTest Patient<random>`, the phone `0000xxxxxx` and the date of birth random
(so the duplicate check does not stop reruns). Rows stay in that database: use a throwaway stack, not real data.

```bash
E2E_BASE_URL=http://localhost:8080 E2E_ADMIN_EMAIL=... E2E_ADMIN_PASSWORD=... E2E_ADMIN_NEW_PASSWORD=... \
  npm run test:e2e:real-stack
```

`tests/m4-ipd-journey.spec.ts` [E8-9] reuses the same setup and drives IPD: creates a ward with two beds (daily rate
300.00, GST-exempt), registers a patient, admits to bed 1, transfers to bed 2, saves the
discharge summary (planned discharge = the next minute), creates the FINAL invoice and checks its total equals the room
charges preview, issues and pays it, discharges, and checks the bed board shows both beds available.

Room rates are priced at 00:00 of each charged day and the API refuses past prices, so the spec inserts one
backdated `service_price` row through `psql` in the Compose `db` container (`backdatedService` in
`support/real-stack.ts`; command overridable with `E2E_COMPOSE`, default run from `e2e/`). Throwaway stacks only.

CI runs both in the `e2e-real-stack` job of `.github/workflows/ci.yml`.
