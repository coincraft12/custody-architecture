# 수탁 지갑 설계 자료 (통합본)

기반 자료: `Session1_Execution Fundamentals` ~ `Session7_Security Control Best Practice`

## 1. 설계 목표
1. 잘못된 송금을 구조적으로 차단
2. 키 유출·내부자·피싱·정책 변조 위험 최소화
3. 멀티체인(EVM/UTXO/Cosmos/Solana) 확장 가능
4. 장애·재시도·중복 상황에서도 원장 정합성 보장
5. 감사·규제 대응 가능한 추적성 확보

## 2. 표준 아키텍처 (7모듈)
1. `API/Orchestrator`: 요청 수신, 워크플로우 제어, idempotency 키 강제
2. `Policy Engine`: 한도·주소·속도·리스크 룰 판정
3. `Approval`: 4-eyes/쿼럼 승인, 고액 추가 승인
4. `Signer (HSM/MPC)`: 서명 전용, 정책 결정 금지
5. `Broadcaster`: 온체인 전파, replacement/speed-up 처리
6. `Confirmation Tracker`: included/safe/finalized 판정
7. `Ledger/Audit`: 내부 원장(SoT), append-only 감사 로그

핵심 원칙: `Policy/Approval`과 `Signer`를 분리하고, Signer 경계에서 정책을 재검증한다.

## 3. 핵심 데이터 모델
1. `Withdrawal` (업무 단위)
2. `TxAttempt` (체인 시도 단위, Withdrawal:N 관계)
3. `NonceReservation` (`chain, from, nonce` 유니크)
4. `PolicyDecision`, `ApprovalDecision`
5. `WhitelistAddress` (등록/보류/활성 상태)
6. `LedgerEntry` (Available/Reserved/Pending/Settled)

## 4. 상태머신
### 4.1 Withdrawal
`REQUESTED -> POLICY_CHECKED -> APPROVAL_PENDING -> APPROVED -> SIGNING -> SIGNED -> BROADCASTED -> INCLUDED -> SAFE/FINALIZED -> LEDGER_POSTED -> COMPLETED`

### 4.2 TxAttempt
`CREATED -> SIGNED -> SENT_TO_RPC -> SEEN_IN_MEMPOOL -> INCLUDED -> CONFIRMED -> FINALIZED`

### 4.3 Address Whitelist
`REGISTERED -> APPROVED -> HOLDING(기본 48h) -> ACTIVE`

### 4.4 Policy Change
`PROPOSED -> QUORUM_APPROVED -> DELAYED -> APPLIED`

롤백 경로를 별도로 두고, 정책 변경 이벤트는 고위험 이벤트로 취급한다.

## 5. 보안 통제 (핵심 패턴)
1. 주소 화이트리스트 + 보류(기본 48시간)
2. 정책 엔진 무결성(TEE/HSM 경계, 변경 이벤트 지연·감사)
3. Signer 경계 재검증(최종 방어선)
4. 키 구조 분리(기본 2-of-3, 고액은 3-of-5 이상)
5. 정책 변경·화이트리스트 변경은 출금 승인과 별도 프로세스
6. 메시지 서명은 EIP-712 중심, `chainId/verifyingContract/nonce/deadline` 강제

## 6. 출금 신뢰성 설계
1. Broadcaster와 Confirmation Tracker를 분리 운영
2. 재시도 3원칙: 멱등성 + 재생방지 + 지수백오프(지터 포함)
3. `tx_hash` 단독 추적 금지, `(chain, from, nonce)` 병행 추적
4. 예외 6종 표준화
   - `FAILED`
   - `EXPIRED`
   - `DROPPED`
   - `REPLACED`
   - `REVERTED`
   - `RPC_INCONSISTENT`
5. 멀티 RPC + 쿼럼 판정, head mismatch 모니터링
6. 원장 반영은 finality 정책 충족 후 수행

## 7. 멀티체인 어댑터 표준
### 7.1 공통 메서드
1. `prepareSend(request)`
2. `broadcast(preparedTx)`
3. `getTxStatus(query)`
4. `getHeads()`

### 7.2 원칙
1. 체인별 차이를 숨기지 말고 정책 파라미터로 노출
2. 어댑터는 관측 사실만 반환, 정책 결정은 상위 계층이 수행
3. confirmations 하드코딩 대신 `FinalityPolicy`로 완료 선언

### 7.3 Coinbase x402(402x) 결제 레이어 적용
1. x402는 EIP 대체가 아니라 `HTTP 402` 기반의 API 결제 오케스트레이션 계층으로 취급
2. 결제 흐름도 기존 7모듈 통제로 관리
   - `API/Orchestrator`: 결제 intent/idempotency 발급
   - `Policy/Approval`: 엔드포인트별 과금 한도, 자동 결제 이상 탐지, 고액 승인
   - `Signer`: 결제 스코프(수신자/금액/만료/nonce) 검증 후 서명
   - `Ledger/Audit`: usage -> reserve -> commit -> settle 이벤트를 append-only로 기록
3. 구현 필수 요소
   - `payment_intent_id`, `authorization_id`, `idempotency_key`, `correlation_id`
   - `UNIQUE(idempotency_key, merchant_id, endpoint)` 및 `UNIQUE(chain, from, nonce)`
   - `used_authorization_digest` 저장으로 replay 차단
4. 주요 리스크와 통제
   - 중복 과금: idempotency 강제 + 동일 응답 재사용
   - 재생 공격: nonce/deadline/digest 재사용 금지
   - 정산 불일치: Outbox + reconciliation + finality 이후 커밋
   - 정책 오남용: 정책 변경 지연 반영 + 쿼럼 승인 + 감사 로그

## 8. 운영 지표 (KPI)
1. `time_to_broadcast`
2. `time_to_inclusion`
3. `time_to_safe`, `time_to_finalized`
4. `dropped_count`, `replaced_count`, `reorged_out_count`
5. `rpc_error_rate`, `head_mismatch_rate`
6. `pending_age_p95`
7. `reserve_without_commit_count`

### 8.2 KPI 대시보드 예시 (권장 최소 구성)

아래 4개 패널만 있어도 운영자는 병목 지점을 빠르게 좁힐 수 있다.

| 패널 | 보는 질문 |
|---|---|
| `Broadcast -> Inclusion` 시간 분포 | 지금 문제는 전파 지연인가 |
| `Pending Age P95` + `Dropped/Replaced` 추이 | 수수료 정책 또는 mempool 문제가 커지고 있는가 |
| RPC 공급자별 `error_rate/head_mismatch_rate` | 특정 RPC 공급자가 오판 또는 장애 상태인가 |
| `Reserve without Commit/Settle` 건수 | 체인 성공 후 원장 반영이 밀리고 있는가 |

강의용 메모:

- 대시보드는 “지표를 많이 보여 주는 화면”이 아니라 “원인 분류를 빨리 하는 화면”이어야 한다.
- 저위험 실습에서는 1~2개 패널만 보여 줘도 충분하지만, 운영 강의에서는 최소 4개 패널 구성이 좋다.

### 8.1 Correlation ID / MDC 로깅 표준
1. 모든 요청 시작 시 `correlation_id`를 생성(또는 전달값 재사용)하고 전 구간 전파
2. 애플리케이션 로그 출력 시 MDC에 다음 키를 기본 주입
   - `correlation_id`
   - `withdrawal_id`
   - `attempt_id`
   - `idempotency_key`
   - `nonce_key`
   - `tx_hash`
3. 로그 포맷(JSON 또는 패턴)에 MDC 필드를 강제 포함
4. 외부 호출(RPC/KMS/메시지 큐)에도 동일 `correlation_id`를 헤더/메타데이터로 전달

## 9. 현재 코드 기준 구현 현황

아래 표는 현재 저장소의 코드 기준으로 “어디까지 구현되어 있는가”를 정리한 것이다. 이 문서의 앞부분은 **목표 아키텍처**이고, 본 절은 **현재 교육용 구현 상태**다.

### 9.1 구현 완료

