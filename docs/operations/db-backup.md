# DB 백업 및 복구 전략 (7-4)

> Custody 서비스 PostgreSQL 데이터베이스 백업·복구 운영 가이드

---

## 1. 백업 전략 개요

### 1-1. 백업 유형

| 유형 | 도구 | 주기 | 보존 기간 | 비고 |
|------|------|------|-----------|------|
| 논리 백업 (전체) | `pg_dump` | 일 1회 (00:00 UTC) | 30일 | 전체 스키마 + 데이터 |
| 논리 백업 (증분) | WAL + `pg_basebackup` | 연속 (WAL 아카이빙) | 7일 | PITR 지원 |
| 스냅샷 백업 | 클라우드 볼륨 스냅샷 | 일 1회 | 7일 | 빠른 복구용 |

### 1-2. RTO / RPO 목표

| 지표 | 목표 |
|------|------|
| **RTO** (Recovery Time Objective) | ≤ 1시간 (스냅샷 복구 기준) |
| **RPO** (Recovery Point Objective) | ≤ 5분 (WAL 아카이빙 기준) |

---

## 2. pg_dump 전체 백업

### 2-1. 명령어

```bash
# 커스텀 포맷(압축) 백업
pg_dump \
  --host=$CUSTODY_DB_HOST \
  --port=5432 \
  --username=$CUSTODY_DB_USERNAME \
  --dbname=custody \
  --format=custom \
  --compress=9 \
  --file=/backup/custody_$(date +%Y%m%d_%H%M%S).dump

# 환경변수: PGPASSWORD=$CUSTODY_DB_PASSWORD (또는 .pgpass 파일 사용)
```

### 2-2. 자동화 (cron)

```cron
# /etc/cron.d/custody-db-backup
0 0 * * * postgres PGPASSWORD=$CUSTODY_DB_PASSWORD pg_dump \
  -h $CUSTODY_DB_HOST -U $CUSTODY_DB_USERNAME -d custody \
  -Fc -Z9 -f /backup/custody_$(date +\%Y\%m\%d).dump \
  && find /backup -name "custody_*.dump" -mtime +30 -delete
```

### 2-3. 백업 검증

```bash
# 백업 파일 무결성 확인
pg_restore --list /backup/custody_YYYYMMDD.dump | head -20

# 복원 테스트 (staging DB)
pg_restore \
  --host=$STAGING_DB_HOST \
  --username=$CUSTODY_DB_USERNAME \
  --dbname=custody_restore_test \
  --clean --if-exists \
  /backup/custody_YYYYMMDD.dump
```

---

## 3. WAL 아카이빙 (PITR)

### 3-1. PostgreSQL 설정 (`postgresql.conf`)

```ini
wal_level = replica
archive_mode = on
archive_command = 'aws s3 cp %p s3://custody-wal-archive/wal/%f'
archive_timeout = 300          # 5분마다 강제 WAL 전환
```

### 3-2. Base Backup

```bash
# 초기 베이스 백업
pg_basebackup \
  --host=$CUSTODY_DB_HOST \
  --username=replication_user \
  --pgdata=/backup/basebackup \
  --format=tar \
  --gzip \
  --checkpoint=fast \
  --write-recovery-conf
```

### 3-3. PITR 복구 절차

```bash
# 1. 타겟 시점 결정 (예: 장애 발생 5분 전)
TARGET_TIME="2026-04-12 14:55:00 UTC"

# 2. 베이스 백업 복원
tar -xzf /backup/basebackup/base.tar.gz -C /var/lib/postgresql/data/

# 3. recovery.conf (또는 postgresql.conf) 설정
cat > /var/lib/postgresql/data/recovery.signal << EOF
# PostgreSQL 14+: recovery.signal 파일만 있으면 PITR 모드
EOF

cat >> /var/lib/postgresql/data/postgresql.conf << EOF
restore_command = 'aws s3 cp s3://custody-wal-archive/wal/%f %p'
recovery_target_time = '${TARGET_TIME}'
recovery_target_action = 'promote'
EOF

# 4. PostgreSQL 시작 — WAL replay 자동 진행
pg_ctl start -D /var/lib/postgresql/data
```

