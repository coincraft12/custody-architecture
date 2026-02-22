# custody-architecture

## 0) 이 README의 목표

이 문서는 “코드를 읽지 않고도” 다음을 할 수 있게 설계했습니다.

주의: 기본 설정은 목(Mock) 모드입니다. 별도 RPC 설정을 하지 않으면(예: `CUSTODY_CHAIN_MODE`가 `mock`일 때)
서버는 목 어댑터를 사용해 네트워크 없이 동작합니다. 실제 체인 RPC를 사용하려면 `CUSTODY_CHAIN_MODE`를 `rpc`로 설정하고,
RPC 관련 세부 설정(RPC URL, 체인 ID, 개인키 등)을 애플리케이션 설정(`application.yml` 또는 환경변수)에서 구성하세요. 

---

## 1) 실습 전체 지도

### 실습 1 — RPC Full Mode Withdrawal + 멱등성

- `POST /withdrawals`는 DB 저장 + 실제 RPC 브로드캐스트까지 수행
- 동일 `Idempotency-Key` 재호출 시 재브로드캐스트 없이 기존 canonical `txHash` 유지
- `GET /evm/tx/{txHash}/wait`로 포함 여부 확인

### 실습 2 — Retry / Replace (실체인 규칙)

- `POST /withdrawals/{id}/retry`: 새 nonce로 새 attempt 브로드캐스트
- `POST /withdrawals/{id}/replace`: 같은 nonce fee bump로 canonical 교체
- `GET /withdrawals/{id}/attempts`로 누적/전환 확인

### 실습 3 — Chain Adapter + EVM RPC(Sepolia/Hoodi) 연동

- `custody.chain.mode=rpc`일 때 EVM adapter는 실제 RPC(Sepolia/Hoodi)에 EIP-1559 타입2 서명 트랜잭션(`eth_sendRawTransaction`)을 전송
- BFT adapter는 기존 mock 흐름 유지
- 오케스트레이터는 체인별 세부사항을 몰라도 동일한 호출 형태 유지

### 실습 4 — Policy Engine + Audit

- 금액 제한
- 수신 주소 화이트리스트
- 허용/거절 근거를 감사 로그(`policy-audits`)로 확인

### 실습 5 (심화) — 관찰성 + 동시성(실습 경험 개선)

- 동시 요청에서 멱등성이 깨지지 않는지 확인
- 상태 전이/attempt 누적을 관찰 가능한 형태로 점검

---

## 2) 준비물

- Java 21+
- Gradle Wrapper (`./gradlew`)
- 기본 포트: `8080`
- 권장 프로파일: `SPRING_PROFILES_ACTIVE=labs-rpc` (내부적으로 `custody.chain.mode=rpc`) — 선택 사항 (기본은 목(Mock) 모드)
 - 서비스 모드 환경변수: `CUSTODY_CHAIN_MODE` (`mock` 또는 `rpc`) — 기본값: `mock`
   - `mock`: 네트워크 호출 없이 내부 mock adapter로 동작
   - `rpc`: 실제 RPC를 사용해 브로드캐스트/영수증 확인 수행
   - RPC 모드에서 필요한 세부 설정(RPC URL, 체인 ID, 개인키 등)은 애플리케이션 설정(`application.yml` 또는 환경변수)을 통해 구성하세요.

H2 Console

- URL: `http://localhost:8080/h2`
- JDBC URL: `jdbc:h2:mem:testdb`
- username: `sa`
- password: 빈 값

---

## 3) 빠른 시작

```bash
cd custody
./gradlew build clean
./gradlew bootRun
```

서버가 뜨면 새 터미널에서 아래 API를 호출하세요.

---

## 4) 실습용 공통 변수

### PowerShell

```powershell
$BASE_URL = "http://localhost:8080"
```

### RPC 연결

먼저 서비스 모드를 `rpc`로 설정하고, 프로젝트 설정에서 RPC URL/체인 ID/개인키 등을 구성하세요.

