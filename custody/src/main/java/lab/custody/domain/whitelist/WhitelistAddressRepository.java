package lab.custody.domain.whitelist;

import lab.custody.domain.withdrawal.ChainType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WhitelistAddressRepository extends JpaRepository<WhitelistAddress, UUID> {

    Optional<WhitelistAddress> findByAddressAndChainType(String address, ChainType chainType);

    boolean existsByAddressAndChainTypeAndStatus(String address, ChainType chainType, WhitelistStatus status);

    /** 스케줄러: HOLDING 중 activeAfter 경과한 항목 조회 */
    List<WhitelistAddress> findByStatusAndActiveAfterLessThanEqual(WhitelistStatus status, Instant now);

    List<WhitelistAddress> findByStatus(WhitelistStatus status);

    List<WhitelistAddress> findAllByOrderByRegisteredAtDesc();

    long countByStatus(WhitelistStatus status);
}
