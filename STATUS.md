# STATUS — custody

> 이 파일은 어떤 세션/AI/수동 작업 이후에도 반드시 업데이트한다.
> 다음 작업자가 이 파일 하나만 읽어도 현재 상태를 파악할 수 있어야 한다.

## 현재 상태
- **단계**: 🟢 LOW 항목 처리 — 5-4/15-1-2/15-2-2/15-3-2/15-4-1~3 완료 (2026-04-13). 미완료: 섹션 16(PDS 통합, Phase 4+)만 남음
- **언어**: Java / Spring Boot
- **DB**: PostgreSQL + Flyway
- **테스트**: 162개 + `MockAutoConfirmIntegrationTest` 3개 신규 = 165개 예상 (2026-04-13)
- **최신 커밋**: `3fcc365`

## 마지막 작업 내용 (2026-04-13 LOW 항목 7개 완료)

### 5-4 Mock 어댑터 자동 확인
- **5-4-1/5-4-2**: `EvmMockAdapter` — `custody.mock.auto-confirm-delay-ms` 설정 추가, `autoConfirmDelayMs > 0`이면 broadcast 후 가상 스레드(Thread.ofVirtual)로 지연 후 `ConfirmationTracker.startTrackingByAttemptId()` 자동 호출
- `TxAttemptRepository.findByTxHash()` 메서드 추가 (txHash → attempt 조회)
- `application.yaml`에 `custody.mock.auto-confirm-delay-ms: ${CUSTODY_MOCK_AUTO_CONFIRM_DELAY_MS:0}` 추가
- **5-4-3**: `MockAutoConfirmIntegrationTest` 신규 작성 — 3개 테스트
  - `autoConfirm_afterBroadcast_transitionsToCompleted`: Awaitility 5초 내 W10 전이 검증
  - `autoConfirm_afterBroadcast_attemptBecomesIncluded`: attempt INCLUDED 전이 검증
  - `autoConfirm_initialStatusIsW6_Broadcasted`: broadcast 직후 W6 확인 + 이후 W10 전이
- `build.gradle`에 `org.awaitility:awaitility` 테스트 의존성 추가

### 15-1-2 Private mempool 결정
- `docs/architecture/private-mempool-decision.md` 신규 작성
  - Flashbots Protect / MEV Blocker / 자체 Private Relay 3가지 옵션 비교
  - 결정: **현재 Phase 미도입**, Phase 3 재검토 기준 5가지 명시
  - 코드 변경 없이 `CUSTODY_EVM_RPC_URL` 교체로 즉시 전환 가능 (15-1-1 활용)

### 15-2-2 HSM 연동 설계
- `docs/operations/hsm-integration-plan.md` 신규 작성
  - AWS CloudHSM vs Azure Dedicated HSM 비교표 (비용/SLA/SDK/장단점)
  - 권장: Phase 2 AWS KMS → Phase 3 CloudHSM 순차 전환
  - `KmsSignerConnector`, `CloudHsmSignerConnector` 구현 계획 (Java 코드 스니펫 포함)
  - 설정 구조 계획 (`custody.signer.type: local|kms|cloudhsm|vault`)
  - 무중단 마이그레이션 절차 5단계

### 15-3-2 ConfirmationTracker 분산 락 설계
- `ConfirmationTracker.java` `trackingSet` 필드에 분산 락 설계 주석 추가
  - DB 기반 `confirmation_tracker_locks` 테이블 설계 (Phase 3)
  - INSERT … ON CONFLICT DO NOTHING 원자적 락 획득 방식
  - `Phase 3: DB 분산 락 획득 시도` 주석 코드 블록 포함
- `docs/architecture/distributed-confirmation-tracker.md` 신규 작성
  - 현재 단일 인스턴스 한계 분석
  - DB 기반 vs Redis 기반 락 비교표
  - `ConfirmationTrackerLockRepository` 구현 계획 (SQL 포함)
  - 만료 락 정리 스케줄러 설계
  - 현재 단일 인스턴스 운영 지침

### 15-4-1~3 보안 감사
- `docs/operations/security-audit-plan.md` 신규 작성
  - **Part 1 (15-4-1)**: 제3자 감사 범위, 업체 선정 기준, 일정, 취약점 SLA (Critical 24h / High 72h)
  - **Part 2 (15-4-2)**: OWASP Top 10 2021 항목별 점검 결과 (A01~A10), 종합 위험 매트릭스, 자체 점검 체크리스트
