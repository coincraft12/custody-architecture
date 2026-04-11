# STATUS — custody

> 이 파일은 어떤 세션/AI/수동 작업 이후에도 반드시 업데이트한다.
> 다음 작업자가 이 파일 하나만 읽어도 현재 상태를 파악할 수 있어야 한다.

## 현재 상태
- **단계**: 운영형 전환 TODO 진행 중 (2026-04-12)
- **언어**: Java / Spring Boot
- **DB**: PostgreSQL + Flyway

## 마지막 작업 내용
- 다중 RPC 프로바이더 폴백 (4-3) + Web3j 타임아웃 (4-4) 완료 (2026-04-12)
  - `EvmRpcProviderPool`: primary + fallback Web3j 인스턴스 리스트 보유
  - `EvmRpcConfig`: `@Bean EvmRpcProviderPool` — primary + fallback URL + OkHttp 타임아웃(30s) 설정으로 인스턴스 생성
  - `EvmRpcAdapter`: `withFallback()` 헬퍼 — 순서대로 프로바이더 시도, URL 로그 기록; `broadcast()`는 primary only
  - `application.yaml`: `custody.evm.fallback-rpc-urls` + `connect/read-timeout-seconds` 추가 (환경변수 오버라이드 포함)
  - 전체 124개 테스트 통과
- Circuit Breaker (4-1) + Retry + Backoff (4-2) 완료 (2026-04-12)
  - `build.gradle`: `resilience4j-spring-boot3:2.2.0` + `spring-boot-starter-aop` 추가
  - `EvmRpcAdapter.broadcast()`: `@CircuitBreaker(name="evmRpc")` — open 시 `broadcastFallback()` → BroadcastRejectedException
  - `EvmRpcAdapter.getPendingNonce()`: `@CircuitBreaker` + `@Retry(name="evmRpcRetry")`
  - `EvmRpcAdapter.getReceipt/getBlockNumber/getTransaction()`: `@Retry` (broadcast() 제외 — 멱등성 파괴 위험 주석)
  - `application.yaml`: resilience4j CB(50%/10/30s) + Retry(3회/1s/×2 지수 백오프) 설정
  - `AttemptExceptionType`: RPC_TRANSIENT/RPC_PERMANENT/RPC_NETWORK 추가 (4-2-5)
  - 전체 124개 테스트 통과
- ConfirmationTracker 설정 외부화 (5-1) + Finalization 블록 수 (5-2) 완료 (2026-04-12)
  - `EvmRpcAdapter.getBlockNumber()` 추가 (RPC 메트릭 포함)
  - `ConfirmationTracker`: `@Value` 주입 4개 (max-tries/poll-interval-ms/finalization-block-count/finalization-timeout-minutes)
  - `waitForFinalization()`: receipt 후 블록 수 경과 대기 → W8_SAFE_FINALIZED + LedgerService.settle()
  - `finalization_timeout.total` 카운터 신규; `finalizationBlockCount=0` 시 즉시 확정 (mock/dev 기본)
  - `ConfirmationTrackerTest`, `StartupRecoveryServiceTest`: 패키지 private 생성자 인수 추가
  - `application.yaml`: 4개 `custody.confirmation-tracker.*` 설정 추가
  - 전체 124개 테스트 통과
- Prometheus AlertRule (3-4) + 트랜잭션 일관성 (6-2) + Outbox 패턴 (6-3) 완료 (2026-04-12)
  - `monitoring/prometheus/alerts.yml`: 4개 AlertRule — WithdrawalHighPolicyRejectedRate / ConfirmationTrackerTimeoutHigh / EvmRpcHighErrorRate / HikariPoolSaturation
  - `monitoring/prometheus/prometheus.yml`: `rule_files: - alerts.yml` 참조 추가
  - `WithdrawalService.broadcastAttempt()`: 6-2-1 시나리오 분석 주석(broadcast→DB 롤백 불일치); 6-2-2/6-3-3 `WITHDRAWAL_BROADCASTED` OutboxEvent 동일 트랜잭션 저장
  - `LedgerService`: 6-2-3/6-2-4 트랜잭션 경계 확인 javadoc 추가
  - `OutboxPublisher`: `@Scheduled(fixedDelayString)` 5s 폴링 → PENDING 이벤트 로그 발행(Phase 3 Kafka 대체), `markPublished()` 중복 방지
  - `OutboxEvent.payload`: `columnDefinition = "jsonb"` → `@JdbcTypeCode(SqlTypes.JSON)` (H2/PostgreSQL 공용)
  - `WithdrawalService` 생성자에 `OutboxEventRepository` 추가; `WithdrawalServiceIdempotencyTest` 업데이트
  - 전체 124개 테스트 통과
