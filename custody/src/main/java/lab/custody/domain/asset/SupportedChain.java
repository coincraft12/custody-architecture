package lab.custody.domain.asset;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "supported_chains")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class SupportedChain {

    @Id
    @Column(name = "chain_type", length = 32)
    private String chainType;

    @Column(name = "native_asset", nullable = false, length = 20)
    private String nativeAsset;

    @Column(name = "adapter_bean_name", nullable = false, length = 64)
    private String adapterBeanName;

    @Column(nullable = false)
    private boolean enabled;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
