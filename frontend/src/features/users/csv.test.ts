import { parseCsv, parseUserImport, toCsv } from './csv'

describe('user import CSV', () => {
  it('splits quoted fields, escaped quotes and CRLF lines, skipping blank lines', () => {
    expect(parseCsv('a,"b, c","d ""e"""\r\n\r\nx,y,z\n')).toEqual([['a', 'b, c', 'd "e"'], ['x', 'y', 'z']])
  })

  it('reads rows by header name, upper-cases role codes and splits them on ;', () => {
    const result = parseUserImport('﻿Roles,Email,FullName\nnurse; receptionist ,test.nina@demo.example,Test Nina\n')
    expect(result.errors).toEqual([])
    expect(result.rows).toEqual([{ email: 'test.nina@demo.example', fullName: 'Test Nina', roleCodes: ['NURSE', 'RECEPTIONIST'] }])
  })

  it('reports a missing header column and bad rows by row number', () => {
    expect(parseUserImport('email,name\nx@y.z,X').errors[0]).toMatch(/email, fullName and roles/)
    expect(parseUserImport('email,fullName,roles\nnot-an-email,,').errors).toEqual([
      'Row 1: enter a valid email.', 'Row 1: full name is required.', 'Row 1: give at least one role code.',
    ])
  })

  it('rejects more than 200 rows', () => {
    const body = Array.from({ length: 201 }, (_, i) => `u${i}@demo.example,Test User,NURSE`).join('\n')
    expect(parseUserImport(`email,fullName,roles\n${body}`).errors[0]).toMatch(/At most 200/)
  })

  it('writes quoted CSV', () => {
    expect(toCsv([['a', 'b"c']])).toBe('"a","b""c"\r\n')
  })
})
