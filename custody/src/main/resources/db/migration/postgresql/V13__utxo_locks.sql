CREATE TABLE utxo_locks (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    txid            VARCHAR(64)  NOT NULL,
    vout            INTEGER      NOT NULL,
    address         VARCHAR(100) NOT NULL,
    amount_sat      BIGINT       NOT NULL,
    withdrawal_id   UUID,
    status          VARCHAR(20)  NOT NULL DEFAULT 'LOCKED',
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    expires_at      TIMESTAMPTZ  NOT NULL,
    CONSTRAINT utxo_locks_txid_vout_unique UNIQUE (txid, vout)
);

CREATE INDEX utxo_locks_status_idx ON utxo_locks(status);
CREATE INDEX utxo_locks_expires_at_idx ON utxo_locks(expires_at);
