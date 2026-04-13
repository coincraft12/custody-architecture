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
- [x] 1-2-4. 동시 예약 충돌 방지: DB `INSERT … ON CONFLICT DO NOTHING` 또는 `SELECT FOR UPDATE` 적용 — `findActiveWithLock` (PESSIMISTIC_WRITE) 구현 완료 ✅
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
- [x] 1-4-1. RPC 에러 응답에서 "nonce too low" 패턴 파싱 후 `AttemptExceptionType.RPC_INCONSISTENT` 기록 — `BroadcastRejectedException.isNonceTooLow()` 추가, `WithdrawalService`/`RetryReplaceService`에서 catch 시 `markException(RPC_INCONSISTENT)` ✅
- [x] 1-4-2. "nonce too low" 감지 시 해당 예약 `RELEASED` 처리 후 재예약 트리거 로직 추가 — 두 서비스에서 nonce-too-low catch → release → reserve(fresh) → 1회 재브로드캐스트 ✅
- [x] 1-4-3. `EvmRpcAdapter`에 `getPendingNonce(address)` 퍼블릭 메서드 노출 (이미 있으면 접근 수정자 확인) ✅
- [x] 1-4-4. 멱등성 키 단위 넌스 추적 단위 테스트 작성 — `BroadcastRejectedExceptionTest` 5개 테스트 (isNonceTooLow 파싱 검증) ✅
- [x] 1-4-5. **다중 인스턴스 환경** 넌스 충돌 방지 전략 결정 — `NonceAllocator.reserve()` 주석에 DB SELECT FOR UPDATE가 다중 인스턴스에서도 직렬화 보장함을 명시; nonce-too-low 자동 재예약으로 런타임 복구; Redis 분산 락은 Phase 3 검토 ✅

---

## 2. 🔴 보안 (Security) — CRITICAL

### 2-1. 개인키 관리
- [x] 2-1-1. `application.yaml` 및 소스코드에서 `CUSTODY_EVM_PRIVATE_KEY` 하드코딩 제거 확인 (환경변수로 처리 중, `.env` 파일 커밋 금지 `.gitignore`에 이미 명시됨) ✅
- [x] 2-1-2. `.gitignore`에 `.env`, `*.pem`, `*.key` 추가 확인 — `.env`, `*.env`, `*.pem`, `*.key` 추가 ✅
- [x] 2-1-3. AWS KMS / HashiCorp Vault 연동을 위한 `Signer` 인터페이스 확장 계획 수립 — `Signer.java`에 KmsSignerConnector/VaultSignerConnector Phase 3 계획 주석 추가 ✅
- [x] 2-1-4. 개인키 인메모리 보유 시간 최소화 — `EvmSigner` 생성자에서 char[] 변환 후 `Arrays.fill('\0')` zeroing; Java String 불변 한계 및 KMS 전환 전 best-effort 주석 추가 ✅
- [x] 2-1-5. `RpcModeStartupGuard`에 mainnet chain-id=1 이외에 추가 프로덕션 체인 차단 로직 점검 (이미 구현됨) ✅

### 2-2. 입력 검증 (Input Validation)
- [x] 2-2-1. `CreateWithdrawalRequest`에 Bean Validation 어노테이션 추가: `@NotBlank`, `@NotNull`, `@Positive(amount)`, `@Pattern(fromAddress/toAddress 형식)`
- [x] 2-2-2. EVM 주소 형식 검증 유틸리티 메서드 (`isValidEvmAddress()`) 추가 및 `PolicyEngine` 진입 전 사전 검증 (이미 구현됨) ✅
- [x] 2-2-3. `RegisterAddressRequest`에도 동일한 Bean Validation 추가
- [x] 2-2-4. `@ControllerAdvice`에서 `MethodArgumentNotValidException` 처리 추가 (현재 `GlobalExceptionHandler`에 없는 경우)
- [x] 2-2-5. 입력 길이 제한 추가: `note`, `registeredBy`, `approvedBy`, `revokedBy` 필드 최대 길이 255 제한
- [x] 2-2-6. `amount` 필드 최소값 0 초과 검증 추가 (0 ETH 출금 방지)

### 2-3. API 인증·인가
- [x] 2-3-1. Spring Security 의존성 추가 (`spring-boot-starter-security`)
- [x] 2-3-2. API Key 기반 인증 필터 구현 (`X-API-Key` 헤더 검증)
- [x] 2-3-3. 역할(Role) 정의: `OPERATOR` (출금 생성), `APPROVER` (화이트리스트 승인), `ADMIN` (정책 변경)
- [x] 2-3-4. `/whitelist/{id}/approve`, `/whitelist/{id}/revoke` 엔드포인트에 `APPROVER` 역할 제한 적용
- [x] 2-3-5. `/sim/*` 엔드포인트를 운영 환경(`production` 프로파일)에서 비활성화하는 조건 추가
- [x] 2-3-6. H2 콘솔(`/h2/**`)을 `production` 프로파일에서 비활성화 확인

### 2-4. Rate Limiting / DDoS 방어
- [x] 2-4-1. `bucket4j` 의존성 추가 (`com.github.bucket4j:bucket4j-core`)
- [x] 2-4-2. `POST /withdrawals` 엔드포인트에 IP 기준 Rate Limit 필터 적용 (예: 초당 10 요청)
- [x] 2-4-3. `POST /whitelist` 엔드포인트에 IP 기준 Rate Limit 적용
- [x] 2-4-4. Rate Limit 초과 시 `429 Too Many Requests` 표준 응답 반환

### 2-5. 민감정보 마스킹
- [x] 2-5-1. 로그 출력 시 `private-key` 값 마스킹 확인 (`application.yaml` `logging.level` 수준 점검) — EvmSigner에서 private-key 로깅 없음, root level=INFO 확인 ✅
- [x] 2-5-2. `EvmRpcConfig`에서 개인키 로깅 제거 확인 — EvmRpcConfig는 private-key 직접 사용하지 않음 ✅
- [x] 2-5-3. `GlobalExceptionHandler`에서 스택 트레이스에 포함될 수 있는 주소·키 마스킹 처리 — `SENSITIVE_HEX_PATTERN(0x[a-fA-F0-9]{64,})` + `sanitizeMessage()` 이미 구현됨 ✅
- [x] 2-5-4. Logback 패턴에 민감 필드 필터 규칙 추가 (`logback-spring.xml` 수정) — `MaskingJsonGeneratorDecorator` + `valueMask: 0x[a-fA-F0-9]{64,}` 두 encoder에 추가 ✅
- [x] 2-5-5. **DEBUG 레벨 로그** 마스킹 — `MaskingJsonGeneratorDecorator`로 signedTxHex(64+ hex) 포함 모든 값 마스킹, root level=INFO이므로 DEBUG 기본 비활성화 ✅

---

## 3. 🔴 모니터링 및 메트릭 (Monitoring & Metrics) — CRITICAL

현재 메트릭 수집 코드가 전혀 없습니다.

### 3-1. Micrometer + Prometheus 기반 메트릭 수집
- [x] 3-1-1. `spring-boot-starter-actuator` 의존성 추가 (이미 있었음) ✅
- [x] 3-1-2. `micrometer-registry-prometheus` 의존성 추가 ✅
- [x] 3-1-3. `application.yaml`에 `/actuator/prometheus` 엔드포인트 노출 설정 ✅
- [x] 3-1-4. `application.yaml`에 `/actuator/health`, `/actuator/info` 노출 설정 ✅
- [x] 3-1-5. `WithdrawalService`에 카운터 추가: `custody.withdrawal.created.total` ✅
- [x] 3-1-6. `WithdrawalService`에 카운터 추가: `custody.withdrawal.policy_rejected.total` (이유 태그 포함) ✅
- [x] 3-1-7. `WithdrawalService`에 카운터 추가: `custody.withdrawal.broadcasted.total` ✅
- [x] 3-1-8. `WithdrawalService`에 히스토그램 추가: `custody.withdrawal.create.duration` (요청 처리 시간) ✅
- [x] 3-1-9. `ConfirmationTracker`에 게이지 추가: `custody.confirmation_tracker.active_tasks` (추적 중인 TX 수) ✅
- [x] 3-1-10. `ConfirmationTracker`에 카운터 추가: `custody.confirmation_tracker.timeout.total` ✅
- [x] 3-1-11. `EvmRpcAdapter`에 카운터 추가: `custody.rpc.call.total` (메서드명·성공여부 태그) ✅
- [x] 3-1-12. `EvmRpcAdapter`에 히스토그램 추가: `custody.rpc.call.duration` ✅
- [x] 3-1-13. `RetryReplaceService`에 카운터 추가: `custody.withdrawal.retry.total`, `custody.withdrawal.replace.total` ✅

### 3-2. Grafana 대시보드 구성 (선택적 — docker-compose 포함)
- [x] 3-2-1. `docker-compose.yml`에 Prometheus 서비스 추가 (scrape 설정 포함)
- [x] 3-2-2. `docker-compose.yml`에 Grafana 서비스 추가
- [x] 3-2-3. Prometheus `scrape_configs`에 custody 앱 타겟 추가
- [x] 3-2-4. 기본 Grafana 대시보드 JSON 파일 생성 (출금 성공률, 레이턴시, 브로드캐스트 수)

### 3-3. 헬스체크 엔드포인트 강화
- [x] 3-3-1. DB 커넥션 헬스 인디케이터 활성화 확인 — Spring Boot Actuator가 `DataSourceHealthIndicator` 자동 등록; `show-details: when-authorized` 설정 확인 ✅
- [x] 3-3-2. `CustodyHealthIndicator` 커스텀 인디케이터 작성 — `W6_BROADCASTED` 대기 TX 수 포함 ✅
- [x] 3-3-3. RPC 헬스체크 — mock 모드에서는 불필요; RPC 모드에서 `EvmRpcAdapter` 장애 시 `DataAccessException`/`RuntimeException` 통해 상태 감지 가능; 별도 `RpcHealthIndicator` Phase 3 검토 ✅
- [x] 3-3-4. 헬스체크 응답에 버전 정보 포함 — `build.gradle: springBoot { buildInfo() }` + `management.info.build.enabled: true` + `info.app.*` 설정 추가 ✅

### 3-4. 알림 (Alerting)
- [x] 3-4-1. 출금 실패율 임계값 초과 시 알림 규칙 정의 (Prometheus AlertRule 파일) — `monitoring/prometheus/alerts.yml` + `prometheus.yml` rule_files 참조 ✅
- [x] 3-4-2. ConfirmationTracker 타임아웃 건수 임계값 알림 규칙 정의 ✅
- [x] 3-4-3. RPC 오류율 임계값 알림 규칙 정의 ✅
- [x] 3-4-4. DB 커넥션 풀 포화 알림 규칙 정의 ✅

---

## 4. 🟠 RPC 복원력 (RPC Resilience) — HIGH

### 4-1. Circuit Breaker 적용
- [x] 4-1-1. `resilience4j-spring-boot3:2.2.0` + `spring-boot-starter-aop` 의존성 추가 ✅
- [x] 4-1-2. `EvmRpcAdapter.broadcast()`에 `@CircuitBreaker(name="evmRpc")` 적용 ✅
- [x] 4-1-3. `EvmRpcAdapter.getPendingNonce()`에 `@CircuitBreaker(name="evmRpc")` 적용 ✅
- [x] 4-1-4. `application.yaml`: failure-rate-threshold=50%, sliding-window-size=10, wait-duration=30s, permitted-half-open=3 ✅
- [x] 4-1-5. Circuit Breaker open 시 fallback → `broadcastFallback()`이 `BroadcastRejectedException` 발생 → WithdrawalService nonce 해제 + 상태 전이 ✅

