package lab.custody.orchestration.policy;

import lab.custody.domain.whitelist.WhitelistAddressRepository;
import lab.custody.domain.whitelist.WhitelistStatus;
import lab.custody.domain.withdrawal.ChainType;
import lab.custody.orchestration.CreateWithdrawalRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * 출금 대상 주소가 DB 화이트리스트에서 ACTIVE 상태인지 검증.
 *
 * ACTIVE 조건: status=ACTIVE (HOLDING·REGISTERED·REVOKED 는 거부)
 * 빈 화이트리스트(DB 항목 0건): 전체 거부 (기본 차단, 보안 원칙)
 *
 * 기존 정적 설정(policy.whitelist-to-addresses)은
 * WhitelistService.seedFromStaticConfig() 에서 ACTIVE 엔트리로 자동 시드된다.
 */
@Component
@Order(20)
@RequiredArgsConstructor
public class ToAddressWhitelistPolicyRule implements PolicyRule {

    private final WhitelistAddressRepository whitelistRepository;

    @Override
    public PolicyDecision evaluate(CreateWithdrawalRequest req) {
        ChainType chainType = parseChainType(req.chainType());

        boolean active = whitelistRepository.existsByAddressAndChainTypeAndStatus(
                req.toAddress(), chainType, WhitelistStatus.ACTIVE);

        if (!active) {
            return PolicyDecision.reject("TO_ADDRESS_NOT_WHITELISTED: " + req.toAddress());
        }
        return PolicyDecision.allow();
    }

    private ChainType parseChainType(String chainType) {
        if (chainType == null || chainType.isBlank()) return ChainType.EVM;
        try {
            return ChainType.valueOf(chainType.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return ChainType.EVM;
        }
    }
}