| 영역 | 현재 상태 | 관련 코드/메모 |
|---|---|---|
| 출금 생성 + 멱등성 | `Idempotency-Key` 기반으로 동일 요청 중복 생성 방지 | `WithdrawalService#createOrGet`, `Withdrawal.idempotencyKey` |
| Withdrawal / TxAttempt 분리 | 출금 1건에 대해 여러 시도 생성 및 이력 조회 가능 | `AttemptService`, `TxAttempt`, `/withdrawals/{id}/attempts` |
| 기본 출금 상태 전이 | `W0 -> W1 -> W3 -> W4 -> W5 -> W6 -> W7` 흐름 구현 | `WithdrawalService`, `RetryReplaceService#sync/simulateConfirmation` |
| Retry / Replace | retry는 새 nonce, replace는 동일 nonce fee bump로 처리 | `RetryReplaceService#retry`, `#replace` |
| Ledger RESERVE / SETTLE | 승인 시 `RESERVE`, finalize 시 `SETTLE` 기록 | `LedgerService#reserve`, `#settle` |
| Broadcaster / Tracker 분리 | 브로드캐스트와 포함 확인 책임이 분리되어 있음 | `EvmRpcAdapter`, `ConfirmationTracker` |
| 정책 엔진 기본형 | 금액 제한 + 목적지 화이트리스트 룰 적용 | `AmountLimitPolicyRule`, `ToAddressWhitelistPolicyRule` |
| 화이트리스트 워크플로우 | `REGISTERED -> APPROVED -> HOLDING -> ACTIVE -> REVOKED` 및 scheduler 승격 구현 | `WhitelistService`, `WhitelistController` |
| 실습/시뮬레이션 경로 | fake chain 기반으로 confirm/finalize/replaced 시나리오 재현 가능 | `SimController`, `FakeChain`, 통합테스트 |

### 9.2 부분 구현

| 영역 | 현재 상태 | 앞으로 구현해야 할 부분 |
|---|---|---|
| Approval | 구조는 있으나 현재는 auto-approve placeholder | 실제 승인 task, quorum, 승인자 기록, human-in-the-loop |
| Signer 경계 검증 | 실제 서명 경로는 있으나 강의안 수준의 구조화 입력 검증은 부족 | `policy_decision_id`, `approval_bundle`, `deadline`, `verifyingContract` 검증 |
| Confirmation Tracker | receipt polling 후 `INCLUDED` 반영 중심 | `SAFE/FINALIZED` 분리, 리오그 대응, finality policy 적용 |
| TxAttempt 상태머신 | `CREATED/BROADCASTED/INCLUDED/SUCCESS/FAILED/REPLACED` 수준 | `SEEN_IN_MEMPOOL`, `CONFIRMED`, `FINALIZED`, dropped/expired 분리 |
| 예외 처리 | 일부 exception type과 retry/replace 흐름 존재 | `DROPPED`, `EXPIRED`, `RPC_INCONSISTENT`, `REVERTED` 자동 분류와 런북 연결 |
| 관측성 | 로그와 API는 있으나 운영 대시보드/알람 체계는 코드에 녹아 있지 않음 | MDC 일관화, metrics, alerts, provider별 관측 저장 |
| 멀티체인 추상화 | `ChainAdapter` 인터페이스와 EVM adapter는 존재 | UTXO/Cosmos/Solana용 adapter, capability 선언, finality 차등 처리 |

### 9.3 미구현

| 영역 | 현재 상태 | 앞으로 구현해야 할 부분 |
|---|---|---|
| NonceReservation DB 모델 | 현재 운영형 reservation 테이블 없음 | `UNIQUE(chain, from, nonce)` 제약, reservation lifecycle, recovery 로직 |
| 멀티 RPC 쿼럼 | 단일 RPC adapter 중심 | 다중 provider 조회, quorum 판정, provider degrade mode |
| 운영형 finality policy | 코드상 체인별 safe/finalized 완료 선언 없음 | 체인/금액/risk tier별 완료 기준 테이블 |
| Outbox / 비동기 메시징 | DB-외부호출 분리용 outbox 없음 | broadcast/ledger/reconciliation 이벤트 outbox 도입 |
| Reconciliation 엔진 | ledger와 chain 사이 상시 대사 엔진 없음 | chain snapshot 대사, 예외 큐, 수동 개입 플로우 |
| 정책 변경 상태머신 | 강의안에는 있으나 코드엔 없음 | `PROPOSED -> QUORUM_APPROVED -> DELAYED -> APPLIED` 구현 |
| x402 결제 계층 | 설계 문서상 확장 아이디어만 존재 | payment intent, auth digest, duplicate charge 방지 구현 |

### 9.4 현재 코드에 대한 한 줄 평가

현재 저장소는 **교육용 custody 백엔드의 핵심 뼈대**는 구현되어 있다. 즉, `Withdrawal/TxAttempt`, idempotency, retry/replace, reserve/settle, whitelist, fake-chain 기반 실습은 작동한다. 반면 운영 완성형 custody에서 필요한 approval 실체화, nonce reservation DB화, 멀티 RPC, finality policy, reconciliation/outbox는 아직 남아 있다.

## 10. 운영형 DB 스키마 초안

아래 초안은 PostgreSQL 같은 관계형 DB를 기준으로 한 운영형 custody 스키마다. 목표는 단순 저장이 아니라, **중복 방지, 동시성 제어, 감사 가능성, 장애 복구 가능성**을 DB 레벨에서 보장하는 것이다.

### 10.1 설계 원칙

1. 업무 단위와 체인 실행 단위를 분리한다.
2. 핵심 멱등성 키는 DB 유니크 제약으로 강제한다.
3. ledger와 audit는 append-only를 기본으로 한다.
4. 정책/승인/서명/브로드캐스트/확정 이벤트를 서로 추적 가능해야 한다.
5. 운영자가 조회할 인덱스를 처음부터 설계한다.

### 10.2 핵심 테이블

#### `withdrawals`

업무 단위 출금 요청.

주요 컬럼:

| 컬럼 | 타입 예시 | 설명 |
|---|---|---|
| `id` | `uuid` PK | withdrawal 식별자 |
| `idempotency_key` | `varchar(128)` | API 요청 멱등성 키 |
| `chain_type` | `varchar(32)` | `EVM`, `BTC`, `COSMOS` 등 |
| `from_address` | `varchar(128)` | 출금 지갑 주소 |
| `to_address` | `varchar(128)` | 목적지 주소 |
| `asset` | `varchar(32)` | 자산 코드 |
| `amount` | `numeric(38,0)` | smallest unit 기준 금액 |
| `status` | `varchar(32)` | `W0_REQUESTED` 등 |
| `policy_decision_id` | `uuid` nullable | 연결된 정책 판정 |
| `approval_bundle_id` | `uuid` nullable | 연결된 승인 묶음 |
| `correlation_id` | `varchar(128)` | 로그/트레이스 연결 |
| `requested_by` | `varchar(128)` | 요청자 |
| `created_at` | `timestamptz` | 생성 시각 |
| `updated_at` | `timestamptz` | 수정 시각 |

제약/인덱스:

- `PRIMARY KEY (id)`
- `UNIQUE (idempotency_key)`
- `INDEX (status, created_at)`
- `INDEX (from_address, created_at)`
- `INDEX (correlation_id)`

#### `tx_attempts`

체인 시도 단위. 동일 withdrawal에 여러 건 존재 가능.

주요 컬럼:

| 컬럼 | 타입 예시 | 설명 |
|---|---|---|
| `id` | `uuid` PK | attempt 식별자 |
| `withdrawal_id` | `uuid` FK | 상위 withdrawal |
| `attempt_no` | `int` | 1..N |
| `from_address` | `varchar(128)` | 발신 주소 |
| `nonce` | `bigint` | 계정 기반 체인의 nonce |
| `attempt_group_key` | `varchar(180)` | `(chain, from, nonce)` 파생 키 |
| `tx_hash` | `varchar(80)` nullable | 브로드캐스트 후 채워짐 |
| `status` | `varchar(32)` | attempt 상태 |
| `canonical` | `boolean` | 현재 대표 attempt 여부 |
| `exception_type` | `varchar(32)` nullable | `REPLACED`, `FAILED_SYSTEM` 등 |
| `exception_detail` | `varchar(500)` nullable | 상세 설명 |
| `max_priority_fee_per_gas` | `numeric(38,0)` nullable | EVM fee |
| `max_fee_per_gas` | `numeric(38,0)` nullable | EVM fee |
| `broadcasted_at` | `timestamptz` nullable | 전파 시각 |
| `included_at` | `timestamptz` nullable | 포함 시각 |
| `finalized_at` | `timestamptz` nullable | 확정 시각 |
| `created_at` | `timestamptz` | 생성 시각 |

제약/인덱스:

- `PRIMARY KEY (id)`
- `FOREIGN KEY (withdrawal_id) REFERENCES withdrawals(id)`
- `UNIQUE (withdrawal_id, attempt_no)`
- `INDEX (withdrawal_id, attempt_no)`
- `INDEX (attempt_group_key)`
- `INDEX (tx_hash)`
- `INDEX (status, canonical)`

#### `nonce_reservations`

