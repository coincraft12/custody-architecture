# custody-architecture

> Korean version: [README.ko.md](README.ko.md)

## 0) Project Goal

This is a learning-oriented architecture project focused on **withdrawal orchestration**, designed to practice and validate the core flows of a custody system: policy validation, idempotency, attempt/retry/replace, chain adapter integration, and audit logging.

You can verify each step through API calls and scenario execution — without reading all the code.

> **Note:** The default mode is **Mock**. Unless you configure a separate RPC setting (i.e., when `CUSTODY_CHAIN_MODE` is `mock`), the server uses a mock adapter and operates without a network connection. To use a real chain RPC, set `CUSTODY_CHAIN_MODE` to `rpc` and configure the RPC URL, chain ID, and private key in `application.yml` or environment variables.

---

## 1) Lab Overview

### Lab 1 — Policy Engine + Audit

- Amount limit enforcement
- Recipient address whitelist
- Allow/reject decisions recorded in audit log (`policy-audits`)

### Lab 2 — Withdrawal + Idempotency

- `POST /withdrawals` performs DB save + actual RPC broadcast
- Replay with same `Idempotency-Key` returns existing canonical `txHash` without re-broadcasting
- Verify inclusion via `GET /evm/tx/{txHash}/wait`

### Lab 3 — Retry / Replace (Real Chain Rules)

- `POST /withdrawals/{id}/retry`: broadcast a new attempt with a new nonce
- `POST /withdrawals/{id}/replace`: replace canonical with same nonce + fee bump
- `GET /withdrawals/{id}/attempts`: view accumulated/transitioned attempts

### Lab 4 — Chain Adapter + EVM RPC (Sepolia/Hoodi)

- When `custody.chain.mode=rpc`, the EVM adapter sends EIP-1559 type-2 signed transactions (`eth_sendRawTransaction`) to Sepolia/Hoodi
- BFT adapter maintains existing mock flow
- The orchestrator uses a unified call interface regardless of chain-specific details

### Lab 6 — Whitelist Address Registration + 48h Hold Workflow

- `POST /whitelist` to register a withdrawal-allowed address → `REGISTERED`
- `POST /whitelist/{id}/approve` to approve → `HOLDING` (48h hold timer starts)
- Scheduler automatically transitions to `ACTIVE` after 48h
- Attempting withdrawal to a non-`ACTIVE` address returns `W0_POLICY_REJECTED`
- `POST /whitelist/{id}/revoke` to cancel → `REVOKED` (immediate withdrawal block)

### Lab 7 — Withdrawal State Machine W1→W10 Complete

- Verify `W0 → W1 → W3 → W4 → W5 → W6` transitions on withdrawal creation
- `POST /sim/confirm` → simulate on-chain inclusion → `W7_INCLUDED`
- `POST /sim/finalize` → simulate finalization → `W8 → W9 → W10_COMPLETED`
- `GET /withdrawals/{id}/ledger` → view ledger entries (RESERVE, SETTLE)

---

## 2) Prerequisites

- Java 21+
- Gradle Wrapper (`./gradlew`)

**H2 Console**

- URL: `http://localhost:8080/h2`
- JDBC URL: `jdbc:h2:mem:testdb`
- Username: `sa`
- Password: *(empty)*

---

## 3) Getting Started

### Clone the Repository

```bash
git clone https://github.com/coincraft12/custody-architecture.git
```

### Build the Project

```bash
cd custody-architecture/custody
./gradlew build clean
```

### Configure RPC Connection

```powershell
$env:CUSTODY_CHAIN_MODE = "rpc"
$env:CUSTODY_EVM_RPC_URL = "https://ethereum-sepolia-rpc.publicnode.com"
$env:CUSTODY_EVM_CHAIN_ID = "11155111"
$env:CUSTODY_EVM_PRIVATE_KEY = "<YOUR_SEPOLIA_PRIVATE_KEY>"
```

> **Warning:** Only use test wallets. Never use mainnet or real wallet keys.

### Start the Server

```bash
./gradlew bootRun
```

> Set environment variables before starting the server. If values change while the server is running, restart the server.

**Lab tip:** To manually demonstrate the `INCLUDED` transition after broadcast, disable the automatic Confirmation Tracker:

