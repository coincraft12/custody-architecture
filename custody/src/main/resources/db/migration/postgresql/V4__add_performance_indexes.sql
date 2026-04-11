-- V4: 성능 인덱스 추가 (7-2)
-- 기존 V1 인덱스: idx_withdrawals_status_created_at (status, created_at),
--                 idx_tx_attempts_tx_hash, idx_whitelist_addresses_status_active_after,
--                 idx_ledger_entries_withdrawal_created_at (withdrawal_id, created_at)
-- 신규: 단일 status 조회, status+updated_at 복합 조회, ledger type 필터링

-- 7-2-1: withdrawals.status 단일 컬럼 인덱스
--   - StartupRecoveryService: findByStatus(W6_BROADCASTED) — status 단독 조회
--   - WithdrawalRepository.countByStatus() — health indicator
CREATE INDEX IF NOT EXISTS idx_withdrawals_status
    ON withdrawals (status);

-- 7-2-2: withdrawals(status, updated_at) 복합 인덱스
--   - 넌스 만료 스케줄러: status=NONCE_RESERVED AND updated_at < cutoff 필터
--   - 감사/모니터링 쿼리: 특정 상태의 최근 변경 건 조회
CREATE INDEX IF NOT EXISTS idx_withdrawals_status_updated_at
    ON withdrawals (status, updated_at);

-- 7-2-5: ledger_entries(withdrawal_id, type) 복합 인덱스
--   - LedgerService.reserve/settle: withdrawal_id + type 조합 조회
--   - RESERVE/SETTLE 기록 빠른 존재 여부 확인
CREATE INDEX IF NOT EXISTS idx_ledger_entries_withdrawal_type
    ON ledger_entries (withdrawal_id, type);
