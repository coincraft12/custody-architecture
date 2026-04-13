# HSM 연동 계획 (15-2-2)

> 작성일: 2026-04-13
> 상태: 설계 문서 — 실제 HSM 하드웨어 없이 연동 아키텍처 및 Signer 인터페이스 확장 계획 수립

---

## 1. 현재 Signer 아키텍처

현재 `EvmSigner`는 환경변수(`CUSTODY_EVM_PRIVATE_KEY`)에서 개인키를 로드하여
JVM 메모리에서 서명을 수행한다. Phase 1에서 다음 보완을 완료했다:

- char[] zeroing (best-effort, JVM GC 한계 있음)
- `Signer.java`에 Phase 3 KMS/Vault 전환 계획 주석 추가 (2-1-3)
- PDS 훅 메서드 시그니처 예약 (`getRecoveryKeyPdsId()`, 16-1-3)

**한계**: 개인키가 JVM 메모리에 상주하므로 메모리 덤프 공격, 코어 덤프 등으로 노출 위험.
운영 단계에서 HSM(Hardware Security Module) 또는 KMS로의 전환이 필요하다.

---

## 2. AWS CloudHSM vs Azure Dedicated HSM 비교

| 항목 | AWS CloudHSM | Azure Dedicated HSM |
|------|-------------|---------------------|
| **표준** | FIPS 140-2 Level 3 | FIPS 140-2 Level 3 |
| **제조사** | Cavium/Marvell LiquidSecurity | Thales Luna Network HSM 7 |
| **관리 방식** | AWS 관리형 클러스터, 고객이 키 소유 | Azure가 하드웨어 제공, 고객이 완전 제어 |
| **SLA** | 99.99% (multi-AZ 클러스터) | 99.9% (단일 인스턴스 기준) |
| **SDK** | AWS CloudHSM JCE Provider | PKCS#11, JCE (Thales SDK) |
| **Spring 통합** | `software.amazon.cloudhsm:cloudhsm-jce` | `com.safenet:pkcs11-provider` (Thales) |
| **비용 (추정)** | $1.60/hr per HSM + 클러스터 최소 2대 | $2.00/hr (단일 HSM) |
| **최소 구성** | 2 HSM (HA 클러스터) ≈ $2,304/월 | 1 HSM ≈ $1,440/월 |
| **키 백업** | 클러스터 내 자동 복제 | 고객이 수동 백업 책임 |
| **장점** | AWS 생태계 통합, 관리형 HA | 완전한 키 소유권, Thales 생태계 |
| **단점** | AWS 의존성, 비용 높음 | 수동 운영 부담, Azure 전용 |

### 2-1. 권장 선택

**현재 Phase**: AWS KMS(소프트웨어 기반) 우선 적용, 이후 CloudHSM으로 업그레이드.

| 단계 | 솔루션 | 이유 |
|------|--------|------|
| Phase 2 (파일럿) | AWS KMS (키 관리 서비스, HSM 기반 옵션) | 비용 대비 보안 충분, 빠른 통합 가능 |
| Phase 3 (기관 고객) | AWS CloudHSM 또는 Azure Dedicated HSM | FIPS 140-2 Level 3 감사 요구 시 |

---

## 3. 연동 아키텍처

```
┌─────────────────────────────────────────────────────────────┐
│                    custody-app (JVM)                         │
│                                                              │
│  WithdrawalService                                           │
│       │ sign(txData)                                         │
│       ▼                                                      │
│  ┌─────────────────┐   <<interface>>                        │
│  │  SignerConnector │◄─────── getPublicAddress()            │
│  │                 │         sign(txData): SignedTx          │
│  └────────┬────────┘         getRecoveryKeyPdsId()          │
│           │                                                  │
│    ┌──────┴──────────────────┐                              │
│    │                         │                              │
│    ▼                         ▼                              │
│  LocalSigner              KmsSignerConnector                │
│  (현재, Phase 1)          (Phase 2 예정)                   │
│  - ENV 개인키              - AWS KMS API 호출               │
│  - JVM 메모리 서명          - 서명 요청을 KMS로 위임         │
│    (char[] zeroing)         - 개인키 JVM 외부 보관           │
│                              │                              │
│                              └─────► AWS KMS / CloudHSM    │
└─────────────────────────────────────────────────────────────┘
```

### 3-1. 현재 Signer 인터페이스 (예약 상태)

```java
// lab.custody.domain.signer.Signer (또는 SignerConnector)
public interface SignerConnector {
    String getPublicAddress();
    SignedTransaction sign(RawTransaction tx, long chainId);

    // 16-1-3: PDS 훅 — Phase 2에서 구현 예정
    default Optional<String> getRecoveryKeyPdsId() { return Optional.empty(); }
}
```

### 3-2. Phase 2: KmsSignerConnector 구현 계획