운영형 핵심 테이블. `(chain, from, nonce)`를 DB 레벨에서 보호.

주요 컬럼:

| 컬럼 | 타입 예시 | 설명 |
|---|---|---|
| `id` | `uuid` PK | reservation 식별자 |
| `chain_type` | `varchar(32)` | 체인 |
| `from_address` | `varchar(128)` | nonce 공유 주소 |
| `nonce` | `bigint` | 예약된 nonce |
| `withdrawal_id` | `uuid` FK nullable | 연결된 withdrawal |
| `attempt_id` | `uuid` FK nullable | 연결된 attempt |
| `status` | `varchar(32)` | `RESERVED`, `BROADCASTED`, `FINALIZED`, `RELEASED`, `SUPERSEDED` |
| `expires_at` | `timestamptz` nullable | 미사용 reservation 만료 |
| `created_at` | `timestamptz` | 생성 시각 |
| `updated_at` | `timestamptz` | 수정 시각 |

제약/인덱스:

- `PRIMARY KEY (id)`
- `UNIQUE (chain_type, from_address, nonce)`
- `INDEX (withdrawal_id)`
- `INDEX (attempt_id)`
- `INDEX (status, expires_at)`

#### `ledger_entries`

append-only 원장 이벤트.

주요 컬럼:

| 컬럼 | 타입 예시 | 설명 |
|---|---|---|
| `id` | `uuid` PK | ledger entry 식별자 |
| `withdrawal_id` | `uuid` FK | 연결된 withdrawal |
| `attempt_id` | `uuid` FK nullable | 관련 attempt |
| `entry_type` | `varchar(32)` | `RESERVE`, `SETTLE`, `RELEASE`, `ADJUSTMENT` |
| `asset` | `varchar(32)` | 자산 |
| `amount` | `numeric(38,0)` | 금액 |
| `from_address` | `varchar(128)` | 출발 주소 |
| `to_address` | `varchar(128)` | 목적지 주소 |
| `reference_id` | `varchar(128)` nullable | 외부 참조 |
| `created_at` | `timestamptz` | 생성 시각 |

제약/인덱스:

- `PRIMARY KEY (id)`
- `INDEX (withdrawal_id, created_at)`
- `INDEX (attempt_id, created_at)`
- `INDEX (entry_type, created_at)`

#### `policy_decisions`

정책 판정 결과 저장.

주요 컬럼:

| 컬럼 | 타입 예시 | 설명 |
|---|---|---|
| `id` | `uuid` PK | policy decision 식별자 |
| `withdrawal_id` | `uuid` FK | 대상 withdrawal |
| `decision` | `varchar(16)` | `ALLOW`, `REJECT`, `REVIEW` |
| `reason_code` | `varchar(64)` | 규칙 코드 |
| `reason_detail` | `varchar(500)` | 상세 설명 |
| `rule_snapshot` | `jsonb` | 당시 룰 스냅샷 |
| `created_at` | `timestamptz` | 생성 시각 |

제약/인덱스:

- `PRIMARY KEY (id)`
- `INDEX (withdrawal_id, created_at)`
- `INDEX (decision, created_at)`

#### `approval_tasks`

사람 승인 workflow.

주요 컬럼:

| 컬럼 | 타입 예시 | 설명 |
|---|---|---|
| `id` | `uuid` PK | approval task 식별자 |
| `withdrawal_id` | `uuid` FK | 대상 withdrawal |
| `risk_tier` | `varchar(16)` | `LOW`, `MEDIUM`, `HIGH` |
| `required_approvals` | `int` | 필요 승인 수 |
| `status` | `varchar(32)` | `PENDING`, `APPROVED`, `REJECTED`, `EXPIRED` |
| `expires_at` | `timestamptz` nullable | 승인 만료 |
| `created_at` | `timestamptz` | 생성 시각 |
| `updated_at` | `timestamptz` | 수정 시각 |

제약/인덱스:

- `PRIMARY KEY (id)`
- `INDEX (withdrawal_id)`
- `INDEX (status, expires_at)`

#### `approval_decisions`

개별 승인자 결정 이력.

주요 컬럼:

| 컬럼 | 타입 예시 | 설명 |
|---|---|---|
| `id` | `uuid` PK | 개별 승인 식별자 |
| `approval_task_id` | `uuid` FK | 상위 approval task |
| `approver_id` | `varchar(128)` | 승인자 |
| `decision` | `varchar(16)` | `APPROVE`, `REJECT` |
| `comment` | `varchar(500)` nullable | 코멘트 |
| `created_at` | `timestamptz` | 생성 시각 |

제약/인덱스:

- `PRIMARY KEY (id)`
- `UNIQUE (approval_task_id, approver_id)`
- `INDEX (approval_task_id, created_at)`

#### `whitelist_addresses`

목적지 주소 화이트리스트.

주요 컬럼:

| 컬럼 | 타입 예시 | 설명 |
|---|---|---|
| `id` | `uuid` PK | 항목 식별자 |
| `address` | `varchar(128)` | 주소 |
| `chain_type` | `varchar(32)` | 체인 |
| `status` | `varchar(32)` | `REGISTERED`, `APPROVED`, `HOLDING`, `ACTIVE`, `REVOKED`, `EXPIRED` |
| `registered_by` | `varchar(128)` | 등록자 |
| `approved_by` | `varchar(128)` nullable | 승인자 |
| `revoked_by` | `varchar(128)` nullable | 회수자 |
| `note` | `varchar(500)` nullable | 메모 |
| `active_after` | `timestamptz` nullable | hold 종료 시각 |
| `registered_at` | `timestamptz` | 등록 시각 |
| `updated_at` | `timestamptz` | 수정 시각 |

제약/인덱스:

- `PRIMARY KEY (id)`
- `UNIQUE (address, chain_type)`
- `INDEX (status, active_after)`

#### `policy_change_requests`

정책 변경 상태머신.

주요 컬럼:

| 컬럼 | 타입 예시 | 설명 |
|---|---|---|
| `id` | `uuid` PK | 정책 변경 요청 식별자 |
| `change_type` | `varchar(64)` | 한도 변경, whitelist rule 변경 등 |
| `status` | `varchar(32)` | `PROPOSED`, `QUORUM_APPROVED`, `DELAYED`, `APPLIED`, `ROLLED_BACK` |
| `payload` | `jsonb` | 변경 내용 |
| `requested_by` | `varchar(128)` | 요청자 |
| `approved_at` | `timestamptz` nullable | 승인 시각 |
| `apply_after` | `timestamptz` nullable | 지연 적용 시각 |
| `applied_at` | `timestamptz` nullable | 실제 반영 시각 |
| `created_at` | `timestamptz` | 생성 시각 |

제약/인덱스:

- `PRIMARY KEY (id)`
- `INDEX (status, apply_after)`

#### `outbox_events`

외부 호출/후속 처리 분리를 위한 outbox.

주요 컬럼:

| 컬럼 | 타입 예시 | 설명 |
|---|---|---|
| `id` | `uuid` PK | 이벤트 식별자 |
| `aggregate_type` | `varchar(64)` | `WITHDRAWAL`, `ATTEMPT`, `LEDGER` |
| `aggregate_id` | `uuid` | 대상 aggregate |
| `event_type` | `varchar(64)` | `WITHDRAWAL_RESERVED`, `ATTEMPT_BROADCASTED` 등 |
| `payload` | `jsonb` | 이벤트 payload |
| `status` | `varchar(32)` | `PENDING`, `SENT`, `FAILED`, `DEAD_LETTER` |
| `attempt_count` | `int` | relay 재시도 횟수 |
| `available_at` | `timestamptz` | 재시도 가능 시각 |
| `created_at` | `timestamptz` | 생성 시각 |
| `sent_at` | `timestamptz` nullable | 전송 완료 시각 |

제약/인덱스:

- `PRIMARY KEY (id)`
- `INDEX (status, available_at)`
- `INDEX (aggregate_type, aggregate_id)`

#### `rpc_observation_snapshots`

멀티 RPC 운영용 관측 스냅샷.

주요 컬럼:

| 컬럼 | 타입 예시 | 설명 |
|---|---|---|
| `id` | `uuid` PK | 스냅샷 식별자 |
| `provider_name` | `varchar(64)` | RPC 공급자 이름 |
| `chain_type` | `varchar(32)` | 체인 |
| `tx_hash` | `varchar(80)` nullable | 대상 tx |
| `head_number` | `bigint` nullable | 관측 head |
| `receipt_found` | `boolean` nullable | receipt 존재 여부 |
| `receipt_block_number` | `bigint` nullable | receipt block |
| `observation_type` | `varchar(32)` | `HEAD`, `RECEIPT`, `TX_LOOKUP` |
| `raw_payload` | `jsonb` nullable | 원문 |
| `created_at` | `timestamptz` | 생성 시각 |

