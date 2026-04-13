package lab.custody.domain.bitcoin;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 19-4: Repository for UTXO lock records.
 */
@Repository
public interface UtxoLockRepository extends JpaRepository<UtxoLock, UUID> {

    /**
     * Returns all locked UTXO keys as "txid:vout" strings.
     * Used to filter out in-use UTXOs during UTXO selection.
     */
    @Query("SELECT CONCAT(u.txid, ':', u.vout) FROM UtxoLock u WHERE u.status = 'LOCKED'")
    List<String> findAllLockedUtxoKeys();

    /**
     * Expires all LOCKED records whose expiresAt is in the past.
     *
     * @param now current instant
     * @return number of records updated
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE UtxoLock u SET u.status = 'EXPIRED' WHERE u.status = 'LOCKED' AND u.expiresAt < :now")
    int expireOldLocks(@Param("now") Instant now);
}
