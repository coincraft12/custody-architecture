-- V3: whitelist_audit_log 테이블 신규 생성 + approval_tasks.approved_count 컬럼 추가

-- ─────────────────────────────────────────────────────────────────
-- 1. whitelist_audit_log
--    WhitelistService의 상태 전이(approve / revoke / activate) 이력을 불변 레코드로 보관
-- ─────────────────────────────────────────────────────────────────

CREATE TABLE whitelist_audit_log (
    id                   uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    whitelist_address_id uuid        NOT NULL,
    action               varchar(32) NOT NULL,
    actor_id             varchar(128) NOT NULL,
    previous_status      varchar(32),
    new_status           varchar(32) NOT NULL,
    created_at           timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT fk_whitelist_audit_log_address
        FOREIGN KEY (whitelist_address_id) REFERENCES whitelist_addresses (id)
);

CREATE INDEX idx_whitelist_audit_log_address_created_at
    ON whitelist_audit_log (whitelist_address_id, created_at);

CREATE INDEX idx_whitelist_audit_log_action_created_at
    ON whitelist_audit_log (action, created_at);

-- ─────────────────────────────────────────────────────────────────
-- 2. approval_tasks.approved_count 컬럼 추가
--    4-eyes 승인 로직에서 현재 누적 승인 수를 추적하기 위해 필요
-- ─────────────────────────────────────────────────────────────────

ALTER TABLE approval_tasks
    ADD COLUMN IF NOT EXISTS approved_count integer NOT NULL DEFAULT 0;
