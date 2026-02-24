# custody-architecture

## 0) 이 프로젝트의 목표
이 프로젝트는 출금(Withdrawal) 오케스트레이션을 중심으로, 커스터디 시스템의 핵심 흐름(정책 검증, 멱등성, 시도/재시도/대체, 체인 어댑터 연동, 감사 로그)을 실습하고 검증하기 위한 학습용 아키텍처 프로젝트입니다.

코드를 모두 읽지 않아도 API 호출과 시나리오 실행만으로 위 흐름을 단계별로 확인할 수 있도록 구성했습니다.

주의: 기본 설정은 목(Mock) 모드입니다. 별도 RPC 설정을 하지 않으면(예: `CUSTODY_CHAIN_MODE`가 `mock`일 때) 서버는 목 어댑터를 사용해 네트워크 없이 동작합니다. 실제 체인 RPC를 사용하려면 `CUSTODY_CHAIN_MODE`를 `rpc`로 설정하고, RPC 관련 세부 설정(RPC URL, 체인 ID, 개인키 등)을 애플리케이션 설정(`application.yml` 또는 환경변수)에서 구성하세요.  

---

## 1) 실습 전체 지도

### 실습 1 — Policy Engine + Audit

- 금액 제한
- 수신 주소 화이트리스트
- 허용/거절 근거를 감사 로그(`policy-audits`)로 확인

### 실습 2 — Withdrawal + 멱등성

- `POST /withdrawals`는 DB 저장 + 실제 RPC 브로드캐스트까지 수행
- 동일 `Idempotency-Key` 재호출 시 재브로드캐스트 없이 기존 canonical `txHash` 유지
- `GET /evm/tx/{txHash}/wait`로 포함 여부 확인

### 실습 3 — Retry / Replace (실체인 규칙)

- `POST /withdrawals/{id}/retry`: 새 nonce로 새 attempt 브로드캐스트
- `POST /withdrawals/{id}/replace`: 같은 nonce fee bump로 canonical 교체
- `GET /withdrawals/{id}/attempts`로 누적/전환 확인

### 실습 4 — Chain Adapter + EVM RPC(Sepolia/Hoodi) 연동

- `custody.chain.mode=rpc`일 때 EVM adapter는 실제 RPC(Sepolia/Hoodi)에 EIP-1559 타입2 서명 트랜잭션(`eth_sendRawTransaction`)을 전송
- BFT adapter는 기존 mock 흐름 유지
- 오케스트레이터는 체인별 세부사항을 몰라도 동일한 호출 형태 유지

---

## 2) 환경 설정

- Java 21+
- Gradle Wrapper (`./gradlew`)

H2 Console

- URL: `http://localhost:8080/h2`
- JDBC URL: `jdbc:h2:mem:testdb`
- username: `sa`
- password: 빈 값

---

## 3) 시작

### 소스코드 다운로드

```bash
git clone https://github.com/coincraft12/custody-architecture.git
```

### 프로젝트 빌드

```bash
cd custody-architecture/custody
./gradlew build clean
```

### 실습용 공통 변수 입력

```powershell
$BASE_URL = "http://localhost:8080"
```

### RPC 연결

```powershell
$env:CUSTODY_CHAIN_MODE = "rpc"
$env:CUSTODY_EVM_RPC_URL = "https://ethereum-sepolia-rpc.publicnode.com"
$env:CUSTODY_EVM_CHAIN_ID = "11155111"
$env:CUSTODY_EVM_PRIVATE_KEY = "<YOUR_SEPOLIA_PRIVATE_KEY>"
```

주의: RPC 모드에서 사용하는 RPC URL, 체인 ID, 개인키 등의 세부 구성은 `application.yml` 또는 환경변수로 제공합니다. 개인키는 테스트용 지갑만 사용하세요. 절대 운영/실지갑 키를 사용하지 마세요.

### 서버 실행

```bash
./gradlew bootRun
```

