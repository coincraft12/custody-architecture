# 보안 감사 계획 (15-4-1, 15-4-2)

> 작성일: 2026-04-13
> 상태: 계획 문서 — Phase 3(기관 고객 확보 전) 실행 예정

---

## Part 1. 제3자 보안 감사 계획 (15-4-1)

### 1. 감사 목표

| 목표 | 설명 |
|------|------|
| 취약점 발견 | 코드, 인프라, API의 보안 취약점 사전 발견 및 수정 |
| 규정 준수 | 금융 규제 기관 및 기관 고객의 보안 요구사항 충족 |
| 신뢰 구축 | 독립적인 제3자 검증으로 고객 신뢰 제고 |
| 지속적 개선 | 취약점 재현 시나리오를 CI 파이프라인에 통합 |

### 2. 감사 범위

#### 2-1. Penetration Testing 범위

| 영역 | 대상 | 방법 |
|------|------|------|
| **API 보안** | `/withdrawals`, `/whitelist`, `/approvals` 전 엔드포인트 | 블랙박스 + 그레이박스 |
| **인증/인가** | API Key 인증, Role 기반 접근 제어 | 인가 우회 시도 |
| **비즈니스 로직** | 출금 정책 우회, 화이트리스트 조작 | 논리적 취약점 탐색 |
| **DB 보안** | SQL 인젝션, 데이터 노출 | 자동화 스캔 + 수동 검토 |
| **서버 인프라** | Docker 컨테이너, 네트워크 설정 | 컨테이너 탈출 시도 |
| **의존성** | 서드파티 라이브러리 취약점 | SCA(소프트웨어 구성 분석) |

#### 2-2. 감사 제외 범위

- 실제 블록체인 노드 (외부 인프라)
- AWS/Azure 클라우드 인프라 (별도 클라우드 보안 점검)
- PDS-core 연동 (Phase 2 미구현)

### 3. 감사 업체 선정 기준

| 기준 | 필수 | 우대 |
|------|------|------|
| 블록체인/Web3 감사 경험 | ✅ 3년 이상 | EVM 스마트 컨트랙트 감사 경험 |
| 금융 서비스 감사 경험 | ✅ | 디지털 자산 커스터디 감사 이력 |
| 자격 인증 | OSCP, CEH, CISA 중 1개 이상 | CISSP |
| 독립성 | 코드 개발 참여 없음 | ✅ |
| 보고서 품질 | CVSS 점수 포함, 재현 절차 명시 | 영문 + 한국어 보고서 |
| 재감사 포함 | ✅ 취약점 수정 후 1회 재검증 포함 | |

#### 3-1. 후보 업체 (참고용)

| 업체 | 특화 영역 | 비고 |
|------|----------|------|
| Trail of Bits | Web3, 스마트 컨트랙트 | 글로벌 최고 수준 |
| Halborn | 블록체인 보안, 커스터디 | 디지털 자산 특화 |
| Slowmist | 한국/아시아 기반, DeFi | 아시아 시장 경험 |
| KISA 지정 보안 업체 | 국내 금융 규제 대응 | 국내 규제 요건 충족 |

### 4. 감사 일정 계획

```
Phase 2 완료 후 (파일럿 고객 확보 전)
│
├── 주 1-2: 감사 업체 선정 및 계약
├── 주 3-4: 코드베이스 공유 및 사전 인터뷰
├── 주 5-8: 본 감사 (Pen Test + 코드 리뷰)
├── 주 9-10: 결과 보고서 수령 및 취약점 수정
├── 주 11-12: 재감사 (수정 사항 검증)
└── 최종 감사 보고서 → 기관 고객 제출 가능
```

### 5. 취약점 대응 SLA

| 심각도 | CVSS 범위 | 수정 기한 |
|--------|----------|----------|
| Critical | 9.0–10.0 | 24시간 이내 (운영 즉시 중단) |
| High | 7.0–8.9 | 72시간 이내 |
| Medium | 4.0–6.9 | 2주 이내 |
| Low | 0.1–3.9 | 다음 정기 배포 시 |

---

## Part 2. OWASP Top 10 자체 점검 결과 (15-4-2)

