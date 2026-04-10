# 운영 수준 달성을 위한 상세 To-Do List

> 현재 코드베이스 Assessment 결과를 기반으로, 실제 운영(프로덕션) 환경에서 안전하게 동작하기 위해 필요한 작업을 **영역별·우선순위별**로 최대한 잘게 쪼개 정리한 목록입니다.

---

## 범례 (Legend)

| 기호 | 의미 |
|------|------|
| 🔴 CRITICAL | 즉시 처리 필요 — 운영 배포 차단 수준 |
| 🟠 HIGH | 단기(2주 이내) 처리 필요 |
| 🟡 MEDIUM | 중기(1개월 이내) 처리 필요 |
| 🟢 LOW | 장기·개선 과제 |

---

## 0. 🔴 JPA 엔티티 신규 생성 (사전 과제) — CRITICAL

> 마이그레이션 테이블은 존재하나 JPA 엔티티 클래스가 없어 실제 코드에서 접근 불가능한 테이블이 5개입니다.
> 섹션 1·6·8·10 작업 시작 전 반드시 완료해야 합니다.

### 0-1. 누락된 JPA 엔티티 클래스 작성
- [x] 0-1-1. `NonceReservation` 엔티티 작성 — `nonce_reservations` 테이블 매핑 (섹션 1-1-1~1-1-3 대체, 선행 필수) ✅
- [x] 0-1-2. `OutboxEvent` 엔티티 작성 — `outbox_events` 테이블 매핑 (섹션 6-3 선행 필수) ✅
- [x] 0-1-3. `ApprovalTask` 엔티티 작성 — `approval_tasks` 테이블 매핑 (섹션 10 선행 필수) ✅
- [x] 0-1-4. `ApprovalDecision` 엔티티 작성 — `approval_decisions` 테이블 매핑 (섹션 10 선행 필수) ✅
- [x] 0-1-5. `WhitelistAuditLog` 엔티티 작성 — V3 마이그레이션 추가 후 동시 완료 ✅

### 0-2. 마이그레이션 및 Repository 검증
- [x] 0-2-1. `whitelist_audit_log` 테이블 Flyway 마이그레이션 파일 신규 추가 (`V3__add_whitelist_audit_log_and_approval_count.sql`) ✅
- [x] 0-2-2. 모든 신규 엔티티에 `@Table(name=...)` 명시적 지정 및 컬럼 매핑 확인 ✅
- [x] 0-2-3. 신규 엔티티 Repository 인터페이스 작성 5개: `NonceReservationRepository`, `OutboxEventRepository`, `ApprovalTaskRepository`, `ApprovalDecisionRepository`, `WhitelistAuditLogRepository` ✅

---

## 1. 🔴 넌스 관리 (Nonce Management) — CRITICAL

현재 `NonceAllocator`는 인메모리 카운터에 불과하며, DB의 `nonce_reservations` 테이블이 정의되어 있지만 전혀 사용되지 않습니다.
서버 재시작 시 넌스 상태가 소멸되어 트랜잭션 충돌이 발생합니다.

### 1-1. `nonce_reservations` 테이블 활용 기반 구축
- [x] 1-1-1. `NonceReservation` JPA 엔티티 클래스 작성 — 섹션 0-1-1 완료로 대체 ✅
- [x] 1-1-2. `NonceReservationRepository` Spring Data 인터페이스 작성 — 섹션 0-2-3 완료로 대체 ✅
- [x] 1-1-3. `NonceReservation.status` 열거형 정의 (`RESERVED`, `COMMITTED`, `RELEASED`, `EXPIRED`) — `NonceReservationStatus.java` 완료 ✅
- [x] 1-1-4. `nonce_reservations` 테이블에 `(chain_type, from_address, nonce)` 복합 유니크 제약 확인 (V1 마이그레이션 이미 정의됨) ✅
- [x] 1-1-5. `nonce_reservations` 테이블에 `(from_address, status)` 복합 인덱스 추가 (V2 마이그레이션에 이미 추가됨) ✅

### 1-2. DB 기반 넌스 예약·커밋·해제 로직 구현
- [x] 1-2-1. `NonceAllocator.reserve(chainType, fromAddress, withdrawalId)` → EvmRpcAdapter로 현재 pending nonce 조회 후 DB에 `RESERVED` 레코드 삽입 ✅
- [x] 1-2-2. `NonceAllocator.commit(reservationId)` → `RESERVED` → `COMMITTED` 전이 ✅
- [x] 1-2-3. `NonceAllocator.release(reservationId)` → `COMMITTED`/`RESERVED` → `RELEASED` 전이 (retry/replace 완료 후 호출) ✅
- [ ] 1-2-4. 동시 예약 충돌 방지: DB `INSERT … ON CONFLICT DO NOTHING` 또는 `SELECT FOR UPDATE` 적용
- [x] 1-2-5. `WithdrawalService.createAndBroadcast()`에서 기존 `NonceAllocator.next()` 호출을 새 `reserve()` → `commit()` 흐름으로 교체 ✅
- [x] 1-2-6. `RetryReplaceService.retry()`에서 새 넌스 예약 시 동일 로직 적용 (RPC에서 최신 pending nonce 재조회) ✅
- [x] 1-2-7. `RetryReplaceService.replace()`에서 기존 예약 재사용 로직 구현 (동일 nonce 재사용, 새 예약 불필요) ✅

### 1-3. 넌스 예약 만료·정리 스케줄러
- [x] 1-3-1. `NonceCleaner` 스케줄러 클래스 작성 (`@Scheduled`) ✅
- [x] 1-3-2. `RESERVED` 상태에서 N분 초과 시 `EXPIRED`로 전이하는 쿼리 작성 ✅
- [x] 1-3-3. `EXPIRED` 예약에 연결된 `TxAttempt`를 `FAILED_TIMEOUT`으로 전이하는 로직 추가 ✅
- [x] 1-3-4. 만료 주기(`custody.nonce.expiry-minutes`) 설정값을 `application.yaml`에 추가 ✅
- [x] 1-3-5. `NonceCleaner` 단위 테스트 작성 ✅