```powershell
$env:CUSTODY_CHAIN_MODE = "rpc"
$env:CUSTODY_EVM_RPC_URL = "https://ethereum-sepolia-rpc.publicnode.com"
$env:CUSTODY_EVM_CHAIN_ID = "11155111"
$env:CUSTODY_EVM_PRIVATE_KEY = "<YOUR_SEPOLIA_OR_HOODI_PRIVATE_KEY>"
```

주의: RPC 모드에서 사용하는 RPC URL, 체인 ID, 개인키 등의 세부 구성은 `application.yml` 또는 환경변수로 제공합니다. 개인키는 테스트용 지갑만 사용하세요. 절대 운영/실지갑 키를 사용하지 마세요.

정상 연결 확인

```powershell
Invoke-RestMethod -Uri "$BASE_URL/evm/wallet"
```

결과

```powershell
mode       : rpc
chainId    : 11155111
rpc        : https://ethereum-sepolia-rpc.publicnode.com
address    : 0x740161186057d3a948a1c16f1978937dca269070
balanceWei : 1587757348527098995
balanceEth : 1.587757348527098995
```

---

## 5) 실습 1 — 멱등성 + 초기 Attempt 생성

### 5-1. Withdrawal 생성

```powershell
$from = (Invoke-RestMethod -Uri "$BASE_URL/evm/wallet").address
$to   = "0x1111111111111111111111111111111111111111"
$idemp = "idem-lab1-1"
$w = Invoke-RestMethod -Method POST `
-Uri "$BASE_URL/withdrawals" `
-Headers @{ "Content-Type"="application/json"; "Idempotency-Key"=$idemp } `
-Body (@{
chainType   = "EVM"
fromAddress = $from
toAddress   = $to
asset       = "ETH"
amount      = 0.001  # eth
} | ConvertTo-Json)
$w

```

예시 결과

```powershell
id             : 4d540b1e-281e-43d9-87b6-992042214893
idempotencyKey : idem-lab1-1
fromAddress    : 0x740161186057d3a948a1c16f1978937dca269070
toAddress      : 0x1111111111111111111111111111111111111111
asset          : ETH
amount         : 1000000000000
status         : W6_BROADCASTED
createdAt      : 2026-02-21T16:31:17.649081Z
updatedAt      : 2026-02-21T16:31:18.901122Z
chainType      : EVM
```

### 5-2. 같은 키 + 같은 바디 재요청

```powershell
$w = Invoke-RestMethod -Method POST `
-Uri "$BASE_URL/withdrawals" `
-Headers @{ "Content-Type"="application/json"; "Idempotency-Key"=$idemp } `
-Body (@{
chainType   = "EVM"
fromAddress = $from
toAddress   = $to
asset       = "ETH"
amount      = 0.001
} | ConvertTo-Json)
$w
```

기대 결과

- 첫 요청과 **같은 withdrawal id** 반환

### 5-3. Attempt 목록 확인

```powershell
Invoke-RestMethod -Method GET `
  -Uri "$BASE_URL/withdrawals/$($w.id)/attempts"
```

응답에는 `attemptCount`와 `attempts` 배열이 함께 내려옵니다.
PowerShell에서는 아래 한 줄만으로도 시도 수와 상세 목록을 바로 볼 수 있습니다.

```powershell
Invoke-RestMethod -Method GET `
  -Uri "$BASE_URL/withdrawals/$($w.id)/attempts"
```

기대 결과

```powershell
id                   : 4a7b8db4-17dd-430c-8095-ddbb850c75b5
withdrawalId         : 4d540b1e-281e-43d9-87b6-992042214893
attemptNo            : 1
fromAddress          : 0x740161186057d3a948a1c16f1978937dca269070
nonce                : 41
attemptGroupKey      : 0x740161186057d3a948a1c16f1978937dca269070:41
txHash               : 0x180b8e21f3dc5eddef1379b523cc69eba7047e6a15941e45a74d3ff3f09be16a
maxPriorityFeePerGas :
maxFeePerGas         :
status               : BROADCASTED
canonical            : True
exceptionType        :
exceptionDetail      :
createdAt            : 2026-02-21T16:31:18.100507Z
```

> 참고: `attemptCount = 0`이면 해당 withdrawal은 아직 브로드캐스트 시도가 없다는 뜻입니다
> (예: policy rejected된 건).

