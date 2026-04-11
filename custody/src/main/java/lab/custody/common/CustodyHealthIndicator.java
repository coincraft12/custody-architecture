package lab.custody.common;

import lab.custody.domain.withdrawal.WithdrawalRepository;
import lab.custody.domain.withdrawal.WithdrawalStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * 3-3-2: Custody 도메인 커스텀 헬스 인디케이터.
 *
 * <p>DB 커넥션은 Spring Boot Actuator가 {@code DataSourceHealthIndicator}로 자동 점검하므로
 * 여기서는 도메인 수준 지표만 포함한다.
 * <ul>
 *   <li>{@code broadcastedTxCount} — W6_BROADCASTED 상태로 대기 중인 출금 TX 수</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class CustodyHealthIndicator implements HealthIndicator {

    private final WithdrawalRepository withdrawalRepository;

    @Override
    public Health health() {
        try {
            long broadcasted = withdrawalRepository.countByStatus(WithdrawalStatus.W6_BROADCASTED);
            return Health.up()
                    .withDetail("broadcastedTxCount", broadcasted)
                    .build();
        } catch (Exception e) {
            return Health.down()
                    .withDetail("error", e.getMessage())
                    .build();
        }
    }
}