### 1-4. 넌스 충돌 감지 및 복구
- [ ] 1-4-1. RPC 에러 응답에서 "nonce too low" 패턴 파싱 후 `AttemptExceptionType.RPC_INCONSISTENT` 기록
- [ ] 1-4-2. "nonce too low" 감지 시 해당 예약 `RELEASED` 처리 후 재예약 트리거 로직 추가
- [x] 1-4-3. `EvmRpcAdapter`에 `getPendingNonce(address)` 퍼블릭 메서드 노출 (이미 있으면 접근 수정자 확인) ✅
- [ ] 1-4-4. 멱등성 키 단위 넌스 추적 단위 테스트 작성
- [ ] 1-4-5. **다중 인스턴스 환경** 넌스 충돌 방지 전략 결정 및 구현: DB `SELECT FOR UPDATE SKIP LOCKED` 또는 Redis `SETNX` 분산 락 — 단일 인스턴스용 `ON CONFLICT DO NOTHING`만으로는 멀티 파드 환경에서 불충분

---

## 2. 🔴 보안 (Security) — CRITICAL

### 2-1. 개인키 관리
- [x] 2-1-1. `application.yaml` 및 소스코드에서 `CUSTODY_EVM_PRIVATE_KEY` 하드코딩 제거 확인 (환경변수로 처리 중, `.env` 파일 커밋 금지 `.gitignore`에 이미 명시됨) ✅
- [ ] 2-1-2. `.gitignore`에 `.env`, `*.pem`, `*.key` 추가 확인
- [ ] 2-1-3. AWS KMS / HashiCorp Vault 연동을 위한 `Signer` 인터페이스 확장 계획 수립 (실제 구현은 Phase 3 이후)
- [ ] 2-1-4. 개인키 인메모리 보유 시간 최소화: `EvmSigner`에서 서명 직후 변수 zeroing 처리 추가
- [x] 2-1-5. `RpcModeStartupGuard`에 mainnet chain-id=1 이외에 추가 프로덕션 체인 차단 로직 점검 (이미 구현됨) ✅

### 2-2. 입력 검증 (Input Validation)
- [ ] 2-2-1. `CreateWithdrawalRequest`에 Bean Validation 어노테이션 추가: `@NotBlank`, `@NotNull`, `@Positive(amount)`, `@Pattern(fromAddress/toAddress 형식)`
- [x] 2-2-2. EVM 주소 형식 검증 유틸리티 메서드 (`isValidEvmAddress()`) 추가 및 `PolicyEngine` 진입 전 사전 검증 (이미 구현됨) ✅
- [ ] 2-2-3. `RegisterAddressRequest`에도 동일한 Bean Validation 추가
- [ ] 2-2-4. `@ControllerAdvice`에서 `MethodArgumentNotValidException` 처리 추가 (현재 `GlobalExceptionHandler`에 없는 경우)
- [ ] 2-2-5. 입력 길이 제한 추가: `note`, `registeredBy`, `approvedBy`, `revokedBy` 필드 최대 길이 255 제한
- [ ] 2-2-6. `amount` 필드 최소값 0 초과 검증 추가 (0 ETH 출금 방지)

### 2-3. API 인증·인가
- [ ] 2-3-1. Spring Security 의존성 추가 (`spring-boot-starter-security`)
- [ ] 2-3-2. API Key 기반 인증 필터 구현 (`X-API-Key` 헤더 검증)
- [ ] 2-3-3. 역할(Role) 정의: `OPERATOR` (출금 생성), `APPROVER` (화이트리스트 승인), `ADMIN` (정책 변경)
- [ ] 2-3-4. `/whitelist/{id}/approve`, `/whitelist/{id}/revoke` 엔드포인트에 `APPROVER` 역할 제한 적용
- [ ] 2-3-5. `/sim/*` 엔드포인트를 운영 환경(`production` 프로파일)에서 비활성화하는 조건 추가
- [ ] 2-3-6. H2 콘솔(`/h2/**`)을 `production` 프로파일에서 비활성화 확인

### 2-4. Rate Limiting / DDoS 방어
- [ ] 2-4-1. `bucket4j` 의존성 추가 (`com.github.bucket4j:bucket4j-core`)
- [ ] 2-4-2. `POST /withdrawals` 엔드포인트에 IP 기준 Rate Limit 필터 적용 (예: 초당 10 요청)
- [ ] 2-4-3. `POST /whitelist` 엔드포인트에 IP 기준 Rate Limit 적용
- [ ] 2-4-4. Rate Limit 초과 시 `429 Too Many Requests` 표준 응답 반환

### 2-5. 민감정보 마스킹
- [ ] 2-5-1. 로그 출력 시 `private-key` 값 마스킹 확인 (`application.yaml` `logging.level` 수준 점검)
- [ ] 2-5-2. `EvmRpcConfig`에서 개인키 로깅 제거 확인
- [ ] 2-5-3. `GlobalExceptionHandler`에서 스택 트레이스에 포함될 수 있는 주소·키 마스킹 처리
- [ ] 2-5-4. Logback 패턴에 민감 필드 필터 규칙 추가 (`logback-spring.xml` 수정)
- [ ] 2-5-5. **DEBUG 레벨 로그** 마스킹 — RPC 응답 원문, 서명된 트랜잭션 바이트, `eth_sendRawTransaction` 파라미터가 DEBUG 로그에 노출되지 않도록 `toString()` 오버라이드 또는 Logback 필터 추가

---

## 3. 🔴 모니터링 및 메트릭 (Monitoring & Metrics) — CRITICAL

현재 메트릭 수집 코드가 전혀 없습니다.

### 3-1. Micrometer + Prometheus 기반 메트릭 수집
- [ ] 3-1-1. `spring-boot-starter-actuator` 의존성 추가 (이미 있으면 생략)
- [ ] 3-1-2. `micrometer-registry-prometheus` 의존성 추가
- [ ] 3-1-3. `application.yaml`에 `/actuator/prometheus` 엔드포인트 노출 설정
- [ ] 3-1-4. `application.yaml`에 `/actuator/health`, `/actuator/info` 노출 설정
- [ ] 3-1-5. `WithdrawalService`에 카운터 추가: `custody.withdrawal.created.total`
- [ ] 3-1-6. `WithdrawalService`에 카운터 추가: `custody.withdrawal.policy_rejected.total` (이유 태그 포함)
- [ ] 3-1-7. `WithdrawalService`에 카운터 추가: `custody.withdrawal.broadcasted.total`
- [ ] 3-1-8. `WithdrawalService`에 히스토그램 추가: `custody.withdrawal.create.duration` (요청 처리 시간)
- [ ] 3-1-9. `ConfirmationTracker`에 게이지 추가: `custody.confirmation_tracker.active_tasks` (추적 중인 TX 수)
- [ ] 3-1-10. `ConfirmationTracker`에 카운터 추가: `custody.confirmation_tracker.timeout.total`
- [ ] 3-1-11. `EvmRpcAdapter`에 카운터 추가: `custody.rpc.call.total` (메서드명·성공여부 태그)
- [ ] 3-1-12. `EvmRpcAdapter`에 히스토그램 추가: `custody.rpc.call.duration`
- [ ] 3-1-13. `RetryReplaceService`에 카운터 추가: `custody.withdrawal.retry.total`, `custody.withdrawal.replace.total`

