# custody-architecture 현재 수준 평가

> 작성일: 2026-04-03  
> 대상 브랜치: `main` (Spring Boot 3.5 / Java 21)

---

## 1. 한 줄 요약

> **학습·검증 목적의 잘 구조화된 MVP**로, 핵심 커스터디 흐름은 모두 동작하지만 프로덕션 준비(인증, 키 관리, 모니터링, 멀티 어프루버 등)는 아직 구현되지 않았다.

---

## 2. 구현 완료 항목

| 영역 | 세부 내용 | 완성도 |
|------|-----------|--------|
| **출금 상태 머신** | W0 → W1 → W3 → W4 → W5 → W6 → W7 → W8 → W9 → W10 전환 | ✅ 완성 |
| **멱등성** | `Idempotency-Key` 기반, DB + `ReentrantLock` 이중 보호 | ✅ 완성 |
| **Policy Engine** | 금액 한도, 수신 주소 화이트리스트 규칙 (확장 가능한 `PolicyRule` 인터페이스) | ✅ 완성 |
| **Policy Audit Log** | 모든 정책 평가 결과를 DB에 기록 | ✅ 완성 |
| **Retry / Replace** | 새 nonce로 재브로드캐스트(retry), 동일 nonce fee bump(replace) | ✅ 완성 |
| **체인 어댑터 추상화** | `ChainAdapter` 인터페이스 → EVM/BFT 어댑터 분리 | ✅ 완성 |
| **EVM RPC 어댑터** | Web3j 기반 EIP-1559 서명+브로드캐스트, Sepolia/Hoodi 연결 가능 | ✅ 완성 |
| **화이트리스트 48h 보류** | REGISTERED → HOLDING → (스케줄러) → ACTIVE / REVOKED 상태 머신 | ✅ 완성 |
| **원장(Ledger)** | RESERVE(W3) → SETTLE(W8) 이중 기입 | ✅ 완성 |
| **Confirmation Tracker** | RPC receipt 폴링 → W7_INCLUDED 전환 (비동기, 켜고 끌 수 있음) | ✅ 완성 |
| **구조화 로그** | Logstash JSON 포맷, MDC Correlation-ID 전파 | ✅ 완성 |
| **통합 테스트** | MockMvc 기반, 주요 시나리오 커버 (멱등성·retry/replace·whitelist·상태 머신) | ✅ 완성 |
| **PostgreSQL 마이그레이션** | Flyway V1/V2, 9개 테이블 DDL 포함 | ✅ 완성 |
| **Docker Compose** | PostgreSQL 16 로컬 실행 환경 제공 | ✅ 완성 |

---

## 3. DB 스키마 vs. Java 구현 간 격차

V1 마이그레이션 SQL에는 아래 테이블이 정의되어 있지만, 대응하는 JPA 엔티티나 서비스 로직이 **아직 없다**. 향후 구현할 기능의 "설계 의도"를 미리 담아 둔 것이다.

| 테이블 | 설계 의도 | 구현 여부 |
|--------|-----------|-----------|
| `nonce_reservations` | 중복 nonce 방지를 위한 예약 잠금 | ❌ 미구현 |
| `policy_decisions` | PolicyAuditLog 보다 상세한 정책 결정 기록 | ❌ 미구현 |
| `approval_tasks` / `approval_decisions` | 멀티 어프루버 (n-of-m) 결재 워크플로우 | ❌ 미구현 (`ApprovalService`는 자동 승인) |
| `policy_change_requests` | 정책 변경 이력 관리 | ❌ 미구현 |
| `outbox_events` | Transactional Outbox 패턴 (이벤트 발행 보장) | ❌ 미구현 |
| `rpc_observation_snapshots` | RPC 응답 관측 기록 | ❌ 미구현 |

---

## 4. 프로덕션 준비도 체크리스트

아래 항목은 학습·PoC 목적으로는 의도적으로 제외되었으나, 실제 서비스라면 필요한 요소들이다.

### 4-1. 보안