---

## 4. 복구 시나리오별 절차

### 4-1. 단순 데이터 오손 (특정 테이블 복구)

```bash
# 단일 테이블 복원
pg_restore \
  --host=$CUSTODY_DB_HOST \
  --username=$CUSTODY_DB_USERNAME \
  --dbname=custody \
  --table=withdrawals \
  /backup/custody_YYYYMMDD.dump
```

### 4-2. DB 전체 장애 (스냅샷 복구)

1. 클라우드 콘솔에서 최신 스냅샷 선택 → 신규 볼륨 생성
2. 신규 PostgreSQL 인스턴스에 볼륨 마운트
3. `CUSTODY_DB_URL` 환경변수를 신규 엔드포인트로 업데이트
4. Custody 서비스 재시작 (Flyway 마이그레이션 자동 실행)

### 4-3. 논리 오류 (잘못된 배포 후 롤백)

```bash
# 1. Custody 서비스 중지 (신규 TX 차단)
kubectl scale deployment custody --replicas=0

# 2. 현재 DB 덤프 (분석용)
pg_dump -Fc -Z9 -h $HOST -U $USER -d custody \
  -f /backup/pre_rollback_$(date +%Y%m%d_%H%M%S).dump

# 3. 대상 시점으로 PITR 또는 특정 덤프에서 복원
# (위 3-3 또는 4-1 절차 참조)

# 4. Flyway 상태 확인
flyway -url=$CUSTODY_DB_URL info

# 5. 서비스 재시작
kubectl scale deployment custody --replicas=3
```

---

## 5. OutboxEvent 무결성 주의사항

`outbox_events` 테이블은 브로드캐스트된 TX의 이벤트 기록을 담는다.
복구 후 `status = 'PENDING'` 이벤트가 남아 있으면 `OutboxPublisher`가 재발행을 시도한다.

- **정상 케이스**: 이미 발행된 이벤트는 외부 시스템에서 멱등 처리 필요
- **확인 쿼리**:
```sql
SELECT id, aggregate_type, event_type, status, available_at, created_at
FROM outbox_events
WHERE status = 'PENDING'
ORDER BY available_at;
```

---

## 6. 모니터링 및 알림

### 6-1. Prometheus AlertRule (HikariCP 풀 포화 — 기존 alerts.yml)

```yaml
# monitoring/prometheus/alerts.yml 에 이미 정의됨
- alert: HikariPoolSaturation
  expr: hikaricp_connections_pending > 0
  for: 1m
  labels: {severity: warning}
```

### 6-2. 백업 성공 여부 알림

```bash
# 백업 완료 후 상태 파일 갱신
echo "$(date -u +%Y-%m-%dT%H:%M:%SZ)" > /backup/last_successful_backup.txt

# Prometheus textfile collector로 노출 (node_exporter --collector.textfile)
echo "custody_db_backup_last_success_timestamp $(date +%s)" \
  > /var/lib/node_exporter/custody_backup.prom
```

```yaml
# AlertRule 추가 예시
- alert: CustodyDbBackupMissing
  expr: (time() - custody_db_backup_last_success_timestamp) > 86400
  for: 5m
  labels: {severity: critical}
  annotations:
    summary: "Custody DB 백업이 24시간 이상 없음"
```

---

## 7. 접근 권한 관리

| 역할 | 권한 | 사용 목적 |
|------|------|-----------|
| `custody_app` | SELECT, INSERT, UPDATE, DELETE | 애플리케이션 운영 |
| `custody_readonly` | SELECT | 모니터링, 읽기 전용 분석 |
| `custody_migration` | DDL + DML | Flyway 마이그레이션 전용 |
| `replication_user` | REPLICATION | pg_basebackup, 스트리밍 복제 |

```sql
-- 최소 권한 원칙 적용
CREATE ROLE custody_app LOGIN PASSWORD '${CUSTODY_DB_PASSWORD}';
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO custody_app;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO custody_app;
```
