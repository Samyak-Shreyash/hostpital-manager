# HMS Design System — Master

Global rules for the HMS staff web app (React + Tailwind v4). Tokens live in `frontend/src/index.css` (`@theme`).
Page overrides, if ever needed, go in `design-system/hms/pages/<page>.md` and win over this file.

## Direction
Clinical, calm, dense-but-legible. The app is used by front desk, doctors, nurses and billing staff on desktop
monitors for long shifts. Data and forms come first and there is no decoration. Light theme only for Release 1
(dark mode deferred: add `dark` token overrides when requested).

## Colour (semantic tokens → Tailwind classes)
Direction "Indian market" (canvas https://claude.ai/artifact/YYoUA3Nfw28W751uUkj88J): deep green primary, warm grey canvas,
dark-green sidebar with a saffron brand mark.
| Token | Value | Use |
|---|---|---|
| `canvas` / `surface` / `muted` | #F4F5F1 / white / #ECEEEA | page / cards, inputs / neutral chips, hover |
| `border` / `border-strong` | #E1E5DF / #CBD2CC | dividers / input and button borders |
| `ink` / `ink-muted` | #14231E / #4F5E58 | body text / secondary text (both ≥ 4.5:1 on canvas) |
| `primary` / `primary-hover` / `primary-soft` | #0F6E5B / #0A4F41 / #E3F2EC | one primary action per screen, links, active nav |
| `nav`, `nav-hover`, `nav-ink`, `nav-ink-muted`, `accent` | #12302A, #1C3A31, #DCE9E4, #A9C2B9, #F2A541 | sidebar only; `accent` = brand mark |
| `success`, `warning`, `danger` (+ `-soft`) | green-800, #9A3412, #A61B1B (+ tints) | status text on soft backgrounds |

Fonts: `font-sans` Noto Sans → Nirmala UI/Segoe UI (Devanagari-capable), headings `font-display` (Mukta if installed).
No web-font download: clinics may run on-prem or offline. Controls are at least 44px tall (`min-h-11`).

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
