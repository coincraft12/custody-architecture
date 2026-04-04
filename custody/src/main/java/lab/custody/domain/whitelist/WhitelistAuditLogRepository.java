package lab.custody.domain.whitelist;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface WhitelistAuditLogRepository extends JpaRepository<WhitelistAuditLog, UUID> {

    /** 특정 주소의 전체 변경 이력 조회 (최신 순) */
    List<WhitelistAuditLog> findByWhitelistAddressIdOrderByCreatedAtDesc(UUID whitelistAddressId);

    /** 특정 액션 타입의 이력 조회 */
    List<WhitelistAuditLog> findByAction(String action);

    /** 특정 액터의 전체 액션 이력 조회 */
    List<WhitelistAuditLog> findByActorIdOrderByCreatedAtDesc(String actorId);
}
