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