### 5-4. 같은 키 + 다른 바디(충돌)

```powershell
$from = (Invoke-RestMethod -Uri "$BASE_URL/evm/wallet").address
$to   = "0x1111111111111111111111111111111111111111"
$idemp = "idem-lab1-1"
$w = Invoke-RestMethod -Method POST `
-Uri "$BASE_URL/withdrawals" `
-Headers @{ "Content-Type"="application/json"; "Idempotency-Key"=$idemp } `
-Body (@{
chainType   = "EVM"
fromAddress = $from
toAddress   = $to
asset       = "ETH"
amount      = 0.0001  #수량 변경
} | ConvertTo-Json)
$w

```

기대 결과

- HTTP 409
- 메시지: `same Idempotency-Key cannot be used with a different request body`

---

## 6) 실습 2 — Retry / Replace / Included 수렴 (실체인/RPC)

### 6-1. 테스트용 Withdrawal 생성

```powershell
$idemp = "idem-lab2-1"
$w = Invoke-RestMethod -Method POST `
-Uri "$BASE_URL/withdrawals" `
-Headers @{ "Content-Type"="application/json"; "Idempotency-Key"=$idemp } `
-Body (@{
chainType   = "EVM"
fromAddress = $from
toAddress   = $to
asset       = "USDC"
amount      = 0.0001  # wei
} | ConvertTo-Json)
$w
```

### 6-2. retry 실행 (새 nonce로 재전송)

```powershell
Invoke-RestMethod -Method POST `
  -Uri "$BASE_URL/withdrawals/$($w.id)/retry"
```

```powershell
Invoke-RestMethod -Method GET `
  -Uri "$BASE_URL/withdrawals/$($w.id)/attempts"
```

기대 결과

- attempt 2개
- 이전 attempt: `canonical=false`, `status=FAILED_TIMEOUT`
- 최신 attempt: `canonical=true` (새 nonce로 broadcast)

### 6-3. replace 실행 (같은 nonce, fee bump)

```powershell
Invoke-RestMethod -Method POST `
  -Uri "$BASE_URL/withdrawals/$($w.id)/replace"
```

```powershell
Invoke-RestMethod -Method GET `
  -Uri "$BASE_URL/withdrawals/$($w.id)/attempts"
```

기대 결과

- attempt 3개
- 이전 canonical attempt가 `REPLACED`, `canonical=false`
- 최신 attempt `canonical=true` (같은 nonce로 교체 전송)
- 단, retry 이후 이미 해당 nonce가 블록에 포함된 상태라면 replace는 `nonce too low` 상황이므로 안내 메시지와 함께 거절됩니다. 이 경우 새 nonce를 사용하는 retry를 다시 수행하세요.
- 네트워크 상태에 따라 retry 트랜잭션이 너무 빨리 블록체 포함되는 경우라면 아래와 같이 두개의 트랜잭션을 연달아 실행해 보세요.

```powershell
Invoke-RestMethod -Method POST `
  -Uri "$BASE_URL/withdrawals/$($w.id)/retry"
Invoke-RestMethod -Method POST `
  -Uri "$BASE_URL/withdrawals/$($w.id)/replace"
```

### 6-4. sync로 실제 포함 수렴 확인

```powershell
Invoke-RestMethod -Method POST `
  -Uri "$BASE_URL/withdrawals/{withdrawalId}/sync"
```

```powershell
Invoke-RestMethod -Method GET `
  -Uri "$BASE_URL/withdrawals/{withdrawalId}"
```

기대 결과

- receipt가 확인되면 Withdrawal 상태가 `W7_INCLUDED`로 전이
- canonical attempt가 `INCLUDED`로 전이되고, 성공 receipt(`0x1`)이면 `SUCCESS`로 전이

### 6-5. Confirmation Tracker 실습 — 영수증(Receipt) 확인 후 Included 전이

설명: 실제 RPC를 통해 영수증(receipt)을 확인하는 백그라운드 트래커(Confirmation Tracker)가 실행될 때,
영수증이 확인되면 해당 canonical `TxAttempt`와 `Withdrawal`이 자동으로 `INCLUDED` 상태로 전이되는 흐름을 확인합니다.

