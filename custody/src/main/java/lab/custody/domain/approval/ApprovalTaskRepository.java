package lab.custody.domain.approval;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ApprovalTaskRepository extends JpaRepository<ApprovalTask, UUID> {

    Optional<ApprovalTask> findByWithdrawalId(UUID withdrawalId);

    List<ApprovalTask> findByStatus(ApprovalTaskStatus status);

    /** 만료 스케줄러: PENDING 중 만료 시각 초과한 태스크 조회 */
    List<ApprovalTask> findByStatusAndExpiresAtLessThan(
            ApprovalTaskStatus status, Instant now);
}
