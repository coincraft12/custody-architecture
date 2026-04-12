package lab.custody.domain.policy;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface PolicyChangeRequestRepository extends JpaRepository<PolicyChangeRequest, UUID> {

    /** 특정 변경 유형의 최근 이력 조회 */
    List<PolicyChangeRequest> findByChangeTypeOrderByCreatedAtDesc(String changeType);

    /** 특정 상태의 변경 요청 목록 조회 */
    List<PolicyChangeRequest> findByStatusOrderByCreatedAtDesc(String status);
}