- Confirmation Tracker는 주기적으로(또는 이벤트 기반) `eth_getTransactionReceipt(txHash)`를 호출해 receipt를 확인합니다.

절차

1. Withdrawal 생성 및 브로드캐스트 (6-1 ~ 6-3 참고)
2. 브로드캐스트된 `txHash`를 확보
3. Confirmation Tracker가 receipt를 발견하면 내부에서 `attempt.status -> INCLUDED`, `withdrawal.status -> W7_INCLUDED`로 전이

확인 방법

```powershell
Invoke-RestMethod -Method GET `
  -Uri "$BASE_URL/withdrawals/{withdrawalId}"
```

- `status`가 `W7_INCLUDED`인지 확인
- canonical attempt의 상태가 `INCLUDED`인지 확인

디버깅 / 수동 강제 확인

- 즉시 영수증 조회 및 수동 동기화: `POST /withdrawals/{withdrawalId}/sync` 호출
- txHash에 대해 체인에서 수동으로 receipt를 확인하려면 `GET /evm/tx/{txHash}/wait` 사용

운영 주의사항

- Confirmation Tracker는 재시도/replace에 의해 바뀐 canonical에 대해 올바른 txHash를 추적해야 합니다.
- 다중 체인/네트워크 지연을 고려해 poll 간격과 타임아웃을 적절히 설정하세요.

---

## 7) 실습 3 — ChainAdapter 2종 검증

### 7-1. EVM adapter (Sepolia RPC 실제 호출)



서버를 재시작한 뒤 아래를 호출하세요.

RPC 데모 한 줄 플로우

1. `GET /evm/wallet`로 송신 지갑 주소/잔고/체인 정보를 확인
2. 위 주소에 Sepolia faucet으로 테스트 ETH 입금
3. `POST /adapter-demo/broadcast/evm`로 트랜잭션 브로드캐스트 후 `txHash` 확보
4. `GET /evm/tx/{txHash}/wait?timeoutMs=30000&pollMs=1500`로 포함(영수증) 확인
5. 필요 시 Etherscan에서 `txHash` 검색해 보조 검증

`GET /evm/wallet` 응답 예시:

```json
{
  "mode": "rpc",
  "chainId": 11155111,
  "rpc": "https://ethereum-sepolia-rpc.publicnode.com",
  "address": "0x...",
  "balanceWei": "12300000000000000",
  "balanceEth": "0.0123"
}
```

`GET /evm/tx/{txHash}` 응답 예시(미포함):

```json
{
  "txHash": "0x...",
  "seen": true,
  "receipt": null
}
```

`GET /evm/tx/{txHash}/wait` 응답 예시(타임아웃):

```json
{
  "txHash": "0x...",
  "receipt": null,
  "timeout": true
}
```

```powershell
Invoke-RestMethod -Method POST `
  -Uri "$BASE_URL/adapter-demo/broadcast/evm" `
  -Headers @{ "Content-Type"="application/json" } `
  -Body '{ "from":"ignored", "to":"0x1111111111111111111111111111111111111111", "asset":"ETH", "amount":1 }'
```

기대 결과

- `accepted = true`
- 설정한 `custody.evm.chain-id`와 RPC의 `eth_chainId`가 일치하는지 확인
- nonce를 생략하면 서버가 현재 `eth_getTransactionCount(..., "pending")` 값을 조회해 사용
- 고정 가스값 사용: `gasLimit=21000`, `maxPriorityFee=2 gwei`, `maxFee=20 gwei`
- `txHash`는 실제 EVM 해시(예: `0x...`)

### 7-2. BFT adapter

```powershell
Invoke-RestMethod -Method POST `
  -Uri "$BASE_URL/adapter-demo/broadcast/bft" `
  -Headers @{ "Content-Type"="application/json" } `
  -Body '{ "from":"a", "to":"b", "asset":"TOKEN", "amount":10, "nonce":1 }'
