/** One user row from an import file. */
export interface ImportRow {
  email: string
  fullName: string
  roleCodes: string[]
}

export type ParseResult = { rows: ImportRow[]; errors: string[] }

export const MAX_IMPORT_ROWS = 200

/** RFC 4180-style split: commas, double quotes around fields, "" for a literal quote, CRLF or LF line ends. */
export function parseCsv(text: string): string[][] {
  const records: string[][] = []
  let record: string[] = []
  let field = ''
  let quoted = false
  for (let i = 0; i < text.length; i++) {
    const c = text[i]
    if (quoted) {
      if (c === '"' && text[i + 1] === '"') { field += '"'; i++ }
      else if (c === '"') quoted = false
      else field += c
    } else if (c === '"') quoted = true
    else if (c === ',') { record.push(field); field = '' }
    else if (c === '\n' || c === '\r') {
      if (c === '\r' && text[i + 1] === '\n') i++
      record.push(field); records.push(record); record = []; field = ''
    } else field += c
  }
  if (field !== '' || record.length > 0) { record.push(field); records.push(record) }
  return records.filter((r) => r.some((f) => f.trim() !== ''))
}

/**
 * Header row `email,fullName,roles` (any order, case-insensitive); roles are role codes separated by `;`.
 * Checks only the shape here; the server checks emails and roles again and is the authority.
 */
export function parseUserImport(text: string): ParseResult {
  const [header, ...records] = parseCsv(text.replace(/^\uFEFF/, ''))
  if (!header) return { rows: [], errors: ['The file is empty.'] }
  const columns = header.map((h) => h.trim().toLowerCase())
  const at = { email: columns.indexOf('email'), fullName: columns.indexOf('fullname'), roles: columns.indexOf('roles') }
  if (at.email < 0 || at.fullName < 0 || at.roles < 0) {
    return { rows: [], errors: ['The first line must name the columns email, fullName and roles.'] }
  }
  if (records.length === 0) return { rows: [], errors: ['The file has no user rows.'] }
  if (records.length > MAX_IMPORT_ROWS) {
    return { rows: [], errors: [`At most ${MAX_IMPORT_ROWS} users per file; this one has ${records.length}.`] }
  }
  const errors: string[] = []
  const rows = records.map((r, i) => {
    const row: ImportRow = {
      email: (r[at.email] ?? '').trim(),
      fullName: (r[at.fullName] ?? '').trim(),
      roleCodes: (r[at.roles] ?? '').split(';').map((c) => c.trim().toUpperCase()).filter(Boolean),
    }
    const line = i + 1
    if (!/^[^\s@]+@[^\s@]+$/.test(row.email)) errors.push(`Row ${line}: enter a valid email.`)
    if (!row.fullName) errors.push(`Row ${line}: full name is required.`)
    if (row.roleCodes.length === 0) errors.push(`Row ${line}: give at least one role code.`)
    return row
  })
  return { rows, errors }
}

/** Results file for the administrator; quotes every field. */
export function toCsv(rows: string[][]): string {
  return rows.map((r) => r.map((f) => `"${f.replaceAll('"', '""')}"`).join(',')).join('\r\n') + '\r\n'
}