```powershell
$env:CUSTODY_CONFIRMATION_TRACKER_AUTO_START = "false"
```

### Set Common Lab Variables

```powershell
$BASE_URL = "http://localhost:8080"
$CID_PREFIX = "lab"
$from = (Invoke-RestMethod -Uri "$BASE_URL/evm/wallet").address
$to   = "0x1111111111111111111111111111111111111111"
```

> **Tip:** Include `X-Correlation-Id` header in API calls for log tracing. If omitted, the server generates one automatically.

### Verify RPC Connection

```powershell
Invoke-RestMethod -Uri "$BASE_URL/evm/wallet"
```

Expected response:

```json
{
  "mode": "rpc",
  "chainId": 11155111,
  "rpc": "https://ethereum-sepolia-rpc.publicnode.com",
  "address": "0x740161186057d3a948a1c16f1978937dca269070",
  "balanceWei": 1587757348527098995,
  "balanceEth": 1.587757348527098995
}
```

---

## 5) Lab 1 — Policy + Audit

Default policy (`src/main/resources/application.yaml`):

- `policy.max-amount: 0.1`
- `policy.whitelist-to-addresses: 0xto, 0xtrusted, 0x1111111111111111111111111111111111111111`

> The whitelist now operates on a DB-based (`whitelist_addresses` table) model. Addresses from `policy.whitelist-to-addresses` are seeded as `ACTIVE` on startup, preserving existing lab behavior. To add new addresses, use the `POST /whitelist` → `approve` → (48h wait or manual scheduler trigger) workflow from Lab 6.

### 5-1. Allow Case

```powershell
$w = Invoke-RestMethod -Method POST `
  -Uri "$BASE_URL/withdrawals" `
  -Headers @{
    "Content-Type"    = "application/json"
    "Idempotency-Key" = "idem-lab5-allow-1"
    "X-Correlation-Id" = "$CID_PREFIX-lab5-allow-001"
  } `
  -Body (@{
    chainType   = "EVM"
    fromAddress = $from
    toAddress   = $to
    asset       = "ETH"
    amount      = 0.00001
  } | ConvertTo-Json -Depth 10)
$w
```

Expected: `status = W6_BROADCASTED`

### 5-2. Whitelist Rejection Case

```powershell
$w = Invoke-RestMethod -Method POST `
  -Uri "$BASE_URL/withdrawals" `
  -Headers @{
    "Content-Type"    = "application/json"
    "Idempotency-Key" = "idem-lab4-reject-whitelist-1"
    "X-Correlation-Id" = "$CID_PREFIX-lab5-reject-whitelist-001"
  } `
  -Body (@{
    chainType   = "EVM"
    fromAddress = $from
    toAddress   = "0x2222222222222222222222222222222222222222"
    asset       = "USDC"
    amount      = 0.00001
  } | ConvertTo-Json -Depth 10)
$w
```

Expected:
- `status = W0_POLICY_REJECTED`
- Audit query:

```powershell
Invoke-RestMethod -Method GET -Uri "$BASE_URL/withdrawals/$($w.id)/policy-audits"
```

Expected reason: `TO_ADDRESS_NOT_WHITELISTED: 0xnot-allowed`

### 5-3. Amount Exceeded Rejection Case

```powershell
$w = Invoke-RestMethod -Method POST `
  -Uri "$BASE_URL/withdrawals" `
  -Headers @{
    "Content-Type"    = "application/json"
    "Idempotency-Key" = "idem-lab4-reject-amount-1"
    "X-Correlation-Id" = "$CID_PREFIX-lab5-reject-amount-001"
  } `
  -Body (@{
    chainType   = "EVM"
    fromAddress = $from
    toAddress   = $to
    asset       = "USDC"
    amount      = 0.2   # exceeds policy limit
  } | ConvertTo-Json -Depth 10)