### 3-2. Grafana 대시보드 구성 (선택적 — docker-compose 포함)
- [ ] 3-2-1. `docker-compose.yml`에 Prometheus 서비스 추가 (scrape 설정 포함)
- [ ] 3-2-2. `docker-compose.yml`에 Grafana 서비스 추가
- [ ] 3-2-3. Prometheus `scrape_configs`에 custody 앱 타겟 추가
- [ ] 3-2-4. 기본 Grafana 대시보드 JSON 파일 생성 (출금 성공률, 레이턴시, 브로드캐스트 수)

### 3-3. 헬스체크 엔드포인트 강화
- [ ] 3-3-1. DB 커넥션 헬스 인디케이터 활성화 확인 (`spring.datasource` → `HealthIndicator`)
- [ ] 3-3-2. `CustodyHealthIndicator` 커스텀 인디케이터 작성 (RPC 연결 상태, 대기중 TX 수 등 포함)
- [ ] 3-3-3. RPC 헬스체크: `eth_blockNumber` 호출로 RPC 연결 여부 점검
- [ ] 3-3-4. 헬스체크 응답에 버전 정보 포함 (`/actuator/info`에 빌드 버전 주입)

### 3-4. 알림 (Alerting)
- [ ] 3-4-1. 출금 실패율 임계값 초과 시 알림 규칙 정의 (Prometheus AlertRule 파일)
- [ ] 3-4-2. ConfirmationTracker 타임아웃 건수 임계값 알림 규칙 정의
- [ ] 3-4-3. RPC 오류율 임계값 알림 규칙 정의
- [ ] 3-4-4. DB 커넥션 풀 포화 알림 규칙 정의

---

## 4. 🟠 RPC 복원력 (RPC Resilience) — HIGH

### 4-1. Circuit Breaker 적용
- [ ] 4-1-1. `resilience4j-spring-boot3` 의존성 추가 (`build.gradle`)
- [ ] 4-1-2. `EvmRpcAdapter`의 `broadcast()` 메서드에 `@CircuitBreaker(name="evmRpc")` 적용
- [ ] 4-1-3. `EvmRpcAdapter`의 `getPendingNonce()` 메서드에 Circuit Breaker 적용
- [ ] 4-1-4. `application.yaml`에 Circuit Breaker 설정 추가: 실패율 임계값(50%), 슬라이딩 윈도우(10), 대기 시간(30s)
- [ ] 4-1-5. Circuit Breaker open 시 `BroadcastRejectedException` 발생 및 적절한 상태 전이 처리

### 4-2. Retry + Backoff 정책 구현
- [ ] 4-2-1. `EvmRpcAdapter`에 `@Retry(name="evmRpcRetry")` 적용 (`eth_sendRawTransaction` 제외 — 재전송 금지)
- [ ] 4-2-2. `application.yaml`에 Retry 설정: 최대 3회, 지수 백오프(1s, 2s, 4s)
- [ ] 4-2-3. 브로드캐스트 API(`broadcast()`)는 retry 제외 — 멱등성 파괴 위험 명시 주석 추가
- [ ] 4-2-4. Retry 소진 시 `AttemptExceptionType.FAILED_SYSTEM` 기록
- [ ] 4-2-5. **RPC 오류 분류 체계** 수립: 일시적 오류(TRANSIENT: timeout, rate-limit 429), 영구 오류(PERMANENT: invalid tx, insufficient funds), 네트워크 오류(NETWORK: connection refused) — `AttemptExceptionType` 확장 및 분류별 처리 로직 분기

### 4-3. 다중 RPC 프로바이더 폴백
- [ ] 4-3-1. `application.yaml`에 `custody.evm.fallback-rpc-urls` 리스트 설정 추가
- [ ] 4-3-2. `EvmRpcConfig`에서 폴백 URL 리스트 읽어 `Web3j` 인스턴스 리스트 생성
- [ ] 4-3-3. `EvmRpcAdapter`에 Round-Robin 또는 Priority 기반 프로바이더 선택 로직 구현
- [ ] 4-3-4. 1차 RPC 실패 시 자동으로 다음 프로바이더로 전환하는 폴백 로직 구현
- [ ] 4-3-5. 사용 중인 RPC URL을 로그에 기록 (추적 가능성)

### 4-4. RPC 응답 시간 타임아웃 설정
- [ ] 4-4-1. `Web3j` HTTP 클라이언트 커넥션 타임아웃 설정 (현재 기본값 사용 중, 30s 명시 설정)
- [ ] 4-4-2. `Web3j` 읽기 타임아웃 설정 (30s)
- [ ] 4-4-3. `ConfirmationTracker` 폴링 간격과 타임아웃을 `application.yaml`에서 설정 가능하도록 변경
- [ ] 4-4-4. 타임아웃 설정값을 환경변수로 오버라이드 가능하도록 처리

---

## 5. 🔴 확인 추적 (Confirmation Tracking) — CRITICAL

> 서버 재시작 시 추적 중인 TX가 전부 소실됩니다. 운영 배포 차단 수준으로 격상합니다.

### 5-1. 설정 가변화
- [ ] 5-1-1. `ConfirmationTracker`의 `MAX_TRIES`(60), `POLL_INTERVAL_MS`(2000) 하드코딩 제거
- [ ] 5-1-2. `application.yaml`에 `custody.confirmation-tracker.max-tries`, `custody.confirmation-tracker.poll-interval-ms` 추가
- [ ] 5-1-3. `ConfirmationTracker`에 `@ConfigurationProperties`로 위 설정값 주입
- [ ] 5-1-4. 환경변수 오버라이드 키 추가: `CUSTODY_CONFIRMATION_TRACKER_MAX_TRIES`, `CUSTODY_CONFIRMATION_TRACKER_POLL_INTERVAL_MS`