- 에러 응답 표준화 (6-1) + 커스텀 헬스 인디케이터 (3-3) 완료 (2026-04-12)
  - `GlobalExceptionHandler` 전면 재작성: 통합 `ApiErrorResponse { status, errorCode, message, path, correlationId, timestamp }`
  - 신규 핸들러: `DataAccessException`/`TransactionSystemException` → 503, `NoHandlerFoundException`/`NoResourceFoundException` → 404
  - `ValidationErrorResponse`에 `errorCode`/`timestamp` 추가
  - `CustodyHealthIndicator`: W6_BROADCASTED TX 수 포함 `/actuator/health` 커스텀 지표
  - `build.gradle: springBoot { buildInfo() }` + `info.app.*` → `/actuator/info` 빌드 버전 노출
  - `WithdrawalRepository.countByStatus()` 추가
  - `AdapterDemoControllerBadRequestTest`: `allowedTypes` → `errorCode`/`timestamp` 검증으로 업데이트
  - 전체 124개 테스트 통과
- 넌스 충돌 감지 복구 (1-4) + 개인키 보안 (2-1) 완료 (2026-04-12)
  - `BroadcastRejectedException.isNonceTooLow()` 추가 — RPC 에러 메시지에서 "nonce too low" 감지
  - `WithdrawalService.doCreateAndBroadcast()`: nonce-too-low catch → `markException(RPC_INCONSISTENT)` + release → re-reserve → 재브로드캐스트 1회 자동 복구
  - `RetryReplaceService.retry()`: 동일 nonce-too-low 자동 복구 로직 추가
  - `NonceAllocator.reserve()` 주석: SELECT FOR UPDATE가 다중 인스턴스 환경에서도 안전한 이유 명시 (1-4-5)
  - `.gitignore`: `.env`, `*.env`, `*.pem`, `*.key` 추가 (2-1-2)
  - `Signer.java`: KmsSignerConnector/VaultSignerConnector Phase 3 계획 주석 (2-1-3)
  - `EvmSigner`: char[] zeroing (best-effort) + KMS 전환 전 한계 주석 (2-1-4)
  - `BroadcastRejectedExceptionTest` 5개 테스트 (1-4-4)
  - `StartupRecoveryServiceTest.duplicateTracking_skipped`: `lenient()` 추가 (비동기 레이스 조건)
  - 전체 124개 테스트 통과
- 민감정보 마스킹 (2-5) 구현 완료 (2026-04-12)
  - 2-5-1/2/3: 확인 작업 — EvmSigner/EvmRpcConfig 로깅 없음, GlobalExceptionHandler `SENSITIVE_HEX_PATTERN(0x[a-fA-F0-9]{64,})` 이미 있음
  - 2-5-4/5: `logback-spring.xml` — `JSON_CONSOLE` encoder에 `MaskingJsonGeneratorDecorator` + `valueMask: 0x[a-fA-F0-9]{64,}` 추가; `JSON_PRETTY_FILE` encoder에 `CompositeJsonGeneratorDecorator`(Pretty + Masking) 적용
  - private key(64 hex) 및 서명된 raw tx(>64 hex) 가 로그 JSON 값에 포함되면 자동 `****` 처리
  - 전체 119개 테스트 통과
- Rate Limiting (2-4) 구현 완료 (2026-04-11)
  - `bucket4j-core:8.10.1` 의존성 추가
  - `RateLimitProperties`: `custody.rate-limit.enabled/withdrawalsPerSecond/whitelistPerSecond`
  - `RateLimitFilter`: `OncePerRequestFilter`, `ConcurrentHashMap<IP, Bucket>`, `POST /withdrawals`(10/s) + `POST /whitelist`(20/s) 대상, 초과 시 429 + `Retry-After: 1`
  - `@ConditionalOnProperty(custody.rate-limit.enabled=true)` — 테스트에서 비활성화 가능
  - `RateLimitFilterTest` 3개 테스트 (X-Forwarded-For로 IP 격리)
  - 전체 119개 테스트 통과
- Grafana 대시보드 구성 (3-2) 완료 (2026-04-11)
  - `monitoring/prometheus/prometheus.yml`: custody:8080/actuator/prometheus 15s 스크래핑
  - `monitoring/grafana/provisioning/`: datasource(Prometheus) + dashboard 자동 프로비저닝
  - `monitoring/grafana/dashboards/custody.json`: 10개 패널 — 출금 생성/브로드캐스트 속도, 정책 거부율, 생성 레이턴시 P50/P95/P99, 추적 중인 TX 수, 타임아웃, Retry/Replace, RPC 속도/레이턴시/에러율
  - `docker-compose.yml`: prometheus(9090), grafana(3000) 서비스 추가 + volume 추가
