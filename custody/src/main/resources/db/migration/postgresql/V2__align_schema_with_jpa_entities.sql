ALTER TABLE withdrawals
    ALTER COLUMN amount TYPE bigint USING amount::bigint;

ALTER TABLE tx_attempts
    ALTER COLUMN max_priority_fee_per_gas TYPE bigint USING max_priority_fee_per_gas::bigint,
    ALTER COLUMN max_fee_per_gas TYPE bigint USING max_fee_per_gas::bigint;

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_schema = 'public'
          AND table_name = 'ledger_entries'
          AND column_name = 'entry_type'
    ) THEN
        EXECUTE 'ALTER TABLE ledger_entries RENAME COLUMN entry_type TO type';
    END IF;
END $$;

ALTER TABLE ledger_entries
    ALTER COLUMN type TYPE varchar(16),
    ALTER COLUMN amount TYPE bigint USING amount::bigint;

DROP INDEX IF EXISTS idx_ledger_entries_entry_type_created_at;

CREATE INDEX IF NOT EXISTS idx_ledger_entries_type_created_at
    ON ledger_entries (type, created_at);

CREATE TABLE IF NOT EXISTS policy_audit_logs (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    withdrawal_id uuid NOT NULL,
    allowed boolean NOT NULL,
    reason varchar(255) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT fk_policy_audit_logs_withdrawal
        FOREIGN KEY (withdrawal_id) REFERENCES withdrawals (id)
);

CREATE INDEX IF NOT EXISTS idx_policy_audit_logs_withdrawal
    ON policy_audit_logs (withdrawal_id);

ALTER TABLE whitelist_addresses
    ADD COLUMN IF NOT EXISTS hold_duration_hours bigint NOT NULL DEFAULT 48,
    ADD COLUMN IF NOT EXISTS approved_at timestamptz;
