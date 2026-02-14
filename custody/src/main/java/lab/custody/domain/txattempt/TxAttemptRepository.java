package lab.custody.domain.txattempt;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TxAttemptRepository extends JpaRepository<TxAttempt, UUID> {

    List<TxAttempt> findByWithdrawalIdOrderByAttemptNoAsc(UUID withdrawalId);

    Optional<TxAttempt> findFirstByAttemptGroupKeyAndCanonicalTrue(String attemptGroupKey);

    List<TxAttempt> findByAttemptGroupKeyOrderByAttemptNoAsc(String attemptGroupKey);
}