### 5-2. 확정(Finalization) 블록 수 확인 로직 추가
- [ ] 5-2-1. `application.yaml`에 `custody.confirmation-tracker.finalization-block-count` 추가 (예: Ethereum mainnet = 64, Sepolia = 3)
- [ ] 5-2-2. `ConfirmationTracker`에서 receipt 수신 후 현재 블록 번호 조회 (이미 `getBlockNumber()` 메서드 있음)
- [ ] 5-2-3. `(현재 블록번호 - receipt.blockNumber) >= finalizationBlockCount` 조건 충족 시 W7→W8 전이 로직 구현
- [ ] 5-2-4. 아직 finalization 미달인 TX는 pending queue에 유지하며 재폴링
- [ ] 5-2-5. 최대 확정 대기 시간(`finalization-timeout-minutes`) 초과 시 알림 발생 처리

### 5-3. 서버 재시작 후 미완료 TX 재추적
- [ ] 5-3-1. `CustodyApplication` 시작 시 `W6_BROADCASTED` 상태인 `Withdrawal` 목록 조회
- [ ] 5-3-2. 각 미완료 TX를 `ConfirmationTracker`에 재등록하는 `@PostConstruct` 메서드 추가
- [ ] 5-3-3. 재등록 시 중복 추적 방지: `ConfirmationTracker` 내부 추적 Set에 이미 있는 경우 스킵
- [ ] 5-3-4. 재시작 후 재추적 건수를 로그로 기록

### 5-4. Mock 어댑터에서의 자동 확인
- [ ] 5-4-1. `EvmMockAdapter`에서 broadcast 후 일정 지연(예: 500ms) 뒤 자동으로 W7→W8→W10 전이하는 옵션 추가
- [ ] 5-4-2. `application.yaml`에 `custody.mock.auto-confirm-delay-ms` 설정 추가
- [ ] 5-4-3. `LabScenariosIntegrationTest`에 자동 확인 시나리오 테스트 추가

---

## 6. 🟠 오류 처리 및 복원력 (Error Handling & Resilience) — HIGH

### 6-1. 글로벌 예외 처리 보강
- [ ] 6-1-1. `GlobalExceptionHandler`에 `DataAccessException` 처리 추가 (DB 오류 → 503)
- [ ] 6-1-2. `GlobalExceptionHandler`에 `TransactionSystemException` 처리 추가
- [x] 6-1-3. `GlobalExceptionHandler`에 `HttpMessageNotReadableException` 처리 추가 (잘못된 JSON → 400) ✅
- [ ] 6-1-4. `GlobalExceptionHandler`에 `MethodArgumentNotValidException` 처리 추가 (Bean Validation → 400, 필드별 에러 목록 포함)
- [ ] 6-1-5. `GlobalExceptionHandler`에 `NoHandlerFoundException` 처리 추가 (404)
- [ ] 6-1-6. 에러 응답 표준화: `{ errorCode, message, correlationId, timestamp }` 구조 확정 후 모든 예외 핸들러에 일관 적용

### 6-2. 트랜잭션 일관성 보장
- [ ] 6-2-1. `WithdrawalService.createAndBroadcast()`에서 브로드캐스트 성공 후 DB 저장 실패 시 TX가 mempool에 남는 시나리오 분석 및 주석 추가
- [ ] 6-2-2. 브로드캐스트 후 DB 저장 실패 시 `outbox_events` 테이블에 보상(compensation) 이벤트 기록하는 로직 추가
- [ ] 6-2-3. `LedgerService.reserve()`가 실패하면 W3 전이 롤백되는지 트랜잭션 경계 확인
- [ ] 6-2-4. `LedgerService.settle()` + W9→W10 전이가 동일 트랜잭션 안에 있는지 확인

### 6-3. Outbox 패턴 (기본 구현) — ⚠️ CRITICAL 격상 권고
- [ ] 6-3-1. `OutboxEvent` JPA 엔티티 클래스 작성 (`id`, `aggregateType`, `aggregateId`, `eventType`, `payload`(JSONB), `published`, `createdAt`)
- [ ] 6-3-2. `OutboxEventRepository` 작성
- [ ] 6-3-3. `WithdrawalService`의 주요 상태 전이 시 Outbox 이벤트 기록 (같은 트랜잭션 내)
- [ ] 6-3-4. `OutboxPublisher` 스케줄러 작성: unpublished 이벤트를 주기적으로 조회 후 로그에 출력 (실제 Kafka 연동은 Phase 3)
- [ ] 6-3-5. `outbox_events.published = true` 처리로 중복 발행 방지

---

## 7. 🟠 데이터베이스 (Database) — HIGH

### 7-1. PostgreSQL 마이그레이션 검증
- [ ] 7-1-1. `V1__operational_schema_postgresql.sql`에서 현재 JPA 엔티티와 컬럼명/타입이 일치하는지 전수 확인
- [ ] 7-1-2. `V2__align_schema_with_jpa_entities.sql`의 `ALTER TABLE` 구문이 V1 이후 올바르게 적용되는지 확인
- [ ] 7-1-3. 현재 미사용 테이블(`policy_decisions`, `approval_tasks`, `approval_decisions`, `policy_change_requests`, `outbox_events`, `rpc_observation_snapshots`) 처리 계획 결정: 유지 또는 별도 마이그레이션으로 정리
- [ ] 7-1-4. `nonce_reservations` 테이블이 Flyway 마이그레이션에 올바르게 포함되어 있는지 확인
- [ ] 7-1-5. Flyway migrate → `\dt` 결과와 JPA 엔티티 목록 수동 대조

### 7-2. 인덱스 최적화
- [ ] 7-2-1. `withdrawals` 테이블: `status` 컬럼 단독 인덱스 추가 (미완료 TX 조회 최적화)
- [ ] 7-2-2. `withdrawals` 테이블: `(status, updated_at)` 복합 인덱스 추가 (정렬 포함 쿼리 최적화)
- [ ] 7-2-3. `tx_attempts` 테이블: `tx_hash` 단독 인덱스 존재 여부 확인 (ConfirmationTracker 조회용)
- [ ] 7-2-4. `whitelist_addresses` 테이블: `(status, active_after)` 복합 인덱스 추가 (스케줄러 조회 최적화)
- [ ] 7-2-5. `ledger_entries` 테이블: `(withdrawal_id, type)` 복합 인덱스 추가
- [ ] 7-2-6. EXPLAIN ANALYZE로 주요 쿼리 실행 계획 검증 후 추가 인덱스 여부 결정

### 7-3. 커넥션 풀 설정 (HikariCP)
- [ ] 7-3-1. `application-postgres.yaml`에 HikariCP 설정 추가: `maximum-pool-size`, `minimum-idle`, `connection-timeout`, `idle-timeout`, `max-lifetime`
- [ ] 7-3-2. 개발/스테이징/운영 환경별 커넥션 풀 크기 기준값 문서화
- [ ] 7-3-3. `custody.hikari.*` 환경변수 오버라이드 지원 추가