제약/인덱스:

- `PRIMARY KEY (id)`
- `INDEX (provider_name, chain_type, created_at)`
- `INDEX (tx_hash, created_at)`

### 10.3 운영형 필수 유니크 제약

가장 중요한 유니크 제약은 아래 4개다.

1. `withdrawals.idempotency_key`
2. `nonce_reservations (chain_type, from_address, nonce)`
3. `tx_attempts (withdrawal_id, attempt_no)`
4. `approval_decisions (approval_task_id, approver_id)`

### 10.4 운영자가 자주 쓰는 조회 패턴

운영형 DB는 쓰기만 중요한 것이 아니라, “사고 났을 때 바로 찾을 수 있는가”도 중요하다.

필수 조회 예시:

- 특정 `withdrawal_id`의 전체 상태 이력 조회
- 특정 `(chain, from, nonce)`의 canonical attempt 찾기
- `RESERVE`는 있는데 `SETTLE`이 없는 withdrawal 목록 찾기
- 특정 RPC provider의 `head_mismatch_rate` 집계
- `HOLDING` 상태에서 곧 `ACTIVE`가 될 whitelist 주소 조회
- `PENDING` outbox 이벤트 적체 조회

### 10.5 PostgreSQL 기준 구현 우선순위

1. `withdrawals`, `tx_attempts`, `ledger_entries`, `whitelist_addresses`
2. `nonce_reservations`, `policy_decisions`
3. `approval_tasks`, `approval_decisions`, `policy_change_requests`
4. `outbox_events`, `rpc_observation_snapshots`

### 10.6 현재 코드와의 직접 갭

현재 코드 기준으로 가장 먼저 메워야 하는 DB 갭은 아래다.

| 우선순위 | 부족한 점 | 왜 중요한가 |
|---|---|---|
| P0 | `nonce_reservations` 없음 | 운영형 동시성 제어의 핵심 |
| P0 | 운영형 approval 테이블 없음 | auto-approve에서 실제 승인으로 전환 불가 |
| P1 | outbox 없음 | DB 상태와 외부 호출 불일치 위험 |
| P1 | RPC observation 저장 없음 | 멀티 RPC quorum 운영 불가 |
| P1 | policy change 상태머신 테이블 없음 | 지연 적용/롤백 통제 불가 |

## 11. 구현 로드맵

### 10.1 우선순위 로드맵

| 단계 | 우선순위 | 목표 | 핵심 산출물 |
|---|---|---|---|
| Phase A | P0 | 교육용 구현을 “운영형 기초” 수준으로 끌어올리기 | NonceReservation DB화, MDC/metrics, tracker 고도화 |
| Phase B | P1 | 승인/서명/정책 경계를 운영 수준으로 강화 | Approval workflow, Signer input contract, policy change workflow |
| Phase C | P1 | 외부 의존성과 체인 불확실성에 강한 구조 만들기 | 멀티 RPC quorum, degrade mode, finality policy |
| Phase D | P2 | 회계/운영 완성도 강화 | Outbox, reconciliation, 운영 대시보드 |
| Phase E | P3 | 확장 주제 적용 | 멀티체인 adapter 확장, x402 결제 계층 |

### 10.2 단계별 설명

#### Phase A: 운영형 기초 완성

목표:

1. 현재 데모 중심 상태머신을 운영형 최소 수준으로 보강
2. nonce, tracking, observability에서 사고 가능성이 높은 부분부터 닫기

완료 기준:

- `(chain, from, nonce)` 유니크 제약이 DB에 존재
- `correlation_id`, `withdrawal_id`, `attempt_id`, `nonce_key`, `tx_hash`가 로그에 일관되게 남음
- tracker가 `INCLUDED`와 `SAFE/FINALIZED`를 구분할 준비를 갖춤

#### Phase B: 승인/서명 경계 강화

목표:

1. auto-approve placeholder를 실제 승인 흐름으로 교체
2. signer를 “서명기”에서 “구조화 입력을 검증하는 마지막 방어선”으로 강화

완료 기준:

- 승인 task, 승인자 기록, quorum 확인이 존재
- signer 요청에 `policy_decision_id`, `approval_bundle_id`, `chain_id`, `deadline` 등이 포함됨
- 승인 없는 서명 요청은 거부됨

#### Phase C: 체인 불확실성 대응

목표:

1. 단일 RPC 의존 제거
2. 체인별 finality와 provider 불일치를 정책적으로 다루기

완료 기준:

- 다중 RPC provider에서 head/receipt quorum 판정 가능
- `NORMAL/DEGRADED_READ/DEGRADED_WRITE` 수준의 강등 운용 가능
- 체인/리스크 tier별 finality policy가 존재

#### Phase D: 회계/운영 완성도 강화

목표:

1. DB 상태와 외부 호출의 불일치를 줄임
2. ledger와 chain 사이 대사 프로세스를 도입

완료 기준:

- outbox 기반 비동기 이벤트 전파 도입
- reserve without settle, chain-ledger mismatch를 탐지
- reconciliation 예외 큐와 수동 처리 플로우 존재

#### Phase E: 확장 주제

목표:

1. 멀티체인 지원을 실질적으로 확장
2. 같은 통제 원칙을 x402 같은 API 결제 계층까지 확장

완료 기준:

- EVM 외 adapter 추가
- x402 intent/auth/settlement 모델 실험 가능

## 12. 티켓 단위 TODO List

아래 항목은 실제 이슈 트래커에 바로 옮길 수 있도록 잘게 쪼갠 작업 단위다.

### 11.1 P0 티켓

- [ ] `T01` `NonceReservation` 엔티티/리포지토리 추가
  - `chain`, `from_address`, `nonce` 유니크 제약
  - reservation 상태와 만료시간 포함

- [ ] `T02` 메모리 기반 nonce 처리 제거 또는 축소
  - `NonceAllocator`를 DB reservation 기반 흐름으로 대체

- [ ] `T03` `WithdrawalService`에서 nonce 예약 흐름 연결
  - attempt 생성 전 reservation 생성
  - 실패 시 reservation release/recovery 규칙 정의

- [ ] `T04` 로그 MDC 표준화
  - `correlation_id`, `withdrawal_id`, `attempt_id`, `idempotency_key`, `nonce_key`, `tx_hash` 주입

- [ ] `T05` 핵심 메트릭 추가
  - `time_to_broadcast`
  - `time_to_inclusion`
  - `pending_age`
  - `replaced/dropped` 카운트

- [ ] `T06` `ConfirmationTracker` 상태 고도화 1차
  - `INCLUDED`와 `SUCCESS/FAILED`를 더 명확히 분리
  - 리오그 대비 확장 포인트 추가

- [ ] `T07` `TxAttemptStatus` 확장
  - `SEEN_IN_MEMPOOL`, `CONFIRMED`, `FINALIZED`, `DROPPED`, `EXPIRED` 검토

- [ ] `T08` 운영용 실패 분류 기초 추가
  - `FAILED`, `REPLACED`, `REVERTED`, `RPC_INCONSISTENT` 저장 구조 보강

### 11.2 P1 티켓

- [ ] `T09` Approval task 모델 추가
  - 승인 요청/승인자/승인시각/코멘트 저장

- [ ] `T10` quorum approval 정책 도입
  - 금액 구간별 요구 승인 수 정의

- [ ] `T11` high-risk withdrawal human-in-the-loop 추가
  - 금액/신규 주소/정책 변경 직후 조건 연동

- [ ] `T12` Signer input contract 정의
  - `request_id`, `withdrawal_id`, `attempt_id`, `chain_id`, `deadline`, `policy_decision_id`, `approval_bundle_id`

- [ ] `T13` signer 재검증 로직 구현
  - 승인 payload와 서명 payload 일치 여부 검사
  - 만료/재사용 request 거부

- [ ] `T14` 정책 변경 상태머신 모델 추가
  - `PROPOSED -> QUORUM_APPROVED -> DELAYED -> APPLIED`

- [ ] `T15` whitelist와 policy change 감사 로그 추가
  - 변경자, 승인자, 만료시각, 사유 저장

### 11.3 P1-P2 티켓

- [ ] `T16` 멀티 RPC provider 추상화 도입
  - provider 목록 관리
  - provider별 health 정보 저장

- [ ] `T17` head/receipt quorum 판정 로직 구현
  - 단일 provider 오판 방지