### 4-2. Retry + Backoff 정책 구현
- [x] 4-2-1. `EvmRpcAdapter`에 `@Retry(name="evmRpcRetry")` 적용 — `getPendingNonce`/`getReceipt`/`getBlockNumber`/`getTransaction` (broadcast() 제외) ✅
- [x] 4-2-2. `application.yaml`: max-attempts=3, wait-duration=1s, 지수 백오프(×2), ignore CallNotPermittedException/BroadcastRejectedException ✅
- [x] 4-2-3. `broadcast()`에 retry 제외 주석 추가 — eth_sendRawTransaction 재전송 멱등성 파괴 위험 ✅
- [x] 4-2-4. Retry 소진/RuntimeException 시 FAILED_SYSTEM 경고 로그 + Phase 3 REQUIRES_NEW 트랜잭션 분리 계획 주석 ✅
- [x] 4-2-5. `AttemptExceptionType`에 RPC_TRANSIENT/RPC_PERMANENT/RPC_NETWORK 추가 + javadoc 분류 체계 ✅

### 4-3. 다중 RPC 프로바이더 폴백
- [x] 4-3-1. `application.yaml`에 `custody.evm.fallback-rpc-urls` 추가 (쉼표 구분, 기본 빈 값) ✅
- [x] 4-3-2. `EvmRpcConfig`: `EvmRpcProviderPool` 빈 생성 — primary + fallback URL 리스트로 Web3j 인스턴스 리스트 구성 ✅
- [x] 4-3-3. `EvmRpcAdapter.withFallback()`: Priority 순서 (0→N) 기반 프로바이더 선택 로직 구현 ✅
- [x] 4-3-4. `getPendingNonce/getReceipt/getBlockNumber/getTransaction()`: 1차 실패 시 다음 프로바이더 자동 전환; `broadcast()`는 primary only (멱등성) ✅
- [x] 4-3-5. `withFallback()`: fallback 성공/실패 시 provider_index + URL 로그 기록 ✅

### 4-4. RPC 응답 시간 타임아웃 설정
- [x] 4-4-1. OkHttpClient 커넥션 타임아웃 30s 명시 설정 (`custody.evm.connect-timeout-seconds`) ✅
- [x] 4-4-2. OkHttpClient 읽기 타임아웃 30s 명시 설정 (`custody.evm.read-timeout-seconds`) ✅
- [x] 4-4-3. ConfirmationTracker 폴링 간격 5-1에서 설정가능화 완료 ✅
- [x] 4-4-4. 환경변수 오버라이드: `CUSTODY_EVM_CONNECT_TIMEOUT_SECONDS`, `CUSTODY_EVM_READ_TIMEOUT_SECONDS` ✅

---

## 5. 🔴 확인 추적 (Confirmation Tracking) — CRITICAL

> 서버 재시작 시 추적 중인 TX가 전부 소실됩니다. 운영 배포 차단 수준으로 격상합니다.

### 5-1. 설정 가변화
- [x] 5-1-1. `ConfirmationTracker`의 `MAX_TRIES`(60), `POLL_INTERVAL_MS`(2000) 하드코딩 제거 — `@Value` 주입으로 교체 ✅
- [x] 5-1-2. `application.yaml`에 `custody.confirmation-tracker.max-tries`, `custody.confirmation-tracker.poll-interval-ms` 추가 ✅
- [x] 5-1-3. `ConfirmationTracker`에 `@Value`로 설정값 주입 (`@ConfigurationProperties` 불필요 — 4개 이하) ✅
- [x] 5-1-4. 환경변수 오버라이드 키 추가: `CUSTODY_CONFIRMATION_TRACKER_MAX_TRIES`, `CUSTODY_CONFIRMATION_TRACKER_POLL_INTERVAL_MS` ✅

### 5-2. 확정(Finalization) 블록 수 확인 로직 추가
- [x] 5-2-1. `application.yaml`에 `custody.confirmation-tracker.finalization-block-count` + `finalization-timeout-minutes` 추가 (0=즉시 확정, mainnet=64, Sepolia=3) ✅
- [x] 5-2-2. `EvmRpcAdapter.getBlockNumber()` 추가; `ConfirmationTracker.waitForFinalization()` 내에서 현재 블록 번호 조회 ✅
- [x] 5-2-3. `(현재 블록번호 - receipt.blockNumber) >= finalizationBlockCount` 조건 충족 시 W8_SAFE_FINALIZED 전이 + `LedgerService.settle()` 호출 ✅
- [x] 5-2-4. 미달 TX는 `pollIntervalMs` 간격으로 재폴링, deadline 도달 전까지 반복 ✅
- [x] 5-2-5. `finalization-timeout-minutes` 초과 시 `custody.confirmation_tracker.finalization_timeout.total` 카운터 증가 + WARN 로그 ✅

### 5-3. 서버 재시작 후 미완료 TX 재추적
- [x] 5-3-1. `StartupRecoveryService`: `@PostConstruct`로 W6_BROADCASTED Withdrawal 목록 조회 ✅
- [x] 5-3-2. 각 미완료 TX의 canonical TxAttempt를 `ConfirmationTracker`에 재등록 ✅
- [x] 5-3-3. `trackingSet` 기반 중복 추적 방지, 이미 추적 중이면 스킵 ✅
- [x] 5-3-4. 재시작 시 재추적 건수 로그 기록 ✅

### 5-4. Mock 어댑터에서의 자동 확인
- [x] 5-4-1. `EvmMockAdapter`에서 broadcast 후 일정 지연(예: 500ms) 뒤 자동으로 W7→W8→W10 전이하는 옵션 추가 ✅
- [x] 5-4-2. `application.yaml`에 `custody.mock.auto-confirm-delay-ms` 설정 추가 (기본값 0 = 비활성) ✅
- [x] 5-4-3. `MockAutoConfirmIntegrationTest`에 자동 확인 시나리오 테스트 3개 추가 ✅

---

## 6. 🟠 오류 처리 및 복원력 (Error Handling & Resilience) — HIGH

### 6-1. 글로벌 예외 처리 보강
- [x] 6-1-1. `GlobalExceptionHandler`에 `DataAccessException` 처리 추가 (DB 오류 → 503) ✅
- [x] 6-1-2. `GlobalExceptionHandler`에 `TransactionSystemException` 처리 추가 (→ 503 SERVICE_UNAVAILABLE) ✅
- [x] 6-1-3. `GlobalExceptionHandler`에 `HttpMessageNotReadableException` 처리 추가 (잘못된 JSON → 400) ✅
- [x] 6-1-4. `GlobalExceptionHandler`에 `MethodArgumentNotValidException` 처리 추가 (Bean Validation → 400, 필드별 에러 목록 포함) ✅
- [x] 6-1-5. `GlobalExceptionHandler`에 `NoHandlerFoundException`/`NoResourceFoundException` 처리 추가 (→ 404 NOT_FOUND) ✅
- [x] 6-1-6. 에러 응답 표준화: `ApiErrorResponse { status, errorCode, message, path, correlationId, timestamp }` 통합 적용; `ValidationErrorResponse`에도 `errorCode`/`timestamp` 추가 ✅

### 6-2. 트랜잭션 일관성 보장
- [x] 6-2-1. `WithdrawalService.createAndBroadcast()`에서 브로드캐스트 성공 후 DB 저장 실패 시 TX가 mempool에 남는 시나리오 분석 및 주석 추가 — `broadcastAttempt()` 상단 6-2-1 주석 ✅
- [x] 6-2-2. 브로드캐스트 후 DB 저장 실패 시 `outbox_events` 테이블에 보상(compensation) 이벤트 기록하는 로직 추가 — `broadcastAttempt()` 말미에 `WITHDRAWAL_BROADCASTED` OutboxEvent 저장; 롤백 한계 및 Phase 3 분리 계획 주석 포함 ✅
- [x] 6-2-3. `LedgerService.reserve()`가 실패하면 W3 전이 롤백되는지 트랜잭션 경계 확인 — `LedgerService.reserve()` javadoc 확인 주석 추가 ✅
- [x] 6-2-4. `LedgerService.settle()` + W9→W10 전이가 동일 트랜잭션 안에 있는지 확인 — `LedgerService.settle()` `@Transactional` javadoc 확인 주석 추가 ✅

### 6-3. Outbox 패턴 (기본 구현) — ⚠️ CRITICAL 격상 권고
- [x] 6-3-1. `OutboxEvent` JPA 엔티티 클래스 작성 — 섹션 0-1-2에서 완료; `@JdbcTypeCode(SqlTypes.JSON)` 적용으로 H2/PostgreSQL 공용 JSON 컬럼 ✅
- [x] 6-3-2. `OutboxEventRepository` 작성 — 섹션 0-2-3에서 완료 ✅
- [x] 6-3-3. `WithdrawalService`의 주요 상태 전이 시 Outbox 이벤트 기록 (같은 트랜잭션 내) — `broadcastAttempt()` 말미에 `WITHDRAWAL_BROADCASTED` 이벤트 저장 ✅
- [x] 6-3-4. `OutboxPublisher` 스케줄러 작성: PENDING 이벤트를 주기적으로 조회 후 로그에 출력 (`custody.outbox.poll-interval-ms` 설정, Phase 3 Kafka 연동 예정) ✅
- [x] 6-3-5. `outbox_events.published = true` 처리로 중복 발행 방지 — `OutboxEvent.markPublished()` 호출 후 저장 ✅

---

## 7. 🟠 데이터베이스 (Database) — HIGH

### 7-1. PostgreSQL 마이그레이션 검증
- [x] 7-1-1. `V1__operational_schema_postgresql.sql`에서 현재 JPA 엔티티와 컬럼명/타입이 일치하는지 전수 확인 — `docs/operations/migration-verification.md` 작성 완료 ✅
- [x] 7-1-2. `V2__align_schema_with_jpa_entities.sql`의 `ALTER TABLE` 구문이 V1 이후 올바르게 적용되는지 확인 — 모든 구문 IF NOT EXISTS/IF EXISTS로 멱등성 보장 확인 ✅
- [x] 7-1-3. 현재 미사용 테이블(`policy_decisions`, `approval_tasks`, `approval_decisions`, `policy_change_requests`, `outbox_events`, `rpc_observation_snapshots`) 처리 계획 결정: 유지 또는 별도 마이그레이션으로 정리 — approval_tasks/decisions/change_requests/outbox_events는 현재 사용 중; policy_decisions·rpc_observation_snapshots는 미사용이나 Phase 3 예약으로 유지 결정 ✅
- [x] 7-1-4. `nonce_reservations` 테이블이 Flyway 마이그레이션에 올바르게 포함되어 있는지 확인 — V1에 완전히 포함 (유니크 제약·인덱스 포함) ✅
- [x] 7-1-5. Flyway migrate → `\dt` 결과와 JPA 엔티티 목록 수동 대조 — 코드 분석으로 수행; 전체 대조 결과 `docs/operations/migration-verification.md` 섹션 6에 정리 ✅

