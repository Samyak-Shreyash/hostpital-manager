package com.hms.organization.application;

/** The two org-owned reference lists that share one shape (department, specialty). Table names are fixed here. */
public enum CatalogKind {
    DEPARTMENT("department", "DEPARTMENT", "DUPLICATE_DEPARTMENT_CODE", "Department"),
    SPECIALTY("specialty", "SPECIALTY", "DUPLICATE_SPECIALTY_CODE", "Specialty");

    private final String table;
    private final String entity;
    private final String duplicateCode;
    private final String label;

    CatalogKind(String table, String entity, String duplicateCode, String label) {
        this.table = table;
        this.entity = entity;
        this.duplicateCode = duplicateCode;
        this.label = label;
    }

    public String table() {
        return table;
    }

    public String entity() {
        return entity;
    }

    public String duplicateCode() {
        return duplicateCode;
    }

    public String label() {
        return label;
    }
}
