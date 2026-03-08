CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE TABLE withdrawals (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    idempotency_key varchar(128) NOT NULL,
    chain_type varchar(32) NOT NULL,
    from_address varchar(128) NOT NULL,
    to_address varchar(128) NOT NULL,
    asset varchar(32) NOT NULL,
    amount numeric(38,0) NOT NULL,
    status varchar(32) NOT NULL,
    policy_decision_id uuid,
    approval_bundle_id uuid,
    correlation_id varchar(128),
    requested_by varchar(128),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_withdrawals_idempotency_key UNIQUE (idempotency_key)
);

CREATE INDEX idx_withdrawals_status_created_at
    ON withdrawals (status, created_at);

CREATE INDEX idx_withdrawals_from_address_created_at
    ON withdrawals (from_address, created_at);

CREATE INDEX idx_withdrawals_correlation_id
    ON withdrawals (correlation_id);

CREATE TABLE tx_attempts (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    withdrawal_id uuid NOT NULL,
    attempt_no integer NOT NULL,
    from_address varchar(128) NOT NULL,
    nonce bigint NOT NULL,
    attempt_group_key varchar(180) NOT NULL,
    tx_hash varchar(80),
    status varchar(32) NOT NULL,
    canonical boolean NOT NULL DEFAULT false,
    exception_type varchar(32),
    exception_detail varchar(500),
    max_priority_fee_per_gas numeric(38,0),
    max_fee_per_gas numeric(38,0),
    broadcasted_at timestamptz,
    included_at timestamptz,
    finalized_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT fk_tx_attempts_withdrawal
        FOREIGN KEY (withdrawal_id) REFERENCES withdrawals (id),
    CONSTRAINT uq_tx_attempts_withdrawal_attempt_no
        UNIQUE (withdrawal_id, attempt_no)
);

CREATE INDEX idx_tx_attempts_withdrawal_attempt_no
    ON tx_attempts (withdrawal_id, attempt_no);

CREATE INDEX idx_tx_attempts_attempt_group_key
    ON tx_attempts (attempt_group_key);

CREATE INDEX idx_tx_attempts_tx_hash
    ON tx_attempts (tx_hash);

CREATE INDEX idx_tx_attempts_status_canonical
    ON tx_attempts (status, canonical);

CREATE TABLE nonce_reservations (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    chain_type varchar(32) NOT NULL,
    from_address varchar(128) NOT NULL,
    nonce bigint NOT NULL,
    withdrawal_id uuid,
    attempt_id uuid,
    status varchar(32) NOT NULL,
    expires_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_nonce_reservations_chain_from_nonce
        UNIQUE (chain_type, from_address, nonce),
    CONSTRAINT fk_nonce_reservations_withdrawal
        FOREIGN KEY (withdrawal_id) REFERENCES withdrawals (id),
    CONSTRAINT fk_nonce_reservations_attempt
        FOREIGN KEY (attempt_id) REFERENCES tx_attempts (id)
);

CREATE INDEX idx_nonce_reservations_withdrawal_id
    ON nonce_reservations (withdrawal_id);

CREATE INDEX idx_nonce_reservations_attempt_id
    ON nonce_reservations (attempt_id);

CREATE INDEX idx_nonce_reservations_status_expires_at
    ON nonce_reservations (status, expires_at);

CREATE TABLE ledger_entries (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    withdrawal_id uuid NOT NULL,
    attempt_id uuid,
    entry_type varchar(32) NOT NULL,
    asset varchar(32) NOT NULL,
    amount numeric(38,0) NOT NULL,
    from_address varchar(128) NOT NULL,
    to_address varchar(128) NOT NULL,
    reference_id varchar(128),
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT fk_ledger_entries_withdrawal
        FOREIGN KEY (withdrawal_id) REFERENCES withdrawals (id),
    CONSTRAINT fk_ledger_entries_attempt
        FOREIGN KEY (attempt_id) REFERENCES tx_attempts (id)
);

CREATE INDEX idx_ledger_entries_withdrawal_created_at
    ON ledger_entries (withdrawal_id, created_at);

CREATE INDEX idx_ledger_entries_attempt_created_at
    ON ledger_entries (attempt_id, created_at);

CREATE INDEX idx_ledger_entries_entry_type_created_at
    ON ledger_entries (entry_type, created_at);