$w
```

Expected:
- `status = W0_POLICY_REJECTED`
- Audit reason: `AMOUNT_LIMIT_EXCEEDED: max=0.1, requested=0.2`

---

## 6) Lab 2 — Idempotency + Initial Attempt Creation

### Goal

- Verify that concurrent requests with the same `Idempotency-Key` create only one Withdrawal
- Confirm that attempt count does not increase unnecessarily

### 6-1. Create Withdrawal

```powershell
$idemp = "idem-lab1-2"
$w = Invoke-RestMethod -Method POST `
  -Uri "$BASE_URL/withdrawals" `
  -Headers @{ "Content-Type"="application/json"; "Idempotency-Key"=$idemp; "X-Correlation-Id"="$CID_PREFIX-lab6-create-001" } `
  -Body (@{
    chainType   = "EVM"
    fromAddress = $from
    toAddress   = $to
    asset       = "ETH"
    amount      = 0.001
  } | ConvertTo-Json)
$w
```

### 6-2. Replay with Same Key + Same Body

```powershell
$w = Invoke-RestMethod -Method POST `
  -Uri "$BASE_URL/withdrawals" `
  -Headers @{ "Content-Type"="application/json"; "Idempotency-Key"=$idemp; "X-Correlation-Id"="$CID_PREFIX-lab6-replay-001" } `
  -Body (@{
    chainType   = "EVM"
    fromAddress = $from
    toAddress   = $to
    asset       = "ETH"
    amount      = 0.001
  } | ConvertTo-Json)
$w
```

Expected: same `withdrawal id` as the first request

### 6-3. View Attempt List

```powershell
Invoke-RestMethod -Method GET -Uri "$BASE_URL/withdrawals/$($w.id)/attempts"
```

### 6-4. Same Key + Different Body (Conflict)

```powershell
$idemp = "idem-lab1-2"
$w = Invoke-RestMethod -Method POST `
  -Uri "$BASE_URL/withdrawals" `
  -Headers @{ "Content-Type"="application/json"; "Idempotency-Key"=$idemp; "X-Correlation-Id"="$CID_PREFIX-lab6-conflict-001" } `
  -Body (@{
    chainType   = "EVM"
    fromAddress = $from
    toAddress   = $to
    asset       = "ETH"
    amount      = 0.0001  # different amount
  } | ConvertTo-Json)
```

Expected: HTTP 409 — `same Idempotency-Key cannot be used with a different request body`

### 6-5. Concurrent Requests (PowerShell)

```powershell
$idemp   = "idem-concurrency-2"
$jobs = 1..5 | ForEach-Object {
  Start-Job -ScriptBlock {
    param($BASE_URL, $from, $to, $idemp)
    try {
      $cid = "lab6-concurrency-$([guid]::NewGuid().ToString('N').Substring(0,8))"
      Invoke-RestMethod -Method POST `
        -Uri "$BASE_URL/withdrawals" `
        -Headers @{ "Content-Type"="application/json"; "Idempotency-Key"=$idemp; "X-Correlation-Id"=$cid } `
        -Body (@{
          chainType="EVM"; fromAddress=$from; toAddress=$to; asset="USDC"; amount=0.00001
        } | ConvertTo-Json -Depth 10)
    } catch {
      $r = $_.Exception.Response
      $sr = New-Object System.IO.StreamReader($r.GetResponseStream())
      $sr.ReadToEnd()
    }
  } -ArgumentList $BASE_URL, $from, $to, $idemp
}
$jobs | Receive-Job -Wait -AutoRemoveJob
```

### 6-6. Verification Points

- All returned `id` values are identical
- `/withdrawals/{id}/attempts` shows exactly 1 initial attempt

---

## 7) Lab 3 — Retry / Replace / Included

### 7-1. Create Withdrawal

```powershell
$idemp = "idem-lab3-1"
$w = Invoke-RestMethod -Method POST `
  -Uri "$BASE_URL/withdrawals" `
  -Headers @{ "Content-Type"="application/json"; "Idempotency-Key"=$idemp; "X-Correlation-Id"="$CID_PREFIX-lab7-create-001" } `
  -Body (@{
    chainType   = "EVM"
    fromAddress = $from
    toAddress   = $to
    asset       = "USDC"
    amount      = 0.0001
  } | ConvertTo-Json)
$w
```

### 7-2. Retry (New Nonce Broadcast)

