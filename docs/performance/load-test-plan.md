# 부하 테스트 계획 (Load Test Plan)

> 9-3: JMeter / Gatling 기반 성능 측정 계획 및 기준값 문서

## 테스트 환경

| 항목 | 값 |
|---|---|
| 대상 서버 | localhost:8080 (또는 스테이징 서버) |
| 체인 모드 | mock (RPC 제외) |
| DB | PostgreSQL (docker-compose) |
| 인증 | X-API-Key: dev-operator-key |

---

## 9-3-1: POST /withdrawals 100 RPS 지속 부하 테스트

### JMeter 설정
```xml
<!-- jmeter/withdrawal-load-test.jmx 참조 -->
<!-- Thread Group: 100 threads, ramp-up 10s, duration 60s -->
<!-- HTTP Request: POST /withdrawals -->
<!-- Header: Idempotency-Key: ${__UUID()}, X-API-Key: dev-operator-key -->
```

### Gatling 스크립트 (docs/performance/WithdrawalLoadSimulation.scala 참조)
- 시나리오: `POST /withdrawals` 100 RPS, 60초 지속
- 목표: P99 < 500ms, 오류율 < 1%

---

## 9-3-2: 동시 멱등성 키 충돌 부하 테스트

### 목표
- 1000개 스레드가 동일 `Idempotency-Key` 동시 전송
- DB UNIQUE 제약 + 분산 락이 정확히 1개만 생성함을 확인
- 기대: 첫 번째 요청 200 OK, 나머지 999개 200 OK (동일 결과 반환, idempotent)

### JMeter 설정
```
Thread Count: 1000
Same Idempotency-Key: "idem-concurrent-stress-test-1"
Ramp-up: 0s (즉시)
Duration: single shot
```

---

## 9-3-3: ConfirmationTracker 동시 100개 TX 추적

### 목표
- 100개 출금 동시 생성 후 ConfirmationTracker 메모리/CPU 모니터링
- `custody.confirmation_tracker.active_tasks` 게이지 확인
- 메모리 누수 없음 확인 (GC 모니터링)

### 측정 방법
```bash
# JVM 힙 모니터링
jconsole 또는 jstat -gc <pid> 1s

# Prometheus 게이지 확인
curl http://localhost:8080/actuator/prometheus | grep custody_confirmation_tracker
```

---

## 9-3-4: 기준값 (Baseline)

현재 측정값 (2026-04-13 기준, mock 모드):

| 지표 | 기준값 | 비고 |
|---|---|---|
| POST /withdrawals P50 | < 50ms | mock 모드 |
| POST /withdrawals P99 | < 200ms | mock 모드 |
| DB 단독 TPS | - | PostgreSQL 환경 미측정 |
| ConfirmationTracker 100개 | 메모리 증가 < 50MB | 예상값 |

> 실제 측정 후 이 표를 업데이트한다.
