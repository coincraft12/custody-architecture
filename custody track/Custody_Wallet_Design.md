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

## 9. 단계별 구축 로드맵
1. Phase 1: 7모듈 분리, 상태머신/원장/감사 로그 확립
2. Phase 2: 화이트리스트+보류, 쿼럼 승인, nonce manager, retry 엔진
3. Phase 3: 멀티 RPC 쿼럼, 멀티체인 어댑터, 정책 변경 지연/롤백, 고급 모니터링

## 10. 구현 체크리스트
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
