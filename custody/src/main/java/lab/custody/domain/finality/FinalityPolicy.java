package lab.custody.domain.finality;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * 17-9: JPA entity for the {@code finality_policies} table.
 *
 * <p>Stores per-chain, per-tier finalization rules.
 * Tier values: LOW, MEDIUM, HIGH.
 */
@Entity
@Table(name = "finality_policies")
@Getter
@Setter
@NoArgsConstructor
public class FinalityPolicy {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "chain_type", nullable = false, length = 32)
    private String chainType;

    @Column(name = "tier", nullable = false, length = 16)
    private String tier;

    @Column(name = "min_confirmations", nullable = false)
    private int minConfirmations;

    @Column(name = "require_safe_head", nullable = false)
    private boolean requireSafeHead;

    @Column(name = "require_finalized_head", nullable = false)
    private boolean requireFinalizedHead;

    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();
}
