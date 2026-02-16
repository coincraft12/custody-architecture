package lab.custody.domain.policy;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface PolicyAuditLogRepository extends JpaRepository<PolicyAuditLog, UUID> {
    List<PolicyAuditLog> findByWithdrawalIdOrderByCreatedAtAsc(UUID withdrawalId);
}