| 항목 | 현재 상태 |
|------|-----------|
| API 인증/인가 (JWT, API Key 등) | ❌ 없음 |
| HSM / 실제 키 관리 | ❌ `MockSigner`만 구현됨 (`EvmSigner`는 평문 private key) |
| TLS/HTTPS | ❌ 미설정 |
| Secret 관리 (Vault 등) | ❌ 환경변수 의존 |
| Rate Limiting | ❌ 없음 |

### 4-2. 운영

| 항목 | 현재 상태 |
|------|-----------|
| Spring Actuator / Health Check | ❌ 미설정 |
| Prometheus / Micrometer 메트릭 | ❌ 없음 |
| Alert / PagerDuty 연동 | ❌ 없음 |
| Distributed Tracing (OTEL 등) | ❌ 없음 (MDC Correlation-ID만 존재) |
| Graceful Shutdown | ❌ 미설정 |

### 4-3. 기능

| 항목 | 현재 상태 |
|------|-----------|
| 멀티 어프루버 결재 | ❌ 자동 승인만 지원 |
| 입금(Deposit) 처리 | ❌ 없음 |
| 잔액 확인 / 잔액 부족 처리 | ❌ 없음 |
| 멀티 테넌트 | ❌ 단일 테넌트 |
| Nonce 예약 잠금 | ❌ `NonceAllocator`가 있지만 실제 DB 잠금 미구현 |
| Outbox 이벤트 발행 | ❌ 스키마만 존재 |
| BFT 어댑터 실제 연동 | ❌ `BftMockAdapter`만 존재 |
| API 버전 관리 | ❌ 없음 |

---

## 5. 코드 품질

| 항목 | 평가 |
|------|------|
| **레이어 분리** | `domain` / `orchestration` / `adapter` 가 명확히 분리되어 있음 ✅ |
| **도메인 모델** | `Withdrawal`, `TxAttempt`, `WhitelistAddress` 등 상태 전환 메서드가 도메인 내부에 캡슐화됨 ✅ |
| **불변성** | `@Builder` + `@NoArgsConstructor(PROTECTED)` 패턴으로 무분별한 setter 억제 ✅ |
| **트랜잭션 경계** | 핵심 흐름이 단일 트랜잭션 내 처리됨 ✅ |
| **주석 스타일** | 영어·한국어 혼용. 일부 "왜"를 설명하는 주석 있음 ✅ |
| **테스트 커버리지** | 주요 시나리오 통합 테스트 존재. 단위 테스트는 부족함 ⚠️ |
| **예외 처리** | `GlobalExceptionHandler`로 HTTP 응답 표준화됨 ✅ |

---

## 6. 아키텍처 성숙도 요약

```
Level 1 — 작동 가능 (Runnable)         ✅ 완성
Level 2 — 학습·시연 가능 (Demonstrable) ✅ 완성
Level 3 — 실제 테스트넷 브로드캐스트     ✅ 완성 (EVM RPC 모드)
Level 4 — 운영 준비 (Production-Ready)  ❌ 미완 (인증·HSM·모니터링·멀티어프루버)
Level 5 — 엔터프라이즈 (Enterprise)      ❌ 미완 (멀티 테넌트·Outbox·BFT 실 연동)
```

**현재 레포는 Level 3에 해당한다.**  
커스터디 시스템의 핵심 설계 패턴(상태 머신, 멱등성, 어댑터 추상화, 감사 로그)을 실습·검증하기에 충분한 수준이며, EVM 테스트넷 브로드캐스트까지 실제로 동작한다.

---

## 7. 권장 다음 단계 (우선순위 순)

1. **API 인증 추가** — Spring Security + JWT 또는 API Key 미들웨어
2. **단위 테스트 보강** — `PolicyEngine`, `WithdrawalService`, `AttemptService` 등 순수 로직 단위 테스트
3. **멀티 어프루버 결재 구현** — `approval_tasks` / `approval_decisions` 테이블 활용
4. **Nonce DB 잠금 구현** — `nonce_reservations` 테이블 활용, 동시 브로드캐스트 방지
5. **Spring Actuator 활성화** — 헬스 체크 + 기본 메트릭
6. **Outbox 이벤트 발행** — `outbox_events` 테이블 기반 트랜잭셔널 아웃박스 구현
7. **실제 키 관리** — 환경변수 private key를 Vault 또는 AWS KMS로 교체
