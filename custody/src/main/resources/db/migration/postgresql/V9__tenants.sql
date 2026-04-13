-- 테넌트 기본 테이블
CREATE TABLE tenants (
    tenant_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(128) NOT NULL UNIQUE,
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',  -- ACTIVE, SUSPENDED
    plan VARCHAR(64),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- 기본 테넌트 (기존 데이터 마이그레이션용)
INSERT INTO tenants (tenant_id, name, status) VALUES
('00000000-0000-0000-0000-000000000001', 'DEFAULT', 'ACTIVE');

-- 테넌트 멤버 (대시보드 사용자)
CREATE TABLE tenant_members (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(tenant_id),
    user_id VARCHAR(128) NOT NULL,
    role VARCHAR(32) NOT NULL,  -- OPERATOR, APPROVER, ADMIN, AUDITOR
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_tenant_members_tenant_user UNIQUE (tenant_id, user_id)
);

-- API Key (외부 서비스 연동)
CREATE TABLE tenant_api_keys (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(tenant_id),
    key_hash VARCHAR(128) NOT NULL UNIQUE,  -- SHA-256 hash of actual key
    role VARCHAR(32) NOT NULL,  -- OPERATOR, APPROVER, ADMIN
    expires_at TIMESTAMPTZ,     -- NULL = 만료 없음
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_tenant_api_keys_tenant_id ON tenant_api_keys(tenant_id);
CREATE INDEX idx_tenant_api_keys_key_hash ON tenant_api_keys(key_hash);