- [ ] `T18` degrade mode 정책 구현
  - `NORMAL`, `DEGRADED_READ`, `DEGRADED_WRITE`, `MANUAL_APPROVAL_ONLY`

- [ ] `T19` finality policy 테이블 추가
  - 체인/리스크 tier별 완료 기준 설정

- [ ] `T20` tracker의 `SAFE/FINALIZED` 판정 구현
  - settlement 이전 finality 확인

### 11.4 P2 티켓

- [ ] `T21` outbox 테이블 및 relay worker 추가
  - DB 커밋과 외부 호출 분리

- [ ] `T22` ledger/chain reconciliation 배치 추가
  - reserve without settle 탐지
  - chain success but ledger miss 탐지

- [ ] `T23` reconciliation 예외 큐 추가
  - 수동 개입 대상 분류

- [ ] `T24` 운영 대시보드 초안 구성
  - provider 상태
  - inclusion latency
  - reserve without settle

### 11.5 P3 티켓

- [ ] `T25` UTXO adapter 초안 추가
  - input selection / tx status snapshot 모델 정의

- [ ] `T26` Cosmos adapter 초안 추가
  - `account_number`, `sequence` 반영

- [ ] `T27` x402 payment intent / authorization 모델 설계
  - `payment_intent_id`, `authorization_id`, `authorization_digest`

- [ ] `T28` x402 duplicate charge 방지 구현
  - `UNIQUE(idempotency_key, merchant_id, endpoint)`

## 13. 구현 체크리스트
- [ ] `correlation_id`를 생성/전파하고 MDC에 주입해 로그에 항상 출력
- [ ] `withdrawal_id`, `attempt_id`, `idempotency_key`, `nonce_key`를 전 구간 추적
- [ ] `(chain, from, nonce)` 유니크 제약 적용
- [ ] Signer 입력 포맷에 policy/approval proof 포함
- [ ] 고액 출금 human-in-the-loop 강제
- [ ] 정책/권한 변경 만료(expiry)와 자동 회수 구현
- [ ] 원장 이벤트 append-only 저장
- [ ] 재시도 정책(백오프+지터) 및 수동 개입 경계 정의
- [ ] 멀티 RPC 불일치 대응(쿼럼/강등 모드) 구현
- [ ] x402 결제에 `payment_intent_id/idempotency_key/authorization_digest` 통제 적용

## 14. 운영형 Custody 회사 기준 상세 TODO List

아래 목록은 "우리가 실제로 커스터디 지갑을 운영하는 회사"라고 가정했을 때 필요한 실행 항목을 세부 작업 단위로 정리한 것이다.

핵심 원칙:

1. 출금 성공률보다 먼저 "잘못된 출금이 절대 나가지 않는 구조"를 만든다.
2. 코드 구현과 동시에 운영 통제, 감사 가능성, 사고 대응 체계를 같이 만든다.
3. "나중에 문서화"가 아니라, 정책/런북/승인 기준/알람 기준을 구현과 같이 남긴다.

### 14.1 Program / Governance

- [ ] `OPS-001` 서비스 범위 정의
  - 어떤 자산을 커스터디할지 결정
  - 어떤 체인을 1차 지원할지 결정
  - hot / warm / cold wallet 비율 정의
  - 내부 자금과 고객 자금의 분리 원칙 정의

- [ ] `OPS-002` 리스크 등급 체계 정의
  - 소액/중액/고액 출금 구간 정의
  - 신규 주소, 신규 체인, 신규 자산에 대한 high-risk 기준 정의
  - 고객 등급별 추가 통제 여부 정의

- [ ] `OPS-003` 권한 모델 정의
  - requester / approver / signer-operator / finance-operator / auditor / incident-commander 역할 분리
  - 역할별 가능한 API/화면/운영 액션 목록 정의
  - 한 사람이 동시에 가질 수 없는 역할 조합 정의

- [ ] `OPS-004` 변경관리 프로세스 정의
  - 정책 변경, 화이트리스트 변경, signer 교체, RPC provider 추가의 승인 절차 정의
  - emergency change와 normal change를 구분
  - 변경 후 rollback 조건 정의

- [ ] `OPS-005` 보관 정책 정의
  - 거래 이력, 감사 로그, 승인 기록, 운영 로그의 보관 기간 정의
  - 법무/컴플라이언스 요구사항 반영

### 14.2 Product / Business Rules

- [ ] `PRD-001` 입출금 상태 정의서 작성
  - 고객에게 노출할 상태와 내부 상태를 분리
  - `REQUESTED`, `PENDING`, `BROADCASTED`, `CONFIRMED`, `COMPLETED`, `REJECTED`, `MANUAL_REVIEW` 정의

- [ ] `PRD-002` SLA 정의
  - 출금 접수 후 승인까지 목표 시간
  - 승인 후 broadcast까지 목표 시간
  - broadcast 후 chain inclusion까지 목표 시간
  - 수동 심사 건의 최대 처리 시간

- [ ] `PRD-003` 취소 가능 구간 정의
  - 요청 접수 후 취소 가능한지
  - approval 완료 후 취소 가능한지
  - 서명 이후에는 무조건 불가인지 명시

- [ ] `PRD-004` 고객 통지 정책 정의
  - 출금 요청 수신, 수동 심사 진입, 승인 완료, 브로드캐스트, 완료 시점 알림 정의
  - 실패 사유를 고객에게 어느 수준까지 노출할지 정의

### 14.3 Wallet / Treasury Architecture

- [ ] `WAL-001` 지갑 계층 구조 설계
  - hot, warm, cold wallet 역할 정의
  - 체인별 운영 지갑 수량 정의
  - sweep / refill 전략 정의

- [ ] `WAL-002` treasury 운영 정책 정의
  - hot wallet 최소 잔고
  - refill 임계치
  - cold -> warm -> hot 이동 승인 절차

- [ ] `WAL-003` wallet inventory 테이블 설계
  - wallet_id, chain_type, wallet_tier, address, status, owner_team, created_at
  - 사용 중지, rotation 예정, 폐기 상태 정의

- [ ] `WAL-004` 주소 생성/등록 절차 정의
  - 새 운영 주소 발급 절차
  - 서명 키와 주소 매핑 검증
  - 등록 후 health check 및 소액 송금 검증

### 14.4 Key Management / HSM / Signer

- [ ] `KEY-001` signer 배치 모델 정의
  - self-hosted signer인지
  - HSM/KMS 기반인지
  - MPC를 사용할지 결정

- [ ] `KEY-002` 키 생명주기 정의
  - 생성
  - 활성화
  - 회전
  - 폐기
  - 사고 시 비상 폐기

- [ ] `KEY-003` signer 입력 계약 고정
  - `request_id`
  - `withdrawal_id`
  - `attempt_id`
  - `chain_id`
  - `from_address`
  - `to_address`
  - `asset`
  - `amount`
  - `nonce`
  - `deadline`
  - `policy_decision_id`
  - `approval_bundle_id`
  - `authorization_digest`

- [ ] `KEY-004` signer 재검증 규칙 구현
  - approval payload와 sign payload 일치 검증
  - chain_id mismatch 거부
  - deadline 초과 거부
  - 이미 사용된 request_id 재사용 거부

- [ ] `KEY-005` signer 감사 로그 저장
  - 누가 어떤 request_id를 서명했는지 기록
  - 서명 요청 원문 digest 저장
  - 거부된 요청도 기록

- [ ] `KEY-006` signer 장애 격리
  - signer 장애 시 자동 재시도 정책
  - signer 완전 중단 시 출금 중지 기준
  - 대체 signer 전환 절차

### 14.5 Approval / Policy / Whitelist

- [ ] `POL-001` 정책 룰 카탈로그 작성
  - amount limit
  - destination whitelist
  - velocity limit
  - 신규 주소 제한
  - 체인별 리스크 제한
  - 운영자 수동 심사 강제 규칙

- [ ] `POL-002` policy versioning 도입
  - policy snapshot 저장
  - 언제 어떤 버전이 적용되었는지 기록
  - 과거 거래 재현 가능성 확보

- [ ] `POL-003` approval matrix 작성
  - 금액 구간별 필요한 승인자 수 정의
  - 팀 간 분리 승인 원칙 정의
  - 야간/주말 승인 예외 정책 정의

- [ ] `POL-004` whitelist lifecycle 운영 문서화
  - 등록
  - 승인
  - hold
  - 활성화
  - 회수
  - 재등록

- [ ] `POL-005` 정책 변경 delay 적용
  - policy change는 즉시 적용 금지
  - `PROPOSED -> QUORUM_APPROVED -> DELAYED -> APPLIED`
  - delay 중 취소/rollback 가능