```

기대 결과

- `accepted = true`
- `txHash` prefix: `BFT_`

---

## 8) 실습 4 — Policy + Audit

기본 정책 (`src/main/resources/application.yaml`)

- `policy.max-amount: 1000`
- `policy.whitelist-to-addresses: 0xto,0xtrusted`

### 8-1. 허용 케이스

```powershell
Invoke-RestMethod -Method POST `
  -Uri "$BASE_URL/withdrawals" `
  -Headers @{ "Content-Type"="application/json"; "Idempotency-Key"="idem-lab4-allow-1" } `
  -Body '{ "chainType":"EVM", "fromAddress":"0xfrom", "toAddress":"0xto", "asset":"USDC", "amount":100 }'
```

기대 결과

- `status = W4_SIGNING`

### 8-2. 화이트리스트 거절 케이스

```powershell
Invoke-RestMethod -Method POST `
  -Uri "$BASE_URL/withdrawals" `
  -Headers @{ "Content-Type"="application/json"; "Idempotency-Key"="idem-lab4-reject-whitelist-1" } `
  -Body '{ "chainType":"EVM", "fromAddress":"0xfrom", "toAddress":"0xnot-allowed", "asset":"USDC", "amount":100 }'
```

기대 결과

- `status = W0_POLICY_REJECTED`
- 이후 audit 조회:

```powershell
Invoke-RestMethod -Method GET `
  -Uri "$BASE_URL/withdrawals/{withdrawalId}/policy-audits"
```

예상 reason

- `TO_ADDRESS_NOT_WHITELISTED: 0xnot-allowed`

### 8-3. 금액 초과 거절 케이스

```powershell
Invoke-RestMethod -Method POST `
  -Uri "$BASE_URL/withdrawals" `
  -Headers @{ "Content-Type"="application/json"; "Idempotency-Key"="idem-lab4-reject-amount-1" } `
  -Body '{ "chainType":"EVM", "fromAddress":"0xfrom", "toAddress":"0xto", "asset":"USDC", "amount":1001 }'