- **15-4-3**: `.github/dependabot.yml` 신규 작성
  - gradle 의존성 주간 스캔 (매주 월요일, KST 기준)
  - github-actions 주간 스캔
  - spring-boot/web3j/resilience4j/micrometer 그룹화
- `build.gradle` OWASP Dependency-Check 플러그인 추가
  - `org.owasp.dependencycheck:10.0.4`
  - CVSS 7.0 이상 빌드 실패 설정
  - HTML/JSON 보고서 출력

## 마지막 작업 내용 (2026-04-13 MEDIUM 9개 완료)
- **11-2-3**: 네트워크 혼잡도 기반 동적 fee bump — `EvmRpcAdapter.bumpFeeDynamic()` + `resolveCongestedBumpPercentage()` 구현
  - LOW(ratio<0.1 또는 baseFee<10Gwei): 110% / MEDIUM(0.1≤ratio<0.5): 120% / HIGH(ratio≥0.5): 130%
  - `custody.evm.fee-bump-low/medium/high-percentage` 설정 추가 (환경변수 오버라이드 지원)
- **12-1-5**: `BftAdapterIntegrationTest.java` 신규 작성 (5개 테스트)
  - `getTransactionReceipt` broadcast 후 즉시 반환 검증
  - 미등록 txHash → Optional.empty() 검증
  - `getPendingNonce` 주소별 독립 시퀀스 증가 검증
  - `ConfirmationTracker`와의 통합 흐름 — BFT adapter → receipt → INCLUDED 전이
- **12-2-2**: `ChainAdapterRouter` 설정 기반 어댑터 선택 확장
  - `custody.chain-adapter.evm/bft` 환경변수로 Bean 이름 명시적 오버라이드 가능
  - 기존 자동 매핑 동작 완전 보존 (빈 값이면 자동 매핑 사용)
  - 시작 시 라우팅 테이블 로그 출력
- **13-2-1**: README 섹션 14 PostgreSQL — `docker compose up -d` 이후 7단계 연결 검증 순서 상세화
- **13-2-2**: README 환경변수 전체 목록 표 추가 — 9개 카테고리, ~50개 변수
- **13-2-3**: `docs/architecture/` 폴더 생성 + 다이어그램 2개
  - `state-machine.md`: Withdrawal/TxAttempt/WhitelistAddress Mermaid 상태머신
  - `sequence-diagrams.md`: 출금 생성/ConfirmationTracker/Retry-Replace/화이트리스트 시퀀스
- **13-2-4**: `docs/operations/runbook.md` 작성 — P0~P3 장애 대응, 수동 전이, 롤백 절차
- **14-3-2**: `docs/operations/config-management.md` — ENV > application-{profile}.yaml > application.yaml 우선순위 명시, 환경별 조합 가이드
- **14-3-3**: `docs/operations/config-management.md` — AWS Secrets Manager / HashiCorp Vault / K8s Secrets 연동 예시 포함

- 7-1 Flyway 마이그레이션 ↔ JPA 엔티티 전수 대조 완료 (2026-04-13)
  - `docs/operations/migration-verification.md` 신규 작성
  - 전체 11개 테이블 대조: 주요 이슈 — varchar 길이 불일치 다수(기능 영향 없음), `policy_decisions`/`rpc_observation_snapshots` JPA 엔티티 없음(의도적), nonce_reservations V1에 완전 포함 확인
  - 미사용 테이블 처리 결정: approval_tasks/decisions/change_requests/outbox_events 현재 사용 중; 나머지 Phase 3+ 예약으로 유지
- 7-2-6 주요 쿼리 EXPLAIN ANALYZE 가이드 작성 완료 (2026-04-13)
  - `docs/operations/query-analysis.md` 신규 작성
  - 5개 Repository 쿼리 인덱스 커버리지 분석
  - 추가 인덱스 권장: `nonce_reservations(chain_type, from_address, status)` HIGH, `whitelist_addresses(LOWER(address), chain_type, status)` HIGH
  - EXPLAIN ANALYZE 실행 명령·결과 해석 기준 문서화
- 9-4-2 @SpringBootTest → @DataJpaTest/@WebMvcTest 분리 완료 (2026-04-13)
  - `WithdrawalValidationWebMvcTest` 신규 (@WebMvcTest, 6개 테스트 — Bean Validation 실패 케이스)
  - `RegisterAddressValidationWebMvcTest` 신규 (@WebMvcTest, 4개 테스트 — Bean Validation 실패 케이스)
  - `NonceReservationRepositoryDataJpaTest` 신규 (@DataJpaTest, 5개 테스트 — Repository 쿼리 검증)
  - 복잡한 통합 테스트 6개(@SpringBootTest) 유지 + 이유 주석 명시