### 7-4. DB 백업 및 복구 전략 (문서화)
- [ ] 7-4-1. PostgreSQL WAL 기반 PITR(Point-In-Time Recovery) 설정 방법 문서화 (`docs/operations/db-backup.md`)
- [ ] 7-4-2. 일일 전체 백업 스크립트 작성 (`pg_dump` 기반)
- [ ] 7-4-3. `docker-compose.yml`에 PostgreSQL 볼륨 영구 마운트 설정 확인
- [ ] 7-4-4. 복구 테스트 절차 문서화

---

## 8. 🟠 로깅 및 추적성 (Logging & Traceability) — HIGH

### 8-1. 구조화 로그 (Structured Logging) 완성
- [ ] 8-1-1. 모든 컨트롤러/서비스의 로그가 `event=... key=value` 형식을 따르는지 전수 확인
- [x] 8-1-2. `ConfirmationTracker` 비동기 스레드에서 MDC `correlationId` 전파 확인 (부분 구현됨 — 전파 로직 존재) ✅
- [ ] 8-1-3. `@Scheduled` 스케줄러 메서드에서 MDC `correlationId` 자동 생성 및 로깅 추가
- [ ] 8-1-4. 스케줄러 실행마다 `scheduler=WhitelistScheduler event=promoteHoldingToActive promoted=N` 형식 로그 추가
- [ ] 8-1-5. `logback-spring.xml`에서 `production` 프로파일일 때 JSON 형식으로만 출력하도록 설정

### 8-2. 분산 추적 (Distributed Tracing) 준비
- [ ] 8-2-1. `micrometer-tracing-bridge-otel` 의존성 추가
- [ ] 8-2-2. `opentelemetry-exporter-otlp` 의존성 추가
- [ ] 8-2-3. `application.yaml`에 OTLP exporter 엔드포인트 설정 (`management.tracing.enabled=true`)
- [ ] 8-2-4. MDC의 `correlationId`와 OTel `traceId`를 로그에 함께 출력하도록 `logback-spring.xml` 수정
- [ ] 8-2-5. 비동기 스레드(`ConfirmationTracker`, `@Scheduled`)에서 Span 전파 확인

### 8-3. 감사 로그 강화
- [ ] 8-3-1. 화이트리스트 변경 이력 테이블 `whitelist_audit_log` 생성 (마이그레이션 추가)
- [ ] 8-3-2. `WhitelistAuditLog` JPA 엔티티 작성 (`id`, `whitelistAddressId`, `action`, `actorId`, `previousStatus`, `newStatus`, `timestamp`)
- [ ] 8-3-3. `WhitelistService.approve()`, `revoke()`, `activate()` 호출 시 감사 로그 기록
- [ ] 8-3-4. `GET /whitelist/{id}/audit` 엔드포인트 추가 (감사 이력 조회)
- [ ] 8-3-5. 정책 변경 감사 로그: `PolicyEngine` 규칙 변경 시 `policy_change_requests` 테이블 기록 (현재 테이블은 있으나 미사용)

---

## 9. 🟠 테스트 커버리지 (Test Coverage) — HIGH

### 9-1. 단위 테스트 추가
- [x] 9-1-1. `AmountLimitPolicyRuleTest`: 경계값(max-amount 정확히 일치, 초과, 미만) 케이스 테스트 ✅ (`PolicyRuleUnitTest`로 커버)
- [ ] 9-1-2. `ToAddressWhitelistPolicyRuleTest`: ACTIVE/HOLDING/REGISTERED/REVOKED/비존재 주소별 케이스
- [x] 9-1-3. `PolicyEngineTest`: 두 규칙 모두 실패 시 첫 번째 규칙만 기록되는지 확인 (fail-fast 여부) ✅ (`PolicyRuleUnitTest`로 커버)
- [x] 9-1-4. `NonceAllocatorTest`: 동시 예약 시 중복 넌스 발급 없음 검증 (스레드 안전성) ✅
- [x] 9-1-5. `LedgerServiceTest`: RESERVE 후 SETTLE 순서 보장, 이중 SETTLE 방지 테스트 ✅
- [x] 9-1-6. `WhitelistServiceTest`: 각 상태 전이 성공/실패 케이스 (REVOKED 상태에서 approve 시도 등) ✅
- [x] 9-1-7. `ConfirmationTrackerTest`: 타임아웃 발생 시 `FAILED_TIMEOUT` 전이 확인 ✅
- [x] 9-1-8. `RetryReplaceServiceTest`: retry 후 attempt 수 2개, canonical 교체 확인 ✅
- [x] 9-1-9. `RetryReplaceServiceTest`: replace 후 동일 nonce, 더 높은 fee, canonical 교체 확인 ✅

### 9-2. 통합 테스트 보강
- [x] 9-2-1. `WithdrawalServiceIdempotencyTest`에 경쟁 조건(Race Condition) 테스트 추가: 동일 키로 병렬 10개 요청 시 1개만 생성 ✅
- [ ] 9-2-2. 상태머신 전이 불변성 테스트: `W10_COMPLETED` 이후 `POST /retry` 시 적절한 에러 반환 확인
- [ ] 9-2-3. 폴리시 감사 로그 무결성 테스트: 거절 시 `policy_audit_logs`에 레코드 1개만 존재하는지 확인
- [x] 9-2-4. 화이트리스트 hold 만료 스케줄러 통합 테스트: `activeAfter`를 과거 시간으로 설정 후 스케줄러 수동 호출 → ACTIVE 전이 확인 ✅ (`WhitelistWorkflowIntegrationTest`로 커버)
- [x] 9-2-5. 멱등성 충돌 통합 테스트: 동일 키 + 다른 body → 409 응답 확인 ✅ (`WithdrawalControllerIntegrationTest`로 커버)
- [ ] 9-2-6. PostgreSQL 프로파일 통합 테스트: Testcontainers 기반 PostgreSQL로 마이그레이션 + CRUD 전체 흐름 테스트

### 9-3. 성능 및 부하 테스트
- [ ] 9-3-1. JMeter 또는 Gatling 스크립트 작성: `POST /withdrawals` 100 RPS 지속 부하 테스트
- [ ] 9-3-2. 동시 멱등성 키 충돌 부하 테스트: 1000개 동일 키 동시 요청 → DB 유니크 제약 + 락 동작 확인
- [ ] 9-3-3. ConfirmationTracker 동시 100개 TX 추적 시 메모리·CPU 사용량 측정
- [ ] 9-3-4. 부하 테스트 결과를 기준값(Baseline)으로 문서화

