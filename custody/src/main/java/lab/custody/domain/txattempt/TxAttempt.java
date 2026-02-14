package lab.custody.domain.txattempt;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "tx_attempts",
       indexes = {
           @Index(name = "idx_attempt_withdrawal", columnList = "withdrawalId"),
           @Index(name = "idx_attempt_group", columnList = "attemptGroupKey"),
           @Index(name = "idx_attempt_txhash", columnList = "txHash")
       })
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class TxAttempt {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private UUID withdrawalId;

    @Column(nullable = false)
    private int attemptNo; // 1..N

    @Column(nullable = false, length = 64)
    private String fromAddress;

    @Column(nullable = false)
    private long nonce;

    // 핵심 키: (from, nonce)
    @Column(nullable = false, length = 140)
    private String attemptGroupKey;

    @Column(length = 80)
    private String txHash; // replace되면 바뀔 수 있음(Attempt 단위)

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private TxAttemptStatus status;

    @Column(nullable = false)
    private boolean canonical; // 현재 “이 그룹의 대표 Attempt”

    @Enumerated(EnumType.STRING)
    @Column(length = 32)
    private AttemptExceptionType exceptionType;

    @Column(length = 200)
    private String exceptionDetail;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    public static TxAttempt created(UUID withdrawalId, int attemptNo, String from, long nonce, boolean canonical) {
        return TxAttempt.builder()
                .withdrawalId(withdrawalId)
                .attemptNo(attemptNo)
                .fromAddress(from)
                .nonce(nonce)
                .attemptGroupKey(groupKey(from, nonce))
                .status(TxAttemptStatus.A0_CREATED)
                .canonical(canonical)
                .createdAt(Instant.now())
                .build();
    }

    public void setTxHash(String txHash) {
        this.txHash = txHash;
    }

    public void transitionTo(TxAttemptStatus next) {
        this.status = next;
    }

    public void markException(AttemptExceptionType type, String detail) {
        this.exceptionType = type;
        this.exceptionDetail = detail;
    }

    public void setCanonical(boolean canonical) {
        this.canonical = canonical;
    }

    public static String groupKey(String from, long nonce) {
        return from + ":" + nonce;
    }
}