> 점검 기준: OWASP Top 10 2021
> 점검 일자: 2026-04-13
> 점검 방법: 코드 분석 기반 (자동화 스캔 별도 수행 필요)

### A01: Broken Access Control (접근 제어 실패)

| 항목 | 상태 | 구현 내용 | 잔여 위험 |
|------|------|----------|----------|
| API Key 인증 | ✅ 완료 | `ApiKeyAuthFilter`, `X-API-Key` 헤더 검증 (2-3-2) | API Key 유출 위험 → 환경변수 관리 필수 |
| Role 기반 접근 제어 | ✅ 완료 | OPERATOR/APPROVER/ADMIN 역할 분리 (2-3-3) | 역할 에스컬레이션 테스트 필요 |
| `/sim/*` 운영 차단 | ✅ 완료 | `@Profile("!production")` 적용 (2-3-5) | |
| H2 콘솔 운영 차단 | ✅ 완료 | `application-production.yaml` 비활성화 (2-3-6) | |
| 승인 워크플로 우회 | ✅ 완료 | 금액 기반 4-eyes 승인 정책 (섹션 10) | 낮은 금액 자동 승인 정책 검토 필요 |

**결론**: 기본 접근 제어 완료. 제3자 감사 시 역할 우회 테스트 필수.

---

### A02: Cryptographic Failures (암호화 실패)

| 항목 | 상태 | 구현 내용 | 잔여 위험 |
|------|------|----------|----------|
| 개인키 하드코딩 금지 | ✅ 완료 | 환경변수, `.gitignore` 적용 (2-1-1, 2-1-2) | |
| TLS 전송 암호화 | ⚠️ 미확인 | `docker-compose.yml` TLS 미구성 | 운영 환경에서 리버스 프록시(nginx/ALB)로 TLS 종료 필요 |
| 개인키 메모리 최소화 | ✅ 완료 | char[] zeroing (best-effort) (2-1-4) | JVM GC 한계 — Phase 2 KMS 전환 시 완전 해소 |
| 로그 민감정보 마스킹 | ✅ 완료 | `MaskingJsonGeneratorDecorator` (2-5-4) | |
| DB 암호화 | ⚠️ 미구현 | PostgreSQL TDE 미구성 | 운영 환경 EBS/disk 암호화로 대체 가능 |

**결론**: TLS 설정 및 DB 암호화는 인프라 레벨에서 별도 처리 필요.

---

### A03: Injection (인젝션)

| 항목 | 상태 | 구현 내용 | 잔여 위험 |
|------|------|----------|----------|
| SQL 인젝션 | ✅ 완료 | Spring Data JPA (파라미터 바인딩), Flyway 마이그레이션만 직접 SQL 사용 | |
| JPQL 인젝션 | ✅ 완료 | 네이티브 쿼리 미사용, 파라미터 바인딩만 사용 | |
| 주소 입력 검증 | ✅ 완료 | `@Pattern` EVM 주소 형식 검증 (2-2-1~2-2-3) | |
| OS Command 인젝션 | ✅ N/A | 외부 명령 실행 없음 | |

**결론**: ORM 사용으로 SQL 인젝션 위험 최소화. 잔여 위험 낮음.

---

### A04: Insecure Design (불안전한 설계)

| 항목 | 상태 | 구현 내용 | 잔여 위험 |
|------|------|----------|----------|
| 상태머신 전이 제어 | ✅ 완료 | `Withdrawal.transitionTo()` 유효성 검사 | 전이 로직 코드 리뷰 필요 |
| Nonce 중복 방지 | ✅ 완료 | DB `SELECT FOR UPDATE` + 복합 유니크 제약 (섹션 1) | |
| Outbox 패턴 | ✅ 완료 | 브로드캐스트 후 DB 원자적 이벤트 저장 (6-3) | Phase 3 Kafka 전환 전까지 로그 발행만 |
| Rate Limiting | ✅ 완료 | Bucket4j IP 기반 (2-4) | 프록시 환경 X-Forwarded-For 신뢰 설정 검토 |
| 화이트리스트 우회 | ✅ 완료 | `ToAddressWhitelistPolicyRule` 적용 | |

