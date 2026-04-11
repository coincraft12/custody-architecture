package lab.custody.adapter;

import org.web3j.crypto.RawTransaction;

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
 */
public interface Signer {
    String sign(RawTransaction tx, long chainId);
    String getAddress();
}