```java
// 계획: lab.custody.adapter.KmsSignerConnector
@Component
@ConditionalOnProperty("custody.signer.type", havingValue = "kms")
public class KmsSignerConnector implements SignerConnector {

    // AWS KMS 키 ARN (환경변수: CUSTODY_KMS_KEY_ARN)
    private final String keyArn;
    private final KmsClient kmsClient;  // software.amazon.awssdk:kms

    @Override
    public SignedTransaction sign(RawTransaction tx, long chainId) {
        // 1. RLP 인코딩 → 서명 대상 해시 계산
        byte[] txHash = Hash.sha3(encode(tx, chainId));

        // 2. KMS API: sign(keyArn, ECDSA_SHA_256, txHash)
        SignRequest req = SignRequest.builder()
                .keyId(keyArn)
                .signingAlgorithm(SigningAlgorithmSpec.ECDSA_SHA_256)
                .message(SdkBytes.fromByteArray(txHash))
                .messageType(MessageType.DIGEST)
                .build();
        SignResponse resp = kmsClient.sign(req);

        // 3. DER 서명 → EIP-155 r, s, v 변환
        byte[] derSig = resp.signature().asByteArray();
        return derToEip155(derSig, tx, chainId);
    }
}
```

### 3-3. Phase 3: CloudHSM/Dedicated HSM 연동

```java
// 계획: lab.custody.adapter.CloudHsmSignerConnector
@Component
@ConditionalOnProperty("custody.signer.type", havingValue = "cloudhsm")
public class CloudHsmSignerConnector implements SignerConnector {

    // AWS CloudHSM JCE Provider 사용
    // Provider: com.cavium.provider.CaviumProvider
    // 키 참조: CKA_LABEL 또는 CKA_ID로 HSM 내 키 참조 (개인키 JVM 외부 보관)
    private final Provider caviumProvider;
    private final String keyLabel;  // 환경변수: CUSTODY_HSM_KEY_LABEL

    @Override
    public SignedTransaction sign(RawTransaction tx, long chainId) {
        KeyStore ks = KeyStore.getInstance("Cavium", caviumProvider);
        ks.load(null, null);
        PrivateKey privateKey = (PrivateKey) ks.getKey(keyLabel, null);

        Signature sig = Signature.getInstance("SHA256withECDSA", caviumProvider);
        sig.initSign(privateKey);
        sig.update(txHash(tx, chainId));
        return parseSignature(sig.sign(), tx, chainId);
    }
}
```

---

## 4. 설정 구조 계획

```yaml
# application.yaml (Phase 2 예정)
custody:
  signer:
    # local(기본) | kms | cloudhsm | vault
    type: ${CUSTODY_SIGNER_TYPE:local}
    kms:
      key-arn: ${CUSTODY_KMS_KEY_ARN:}
      region: ${CUSTODY_KMS_REGION:ap-northeast-2}
    cloudhsm:
      key-label: ${CUSTODY_HSM_KEY_LABEL:custody-signing-key}
      partition-serial: ${CUSTODY_HSM_PARTITION_SERIAL:}
    vault:
      address: ${CUSTODY_VAULT_ADDR:http://vault:8200}
      token: ${CUSTODY_VAULT_TOKEN:}
      transit-key: ${CUSTODY_VAULT_TRANSIT_KEY:custody-evm-key}
```

---

## 5. 마이그레이션 경로 (로컬 키 → KMS → CloudHSM)

```
Phase 1 (현재)         Phase 2 (파일럿)       Phase 3 (기관)
─────────────────────  ─────────────────────  ─────────────────────
ENV 개인키              AWS KMS               AWS CloudHSM
JVM 메모리 서명          API 서명 위임          PKCS#11 JCE 서명
char[] zeroing          개인키 JVM 외부        개인키 HSM 내부
(best-effort)           FIPS 140-2 Level 2     FIPS 140-2 Level 3
```

**마이그레이션 시 무중단 전환 방법**:
1. 새 KMS 키 생성 → 공개 주소 도출
2. custody wallet 잔액을 신규 주소로 이전
3. `CUSTODY_SIGNER_TYPE=kms` 환경변수 교체 후 재배포
4. 구 ENV 개인키 삭제

---

## 6. 보안 감사 체크포인트 (Phase 3 도입 시)

- [ ] HSM 클러스터 HA 구성 검증 (AZ 분산)
- [ ] 키 백업/복구 절차 문서화 및 훈련
- [ ] CloudTrail/CloudWatch로 KMS API 호출 감사 로그 활성화
- [ ] 키 로테이션 정책 수립 (연 1회 이상 권장)
- [ ] 비상 키 복구 절차 수립 (PDS-core와 연계, 섹션 16)

---

## 7. 관련 파일

- `lab/custody/domain/signer/Signer.java` — 현재 인터페이스 + Phase 3 계획 주석
- `lab/custody/adapter/EvmSigner.java` — 현재 LocalSigner 구현
- `docs/architecture/private-mempool-decision.md` — MEV 방어 결정
- `TODO.md` 15-2-1 (완료), 15-2-2 (본 문서)