CREATE TABLE policy_decisions (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    withdrawal_id uuid NOT NULL,
    decision varchar(16) NOT NULL,
    reason_code varchar(64),
    reason_detail varchar(500),
    rule_snapshot jsonb,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT fk_policy_decisions_withdrawal
        FOREIGN KEY (withdrawal_id) REFERENCES withdrawals (id)
);

CREATE INDEX idx_policy_decisions_withdrawal_created_at
    ON policy_decisions (withdrawal_id, created_at);

CREATE INDEX idx_policy_decisions_decision_created_at
    ON policy_decisions (decision, created_at);

CREATE TABLE approval_tasks (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    withdrawal_id uuid NOT NULL,
    risk_tier varchar(16) NOT NULL,
    required_approvals integer NOT NULL,
    status varchar(32) NOT NULL,
    expires_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT fk_approval_tasks_withdrawal
        FOREIGN KEY (withdrawal_id) REFERENCES withdrawals (id)
);

CREATE INDEX idx_approval_tasks_withdrawal_id
    ON approval_tasks (withdrawal_id);

CREATE INDEX idx_approval_tasks_status_expires_at
    ON approval_tasks (status, expires_at);

CREATE TABLE approval_decisions (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    approval_task_id uuid NOT NULL,
    approver_id varchar(128) NOT NULL,
    decision varchar(16) NOT NULL,
    comment varchar(500),
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT fk_approval_decisions_task
        FOREIGN KEY (approval_task_id) REFERENCES approval_tasks (id),
    CONSTRAINT uq_approval_decisions_task_approver
        UNIQUE (approval_task_id, approver_id)
);

CREATE INDEX idx_approval_decisions_task_created_at
    ON approval_decisions (approval_task_id, created_at);

CREATE TABLE whitelist_addresses (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    address varchar(128) NOT NULL,
    chain_type varchar(32) NOT NULL,
    status varchar(32) NOT NULL,
    registered_by varchar(128) NOT NULL,
    approved_by varchar(128),
    revoked_by varchar(128),
    note varchar(500),
    active_after timestamptz,
    registered_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_whitelist_addresses_address_chain
        UNIQUE (address, chain_type)
);

CREATE INDEX idx_whitelist_addresses_status_active_after
    ON whitelist_addresses (status, active_after);

CREATE TABLE policy_change_requests (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    change_type varchar(64) NOT NULL,
    status varchar(32) NOT NULL,
    payload jsonb NOT NULL,
    requested_by varchar(128) NOT NULL,
    approved_at timestamptz,
    apply_after timestamptz,
    applied_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX idx_policy_change_requests_status_apply_after
    ON policy_change_requests (status, apply_after);

CREATE TABLE outbox_events (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    aggregate_type varchar(64) NOT NULL,
    aggregate_id uuid NOT NULL,
    event_type varchar(64) NOT NULL,
    payload jsonb NOT NULL,
    status varchar(32) NOT NULL,
    attempt_count integer NOT NULL DEFAULT 0,
    available_at timestamptz NOT NULL DEFAULT now(),
    created_at timestamptz NOT NULL DEFAULT now(),
    sent_at timestamptz
);

CREATE INDEX idx_outbox_events_status_available_at
    ON outbox_events (status, available_at);

CREATE INDEX idx_outbox_events_aggregate
    ON outbox_events (aggregate_type, aggregate_id);

CREATE TABLE rpc_observation_snapshots (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    provider_name varchar(64) NOT NULL,
    chain_type varchar(32) NOT NULL,
    tx_hash varchar(80),
    head_number bigint,
    receipt_found boolean,
    receipt_block_number bigint,
    observation_type varchar(32) NOT NULL,
    raw_payload jsonb,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX idx_rpc_observation_snapshots_provider_chain_created_at
    ON rpc_observation_snapshots (provider_name, chain_type, created_at);

CREATE INDEX idx_rpc_observation_snapshots_tx_hash_created_at
    ON rpc_observation_snapshots (tx_hash, created_at);

ALTER TABLE withdrawals
    ADD CONSTRAINT fk_withdrawals_policy_decision
        FOREIGN KEY (policy_decision_id) REFERENCES policy_decisions (id);

ALTER TABLE withdrawals
    ADD CONSTRAINT fk_withdrawals_approval_bundle
        FOREIGN KEY (approval_bundle_id) REFERENCES approval_tasks (id);
