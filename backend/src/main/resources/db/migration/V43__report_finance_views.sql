-- [E9-3] Finance report read model. Design: database-design.md section 20w.
-- Runs as hms_owner (ADR-0015). security_invoker = true: RLS of payment, refund, refund_decision applies to hms_app.
--
-- Cash movement per organization-local day and mode: payments count on received_at; refunds count only when
-- effective, on the date they became effective (RECORDED: requested_at; otherwise the APPROVED decision's
-- decided_at). Pending and rejected refunds never appear. Same effectiveness rule as invoice_reconciliation_v (V34).
CREATE VIEW hms.report_cash_movement_v WITH (security_invoker = true) AS
SELECT p.organization_id,
       (p.received_at AT TIME ZONE o.timezone)::date AS day,
       p.mode,
       p.amount AS collected,
       0::numeric(14,2) AS refunded
FROM hms.payment p
JOIN hms.organization o ON o.id = p.organization_id
UNION ALL
SELECT r.organization_id,
       ((CASE WHEN r.initial_status = 'RECORDED' THEN r.requested_at ELSE d.decided_at END)
            AT TIME ZONE o.timezone)::date,
       r.mode,
       0::numeric(14,2),
       r.amount
FROM hms.refund r
JOIN hms.organization o ON o.id = r.organization_id
LEFT JOIN hms.refund_decision d ON d.organization_id = r.organization_id AND d.refund_id = r.id
WHERE r.initial_status = 'RECORDED' OR d.decision = 'APPROVED';

GRANT SELECT ON hms.report_cash_movement_v TO hms_app;