- [ ] `POL-006` break-glass 정책 정의
  - emergency allowlist
  - emergency withdrawal stop
  - emergency signer disable
  - 사용 시 사후 감사 필수화

### 14.6 Core Domain / State Machine

- [ ] `DOM-001` `Withdrawal` 상태기계 확정
  - 상태 전이 허용표 작성
  - 금지 전이 명시
  - 수동 개입 가능한 상태 표시

- [ ] `DOM-002` `TxAttempt` 상태기계 확정
  - `CREATED`
  - `BROADCASTED`
  - `INCLUDED`
  - `FINALIZED`
  - `REPLACED`
  - `DROPPED`
  - `REVERTED`
  - `FAILED`
  - `EXPIRED`

- [ ] `DOM-003` canonical attempt 규칙 명시
  - 같은 withdrawal에 canonical attempt가 하나만 존재하는지 보장
  - replace/retry 시 canonical 교체 규칙 명시

- [ ] `DOM-004` withdrawal 완료 기준 확정
  - `INCLUDED`를 완료로 볼지
  - `SAFE/FINALIZED`만 완료로 볼지
  - 자산/체인/리스크별 차등 적용할지 정의

### 14.7 Nonce / Concurrency / Idempotency

- [ ] `TX-001` `nonce_reservations` 운영형 구현
  - `chain_type + from_address + nonce` 유니크 강제
  - reservation 생성/해제/만료 정책 정의
  - stuck reservation 복구 잡 추가

- [ ] `TX-002` address 단위 single-writer 전략 구현
  - 같은 주소에서 동시에 nonce를 잡지 않도록 보장
  - DB 락 또는 큐 기반 직렬화 선택

- [ ] `TX-003` idempotency 운영 규칙 정의
  - 키 TTL
  - 같은 키에 다른 body가 들어오면 conflict
  - 응답 캐싱 범위 정의

- [ ] `TX-004` retry / replace 기준 정의
  - 어떤 실패는 retry
  - 어떤 실패는 replace
  - 어떤 실패는 수동개입

- [ ] `TX-005` fee bump 정책 정의
  - base fee/priority fee 상한
  - 최대 재시도 횟수
  - 급등 시 중단 기준

### 14.8 Chain Integration / RPC / Finality

- [ ] `CHN-001` 체인별 지원 범위 정의
  - EVM mainnet/L2
  - Bitcoin/UTXO
  - Cosmos
  - Solana
  - 각 체인별 지원 단계 구분

- [ ] `CHN-002` provider 전략 정의
  - primary/secondary/tertiary provider
  - 공급자별 SLA와 비용 기준
  - 장애 시 failover 정책

- [ ] `CHN-003` 멀티 RPC quorum 구현
  - head number quorum
  - receipt quorum
  - inconsistent provider 격리

- [ ] `CHN-004` finality policy 구현
  - 체인별 최소 confirmation 수
  - SAFE/FINALIZED 판정 기준
  - 리오그 시 역전 규칙

- [ ] `CHN-005` mempool 관측 전략 정의
  - tx seen 여부를 얼마나 신뢰할지
  - provider 간 mempool 차이를 어떻게 처리할지

- [ ] `CHN-006` chain exception taxonomy 확정
  - `FAILED_SYSTEM`
  - `FAILED_POLICY`
  - `FAILED_CHAIN_REJECTED`
  - `REPLACED`
  - `DROPPED`
  - `REVERTED`
  - `RPC_INCONSISTENT`
  - `EXPIRED`

### 14.9 Ledger / Accounting / Reconciliation

- [ ] `LED-001` ledger 이벤트 사전 정의
  - `RESERVE`
  - `RELEASE`
  - `SETTLE`
  - `REVERSAL`
  - `FEE_ACCRUAL`

- [ ] `LED-002` append-only 원장 원칙 강제
  - update/delete 금지
  - reversal로만 정정
  - 원장 변경 감사 로그 분리

- [ ] `LED-003` 출금 회계 처리 규칙 정의
  - reserve 시점
  - settle 시점
  - 실패/만료/replaced 시 release 여부
  - 수수료 회계 처리

- [ ] `LED-004` reconciliation 배치 설계
  - reserve without settle 탐지
  - settle without chain finality 탐지
  - chain success but no ledger 탐지
  - ledger settled but tx missing 탐지

- [ ] `LED-005` finance operator 큐 설계
  - 대사 불일치 건 수동 처리 큐
  - 처리자, 처리시각, 처리사유, 재발방지 메모 저장

### 14.10 Security / Infra / Platform

- [ ] `SEC-001` 비밀정보 관리 체계 구축
  - private key 직접 환경변수 주입 금지
  - secret manager/KMS 사용
  - 운영/개발 비밀 분리

- [ ] `SEC-002` 네트워크 분리
  - signer 네트워크 분리
  - admin API 접근 제어
  - bastion 또는 zero trust 경유

- [ ] `SEC-003` 관리자 인증 강화
  - SSO
  - MFA
  - IP 제한
  - 세션 만료 정책

- [ ] `SEC-004` DB 보안
  - at-rest encryption
  - backup encryption
  - PITR
  - schema migration 승인 절차

- [ ] `SEC-005` 공급망 보안
  - dependency scan
  - container image scan
  - build provenance
  - 배포 artifact 서명

### 14.11 SRE / Monitoring / Alerting

- [ ] `SRE-001` 표준 로그 필드 확정
  - `correlation_id`
  - `withdrawal_id`
  - `attempt_id`
  - `idempotency_key`
  - `nonce_key`
  - `tx_hash`
  - `policy_decision_id`
  - `approval_task_id`

- [ ] `SRE-002` 핵심 메트릭 확정
  - approval latency
  - broadcast latency
  - inclusion latency
  - finalization latency
  - reserve without settle count
  - dropped/replaced/reverted count
  - provider mismatch rate

- [ ] `SRE-003` 알람 기준 정의
  - 고액 출금 대기 시간 초과
  - provider quorum 실패
  - signer unavailable
  - reserve without settle 증가
  - reconciliation mismatch 발생

- [ ] `SRE-004` 대시보드 구성
  - 출금 funnel
  - attempt 상태 분포
  - provider health
  - whitelist pending queue
  - approval backlog

- [ ] `SRE-005` capacity plan 작성
  - QPS
  - peak hour
  - provider 호출량
  - DB write/read 부하

### 14.12 Operations / Runbook / Incident Response

- [ ] `RUN-001` 예외 6종 런북 작성
  - `FAILED`
  - `EXPIRED`
  - `DROPPED`
  - `REPLACED`
  - `REVERTED`
  - `RPC_INCONSISTENT`

- [ ] `RUN-002` incident severity 체계 정의
  - Sev1: 잘못된 출금 또는 잠재적 자금 손실
  - Sev2: 출금 중단 또는 대규모 지연
  - Sev3: 부분 기능 장애

- [ ] `RUN-003` 출금 중지 스위치 구현
  - 전체 중지
  - 체인별 중지
  - 자산별 중지
  - 고위험 출금만 중지

- [ ] `RUN-004` signer compromise 대응 절차 작성
  - 즉시 출금 중지
  - 신규 signer 로테이션
  - whitelist/policy 재검토
  - 고객 공지 초안

- [ ] `RUN-005` chain reorg 대응 절차 작성
  - included -> dropped/reverted 역전 시 처리 규칙
  - ledger reversal 규칙
  - 고객 상태 수정 규칙

- [ ] `RUN-006` provider outage 대응 절차 작성
  - provider 강등
  - write 중지 여부 판단
  - 수동 승인 모드 전환

### 14.13 Audit / Compliance / Legal

- [ ] `AUD-001` 감사 추적성 정의
  - "누가 요청했고, 누가 승인했고, 누가 서명했고, 어떤 정책이 적용되었는가"를 한 건 기준으로 재구성 가능해야 함

- [ ] `AUD-002` immutable audit trail 저장 전략 수립
  - DB 감사 로그
  - object storage 보관
  - tamper-evidence 전략

- [ ] `AUD-003` 정기 접근권한 리뷰
  - 관리자 계정 분기별 리뷰
  - 퇴사자/직무변경자 회수 절차

- [ ] `AUD-004` 법무/컴플라이언스 요구사항 매핑
  - 국가별 보관기간
  - 출금 거절 사유 기록 요건
  - 고객 자금 분리 원칙

### 14.14 QA / Simulation / Drill

- [ ] `QA-001` 시뮬레이션 시나리오 확장
  - 정상 출금
  - 승인 지연
  - signer timeout
  - provider mismatch
  - reorg
  - dropped tx
  - replace storm

