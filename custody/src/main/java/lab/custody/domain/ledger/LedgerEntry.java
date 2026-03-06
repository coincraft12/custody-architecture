package lab.custody.domain.ledger;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "ledger_entries",
       indexes = {
           @Index(name = "idx_ledger_withdrawal", columnList = "withdrawalId")
       })
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class LedgerEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private UUID withdrawalId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private LedgerEntryType type;

    @Column(nullable = false, length = 32)
    private String asset;

    @Column(nullable = false)
    private long amount; // wei 단위

    @Column(nullable = false, length = 64)
    private String fromAddress;

    @Column(nullable = false, length = 64)
    private String toAddress;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    public static LedgerEntry reserve(UUID withdrawalId, String asset, long amount,
                                      String fromAddress, String toAddress) {
        return LedgerEntry.builder()
                .withdrawalId(withdrawalId)
                .type(LedgerEntryType.RESERVE)
                .asset(asset)
                .amount(amount)
                .fromAddress(fromAddress)
                .toAddress(toAddress)
                .createdAt(Instant.now())
                .build();
    }

    public static LedgerEntry settle(UUID withdrawalId, String asset, long amount,
                                     String fromAddress, String toAddress) {
        return LedgerEntry.builder()
                .withdrawalId(withdrawalId)
                .type(LedgerEntryType.SETTLE)
                .asset(asset)
                .amount(amount)
                .fromAddress(fromAddress)
                .toAddress(toAddress)
                .createdAt(Instant.now())
                .build();
    }
}
