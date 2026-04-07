package lab.custody.domain.nonce;

import jakarta.persistence.*;
import lab.custody.domain.withdrawal.ChainType;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/**
 * 넌스 예약 엔티티.
 *
 * <p>NonceAllocator가 DB 기반 넌스 관리를 수행할 때 사용하는 예약 레코드.
 * 서버 재시작 시에도 넌스 상태가 유지되며, 중복 넌스 발급을 방지한다.
 *
 * <p>상태 전이: RESERVED → COMMITTED → RELEASED
 *                RESERVED → EXPIRED (만료 스케줄러)
 */
@Entity
@Table(
    name = "nonce_reservations",
    uniqueConstraints = {
        @UniqueConstraint(
            name = "uq_nonce_reservations_chain_from_nonce",
            columnNames = {"chain_type", "from_address", "nonce"}
        )
    },
    indexes = {
        @Index(name = "idx_nonce_reservations_withdrawal_id", columnList = "withdrawal_id"),
        @Index(name = "idx_nonce_reservations_attempt_id",    columnList = "attempt_id"),
        @Index(name = "idx_nonce_reservations_status_expires_at", columnList = "status, expires_at")
    }
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class NonceReservation {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "chain_type", nullable = false, length = 32)
    private ChainType chainType;

    @Column(name = "from_address", nullable = false, length = 128)
    private String fromAddress;

    @Column(nullable = false)
    private long nonce;

    /** 이 넌스를 사용하는 출금 요청 ID (optional) */
    @Column(name = "withdrawal_id")
    private UUID withdrawalId;

    /** 이 넌스를 사용하는 TxAttempt ID (optional) */
    @Column(name = "attempt_id")
    private UUID attemptId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private NonceReservationStatus status;

    /** 예약 만료 시각 — NonceCleaner가 EXPIRED 처리 기준으로 사용 */
    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    // ─────────────────────────── factory ───────────────────────────

    public static NonceReservation reserve(
            ChainType chainType,
            String fromAddress,
            long nonce,
            UUID withdrawalId,
            Instant expiresAt) {
        Instant now = Instant.now();
        return NonceReservation.builder()
                .chainType(chainType)
                .fromAddress(fromAddress)
                .nonce(nonce)
                .withdrawalId(withdrawalId)
                .status(NonceReservationStatus.RESERVED)
                .expiresAt(expiresAt)
                .createdAt(now)
                .updatedAt(now)
                .build();
    }

    // ─────────────────────────── state transitions ───────────────────────────

    /** 브로드캐스트 완료 후 커밋: RESERVED → COMMITTED */
    public void commit(UUID attemptId) {
        if (this.status != NonceReservationStatus.RESERVED) {
            throw new IllegalStateException(
                "commit() 는 RESERVED 상태에서만 가능합니다. 현재: " + this.status);
        }
        this.attemptId = attemptId;
        this.status = NonceReservationStatus.COMMITTED;
        this.updatedAt = Instant.now();
    }

    /** 정상 완료 또는 재시도 후 해제: COMMITTED|RESERVED → RELEASED */
    public void rebindAttempt(UUID attemptId) {
        if (this.status != NonceReservationStatus.COMMITTED) {
            throw new IllegalStateException(
                "rebindAttempt() ??COMMITTED ?곹깭?먯꽌留?媛?ν빀?덈떎. ?꾩옱: " + this.status);
        }
        this.attemptId = attemptId;
        this.updatedAt = Instant.now();
    }

    public void release() {
        if (this.status == NonceReservationStatus.RELEASED
                || this.status == NonceReservationStatus.EXPIRED) {
            throw new IllegalStateException(
                "release() 불가 상태입니다. 현재: " + this.status);
        }
        this.status = NonceReservationStatus.RELEASED;
        this.updatedAt = Instant.now();
    }

    /** 만료 처리: RESERVED → EXPIRED */
    public void expire() {
        if (this.status != NonceReservationStatus.RESERVED) {
            throw new IllegalStateException(
                "expire() 는 RESERVED 상태에서만 가능합니다. 현재: " + this.status);
        }
        this.status = NonceReservationStatus.EXPIRED;
        this.updatedAt = Instant.now();
    }
}
