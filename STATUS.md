# STATUS — custody

> 이 파일은 어떤 세션/AI/수동 작업 이후에도 반드시 업데이트한다.
> 다음 작업자가 이 파일 하나만 읽어도 현재 상태를 파악할 수 있어야 한다.

## 현재 상태
- **단계**: 운영형 전환 TODO 진행 중 (2026-04-11)
- **언어**: Java / Spring Boot
- **DB**: PostgreSQL + Flyway

## 마지막 작업 내용
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

## 다음 작업 항목 (우선순위 순)
1. 🟠 입력 검증 — Bean Validation (2-2)
2. 🟠 Grafana 대시보드 구성 — docker-compose Prometheus + Grafana (3-2)

## 참고 파일
- `TODO.md` — 전체 작업 목록 (~243개)
- `custody track/Custody_SaaS_Product_Design.md` — 제품 설계 (gitignore됨, 로컬만)
- `custody track/Patent_Custody_Integration_Strategy.md` — 특허 통합 전략