### 9-4. 테스트 코드 품질
- [ ] 9-4-1. JaCoCo 플러그인 추가 (`build.gradle`), 라인 커버리지 60% 이상 목표 설정
- [ ] 9-4-2. `@SpringBootTest` 사용 통합 테스트를 `@DataJpaTest`/`@WebMvcTest`로 분리하여 테스트 속도 개선
- [ ] 9-4-3. 공통 테스트 픽스처(Fixture) 클래스 추출: 반복되는 `CreateWithdrawalRequest` 빌더 코드 통합

---

## 10. 🟡 승인 워크플로 (Approval Workflow) — MEDIUM

### 10-1. ApprovalService 실제 구현
- [ ] 10-1-1. `ApprovalTask` JPA 엔티티 작성 (`id`, `withdrawalId`, `requiredApprovals`, `approvedCount`, `status`, `riskTier`)
- [ ] 10-1-2. `ApprovalDecision` JPA 엔티티 작성 (`id`, `taskId`, `approverId`, `decision`, `reason`, `createdAt`)
- [ ] 10-1-3. `ApprovalTaskRepository`, `ApprovalDecisionRepository` 작성
- [ ] 10-1-4. `ApprovalService.createTask(withdrawal)` 구현: 출금 금액/위험 등급 기반으로 `requiredApprovals` 결정
- [ ] 10-1-5. `ApprovalService.approve(taskId, approverId, reason)` 구현: `approvedCount += 1`, 충족 시 `APPROVED` 전이
- [ ] 10-1-6. `ApprovalService.reject(taskId, approverId, reason)` 구현: `REJECTED` 전이 → W0_POLICY_REJECTED
- [ ] 10-1-7. `WithdrawalService`에서 W2_APPROVAL_PENDING 상태 실제 사용: 고금액 출금 시 승인 대기 상태로 저장

### 10-2. 4-Eyes (2인 이상 승인) 정책
- [ ] 10-2-1. `application.yaml`에 `custody.approval.high-risk-threshold` 설정 추가 (예: 1.0 ETH 이상이면 2인 승인)
- [ ] 10-2-2. `ApprovalService`에서 금액 기준으로 `requiredApprovals` 동적 결정 로직 구현
- [ ] 10-2-3. `POST /withdrawals/{id}/approve` API 엔드포인트 추가 (APPROVER 역할 필요)
- [ ] 10-2-4. 4-eyes 통합 테스트: 첫 번째 승인 후에도 W2_APPROVAL_PENDING 유지, 두 번째 승인 후 W3 전이 확인

---

## 11. 🟡 가스 가격 오라클 (Gas Price Oracle) — MEDIUM

### 11-1. 동적 가스 가격 조회
- [ ] 11-1-1. `EvmRpcAdapter`에 `getLatestBaseFee()` 메서드 추가 (`eth_getBlockByNumber("latest")` 호출)
- [ ] 11-1-2. `EvmRpcAdapter`에 `getFeeHistory(blocks, percentile)` 메서드 추가 (`eth_feeHistory` 호출)
- [ ] 11-1-3. `EvmRpcAdapter.broadcast()`에서 하드코딩된 `DEFAULT_MAX_PRIORITY_FEE`, `DEFAULT_MAX_FEE` 제거
- [ ] 11-1-4. 새 가스 계산 로직: `maxPriorityFeePerGas = feeHistory(10th percentile)`, `maxFeePerGas = baseFee * 2 + maxPriorityFeePerGas`
- [ ] 11-1-5. 가스 가격 캐싱: 동일 블록 내 반복 조회 방지 (`Caffeine` 또는 `ConcurrentHashMap` 기반, TTL 12s)

### 11-2. Replace 시 수수료 범프 정책 개선
- [ ] 11-2-1. 현재 `RetryReplaceService`의 fee bump 비율(+10% 하드코딩 여부 확인) 검토
- [ ] 11-2-2. `application.yaml`에 `custody.evm.fee-bump-percentage` 설정 추가 (기본값 110%)
- [ ] 11-2-3. 네트워크 혼잡도 기반 동적 bump 비율 결정 로직 추가 (선택적)

---

## 12. 🟡 멀티체인 지원 (Multi-Chain Support) — MEDIUM

### 12-1. BFT 어댑터 완성
- [ ] 12-1-1. `BftMockAdapter`에 `getTransactionReceipt(txHash)` 구현 (현재 미구현)
- [ ] 12-1-2. `BftMockAdapter`에 `getPendingNonce(address)` 구현
- [ ] 12-1-3. `ChainAdapter` 인터페이스에 `getTransactionReceipt()` 메서드 추가 (현재 EVM-only)
- [ ] 12-1-4. `ConfirmationTracker`에서 `instanceof EvmRpcAdapter` 체크 제거 → 인터페이스 메서드로 통일
- [ ] 12-1-5. BFT 어댑터 통합 테스트 작성

### 12-2. 체인 설정 다형성
- [ ] 12-2-1. `ChainType`별 확정(Finalization) 블록 수 설정 분리: `custody.evm.finalization-blocks`, `custody.bft.finalization-blocks`
- [ ] 12-2-2. `ChainAdapterRouter`에서 설정 기반 어댑터 선택 로직 확장

---

## 13. 🟡 API 문서화 (API Documentation) — MEDIUM

### 13-1. OpenAPI / Swagger 연동
- [ ] 13-1-1. `springdoc-openapi-starter-webmvc-ui` 의존성 추가 (`build.gradle`)
- [ ] 13-1-2. `CustodyApplication`에 `@OpenAPIDefinition` 추가 (title, version, description)
- [ ] 13-1-3. 각 컨트롤러 메서드에 `@Operation`, `@ApiResponse` 어노테이션 추가
- [ ] 13-1-4. `CreateWithdrawalRequest` 필드에 `@Schema(description=..., example=...)` 추가
- [ ] 13-1-5. `production` 프로파일에서 Swagger UI 비활성화 옵션 추가

### 13-2. README 보강
- [ ] 13-2-1. `README.md` 섹션 14(PostgreSQL)에 `docker compose up -d` 이후 실제 연결 검증 순서 상세화
- [ ] 13-2-2. 환경변수 전체 목록 표 (`README.md`)에 새로 추가된 설정값 업데이트
- [ ] 13-2-3. `docs/architecture/` 폴더 생성 후 아키텍처 다이어그램(상태머신, 시퀀스 다이어그램) 추가
- [ ] 13-2-4. 운영 플레이북 문서 작성: 장애 시 대응 절차 (`docs/operations/runbook.md`)