※ 반드시 서버 실행 전 먼저 환경 변수를 입력하세요. 만약 서버 실행 중 환경 변수의 값이 변경되었다면 서버를 재실행해 주세요.

### RPC 정상 연결 확인

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

## 5) 실습 1 — Policy + Audit

기본 정책 (`src/main/resources/application.yaml`)

- `policy.max-amount: 0.1`
- `policy.whitelist-to-addresses: 0xto,0xtrusted, 0x1111111111111111111111111111111111111111`

### 5-1. 허용 케이스

```powershell
$from = "0x740161186057d3a948a1c16f1978937dca269070"
$to   = "0x1111111111111111111111111111111111111111"
$w    = Invoke-RestMethod -Method POST `
  -Uri "$BASE_URL/withdrawals" `
  -Headers @{ 
    "Content-Type"   = "application/json"
    "Idempotency-Key" = "idem-lab5-allow-1"
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

기대 결과

- `status = W5_BROADCASTED`

### 5-2. 화이트리스트 거절 케이스

```powershell
$from = "0x740161186057d3a948a1c16f1978937dca269070"
$to   = "0x2222222222222222222222222222222222222222"  # 화이트리스트에 없는 주소
$w = Invoke-RestMethod -Method POST `
  -Uri "$BASE_URL/withdrawals" `
  -Headers @{ 
    "Content-Type"    = "application/json"
    "Idempotency-Key" = "idem-lab4-reject-whitelist-1"
  } `
  -Body (@{
    chainType   = "EVM"
    fromAddress = $from
    toAddress   = $to
    asset       = "USDC"
    amount      = 0.00001
  } | ConvertTo-Json -Depth 10)
  $w

```

기대 결과

- `status = W0_POLICY_REJECTED`
- 이후 audit 조회:

```powershell
Invoke-RestMethod -Method GET `
  -Uri "$BASE_URL/withdrawals/$($w.id)/policy-audits"
```

예상 reason

- `TO_ADDRESS_NOT_WHITELISTED: 0xnot-allowed`

### 5-3. 금액 초과 거절 케이스

```powershell
$from = "0x740161186057d3a948a1c16f1978937dca269070"
$to   = "0x1111111111111111111111111111111111111111"

$w = Invoke-RestMethod -Method POST `
  -Uri "$BASE_URL/withdrawals" `
  -Headers @{ 
    "Content-Type"    = "application/json"
    "Idempotency-Key" = "idem-lab4-reject-amount-1"
  } `
  -Body (@{
    chainType   = "EVM"
    fromAddress = $from
    toAddress   = $to
    asset       = "USDC"
    amount      = 0.2   # 정책 한도 초과 값
  } | ConvertTo-Json -Depth 10)
  $w

```

기대 결과

- `status = W0_POLICY_REJECTED`
- audit reason:
  - `AMOUNT_LIMIT_EXCEEDED: max=0.1, requested=0.2`


## 6) 실습 2 — 멱등성 + 초기 Attempt 생성

실습 2 동시성 멱등성 점검

### 목표

- 동시 요청에서도 동일 `Idempotency-Key`가 1개의 Withdrawal만 생성하는지 확인
- 결과적으로 attempt가 불필요하게 증가하지 않는지 확인

### 6-1. Withdrawal 생성

```powershell
$from = (Invoke-RestMethod -Uri "$BASE_URL/evm/wallet").address
$to   = "0x1111111111111111111111111111111111111111"
$idemp = "idem-lab1-2"
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

### 6-2. 같은 키 + 같은 바디 재요청

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

### 6-3. Attempt 목록 확인

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

### 6-4. 같은 키 + 다른 바디(충돌)

```powershell
$from = (Invoke-RestMethod -Uri "$BASE_URL/evm/wallet").address
$to   = "0x1111111111111111111111111111111111111111"
$idemp = "idem-lab1-2"
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

