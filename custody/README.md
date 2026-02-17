# custody-architecture

수탁형 지갑 출금 시스템을 **실습 중심**으로 이해하는 프로젝트입니다.
핵심은 아래 한 문장입니다.

> 출금 시스템은 실패를 제거하는 시스템이 아니라,
> 실패를 분류하고 canonical attempt를 재선정해
> 최종 상태로 수렴시키는 시스템이다.

---

## 0) 이 README의 목표

이 문서는 “코드를 읽지 않고도” 다음을 할 수 있게 설계했습니다.

- 서버 실행
- 실습 1~4 수동 점검
- 실습 5(심화: 관찰성/동시성) 수행
- 자동 테스트 실행 및 실패 시 빠른 원인 파악

---

## 1) 실습 전체 지도

### 실습 1 — Withdrawal / TxAttempt 분리 + 멱등성

- 업무 단위인 `Withdrawal`은 1개로 유지
- 체인 시도 단위인 `TxAttempt`는 실패/재시도에 따라 누적
- `Idempotency-Key`로 중복 요청 방지

### 실습 2 — Retry / Replace 시뮬레이션

- 실패(`FAIL_SYSTEM`) 시 새로운 attempt 생성
- 교체(`REPLACED`) 시 canonical attempt 전환
- 성공(`SUCCESS`)까지 수렴하는 흐름 확인

### 실습 3 — Chain Adapter + Sepolia RPC 연동

- EVM adapter는 Sepolia RPC에 실제 서명 트랜잭션(`eth_sendRawTransaction`)을 전송
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
- Sepolia RPC URL 환경변수: `SEPOLIA_RPC_URL` (기본값: `https://ethereum-sepolia-rpc.publicnode.com`)
- 송신 지갑 개인키 환경변수: `SEPOLIA_SENDER_PRIVATE_KEY`

H2 Console

- URL: `http://localhost:8080/h2`
- JDBC URL: `jdbc:h2:mem:testdb`
- username: `sa`
- password: 빈 값

---

## 3) 빠른 시작

```bash
cd custody
chmod +x gradlew
./gradlew bootRun
```

서버가 뜨면 새 터미널에서 아래 API를 호출하세요.

---

## 4) 실습용 공통 변수

### PowerShell

```powershell
$BASE_URL = "http://localhost:8080"
```

---

## 5) 실습 1 — 멱등성 + 초기 Attempt 생성

### 5-1. Withdrawal 생성

```powershell
Invoke-RestMethod -Method POST `
  -Uri "$BASE_URL/withdrawals" `
  -Headers @{ "Content-Type"="application/json"; "Idempotency-Key"="idem-lab1-1" } `
  -Body '{ "chainType":"EVM", "fromAddress":"0xfrom-lab1", "toAddress":"0xto", "asset":"USDC", "amount":100 }'
```

기대 결과

- HTTP 200
- `status = W4_SIGNING`
- 응답의 `id` 저장

### 5-2. 같은 키 + 같은 바디 재요청

```powershell
Invoke-RestMethod -Method POST `
  -Uri "$BASE_URL/withdrawals" `
  -Headers @{ "Content-Type"="application/json"; "Idempotency-Key"="idem-lab1-1" } `
  -Body '{ "chainType":"EVM", "fromAddress":"0xfrom-lab1", "toAddress":"0xto", "asset":"USDC", "amount":100 }'
```

기대 결과

- 첫 요청과 **같은 withdrawal id** 반환

### 5-3. Attempt 목록 확인

```powershell
Invoke-RestMethod -Method GET `
  -Uri "$BASE_URL/withdrawals/{withdrawalId}/attempts"
```

기대 결과

- length = 1
- `attemptNo = 1`
- `canonical = true`

### 5-4. 같은 키 + 다른 바디(충돌)

```powershell
Invoke-RestMethod -Method POST `
  -Uri "$BASE_URL/withdrawals" `
  -Headers @{ "Content-Type"="application/json"; "Idempotency-Key"="idem-lab1-1" } `
  -Body '{ "chainType":"BFT", "fromAddress":"0xfrom-lab1", "toAddress":"0xto", "asset":"USDC", "amount":100 }'
```

기대 결과

- HTTP 409
- 메시지: `same Idempotency-Key cannot be used with a different request body`

---

## 6) 실습 2 — Retry / Replace / Included 수렴

### 6-1. 테스트용 Withdrawal 생성

```powershell
Invoke-RestMethod -Method POST `
  -Uri "$BASE_URL/withdrawals" `
  -Headers @{ "Content-Type"="application/json"; "Idempotency-Key"="idem-lab2-1" } `
  -Body '{ "chainType":"EVM", "fromAddress":"0xfrom-lab2", "toAddress":"0xto", "asset":"USDC", "amount":50 }'
```

응답의 `id`를 `{withdrawalId}`로 사용합니다.

### 6-2. FAIL_SYSTEM 주입 후 broadcast

```powershell
Invoke-RestMethod -Method POST `
  -Uri "$BASE_URL/sim/withdrawals/{withdrawalId}/next-outcome/FAIL_SYSTEM"
```

```powershell
Invoke-RestMethod -Method POST `
  -Uri "$BASE_URL/sim/withdrawals/{withdrawalId}/broadcast"
```

```powershell
Invoke-RestMethod -Method GET `
  -Uri "$BASE_URL/withdrawals/{withdrawalId}/attempts"
```

기대 결과

- attempt 2개
- 이전 attempt: `canonical=false`, `exceptionType=FAILED_SYSTEM`
- 최신 attempt: `canonical=true`

### 6-3. REPLACED 주입 후 broadcast