```powershell
Invoke-RestMethod -Method POST `
  -Uri "$BASE_URL/withdrawals/$($w.id)/retry" `
  -Headers @{ "X-Correlation-Id" = "$CID_PREFIX-lab7-retry-001" }

Invoke-RestMethod -Method GET -Uri "$BASE_URL/withdrawals/$($w.id)/attempts"
```

Expected:
- 2 attempts
- Previous attempt: `canonical=false`, `status=FAILED_TIMEOUT`
- Latest attempt: `canonical=true` (broadcast with new nonce)

### 7-3. Replace (Same Nonce, Fee Bump)

```powershell
Invoke-RestMethod -Method POST `
  -Uri "$BASE_URL/withdrawals/$($w.id)/replace" `
  -Headers @{ "X-Correlation-Id" = "$CID_PREFIX-lab7-replace-001" }

Invoke-RestMethod -Method GET -Uri "$BASE_URL/withdrawals/$($w.id)/attempts"
```

Expected:
- 3 attempts
- Previous canonical: `REPLACED`, `canonical=false`
- Latest attempt: `canonical=true` (sent with same nonce + higher fee)

> If the retry transaction was already included in a block, replace will be rejected with a `nonce too low` message. In that case, run retry again to use a new nonce.

### 7-4. Confirmation Tracker — Receipt Check → INCLUDED Transition

The background Confirmation Tracker polls `eth_getTransactionReceipt(txHash)` periodically. When a receipt is found, the canonical `TxAttempt` and `Withdrawal` are automatically transitioned to `INCLUDED`.

```powershell
# Manual sync
Invoke-RestMethod -Method POST `
  -Uri "$BASE_URL/withdrawals/$($w.id)/sync" `
  -Headers @{ "X-Correlation-Id" = "$CID_PREFIX-lab7-sync-001" }
```

---

## 8) Lab 4 — Chain Adapter Validation

### 8-1. EVM Adapter (Sepolia RPC)

```powershell
Invoke-RestMethod -Method POST `
  -Uri "$BASE_URL/adapter-demo/broadcast/evm" `
  -Headers @{ "Content-Type"="application/json"; "X-Correlation-Id" = "$CID_PREFIX-lab8-evm-broadcast-001" } `
  -Body (@{
    from   = $from
    to     = $to
    asset  = "ETH"
    amount = 0.00001
  } | ConvertTo-Json)
```

Expected:
- `accepted = true`
- `txHash` is a real EVM hash (`0x...`)

### 8-2. BFT Adapter

```powershell
Invoke-RestMethod -Method POST `
  -Uri "$BASE_URL/adapter-demo/broadcast/bft" `
  -Headers @{ "Content-Type"="application/json"; "X-Correlation-Id" = "$CID_PREFIX-lab8-bft-broadcast-001" } `
  -Body (@{
    from   = "a"
    to     = "b"
    asset  = "TOKEN"
    amount = 0.00001
    nonce  = 1
  } | ConvertTo-Json)
```

Expected:
- `accepted = true`
- `txHash` prefix: `BFT_`

### Mock Tests

```powershell
$env:CUSTODY_CHAIN_MODE = "mock"
./gradlew test --tests "**IntegrationTest*"
```

---

## 9) Lab 5 — Correlation ID + Log Standardization

### Goal

- Receive/generate `X-Correlation-Id` per request and return it in response headers
- Output `cid` in application logs with a common format
- Standardize controller/service logs in `event=... key=value` format
- Include `correlationId` in error response body

### 9-1. Pass Correlation ID in Request Header

```powershell
$idemp = "lab-cid-001"
$w = Invoke-RestMethod -Method POST `
  -Uri "$BASE_URL/withdrawals" `
  -Headers @{
    "Content-Type"    = "application/json"
    "Idempotency-Key" = $idemp
    "X-Correlation-Id" = "cid-lab-001"
  } `
  -Body (@{
    chainType   = "EVM"
    fromAddress = (Invoke-RestMethod -Uri "$BASE_URL/evm/wallet").address
    toAddress   = $to
    asset       = "ETH"
    amount      = 0.001
  } | ConvertTo-Json)
