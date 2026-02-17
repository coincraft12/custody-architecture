# custody-architecture

수탁형 지갑 출금 구조에서 **Withdrawal(업무 단위)** 와 **TxAttempt(체인 시도 단위)** 를 분리하고,
Retry / Replace / Policy / Chain Adapter 동작을 단계별로 실습하는 프로젝트입니다.

---

## 1) 실습 범위 요약

- **실습 1: Withdrawal·TxAttempt 모델 + 상태 전이 + 멱등성**
  - `withdrawal` / `tx_attempt` 데이터 모델 및 저장
  - `Idempotency-Key` 기반 중복 요청 방지
  - 업무 단위(Withdrawal)는 유지되고 시도(TxAttempt)는 누적되는 구조

- **실습 2: Retry/Replace 시나리오 시뮬레이션**
  - 실패 유도 후 재시도(Attempt 누적)
  - replace 이벤트 시뮬레이션 후 canonical attempt 전환
  - Withdrawal이 끊기지 않고 최종 상태로 수렴

- **실습 3(경량): ChainAdapter 인터페이스 + Mock 2종**
  - EVM / BFT Mock 구현 2종
  - 오케스트레이터가 체인 상세를 몰라도 동일한 호출 형태로 broadcast

- **실습 4: Policy Engine 최소 구현**
  - 금액 한도 + 수신 주소 화이트리스트 룰
  - 거절 사유 + 감사 로그(audit log) 기록

---

## 2) 실행 환경

- Java 21+
- Gradle(Wrapper 또는 로컬 Gradle)
- Spring Boot + H2(in-memory)

> 기본 포트: `8080`

H2 Console:
- URL: `http://localhost:8080/h2`
- JDBC: `jdbc:h2:mem:testdb`
- username: `sa`
- password: (빈 값)

---

## 3) 빠른 실행

```bash
./gradlew bootRun
```

Gradle wrapper 실행 권한이 없으면:

```bash
chmod +x gradlew
./gradlew bootRun
```

---

## 4) 단계별 점검 가이드 (수동 API)

아래 예시는 모두 `curl` 기준입니다.

### Windows 셸별 줄바꿈 템플릿 (`curl`)

한글 키보드에서 `\` 키가 `₩`로 보이는 경우가 있어 줄바꿈 시 오류가 날 수 있습니다.
아래처럼 **셸별 줄바꿈 문자**를 사용하세요.

#### PowerShell 템플릿

PowerShell은 줄바꿈에 **백틱**(`` ` ``)을 사용합니다.

```powershell
curl -i -X POST "http://localhost:8080/withdrawals" `
  -H "Content-Type: application/json" `
  -H "Idempotency-Key: idem-lab1-1" `
  -d '{"chainType":"evm","fromAddress":"0xfrom-lab1","toAddress":"0xto","asset":"USDC","amount":100}'
```

#### CMD 템플릿

CMD는 줄바꿈에 **캐럿**(`^`)을 사용합니다.

```cmd
curl -i -X POST "http://localhost:8080/withdrawals" ^
  -H "Content-Type: application/json" ^
  -H "Idempotency-Key: idem-lab1-1" ^
  -d "{\"chainType\":\"evm\",\"fromAddress\":\"0xfrom-lab1\",\"toAddress\":\"0xto\",\"asset\":\"USDC\",\"amount\":100}"
```

### STEP 0. 서버 실행

```bash
./gradlew bootRun
```

---

### STEP 1. 실습 1 점검 (멱등성 + 초기 Attempt 생성)

#### 1-1. Withdrawal 생성

```bash
curl -i -X POST "http://localhost:8080/withdrawals" \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: idem-lab1-1" \
  -d '{"chainType":"evm","fromAddress":"0xfrom-lab1","toAddress":"0xto","asset":"USDC","amount":100}'
```

확인 포인트:
- HTTP 200
- 응답 `status = W4_SIGNING`
- 응답 `id` 값 복사

#### 1-2. 같은 Idempotency-Key + 같은 Body 재요청

```bash
curl -i -X POST "http://localhost:8080/withdrawals" \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: idem-lab1-1" \
  -d '{"chainType":"evm","fromAddress":"0xfrom-lab1","toAddress":"0xto","asset":"USDC","amount":100}'
```

확인 포인트:
- 같은 `withdrawal id` 반환 (중복 생성 방지)

#### 1-3. Attempt 목록 확인

```bash
curl -s "http://localhost:8080/withdrawals/{withdrawalId}/attempts"
```

확인 포인트:
- length = 1
- `attemptNo = 1`
- `canonical = true`

#### 1-4. 같은 Idempotency-Key + 다른 Body 충돌 확인

```bash
curl -i -X POST "http://localhost:8080/withdrawals" \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: idem-lab1-1" \
  -d '{"chainType":"bft","fromAddress":"0xfrom-lab1","toAddress":"0xto","asset":"USDC","amount":100}'
```

확인 포인트:
- HTTP 409
- 메시지: `same Idempotency-Key cannot be used with a different request body`

---

### STEP 2. 실습 2 점검 (Retry / Replace / Canonical 전환)

#### 2-1. 테스트용 Withdrawal 생성

```bash
curl -s -X POST "http://localhost:8080/withdrawals" \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: idem-lab2-1" \
  -d '{"chainType":"evm","fromAddress":"0xfrom-lab2","toAddress":"0xto","asset":"USDC","amount":50}'
