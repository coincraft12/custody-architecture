package lab.custody.domain.asset;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "supported_assets",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_supported_assets_chain_symbol",
                columnNames = {"chain_type", "asset_symbol"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class SupportedAsset {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "asset_symbol", nullable = false, length = 20)
    private String assetSymbol;

    @Column(name = "chain_type", nullable = false, length = 32)
    private String chainType;

    /** NULL이면 native asset */
    @Column(name = "contract_address", length = 128)
    private String contractAddress;

    @Column(nullable = false)
    private int decimals;

    @Column(name = "default_gas_limit", nullable = false)
    private long defaultGasLimit;

    @Column(nullable = false)
    private boolean enabled;

    /** is_native 컬럼. 필드명은 native_가 예약어 충돌 방지용, getter는 isNative() */
    @Column(name = "is_native", nullable = false)
    private boolean native_;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** is_native 컬럼에 대한 명시적 getter */
    public boolean isNative() {
        return native_;
    }
}