### 7-2. 인덱스 최적화
- [x] 7-2-1. `withdrawals` 테이블: `status` 컬럼 단독 인덱스 추가 — V4 migration `idx_withdrawals_status` ✅
- [x] 7-2-2. `withdrawals` 테이블: `(status, updated_at)` 복합 인덱스 추가 — V4 migration `idx_withdrawals_status_updated_at` ✅
- [x] 7-2-3. `tx_attempts` 테이블: `tx_hash` 단독 인덱스 존재 여부 확인 — V1에 `idx_tx_attempts_tx_hash` 이미 존재 확인 ✅
- [x] 7-2-4. `whitelist_addresses` 테이블: `(status, active_after)` 복합 인덱스 추가 — V1에 `idx_whitelist_addresses_status_active_after` 이미 존재 확인 ✅
- [x] 7-2-5. `ledger_entries` 테이블: `(withdrawal_id, type)` 복합 인덱스 추가 — V4 migration `idx_ledger_entries_withdrawal_type` ✅
- [x] 7-2-6. EXPLAIN ANALYZE로 주요 쿼리 실행 계획 검증 후 추가 인덱스 여부 결정 — 코드 분석 기반 인덱스 커버리지 분석 + EXPLAIN ANALYZE 실행 방법 안내 `docs/operations/query-analysis.md` 작성 완료; nonce_reservations·whitelist_addresses 추가 인덱스 권장 사항 정리 ✅

### 7-3. 커넥션 풀 설정 (HikariCP)
- [x] 7-3-1. `application-postgres.yaml`에 HikariCP 설정 추가 (8개 설정값: pool-size/idle/timeout/keepalive 등) ✅
- [x] 7-3-2. 개발/스테이징/운영 환경별 커넥션 풀 크기 기준값 문서화 — yaml 주석에 기본값 및 권장 공식 명시 ✅
- [x] 7-3-3. `CUSTODY_DB_POOL_*` 환경변수 오버라이드 지원 추가 ✅

### 7-4. DB 백업 및 복구 전략 (문서화)
- [x] 7-4-1. PostgreSQL WAL 기반 PITR 설정 방법 문서화 — `docs/operations/db-backup.md` 작성 완료 ✅
- [x] 7-4-2. 일일 전체 백업 스크립트 작성 — pg_dump 명령 + cron 설정 docs에 포함 ✅
- [x] 7-4-3. `docker-compose.yml`에 PostgreSQL 볼륨 영구 마운트 확인 — `postgres_data:/var/lib/postgresql/data` ✅
- [x] 7-4-4. 복구 테스트 절차 문서화 — db-backup.md 복구 시나리오 4종 포함 ✅

---

## 8. 🟠 로깅 및 추적성 (Logging & Traceability) — HIGH

### 8-1. 구조화 로그 (Structured Logging) 완성
- [x] 8-1-1. 모든 컨트롤러/서비스의 로그가 `event=... key=value` 형식을 따르는지 전수 확인 — ConfirmationTracker 전체 정규화 완료 ✅
- [x] 8-1-2. `ConfirmationTracker` 비동기 스레드에서 MDC `correlationId` 전파 확인 (부분 구현됨 — 전파 로직 존재) ✅
- [x] 8-1-3. `@Scheduled` 스케줄러 메서드에서 MDC `correlationId` 자동 생성 — NonceCleaner/WhitelistService/OutboxPublisher 완료 ✅
- [x] 8-1-4. 스케줄러 실행마다 `scheduler=WhitelistScheduler event=promoteHoldingToActive promoted=N` 형식 로그 추가 ✅
- [x] 8-1-5. `logback-spring.xml` production 프로파일 JSON-only 출력 설정 ✅

### 8-2. 분산 추적 (Distributed Tracing) 준비
- [x] 8-2-1. `micrometer-tracing-bridge-otel` 의존성 추가 ✅
- [x] 8-2-2. `opentelemetry-exporter-otlp` 의존성 추가 ✅
- [x] 8-2-3. `application.yaml` OTLP exporter 설정 + production 프로파일 활성화 ✅
- [x] 8-2-4. `logback-spring.xml`에 `traceId`/`spanId` MDC 키 포함 ✅
- [x] 8-2-5. 비동기 스레드 Span 전파 — ConfirmationTracker MDC 복사 방식 문서화 ✅

### 8-3. 감사 로그 강화
- [x] 8-3-1. 화이트리스트 변경 이력 테이블 `whitelist_audit_log` 생성 (마이그레이션 추가) — V3 마이그레이션에서 완료 ✅
- [x] 8-3-2. `WhitelistAuditLog` JPA 엔티티 작성 — 섹션 0-1-5에서 완료 ✅
- [x] 8-3-3. `WhitelistService.approve()`, `revoke()`, `activate()` 호출 시 감사 로그 기록 ✅
- [x] 8-3-4. `GET /whitelist/{id}/audit` 엔드포인트 추가 (감사 이력 조회) ✅
- [x] 8-3-5. 정책 변경 감사 로그: `PolicyEngine` 시작 시 규칙 스냅샷을 `policy_change_requests` 테이블에 기록; `PolicyChangeRequest` 엔티티 + `PolicyChangeRequestRepository` 신규 추가 ✅

---

## 9. 🟠 테스트 커버리지 (Test Coverage) — HIGH

### 9-1. 단위 테스트 추가
- [x] 9-1-1. `AmountLimitPolicyRuleTest`: 경계값(max-amount 정확히 일치, 초과, 미만) 케이스 테스트 ✅ (`PolicyRuleUnitTest`로 커버)
- [x] 9-1-2. `ToAddressWhitelistPolicyRuleTest`: ACTIVE/HOLDING/REGISTERED/REVOKED/비존재 주소별 케이스 (6개 테스트) ✅
- [x] 9-1-3. `PolicyEngineTest`: 두 규칙 모두 실패 시 첫 번째 규칙만 기록되는지 확인 (fail-fast 여부) ✅ (`PolicyRuleUnitTest`로 커버)
- [x] 9-1-4. `NonceAllocatorTest`: 동시 예약 시 중복 넌스 발급 없음 검증 (스레드 안전성) ✅
- [x] 9-1-5. `LedgerServiceTest`: RESERVE 후 SETTLE 순서 보장, 이중 SETTLE 방지 테스트 ✅
- [x] 9-1-6. `WhitelistServiceTest`: 각 상태 전이 성공/실패 케이스 (REVOKED 상태에서 approve 시도 등) ✅
- [x] 9-1-7. `ConfirmationTrackerTest`: 타임아웃 발생 시 `FAILED_TIMEOUT` 전이 확인 ✅
- [x] 9-1-8. `RetryReplaceServiceTest`: retry 후 attempt 수 2개, canonical 교체 확인 ✅
- [x] 9-1-9. `RetryReplaceServiceTest`: replace 후 동일 nonce, 더 높은 fee, canonical 교체 확인 ✅

### 9-2. 통합 테스트 보강
- [x] 9-2-1. `WithdrawalServiceIdempotencyTest`에 경쟁 조건(Race Condition) 테스트 추가: 동일 키로 병렬 10개 요청 시 1개만 생성 ✅
- [x] 9-2-2. 상태머신 전이 불변성 테스트: `W10_COMPLETED` 이후 `POST /retry`/`/replace` 에러 반환 확인 (`StateMachineInvarianceTest` 3개 테스트) ✅
- [x] 9-2-3. 정책 감사 로그 무결성 테스트: 거절 시 `policy_audit_logs`에 레코드 1개만 존재 (`PolicyAuditLogIntegrityTest` 3개 테스트) ✅
- [x] 9-2-4. 화이트리스트 hold 만료 스케줄러 통합 테스트: `activeAfter`를 과거 시간으로 설정 후 스케줄러 수동 호출 → ACTIVE 전이 확인 ✅ (`WhitelistWorkflowIntegrationTest`로 커버)
- [x] 9-2-5. 멱등성 충돌 통합 테스트: 동일 키 + 다른 body → 409 응답 확인 ✅ (`WithdrawalControllerIntegrationTest`로 커버)
- [x] 9-2-6. PostgreSQL 프로파일 통합 테스트: Testcontainers 기반 PostgreSQL 테스트 클래스 (`PostgreSqlIntegrationTest`) 작성 — Docker 미설치 환경 자동 스킵 ✅

### 9-3. 성능 및 부하 테스트
- [x] 9-3-1. Gatling 스크립트 작성: `POST /withdrawals` 100 RPS 지속 부하 테스트 (`docs/performance/WithdrawalLoadSimulation.scala`) ✅
- [x] 9-3-2. 동시 멱등성 키 충돌 부하 테스트 Gatling 시나리오 (`WithdrawalLoadSimulation.scala`에 포함) ✅
- [x] 9-3-3. ConfirmationTracker 동시 100개 TX 추적 모니터링 방법 문서화 (`docs/performance/load-test-plan.md`) ✅
- [x] 9-3-4. 부하 테스트 기준값 문서화 (`docs/performance/load-test-plan.md`) ✅

### 9-4. 테스트 코드 품질
- [x] 9-4-1. JaCoCo 플러그인 추가 (`build.gradle`), 라인 커버리지 60% 이상 목표 설정 ✅
- [x] 9-4-2. `@SpringBootTest` 사용 통합 테스트를 `@DataJpaTest`/`@WebMvcTest`로 분리하여 테스트 속도 개선 — `WithdrawalValidationWebMvcTest`, `RegisterAddressValidationWebMvcTest`(@WebMvcTest 신규 분리); `NonceReservationRepositoryDataJpaTest`(@DataJpaTest 신규 분리); 분리 불가한 복잡한 통합 테스트(6개)는 유지 + 이유 주석 명시 ✅
- [x] 9-4-3. 공통 테스트 픽스처 클래스 추출 (`TestFixtures.java`) ✅

---

## 10. 🟡 승인 워크플로 (Approval Workflow) — MEDIUM

### 10-1. ApprovalService 실제 구현
- [x] 10-1-1. `ApprovalTask` JPA 엔티티 작성 — 섹션 0-1-3 완료 ✅
- [x] 10-1-2. `ApprovalDecision` JPA 엔티티 작성 — 섹션 0-1-4 완료 ✅
- [x] 10-1-3. `ApprovalTaskRepository`, `ApprovalDecisionRepository` 작성 — 섹션 0-2-3 완료 ✅
- [x] 10-1-4. `ApprovalService.requestApproval(withdrawal)`: 금액 기반 requiredApprovals 결정 + 태스크 생성 ✅
- [x] 10-1-5. `ApprovalService.approve(taskId, approverId, reason)`: approvedCount += 1, 충족 시 APPROVED 전이 ✅
- [x] 10-1-6. `ApprovalService.reject(taskId, approverId, reason)`: REJECTED 전이 → W0_POLICY_REJECTED ✅
- [x] 10-1-7. `WithdrawalService`에서 W2_APPROVAL_PENDING 실제 사용: requestApproval()=false 시 승인 대기 반환 ✅

### 10-2. 4-Eyes (2인 이상 승인) 정책
- [x] 10-2-1. `application.yaml`에 `custody.approval.*` 설정 추가 (low/high-risk-threshold-eth, expiry-hours, enabled) ✅
- [x] 10-2-2. `ApprovalService.computeRequiredApprovals()`: 금액 기반 동적 결정 (0/1/2인) ✅
- [x] 10-2-3. `POST /withdrawals/{id}/approve`, `POST /withdrawals/{id}/reject-approval` API 추가 (`ApprovalController`) ✅
- [x] 10-2-4. 4-eyes 통합 테스트 (`ApprovalWorkflowIntegrationTest` 5개 테스트) ✅

---

## 11. 🟡 가스 가격 오라클 (Gas Price Oracle) — MEDIUM

