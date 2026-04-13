package lab.custody.domain.bitcoin;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

import java.time.Instant;
import java.util.UUID;

/**
 * 19-3: JPA entity for UTXO optimistic locks.
 *
 * <p>A UTXO is locked when it is selected as an input for a pending transaction.
 * This prevents double-spend during the broadcast window.
 */
@Data
@Entity
@Table(name = "utxo_locks")
public class UtxoLock {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "txid", nullable = false, length = 64)
    private String txid;

    @Column(name = "vout", nullable = false)
    private int vout;

    @Column(name = "address", nullable = false, length = 100)
    private String address;

    @Column(name = "amount_sat", nullable = false)
    private Long amountSat;

    @Column(name = "withdrawal_id")
    private UUID withdrawalId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private UtxoLockStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    public enum UtxoLockStatus {
        LOCKED,
        RELEASED,
        EXPIRED
    }
}
