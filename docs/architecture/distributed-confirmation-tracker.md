# ConfirmationTracker 분산 배포 설계 (15-3-2)

> 작성일: 2026-04-13
> 상태: 설계 문서 — 실제 구현은 Phase 3에서 수행. 현재는 단일 인스턴스 가정.

---

## 1. 현재 ConfirmationTracker 동작

`ConfirmationTracker`는 JVM 내 `ConcurrentHashMap` 기반의 `trackingSet`으로
동일 `attemptId`의 중복 추적을 방지한다 (5-3-3).

```java
// 현재 구현 (단일 인스턴스)
private final Set<UUID> trackingSet = ConcurrentHashMap.newKeySet();

public boolean startTrackingByAttemptId(UUID attemptId) {
    if (!trackingSet.add(attemptId)) {
        return false;  // 이미 추적 중 — 스킵
    }
    // ... 비동기 추적 시작
}
```

**한계**: 여러 인스턴스가 실행될 경우 각 인스턴스가 독립적인 `trackingSet`을 보유하므로
동일 TX를 복수의 인스턴스가 중복 추적하게 된다.

---

## 2. 중복 추적의 문제점

| 문제 | 설명 |
|------|------|
| 중복 상태 전이 | 두 인스턴스가 동시에 `W7_INCLUDED` 전이 → `Withdrawal` 엔티티 낙관적 잠금 충돌 |
| 불필요한 RPC 호출 | 동일 txHash를 n개 인스턴스가 병렬 폴링 → RPC 쿼터 낭비 |
| LedgerService.settle() 중복 | `W10_COMPLETED` 전이가 복수 실행 → `LedgerEntry` 중복 생성 위험 |
| 로그 노이즈 | 동일 TX에 대한 로그가 n배 발생 |

---

## 3. DB 기반 분산 락 설계

### 3-1. 락 테이블 스키마 (Phase 3 마이그레이션)

```sql
-- Phase 3: V6__confirmation_tracker_distributed_lock.sql (예정)
CREATE TABLE confirmation_tracker_locks (
    attempt_id      UUID        PRIMARY KEY,
    locked_by       VARCHAR(64) NOT NULL,     -- 인스턴스 식별자 (hostname:pid)
    locked_at       TIMESTAMP   NOT NULL,
    expires_at      TIMESTAMP   NOT NULL,      -- 락 TTL (인스턴스 크래시 복구용)
    heartbeat_at    TIMESTAMP   NOT NULL       -- 인스턴스 생존 확인
);

CREATE INDEX idx_ctlocks_expires ON confirmation_tracker_locks (expires_at);
```

### 3-2. 락 획득 로직 (Upsert 기반)

```java
// 계획: lab.custody.orchestration.DistributedTrackerLock (Phase 3)
@Repository
public class ConfirmationTrackerLockRepository {

    @Transactional
    public boolean tryAcquire(UUID attemptId, String instanceId, Duration ttl) {
        // PostgreSQL INSERT ... ON CONFLICT DO NOTHING
        // 이미 유효한 락이 존재하면 0 rows affected → false 반환
        int rows = jdbcTemplate.update("""
            INSERT INTO confirmation_tracker_locks
                (attempt_id, locked_by, locked_at, expires_at, heartbeat_at)
            VALUES (?, ?, NOW(), NOW() + INTERVAL '? seconds', NOW())
            ON CONFLICT (attempt_id) DO UPDATE
                SET locked_by = EXCLUDED.locked_by,
                    locked_at = EXCLUDED.locked_at,
                    expires_at = EXCLUDED.expires_at,
                    heartbeat_at = EXCLUDED.heartbeat_at
            WHERE confirmation_tracker_locks.expires_at < NOW()
            """, attemptId, instanceId, ttl.toSeconds());
        return rows > 0;
    }

    @Transactional
    public void heartbeat(UUID attemptId, String instanceId, Duration ttl) {
        jdbcTemplate.update("""
            UPDATE confirmation_tracker_locks
            SET heartbeat_at = NOW(), expires_at = NOW() + INTERVAL '? seconds'
            WHERE attempt_id = ? AND locked_by = ?
            """, ttl.toSeconds(), attemptId, instanceId);
    }

    @Transactional
    public void release(UUID attemptId, String instanceId) {
        jdbcTemplate.update("""
            DELETE FROM confirmation_tracker_locks
            WHERE attempt_id = ? AND locked_by = ?
            """, attemptId, instanceId);
    }
}
```