### 6-5. 동시 요청 실행 (PowerShell)

```powershell
$from    = (Invoke-RestMethod -Uri "$BASE_URL/evm/wallet").address
$to      = "0x1111111111111111111111111111111111111111"
$idemp   = "idem-concurrency-2"   # 동일 멱등키 고정

$jobs = 1..5 | ForEach-Object {
  Start-Job -ScriptBlock {
    param($BASE_URL, $from, $to, $idemp)

    try {
      Invoke-RestMethod -Method POST `
        -Uri "$BASE_URL/withdrawals" `
        -Headers @{ "Content-Type"="application/json"; "Idempotency-Key"=$idemp } `
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

### 6-6. 확인 포인트

- 반환된 `id`가 모두 동일한지
- `/withdrawals/{id}/attempts`에서 초기 attempt가 1개로 유지되는지


---

## 7) 실습 3 — Retry / Replace / Included

### 7-1. Withdrawal 생성

```powershell
$idemp = "idem-lab3-1"
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

### 7-2. retry 실행 (새 nonce로 재전송)

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

### 7-3. replace 실행 (같은 nonce, fee bump)

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


### 7-4. Confirmation Tracker — 영수증(Receipt) 확인 후 Included 전이

설명: 실제 RPC를 통해 영수증(receipt)을 확인하는 백그라운드 트래커(Confirmation Tracker)가 실행될 때,
영수증이 확인되면 해당 canonical `TxAttempt`와 `Withdrawal`이 자동으로 `INCLUDED` 상태로 전이되는 흐름을 확인합니다.

- Confirmation Tracker는 주기적으로(또는 이벤트 기반) `eth_getTransactionReceipt(txHash)`를 호출해 receipt를 확인합니다.

절차

1. 브로드캐스트된 `txHash`를 확보
2. Confirmation Tracker가 receipt를 발견하면 내부에서 `attempt.status -> INCLUDED`, `withdrawal.status -> W7_INCLUDED`로 전이

확인 방법

```powershell
Invoke-RestMethod -Method GET `
  -Uri "$BASE_URL/withdrawals/$($w.id)"
```

- `status`가 `W7_INCLUDED`인지 확인
- canonical attempt의 상태가 `INCLUDED`인지 확인

디버깅 / 수동 강제 확인

- txHash에 대해 체인에서 수동으로 receipt를 확인하려면 `GET /evm/tx/{txHash}/wait` 사용

```powershell
Invoke-RestMethod -Method GET `
  -Uri "$BASE_URL/evm/tx/{txHash}/wait"
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

- 즉시 영수증 조회 및 수동 동기화: `POST /withdrawals/{withdrawalId}/sync` 호출

```powershell
Invoke-RestMethod -Method POST `
  -Uri "$BASE_URL/withdrawals/$($w.id)/sync"
```

운영 주의사항

- Confirmation Tracker는 재시도/replace에 의해 바뀐 canonical에 대해 올바른 txHash를 추적해야 합니다.
- 다중 체인/네트워크 지연을 고려해 poll 간격과 타임아웃을 적절히 설정하세요.

---

## 8) 실습 4 — ChainAdapter 2종 검증

### 8-1. EVM adapter (Sepolia RPC 실제 호출)


```powershell
$from = (Invoke-RestMethod -Uri "$BASE_URL/evm/wallet").address

Invoke-RestMethod -Method POST `
  -Uri "$BASE_URL/adapter-demo/broadcast/evm" `
  -Headers @{ "Content-Type"="application/json" } `
  -Body (@{
    from   = $from
    to     = "0x1111111111111111111111111111111111111111"
    asset  = "ETH"
    amount = 0.00001
  } | ConvertTo-Json)

```

기대 결과

