-- 백필 완료 후 NOT NULL 제약 추가
ALTER TABLE withdrawals ALTER COLUMN tenant_id SET NOT NULL;
ALTER TABLE whitelist_addresses ALTER COLUMN tenant_id SET NOT NULL;
ALTER TABLE ledger_entries ALTER COLUMN tenant_id SET NOT NULL;
ALTER TABLE policy_audit_logs ALTER COLUMN tenant_id SET NOT NULL;
ALTER TABLE approval_tasks ALTER COLUMN tenant_id SET NOT NULL;
ALTER TABLE nonce_reservations ALTER COLUMN tenant_id SET NOT NULL;

-- 인덱스 추가
CREATE INDEX idx_withdrawals_tenant_id ON withdrawals(tenant_id);
CREATE INDEX idx_whitelist_addresses_tenant_id ON whitelist_addresses(tenant_id);
CREATE INDEX idx_ledger_entries_tenant_id ON ledger_entries(tenant_id);
CREATE INDEX idx_nonce_reservations_tenant_id ON nonce_reservations(tenant_id);
