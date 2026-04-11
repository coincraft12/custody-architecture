# STATUS — custody

> 이 파일은 어떤 세션/AI/수동 작업 이후에도 반드시 업데이트한다.
> 다음 작업자가 이 파일 하나만 읽어도 현재 상태를 파악할 수 있어야 한다.

## 현재 상태
- **단계**: 운영형 전환 TODO 진행 중 (2026-04-12)
- **언어**: Java / Spring Boot
- **DB**: PostgreSQL + Flyway

## 마지막 작업 내용
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
1. 🟡 Prometheus AlertRule 정의 (3-4)
2. 🟡 트랜잭션 일관성 보장 (6-2)

## 참고 파일
- `TODO.md` — 전체 작업 목록 (~243개)
- `custody track/Custody_SaaS_Product_Design.md` — 제품 설계 (gitignore됨, 로컬만)
- `custody track/Patent_Custody_Integration_Strategy.md` — 특허 통합 전략
