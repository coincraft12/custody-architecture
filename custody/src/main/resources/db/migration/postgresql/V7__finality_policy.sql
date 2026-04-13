-- 17-9: FinalityPolicy table — per-chain, per-tier finalization rules.

CREATE TABLE finality_policies (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    chain_type VARCHAR(32) NOT NULL,
    tier VARCHAR(16) NOT NULL,      -- LOW, MEDIUM, HIGH
    min_confirmations INT NOT NULL DEFAULT 0,
    require_safe_head BOOLEAN NOT NULL DEFAULT FALSE,
    require_finalized_head BOOLEAN NOT NULL DEFAULT FALSE,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_finality_policies_chain_tier UNIQUE (chain_type, tier)
);

-- EVM seed
INSERT INTO finality_policies (chain_type, tier, min_confirmations, require_safe_head, require_finalized_head) VALUES
    ('EVM', 'LOW',    1, false, false),
    ('EVM', 'MEDIUM', 0, true,  false),
    ('EVM', 'HIGH',   0, false, true);

-- Bitcoin seed
INSERT INTO finality_policies (chain_type, tier, min_confirmations, require_safe_head, require_finalized_head) VALUES
    ('BITCOIN', 'LOW',    1, false, false),
    ('BITCOIN', 'MEDIUM', 3, false, false),
    ('BITCOIN', 'HIGH',   6, false, false);

-- TRON seed
INSERT INTO finality_policies (chain_type, tier, min_confirmations, require_safe_head, require_finalized_head) VALUES
    ('TRON', 'LOW',    1,  false, false),
    ('TRON', 'MEDIUM', 19, false, false),
    ('TRON', 'HIGH',   19, false, false);

-- Solana seed
INSERT INTO finality_policies (chain_type, tier, min_confirmations, require_safe_head, require_finalized_head) VALUES
    ('SOLANA', 'LOW',  32, false, false),
    ('SOLANA', 'HIGH', 32, false, true);
