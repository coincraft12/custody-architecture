# custody-architecture

수탁형 지갑 출금 구조에서\
Withdrawal(업무 단위)와\
TxAttempt(체인 시도 단위)를 분리하고

Retry / Replace 시나리오를 시뮬레이션하는 실습 프로젝트입니다.

------------------------------------------------------------------------

## 실습 목표

-   Withdrawal = 비즈니스 단위
-   TxAttempt = 체인 시도 단위
-   멱등성(Idempotency Key) 처리
-   Nonce 기반 attempt_group_key 구성
-   canonical attempt 전환
-   FAILED / REPLACED 시나리오 시뮬레이션
-   FakeChain 기반 브로드캐스트 테스트

------------------------------------------------------------------------

## 아키텍처 핵심 개념

Withdrawal 1건  
     ├── TxAttempt #1 (FAILED_SYSTEM)  
     ├── TxAttempt #2 (REPLACED)  
     └── TxAttempt #3 (canonical)  

-   Tracker 키 1차: txHash
-   Tracker 키 2차: (from, nonce)
-   같은 nonce는 replacement 가능성 100%

------------------------------------------------------------------------

## 개발 환경

### OS

-   Windows 11

### Java

-   OpenJDK 21 (Temurin 21.0.10 LTS)

확인 명령: java -version

### Build Tool

-   Gradle Wrapper 포함
-   Gradle 9.x

### Framework

-   Spring Boot 4.0.2
-   Spring Data JPA
-   H2 Database (In-Memory)

### IDE / Editor

-   Visual Studio Code 1.101.0

### Git

-   git version 2.49.0.windows.1

------------------------------------------------------------------------

## 데이터베이스

H2 In-Memory Database 사용

접속: http://localhost:8080/h2

JDBC URL: jdbc:h2:mem:testdb

username: sa\
password: (빈 값)

------------------------------------------------------------------------

## 실행 방법

./gradlew bootRun

------------------------------------------------------------------------

## Basic Policy Engine 테스트 가이드

`PolicyEngine`은 현재 아래 2가지를 검사합니다.

-   출금 금액이 `policy.max-amount` 이내인지
-   `policy.whitelist-to-addresses` 에 수신 주소가 포함되는지

기본 설정(`src/main/resources/application.yaml`):

-   `policy.max-amount: 1000`
-   `policy.whitelist-to-addresses: 0xto,0xtrusted`

### 1) 자동 테스트(권장)

Policy reject + audit log 동작은 통합 테스트로 바로 검증 가능합니다.

```bash
./gradlew test --tests "lab.custody.orchestration.WithdrawalControllerIntegrationTest"
```

테스트 포인트:

-   허용 케이스: status = `W4_SIGNING`
-   비허용 케이스: status = `W0_POLICY_REJECTED`
-   감사 로그: `/withdrawals/{id}/policy-audits` 에 reject reason 기록

### 2) 수동 API 테스트

> Windows PowerShell 기준으로 `curl`(`Invoke-WebRequest` 별칭) 문법을 사용합니다.
> 아래 예시는 모두 같은 형식으로 통일했습니다.

#### 2-1. 서버 실행

```bash
./gradlew bootRun
```

#### 2-2. 허용 케이스(화이트리스트 주소 + 금액 제한 이내)

```powershell
curl -Method POST "http://localhost:8080/withdrawals" `
     -Headers @{ "Content-Type"="application/json"; "Idempotency-Key"="idem-allow-1" } `
     -Body '{ "chainType":"evm", "fromAddress":"0xfrom", "toAddress":"0xto", "asset":"USDC", "amount":100 }'
```

확인 포인트:

-   HTTP 200
-   응답 `status` 가 `W4_SIGNING`

#### 2-3. 거절 케이스 #1 (화이트리스트 미포함)

```powershell
curl -Method POST "http://localhost:8080/withdrawals" `
     -Headers @{ "Content-Type"="application/json"; "Idempotency-Key"="idem-reject-whitelist-1" } `
     -Body '{ "chainType":"evm", "fromAddress":"0xfrom", "toAddress":"0xnot-allowed", "asset":"USDC", "amount":100 }'
```

확인 포인트:

-   HTTP 200
-   응답 `status` 가 `W0_POLICY_REJECTED`
-   응답 본문의 `id` 값을 복사

감사 로그 확인:

```powershell
curl -Method GET "http://localhost:8080/withdrawals/{복사한_id}/policy-audits"
```

예상 reason:

-   `TO_ADDRESS_NOT_WHITELISTED: 0xnot-allowed`

#### 2-4. 거절 케이스 #2 (금액 제한 초과)

```powershell
curl -Method POST "http://localhost:8080/withdrawals" `
     -Headers @{ "Content-Type"="application/json"; "Idempotency-Key"="idem-reject-amount-1" } `
     -Body '{ "chainType":"evm", "fromAddress":"0xfrom", "toAddress":"0xto", "asset":"USDC", "amount":1001 }'
```

예상 reason:

-   `AMOUNT_LIMIT_EXCEEDED: max=1000, requested=1001`

### 3) 정책 값 바꿔서 검증하기

실행 시점에 정책 파라미터를 바꿔 다양한 테스트를 할 수 있습니다.

```bash
./gradlew bootRun --args='--policy.max-amount=500 --policy.whitelist-to-addresses=0xaaa,0xbbb'
```

이후 동일한 `curl -Method ... -Headers @{...} -Body '...'` 요청으로 allow/reject 경계값을 빠르게 점검할 수 있습니다.

------------------------------------------------------------------------

## 주요 API

### Withdrawal 생성

POST /withdrawals\
Header: Idempotency-Key

### Attempt 조회

GET /withdrawals/{id}/attempts

### 다음 결과 주입 (시뮬레이션)

POST /sim/withdrawals/{id}/next-outcome/{FAIL_SYSTEM\|REPLACED}

### Broadcast 실행

POST /sim/withdrawals/{id}/broadcast

------------------------------------------------------------------------

## 설계 철학

출금 시스템은 실패를 제거하는 시스템이 아니라,\
실패를 분류하고 canonical을 재선정하는 시스템이다.