### 3-3. ConfirmationTracker 수정 계획 (Phase 3)

```java
// Phase 3 변경 계획 — startTrackingByAttemptId() 수정
public boolean startTrackingByAttemptId(UUID attemptId) {
    // 1. JVM 내 중복 체크 (기존)
    if (!trackingSet.add(attemptId)) {
        return false;
    }

    // 2. Phase 3: DB 분산 락 획득 시도
    // boolean acquired = lockRepo.tryAcquire(attemptId, instanceId, Duration.ofMinutes(5));
    // if (!acquired) {
    //     trackingSet.remove(attemptId);
    //     log.info("event=confirmation_tracker.distributed_lock_missed attemptId={}", attemptId);
    //     return false;
    // }

    activeTasks.incrementAndGet();
    submitWithMdc(() -> {
        try {
            // Phase 3: 주기적 heartbeat 갱신
            // scheduleHeartbeat(attemptId, instanceId);
            trackAttemptInternal(attemptId);
        } finally {
            trackingSet.remove(attemptId);
            activeTasks.decrementAndGet();
            // Phase 3: 락 해제
            // lockRepo.release(attemptId, instanceId);
        }
    });
    return true;
}
```

---

## 4. 대안 설계: Redis 분산 락

DB 기반 락 대신 Redis `SET NX PX`를 사용하는 방법:

```java
// 계획: Redisson 또는 Spring Data Redis 기반
// redisTemplate.opsForValue().setIfAbsent(
//     "ct:lock:" + attemptId, instanceId, Duration.ofMinutes(5))
```

| 항목 | DB 기반 락 | Redis 기반 락 |
|------|------------|---------------|
| 추가 인프라 | 불필요 (기존 PostgreSQL 활용) | Redis 서버 필요 |
| 성능 | 낮음 (DB I/O) | 높음 (in-memory) |
| 복잡성 | 낮음 | 중간 (Redis HA 구성 필요) |
| 적합 트래픽 | 소~중 규모 (초당 수십 TX) | 대규모 (초당 수백+ TX) |
| 현재 선택 | **우선 적용 대상** | Phase 3+ 검토 (15-3-1) |

---

## 5. 만료된 락 정리 스케줄러 (Phase 3)

```java
// 계획: 매 5분마다 만료된 락 정리 + 재할당
@Scheduled(fixedDelayString = "${custody.confirmation-tracker.lock-cleanup-ms:300000}")
public void cleanupExpiredLocks() {
    int cleaned = lockRepo.deleteExpired();
    if (cleaned > 0) {
        log.warn("event=confirmation_tracker.lock_cleanup count={}", cleaned);
        // 만료된 락의 attempt → StartupRecoveryService 패턴으로 재추적
    }
}
```

---

## 6. 현재 단일 인스턴스 운영 지침

현재 Phase에서는 단일 인스턴스로 운영하며, 다음을 준수한다.

1. **수평 확장 금지**: `CUSTODY_CONFIRMATION_TRACKER_AUTO_START=true` 인스턴스는 단 1개만 실행
2. **재시작 복구**: `StartupRecoveryService`가 `W6_BROADCASTED` 상태 TX를 자동 재추적 (5-3)
3. **다중 인스턴스 필요 시**: `CUSTODY_CONFIRMATION_TRACKER_AUTO_START=false`로 설정하고
   별도 워커 인스턴스에서만 추적 담당

---

## 7. 참고

- `ConfirmationTracker.java` — 현재 구현 + Phase 3 주석
- `docs/operations/runbook.md` — 인스턴스 재시작 복구 절차
- `TODO.md` 15-3-1 (완료), 15-3-2 (본 문서)