- [ ] `QA-002` 운영 리허설 수행
  - 주간 출금 중지 훈련
  - signer failover 훈련
  - reconciliation mismatch 훈련

- [ ] `QA-003` chaos test 도입
  - provider 1개 장애
  - provider 2개 불일치
  - DB failover
  - signer latency 급증

- [ ] `QA-004` 출시 게이트 정의
  - P0 보안 이슈 0건
  - Sev1/Sev2 런북 완성
  - 주요 체인에 대한 dry-run 완료
  - 감사 로그 재구성 테스트 통과

### 14.15 추천 실행 순서

실제 운영 전환은 아래 순서가 가장 현실적이다.

1. `Governance + 권한모델 + 정책 기준` 먼저 확정
2. `Nonce / Approval / Signer / Ledger` 같은 자금통제 핵심 구현
3. `멀티 RPC / finality / reconciliation` 같은 체인 불확실성 대응 구현
4. `런북 / 알람 / incident drill` 준비
5. `제한된 자산/체인/고객군`으로 pilot 운영
6. pilot 결과를 반영해 production 확대

### 14.16 운영 시작 전 최종 Go-Live Gate

- [ ] 승인 없이 서명되는 경로가 0개인가
- [ ] `(chain, from, nonce)` 중복이 구조적으로 불가능한가
- [ ] reserve without settle를 탐지하고 처리할 수 있는가
- [ ] provider 불일치 시 write 중지 또는 강등 운용이 가능한가
- [ ] signer 장애 시 수동/대체 절차가 문서화되어 있는가
- [ ] 잘못된 출금이 발생했을 때 30분 내 상황 파악이 가능한가
- [ ] 감사인이 특정 출금 1건의 전체 이력을 재구성할 수 있는가
- [ ] hot wallet 잔고/리필/중지 정책이 정의되어 있는가
- [ ] 운영자 교체/퇴사 시 권한 회수 절차가 자동화되어 있는가
- [ ] 출시 후 첫 2주 동안 daily review 항목이 정의되어 있는가

## 15. 운영형 실행 우선순위 재정렬 + 팀 소유자

아래 표는 14장의 상세 TODO를 실제 실행 순서 기준으로 다시 묶은 것이다.

소유팀 정의:

- `ENG`: 애플리케이션/데이터/플랫폼 엔지니어링
- `SEC`: 보안/키관리/접근통제
- `OPS`: 운영/SRE/온콜/런북
- `FIN`: 재무/원장/대사/정산 운영

### 15.1 P0: 운영 시작 전 반드시 막아야 하는 항목

P0는 "잘못된 출금 방지", "중복 출금 방지", "승인 없는 서명 방지", "사고 시 즉시 중지 가능"에 직결되는 항목이다.

| Priority | ID | 항목 | 주 소유팀 | 보조팀 | 완료 기준 |
|---|---|---|---|---|---|
| P0 | `OPS-001` | 서비스 범위/자산/체인/hot-warm-cold 범위 확정 | OPS | ENG, FIN | 지원 자산/체인과 운영 범위가 문서로 고정됨 |
| P0 | `OPS-002` | 리스크 등급 체계 정의 | OPS | FIN, SEC | 소액/중액/고액 및 high-risk 기준이 승인됨 |
| P0 | `OPS-003` | 권한 모델 정의 | SEC | OPS, ENG | requester/approver/signer/auditor 역할 분리 완료 |
| P0 | `OPS-004` | 정책/화이트리스트/서명기 변경관리 절차 정의 | OPS | SEC, ENG | normal/emergency change 절차와 rollback 기준 확정 |
| P0 | `WAL-001` | 지갑 계층 구조 설계 | OPS | FIN, SEC | hot/warm/cold 지갑 역할과 이동 경로가 정의됨 |
| P0 | `WAL-002` | hot wallet 최소 잔고/리필 정책 정의 | FIN | OPS, ENG | refill threshold와 승인 절차 문서화 완료 |
| P0 | `KEY-001` | signer/HSM/MPC 운영 모델 확정 | SEC | ENG, OPS | 운영 signer 구조가 확정되고 승인됨 |
| P0 | `KEY-002` | 키 생명주기 정의 | SEC | OPS | 생성/활성/회전/폐기/비상폐기 절차 확정 |
| P0 | `KEY-003` | signer 입력 계약 고정 | ENG | SEC | signer 요청 스키마가 문서와 코드로 고정됨 |
| P0 | `KEY-004` | signer 재검증 규칙 구현 | ENG | SEC | approval/policy mismatch, deadline 초과, replay 거부 |
| P0 | `POL-001` | 정책 룰 카탈로그 작성 | OPS | FIN, SEC, ENG | 금액/화이트리스트/velocity/manual review 기준 확정 |
| P0 | `POL-003` | approval matrix 작성 | OPS | FIN, SEC | 금액 구간별 승인자 수와 분리승인 규칙 확정 |
| P0 | `POL-004` | whitelist lifecycle 운영 정의 | OPS | SEC | 등록/승인/보류/활성/회수 절차 확정 |
| P0 | `POL-006` | break-glass 정책 정의 | SEC | OPS | emergency allow/stop/signer disable 절차 승인 |
| P0 | `DOM-001` | Withdrawal 상태기계 확정 | ENG | OPS | 허용/금지 전이표와 수동 개입 구간 문서화 |
| P0 | `DOM-002` | TxAttempt 상태기계 확정 | ENG | OPS | replace/dropped/reverted/failed 상태 정의 확정 |
| P0 | `DOM-003` | canonical attempt 규칙 확정 | ENG | OPS | canonical attempt 1개 규칙이 코드/DB로 보장됨 |
| P0 | `TX-001` | `nonce_reservations` 운영형 구현 | ENG | OPS | `(chain, from, nonce)` 유니크와 만료/복구 규칙 구현 |
| P0 | `TX-002` | address 단위 single-writer 구현 | ENG | OPS | 동일 주소 동시 nonce 경쟁이 구조적으로 차단됨 |
| P0 | `TX-003` | idempotency 운영 규칙 확정 | ENG | OPS | 동일 키 재요청/충돌/TTL 정책이 구현됨 |
| P0 | `TX-004` | retry/replace/수동개입 기준 정의 | OPS | ENG | 실패 유형별 자동/수동 처리 기준표 확정 |
| P0 | `LED-001` | ledger 이벤트 사전 정의 | FIN | ENG, OPS | RESERVE/RELEASE/SETTLE/REVERSAL/FEE_ACCRUAL 정의 확정 |
| P0 | `LED-002` | append-only 원장 원칙 강제 | ENG | FIN | 원장 update/delete 금지와 reversal-only 정정 구현 |
| P0 | `LED-003` | 출금 회계 처리 규칙 정의 | FIN | OPS, ENG | reserve/settle/release 시점과 수수료 규칙 확정 |
| P0 | `SEC-001` | 비밀정보 관리 체계 구축 | SEC | ENG | private key 환경변수 직접 주입 금지, secret manager 적용 |
| P0 | `SEC-003` | 관리자 인증 강화 | SEC | ENG, OPS | SSO/MFA/IP 제한이 운영 경로에 적용됨 |
| P0 | `SRE-001` | 표준 로그 필드 확정 | OPS | ENG | correlation/withdrawal/attempt/nonce/tx 추적 가능 |
| P0 | `SRE-003` | 치명 알람 기준 정의 | OPS | ENG, FIN | signer down, provider quorum fail, reserve-settle mismatch 알람 설정 |
| P0 | `RUN-001` | 예외 6종 런북 작성 | OPS | ENG, FIN | FAILED/EXPIRED/DROPPED/REPLACED/REVERTED/RPC_INCONSISTENT 런북 완료 |
| P0 | `RUN-003` | 출금 중지 스위치 구현 | ENG | OPS, SEC | 전체/체인별/자산별/고위험 중지 가능 |
| P0 | `RUN-004` | signer compromise 대응 절차 작성 | SEC | OPS, ENG | 키 유출 가정 사고 절차와 공지 초안 완성 |
| P0 | `AUD-001` | 감사 추적성 정의 | OPS | SEC, FIN, ENG | 1건 출금을 end-to-end 재구성 가능한 항목 확정 |
| P0 | `QA-004` | 출시 게이트 정의 | OPS | ENG, SEC, FIN | go-live 승인 조건이 체크리스트로 고정됨 |

### 15.2 P1: 운영 안정화와 대규모 사고 방지