```

기대 결과

- `status = W0_POLICY_REJECTED`
- audit reason:
  - `AMOUNT_LIMIT_EXCEEDED: max=1000, requested=1001`

---

## 9) 실습 5 (심화) — 동시성 멱등성 점검

이 실습은 “실습 경험 개선”을 위한 확장 시나리오입니다.

### 목표

- 동시 요청에서도 동일 `Idempotency-Key`가 1개의 Withdrawal만 생성하는지 확인
- 결과적으로 attempt가 불필요하게 증가하지 않는지 확인

### 9-1. 동시 요청 실행 (PowerShell)

```powershell
1..5 | ForEach-Object {
  Start-Job -ScriptBlock {
    param($baseUrl)
    Invoke-RestMethod -Method POST `
      -Uri "$baseUrl/withdrawals" `
      -Headers @{ "Content-Type"="application/json"; "Idempotency-Key"="idem-concurrency-1" } `
      -Body '{ "chainType":"EVM", "fromAddress":"0xfrom-concurrent", "toAddress":"0xto", "asset":"USDC", "amount":77 }'
  } -ArgumentList $BASE_URL
} | Receive-Job -Wait -AutoRemoveJob
```

### 9-2. 확인 포인트

- 반환된 `id`가 모두 동일한지
- `/withdrawals/{id}/attempts`에서 초기 attempt가 1개로 유지되는지

---

## 10) 자동 테스트

프로젝트 루트(`custody`)에서 실행:

```bash
./gradlew test
```

실습 시나리오 통합 테스트만:

```bash
./gradlew test --tests "lab.custody.orchestration.LabScenariosIntegrationTest"
```

Withdrawal API 통합 테스트만:

```bash
./gradlew test --tests "lab.custody.orchestration.WithdrawalControllerIntegrationTest"
```

---

목 테스트 (Mock Tests)

설명: 로컬 개발/CI에서 체인 RPC를 직접 호출하지 않고 동작을 확인하려면 목 모드로 테스트를 실행하세요. 목 테스트는 실제 네트워크 연결 없이 내부 mock adapter/fixture를 사용합니다.

방법 (PowerShell 예)

```powershell
# 1) 간단히: 모드만 mock으로 설정
$env:CUSTODY_CHAIN_MODE = "mock"
./gradlew test --tests "**IntegrationTest*"

# 또는 프로파일을 사용한다면(프로젝트에 설정된 경우)
$env:SPRING_PROFILES_ACTIVE = "labs-mock"
./gradlew test
```

주의: 프로젝트의 프로파일/설정은 환경에 따라 다를 수 있습니다. 목 테스트는 네트워크 불안정성의 영향을 받지 않으므로 로컬 개발과 CI에서 빠른 확인용으로 사용하세요.

---

## 11) 트러블슈팅

### Q1. `invalid chainType: unknown`

- `EVM` 또는 `BFT`로 입력했는지 확인

### Q2. PowerShell에서 JSON 파싱 오류

- 작은따옴표/큰따옴표가 섞이면 깨질 수 있음
- 가장 안전한 방법: JSON 파일 저장 후 `-InFile` 또는 here-string 사용

### Q3. 같은 Idempotency-Key인데 409이 발생

- 같은 키에 바디가 달라졌기 때문
- 키를 바꾸거나 동일 바디로 재시도 필요

### Q4. 정책 거절인데 이유를 모르겠음

- `/withdrawals/{id}/policy-audits` 조회해서 reason 확인

---

## 12) 주요 API 요약

- `POST /withdrawals` (Header: `Idempotency-Key`)
- `GET /withdrawals/{id}`
- `GET /withdrawals/{id}/attempts`
- `GET /withdrawals/{id}/policy-audits`
- `POST /withdrawals/{id}/retry`
- `POST /withdrawals/{id}/replace`
- `POST /withdrawals/{id}/sync`
- `POST /adapter-demo/broadcast/{evm|bft}`
- `GET /evm/wallet` (rpc 모드에서만 활성화)
- `GET /evm/tx/{txHash}` (rpc 모드에서만 활성화)
- `GET /evm/tx/{txHash}/wait?timeoutMs=30000&pollMs=1500` (rpc 모드에서만 활성화)

---

## 13) 다음 확장 과제 (권장)

- Policy 다중 룰(우선순위/다중 사유)
- Adapter timeout/partial failure 시나리오 확장
- 상태 전이 불변식 테스트(canonical은 항상 1개)
- 요청 추적용 correlation id + 로그 표준화

---

## 14) 실습별 핵심 코드

### 실습 1 — Withdrawal / TxAttempt 분리 + Idempotency

핵심 코드 (`WithdrawalService#createOrGet`)

```java
return withdrawalRepository.findByIdempotencyKey(idempotencyKey)
        .map(existing -> validateIdempotentRequest(existing, chainType, req))
        .orElseGet(() -> {
            Withdrawal w = Withdrawal.requested(...);
            Withdrawal saved = withdrawalRepository.save(w);

            PolicyDecision decision = policyEngine.evaluate(req);
            policyAuditLogRepository.save(PolicyAuditLog.of(saved.getId(), decision.allowed(), decision.reason()));

            if (!decision.allowed()) {
                saved.transitionTo(WithdrawalStatus.W0_POLICY_REJECTED);
                return withdrawalRepository.save(saved);
            }

            saved.transitionTo(WithdrawalStatus.W1_POLICY_CHECKED);
            saved.transitionTo(WithdrawalStatus.W4_SIGNING);
            Withdrawal policyPassed = withdrawalRepository.save(saved);

            long nonce = nonceAllocator.reserve(req.fromAddress());
            attemptService.createAttempt(policyPassed.getId(), req.fromAddress(), nonce);

            return policyPassed;
        });
```

- **업무 단위(Withdrawal)** 와 **체인 시도 단위(TxAttempt)** 를 분리해, 재시도/교체가 발생해도 원본 업무 객체는 1개를 유지합니다.
- 같은 `Idempotency-Key`가 들어오면 재생성이 아니라 기존 건을 재사용합니다.
- 정책 통과 시 `W4_SIGNING`까지 전이하고, 그 시점에 첫 `TxAttempt`를 1개 생성합니다.

---

### 실습 2 — Retry / Replace / Included 수렴

핵심 코드 (`RetryReplaceService#retry`, `#replace`, `#sync`)

```java
// retry: 기존 canonical을 timeout 처리하고 새 nonce로 재브로드캐스트
canonical.transitionTo(TxAttemptStatus.FAILED_TIMEOUT);
canonical.setCanonical(false);
long nonce = rpcAdapter.getPendingNonce(rpcAdapter.getSenderAddress()).longValue();
TxAttempt retried = attemptService.createAttempt(withdrawalId, canonical.getFromAddress(), nonce);
broadcast(w, retried);

// replace: 기존 canonical을 REPLACED 처리하고 같은 nonce로 fee bump 브로드캐스트
canonical.transitionTo(TxAttemptStatus.REPLACED);
canonical.markException(AttemptExceptionType.REPLACED, "fee bump replacement");
canonical.setCanonical(false);
TxAttempt replaced = attemptService.createAttempt(withdrawalId, canonical.getFromAddress(), canonical.getNonce());
replaced.setFeeParams(4_000_000_000L, 40_000_000_000L);
broadcast(w, replaced);

// sync: 실제 receipt를 조회해 INCLUDED/SUCCESS/FAILED로 수렴
var receiptOpt = rpcAdapter.getReceipt(canonical.getTxHash());
```

- fake 주입이 아니라 **실제 브로드캐스트와 receipt 조회 결과**로 상태가 바뀝니다.
- `retry`는 새 nonce, `replace`는 같은 nonce(fee bump)라는 규칙으로 canonical attempt를 교체합니다.
- 최종 포함 여부는 `sync`에서 실제 체인 receipt로 판정합니다.

---

### 실습 3 — Chain Adapter + Sepolia RPC

핵심 코드 (`EvmMockAdapter#broadcast`)

```java
String chainId = rpcCall("eth_chainId", List.of()).asText();
if (!SEPOLIA_CHAIN_ID_HEX.equalsIgnoreCase(chainId)) {
    throw new IllegalStateException("Connected RPC is not Sepolia...");
}

Credentials credentials = Credentials.create(senderPrivateKey);
BigInteger nonce = hexToBigInteger(rpcCall("eth_getTransactionCount", List.of(fromAddress, "pending")).asText());
BigInteger gasPrice = hexToBigInteger(rpcCall("eth_gasPrice", List.of()).asText());

RawTransaction rawTransaction = RawTransaction.createEtherTransaction(
        nonce, gasPrice, DEFAULT_GAS_LIMIT, command.to(), BigInteger.valueOf(command.amount())
);

byte[] signedMessage = TransactionEncoder.signMessage(rawTransaction, SEPOLIA_CHAIN_ID_DECIMAL.longValue(), credentials);
String txHash = rpcCall("eth_sendRawTransaction", List.of(Numeric.toHexString(signedMessage))).asText();
```

- 오케스트레이터는 `adapter.broadcast(command)`만 호출하고, 체인별 복잡성은 어댑터 내부로 숨깁니다.
- EVM 어댑터는 `chainId` 검증 → nonce/gas 조회 → 로컬 서명 → `eth_sendRawTransaction` 순으로 동작합니다.
- RPC URL이 없으면 mock hash를 반환하도록 설계되어 로컬 실습도 끊기지 않습니다.

---

### 실습 4 — Policy Engine + Audit

핵심 코드 (`PolicyEngine#evaluate`)

```java
if (req.amount() > maxAmount) {
    return PolicyDecision.reject("AMOUNT_LIMIT_EXCEEDED: max=" + maxAmount + ", requested=" + req.amount());
}

if (!toAddressWhitelist.isEmpty() && !toAddressWhitelist.contains(req.toAddress())) {
    return PolicyDecision.reject("TO_ADDRESS_NOT_WHITELISTED: " + req.toAddress());
}

return PolicyDecision.allow();
```

- 정책은 **허용/거절 + 이유(reason)** 를 함께 반환해야 운영 시 추적이 쉽습니다.
- `WithdrawalService`가 policy 결과를 `policy-audits`에 항상 남기므로, “왜 거절됐는지”를 API로 확인할 수 있습니다.
- 상태(`W0_POLICY_REJECTED`)와 감사로그(reason)를 함께 보게 하면 실무 감각이 빨리 올라옵니다.

---

### 실습 5 (심화) — 동시성 멱등성

핵심 코드 (`WithdrawalService#createOrGet`, `@Transactional`)

```java
@Transactional
public Withdrawal createOrGet(String idempotencyKey, CreateWithdrawalRequest req) {
    return withdrawalRepository.findByIdempotencyKey(idempotencyKey)
            .map(existing -> validateIdempotentRequest(existing, chainType, req))
            .orElseGet(() -> { ... create withdrawal once ... });
}
```

- 동시 요청의 핵심은 “같은 키로 정말 1건만 생기느냐”입니다.
- 그래서 검증 포인트가 `id 동일성` + `attempt 1개 유지`입니다.
- 실무에서는 DB 유니크 제약(`idempotency_key`)까지 함께 두면 안전성이 더 높아집니다.


## 보안/안전 가드

---

### Confirmation Tracker — Receipt Polling 및 상태 전이 (핵심 코드)

아래는 Confirmation Tracker의 간단한 구현 예시입니다. 주기적으로(또는 이벤트 기반으로) 체인에서 `eth_getTransactionReceipt(txHash)`를 호출해
영수증을 발견하면 해당 `TxAttempt`와 `Withdrawal`의 상태를 전이합니다. 핵심 원칙은 `txHash`로 시점을 정확히 확인하고,
canonical이 변경됐을 경우에도 올바른 attempt를 찾아 업데이트하는 것입니다.

```java
@Component
public class ConfirmationTracker {
  private final TxAttemptRepository attemptRepo;
  private final WithdrawalRepository withdrawalRepo;
  private final RpcAdapter rpcAdapter;

  public ConfirmationTracker(TxAttemptRepository attemptRepo,
                 WithdrawalRepository withdrawalRepo,
                 RpcAdapter rpcAdapter) {
    this.attemptRepo = attemptRepo;
    this.withdrawalRepo = withdrawalRepo;
    this.rpcAdapter = rpcAdapter;
  }

  @Scheduled(fixedDelayString = "${confirmation.tracker.poll-ms:5000}")
  @Transactional
  public void pollPendingReceipts() {
    List<TxAttempt> pending = attemptRepo.findCanonicalPendingAttempts(); // BROADCASTED / PENDING
    for (TxAttempt a : pending) {
      String txHash = a.getTxHash();
      if (txHash == null) continue;

      var receiptOpt = rpcAdapter.getReceipt(txHash);
      if (receiptOpt.isPresent()) {
        var receipt = receiptOpt.get();
        a.setStatus(TxAttemptStatus.INCLUDED);
        a.setReceiptStatus(receipt.getStatusHex());
        attemptRepo.save(a);

        Withdrawal w = withdrawalRepo.findById(a.getWithdrawalId()).orElseThrow();
        w.transitionTo(WithdrawalStatus.W7_INCLUDED);
        if ("0x1".equals(receipt.getStatusHex())) {
          w.setResult(WithdrawalResult.SUCCESS);
        } else {
          w.setResult(WithdrawalResult.FAILED);
        }
        withdrawalRepo.save(w);
      }
    }
  }
}
```

- `findCanonicalPendingAttempts()`는 현재 canonical이며 포함되지 않은 attempt만 반환해야 합니다.
- 트래커는 `txHash`가 canonical에 남아있는지, 또는 이미 replace/retry로 canonical이 바뀌었는지를 고려해 영수증을 적용해야 합니다.
- 수작업 동기화(`POST /withdrawals/{id}/sync`)는 즉시 영수증을 확인해 동일한 상태 전이를 수행합니다.


- `custody.evm.chain-id=1`(mainnet)은 부팅 시 차단됩니다.
- `CUSTODY_CHAIN_MODE`가 `rpc`일 경우 RPC 관련 설정이 올바르게 구성되지 않으면 부팅이 실패할 수 있습니다 (RPC URL, 체인 ID, 개인키 등).
- 개인키는 절대 커밋하지 마세요.