$w
```

Expected:
- Response header: `X-Correlation-Id: cid-lab-001`
- Log output contains `correlationId = "cid-lab-001"`

### 9-2. Server Auto-generates Correlation ID When Not Provided

Expected:
- Response header `X-Correlation-Id` is non-empty (server-generated UUID)

### 9-3. Check correlationId in Error Response

```powershell
Invoke-WebRequest `
  -Uri "$BASE_URL/withdrawals" `
  -Method POST `
  -Headers @{
    "Idempotency-Key"  = "lab-cid-bad-json-001"
    "X-Correlation-Id" = "cid-bad-json-001"
  } `
  -ContentType "application/json" `
  -Body "{'chainType':'evm'}"
```

Expected:
- `400 Bad Request`
- Response header: `X-Correlation-Id: cid-bad-json-001`
- Response body includes `correlationId = "cid-bad-json-001"`

---

## 10) Lab 6 — Whitelist Address Registration + 48h Hold Workflow

### Goal

- Understand the transition from static config whitelist → DB-based dynamic whitelist
- Execute `REGISTERED → HOLDING(48h) → ACTIVE` state machine directly
- Verify that `HOLDING` and `REVOKED` addresses are automatically blocked by the policy engine

### Workflow Summary

```
POST /whitelist              → REGISTERED  (withdrawal blocked)
POST /whitelist/{id}/approve → HOLDING     (withdrawal blocked, 48h timer starts)
[48h elapsed, scheduler runs] → ACTIVE     (withdrawal allowed)
POST /whitelist/{id}/revoke  → REVOKED     (withdrawal blocked)
```

### 10-1. Setup

```powershell
$BASE_URL = "http://localhost:8080"
$NEW_ADDR = "0x9999888877776666555544443333222211110000"
```

### 10-2. Register Address → REGISTERED

```powershell
$entry = Invoke-RestMethod -Method POST `
  -Uri "$BASE_URL/whitelist" `
  -Headers @{ "Content-Type" = "application/json" } `
  -Body (@{
    address      = $NEW_ADDR
    chainType    = "EVM"
    registeredBy = "ops-admin"
    note         = "partner settlement address"
  } | ConvertTo-Json)
$entry
$wlId = $entry.id
```

Expected: `status = REGISTERED`, `approvedAt = null`, `activeAfter = null`

### 10-3. Withdrawal Attempt in REGISTERED State → Policy Rejected

Expected: `status = W0_POLICY_REJECTED`

### 10-4. Admin Approve → HOLDING (48h Timer Starts)

```powershell
$entry = Invoke-RestMethod -Method POST `
  -Uri "$BASE_URL/whitelist/$wlId/approve" `
  -Headers @{ "Content-Type" = "application/json" } `
  -Body (@{ approvedBy = "ops-admin" } | ConvertTo-Json)
$entry
```

Expected: `status = HOLDING`, `activeAfter = approvedAt + 48h`

### 10-5. Withdrawal Attempt in HOLDING State → Still Rejected

Expected: `status = W0_POLICY_REJECTED`

### 10-6. After 48h → ACTIVE

The `@Scheduled` scheduler (default: 60s interval) automatically checks and transitions.

```powershell
$entry = Invoke-RestMethod -Uri "$BASE_URL/whitelist/$wlId"
$entry.activeAfter   # scheduler transitions to ACTIVE after this time
```

### 10-7. Withdrawal in ACTIVE State → Success

Expected: `status = W6_BROADCASTED`

### 10-8. Revoke → REVOKED

```powershell
$entry = Invoke-RestMethod -Method POST `
  -Uri "$BASE_URL/whitelist/$wlId/revoke" `
  -Headers @{ "Content-Type" = "application/json" } `
  -Body (@{ revokedBy = "security-team" } | ConvertTo-Json)
