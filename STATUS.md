# STATUS — custody

> 이 파일은 어떤 세션/AI/수동 작업 이후에도 반드시 업데이트한다.
> 다음 작업자가 이 파일 하나만 읽어도 현재 상태를 파악할 수 있어야 한다.

## 현재 상태
- **단계**: 운영형 전환 TODO 진행 중 (2026-04-10)
- **언어**: Java / Spring Boot
- **DB**: PostgreSQL + Flyway

## 완료된 주요 작업
- JPA 엔티티 사전 과제 전체 완료 (섹션 0)
- DB 기반 nonce reservation 구현 완료 (섹션 1-2)
- 테스트 커버리지 보강 (섹션 9 주요 항목)
- pds-core 통합 전략 TODO 섹션 16 추가
- Custody_SaaS_Product_Design.md 섹션 13 추가 (pds-core 아키텍처)

## 다음 작업 항목 (우선순위 순)
1. 🔴 넌스 만료 스케줄러 (1-3)
2. 🔴 보안 — Spring Security, API Key 인증 (2-3)
3. 🔴 모니터링 — Micrometer + Prometheus (3-1)
4. 🔴 확인 추적 — 서버 재시작 후 미완료 TX 재추적 (5-3)

## 참고 파일
- `TODO.md` — 전체 작업 목록 (~243개)
- `custody track/Custody_SaaS_Product_Design.md` — 제품 설계 (gitignore됨, 로컬만)
- `custody track/Patent_Custody_Integration_Strategy.md` — 특허 통합 전략
