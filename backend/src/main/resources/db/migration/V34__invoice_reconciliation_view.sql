-- [E7-7] Nightly reconciliation read model. Design: database-design.md section 20o.
-- Runs as hms_owner (ADR-0015). Billing must not read payment tables through code (CLAUDE.md section 4), so the
-- cross-module read is a SQL view, the documented "reporting reads views" exception. One row per invoice with the
-- derived money figures: credit notes (billing), payments and effective refunds (payment). "Effective" = RECORDED
-- initially or APPROVED by a decision; pending and rejected refunds do not count (same rule as the refund cap).
--
-- security_invoker = true: the view runs with the caller's rights, so the row-level security policies of invoice,
-- payment, refund, refund_decision and credit_note apply to hms_app exactly as for direct queries (a session
-- without an organization sees no rows, another organization's rows are invisible). Without it the view would
-- run as hms_owner and RLS would be evaluated for the owner. hms_app gets SELECT on the view only.

CREATE VIEW hms.invoice_reconciliation_v WITH (security_invoker = true) AS
SELECT i.organization_id,
       i.id,
       i.status,
       i.total,
       coalesce((SELECT sum(c.amount) FROM hms.credit_note c
                 WHERE c.organization_id = i.organization_id AND c.invoice_id = i.id), 0)::numeric(14,2) AS credited,
       coalesce((SELECT sum(p.amount) FROM hms.payment p
                 WHERE p.organization_id = i.organization_id AND p.invoice_id = i.id), 0)::numeric(14,2) AS paid,
       coalesce((SELECT sum(r.amount)
                 FROM hms.refund r
                 JOIN hms.payment p ON p.organization_id = r.organization_id AND p.id = r.payment_id
                 LEFT JOIN hms.refund_decision d ON d.organization_id = r.organization_id AND d.refund_id = r.id
                 WHERE p.organization_id = i.organization_id AND p.invoice_id = i.id
                   AND (r.initial_status = 'RECORDED' OR d.decision = 'APPROVED')), 0)::numeric(14,2) AS refunded
FROM hms.invoice i;

GRANT SELECT ON hms.invoice_reconciliation_v TO hms_app;
