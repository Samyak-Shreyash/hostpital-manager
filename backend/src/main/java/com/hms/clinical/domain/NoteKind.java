package com.hms.clinical.domain;

/** Clinical note kinds. ADDENDUM is written after finalize only ([E6-2]), never through the note endpoint. */
public enum NoteKind {
    SYMPTOMS, EXAMINATION, ASSESSMENT, ADDENDUM
}