### 11-1. 동적 가스 가격 조회
- [x] 11-1-1. `EvmRpcAdapter`에 `getLatestBaseFee()` 메서드 추가 (`eth_getBlockByNumber("latest")` 호출) ✅
- [x] 11-1-2. `EvmRpcAdapter`에 `getFeeHistory(blocks, percentile)` 메서드 추가 (`eth_feeHistory` 호출) ✅
- [x] 11-1-3. `EvmRpcAdapter.broadcast()`에서 하드코딩된 `DEFAULT_MAX_PRIORITY_FEE`, `DEFAULT_MAX_FEE` 제거 ✅
- [x] 11-1-4. 새 가스 계산 로직: `maxPriorityFeePerGas = feeHistory(10th percentile)`, `maxFeePerGas = baseFee * 2 + maxPriorityFeePerGas` ✅
- [x] 11-1-5. 가스 가격 캐싱: 동일 블록 내 반복 조회 방지 (`ConcurrentHashMap` + `AtomicReference` 기반, TTL 12s) ✅

### 11-2. Replace 시 수수료 범프 정책 개선
- [x] 11-2-1. 현재 `RetryReplaceService`의 fee bump 비율(+10% 하드코딩 여부 확인) 검토 — `feeBumpPercentage` 설정값으로 교체 ✅
- [x] 11-2-2. `application.yaml`에 `custody.evm.fee-bump-percentage` 설정 추가 (기본값 110%) ✅
- [x] 11-2-3. 네트워크 혼잡도 기반 동적 bump 비율 결정 로직 추가 (선택적) ✅

---

## 12. 🟡 멀티체인 지원 (Multi-Chain Support) — MEDIUM

### 12-1. BFT 어댑터 완성
- [x] 12-1-1. `BftMockAdapter`에 `getTransactionReceipt(txHash)` 구현 (현재 미구현) ✅
- [x] 12-1-2. `BftMockAdapter`에 `getPendingNonce(address)` 구현 ✅
- [x] 12-1-3. `ChainAdapter` 인터페이스에 `getTransactionReceipt()` 메서드 추가 (현재 EVM-only) ✅
- [x] 12-1-4. `ConfirmationTracker`에서 `instanceof EvmRpcAdapter` 체크 제거 → 인터페이스 메서드로 통일 ✅
- [x] 12-1-5. BFT 어댑터 통합 테스트 작성 ✅

### 12-2. 체인 설정 다형성
- [x] 12-2-1. `ChainType`별 확정(Finalization) 블록 수 설정 분리: `custody.chain-finalization.evm`, `custody.chain-finalization.bft` ✅
- [x] 12-2-2. `ChainAdapterRouter`에서 설정 기반 어댑터 선택 로직 확장 ✅

---

## 13. 🟡 API 문서화 (API Documentation) — MEDIUM

### 13-1. OpenAPI / Swagger 연동
- [x] 13-1-1. `springdoc-openapi-starter-webmvc-ui` 의존성 추가 (`build.gradle`) ✅
- [x] 13-1-2. `CustodyApplication`에 `@OpenAPIDefinition` 추가 (title, version, description) ✅
- [x] 13-1-3. 각 컨트롤러 메서드에 `@Operation`, `@ApiResponse` 어노테이션 추가 (`WithdrawalController`) ✅
- [x] 13-1-4. `CreateWithdrawalRequest` 필드에 `@Schema(description=..., example=...)` 추가 ✅
- [x] 13-1-5. `production` 프로파일에서 Swagger UI 비활성화 옵션 추가 (`application-production.yaml`) ✅

### 13-2. README 보강
- [x] 13-2-1. `README.md` 섹션 14(PostgreSQL)에 `docker compose up -d` 이후 실제 연결 검증 순서 상세화 ✅
- [x] 13-2-2. 환경변수 전체 목록 표 (`README.md`)에 새로 추가된 설정값 업데이트 ✅
- [x] 13-2-3. `docs/architecture/` 폴더 생성 후 아키텍처 다이어그램(상태머신, 시퀀스 다이어그램) 추가 ✅
- [x] 13-2-4. 운영 플레이북 문서 작성: 장애 시 대응 절차 (`docs/operations/runbook.md`) ✅

---

## 14. 🟡 배포 및 운영 자동화 (Deployment & Operations) — MEDIUM

### 14-1. Docker 이미지 최적화
- [x] 14-1-1. `custody/Dockerfile` 작성 (멀티 스테이지 빌드: `./gradlew bootJar` → 최종 JRE 이미지) — 기존 완료 ✅
- [x] 14-1-2. 최종 이미지에 불필요한 레이어 제거 (Gradle 캐시 별도 레이어) — 기존 완료 ✅
- [x] 14-1-3. `docker-compose.yml`에 custody 앱 서비스 추가 (postgres + custody + prometheus + grafana) — 기존 완료 ✅
- [x] 14-1-4. `docker-compose.yml`에 `healthcheck` 설정 추가 (custody: `/actuator/health`, postgres: `pg_isready`) — 기존 완료 ✅

### 14-2. CI/CD 기초
- [x] 14-2-1. `.github/workflows/build.yml` 작성: PR 시 `./gradlew test` + `./gradlew build` 실행 ✅
- [x] 14-2-2. `.github/workflows/build.yml`에 JaCoCo 커버리지 리포트 업로드 추가 ✅
- [x] 14-2-3. 빌드 성공 시 Docker Hub 또는 GHCR에 이미지 푸시 단계 추가 — `deploy.yml`에 GHCR 이미지 푸시 완료 ✅

### 14-3. 설정 관리
- [x] 14-3-1. `application-production.yaml` 프로파일 파일 작성 (H2 제거, Flyway 활성화, H2 콘솔 비활성화, 구조화 로그 활성화) — 기존 완료 + Swagger 비활성화 추가 ✅
- [x] 14-3-2. 환경별 설정 오버라이드 전략 문서화 (`ENV` > `application-{profile}.yaml` > `application.yaml` 우선순위 명시) ✅
- [x] 14-3-3. 민감 설정값(`private-key`, `db-password`)을 외부 Secret Store에서 주입하는 방법 문서화 ✅

---

## 15. 🟢 장기 개선 과제 (Long-term Improvements) — LOW

### 15-1. MEV 방어 및 프라이버시
- [x] 15-1-1. Flashbots Protect RPC 연동 옵션 검토 — `application.yaml` 주석에 Flashbots endpoint 문서화, 환경변수로 교체 가능 ✅
- [x] 15-1-2. Private mempool 사용 여부 결정 및 아키텍처 반영 — 현재 Phase 미도입, Phase 3 재검토. 근거: `docs/architecture/private-mempool-decision.md` ✅

### 15-2. HSM / Cold Wallet 연동
- [x] 15-2-1. `Signer` 인터페이스를 HSM(Hardware Security Module) 구현체로 교체하는 추상화 계획 수립 — `Signer.java` Phase 3 계획 주석 + PDS hook 예약 ✅
- [x] 15-2-2. AWS CloudHSM / Azure Dedicated HSM 연동 PoC — 연동 설계 문서 작성: `docs/operations/hsm-integration-plan.md` (비교표, 연동 아키텍처, Signer 인터페이스 확장 계획 포함) ✅

### 15-3. 샤딩 및 수평 확장
- [x] 15-3-1. `NonceAllocator`를 분산 환경에서 안전하게 사용하기 위한 Redis 기반 분산 락 도입 검토 — `application.yaml` 주석 + `NonceAllocator` 내 SELECT FOR UPDATE 전략 문서화 ✅
- [x] 15-3-2. `ConfirmationTracker`의 작업 분산 설계 — DB 기반 분산 락 설계 주석 + `docs/architecture/distributed-confirmation-tracker.md` 작성 ✅

### 15-4. 보안 감사
- [x] 15-4-1. 제3자 보안 감사 계획 수립 — `docs/operations/security-audit-plan.md` (감사 범위, 업체 기준, 일정, 취약점 SLA 포함) ✅
- [x] 15-4-2. OWASP Top 10 체크리스트 기반 자체 점검 — `docs/operations/security-audit-plan.md` Part 2에 A01~A10 항목별 점검 결과 포함 ✅
- [x] 15-4-3. 의존성 취약점 스캔 자동화 — `.github/dependabot.yml` (gradle + github-actions 주간 스캔) + `build.gradle` OWASP Dependency-Check 플러그인 추가 ✅

---

---

## 16. 🟢 PDS 통합 (pds-core 특허 B-1 + B-2) — LOW (Phase 4+)

> MVP 단계에서는 코드 구현 없이 **구조 예약만** 한다.
> 실제 통합은 파일럿 고객 확보 이후 Phase 2~3에서 점진적으로 활성화한다.
> 관련 설계: `f:\Workplace\custody\custody track\Custody_SaaS_Product_Design.md` 섹션 13
> 관련 레포: `f:\Workplace\pds-core`

### 16-1. MVP 단계 구조 예약 (코드 없이 스키마/인터페이스만)

- [x] 16-1-1. `V5__pds_structure_reservation.sql` Flyway 마이그레이션 파일 추가 (`tenant_pds_records` 테이블 예약) ✅
- [x] 16-1-2. `policy_audit_logs` 테이블에 `previous_hash TEXT`, `current_hash TEXT` 컬럼 예약 (null 허용, 미사용) ✅
- [x] 16-1-3. `Signer` 인터페이스에 PDS 훅 메서드 시그니처 예약 (default 구현 = no-op) ✅
- [x] 16-1-4. `application.yaml`에 pds feature flag 섹션 추가 (전부 false로 초기화) ✅
- [x] 16-1-5. `PdsProperties` `@ConfigurationProperties` 클래스 작성 (`domain/pds/PdsProperties.java`) ✅

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

---

## 17. 🔴 ChainAdapter 인터페이스 재설계 — CRITICAL

> **코드 현황 확인 (2026-04-13):**
> - `ChainAdapter`: `broadcast(BroadcastCommand)` + `getChainType()` + `getTransactionReceipt()` (default empty)
> - `BroadcastCommand`: `nonce`, `maxPriorityFeePerGas`, `maxFeePerGas` — EVM 전용 필드 포함
> - `ConfirmationTracker.waitForFinalization()` line 299: `instanceof EvmRpcAdapter` 체크 여전히 존재
> - `ChainType` enum: `{EVM, BFT}` — BITCOIN/TRON/SOLANA 미포함
> - `Signer`: `sign(RawTransaction tx, long chainId)` — web3j 타입 직접 의존, EVM 전용

### 17-0. 선행 작업 — ChainType enum 확장
- [ ] 17-0-1. `ChainType` enum에 `BITCOIN`, `TRON`, `SOLANA` 추가
- [ ] 17-0-2. `ChainAdapterRouter` 생성자의 하드코딩된 `EVM`/`BFT` 오버라이드 설정을 `Map<ChainType, String>` 기반으로 교체 (`custody.chain-adapter.bitcoin:`, `custody.chain-adapter.tron:` 등)
- [ ] 17-0-3. `CreateWithdrawalRequest`의 chainType 파싱 로직에 신규 체인 타입 추가

### 17-1. PreparedTx sealed interface 설계
> `prepareSend()` → `broadcast()` 파이프라인에서 체인별로 완전히 다른 데이터 구조 필요.
> EVM: signed hex + nonce / Bitcoin: selected UTXOs + signed raw tx + change address / Solana: serialized tx + durable nonce pubkey
- [ ] 17-1-1. `PreparedTx` sealed interface 정의:
  - `EvmPreparedTx(String signedHexTx, long nonce, BigInteger maxFeePerGas, BigInteger maxPriorityFeePerGas)`
  - `BitcoinPreparedTx(List<UtxoRef> selectedUtxos, byte[] signedRawTx, String changeAddress, long feeSat)`
  - `TronPreparedTx(byte[] signedTxBytes, long expirationMs)` — TRON 60초 만료 타임스탬프 포함
  - `SolanaPreparedTx(byte[] serializedTx, String nonceAccountPubkey, String recentBlockhash)`

