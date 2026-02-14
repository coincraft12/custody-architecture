package lab.custody.domain.withdrawal;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface WithdrawalRepository extends JpaRepository<Withdrawal, UUID> {
    Optional<Withdrawal> findByIdempotencyKey(String idempotencyKey);
}
