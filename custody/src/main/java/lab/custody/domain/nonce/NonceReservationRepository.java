package lab.custody.domain.nonce;

import jakarta.persistence.LockModeType;
import lab.custody.domain.withdrawal.ChainType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface NonceReservationRepository extends JpaRepository<NonceReservation, UUID> {

    Optional<NonceReservation> findByAttemptId(UUID attemptId);

    Optional<NonceReservation> findByChainTypeAndFromAddressAndNonce(
            ChainType chainType, String fromAddress, long nonce);

    List<NonceReservation> findByWithdrawalId(UUID withdrawalId);

    List<NonceReservation> findByStatus(NonceReservationStatus status);

    /** NonceCleaner: 만료 시각이 지난 RESERVED 예약 목록 조회 */
    List<NonceReservation> findByStatusAndExpiresAtLessThan(
            NonceReservationStatus status, Instant now);

    /** 주소별 RESERVED 상태 예약 목록 — 중복 예약 여부 확인용 */
    List<NonceReservation> findByChainTypeAndFromAddressAndStatus(
            ChainType chainType, String fromAddress, NonceReservationStatus status);

    /** 활성 예약(RESERVED + COMMITTED) 중 가장 높은 넌스 조회 */
    @Query("""
            SELECT MAX(r.nonce) FROM NonceReservation r
            WHERE r.chainType = :chainType
              AND r.fromAddress = :fromAddress
              AND r.status IN (lab.custody.domain.nonce.NonceReservationStatus.RESERVED,
                               lab.custody.domain.nonce.NonceReservationStatus.COMMITTED)
            """)
    Optional<Long> findMaxActiveNonce(
            @Param("chainType") ChainType chainType,
            @Param("fromAddress") String fromAddress);

    /**
     * 동시 예약 충돌 방지용 비관적 락 조회.
     * 같은 주소에 대한 활성 예약 레코드를 SELECT FOR UPDATE로 잠근다.
     * 이 메서드를 호출한 트랜잭션이 커밋될 때까지 동일 주소의 다른 예약 시도가 블록된다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT r FROM NonceReservation r
            WHERE r.chainType = :chainType
              AND r.fromAddress = :fromAddress
              AND r.status IN (lab.custody.domain.nonce.NonceReservationStatus.RESERVED,
                               lab.custody.domain.nonce.NonceReservationStatus.COMMITTED)
            """)
    List<NonceReservation> findActiveWithLock(
            @Param("chainType") ChainType chainType,
            @Param("fromAddress") String fromAddress);

    /** NonceCleaner 벌크 만료 처리 */
    @Modifying
    @Query("""
            UPDATE NonceReservation r
               SET r.status = lab.custody.domain.nonce.NonceReservationStatus.EXPIRED,
                   r.updatedAt = :now
             WHERE r.status = lab.custody.domain.nonce.NonceReservationStatus.RESERVED
               AND r.expiresAt < :now
            """)
    int bulkExpire(@Param("now") Instant now);
}