- 섹션 11~16 구현 완료 (2026-04-13)
  - 11: EIP-1559 Gas Price Oracle — `getLatestBaseFee()`, `getFeeHistory()`, `fetchGasPrices()` with AtomicReference TTL 12s cache; `broadcast()` 동적 가스 적용; `feeBumpPercentage` 설정화 (110%)
  - 12: BFT 어댑터 완성 — `BftMockAdapter.getTransactionReceipt()` + `getPendingNonce()` 구현; `ChainAdapter` 인터페이스에 `getTransactionReceipt()` default 메서드 추가; `ConfirmationTracker` `instanceof EvmRpcAdapter` 제거 → 인터페이스 메서드 통일; 체인별 finalization 블록 수 설정 분리
  - 13: OpenAPI/Swagger — `springdoc-openapi-starter-webmvc-ui:2.5.0` 추가; `@OpenAPIDefinition`; `WithdrawalController`에 `@Operation`/`@ApiResponse`; `CreateWithdrawalRequest`에 `@Schema`; production 프로파일 Swagger 비활성화
  - 14: CI/CD — `.github/workflows/build.yml` (PR 트리거, JaCoCo 업로드); Dockerfile/docker-compose.yml 이미 완성 확인; `application-production.yaml` Swagger 비활성화 추가
  - 15: 보안 강화 — Flashbots RPC 환경변수 교체 주석; HSM `Signer` Phase 3 계획 + PDS hook 예약; Redis 분산 락 Phase 3 주석
  - 16: PDS 구조 예약 — `V5__pds_structure_reservation.sql` (`tenant_pds_records` + `policy_audit_logs` 해시 컬럼); `Signer.getRecoveryKeyPdsId()` no-op default; `pds.*` feature flag yaml; `PdsProperties` 클래스
  - 전체 142개 테스트 통과
- 분산 추적 OpenTelemetry 준비 (8-2) 완료 (2026-04-12)
  - `build.gradle`: `micrometer-tracing-bridge-otel` + `opentelemetry-exporter-otlp` 의존성 추가
  - `application.yaml`: `management.tracing.enabled/sampling.probability` (기본 비활성, 환경변수 오버라이드)
  - `application-production.yaml`: tracing 활성화 + OTLP endpoint 설정
  - `logback-spring.xml`: `includeMdcKeyName traceId/spanId` — OTel traceId를 JSON 로그에 자동 포함
  - `ConfirmationTracker.submitWithMdc()`: MDC 전체 복사로 traceId/spanId 비동기 스레드 전파 주석 (8-2-5)
  - 전체 124개 테스트 통과
- DB 인덱스 최적화 (7-2) + HikariCP 설정 (7-3) + DB 백업 문서화 (7-4) + 로그 표준화 (8-1) 완료 (2026-04-12)
  - `V4__add_performance_indexes.sql`: `idx_withdrawals_status`, `idx_withdrawals_status_updated_at`, `idx_ledger_entries_withdrawal_type` 3개 인덱스 추가
  - `application-postgres.yaml`: HikariCP 8개 설정 (maximum-pool-size/minimum-idle/idle-timeout/max-lifetime/connection-timeout/keepalive-time/pool-name) + 환경변수 오버라이드
  - `docs/operations/db-backup.md`: pg_dump 전체 백업 / WAL PITR / 복구 시나리오 4종 / OutboxEvent 무결성 주의사항 / Prometheus 알림 문서화
  - `NonceCleaner`: 로그 `event=...` 형식으로 통일 + MDC `correlationId` 자동 생성 (8-1-3/8-1-4)
  - `WhitelistService.promoteHoldingToActive()`: MDC `correlationId` 자동 생성 + `scheduler=WhitelistScheduler` 형식 (8-1-3/8-1-4)
  - `OutboxPublisher.publish()`: MDC `correlationId` 자동 생성, `doPublish()` 내부 메서드로 분리 (8-1-3)
  - `ConfirmationTracker`: 비표준 로그 메시지 전부 `event=...` 형식으로 통일 (8-1-1)
  - `logback-spring.xml`: `<springProfile name="production">` — JSON_CONSOLE만 출력; `!production` — 파일 appender 병행 (8-1-5)
  - 전체 124개 테스트 통과
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
- 섹션 11 EIP-1559 Gas Price Oracle 완료 (2026-04-13)
- 섹션 12 멀티체인 BFT 어댑터 + ChainAdapter 인터페이스 통일 완료 (2026-04-13)
- 섹션 13 OpenAPI/Swagger 연동 완료 (2026-04-13)
- 섹션 14 CI/CD (build.yml) + 설정 관리 완료 (2026-04-13)
- 섹션 15 보안 강화 계획 문서화 완료 (2026-04-13)
- 섹션 16 PDS 구조 예약 (Phase 1) 완료 (2026-04-13)
- 섹션 8-3 감사 로그 강화 완료 (2026-04-13)
- 섹션 9 테스트 커버리지 보강 완료 (2026-04-13)
- 섹션 10 승인 워크플로 실제 구현 완료 (2026-04-13)
- 분산 추적 OpenTelemetry 준비 (8-2) 완료 (2026-04-12)
- DB 인덱스 최적화 (7-2) 완료 (2026-04-12)
- HikariCP 설정 (7-3) 완료 (2026-04-12)
- DB 백업 및 복구 전략 문서화 (7-4) 완료 (2026-04-12)
- 로그 표준화 (8-1) 완료 (2026-04-12)
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