**결론**: 핵심 비즈니스 로직 보안 설계 완료. 제3자 감사 시 로직 우회 시도 필수.

---

### A05: Security Misconfiguration (보안 설정 오류)

| 항목 | 상태 | 구현 내용 | 잔여 위험 |
|------|------|----------|----------|
| Swagger 운영 비활성화 | ✅ 완료 | `application-production.yaml` (13-1-5) | |
| H2 콘솔 비활성화 | ✅ 완료 | `application-production.yaml` | |
| Actuator 노출 제한 | ✅ 완료 | health/info/prometheus만 노출 | prometheus 엔드포인트 네트워크 접근 제한 권장 |
| 기본 자격 증명 제거 | ⚠️ 주의 | `application.yaml`에 dev-*-key 기본값 존재 | 운영 환경에서 반드시 환경변수로 교체 필수 |
| Spring Security 기본 설정 | ✅ 완료 | 커스텀 `ApiKeyAuthFilter` 적용 | |
| 오류 응답 정보 노출 | ✅ 완료 | `GlobalExceptionHandler` 스택 트레이스 마스킹 (2-5-3) | |

**결론**: 운영 프로파일 설정 완료. 환경변수 관리 절차 준수 필수.

---

### A06: Vulnerable and Outdated Components (취약하고 오래된 구성요소)

| 항목 | 상태 | 구현 내용 | 잔여 위험 |
|------|------|----------|----------|
| 의존성 취약점 스캔 | ⚠️ 부분 | Dependabot 설정 완료 예정 (15-4-3) | 현재 수동 검토만 |
| Spring Boot 버전 | ✅ 최신 | 3.5.0 사용 (2026-04 기준 최신) | |
| Web3j 버전 | ⚠️ 주의 | 4.10.3 — 취약점 이력 확인 필요 | |
| 정기 업데이트 | ⚠️ 미정 | 분기 1회 의존성 업데이트 권장 | |

**결론**: Dependabot 설정 후 자동 스캔 활성화 필요 (15-4-3).

---

### A07: Identification and Authentication Failures (식별 및 인증 실패)

| 항목 | 상태 | 구현 내용 | 잔여 위험 |
|------|------|----------|----------|
| API Key 인증 | ✅ 완료 | `X-API-Key` 헤더 검증 (2-3-2) | API Key는 정적 — JWT 전환 고려 (Phase 3) |
| Brute Force 방어 | ✅ 완료 | Rate Limiting (2-4) — IP 기반 제한 | API Key 추측 공격 방어 (429 응답) |
| 세션 관리 | ✅ N/A | Stateless API (세션 없음) | |
| MFA | ⚠️ 미구현 | 현재 API Key만 사용 | 고가치 작업(승인)에 대한 MFA 고려 |

**결론**: 기본 인증 완료. API Key 갱신 정책 및 MFA는 Phase 3 검토.

---

### A08: Software and Data Integrity Failures (소프트웨어 및 데이터 무결성 실패)

| 항목 | 상태 | 구현 내용 | 잔여 위험 |
|------|------|----------|----------|
| 감사 로그 무결성 | ✅ 완료 | `whitelist_audit_log` 테이블 (8-3) | 해시 체인은 Phase 3 (PDS 연동) |
| PDS 정책 해시 체인 | ⚠️ 예약 | `policy_audit_logs` 해시 컬럼 예약 (16-1-2) | Phase 3 미구현 |
| CI/CD 파이프라인 | ✅ 완료 | `.github/workflows/build.yml`, 테스트 검증 (14-2-1) | |
| 서명 검증 | ✅ N/A | TX 서명은 EvmSigner에서 수행 | |

**결론**: 감사 로그 기본 구현 완료. 해시 체인은 Phase 3에서 강화.

---

### A09: Security Logging and Monitoring Failures (보안 로깅 및 모니터링 실패)