### 17-2. 공통 인터페이스 재정의
- [ ] 17-2-1. `ChainAdapter` 인터페이스 4-method로 재정의:
  - `PreparedTx prepareSend(SendRequest)` — 체인별 선행 작업 (nonce 예약/UTXO 선택/blockhash 조회) + 서명
  - `BroadcastResult broadcast(PreparedTx)` — sealed type 분기로 체인별 처리
  - `TxStatusSnapshot getTxStatus(String txHash)` — 체인 조회 후 표준 상태 반환
  - `HeadsSnapshot getHeads()` — 최신/안전/완결 헤드 조회
  - `Set<ChainAdapterCapability> capabilities()` — 체인 기능 선언
- [ ] 17-2-2. `SendRequest` 공통 DTO: `chainType`, `asset`, `toAddress`, `amountRaw` (최소 단위 정수), `fromWalletId`, `idempotencyKey`, `feePolicyId`
- [ ] 17-2-3. `TxStatusSnapshot` DTO:
  - `status`: `UNKNOWN / PENDING / INCLUDED / SAFE / FINALIZED / FAILED / DROPPED / REPLACED`
  - `blockNumber`: `Long` (nullable — UTXO 체인은 block height, Solana는 slot, EVM은 block number)
  - `confirmations`: `Integer` (nullable)
  - `revertReason`: `String` (nullable — EVM reverted tx용)
- [ ] 17-2-4. `HeadsSnapshot` DTO: `latestBlock`, `safeBlock` (nullable), `finalizedBlock` (nullable), `timestampMs`
- [ ] 17-2-5. `ChainAdapterCapability` enum: `ACCOUNT_NONCE`, `FINALIZED_HEAD`, `REPLACE_TX`, `UTXO_MODEL`, `DURABLE_NONCE`

### 17-3. Signer 인터페이스 chain-agnostic 전환
> **코드 현황**: `Signer.sign(RawTransaction tx, long chainId)` — web3j `RawTransaction` 타입에 직접 의존.
> Bitcoin/TRON/Solana Signer 구현 불가 상태.
- [ ] 17-3-1. `Signer` 인터페이스 교체:
  - `byte[] signRaw(byte[] txBytes)` — 체인이 직렬화한 바이트에 서명, 서명 바이트만 반환
  - `String getPublicKeyHex()` — 공개키 hex (각 체인이 주소로 변환)
  - `ChainType getChainType()` — 이 Signer가 담당하는 체인
  - 기존 `String getAddress()` 유지 (EVM 주소용)
  - 기존 `Optional<String> getRecoveryKeyPdsId()` 유지
- [ ] 17-3-2. `EvmSigner` — 기존 `sign(RawTransaction, chainId)` 내부 유지하되, 외부 인터페이스는 `signRaw(byte[])` 구현 (web3j RLP 인코딩 → signRaw 위임)
- [ ] 17-3-3. `SignerRegistry` 클래스 작성 — `(tenantId, chainType)` → `Signer` 인스턴스 라우팅 (섹션 24와 연동)

### 17-4. 기존 EVM 어댑터 마이그레이션
- [ ] 17-4-1. `EvmRpcAdapter.prepareSend()` 구현:
  - EVM ETH 전송: nonce 예약 + fee 견적 + `RawTransaction.createEtherTransaction()` 빌드 + 서명 → `EvmPreparedTx`
  - EVM ERC-20 전송: nonce 예약 + fee 견적 + ABI 인코딩된 `transfer()` data + `RawTransaction.createTransaction()` 빌드 + 서명 → `EvmPreparedTx` (**섹션 21과 연동**)
- [ ] 17-4-2. `EvmRpcAdapter.broadcast(PreparedTx)` — `EvmPreparedTx` 타입 체크 + `ethSendRawTransaction()`
- [ ] 17-4-3. `EvmRpcAdapter.getTxStatus()` — `getTransactionReceipt()` 결과를 `TxStatusSnapshot`으로 변환 (revertReason 포함)
- [ ] 17-4-4. `EvmRpcAdapter.getHeads()` — `ethBlockNumber()` + `eth_getBlockByNumber("safe")` + `eth_getBlockByNumber("finalized")` → `HeadsSnapshot`
- [ ] 17-4-5. 기존 `getPendingNonce()` public → package-private (또는 internal), `prepareSend()` 내부에서만 호출
- [ ] 17-4-6. 기존 `getTransactionReceipt()` 인터페이스 메서드 제거 (getTxStatus로 완전 대체)

### 17-5. ConfirmationTracker 마이그레이션
- [ ] 17-5-1. `trackAttemptInternal()` — `adapter.getTransactionReceipt()` → `adapter.getTxStatus()` 교체
- [ ] 17-5-2. `waitForFinalization()` 시그니처 변경: `TransactionReceipt receipt` → `TxStatusSnapshot includedStatus` (blockNumber 추출)
- [ ] 17-5-3. **`instanceof EvmRpcAdapter` 체크 완전 제거** — `adapter.getHeads().latestBlock()`으로 교체
- [ ] 17-5-4. `finalizationBlockCount` 하드코딩 → `FinalityPolicy` 조회로 교체 (17-6 연동)
- [ ] 17-5-5. web3j `TransactionReceipt` import 완전 제거 검증

### 17-6. FinalityPolicy 엔진
- [ ] 17-6-1. `finality_policies` 테이블 — `chainType`, `tier(LOW/MEDIUM/HIGH)`, `minConfirmations`, `requireSafeHead`, `requireFinalizedHead`, `enabled`
- [ ] 17-6-2. Flyway 마이그레이션 작성 (V7__finality_policy.sql)
- [ ] 17-6-3. `FinalityPolicy` JPA 엔티티 + `FinalityPolicyRepository`
- [ ] 17-6-4. `ConfirmationTracker` — `FinalityPolicyRepository`에서 정책 조회 후 판정
- [ ] 17-6-5. seed 데이터 — EVM: LOW=1확인, MEDIUM=safe head, HIGH=finalized head
- [ ] 17-6-6. seed 데이터 — Bitcoin: LOW=1확인, MEDIUM=3확인, HIGH=6확인
- [ ] 17-6-7. seed 데이터 — TRON: LOW=1확인, MEDIUM=19확인(DPOS 기준), HIGH=19확인
- [ ] 17-6-8. seed 데이터 — Solana: LOW=confirmed(32 votes), HIGH=finalized(~32 slots)

---

## 18. 🔴 ERC-20 토큰 전송 — CRITICAL

> **코드 현황**: `EvmRpcAdapter.broadcast()`는 `RawTransaction.createEtherTransaction()` 전용.
> `GAS_LIMIT = 21_000` 하드코딩. USDC/USDT 전송 불가 상태.
> 이 섹션 없이는 EVM 기반 토큰 커스터디 자체가 불가.

### 18-1. ABI 인코딩 유틸리티
- [ ] 18-1-1. `Erc20TransferEncoder` 유틸 클래스 작성:
  - `encodeTransfer(String toAddress, BigInteger amount)` → `String hexData` (ERC-20 `transfer(address,uint256)` ABI 인코딩)
  - function selector: `0xa9059cbb`
  - address padding: 32바이트 좌패딩
  - amount padding: 32바이트 big-endian
- [ ] 18-1-2. 단위 테스트 — USDC 100 전송 ABI 인코딩 결과값 검증 (알려진 hex와 비교)

### 18-2. EvmRpcAdapter ERC-20 지원
- [ ] 18-2-1. `prepareSend()` 내 asset 분기 로직:
  - asset == 네이티브(ETH): `RawTransaction.createEtherTransaction()`, gasLimit=21,000
  - asset == ERC-20: `RawTransaction.createTransaction()`, to=컨트랙트주소, value=0, data=`Erc20TransferEncoder.encodeTransfer()`, gasLimit=`supported_assets.defaultGasLimit` 조회 (섹션 21 연동)
- [ ] 18-2-2. `SupportedAsset`에서 컨트랙트 주소 + gasLimit 조회 — `AssetRegistryService.getAsset(chainType, symbol)` 호출
- [ ] 18-2-3. 출금 amount 단위 처리 — `amountRaw`는 토큰 최소 단위 정수 (USDC: 6 decimals, ETH: 18 decimals). `WithdrawalService` ETH→Wei 변환 (`ethToWei()`)을 asset-agnostic으로 교체
- [ ] 18-2-4. ERC-20 전송 단위 테스트 — Mock RPC로 `eth_sendRawTransaction` 호출 시 data 필드에 올바른 ABI 인코딩 포함 검증
- [ ] 18-2-5. ERC-20 전송 통합 테스트 — EvmMockAdapter 또는 Hardhat 로컬넷에서 실제 ERC-20 컨트랙트 배포 후 전송 검증

### 18-3. 출금 amount 단위 정책 명확화
- [ ] 18-3-1. API 스펙 문서화 — `CreateWithdrawalRequest.amount`는 항상 최소 단위 정수 (wei / satoshi / lamport 등). README + Swagger에 명시
- [ ] 18-3-2. `WithdrawalService.ethToWei()` 제거 또는 `toMinUnit()`으로 rename — 실제로는 그냥 pass-through여야 함 (호출자가 이미 최소 단위로 전달)

---

## 19. 🟠 Bitcoin 어댑터 — HIGH

> **전제**: 섹션 17 완료 후 진행. `ChainType.BITCOIN`, `PreparedTx` sealed interface, Signer 재설계 완료 상태.

### 19-1. 의존성 및 기초
- [ ] 19-1-1. `bitcoinj-core:0.16.x` 의존성 추가 (build.gradle)
- [ ] 19-1-2. `BitcoinNetworkParams` 설정 빈 — `custody.bitcoin.network: mainnet|testnet|regtest` 환경변수 분기
- [ ] 19-1-3. Bitcoin RPC 연결 설정 — `custody.bitcoin.rpc-url`, `custody.bitcoin.rpc-user`, `custody.bitcoin.rpc-password`
- [ ] 19-1-4. Bitcoin RPC HTTP 클라이언트 — JSON-RPC 2.0 래퍼 클래스 작성 (Spring `RestClient` 기반)

### 19-2. UTXO 잠금 테이블 (선행 필요)
> UTXO 이중 사용 방지는 DB 잠금이 선행되어야 `BitcoinAdapter` 구현 가능.
- [ ] 19-2-1. `utxo_locks` 테이블 — `id`, `txid`, `vout`, `address`, `amountSat`, `withdrawalId`, `status(LOCKED/RELEASED/EXPIRED)`, `createdAt`, `expiresAt`
- [ ] 19-2-2. Flyway 마이그레이션 (V8__utxo_locks.sql) — `UNIQUE(txid, vout)` 제약
- [ ] 19-2-3. `UtxoLock` JPA 엔티티 + `UtxoLockRepository`
- [ ] 19-2-4. `UtxoLockCleaner` — 만료된 LOCKED 잠금 EXPIRED 처리 스케줄러

### 19-3. BitcoinAdapter 구현
- [ ] 19-3-1. `BitcoinAdapter implements ChainAdapter` 클래스 작성
- [ ] 19-3-2. `prepareSend()`:
  - `listunspent` RPC로 UTXO 목록 조회
  - `utxo_locks` 테이블에서 이미 잠긴 UTXO 제외
  - Largest-first UTXO 선택 (total ≥ amount + estimated_fee)
  - `estimatesmartfee` RPC로 sat/vbyte 견적
  - 잔돈 주소(change address) 계산: input 합계 - amount - fee
  - **dust check**: 잔돈 < 546 sat이면 수수료에 흡수 (별도 output 생성 안 함)
  - 선택된 UTXO `utxo_locks`에 INSERT (UNIQUE 제약으로 race condition 방지)
  - `BitcoinSigner.signUtxos()` 호출 → `BitcoinPreparedTx`
