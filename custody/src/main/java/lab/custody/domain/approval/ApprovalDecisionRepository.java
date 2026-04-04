package lab.custody.domain.approval;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ApprovalDecisionRepository extends JpaRepository<ApprovalDecision, UUID> {

    List<ApprovalDecision> findByApprovalTaskIdOrderByCreatedAtAsc(UUID approvalTaskId);

    Optional<ApprovalDecision> findByApprovalTaskIdAndApproverId(
            UUID approvalTaskId, String approverId);

    boolean existsByApprovalTaskIdAndApproverId(UUID approvalTaskId, String approverId);

    long countByApprovalTaskIdAndDecision(UUID approvalTaskId, ApprovalDecisionType decision);
}