- `accepted = true`
- 설정한 `custody.evm.chain-id`와 RPC의 `eth_chainId`가 일치하는지 확인
- nonce를 생략하면 서버가 현재 `eth_getTransactionCount(..., "pending")` 값을 조회해 사용
- 고정 가스값 사용: `gasLimit=21000`, `maxPriorityFee=2 gwei`, `maxFee=20 gwei`
- `txHash`는 실제 EVM 해시(예: `0x...`)

### 8-2. BFT adapter

```powershell
Invoke-RestMethod -Method POST `
  -Uri "$BASE_URL/adapter-demo/broadcast/bft" `
  -Headers @{ "Content-Type"="application/json" } `
  -Body (@{
    from   = "a"
    to     = "b"
    asset  = "TOKEN"
    amount = 0.00001
    nonce  = 1
  } | ConvertTo-Json)
```

기대 결과

- `accepted = true`
- `txHash` prefix: `BFT_`

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

## 9) 실습 5 — Correlation ID + 로그 표준화

목표

- 요청 단위 추적용 `X-Correlation-Id`를 수신/생성하고 응답 헤더로 반환
- 애플리케이션 로그에 `cid`를 공통 포맷으로 출력
- 컨트롤러/핵심 서비스 로그를 `event=... key=value` 형태로 표준화
- 에러 응답 body에서도 `correlationId`를 확인 가능하게 구성

### 9-1. 요청 헤더로 correlation id 전달 (정상 응답/로그 확인)

```powershell
$body = @{
  chainType   = "EVM"
  fromAddress = "0xfrom"
  toAddress   = "0x1111111111111111111111111111111111111111"
  asset       = "ETH"
  amount      = 0.01
} | ConvertTo-Json -Compress

Invoke-WebRequest `
  -Uri "$BASE_URL/withdrawals" `
  -Method POST `
  -Headers @{
    "Idempotency-Key"  = "lab-cid-001"
    "X-Correlation-Id" = "cid-lab-001"
  } `
  -ContentType "application/json" `
  -Body $body
```

기대 결과

- 응답 헤더에 `X-Correlation-Id: cid-lab-001`
- 애플리케이션 로그에 `[cid:cid-lab-001]`
- 컨트롤러/서비스 로그가 `event=withdrawal.create.request ...`, `event=withdrawal_service.create_or_get.start ...` 형태로 출력

### 9-2. correlation id 미전달 시 서버 자동 생성 확인

```powershell
Invoke-WebRequest `
  -Uri "$BASE_URL/withdrawals" `
  -Method POST `
  -Headers @{ "Idempotency-Key" = "lab-cid-002" } `
  -ContentType "application/json" `
  -Body $body
```

기대 결과

- 응답 헤더 `X-Correlation-Id`가 비어 있지 않음(서버 생성 UUID)
- 로그에도 동일한 `cid`가 출력

### 9-3. 에러 응답에서 correlationId 확인 (PowerShell JSON 파싱 오류 예시)

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

기대 결과

- `400 Bad Request`
- 응답 헤더에 `X-Correlation-Id: cid-bad-json-001`
- 응답 body에 `correlationId = "cid-bad-json-001"`

운영 팁

- `spring.jpa.show-sql: true` 상태에서는 `Hibernate:` SQL 출력이 섞여 보일 수 있습니다.
- `cid` 로그만 보고 싶다면 실습 중에는 `spring.jpa.show-sql: false`로 잠시 꺼두세요.

---

## 10) 주요 API 요약

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

## 11) 다음 확장 과제 (권장)

- Policy 다중 룰(우선순위/다중 사유)
- Adapter timeout/partial failure 시나리오 확장
- 상태 전이 불변식 테스트(canonical은 항상 1개)
- 비동기 작업(ConfirmationTracker)까지 correlation id(MDC) 전파
- JSON 구조화 로그 적용(검색/집계 친화적 포맷)
- 민감정보 마스킹 규칙 표준화(주소/키/원문 예외 메시지)

---

## 12) 실습별 핵심 코드

### 실습 1 — Policy Engine + Audit

핵심 코드 (`PolicyEngine#evaluate`, `WithdrawalService#createAndBroadcast`)

