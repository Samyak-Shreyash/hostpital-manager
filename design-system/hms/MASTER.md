# HMS Design System — Master

Global rules for the HMS staff web app (React + Tailwind v4). Tokens live in `frontend/src/index.css` (`@theme`).
Page overrides, if ever needed, go in `design-system/hms/pages/<page>.md` and win over this file.

## Direction
Clinical, calm, dense-but-legible. The app is used by front desk, doctors, nurses and billing staff on desktop
monitors for long shifts. Data and forms come first and there is no decoration. Light theme only for Release 1
(dark mode deferred: add `dark` token overrides when requested).

## Colour (semantic tokens → Tailwind classes)
| Token | Value | Use |
|---|---|---|
| `canvas` | slate-50 | page background (`bg-canvas`) |
| `surface` | white | cards, header, tables, inputs |
| `border` | slate-200 | dividers, input borders |
| `ink` / `ink-muted` | slate-900 / slate-600 | body text / secondary text (both ≥ 4.5:1 on canvas) |
| `primary` / `primary-hover` / `primary-soft` | blue-700 / blue-800 / blue-50 | one primary action per screen, links, active nav |
| `focus` | blue-600 | focus ring (global `:focus-visible`) |
| `success`, `warning`, `danger` (+ `-soft`) | green-700, amber-800, red-700 (+ 50 tints) | status text on soft backgrounds |

Rules: use tokens (`text-ink-muted`, `bg-danger-soft`), not raw `slate-*`/`red-*`. Status is never shown by colour alone:
pair it with a text label or icon. Clinical alerts (allergies) use `danger`.

## Typography
- Font: system UI stack (no web font, so it works offline on clinic LANs and adds no extra network requests). Mono: `font-mono` for codes and temporary passwords.
- Scale: `text-xs` 12 (meta) · `text-sm` 14 (labels, table cells, helper/error) · `text-base` 16 (body, inputs) ·
  `text-lg` 18 (section) · `text-xl` 20 (page title on sub-pages) · `text-2xl` 24 (page title).
- Weights: 400 body, 500 labels, 600 headings. Line height: Tailwind defaults (≈1.5 body).
- Numbers: `tabular-nums` on money, quantities, times, and table numeric columns, which are right-aligned. Money is formatted `₹1,23,456.00` (en-IN).
- Business keys (UHID, invoice no.) are shown verbatim in `font-mono`. Never display internal UUIDs.

## Spacing & layout
- 4px base; use Tailwind steps 1,2,3,4,6,8,12 only (4–48px).
- Component internals 8–16px (`p-2`–`p-4`, `gap-2`–`gap-4`); between sections 24–32px (`mt-6`/`mt-8`); page padding `p-6`.
- Content max width `max-w-5xl` for forms/detail pages, `max-w-7xl` for list/table pages.
- Radius: `rounded` (4px) for inputs/buttons/badges, `rounded-lg` for cards/dialogs. Shadow: none or `shadow-sm` only.
- Breakpoints: Tailwind defaults; designed desktop-first for ≥1024px but must not scroll horizontally at 375px
  (tables scroll inside their own `overflow-x-auto` wrapper).

## Components (baseline)
- Button primary: `rounded bg-primary px-4 py-2 text-sm font-medium text-white hover:bg-primary-hover disabled:opacity-50`.
  Secondary: `border border-border bg-surface text-ink`. Destructive: `bg-danger` + confirmation dialog.
- Input: `rounded border border-border bg-surface px-3 py-2 text-base`; visible `<label>` always; error under the field
  (`text-sm text-danger`, `role="alert"`); keep entered values after a failed submit; focus the first invalid field.
- Hit targets ≥ 40px tall for buttons/inputs; ≥ 8px between adjacent controls.
- Tables: `text-sm`, header `font-medium text-ink-muted`, row dividers `border-border`, empty state with next action.
- Loading: disable the submit button and show its pending label; skeletons for loads > 1s. Errors: `ErrorBanner` with a recovery action.
- Icons (when added): one outline family, consistent stroke; icon-only buttons need `aria-label`.

## Accessibility (non-negotiable)
WCAG 2.2 AA contrast; full keyboard use; visible focus (global ring, never `outline-none` without replacement);
semantic landmarks (`header`, `nav[aria-label]`, `main`); sequential headings (one `h1` per page);
password fields allow paste/autofill; respect reduced motion (global rule).

## Anti-patterns
Emojis as icons · colour-only status · placeholder-as-label · gradients/glassmorphism · more than one primary button
per view · truncating patient names · real patient data in screenshots or mocks (synthetic only).