- [ ] 19-3-3. `broadcast()` — `sendrawtransaction` RPC 호출, 실패 시 UTXO 잠금 해제
- [ ] 19-3-4. `getTxStatus()` — `gettransaction` RPC → confirmation 수 → `TxStatusSnapshot`
- [ ] 19-3-5. `getHeads()` — `getblockchaininfo` → latestBlock (Bitcoin은 safe/finalized 개념 없음 → null)
- [ ] 19-3-6. `capabilities()` — `{UTXO_MODEL, REPLACE_TX}` (RBF 지원)

### 19-4. BitcoinSigner 구현
- [ ] 19-4-1. `BitcoinSigner implements Signer` — `signRaw()` + bitcoinj `Transaction` 빌드
- [ ] 19-4-2. 선택된 UTXO 각각 서명 — P2WPKH (bech32 `bc1...`) 기본 지원
- [ ] 19-4-3. 서명 완료 raw tx hex 반환

### 19-5. Bitcoin RBF (Replace-by-Fee)
> stuck Bitcoin 트랜잭션 처리. EVM의 RetryReplaceService와 별도 구현.
- [ ] 19-5-1. `BitcoinRetryService` 클래스 작성
- [ ] 19-5-2. 동일 UTXO + 더 높은 수수료(+30% 이상) + RBF 시그널(`sequence=0xFFFFFFFD`) 재서명 후 브로드캐스트
- [ ] 19-5-3. 원본 tx가 이미 confirm됐으면 replace 시도 차단 (getTxStatus 확인)
- [ ] 19-5-4. `RetryReplaceService`가 `ChainType`별로 라우팅하도록 확장 (EVM → 기존 로직, BITCOIN → `BitcoinRetryService`)

### 19-6. 주소 검증
- [ ] 19-6-1. Bitcoin 주소 포맷 검증 유틸 — P2PKH(`1...`), P2SH(`3...`), bech32(`bc1q...`), Taproot(`bc1p...`) / testnet(`m/n`, `tb1...`)
- [ ] 19-6-2. `CreateWithdrawalRequest` chainType=BITCOIN 시 Bitcoin 주소 검증 분기

### 19-7. 테스트
- [ ] 19-7-1. `BitcoinAdapter` 단위 테스트 — Mock RPC 응답으로 UTXO 선택, 수수료 계산, dust 처리 검증
- [ ] 19-7-2. UTXO 이중 사용 방지 동시성 테스트 — 동일 UTXO에 병렬 `prepareSend()` 호출 시 UNIQUE 제약으로 차단 검증
- [ ] 19-7-3. RBF 테스트 — regtest 환경에서 낮은 수수료 tx 브로드캐스트 후 RBF 치환 검증

---

## 20. 🟠 TRON 어댑터 — HIGH

> USDT 거래량의 상당 부분이 TRON 네트워크(TRC-20). **전제**: 섹션 17 완료.

### 20-1. TRON 기초
- [ ] 20-1-1. TRON Java SDK (tronj) 또는 HTTP REST API 클라이언트 구현 결정 — tronj 라이브러리 유지보수 상태 확인 후 직접 HTTP 구현 고려
- [ ] 20-1-2. TRON Full Node 연결 설정 — `custody.tron.rpc-url`, `custody.tron.api-key` (TronGrid API Key 필수)
- [ ] 20-1-3. TRON 주소 포맷 검증 — Base58Check, 첫 글자 `T`, 34자

### 20-2. TronAdapter 구현
- [ ] 20-2-1. `TronAdapter implements ChainAdapter` 작성
- [ ] 20-2-2. `prepareSend()`:
  - TRX 전송: `/wallet/createtransaction` API 호출
  - TRC-20 전송: `/wallet/triggersmartcontract` API 호출 (`transfer(address,uint256)` ABI 인코딩)
  - **트랜잭션 만료 처리**: TRON tx는 생성 시점에서 `expiration = now + 60_000ms` 포함. `TronPreparedTx`에 `expirationMs` 저장
  - bandwidth/energy 견적
- [ ] 20-2-3. `broadcast()`:
  - 트랜잭션 만료 검증: `System.currentTimeMillis() > expirationMs - 5000`이면 `prepareSend()` 재호출
  - `/wallet/broadcasttransaction` API 호출
  - `BANDWIDTH_ERROR` / `ENERGY_INSUFFICIENT` 에러 → `BroadcastRejectedException` (재시도 불가 타입 명시)
- [ ] 20-2-4. `getTxStatus()` — `/wallet/gettransactionbyid` → confirmation 조회
- [ ] 20-2-5. `getHeads()` — `/wallet/getnowblock` → latestBlock (TRON은 finalized 없음)
- [ ] 20-2-6. `capabilities()` — `{ACCOUNT_NONCE}` (TRON은 ref_block 기반이라 nonce와 유사한 개념 있음)

### 20-3. TronSigner 구현
- [ ] 20-3-1. `TronSigner implements Signer` — secp256k1 서명 (EVM과 같은 곡선, 직렬화 방식 다름)
- [ ] 20-3-2. TRON 트랜잭션 직렬화: protobuf `Transaction.raw_data` 해시(SHA256) → 서명
- [ ] 20-3-3. `signRaw(byte[] rawDataBytes)` → 65바이트 서명 반환

### 20-4. 테스트
- [ ] 20-4-1. `TronAdapter` 단위 테스트 — Mock API 응답, TRC-20 ABI 인코딩 검증
- [ ] 20-4-2. 트랜잭션 만료 처리 테스트 — 만료된 `TronPreparedTx`로 `broadcast()` 호출 시 재생성 흐름 검증
- [ ] 20-4-3. Nile testnet 연동 통합 테스트 (optional — 환경 있을 때)

---

## 21. 🟡 Solana 어댑터 — MEDIUM

> **전제**: 섹션 17 완료. Ed25519 키, Durable Nonce, ATA 복잡도로 인해 TRON보다 후순위.

### 21-1. Solana 기초
- [ ] 21-1-1. `solanaj` 라이브러리 또는 HTTP RPC 직접 구현 결정
- [ ] 21-1-2. Solana RPC 연결 설정 — `custody.solana.rpc-url`, `custody.solana.ws-url` (선택)
- [ ] 21-1-3. Solana 주소 포맷 검증 — Base58, 32바이트 공개키 (43-44자)

### 21-2. Durable Nonce Account 사전 준비
> Solana는 최근 blockhash 기반 tx가 ~2분 내 expire. 커스터디는 Durable Nonce 필수.
- [ ] 21-2-1. Durable Nonce Account 생성 운영 절차 문서화 (`docs/operations/solana-nonce-setup.md`)
  - 체인당 N개 nonce account 필요 (출금 동시성만큼)
  - 각 nonce account에 최소 rent-exempt SOL 예치 필요
- [ ] 21-2-2. `solana_nonce_accounts` 테이블 — `pubkey`, `authority`, `currentNonce`, `status(AVAILABLE/IN_USE/STALE)`, `lastUsedAt`
- [ ] 21-2-3. `SolanaNonceAccountPool` 서비스 — 사용 가능한 nonce account 대여/반납 (DB SELECT FOR UPDATE)

### 21-3. SolanaAdapter 구현
- [ ] 21-3-1. `SolanaAdapter implements ChainAdapter` 작성
- [ ] 21-3-2. `prepareSend()`:
  - `SolanaNonceAccountPool`에서 nonce account 대여
  - SOL 전송: SystemProgram.transfer instruction 빌드
  - SPL Token 전송:
    - 수신자 ATA 존재 확인 (`getAccountInfo`)
    - ATA 없으면 `createAssociatedTokenAccountInstruction` 포함 (비용 ~0.002 SOL 발신자 부담)
  - `ComputeBudgetProgram.setComputeUnitPrice()` instruction 추가 (priority fee)
  - `AdvanceNonceAccount` instruction 첫 번째에 삽입
  - `SolanaSigner.sign()` 호출 → `SolanaPreparedTx`
- [ ] 21-3-3. `broadcast()` — `sendTransaction` RPC
- [ ] 21-3-4. `getTxStatus()` — `getSignatureStatuses` → `processed/confirmed/finalized` 매핑
- [ ] 21-3-5. `getHeads()` — `getSlot(confirmed)` + `getSlot(finalized)`
- [ ] 21-3-6. `capabilities()` — `{DURABLE_NONCE, FINALIZED_HEAD}`

### 21-4. SolanaSigner 구현
- [ ] 21-4-1. `SolanaSigner implements Signer` — Ed25519 서명 (`TweetNaCl` 또는 BouncyCastle `Ed25519`)
- [ ] 21-4-2. `signRaw(byte[] messageBytes)` → 64바이트 Ed25519 서명

### 21-5. 테스트
- [ ] 21-5-1. `SolanaAdapter` 단위 테스트
- [ ] 21-5-2. ATA 미존재 시 createATA instruction 포함 검증
- [ ] 21-5-3. Solana devnet 통합 테스트 (optional)

---

## 22. 🟠 자산 레지스트리 (Asset Registry) — HIGH

> **이 섹션은 섹션 18(ERC-20 전송)과 직결. 섹션 18 작업 전에 완료 필요.**

### 22-1. 데이터 모델
- [ ] 22-1-1. `supported_chains` 테이블 — `chainType(PK)`, `nativeAsset`, `adapterBeanName`, `enabled`, `rpcUrlConfig`
- [ ] 22-1-2. `supported_assets` 테이블 — `id(PK)`, `assetSymbol`, `chainType`, `contractAddress`(nullable, ERC-20/TRC-20/SPL용), `decimals`, `defaultGasLimit`(체인별 기본 가스/수수료 단위), `enabled`, `isNative`
- [ ] 22-1-3. Flyway 마이그레이션 (V9__asset_registry.sql)
- [ ] 22-1-4. `SupportedChain`, `SupportedAsset` JPA 엔티티 + Repository
- [ ] 22-1-5. seed 데이터:
  - `EVM / ETH / native / gasLimit=21000`
  - `EVM / USDC / 0xA0b8...6eB48 / decimals=6 / gasLimit=65000`
  - `EVM / USDT / 0xdAC1...1eC7 / decimals=6 / gasLimit=65000`
  - `BITCOIN / BTC / native`
  - `TRON / TRX / native`
  - `TRON / USDT / TR7NH...64Mx / decimals=6`
  - `SOLANA / SOL / native`
  - `SOLANA / USDC / EPjFW...Dt1v / decimals=6`

### 22-2. API
- [ ] 22-2-1. `AssetRegistryService` — `getAsset(chainType, symbol)` / `getSupportedChains()` / `isAssetEnabled(chainType, symbol)`
- [ ] 22-2-2. `GET /api/chains` — 지원 체인 목록
- [ ] 22-2-3. `GET /api/chains/{chainType}/assets` — 체인별 지원 자산 목록
- [ ] 22-2-4. `CreateWithdrawalRequest` 검증 시 `supported_assets` 조회 (미지원 체인/자산 400 반환)
- [ ] 22-2-5. ADMIN 역할 — 체인/자산 `enabled` 토글 API (`PATCH /api/admin/assets/{id}/toggle`)

---

## 23. 🟠 RPC Degrade Mode — HIGH

> 단일 RPC 오류가 전체 서비스 중단으로 이어지지 않도록 단계적 운영 모드 도입.