```java
PolicyDecision decision = policyEngine.evaluate(req);
policyAuditLogRepository.save(PolicyAuditLog.of(saved.getId(), decision.allowed(), decision.reason()));

if (!decision.allowed()) {
    saved.transitionTo(WithdrawalStatus.W0_POLICY_REJECTED);
    return withdrawalRepository.save(saved);
}
```

```java
if (req.amount().compareTo(maxAmountEth) > 0) {
    return PolicyDecision.reject("AMOUNT_LIMIT_EXCEEDED: max=" + maxAmountEth + ", requested=" + req.amount());
}

if (!toAddressWhitelist.isEmpty() && !toAddressWhitelist.contains(req.toAddress())) {
    return PolicyDecision.reject("TO_ADDRESS_NOT_WHITELISTED: " + req.toAddress());
}

return PolicyDecision.allow();
```

- 정책 판단은 `PolicyDecision(allow/reject + reason)`으로 반환하고, `WithdrawalService`가 결과를 `policy-audits`에 항상 기록합니다.
- 정책 거절 시 상태를 `W0_POLICY_REJECTED`로 전이하고 이후 브로드캐스트 단계로 진행하지 않습니다.
- 정책 룰은 현재 `금액 한도` + `수신 주소 화이트리스트`입니다.

---

### 실습 2 — Withdrawal / TxAttempt 분리 + 멱등성

핵심 코드 (`WithdrawalService#createOrGet`, `#createAndBroadcast`, `#validateIdempotentRequest`, `AttemptService#createAttempt`)

```java
public Withdrawal createOrGet(String idempotencyKey, CreateWithdrawalRequest req) {
    ChainType chainType = parseChainType(req.chainType());
    ReentrantLock lock = idempotencyLocks.computeIfAbsent(idempotencyKey, key -> new ReentrantLock());
    lock.lock();
    try {
        return transactionTemplate.execute(status ->
                withdrawalRepository.findByIdempotencyKey(idempotencyKey)
                        .map(existing -> validateIdempotentRequest(existing, chainType, req))
                        .orElseGet(() -> createAndBroadcast(idempotencyKey, chainType, req))
        );
    } finally {
        lock.unlock();
    }
}
```

```java
TxAttempt attempt = attemptService.createAttempt(saved.getId(), req.fromAddress(), resolveInitialNonce(chainType, req.fromAddress()));
broadcastAttempt(saved, attempt);

if (confirmationTracker != null) {
    confirmationTracker.startTracking(attempt);
}
```

```java
boolean matches = existing.getChainType() == chainType
    && existing.getFromAddress().equals(req.fromAddress())
    && existing.getToAddress().equals(req.toAddress())
    && existing.getAsset().equals(req.asset())
    && existing.getAmount() == reqWei;

if (!matches) {
    throw new IdempotencyConflictException("same Idempotency-Key cannot be used with a different request body");
}
```

- **업무 단위(Withdrawal)** 와 **시도 단위(TxAttempt)** 를 분리해서 retry/replace가 일어나도 원본 Withdrawal은 유지합니다.
- 같은 `Idempotency-Key` + 동일 바디면 기존 Withdrawal을 반환하고, 같은 키 + 다른 바디면 `409` 충돌을 유도합니다.
- 같은 `Idempotency-Key` 동시 요청은 `ReentrantLock`으로 직렬화하고, 락 내부에서 `TransactionTemplate`로 조회/생성/브로드캐스트를 커밋까지 묶어 중복 브로드캐스트(`already known`)를 방지합니다.
- 생성 시점에 첫 `TxAttempt`를 만들고 브로드캐스트하며, 가능하면 `ConfirmationTracker` 비동기 추적도 시작합니다.
- 현재 구현에는 `ApprovalService`, `LedgerService`가 선택 주입(`@Autowired(required=false)`)으로 연결될 수 있는 훅이 포함되어 있습니다.