- 입력 검증 Bean Validation (2-2) 구현 완료 (2026-04-11)
  - `spring-boot-starter-validation` 의존성 추가
  - `CreateWithdrawalRequest`: `@NotBlank` (chainType/fromAddress/toAddress/asset), `@Positive` (amount), `@Pattern` (EVM 40-hex 주소), `@Size` (asset≤20)
  - `RegisterAddressRequest`: `@NotBlank` (address/chainType/registeredBy), `@Pattern` (EVM), `@Size` (registeredBy/note≤255)
  - `GlobalExceptionHandler`: `MethodArgumentNotValidException` 핸들러 추가 → `ValidationErrorResponse {status, message, errors[], correlationId}`
  - `WithdrawalController`, `WhitelistController`에 `@Valid` 추가
  - 기존 통합 테스트 주소를 유효한 40자 hex EVM 주소로 전환 (`"0xto"` → Hardhat account 1, `"0xfrom"` → Hardhat account 0 등)
  - `create_withoutChainType_defaultsToEvm` 테스트 → `create_withoutChainType_returnsBadRequest`로 변경
  - `WithdrawalValidationTest` 7개 + `RegisterAddressValidationTest` 5개 신규 테스트 추가
  - 전체 116개 테스트 통과
- 서버 재시작 후 미완료 TX 재추적 (5-3) 구현 완료 (2026-04-11)
  - `StartupRecoveryService`: `@PostConstruct`로 W6_BROADCASTED 출금 DB 조회 → canonical TxAttempt 재등록
  - `ConfirmationTracker`: `ConcurrentHashMap.newKeySet()` 기반 중복 추적 방지 (`trackingSet`)
  - `startTrackingByAttemptId(UUID)` boolean 반환, `isTracking(UUID)` 추가
  - `WithdrawalRepository.findByStatus()`, `TxAttemptRepository.findFirstByWithdrawalIdAndCanonicalTrue()` 추가
  - `StartupRecoveryServiceTest` 5개 테스트 작성 (전체 104개 통과)
- Micrometer + Prometheus 메트릭 수집 (3-1) 구현 완료 (2026-04-11)
  - `micrometer-registry-prometheus` 의존성 추가
  - `application.yaml`: `/actuator/prometheus`, `/actuator/health`, `/actuator/info` 노출
  - `WithdrawalService`: created / policy_rejected(reason 태그) / broadcasted 카운터 + create.duration 타이머
  - `ConfirmationTracker`: active_tasks 게이지(AtomicInteger) + timeout.total 카운터
  - `EvmRpcAdapter`: rpc.call.total 카운터 + rpc.call.duration 타이머 (method·success 태그, broadcast/getPendingNonce/getReceipt/getTransaction)
  - `RetryReplaceService`: retry.total + replace.total 카운터
  - 기존 생성자 주입 방식으로 `MeterRegistry` 주입, 테스트 3개 `SimpleMeterRegistry` 추가

## 완료된 주요 작업
- 다중 RPC 프로바이더 폴백 (4-3) 완료 (2026-04-12)
- Web3j 타임아웃 설정 (4-4) 완료 (2026-04-12)
- Circuit Breaker (4-1) 완료 (2026-04-12)
- Retry + Backoff (4-2) 완료 (2026-04-12)
- ConfirmationTracker 설정 외부화 (5-1) 완료 (2026-04-12)
- Finalization 블록 수 확인 (5-2) 완료 (2026-04-12)
- Prometheus AlertRule (3-4) 완료 (2026-04-12)
- 트랜잭션 일관성 보장 (6-2) 완료 (2026-04-12)
- Outbox 패턴 기본 구현 (6-3) 완료 (2026-04-12)
- 에러 응답 표준화 (6-1) 완료 (2026-04-12)
- 커스텀 헬스 인디케이터 (3-3) 완료 (2026-04-12)
- 넌스 충돌 감지 복구 (1-4) 완료 (2026-04-12)
- 개인키 보안 (2-1) 완료 (2026-04-12)
- 민감정보 마스킹 (2-5) 완료 (2026-04-12)
- JPA 엔티티 사전 과제 전체 완료 (섹션 0)
- DB 기반 nonce reservation 구현 완료 (섹션 1-2)
- 테스트 커버리지 보강 (섹션 9 주요 항목)
- pds-core 통합 전략 TODO 섹션 16 추가
- Custody_SaaS_Product_Design.md 섹션 13 추가 (pds-core 아키텍처)
- API Key 인증·인가 (2-3) 완료 (2026-04-10)
- 동시 예약 충돌 방지 (1-2-4) 완료 (2026-04-10)
- 넌스 만료 스케줄러 (1-3) 완료 (2026-04-10)
- Micrometer + Prometheus 메트릭 수집 (3-1) 완료 (2026-04-11)
- 서버 재시작 후 미완료 TX 재추적 (5-3) 완료 (2026-04-11)
- 입력 검증 Bean Validation (2-2) 완료 (2026-04-11)
- Grafana 대시보드 구성 (3-2) 완료 (2026-04-11)
- Rate Limiting (2-4) 완료 (2026-04-11)

## 다음 작업 항목 (우선순위 순)
1. 🟠 DB 인덱스 최적화 (7-2)
2. 🟠 HikariCP 설정 (7-3)
3. 🟠 로그 표준화 (8-1)

## 참고 파일
- `TODO.md` — 전체 작업 목록 (~243개)
- `custody track/Custody_SaaS_Product_Design.md` — 제품 설계 (gitignore됨, 로컬만)
- `custody track/Patent_Custody_Integration_Strategy.md` — 특허 통합 전략