---

## 14. 🟡 배포 및 운영 자동화 (Deployment & Operations) — MEDIUM

### 14-1. Docker 이미지 최적화
- [ ] 14-1-1. `custody/Dockerfile` 작성 (멀티 스테이지 빌드: `./gradlew bootJar` → 최종 JRE 이미지)
- [ ] 14-1-2. 최종 이미지에 불필요한 레이어 제거 (Gradle 캐시 별도 레이어)
- [ ] 14-1-3. `docker-compose.yml`에 custody 앱 서비스 추가 (postgres + custody + prometheus + grafana)
- [ ] 14-1-4. `docker-compose.yml`에 `healthcheck` 설정 추가 (custody: `/actuator/health`, postgres: `pg_isready`)

### 14-2. CI/CD 기초
- [ ] 14-2-1. `.github/workflows/build.yml` 작성: PR 시 `./gradlew test` + `./gradlew build` 실행
- [ ] 14-2-2. `.github/workflows/build.yml`에 JaCoCo 커버리지 리포트 업로드 추가
- [ ] 14-2-3. 빌드 성공 시 Docker Hub 또는 GHCR에 이미지 푸시 단계 추가

### 14-3. 설정 관리
- [ ] 14-3-1. `application-production.yaml` 프로파일 파일 작성 (H2 제거, Flyway 활성화, H2 콘솔 비활성화, 구조화 로그 활성화)
- [ ] 14-3-2. 환경별 설정 오버라이드 전략 문서화 (`ENV` > `application-{profile}.yaml` > `application.yaml` 우선순위 명시)
- [ ] 14-3-3. 민감 설정값(`private-key`, `db-password`)을 외부 Secret Store에서 주입하는 방법 문서화

---

## 15. 🟢 장기 개선 과제 (Long-term Improvements) — LOW

### 15-1. MEV 방어 및 프라이버시
- [ ] 15-1-1. Flashbots Protect RPC 연동 옵션 검토 (`CUSTODY_EVM_RPC_URL`을 Flashbots endpoint로 교체)
- [ ] 15-1-2. Private mempool 사용 여부 결정 및 아키텍처 반영

### 15-2. HSM / Cold Wallet 연동
- [ ] 15-2-1. `Signer` 인터페이스를 HSM(Hardware Security Module) 구현체로 교체하는 추상화 계획 수립
- [ ] 15-2-2. AWS CloudHSM 또는 Azure Dedicated HSM 연동 PoC 진행

### 15-3. 샤딩 및 수평 확장
- [ ] 15-3-1. `NonceAllocator`를 분산 환경에서 안전하게 사용하기 위한 Redis 기반 분산 락 도입 검토
- [ ] 15-3-2. `ConfirmationTracker`의 작업 분산: 여러 인스턴스가 동일 TX를 중복 추적하지 않도록 DB 기반 락 설계

### 15-4. 보안 감사
- [ ] 15-4-1. 제3자 보안 감사(Penetration Testing) 계획 수립
- [ ] 15-4-2. OWASP Top 10 체크리스트 기반 자체 점검 수행
- [ ] 15-4-3. 의존성 취약점 스캔 자동화: GitHub Dependabot 또는 OWASP Dependency-Check 연동

---

---

## 16. 🟢 PDS 통합 (pds-core 특허 B-1 + B-2) — LOW (Phase 4+)

> MVP 단계에서는 코드 구현 없이 **구조 예약만** 한다.
> 실제 통합은 파일럿 고객 확보 이후 Phase 2~3에서 점진적으로 활성화한다.
> 관련 설계: `f:\Workplace\custody\custody track\Custody_SaaS_Product_Design.md` 섹션 13
> 관련 레포: `f:\Workplace\pds-core`

### 16-1. MVP 단계 구조 예약 (코드 없이 스키마/인터페이스만)

- [ ] 16-1-1. `V_pds__add_tenant_pds_records.sql` Flyway 마이그레이션 파일 추가 (빈 테이블 예약)
  ```sql
  CREATE TABLE tenant_pds_records (
    id         UUID PRIMARY KEY,
    tenant_id  UUID NOT NULL,
    pds_type   TEXT NOT NULL,  -- SIGNER_KEY | EMERGENCY_ACCESS | OPERATOR_CREDENTIAL
    pds_data   JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
  );
  ```
- [ ] 16-1-2. `policy_audit_logs` 테이블에 `previous_hash TEXT`, `current_hash TEXT` 컬럼 예약 (null 허용, 미사용)
- [ ] 16-1-3. `SignerConnector` 인터페이스에 PDS 훅 메서드 시그니처 예약 (default 구현 = no-op)
  ```java
  default Optional<String> getRecoveryKeyPdsId() { return Optional.empty(); }
  ```
- [ ] 16-1-4. `application.yaml`에 pds feature flag 섹션 추가 (전부 false로 초기화)
  ```yaml
  pds:
    enabled: false
    endpoint: http://pds-core:3100
    features:
      signer-key-pds: false
      policy-audit-chain: false
      emergency-access: false
  ```
- [ ] 16-1-5. `PdsProperties` `@ConfigurationProperties` 클래스 작성 (빈 껍데기, 이후 확장)

### 16-2. Phase 2: Signer 복구 키 PDS화 (특허 B-2 적용)

> 선행 조건: MVP 완료 + 파일럿 고객 1곳 이상 확보

- [ ] 16-2-1. `pds-core` Docker 서비스를 `docker-compose.yml`에 추가 (`:3100`)
- [ ] 16-2-2. `PdsCoreClient` HTTP 클라이언트 클래스 작성 (RestTemplate 또는 WebClient)
  - `create(seed, deviceInput, userInput, mediaInput)` → `POST /pds/create`
  - `recover(pds, deviceInput, userInput, mediaInput)` → `POST /pds/recover`
  - `destroy(pds, reason)` → `POST /pds/destroy`
  - `verify(pds)` → `POST /pds/verify`
- [ ] 16-2-3. `PdsRecord` JPA 엔티티 작성 — `tenant_pds_records` 테이블 매핑
- [ ] 16-2-4. `PdsRecordRepository` 작성
- [ ] 16-2-5. `PdsAwareSignerConnector` 구현체 작성 — `SignerConnector` 구현 + pds-core 연동
- [ ] 16-2-6. Signer 키 등록 API `POST /internal/pds/signer-key` 추가 (admin only)
- [ ] 16-2-7. 비상 복구 API `POST /internal/pds/recover` 추가 (3-Factor 입력, admin only)
- [ ] 16-2-8. 통합 테스트: createPds → processRecovery → 복호화된 seed 검증 (pds-core 서버 모킹)