---

### 실습 3 — Retry / Replace / Included 수렴

핵심 코드 (`RetryReplaceService#retry`, `#replace`, `#sync`)

```java
canonical.transitionTo(TxAttemptStatus.FAILED_TIMEOUT);
canonical.setCanonical(false);

long nonce = canonical.getNonce() + 1;
if (adapter instanceof EvmRpcAdapter rpcAdapter) {
    nonce = rpcAdapter.getPendingNonce(canonical.getFromAddress()).longValue();
}

TxAttempt retried = attemptService.createAttempt(withdrawalId, canonical.getFromAddress(), nonce);
broadcast(w, retried);
```

```java
canonical.transitionTo(TxAttemptStatus.REPLACED);
canonical.markException(AttemptExceptionType.REPLACED, "fee bump replacement");
canonical.setCanonical(false);

TxAttempt replaced = attemptService.createAttempt(withdrawalId, canonical.getFromAddress(), canonical.getNonce());
replaced.setFeeParams(
        bumpedFee(canonical.getMaxPriorityFeePerGas(), DEFAULT_PRIORITY_FEE),
        bumpedFee(canonical.getMaxFeePerGas(), DEFAULT_MAX_FEE)
);
broadcast(w, replaced);
```

```java
var receiptOpt = rpcAdapter.getReceipt(canonical.getTxHash());
if (receiptOpt.isPresent()) {
    canonical.transitionTo(TxAttemptStatus.INCLUDED);
    if ("0x1".equalsIgnoreCase(receiptOpt.get().getStatus())) {
        canonical.transitionTo(TxAttemptStatus.SUCCESS);
        w.transitionTo(WithdrawalStatus.W7_INCLUDED);
    } else {
        canonical.transitionTo(TxAttemptStatus.FAILED);
    }
}
```

- `retry`는 기존 canonical attempt를 timeout 처리하고 새 nonce(가능하면 RPC pending nonce)로 재전송합니다.
- `replace`는 같은 nonce를 유지한 채 fee bump(약 +12.5%)로 교체 전송합니다.
- `sync`는 실제 receipt를 폴링해서 `INCLUDED/SUCCESS/FAILED`로 수렴시킵니다.
- 현재 구현은 `retry/replace` 총 시도 수를 `3`개로 제한합니다.

---

### 실습 4 — Chain Adapter + EVM RPC(Sepolia/Hoodi)

핵심 코드 (`EvmRpcAdapter#broadcast`, `#ensureConnectedChainIdMatchesConfigured`)

```java
if (!isValidAddress(command.to())) {
    throw new IllegalArgumentException("Invalid EVM to-address: " + command.to());
}

ensureConnectedChainIdMatchesConfigured();

BigInteger nonce = command.nonce() >= 0
        ? BigInteger.valueOf(command.nonce())
        : getPendingNonce(signer.getAddress());

RawTransaction rawTransaction = RawTransaction.createEtherTransaction(
        configuredChainId,
        nonce,
        GAS_LIMIT,
        command.to(),
        BigInteger.valueOf(command.amount()),
        maxPriorityFeePerGas,
        maxFeePerGas
);

String signedTxHex = signer.sign(rawTransaction, configuredChainId);
EthSendTransaction sent = web3j.ethSendRawTransaction(signedTxHex).send();
```

```java
EthChainId chainIdResponse = web3j.ethChainId().send();
long remoteChainId = chainIdResponse.getChainId().longValue();
if (remoteChainId != configuredChainId) {
    throw new IllegalStateException(
        "Connected RPC chain id mismatch. expected=" + configuredChainId + ", actual=" + remoteChainId
    );
}
```