## 완료된 주요 작업 (2026-04-13 추가)
- 7-1 Flyway ↔ JPA 전수 대조 — `docs/operations/migration-verification.md` 완료
- 7-2-6 인덱스 커버리지 분석 + EXPLAIN ANALYZE 가이드 — `docs/operations/query-analysis.md` 완료
- 9-4-2 @WebMvcTest/@DataJpaTest 분리 — 3개 신규 테스트 파일 완료
- 감사 로그 강화 (8-3) — whitelist_audit_log 테이블, GET /whitelist/{id}/audit 완료
- 테스트 보강 (9) — PolicyRule 단위테스트, 상태머신 불변성, Testcontainers PostgreSQL, JaCoCo 완료
- 승인 워크플로 (10) — ApprovalTask/Decision 엔티티, 4-eyes API 완료
- EIP-1559 가스 계산 동적화 (11) — baseFee + feeHistory 12초 캐시 완료
- BFT 어댑터 완성 (12) — getTransactionReceipt 인터페이스 통일 완료
- Swagger/OpenAPI (13) — springdoc 2.5.0, production 비활성화 완료
- CI/CD (14) — .github/workflows/build.yml, JaCoCo 업로드 완료
- 보안 강화 플래닝 (15) — Flashbots/Redis 분산락 주석, Signer PDS 훅 완료
- PDS 통합 구조 예약 (16) — tenant_pds_records 테이블, feature flags, PdsProperties 완료

## 다음 작업 항목 (2026-04-13 기준 실제 미구현)

### ✅ 2026-04-13 완료된 🟠 HIGH 항목
- **7-1**: Flyway 마이그레이션 ↔ JPA 엔티티 전수 대조 → `docs/operations/migration-verification.md`
- **7-2-6**: EXPLAIN ANALYZE 가이드 → `docs/operations/query-analysis.md`
- **9-4-2**: `@SpringBootTest` → `@WebMvcTest`/`@DataJpaTest` 분리 (3개 신규 테스트 파일)

### ✅ 2026-04-13 완료된 🟡 MEDIUM 항목 (9개)
- **11-2-3**: 혼잡도 기반 동적 fee bump (`bumpFeeDynamic`) + 설정 3개
- **12-1-5**: `BftAdapterIntegrationTest.java` (5개 테스트)
- **12-2-2**: `ChainAdapterRouter` 설정 기반 어댑터 선택 (`custody.chain-adapter.*`)
- **13-2-1**: README 섹션 14 PostgreSQL 연결 검증 7단계 상세화
- **13-2-2**: README 환경변수 전체 목록 표 (~50개 변수)
- **13-2-3**: `docs/architecture/state-machine.md` + `sequence-diagrams.md` (Mermaid)
- **13-2-4**: `docs/operations/runbook.md` (P0~P3 장애 대응)
- **14-3-2**: `docs/operations/config-management.md` (설정 오버라이드 전략)
- **14-3-3**: `docs/operations/config-management.md` (AWS Secrets Manager / Vault / K8s 예시)

### 🟢 장기 (Phase 4+) — 유일한 미완료 항목
- **16-2~16-4**: PDS 통합 (파일럿 고객 확보 후 Phase 2~4에서 점진적 구현)

## 참고 파일
- `TODO.md` — 전체 작업 목록 (~243개)
- `custody track/Custody_SaaS_Product_Design.md` — 제품 설계 (gitignore됨, 로컬만)
- `custody track/Patent_Custody_Integration_Strategy.md` — 특허 통합 전략