P1은 "운영은 되지만 아직 불안한 상태"를 넘어서, provider 불일치/최종성/대사 불일치에 버티게 만드는 항목이다.

| Priority | ID | 항목 | 주 소유팀 | 보조팀 | 완료 기준 |
|---|---|---|---|---|---|
| P1 | `PRD-001` | 고객 노출 상태 vs 내부 상태 분리 | OPS | ENG | 외부 상태 모델과 내부 상태 모델이 분리됨 |
| P1 | `PRD-002` | SLA 정의 | OPS | FIN | approval/broadcast/finalization SLA 합의 |
| P1 | `PRD-004` | 고객 통지 정책 정의 | OPS | FIN, ENG | 상태 변화와 실패 시 고객 통지 규칙 확정 |
| P1 | `WAL-003` | wallet inventory 테이블 설계 | ENG | OPS, FIN | 운영 주소 inventory 관리 가능 |
| P1 | `WAL-004` | 주소 생성/등록 절차 정의 | OPS | SEC | 신규 주소 발급/검증 절차 확정 |
| P1 | `KEY-005` | signer 감사 로그 저장 | ENG | SEC | signer request/reject/sign 기록 저장 |
| P1 | `KEY-006` | signer 장애 격리/대체 signer 전환 | SEC | OPS, ENG | signer failover 절차 구현 |
| P1 | `POL-002` | policy versioning 도입 | ENG | OPS | policy snapshot과 버전 이력 저장 |
| P1 | `POL-005` | 정책 변경 delay 적용 | ENG | OPS, SEC | `PROPOSED -> QUORUM_APPROVED -> DELAYED -> APPLIED` 구현 |
| P1 | `DOM-004` | withdrawal 완료 기준 확정 | OPS | ENG, FIN | chain/asset/risk tier별 completion 기준 합의 |
| P1 | `TX-005` | fee bump 정책 정의 | OPS | FIN, ENG | 최대 bump, 재시도 횟수, 중단 기준 정의 |
| P1 | `CHN-002` | provider 전략 정의 | OPS | ENG | primary/secondary/tertiary provider 정책 수립 |
| P1 | `CHN-003` | 멀티 RPC quorum 구현 | ENG | OPS | head/receipt quorum 및 inconsistent provider 격리 구현 |
| P1 | `CHN-004` | finality policy 구현 | ENG | OPS, FIN | 체인/리스크별 SAFE/FINALIZED 기준 구현 |
| P1 | `CHN-005` | mempool 관측 전략 정의 | OPS | ENG | mempool 신뢰도와 처리 정책 정의 |
| P1 | `CHN-006` | chain exception taxonomy 확정 | OPS | ENG | chain 예외 유형과 후속 액션 기준 확정 |
| P1 | `LED-004` | reconciliation 배치 설계 | FIN | ENG, OPS | reserve-settle-chain mismatch 탐지 배치 구현 |
| P1 | `LED-005` | finance operator 큐 설계 | FIN | OPS, ENG | 대사 불일치 수동 처리 큐 구축 |
| P1 | `SEC-002` | 네트워크 분리 | SEC | ENG | signer/admin 경로 분리 및 bastion/zero trust 적용 |
| P1 | `SEC-004` | DB 보안 | SEC | ENG | backup encryption, PITR, migration 승인 절차 적용 |
| P1 | `SRE-002` | 핵심 메트릭 확정 | OPS | ENG, FIN | approval/broadcast/inclusion/finalization/대사 메트릭 확정 |
| P1 | `SRE-004` | 운영 대시보드 구성 | OPS | ENG, FIN | provider health, approval backlog, reserve-settle 대시보드 운영 |
| P1 | `RUN-002` | incident severity 체계 정의 | OPS | SEC, ENG | Sev1/2/3 정의와 호출 체계 확정 |
| P1 | `RUN-005` | chain reorg 대응 절차 | OPS | ENG, FIN | reorg 시 고객 상태/원장 reversal 규칙 문서화 |
| P1 | `RUN-006` | provider outage 대응 절차 | OPS | ENG | provider 강등 및 write stop 기준 확정 |
| P1 | `AUD-002` | immutable audit trail 저장 전략 | SEC | ENG, OPS | tamper-evident audit 저장 전략 수립 |
| P1 | `AUD-004` | 법무/컴플라이언스 요구사항 매핑 | OPS | FIN, SEC | 보관기간/거절사유/자금분리 요구사항 반영 |
| P1 | `QA-001` | 시뮬레이션 시나리오 확장 | ENG | OPS | provider mismatch/reorg/dropped/replace storm 시뮬레이션 가능 |
| P1 | `QA-002` | 운영 리허설 수행 | OPS | ENG, SEC, FIN | signer failover/withdrawal stop/reconciliation mismatch 훈련 완료 |

### 15.3 P2: 확장성, 효율화, 감사 고도화

P2는 운영이 가능한 상태를 넘어서, 규모 확장과 장기 운영 효율을 위한 항목이다.

| Priority | ID | 항목 | 주 소유팀 | 보조팀 | 완료 기준 |
|---|---|---|---|---|---|
| P2 | `PRD-003` | 취소 가능 구간 정의 | OPS | ENG | 취소 허용 구간과 UI/API 반영 |
| P2 | `CHN-001` | 체인별 지원 범위 확장 | OPS | ENG | EVM 외 체인 지원 단계 로드맵 확정 |
| P2 | `SEC-005` | 공급망 보안 | SEC | ENG | dependency/image/build provenance 통제 적용 |
| P2 | `SRE-005` | capacity plan 작성 | OPS | ENG, FIN | peak traffic/DB/provider 호출량 계획 수립 |
| P2 | `AUD-003` | 정기 접근권한 리뷰 | SEC | OPS | 분기별 access review 자동화 |
| P2 | `QA-003` | chaos test 도입 | ENG | OPS | provider/DB/signer 장애 chaos test 운영 |
| P2 | `OPS-005` | 로그/승인/감사 보관 정책 확정 | OPS | SEC, FIN | 보관기간과 파기정책 확정 |

### 15.4 팀별 1차 책임 범위

#### Engineering

- `KEY-003`, `KEY-004`
- `DOM-001`, `DOM-002`, `DOM-003`
- `TX-001`, `TX-002`, `TX-003`
- `LED-002`
- `RUN-003`
- `CHN-003`, `CHN-004`
- `POL-005`

#### Security

- `OPS-003`
- `KEY-001`, `KEY-002`
- `POL-006`
- `SEC-001`, `SEC-002`, `SEC-003`, `SEC-004`, `SEC-005`
- `RUN-004`
- `AUD-002`, `AUD-003`

#### Operations

- `OPS-001`, `OPS-002`, `OPS-004`
- `POL-001`, `POL-003`, `POL-004`
- `TX-004`, `TX-005`
- `CHN-002`, `CHN-005`, `CHN-006`
- `SRE-001`, `SRE-002`, `SRE-003`, `SRE-004`, `SRE-005`
- `RUN-001`, `RUN-002`, `RUN-005`, `RUN-006`
- `QA-002`, `QA-004`

#### Finance

- `WAL-002`
- `LED-001`, `LED-003`, `LED-004`, `LED-005`
- `PRD-002`
- `AUD-004`

### 15.5 추천 첫 6주 실행안

#### Week 1-2

- `OPS-001`, `OPS-002`, `OPS-003`, `POL-001`, `POL-003`
- `KEY-001`, `KEY-003`
- `DOM-001`, `DOM-002`
- `TX-001`, `TX-002`, `TX-003`

#### Week 3-4

- `KEY-004`, `POL-004`, `POL-006`
- `LED-001`, `LED-002`, `LED-003`
- `SEC-001`, `SEC-003`
- `SRE-001`, `SRE-003`
- `RUN-001`, `RUN-003`, `RUN-004`

#### Week 5-6

- `CHN-002`, `CHN-003`, `CHN-004`
- `LED-004`, `LED-005`
- `SRE-002`, `SRE-004`
- `RUN-005`, `RUN-006`
- `QA-001`, `QA-002`

### 15.6 P0 승인 기준

P0가 끝났다고 말하려면 최소 아래가 충족돼야 한다.

- 승인 없이 서명되는 경로가 없어야 한다.
- 동일 `(chain, from, nonce)` 중복 출금이 구조적으로 불가능해야 한다.
- 고액 출금은 무조건 human-in-the-loop를 거쳐야 한다.
- ledger는 append-only로 기록돼야 한다.
- signer compromise와 provider outage에 대한 중지 절차가 문서화돼 있어야 한다.
- 운영자가 로그와 DB만으로 1건 출금의 전체 이력을 추적할 수 있어야 한다.
