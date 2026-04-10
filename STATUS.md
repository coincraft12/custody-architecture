# STATUS — custody

> 이 파일은 어떤 세션/AI/수동 작업 이후에도 반드시 업데이트한다.
> 다음 작업자가 이 파일 하나만 읽어도 현재 상태를 파악할 수 있어야 한다.

## 현재 상태
- **단계**: 운영형 전환 TODO 진행 중 (2026-04-10)
- **언어**: Java / Spring Boot
- **DB**: PostgreSQL + Flyway

## 마지막 작업 내용
- API Key 인증·인가 (2-3) 구현 완료 (2026-04-10)
  - `SecurityConfig` + `ApiKeyAuthFilter` + `ApiKeyProperties` 작성
  - Role: OPERATOR / APPROVER / ADMIN
  - `/sim/**` → `@Profile("!production")` 비활성화
  - `application-production.yaml` API 키 환경변수 연동
  - 스테이징 배포 자동화 (Dockerfile + GitHub Actions + Hetzner VPS)
  - 전체 테스트 통과 (99개)
- 동시 예약 충돌 방지 (1-2-4) 구현 완료 (2026-04-10)
  - `NonceReservationRepository.findActiveWithLock` (SELECT FOR UPDATE) 추가
  - `NonceAllocator.reserve()`: retry 루프 제거 → SELECT FOR UPDATE 기반 단순화
  - `NonceAllocatorTest` 재작성: 5개 테스트 통과
- 넌스 만료 스케줄러 (1-3) 구현 완료 (2026-04-10)
  - `NonceCleaner` 스케줄러 작성 (@Scheduled, 매 1분)
  - RESERVED 만료 → EXPIRED 전이 + TxAttempt → FAILED_TIMEOUT
  - `custody.nonce.expiry-minutes` 설정값 추가 (기본값 10분)
  - 단위 테스트 5개 작성·통과

## 완료된 주요 작업
- JPA 엔티티 사전 과제 전체 완료 (섹션 0)
- DB 기반 nonce reservation 구현 완료 (섹션 1-2)
- 테스트 커버리지 보강 (섹션 9 주요 항목)
- pds-core 통합 전략 TODO 섹션 16 추가
- Custody_SaaS_Product_Design.md 섹션 13 추가 (pds-core 아키텍처)

## 다음 작업 항목 (우선순위 순)
1. 🔴 모니터링 — Micrometer + Prometheus (3-1)
2. 🔴 확인 추적 — 서버 재시작 후 미완료 TX 재추적 (5-3)
3. 🟠 입력 검증 — Bean Validation (2-2)

## 참고 파일
- `TODO.md` — 전체 작업 목록 (~243개)
- `custody track/Custody_SaaS_Product_Design.md` — 제품 설계 (gitignore됨, 로컬만)
- `custody track/Patent_Custody_Integration_Strategy.md` — 특허 통합 전략