$entry
```

Expected: `status = REVOKED` — subsequent withdrawals to this address return `W0_POLICY_REJECTED`

### 10-9. List with Status Filter

```powershell
Invoke-RestMethod -Uri "$BASE_URL/whitelist"
Invoke-RestMethod -Uri "$BASE_URL/whitelist?status=HOLDING"
Invoke-RestMethod -Uri "$BASE_URL/whitelist?status=ACTIVE"
```

### Environment Variables

| Variable | Default | Description |
|---|---|---|
| `CUSTODY_WHITELIST_HOLD_HOURS` | `48` | Hold duration after approval (hours) |
| `CUSTODY_WHITELIST_SCHEDULER_DELAY_MS` | `60000` | Scheduler interval (ms) |

---

## 11) Lab 7 — Withdrawal State Machine W1→W10 Complete

### Goal

- Understand `W1→W3→W4→W5→W6` transitions within `createAndBroadcast()`
- Advance to W10 manually via simulation endpoints (`/sim/confirm`, `/sim/finalize`)
- View ledger entries (`LedgerEntry`) — `RESERVE` (on approval) + `SETTLE` (on finalization)

### State Machine Summary

```
W0_REQUESTED
  → W1_POLICY_CHECKED    (policy + approval passed)
  → W3_APPROVED          (approval complete + RESERVE ledger entry)
  → W4_SIGNING           (signing requested)
  → W5_SIGNED            (signing complete — mock adapter handles atomically)
  → W6_BROADCASTED       (node submission complete)
  [POST /sim/confirm]
  → W7_INCLUDED          (on-chain inclusion confirmed)
  [POST /sim/finalize]
  → W8_SAFE_FINALIZED    (sufficient block confirmations)
  → W9_LEDGER_POSTED     (SETTLE ledger entry recorded)
  → W10_COMPLETED        (fully complete)
```

> **Note:** W1~W5 occur within a single `TransactionTemplate` in `createAndBroadcast()`, so only the final state (`W6_BROADCASTED`) is committed to the DB. Read the code flow and check log output at each transition point.

### 11-2. Create Withdrawal → W6_BROADCASTED

```powershell
$w = Invoke-RestMethod -Method POST `
  -Uri "$BASE_URL/withdrawals" `
  -Headers @{
    "Content-Type"    = "application/json"
    "Idempotency-Key" = "idem-lab7-1"
  } `
  -Body (@{
    chainType   = "EVM"
    fromAddress = $from
    toAddress   = $to
    asset       = "ETH"
    amount      = 0.0001
  } | ConvertTo-Json)
$w
$wId = $w.id
```

### 11-3. View Ledger — RESERVE Entry

```powershell
Invoke-RestMethod -Uri "$BASE_URL/withdrawals/$wId/ledger"
```

Expected: one `RESERVE` entry created at `W3_APPROVED`

### 11-4. Simulate On-chain Inclusion → W7_INCLUDED

```powershell
Invoke-RestMethod -Method POST -Uri "$BASE_URL/sim/withdrawals/$wId/confirm"
Invoke-RestMethod -Uri "$BASE_URL/withdrawals/$wId"
```

Expected: `status = W7_INCLUDED`

### 11-5. Simulate Finalization → W10_COMPLETED

```powershell
$finalized = Invoke-RestMethod -Method POST -Uri "$BASE_URL/sim/withdrawals/$wId/finalize"
$finalized.status   # W10_COMPLETED
```

### 11-6. View Ledger — RESERVE + SETTLE Entries

```powershell
Invoke-RestMethod -Uri "$BASE_URL/withdrawals/$wId/ledger"
```

Expected: `RESERVE` (W3) + `SETTLE` (W8) entries in order

---

## 12) API Reference

| Method | Endpoint | Description |
|---|---|---|
| `POST` | `/withdrawals` | Create withdrawal (Header: `Idempotency-Key`) |
| `GET` | `/withdrawals/{id}` | Get withdrawal |
| `GET` | `/withdrawals/{id}/attempts` | List tx attempts |
| `GET` | `/withdrawals/{id}/policy-audits` | View policy audit log |
| `GET` | `/withdrawals/{id}/ledger` | View ledger entries (RESERVE, SETTLE) |
| `POST` | `/withdrawals/{id}/retry` | Retry with new nonce |
| `POST` | `/withdrawals/{id}/replace` | Replace with fee bump |
| `POST` | `/withdrawals/{id}/sync` | Manually sync receipt |
| `POST` | `/adapter-demo/broadcast/{evm\|bft}` | Direct adapter broadcast |
| `GET` | `/evm/wallet` | Wallet info (RPC mode only) |
| `GET` | `/evm/tx/{txHash}` | Check tx (RPC mode only) |
| `GET` | `/evm/tx/{txHash}/wait` | Poll for tx receipt (RPC mode only) |
| `POST` | `/whitelist` | Register address |
| `POST` | `/whitelist/{id}/approve` | Approve (→ HOLDING) |
| `POST` | `/whitelist/{id}/revoke` | Revoke (→ REVOKED) |
| `GET` | `/whitelist` | List (`?status=REGISTERED\|HOLDING\|ACTIVE\|REVOKED`) |
| `GET` | `/whitelist/{id}` | Get single entry |
| `POST` | `/sim/withdrawals/{id}/confirm` | Simulate on-chain inclusion (→ W7_INCLUDED) |
| `POST` | `/sim/withdrawals/{id}/finalize` | Simulate finalization (→ W10_COMPLETED) |

