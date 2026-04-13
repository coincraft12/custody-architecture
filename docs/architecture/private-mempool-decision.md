# Private Mempool 사용 여부 결정 (15-1-2)

> 작성일: 2026-04-13
> 결정 상태: **현재 Phase에서 미도입. Phase 3(운영 확장 단계)에서 재검토.**

---

## 1. 배경

표준 Ethereum mempool(공개 mempool)에 트랜잭션을 제출하면 다음 위험이 존재한다.

| 위협 | 설명 |
|------|------|
| Front-running | 멤풀 관찰자가 높은 gas로 동일 트랜잭션을 선행 제출 |
| Sandwich attack | DEX 거래 전후로 공격자 TX를 끼워 가격 영향 유도 |
| MEV (Maximal Extractable Value) | 블록 빌더·검증자가 TX 순서를 조작하여 차익 추구 |

Custody 출금은 주로 ETH/ERC-20 단순 전송이며 DEX 상호작용이 없으므로 위 위협 수준이
일반 DeFi 거래 대비 낮다. 그러나 대형 출금 금액이 노출될 경우 front-running 가능성은 존재한다.

---

## 2. Private Mempool 옵션 비교

### 2-1. Flashbots Protect RPC

| 항목 | 내용 |
|------|------|
| 엔드포인트 | `https://rpc.flashbots.net` (mainnet) / `https://rpc-sepolia.flashbots.net` (Sepolia) |
| 동작 방식 | TX를 공개 mempool 대신 Flashbots 릴레이 네트워크를 통해 블록 빌더에 직접 전달 |
| 장점 | MEV 노출 최소화, Revert 시 가스비 미청구, sandwich 공격 방어 |
| 단점 | 단일 릴레이 의존성, 오픈 소스이나 Flashbots 서비스 가용성에 종속 |
| 통합 난이도 | **낮음** — `CUSTODY_EVM_RPC_URL` 환경변수를 Flashbots 엔드포인트로 교체하면 코드 변경 없이 적용 가능 (15-1-1 완료) |

### 2-2. MEV Blocker (CoW Protocol)

| 항목 | 내용 |
|------|------|
| 엔드포인트 | `https://rpc.mevblocker.io` |
| 동작 방식 | 공개 mempool 전송 전 백런닝 차단 경매 수행 |
| 장점 | 추가 수익 환원, Flashbots 대안 |
| 단점 | DEX 중심 설계, 단순 ETH 전송에는 과도한 복잡성 |

### 2-3. 자체 Private Relay 구축

| 항목 | 내용 |
|------|------|
| 방식 | mev-boost, builder API를 통해 자체 블록 빌더와 직접 연결 |
| 장점 | 완전 자체 제어, 제3자 의존성 없음 |
| 단점 | 높은 구축 비용, 인프라 운영 부담, Phase 4+ 수준의 엔지니어링 필요 |

---

## 3. 현재 Phase 결정: **미도입**

### 3-1. 근거

1. **Custody 출금 특성**: ETH/ERC-20 단순 전송 위주. DEX 상호작용이 없으므로 sandwich/front-running 위협이 DeFi 대비 낮다.
2. **화이트리스트 기반 출금**: 사전 승인된 주소로만 전송 가능하므로 무작위 MEV 타겟이 될 가능성이 낮다.
3. **현재 거래 규모**: MVP/파일럿 단계에서 거래 금액 및 빈도가 낮아 MEV 타겟으로서의 경제적 유인이 제한적이다.
4. **운영 복잡성 증가**: Private RPC 도입 시 가용성 모니터링, 폴백 전략 등 추가 운영 부담이 발생한다.
5. **코드 변경 없이 전환 가능**: 이미 `CUSTODY_EVM_RPC_URL` 환경변수 기반으로 설계되어 있어 필요 시 즉시 전환 가능 (15-1-1).

### 3-2. 현재 적용 상태

```yaml
# application.yaml (15-1-1 기준)
custody:
  evm:
    rpc-url: ${CUSTODY_EVM_RPC_URL:https://ethereum-sepolia-rpc.publicnode.com}
    # Private mempool 전환 시:
    # CUSTODY_EVM_RPC_URL=https://rpc.flashbots.net  (mainnet)
    # CUSTODY_EVM_RPC_URL=https://rpc-sepolia.flashbots.net  (Sepolia)
```

코드 변경 없이 환경변수 교체만으로 Flashbots Protect RPC로 전환 가능하다.

---

## 4. Phase 3 재검토 기준

다음 조건 중 하나라도 충족되면 Phase 3에서 Private mempool 도입을 재검토한다.

| 조건 | 기준값 |
|------|--------|
| 단일 출금 금액 증가 | 평균 출금 금액 > 10 ETH |
| MEV 피해 사례 발생 | 실제 front-running/sandwich 공격 탐지 1건 이상 |
| 기관 고객 요구 | 기관 고객이 private mempool 보장을 계약 조건으로 요구 |
| 거래 빈도 증가 | 일 출금 건수 > 1,000건 |

---

## 5. Phase 3 도입 시 구현 계획

1. `EvmRpcConfig`에 `privateRpcUrl` 설정 추가 (별도 OkHttpClient 인스턴스)
2. `EvmRpcAdapter.broadcast()`: `privateRpcUrl` 설정 시 private 엔드포인트로 TX 전송
3. `EvmRpcProviderPool`에 private relay URL을 broadcast 전용 프라이머리로 등록
4. 모니터링: Prometheus 알림 — TX mempool 대기 시간 이상 증가 감지

---

## 6. 참고 링크

- Flashbots Protect: https://docs.flashbots.net/flashbots-protect/overview
- MEV Blocker: https://mevblocker.io
- 관련 코드: `application.yaml` custody.evm.rpc-url (15-1-1 주석 참조)
- 관련 TODO: `TODO.md` 15-1-1 (완료), 15-1-2 (본 문서)
