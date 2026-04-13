-- 18-5: Test seed data for AssetRegistryService validation in integration tests.
-- H2-compatible MERGE syntax.
-- Requires spring.jpa.defer-datasource-initialization=true so tables exist when this runs.

-- supported_chains
MERGE INTO supported_chains (chain_type, native_asset, adapter_bean_name, enabled, created_at)
    KEY (chain_type)
    VALUES ('EVM',  'ETH', 'evmRpcAdapter',   TRUE, CURRENT_TIMESTAMP);

MERGE INTO supported_chains (chain_type, native_asset, adapter_bean_name, enabled, created_at)
    KEY (chain_type)
    VALUES ('BFT',  'BFT', 'bftMockAdapter',  TRUE, CURRENT_TIMESTAMP);

-- supported_assets (EVM — ETH native)
MERGE INTO supported_assets (id, asset_symbol, chain_type, contract_address, decimals, default_gas_limit, enabled, is_native, created_at)
    KEY (chain_type, asset_symbol)
    VALUES (RANDOM_UUID(), 'ETH', 'EVM', NULL, 18, 21000, TRUE, TRUE, CURRENT_TIMESTAMP);

-- supported_assets (EVM — USDC ERC-20)
MERGE INTO supported_assets (id, asset_symbol, chain_type, contract_address, decimals, default_gas_limit, enabled, is_native, created_at)
    KEY (chain_type, asset_symbol)
    VALUES (RANDOM_UUID(), 'USDC', 'EVM', '0xA0b86991c6218b36c1d19D4a2e9Eb0cE3606eB48', 6, 65000, TRUE, FALSE, CURRENT_TIMESTAMP);

-- supported_assets (BFT — native)
MERGE INTO supported_assets (id, asset_symbol, chain_type, contract_address, decimals, default_gas_limit, enabled, is_native, created_at)
    KEY (chain_type, asset_symbol)
    VALUES (RANDOM_UUID(), 'BFT', 'BFT', NULL, 0, 0, TRUE, TRUE, CURRENT_TIMESTAMP);

-- supported_assets (BFT — USDC)
MERGE INTO supported_assets (id, asset_symbol, chain_type, contract_address, decimals, default_gas_limit, enabled, is_native, created_at)
    KEY (chain_type, asset_symbol)
    VALUES (RANDOM_UUID(), 'USDC', 'BFT', NULL, 6, 0, TRUE, FALSE, CURRENT_TIMESTAMP);