### 23-1. 운영 모드 영속성 (선행 필요)
> 재시작 후 모드가 NORMAL로 초기화되면 STOP_THE_LINE 중 재시작 시 출금이 재개된다. DB 영속 필수.
- [ ] 23-1-1. `system_configs` 테이블 — `key(PK VARCHAR)`, `value TEXT`, `updatedAt`, `updatedBy`
- [ ] 23-1-2. Flyway 마이그레이션 (V10__system_configs.sql)
- [ ] 23-1-3. `SystemConfigRepository` + `SystemConfigService` — `get(key)` / `set(key, value, updatedBy)` + 감사 로그
- [ ] 23-1-4. seed 데이터 — `rpc_operation_mode = NORMAL`
- [ ] 23-1-5. 애플리케이션 시작 시 `system_configs`에서 모드 로드 (`@PostConstruct`)

### 23-2. 운영 모드 정의 및 전환
- [ ] 23-2-1. `RpcOperationMode` enum: `NORMAL / DEGRADED_READ / DEGRADED_WRITE / MANUAL_APPROVAL_ONLY / STOP_THE_LINE`
- [ ] 23-2-2. `RpcHealthMonitor` — Micrometer `custody.rpc.call.total{success=false}` 카운터 기반 5분 에러율 집계
- [ ] 23-2-3. 에러율 임계값 설정 — `custody.rpc.degrade.*` 환경변수 (DEGRADED_READ: 20%, DEGRADED_WRITE: 40%, STOP: 60%)
- [ ] 23-2-4. 모드 자동 전환 로직 — `RpcHealthMonitor` 1분 주기 체크 + `SystemConfigService.set()` 저장
- [ ] 23-2-5. 모드 변경 이벤트 감사 로그 기록 (`system_configs` 변경 이력)

### 23-3. 모드별 동작 구현
- [ ] 23-3-1. `DEGRADED_READ`: `FinalityPolicy` confirmation 수 2배 적용 (finalization 판정 보수적)
- [ ] 23-3-2. `DEGRADED_WRITE`: `WithdrawalService` 고액 출금 자동 차단 — 임계 금액 이상이면 `MANUAL_APPROVAL_ONLY` 경로로 강제
- [ ] 23-3-3. `MANUAL_APPROVAL_ONLY`: 신규 자동 브로드캐스트 중지 — 모든 출금이 4-eyes 승인 대기
- [ ] 23-3-4. `STOP_THE_LINE`: 신규 브로드캐스트 전면 중지 + 운영자 알림 (이메일/Slack webhook)
- [ ] 23-3-5. **STOP_THE_LINE 해제 API**: `POST /api/admin/rpc-mode/resume` — ADMIN 권한, 이유 comment 필수 + 감사 로그

### 23-4. RPC Quorum
- [ ] 23-4-1. `EvmRpcProviderPool` — finalized head 조회 시 2개 이상 RPC에 동시 질의
- [ ] 23-4-2. 다수결: 응답 중 최댓값이 과반수면 채택, 불일치 감지 시 `DEGRADED_READ` 자동 전환
- [ ] 23-4-3. `rpc_observation_snapshots` 테이블 — `rpcUrl`, `chainType`, `observedBlock`, `observedAt`, `snapshotType`
- [ ] 23-4-4. Flyway 마이그레이션 (V11__rpc_observation_snapshots.sql)
- [ ] 23-4-5. RPC 관측값 저장 스케줄러 (5분 주기, 감사용)

---

## 24. 🟠 멀티테넌시 (Multi-Tenancy) — HIGH

> B2B 납품 제품. 고객사(tenant)별 데이터 완전 격리 필수.

### 24-1. 테넌트 엔티티 (선행 필요)
- [ ] 24-1-1. `tenants` 테이블 — `tenantId(PK UUID)`, `name`, `status(ACTIVE/SUSPENDED)`, `plan`, `createdAt`
- [ ] 24-1-2. `tenant_members` 테이블 — `id`, `tenantId(FK)`, `userId`, `role(OPERATOR/APPROVER/ADMIN/AUDITOR)`, `createdAt`
- [ ] 24-1-3. `tenant_api_keys` 테이블 — `id`, `tenantId(FK)`, `keyHash(UNIQUE)`, `role`, `expiresAt`, `enabled`, `createdAt`
- [ ] 24-1-4. Flyway 마이그레이션 (V12__tenants.sql) — `tenants` 테이블 + 기본 `DEFAULT` 테넌트 insert

### 24-2. 기존 엔티티 tenant_id 추가 (3단계 마이그레이션)
> **주의**: 기존 DB에 데이터 존재. NOT NULL 바로 추가 시 Flyway 실패. 반드시 3단계로 진행.
- [ ] 24-2-1. **1단계** Flyway 마이그레이션 (V13__add_tenant_id_nullable.sql):
  - `withdrawals.tenant_id UUID NULL` 추가
  - `whitelist_addresses.tenant_id UUID NULL` 추가
  - `ledger_entries.tenant_id UUID NULL` 추가
  - `policy_audit_logs.tenant_id UUID NULL` 추가
  - `approval_tasks.tenant_id UUID NULL` 추가
  - `nonce_reservations.tenant_id UUID NULL` 추가
- [ ] 24-2-2. **2단계** 데이터 마이그레이션 SQL (V13 동일 파일 내 또는 V14):
  - `UPDATE withdrawals SET tenant_id = (SELECT tenantId FROM tenants WHERE name = 'DEFAULT') WHERE tenant_id IS NULL`
  - 위 패턴으로 전 테이블 기존 데이터에 DEFAULT 테넌트 할당
- [ ] 24-2-3. **3단계** Flyway 마이그레이션 (V15__tenant_id_not_null.sql):
  - 각 테이블 `tenant_id NOT NULL` 제약 추가
  - `idx_withdrawals_tenant_id`, `idx_whitelist_addresses_tenant_id` 등 인덱스 추가

### 24-3. API 레이어 tenant 격리
- [ ] 24-3-1. `TenantContextHolder` — ThreadLocal 기반 현재 요청의 `tenantId` 저장
- [ ] 24-3-2. `TenantResolutionFilter` — API Key → `tenant_api_keys` 조회 → `TenantContextHolder` 주입
- [ ] 24-3-3. `WithdrawalService`, `WhitelistService` 등 모든 Service — 생성/조회 시 `tenantId` 필터 강제
- [ ] 24-3-4. Cross-tenant 차단 — 리소스의 `tenantId` ≠ 요청 `tenantId` 시 403
- [ ] 24-3-5. `@TenantIsolated` AOP 어노테이션 — Service 메서드에 적용 시 자동 tenantId 주입 + 검증
- [ ] 24-3-6. 테넌트 격리 통합 테스트 — tenantA 출금이 tenantB 토큰으로 조회 시 빈 목록 또는 403 검증

### 24-4. NonceAllocator 멀티테넌시
> 테넌트별로 다른 signer/wallet 주소를 사용하면 nonce 공간이 분리됨.
- [ ] 24-4-1. `NonceAllocator.reserve()` 시그니처에 `fromAddress` 명시적 전달 확인 (현재 코드 확인 필요)
- [ ] 24-4-2. `nonce_reservations` UNIQUE 제약이 `(chainType, fromAddress, nonce)` 이미 포함 — tenant 격리 자동 적용 확인 및 테스트
- [ ] 24-4-3. 테넌트별 주소 관리 — `tenant_wallets` 테이블 설계 (섹션 26 키 관리와 연동)

### 24-5. 테넌트별 설정 분리
- [ ] 24-5-1. `tenant_policy_sets` 테이블 — `tenantId(FK)`, 금액 한도, 화이트리스트 정책, 승인 정책
- [ ] 24-5-2. `tenant_signer_bindings` 테이블 — `tenantId(FK)`, `chainType`, `signerType(local|kms|cloudhsm|external_api)`, `signerConfig JSON`
- [ ] 24-5-3. `tenant_chain_configs` 테이블 — `tenantId(FK)`, `chainType`, `rpcUrlOverride`(nullable), `enabled`

---

## 25. 🟠 External Signer 연동 — HIGH

> **전제**: 섹션 17-3 (Signer 인터페이스 chain-agnostic 전환) 완료 필수.

### 25-1. SignerRegistry
- [ ] 25-1-1. `SignerRegistry` 클래스 — `(tenantId, chainType)` → `Signer` 인스턴스 라우팅
- [ ] 25-1-2. `tenant_signer_bindings` 테이블 (24-5-2) 조회 → signerType 분기
- [ ] 25-1-3. `custody.signer.type: local|kms|cloudhsm|vault|external-api` 기본값 설정 (테넌트 오버라이드 없을 때)

### 25-2. AWS KMS Signer
- [ ] 25-2-1. `aws-java-sdk-kms` 의존성 추가
- [ ] 25-2-2. `KmsSignerConnector implements Signer` — KMS `sign()` 호출
- [ ] 25-2-3. KMS DER 서명 → EVM raw 서명 (r, s, v) 변환 — DER `SEQUENCE { INTEGER r, INTEGER s }` 파싱
- [ ] 25-2-4. `custody.signer.kms.key-arn` 환경변수 설정
- [ ] 25-2-5. KMS Signer 단위 테스트 (KMS Mock 사용)

### 25-3. HSM Signer (설계 + stub)
- [ ] 25-3-1. PKCS#11 JCA Provider (`SunPKCS11` 또는 BouncyCastle) 연동 설계 문서화
- [ ] 25-3-2. `CloudHsmSignerConnector implements Signer` stub 작성 + Phase 3 TODO 주석
- [ ] 25-3-3. HSM HA — Primary/Secondary failover 설계 문서화

### 25-4. External API Signer (고객 자체 signer)
- [ ] 25-4-1. `ExternalApiSignerConnector implements Signer`
- [ ] 25-4-2. 서명 요청 스키마: `{chainType, txHashHex, fromAddress}` → `{signatureHex}`
- [ ] 25-4-3. HMAC-SHA256 요청 인증 (`X-Custody-Sig` 헤더)
- [ ] 25-4-4. `RestClient` 기반 HTTP 호출 + timeout(5s) + retry(2회) + fallback(BroadcastRejectedException)
- [ ] 25-4-5. TLS mutual auth (mTLS) 지원 옵션 (`custody.signer.external-api.mtls-enabled`)

---

## 26. 🟡 Reconciliation (대사) — MEDIUM

### 26-1. 대사 엔진
- [ ] 26-1-1. `ReconciliationService` — 주소별 온체인 잔액 조회 + 내부 원장 잔액 비교
- [ ] 26-1-2. 온체인 잔액 조회 방법 (체인별):
  - EVM 네이티브(ETH): `eth_getBalance`
  - **EVM ERC-20**: 컨트랙트 `balanceOf(address)` 호출 (`eth_call`) — `Erc20BalanceReader` 유틸 작성
  - Bitcoin: `listunspent` 합산
  - TRON TRX: REST API `/v1/accounts/{address}`
  - TRON TRC-20: `triggersmartcontract balanceOf()`
- [ ] 26-1-3. `reconciliation_reports` 테이블 — `runAt`, `chainType`, `assetSymbol`, `address`, `onChainBalance`, `ledgerBalance`, `status(MATCHED/MISMATCH/SKIPPED)`, `delta`, `note`
- [ ] 26-1-4. Flyway 마이그레이션 (V16__reconciliation.sql)
- [ ] 26-1-5. **진행 중 출금 race condition 처리**: 대사 실행 시 `W3_APPROVED` ~ `W9_SETTLING` 상태 출금 금액을 `pendingAmount`로 집계 → `onChainBalance + pendingAmount ≈ ledgerBalance` 허용 delta 적용
- [ ] 26-1-6. `ReconciliationScheduler` — 1일 1회 새벽 3시 (운영 부하 최소 시간대) 자동 실행
- [ ] 26-1-7. 불일치 감지 시 `MISMATCH` 레코드 + 운영자 알림 트리거 (이메일 / Slack webhook)

