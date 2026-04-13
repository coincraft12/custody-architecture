package lab.custody.adapter;

import lab.custody.domain.withdrawal.ChainType;
import org.web3j.crypto.RawTransaction;

import java.util.Optional;

/**
 * 트랜잭션 서명 추상화.
 *
 * <p>현재 구현체: {@link EvmSigner} (JVM 인메모리 개인키).
 *
 * <p><b>KMS 확장 계획 (2-1-3)</b>: 운영 환경에서는 개인키를 JVM 힙에 두지 않고
 * 외부 HSM/KMS에 위임해야 한다. 이 인터페이스를 구현하는
 * {@code KmsSignerConnector}(AWS KMS) 또는 {@code VaultSignerConnector}(HashiCorp Vault)를
 * Phase 3에서 추가할 계획이다.
 * 인터페이스 시그니처는 변경 없이 구현체만 교체(DI 설정만 변경)하면 된다.
 *
 * <p><b>PDS 훅 (16-1-3)</b>: Phase 2+에서 PDS(Personal Data Store) 통합 시
 * 복구 키 PDS 식별자를 반환하는 {@link #getRecoveryKeyPdsId()} 메서드를 활용한다.
 * 현재는 no-op (기본 구현 = empty).
 *
 * <p><b>17-4: Chain-agnostic signRaw()</b>: 체인이 직렬화한 txBytes에 서명한다.
 * EVM: 65바이트 (v, r, s) / Bitcoin: DER 서명 / TRON: 65바이트 / Solana: 64바이트 Ed25519.
 * 레거시 {@link #sign(RawTransaction, long)} 메서드는 EVM 전용이며 하위 호환을 위해 유지한다.
 */
public interface Signer {

    /**
     * 레거시 EVM 서명 메서드 — web3j {@link RawTransaction}을 직접 받아 서명한 hex 문자열 반환.
     *
     * @deprecated 새 코드는 {@link #signRaw(byte[])} 를 사용할 것.
     */
    @Deprecated
    String sign(RawTransaction tx, long chainId);

    /**
     * 17-4: Chain-agnostic raw-bytes 서명.
     *
     * <p>체인 레이어가 직렬화한 {@code txBytes} 에 서명하고 서명 바이트를 반환한다.
     * <ul>
     *   <li>EVM  : 65바이트 (v‖r‖s)</li>
     *   <li>Bitcoin : DER-encoded ECDSA 서명</li>
     *   <li>TRON : 65바이트 (v‖r‖s, secp256k1)</li>
     *   <li>Solana : 64바이트 Ed25519 서명</li>
     * </ul>
     *
     * @param txBytes 체인이 직렬화한 트랜잭션 바이트 (서명 전)
     * @return 서명 바이트 (형식은 체인 타입에 따라 상이)
     */
    default byte[] signRaw(byte[] txBytes) {
        throw new UnsupportedOperationException(
                "signRaw() not implemented for " + getClass().getSimpleName());
    }

    /** 서명자 주소 (체인별 형식). */
    String getAddress();

    /**
     * 17-4: 이 서명자가 담당하는 체인 타입.
     *
     * <p>기본값: EVM (하위 호환).
     */
    default ChainType getChainType() {
        return ChainType.EVM;
    }

    /**
     * 16-1-3: PDS 복구 키 식별자.
     * Phase 2+: PdsCoreClient로 복구 키를 PDS에 등록한 후 해당 pdsId를 반환.
     * Phase 1 (현재): no-op — Optional.empty() 반환.
     */
    default Optional<String> getRecoveryKeyPdsId() {
        return Optional.empty();
    }
}
