package lab.custody.adapter;

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
 */
public interface Signer {
    String sign(RawTransaction tx, long chainId);
    String getAddress();

    /**
     * 16-1-3: PDS 복구 키 식별자.
     * Phase 2+: PdsCoreClient로 복구 키를 PDS에 등록한 후 해당 pdsId를 반환.
     * Phase 1 (현재): no-op — Optional.empty() 반환.
     */
    default Optional<String> getRecoveryKeyPdsId() {
        return Optional.empty();
    }
}