```powershell
Invoke-RestMethod -Method POST `
  -Uri "$BASE_URL/sim/withdrawals/{withdrawalId}/next-outcome/REPLACED"
```

```powershell
Invoke-RestMethod -Method POST `
  -Uri "$BASE_URL/sim/withdrawals/{withdrawalId}/broadcast"
```

```powershell
Invoke-RestMethod -Method GET `
  -Uri "$BASE_URL/withdrawals/{withdrawalId}/attempts"
```

기대 결과

- attempt 3개
- 이전 canonical attempt가 `REPLACED`, `canonical=false`
- 최신 attempt `canonical=true`

### 6-4. SUCCESS 주입 후 broadcast

```powershell
Invoke-RestMethod -Method POST `
  -Uri "$BASE_URL/sim/withdrawals/{withdrawalId}/next-outcome/SUCCESS"
```

```powershell
Invoke-RestMethod -Method POST `
  -Uri "$BASE_URL/sim/withdrawals/{withdrawalId}/broadcast"
```

```powershell
Invoke-RestMethod -Method GET `
  -Uri "$BASE_URL/withdrawals/{withdrawalId}"
```

기대 결과

- Withdrawal 최종 상태: `W7_INCLUDED`
- 최신 canonical attempt: `A4_INCLUDED`

---

## 7) 실습 3 — ChainAdapter 2종 검증

### 7-1. EVM adapter (Sepolia RPC 실제 호출)

먼저 RPC URL/송신 지갑 개인키를 설정합니다.

```powershell
$env:SEPOLIA_RPC_URL = "https://ethereum-sepolia-rpc.publicnode.com"
$env:SEPOLIA_SENDER_PRIVATE_KEY = "<YOUR_SEPOLIA_PRIVATE_KEY>"
```

> 주의: 개인키는 테스트용 지갑만 사용하세요. 절대 운영/실지갑 키를 사용하지 마세요.

서버를 재시작한 뒤 아래를 호출하세요.

```powershell
Invoke-RestMethod -Method POST `
  -Uri "$BASE_URL/adapter-demo/broadcast/evm" `
  -Headers @{ "Content-Type"="application/json" } `
  -Body '{ "from":"ignored", "to":"0x1111111111111111111111111111111111111111", "asset":"ETH", "amount":1, "nonce":0 }'
```

기대 결과

- `accepted = true`
- Sepolia RPC(`eth_chainId`) 확인 후, 로컬 서명 트랜잭션을 `eth_sendRawTransaction`으로 전송
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
- `POST /sim/withdrawals/{id}/next-outcome/{FAIL_SYSTEM|REPLACED|SUCCESS}`
- `POST /sim/withdrawals/{id}/broadcast`
- `POST /adapter-demo/broadcast/{evm|bft}`

---

## 13) 다음 확장 과제 (권장)

- Policy 다중 룰(우선순위/다중 사유)
- Adapter timeout/partial failure 시나리오 확장
- 상태 전이 불변식 테스트(canonical은 항상 1개)
- 요청 추적용 correlation id + 로그 표준화

---

## 14) 실습별 핵심 코드 + 수강생 설명 포인트

아래는 강의에서 그대로 보여주기 좋은 **핵심 코드**와 설명 포인트입니다.

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

수강생 설명 포인트

- **업무 단위(Withdrawal)** 와 **체인 시도 단위(TxAttempt)** 를 분리해, 재시도/교체가 발생해도 원본 업무 객체는 1개를 유지합니다.
- 같은 `Idempotency-Key`가 들어오면 재생성이 아니라 기존 건을 재사용합니다.
- 정책 통과 시 `W4_SIGNING`까지 전이하고, 그 시점에 첫 `TxAttempt`를 1개 생성합니다.

---

### 실습 2 — Retry / Replace / Included 수렴

핵심 코드 (`RetryReplaceService#simulateBroadcast`)

```java
if (outcome == NextOutcome.FAIL_SYSTEM) {
    canonical.markException(AttemptExceptionType.FAILED_SYSTEM, "simulated failure");
    canonical.setCanonical(false);

    long sameNonce = canonical.getNonce();
    TxAttempt newAttempt = attemptService.createAttempt(withdrawalId, canonical.getFromAddress(), sameNonce);
    newAttempt.setCanonical(true);

} else if (outcome == NextOutcome.REPLACED) {
    canonical.markException(AttemptExceptionType.REPLACED, "simulated replace");
    canonical.setCanonical(false);

    long sameNonce = canonical.getNonce();
    TxAttempt newAttempt = attemptService.createAttempt(withdrawalId, canonical.getFromAddress(), sameNonce);
    newAttempt.setCanonical(true);

} else {
    canonical.transitionTo(TxAttemptStatus.A4_INCLUDED);
    w.transitionTo(WithdrawalStatus.W7_INCLUDED);
}
```

수강생 설명 포인트

- 실패를 없애는 게 아니라 **실패를 기록하고 canonical attempt를 갈아끼우는 모델**입니다.
- `FAIL_SYSTEM`, `REPLACED` 둘 다 기존 canonical을 내리고 새 attempt를 canonical로 승격합니다.
- `SUCCESS`에서만 `Withdrawal=W7_INCLUDED`로 수렴합니다.

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

수강생 설명 포인트

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

수강생 설명 포인트

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

수강생 설명 포인트

- 동시 요청의 핵심은 “같은 키로 정말 1건만 생기느냐”입니다.
- 그래서 검증 포인트가 `id 동일성` + `attempt 1개 유지`입니다.
- 실무에서는 DB 유니크 제약(`idempotency_key`)까지 함께 두면 안전성이 더 높아집니다.