- 오케스트레이터는 `ChainAdapterRouter`를 통해 체인 타입별 어댑터를 선택하고 `broadcast(command)`만 호출합니다.
- EVM RPC 어댑터는 web3j 기반으로 EIP-1559 타입 트랜잭션을 서명/전송하며, `chainId` 불일치를 즉시 차단합니다.
- nonce/fee는 요청값이 없으면 어댑터 기본값(`pending nonce`, 기본 fee)으로 보정됩니다.

---

### 실습 5 — Correlation ID + 로그 표준화

핵심 코드 (`CorrelationIdFilter#doFilterInternal`, `GlobalExceptionHandler`, `application.yaml`, `WithdrawalController`/`WithdrawalService`)

```java
String correlationId = resolveCorrelationId(request.getHeader(CORRELATION_ID_HEADER));
MDC.put(MDC_KEY, correlationId);
response.setHeader(CORRELATION_ID_HEADER, correlationId);

try {
    filterChain.doFilter(request, response);
} finally {
    MDC.remove(MDC_KEY);
}
```

```yaml
logging:
  pattern:
    level: "%5p [cid:%X{correlationId:-none}]"
```

```java
ErrorResponse body = new ErrorResponse(
        HttpStatus.BAD_REQUEST.value(),
        message,
        allowedChainTypes(),
        currentCorrelationId()
);
```

```java
log.info("event=withdrawal.create.request chainType={} asset={} amount={} toAddress={} idempotencyKeyPresent={}",
        req.chainType(), req.asset(), req.amount(), req.toAddress(), idempotencyKey != null && !idempotencyKey.isBlank());

log.info("event=withdrawal_service.create_or_get.start idempotencyKey={} chainType={} asset={} amount={} toAddress={}",
        idempotencyKey, chainType, req.asset(), req.amount(), req.toAddress());
```

- `CorrelationIdFilter`가 요청 헤더(`X-Correlation-Id`)를 수신하거나 서버에서 생성한 값을 `MDC`에 넣고, 응답 헤더에도 동일 값을 반환합니다.
- 로그 패턴 `%X{correlationId}`로 모든 애플리케이션 로그에 `cid`를 표시합니다.
- 예외 응답 body(`ErrorResponse`, `RuntimeErrorResponse`)에 `correlationId`를 포함해 클라이언트/서버 로그 상호 추적을 쉽게 만듭니다.
- 컨트롤러/서비스 로그는 `event=... key=value` 형태로 통일해 검색/필터링을 단순화합니다.

---

### 실습 6 (심화) — 동시성 멱등성 + 비동기 Confirmation Tracker

핵심 코드 (`WithdrawalService#createOrGet`, `ConfirmationTracker`)

```java
ReentrantLock lock = idempotencyLocks.computeIfAbsent(idempotencyKey, key -> new ReentrantLock());
lock.lock();
try {
    return transactionTemplate.execute(status ->
            withdrawalRepository.findByIdempotencyKey(idempotencyKey)
                    .map(existing -> validateIdempotentRequest(existing, chainType, req))
                    .orElseGet(() -> createAndBroadcast(idempotencyKey, chainType, req))
    );
} finally {
    lock.unlock();
}
```

```java
public void startTracking(TxAttempt attempt) {
    executor.submit(() -> trackAttemptInternal(attempt.getId()));
}

while (tries < 60) {
    Optional<TransactionReceipt> r = rpcAdapter.getReceipt(txHash);
    if (r.isPresent()) {
        toUpdateAttempt.transitionTo(TxAttemptStatus.INCLUDED);
        toUpdateWithdrawal.transitionTo(WithdrawalStatus.W7_INCLUDED);
        return;
    }
    TimeUnit.SECONDS.sleep(2);
}
timeoutAttempt.transitionTo(TxAttemptStatus.FAILED_TIMEOUT);
```