---

## 13) Suggested Extensions

- Multi-rule policy (priority ordering, multiple rejection reasons)
- Adapter timeout / partial failure scenario expansion
- State transition invariant tests (canonical count must always be 1)
- Propagate correlation ID (MDC) to async tasks (ConfirmationTracker)
- Structured JSON logging (search/aggregation-friendly format)
- Sensitive data masking rules (addresses, keys, exception messages)
- Whitelist change history audit log (`WhitelistAuditLog`)
- Quorum approval (4-eyes) — require 2+ approve calls before HOLDING transition
- `activeAfter` admin force-shorten/extend feature

---

## 14) PostgreSQL + Flyway (Production Mode)

The default `./gradlew build` only compiles and tests. PostgreSQL table creation is handled by Flyway when the server runs with the `postgres` profile.

### 14-1. Start PostgreSQL

```powershell
cd F:\Workplace\custody
docker compose up -d
```

Default connection:

| Field | Value |
|---|---|
| DB | `custody` |
| Username | `custody` |
| Password | `custody` |
| Port | `5432` |

### 14-2. Run Server with `postgres` Profile

```powershell
cd F:\Workplace\custody\custody
$env:CUSTODY_DB_URL = "jdbc:postgresql://localhost:5432/custody"
$env:CUSTODY_DB_USERNAME = "custody"
$env:CUSTODY_DB_PASSWORD = "custody"
.\gradlew bootRun --args='--spring.profiles.active=postgres'
```

### 14-3. Verify Flyway Migrations

```powershell
docker exec -it custody-postgres psql -U custody -d custody -c "\dt"
```

Expected tables:
- `withdrawals`, `tx_attempts`, `nonce_reservations`, `ledger_entries`
- `policy_decisions`, `approval_tasks`, `approval_decisions`
- `whitelist_addresses`, `policy_change_requests`
- `outbox_events`, `rpc_observation_snapshots`

```powershell
docker exec -it custody-postgres psql -U custody -d custody -c "select installed_rank, version, description, success from flyway_schema_history order by installed_rank;"
```

Expected: `version = 1`, `description = operational schema postgresql`, `success = true`

### 14-4. Notes

- Flyway is disabled for the default H2/test path.
- Production DB validation requires Docker to be installed.

---

## Security / Safety Guards

- `custody.evm.chain-id=1` (mainnet) is blocked at startup.
- In `rpc` mode, missing `CUSTODY_EVM_PRIVATE_KEY` or `CUSTODY_EVM_RPC_URL` causes `RpcModeStartupGuard` to abort startup.
- `EvmRpcAdapter` validates that the RPC's actual `eth_chainId` matches the configured value before broadcasting.
- **Never commit private keys.**

---

## Troubleshooting — Reading RPC Error Response Body (PowerShell)

```powershell
try {
  Invoke-RestMethod -Method POST `
    -Uri "$BASE_URL/withdrawals/$($w.id)/replace" `
    -Headers @{ "X-Correlation-Id" = "$CID_PREFIX-troubleshoot-replace-001" }
} catch {
  $resp = $_.Exception.Response
  $reader = New-Object System.IO.StreamReader($resp.GetResponseStream())
  $reader.ReadToEnd()
}
```

Use this to inspect actual error messages (e.g., `nonce too low`) when RPC-related API calls fail.
