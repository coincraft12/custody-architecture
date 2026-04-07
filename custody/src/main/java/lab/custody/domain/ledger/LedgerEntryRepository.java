package lab.custody.domain.ledger;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface LedgerEntryRepository extends JpaRepository<LedgerEntry, UUID> {

    List<LedgerEntry> findByWithdrawalIdOrderByCreatedAtAsc(UUID withdrawalId);

    boolean existsByWithdrawalIdAndType(UUID withdrawalId, LedgerEntryType type);
}