### 16-3. Phase 3: 정책 감사 해시 체인 (특허 B-1 적용)

> 선행 조건: Phase 2 완료 + 감사 요구 고객 확보

- [ ] 16-3-1. `PdsAuditChain` 서비스 작성 — 정책 변경 이벤트를 PDS metadata로 기록
- [ ] 16-3-2. `PolicyEngine` 규칙 변경 시 `PdsAuditChain.record()` 호출 추가
- [ ] 16-3-3. `policy_audit_logs.current_hash` 컬럼 채우는 마이그레이션 작성
- [ ] 16-3-4. `GET /audit/policy/verify` 엔드포인트 추가 — 전체 해시 체인 무결성 검증
- [ ] 16-3-5. 해시 체인 변조 탐지 시 알림 발생 (기존 알림 채널 재사용)

### 16-4. Phase 4: 운영자 비상 접근 키 (B-1 + B-2 결합)

- [ ] 16-4-1. 비상 접근 PDS 발급 워크플로우 설계 (ONE_TIME, expiresAt 설정 필수)
- [ ] 16-4-2. 비상 접근 PDS 사용 후 자동 파기 확인 로직 추가
- [ ] 16-4-3. tenant별 비상 접근 이력 감사 로그 추가
- [ ] 16-4-4. pds-core 라이선스 사업 검토 — 타 custody 벤더 공급 가능성 평가

---

## 우선순위 요약 (Summary)

| 순서 | 작업 영역 | 아이템 수 | 예상 기간 |
|------|-----------|-----------|-----------|
| 0 | 🔴 JPA 엔티티 사전 과제 | 8개 | 0.5주 |
| 1 | 🔴 넌스 관리 | 19개 | **2주** |
| 2 | 🔴 보안 | 21개 | 1주 |
| 3 | 🔴 모니터링/메트릭 | 17개 | 1주 |
| 4 | 🟠 RPC 복원력 | 16개 | 1주 |
| 5 | 🔴 확인 추적 | 15개 | 1주 |
| 6 | 🟠 오류 처리 | 15개 | 1주 |
| 7 | 🟠 데이터베이스 | 17개 | 1주 |
| 8 | 🟠 로깅/추적성 | 17개 | 1주 |
| 9 | 🟠 테스트 커버리지 | 19개 | 2주 |
| 10 | 🟡 승인 워크플로 | 11개 | 1주 |
| 11 | 🟡 가스 오라클 | 10개 | 0.5주 |
| 12 | 🟡 멀티체인 | 7개 | 1주 |
| 13 | 🟡 API 문서화 | 9개 | 0.5주 |
| 14 | 🟡 배포 자동화 | 9개 | 0.5주 |
| 15 | 🟢 장기 개선 | 11개 | 미정 |
| 16 | 🟢 PDS 통합 (특허 B-1+B-2) | 22개 | Phase 4+ |
| **합계** | | **~243개** | **약 16~20주 + Phase 4** |

> **Phase 0 (즉시, 1주):** 섹션 0 (JPA 엔티티 사전 과제 — 다른 섹션 차단 해제)
> **Phase 1 (1~5주):** 섹션 1·2·3·5 (넌스·보안·모니터링·확인추적 — 전부 CRITICAL)
> **Phase 2 (6~10주):** 섹션 4·6·7·8·9 (RPC 복원력·오류·DB·로깅·테스트)
> **Phase 3 (11~15주):** 섹션 10~14 (승인·가스·멀티체인·문서·배포)
> **Phase 4 (장기):** 섹션 15 (MEV·HSM·샤딩·보안 감사) + 섹션 16 (PDS 통합 — 파일럿 이후 점진 적용)

> **이미 완료된 항목 (레포 교차검증 결과, 2026-04-05):**
> - 1-1-4, 1-1-5 (nonce_reservations 마이그레이션 인덱스·제약)
> - 1-4-3 (getPendingNonce 퍼블릭 메서드)
> - 2-1-1, 2-1-5 (환경변수 처리·RpcModeStartupGuard)
> - 2-2-2 (isValidEvmAddress 유틸리티)
> - 6-1-3 (HttpMessageNotReadableException 처리)
> - 8-1-2 (MDC correlationId 부분 구현)
>
> **추가 완료 항목 (테스트 보강 반영, 2026-04-08):**
> - 9-1-1, 9-1-3 (`PolicyRuleUnitTest`로 AmountLimit 경계값 및 PolicyEngine fail-fast 검증)
> - 9-1-4 (`NonceAllocatorTest` 동시성 검증)
> - 9-1-5 (`LedgerServiceTest`로 RESERVE 선행 및 중복 SETTLE 방지 검증)
> - 9-1-6 (`WhitelistServiceTest`로 상태 전이 실패/정규화/default 처리 검증)
> - 9-1-7 (`ConfirmationTrackerTest` timeout → `FAILED_TIMEOUT` 전이 검증)
> - 9-1-8, 9-1-9 (`RetryReplaceServiceTest`로 retry/replace canonical 전환 및 nonce/fee 검증)
> - 9-2-1 (`WithdrawalServiceIdempotencyTest` 병렬 race condition 검증)
> - 9-2-4 (`WhitelistWorkflowIntegrationTest`로 hold 만료 스케줄러 수동 호출 검증)
> - 9-2-5 (`WithdrawalControllerIntegrationTest`로 동일 키 + 다른 body → 409 검증)
>
> **추가 완료 항목 (nonce reservation 구현 반영, 2026-04-08):**
> - 1-2-1, 1-2-2, 1-2-3 (`NonceAllocator` DB 기반 reserve/commit/release 구현)
> - 1-2-5 (`WithdrawalService.createAndBroadcast()`를 `reserve → commit` 흐름으로 교체)
> - 1-2-6 (`RetryReplaceService.retry()`에서 새 nonce 예약 후 이전 예약 release 처리)
> - 1-2-7 (`RetryReplaceService.replace()`에서 기존 committed reservation 재사용/rebind 처리)
> - 1-2-4는 현재 DB UNIQUE 제약 + `DataIntegrityViolationException` 재시도로 부분 대응 중이며, `ON CONFLICT DO NOTHING` / `SELECT FOR UPDATE` 수준의 명시적 구현은 미완료
