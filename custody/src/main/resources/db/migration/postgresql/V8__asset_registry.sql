-- V8: Asset Registry — supported_chains + supported_assets (섹션 22)

CREATE TABLE supported_chains (
    chain_type          VARCHAR(32)  PRIMARY KEY,
    native_asset        VARCHAR(20)  NOT NULL,
    adapter_bean_name   VARCHAR(64)  NOT NULL,
    enabled             BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE TABLE supported_assets (
    id                  UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    asset_symbol        VARCHAR(20)  NOT NULL,
    chain_type          VARCHAR(32)  NOT NULL REFERENCES supported_chains(chain_type),
    contract_address    VARCHAR(128),               -- NULL이면 native asset
    decimals            INT          NOT NULL,
    default_gas_limit   BIGINT       NOT NULL DEFAULT 21000,
    enabled             BOOLEAN      NOT NULL DEFAULT TRUE,
    is_native           BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_supported_assets_chain_symbol UNIQUE (chain_type, asset_symbol)
);

-- seed: supported_chains
INSERT INTO supported_chains (chain_type, native_asset, adapter_bean_name) VALUES
('EVM',     'ETH', 'evmRpcAdapter'),
('BFT',     'BFT', 'bftMockAdapter'),
('BITCOIN', 'BTC', 'bitcoinAdapter'),
('TRON',    'TRX', 'tronAdapter'),
('SOLANA',  'SOL', 'solanaAdapter');

-- seed: supported_assets (EVM)
INSERT INTO supported_assets (asset_symbol, chain_type, contract_address, decimals, default_gas_limit, is_native) VALUES
('ETH',  'EVM', NULL,                                          18, 21000, TRUE),
('USDC', 'EVM', '0xA0b86991c6218b36c1d19D4a2e9Eb0cE3606eB48',  6, 65000, FALSE),
('USDT', 'EVM', '0xdAC17F958D2ee523a2206206994597C13D831ec7',  6, 65000, FALSE);

-- seed: supported_assets (Bitcoin)
INSERT INTO supported_assets (asset_symbol, chain_type, contract_address, decimals, default_gas_limit, is_native) VALUES
('BTC', 'BITCOIN', NULL, 8, 0, TRUE);

-- seed: supported_assets (TRON)
INSERT INTO supported_assets (asset_symbol, chain_type, contract_address, decimals, default_gas_limit, is_native) VALUES
('TRX',  'TRON', NULL,                               6,      0, TRUE),
('USDT', 'TRON', 'TR7NHqjeKQxGTCi8q8ZY4pL8otSzgjLj6t', 6, 100000, FALSE);

-- seed: supported_assets (SOLANA)
INSERT INTO supported_assets (asset_symbol, chain_type, contract_address, decimals, default_gas_limit, is_native) VALUES
('SOL',  'SOLANA', NULL,                                           9, 0, TRUE),
('USDC', 'SOLANA', 'EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v', 6, 0, FALSE);
