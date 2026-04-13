-- tenant_id를 NULL 허용으로 추가 (기존 데이터 유지)
ALTER TABLE withdrawals ADD COLUMN IF NOT EXISTS tenant_id UUID;
ALTER TABLE whitelist_addresses ADD COLUMN IF NOT EXISTS tenant_id UUID;
ALTER TABLE ledger_entries ADD COLUMN IF NOT EXISTS tenant_id UUID;
ALTER TABLE policy_audit_logs ADD COLUMN IF NOT EXISTS tenant_id UUID;
ALTER TABLE approval_tasks ADD COLUMN IF NOT EXISTS tenant_id UUID;
ALTER TABLE nonce_reservations ADD COLUMN IF NOT EXISTS tenant_id UUID;