| 항목 | 상태 | 구현 내용 | 잔여 위험 |
|------|------|----------|----------|
| 구조화 로그 | ✅ 완료 | `event=key=value` 형식, JSON 출력 (8-1) | |
| 민감정보 마스킹 | ✅ 완료 | `MaskingJsonGeneratorDecorator` (2-5-4) | |
| 상관관계 ID | ✅ 완료 | MDC `correlationId` 전파 (8-1-2) | |
| 분산 추적 | ✅ 완료 | OpenTelemetry OTLP (8-2) | 운영 환경 Jaeger/Tempo 구성 필요 |
| 알림 규칙 | ✅ 완료 | Prometheus AlertRule 4개 (3-4) | Alertmanager 연동 설정 필요 |
| 인증 실패 로그 | ✅ 완료 | `ApiKeyAuthFilter` 403 응답 시 로그 | |
| 출금 거절 로그 | ✅ 완료 | `policy_rejected` 카운터 + 감사 로그 | |

**결론**: 로깅/모니터링 체계 양호. Alertmanager 연동으로 실시간 알림 활성화 권장.

---

### A10: Server-Side Request Forgery (SSRF)

| 항목 | 상태 | 구현 내용 | 잔여 위험 |
|------|------|----------|----------|
| 외부 URL 호출 | ✅ 제한적 | RPC URL만 외부 호출 (고정 설정) | RPC URL이 환경변수 — 내부 IP 차단 필요 |
| SSRF 방어 | ⚠️ 미구현 | RPC URL 도메인 화이트리스트 미적용 | CUSTODY_EVM_RPC_URL에 내부 IP 차단 로직 추가 권장 |

**결론**: RPC URL 검증 로직 추가 권장 (Phase 2 개선 항목).

---

## Part 3. OWASP Top 10 종합 위험 매트릭스

| 항목 | 위험도 | 현재 상태 | 우선순위 |
|------|--------|----------|----------|
| A01: Broken Access Control | 🔴 High | ✅ 완료 | — |
| A02: Cryptographic Failures | 🟠 Medium | ⚠️ TLS 미구성 | Phase 2 |
| A03: Injection | 🟢 Low | ✅ 완료 | — |
| A04: Insecure Design | 🟠 Medium | ✅ 완료 | 제3자 감사 시 검증 |
| A05: Security Misconfiguration | 🟠 Medium | ⚠️ 운영 환경 설정 주의 | Phase 2 |
| A06: Outdated Components | 🟠 Medium | ⚠️ Dependabot 설정 예정 | Phase 2 (15-4-3) |
| A07: Auth Failures | 🟠 Medium | ✅ 완료 | MFA Phase 3 검토 |
| A08: Integrity Failures | 🟢 Low | ✅ 완료 (부분) | Phase 3 해시 체인 |
| A09: Logging Failures | 🟢 Low | ✅ 완료 | Alertmanager 설정 |
| A10: SSRF | 🟡 Low-Med | ⚠️ RPC URL 검증 미흡 | Phase 2 개선 |

---

## Part 4. 자체 점검 체크리스트

### 4-1. 코드 리뷰 체크리스트

- [x] 환경변수 이외의 개인키 하드코딩 없음
- [x] SQL 파라미터 바인딩 적용 (네이티브 쿼리 없음)
- [x] 모든 입력 값에 Bean Validation 적용
- [x] 스택 트레이스가 API 응답에 포함되지 않음
- [x] 로그에 개인키/서명된 TX 등 민감정보 마스킹 적용
- [x] 운영 프로파일에서 디버그 엔드포인트 비활성화
- [ ] TLS 설정 (인프라 레벨) — 운영 배포 시 확인 필요
- [ ] RPC URL 내부 IP 차단 로직 추가 필요

### 4-2. 인프라 체크리스트 (운영 배포 시)

- [ ] PostgreSQL 방화벽 설정 (custody-app 전용 접근)
- [ ] Prometheus/Grafana 엔드포인트 내부망 접근 제한
- [ ] Docker 컨테이너 루트 실행 금지 (non-root user)
- [ ] 컨테이너 이미지 취약점 스캔 (Trivy, Snyk)
- [ ] API Key 최소 권한 원칙 (운영용 별도 키 발급)
- [ ] 감사 로그 외부 스토리지 백업 (S3/CloudWatch Logs)