```

`id`를 복사해 `{withdrawalId}`로 사용.

#### 2-2. FAIL_SYSTEM 주입 후 broadcast

```bash
curl -X POST "http://localhost:8080/sim/withdrawals/{withdrawalId}/next-outcome/FAIL_SYSTEM"
curl -s -X POST "http://localhost:8080/sim/withdrawals/{withdrawalId}/broadcast"
```

```bash
curl -s "http://localhost:8080/withdrawals/{withdrawalId}/attempts"
```

확인 포인트:
- Attempt가 2개로 증가
- 기존 attempt `canonical=false`, `exceptionType=FAILED_SYSTEM`
- 새 attempt `canonical=true`

#### 2-3. REPLACED 주입 후 broadcast

```bash
curl -X POST "http://localhost:8080/sim/withdrawals/{withdrawalId}/next-outcome/REPLACED"
curl -s -X POST "http://localhost:8080/sim/withdrawals/{withdrawalId}/broadcast"
```

```bash
curl -s "http://localhost:8080/withdrawals/{withdrawalId}/attempts"
```

확인 포인트:
- Attempt가 3개로 증가
- 이전 canonical attempt가 `exceptionType=REPLACED`, `canonical=false`
- 최신 attempt가 `canonical=true`

#### 2-4. SUCCESS 주입 후 broadcast

```bash
curl -X POST "http://localhost:8080/sim/withdrawals/{withdrawalId}/next-outcome/SUCCESS"
curl -s -X POST "http://localhost:8080/sim/withdrawals/{withdrawalId}/broadcast"
```

```bash
curl -s "http://localhost:8080/withdrawals/{withdrawalId}"
```

확인 포인트:
- Withdrawal 최종 `status = W7_INCLUDED`
- 최신 canonical attempt `status = A4_INCLUDED`

---

### STEP 3. 실습 3 점검 (ChainAdapter 2종)

#### 3-1. EVM adapter 호출

```bash
curl -s -X POST "http://localhost:8080/adapter-demo/broadcast/evm" \
  -H "Content-Type: application/json" \
  -d '{"from":"a","to":"b","asset":"ETH","amount":10,"nonce":1}'
```

확인 포인트:
- `accepted = true`
- `txHash` prefix가 `0xEVM_`

#### 3-2. BFT adapter 호출

```bash
curl -s -X POST "http://localhost:8080/adapter-demo/broadcast/bft" \
  -H "Content-Type: application/json" \
  -d '{"from":"a","to":"b","asset":"TOKEN","amount":10,"nonce":1}'
```

확인 포인트:
- `accepted = true`
- `txHash` prefix가 `BFT_`

---

### STEP 4. 실습 4 점검 (Policy Engine + Audit)

기본 정책(`application.yaml`):
- `policy.max-amount: 1000`
- `policy.whitelist-to-addresses: 0xto,0xtrusted`

#### 4-1. 허용 케이스

```bash
curl -s -X POST "http://localhost:8080/withdrawals" \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: idem-lab4-allow-1" \
  -d '{"chainType":"evm","fromAddress":"0xfrom","toAddress":"0xto","asset":"USDC","amount":100}'
```

확인 포인트:
- `status = W4_SIGNING`

#### 4-2. 화이트리스트 거절 케이스

```bash
curl -s -X POST "http://localhost:8080/withdrawals" \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: idem-lab4-reject-whitelist-1" \
  -d '{"chainType":"evm","fromAddress":"0xfrom","toAddress":"0xnot-allowed","asset":"USDC","amount":100}'
```

확인 포인트:
- `status = W0_POLICY_REJECTED`
- 응답 `id` 획득 후 audit 확인:

```bash
curl -s "http://localhost:8080/withdrawals/{withdrawalId}/policy-audits"
```

예상 reason:
- `TO_ADDRESS_NOT_WHITELISTED: 0xnot-allowed`

#### 4-3. 금액 초과 거절 케이스

```bash
curl -s -X POST "http://localhost:8080/withdrawals" \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: idem-lab4-reject-amount-1" \
  -d '{"chainType":"evm","fromAddress":"0xfrom","toAddress":"0xto","asset":"USDC","amount":1001}'
```

확인 포인트:
- `status = W0_POLICY_REJECTED`
- audit reason:
  - `AMOUNT_LIMIT_EXCEEDED: max=1000, requested=1001`

---

## 5) 자동 테스트

전체 테스트:

```bash
./gradlew test
```

실습 시나리오 통합 테스트만 실행:

```bash
./gradlew test --tests "lab.custody.orchestration.LabScenariosIntegrationTest"
```

---

## 6) 주요 API 요약

- `POST /withdrawals` (Header: `Idempotency-Key`)
- `GET /withdrawals/{id}`
- `GET /withdrawals/{id}/attempts`
- `GET /withdrawals/{id}/policy-audits`
- `POST /sim/withdrawals/{id}/next-outcome/{FAIL_SYSTEM|REPLACED|SUCCESS}`
- `POST /sim/withdrawals/{id}/broadcast`
- `POST /adapter-demo/broadcast/{evm|bft}`

---

## 설계 철학

출금 시스템은 실패를 제거하는 시스템이 아니라,
실패를 분류하고 canonical을 재선정하여
최종 상태로 수렴시키는 시스템이다.
