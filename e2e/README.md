# e2e

Playwright browser tests. Run with `npm ci && npx playwright install chromium && npm run test:e2e`; the config starts the
Vite dev server of `../frontend` itself.

**Not a full-stack test.** The backend is not started: `tests/login-logout.spec.ts` mocks `/api/v1/**` with
`page.route`, so it checks the SPA's routing, login form, CSRF header and logout only, not the real API. A full-stack
journey suite needs the Compose stack and comes with its own issue. Test data is synthetic (`admin@example.test`).