- 단순 `@Transactional`만으로는 동시 요청에서 중복 브로드캐스트가 발생할 수 있어, 현재 구현은 `Idempotency-Key`별 JVM 락 + `TransactionTemplate`로 커밋 완료까지 직렬화합니다.
- 현재 `ConfirmationTracker`는 `@Scheduled`가 아니라 `ExecutorService` 기반 비동기 추적 방식입니다.
- EVM RPC 어댑터일 때만 receipt 폴링을 수행하고, 일정 시간 내 receipt 미발견 시 `FAILED_TIMEOUT`으로 표시합니다.


## 보안/안전 가드

---

### Confirmation Tracker — Receipt Polling 및 상태 전이 (핵심 코드)

현재 구현은 `ExecutorService` 기반 비동기 폴링입니다. 브로드캐스트 직후 `startTracking(attempt)`를 호출하면 attempt ID 기준으로 재조회 후 receipt를 추적합니다.

```java
public void startTracking(TxAttempt attempt) {
    executor.submit(() -> trackAttemptInternal(attempt.getId()));
}

private void trackAttemptInternal(UUID attemptId) {
    ...
    while (tries < 60) {
        Optional<TransactionReceipt> r = rpcAdapter.getReceipt(txHash);
        if (r.isPresent()) {
            toUpdateAttempt.transitionTo(TxAttemptStatus.INCLUDED);
            txAttemptRepository.save(toUpdateAttempt);

            toUpdateWithdrawal.transitionTo(WithdrawalStatus.W7_INCLUDED);
            withdrawalRepository.save(toUpdateWithdrawal);
            return;
        }
        TimeUnit.SECONDS.sleep(2);
    }
    timeoutAttempt.transitionTo(TxAttemptStatus.FAILED_TIMEOUT);
}
```

- 트래커는 엔티티를 다시 조회한 뒤 업데이트해서 stale entity 문제를 줄이도록 작성되어 있습니다.
- 현재 구현은 receipt status(`0x1/0x0`)까지 저장하지 않고, receipt 존재 여부 기준으로 `INCLUDED`와 `W7_INCLUDED`를 전이합니다.
- 최종 성공/실패 판정까지 필요하면 `POST /withdrawals/{id}/sync` 경로(`RetryReplaceService#sync`)를 사용하세요.

### Troubleshooting — RPC 호출 오류 응답 본문 확인 (PowerShell)

실습 중 RPC 관련 API 호출이 실패하면 PowerShell의 `Invoke-RestMethod`가 본문 대신 예외만 보여주는 경우가 있습니다. 아래처럼 `try/catch`로 감싸서 **응답 본문(response body)** 을 다시 읽으면 실제 오류 메시지(RPC reject 사유, validation 에러 등)를 확인할 수 있습니다.

```powershell
try {
  Invoke-RestMethod -Method POST `
    -Uri "$BASE_URL/withdrawals/$($w.id)/replace"
} catch {
  $resp = $_.Exception.Response
  $reader = New-Object System.IO.StreamReader($resp.GetResponseStream())
  $reader.ReadToEnd()
}
```

- `replace`에서 `nonce too low` 같은 RPC 에러가 날 때 원인 확인에 유용합니다.
- 다른 API(`POST /withdrawals`, `POST /withdrawals/{id}/retry`, `POST /withdrawals/{id}/sync`)에도 동일하게 사용할 수 있습니다.
- 필요하면 `Write-Host $_.Exception.Message`도 함께 출력해 HTTP 레벨 오류 메시지를 같이 확인하세요.


- `custody.evm.chain-id=1`(mainnet)은 부팅 시 차단됩니다.
- `custody.chain.mode=rpc`일 경우 `CUSTODY_EVM_PRIVATE_KEY`, `CUSTODY_EVM_RPC_URL` 미설정 시 `RpcModeStartupGuard`에서 부팅을 차단합니다.
- `EvmRpcAdapter`는 브로드캐스트 전에 RPC의 실제 `eth_chainId`와 설정값(`custody.evm.chain-id`) 일치 여부를 검증합니다.
- 개인키는 절대 커밋하지 마세요.
