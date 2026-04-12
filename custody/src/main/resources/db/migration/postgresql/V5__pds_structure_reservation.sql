-- 16-1-1: Phase 4+ PDS integration — structure reservation only.
-- Tables are created now but columns are nullable and unused until Phase 2.
-- Activation: set pds.features.signer-key-pds=true (requires pds-core service).

-- tenant_pds_records: holds PDS metadata per tenant (signer key, emergency access, operator credentials)
CREATE TABLE IF NOT EXISTS tenant_pds_records (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id  UUID NOT NULL,
    -- SIGNER_KEY | EMERGENCY_ACCESS | OPERATOR_CREDENTIAL
    pds_type   TEXT NOT NULL,
    pds_data   JSONB NOT NULL DEFAULT '{}',
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_tenant_pds_tenant_id ON tenant_pds_records(tenant_id);
CREATE INDEX IF NOT EXISTS idx_tenant_pds_type ON tenant_pds_records(pds_type);

-- 16-1-2: policy_audit_logs hash columns — reserved for Phase 3 audit chain (B-1 patent)
-- NULL = not yet hashed. Populated by PdsAuditChain service when pds.features.policy-audit-chain=true
ALTER TABLE policy_audit_logs
    ADD COLUMN IF NOT EXISTS previous_hash TEXT,
    ADD COLUMN IF NOT EXISTS current_hash  TEXT;