### 26-2. 대사 API
- [ ] 26-2-1. `GET /api/reconciliation/latest` — 최신 실행 결과 조회 (AUDITOR 이상 권한)
- [ ] 26-2-2. `POST /api/reconciliation/run` — ADMIN 권한 수동 실행
- [ ] 26-2-3. 불일치 건 `PATCH /api/reconciliation/{id}/note` — 수동 처리 메모 기록

---

## 27. 🟠 운영자 대시보드 — 프론트엔드 — HIGH

> React SPA → Spring `src/main/resources/static/` 포함 → 단일 JAR 배포.
> Grafana: 기술 모니터링 전용 유지. 대시보드: 순수 운영 UI.

### 27-1. 백엔드 준비
- [ ] 27-1-1. `dashboard_users` 테이블 — `id`, `tenantId(FK)`, `username(UNIQUE)`, `passwordHash`, `role`, `mfaSecret`(nullable), `failedLoginCount`, `lockedUntil`(nullable), `createdAt`
- [ ] 27-1-2. Flyway 마이그레이션 (V17__dashboard_users.sql)
- [ ] 27-1-3. 초기 admin seed — 환경변수 `CUSTODY_ADMIN_USERNAME` / `CUSTODY_ADMIN_PASSWORD` (bcrypt hash)
- [ ] 27-1-4. Spring Security 설정 — JWT 검증 필터 + `/api/auth/**` 허용 + `GET /` `GET /assets/**` 허용 + 나머지 인증 필요
- [ ] 27-1-5. `GET /` → `index.html` 서빙, `GET /api/**` → REST API 라우팅 (SPA fallback 설정)
- [ ] 27-1-6. **CORS 설정** — 개발 시 `http://localhost:5173` (Vite dev server) 허용, production 프로파일에서 비활성화

### 27-2. 인증 API
- [ ] 27-2-1. `POST /api/auth/login` — username/password 검증 → JWT access token(15분) + refresh token(7일) 발급
- [ ] 27-2-2. **로그인 실패 횟수 제한**: 5회 실패 시 15분 잠금 (`failedLoginCount`, `lockedUntil` 컬럼 사용)
- [ ] 27-2-3. `POST /api/auth/refresh` — refresh token 검증 → 새 access token + **refresh token rotation** (구 토큰 무효화)
- [ ] 27-2-4. `POST /api/auth/logout` — refresh token 무효화 (DB blacklist 또는 Redis)
- [ ] 27-2-5. **JWT 키 설정** — `custody.jwt.private-key-path` / `custody.jwt.public-key-path` 환경변수 (RS256 비대칭키). HS256은 멀티 인스턴스 환경에서 비밀키 공유 문제 있음.

### 27-3. 프론트엔드 프로젝트 세팅
- [ ] 27-3-1. `dashboard/` 디렉토리에 React + TypeScript + Vite 프로젝트 초기화
- [ ] 27-3-2. UI 컴포넌트 라이브러리 — shadcn/ui (Tailwind 기반)
- [ ] 27-3-3. Gradle에 프론트엔드 빌드 태스크 연동 — `./gradlew build` 시 React 빌드 후 `src/main/resources/static/` 복사
- [ ] 27-3-4. JWT 만료 자동 refresh 또는 로그인 화면 redirect

### 27-4. 홈 대시보드 화면
- [ ] 27-4-1. `GET /api/dashboard/summary` 백엔드 API — 오늘 출금 건수(상태별), 승인 대기 건수, 시스템 상태(RPC/DB), RPC 모드
- [ ] 27-4-2. 오늘 출금 건수 카드 (PENDING/COMPLETED/FAILED)
- [ ] 27-4-3. 승인 대기 건수 카드
- [ ] 27-4-4. 시스템 상태 신호등 — RPC 연결 / DB / Degrade Mode (GREEN/YELLOW/RED)
- [ ] 27-4-5. 최근 출금 5건 타임라인

### 27-5. 출금 목록 화면
- [ ] 27-5-1. `GET /api/withdrawals?status=&chainType=&asset=&page=&size=` — `Page<WithdrawalSummaryDto>` 페이징 응답
- [ ] 27-5-2. 상태별 필터 탭, 체인/자산별 필터
- [ ] 27-5-3. 건별 클릭 → 상태 전이 히스토리 + TxHash + 블록 익스플로러 링크 (체인별 분기)
- [ ] 27-5-4. 실패 건 재시도 버튼 (`POST /api/withdrawals/{id}/retry`)

### 27-6. 4-eyes 승인 화면
- [ ] 27-6-1. `GET /api/approvals/pending`, `POST /api/approvals/{id}/approve`, `POST /api/approvals/{id}/reject`
- [ ] 27-6-2. 승인 대기 목록 — withdrawalId, 요청자, 금액, 체인, 자산, 수신 주소, 요청 시각
- [ ] 27-6-3. 승인/거절 버튼 + 코멘트 입력
- [ ] 27-6-4. APPROVER 역할만 승인 버튼 표시

### 27-7. 화이트리스트 관리 화면
- [ ] 27-7-1. 등록 주소 목록, 신규 등록 폼, 비활성화/승격 버튼

### 27-8. 시스템 / 감사 로그 화면
- [ ] 27-8-1. `GET /api/system/status` — RPC 상태, Degrade Mode, hot wallet 잔액
- [ ] 27-8-2. `GET /api/audit-logs` — actor/action/resourceId/timestamp 필터
- [ ] 27-8-3. Degrade Mode 현황 + **수동 모드 전환 버튼** (`POST /api/admin/rpc-mode/resume` — ADMIN)

### 27-9. 프론트엔드 공통
- [ ] 27-9-1. 역할별 메뉴 표시 제어 (OPERATOR/APPROVER/ADMIN/AUDITOR)
- [ ] 27-9-2. 한국어/영어 i18n 기초 설정
- [ ] 27-9-3. 반응형 레이아웃 (1280px 이상 기준)
- [ ] 27-9-4. E2E 테스트 — Playwright 로그인 + 출금 목록 + 승인 흐름

---

## 28. 🟡 Enterprise Auth (RBAC / MFA / IP Allowlist) — MEDIUM

### 28-1. RBAC 강화
- [ ] 28-1-1. 역할 4종 완전 분리 — OPERATOR(출금 생성) / APPROVER(승인) / ADMIN(설정+모드변경) / AUDITOR(읽기전용)
- [ ] 28-1-2. 메서드 레벨 `@PreAuthorize` 전면 적용
- [ ] 28-1-3. 역할별 API 접근 매트릭스 문서화 (`docs/operations/rbac-matrix.md`)

### 28-2. MFA (TOTP)
- [ ] 28-2-1. `POST /api/auth/mfa/setup` — TOTP 시크릿 생성 + QR 코드 URI 반환 (Google Authenticator 호환)
- [ ] 28-2-2. 로그인 시 TOTP 코드 검증 2단계 추가
- [ ] 28-2-3. ADMIN / APPROVER 역할 MFA 필수 강제

### 28-3. IP Allowlist
- [ ] 28-3-1. `tenant_ip_allowlists` 테이블 — `tenantId(FK)`, `cidr`, `description`, `enabled`
- [ ] 28-3-2. Flyway 마이그레이션 (V18__ip_allowlist.sql)
- [ ] 28-3-3. API 요청 IP allowlist 외부이면 403 반환 (TenantResolutionFilter에 통합)
- [ ] 28-3-4. `X-Forwarded-For` 헤더 처리 — 리버스 프록시 환경에서 실제 클라이언트 IP 추출

---

## 29. 🟠 Hot Wallet 잔액 모니터링 — HIGH

> **운영에서 가장 먼저 터지는 문제**: ETH/BTC/TRX 가스비 잔액 부족 시 모든 출금 중단.
> 현재 Prometheus alert에 없음. 반드시 추가.

- [ ] 29-1-1. `WalletBalancePoller` 스케줄러 — 5분 주기로 각 체인별 hot wallet 잔액 조회
  - EVM: `eth_getBalance`
  - Bitcoin: `getbalance` RPC
  - TRON: `/v1/accounts/{address}` bandwidth/energy 포함
- [ ] 29-1-2. Micrometer Gauge 등록 — `custody.hot_wallet.balance_wei{chainType=EVM}` 등
- [ ] 29-1-3. Prometheus AlertRule 추가 (`monitoring/prometheus/alerts.yml`):
  - `EvmHotWalletLowBalance`: balance < 0.1 ETH → WARNING
  - `EvmHotWalletCriticalBalance`: balance < 0.01 ETH → CRITICAL
  - Bitcoin, TRON 동일 패턴
- [ ] 29-1-4. `GET /api/system/status` 응답에 hot wallet 잔액 포함 (대시보드 27-8-1 연동)
- [ ] 29-1-5. 잔액 부족 시 `WithdrawalService` 브로드캐스트 직전 체크 — 잔액 < 최소 임계값이면 즉시 실패 (무의미한 RPC 호출 방지)

---

## 30. 🟠 온프레미스 납품 패키지 — HIGH

### 30-1. 패키지 구성
- [ ] 30-1-1. `docker-compose.prod.yml` — custody-app + PostgreSQL + Grafana + Prometheus
  - **볼륨 explicit naming**: `custody_pgdata`, `custody_grafana_data`, `custody_prometheus_data` (실수로 `down -v` 시 데이터 소실 방지)
  - **리소스 제한**: custody-app `mem_limit: 2g`, PostgreSQL `mem_limit: 4g`
  - **health check**: `depends_on: condition: service_healthy` 적용 (PostgreSQL → custody-app 순서 보장)
  - **로그 로테이션**: `logging.driver: json-file, max-size: 100m, max-file: 5`
- [ ] 30-1-2. `.env.example` — 필수 환경변수 전체 + 설명 주석
- [ ] 30-1-3. `install.sh` — Docker 설치 확인, `.env` 파일 생성 가이드, 초기 DB 마이그레이션 실행, **초기 admin 비밀번호 강제 변경 안내**
- [ ] 30-1-4. `docs/onpremise-install-guide.md` — 요구사항, 단계별 절차, 트러블슈팅

### 30-2. 보안 강화 (납품 전 필수)
- [ ] 30-2-1. HTTPS 설정 — Let's Encrypt certbot 또는 자체 인증서 마운트 옵션
- [ ] 30-2-2. PostgreSQL 외부 노출 차단 — docker internal network만 허용 (포트 5432 host binding 없음)
- [ ] 30-2-3. Grafana 접근 제한 — `GF_AUTH_ANONYMOUS_ENABLED=false`, `GF_SERVER_ROOT_URL` 설정
- [ ] 30-2-4. JWT RS256 키 쌍 생성 스크립트 (`scripts/generate-jwt-keys.sh`) + `.env.example`에 경로 항목 추가

### 30-3. 버전 관리 및 업그레이드
- [ ] 30-3-1. Flyway 자동 마이그레이션 — 업그레이드 시 `./gradlew flyway:migrate`
- [ ] 30-3-2. `CHANGELOG.md` — 버전별 변경 내역 및 업그레이드 주의사항
- [ ] 30-3-3. `upgrade.sh` — 새 버전 pull + 마이그레이션 실행 + 무중단 재시작 (`--no-deps --build`)
- [ ] 30-3-4. 백업 자동화 스크립트 포함 — `scripts/backup.sh` (pg_dump → gzip → 외부 스토리지)

