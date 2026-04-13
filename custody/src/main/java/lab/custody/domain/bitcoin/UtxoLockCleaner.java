package lab.custody.domain.bitcoin;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * 19-5: Scheduler that periodically expires stale UTXO locks.
 *
 * <p>Runs every 60 seconds. Any LOCKED record whose {@code expiresAt} has
 * passed is transitioned to EXPIRED so the underlying UTXO becomes available
 * for future sends.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class UtxoLockCleaner {

    private final UtxoLockRepository utxoLockRepository;

    @Scheduled(fixedDelay = 60_000)
    @Transactional
    public void expireStale() {
        int count = utxoLockRepository.expireOldLocks(Instant.now());
        if (count > 0) {
            log.info("event=utxo_lock_cleaner.expired count={}", count);
        }
    }
}
