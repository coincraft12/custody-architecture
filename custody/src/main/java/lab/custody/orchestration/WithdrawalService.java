package lab.custody.orchestration;


import lab.custody.domain.policy.PolicyAuditLog;
import lab.custody.domain.policy.PolicyAuditLogRepository;
import lab.custody.domain.withdrawal.ChainType;

import lab.custody.domain.withdrawal.Withdrawal;
import lab.custody.domain.withdrawal.WithdrawalRepository;
import lab.custody.domain.withdrawal.WithdrawalStatus;
import lab.custody.orchestration.policy.PolicyDecision;
import lab.custody.orchestration.policy.PolicyEngine;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class WithdrawalService {

    private final WithdrawalRepository withdrawalRepository;
    private final NonceAllocator nonceAllocator;
    private final AttemptService attemptService;
    private final PolicyEngine policyEngine;
    private final PolicyAuditLogRepository policyAuditLogRepository;

    @Transactional
    public Withdrawal createOrGet(String idempotencyKey, CreateWithdrawalRequest req) {
        ChainType chainType = parseChainType(req.chainType());
        return withdrawalRepository.findByIdempotencyKey(idempotencyKey)
                .map(existing -> validateIdempotentRequest(existing, chainType, req))
                .orElseGet(() -> {
                    Withdrawal w = Withdrawal.requested(
                            idempotencyKey,
                            chainType,
                            req.fromAddress(),
                            req.toAddress(),
                            req.asset(),
                            req.amount()
                    );

                    Withdrawal saved = withdrawalRepository.save(w);

                    PolicyDecision decision = policyEngine.evaluate(req);
                    policyAuditLogRepository.save(PolicyAuditLog.of(saved.getId(), decision.allowed(), decision.reason()));

                    if (!decision.allowed()) {
                        saved.transitionTo(WithdrawalStatus.W0_POLICY_REJECTED);
                        return withdrawalRepository.save(saved);
                    }

                    saved.transitionTo(WithdrawalStatus.W1_POLICY_CHECKED);
                    saved.transitionTo(WithdrawalStatus.W4_SIGNING);
                    Withdrawal policyPassed = withdrawalRepository.save(saved);

                    long nonce = nonceAllocator.reserve(req.fromAddress());
                    attemptService.createAttempt(policyPassed.getId(), req.fromAddress(), nonce);

                    return policyPassed;
                });
    }

    private Withdrawal validateIdempotentRequest(Withdrawal existing, ChainType chainType, CreateWithdrawalRequest req) {
        boolean matches = existing.getChainType() == chainType
                && existing.getFromAddress().equals(req.fromAddress())
                && existing.getToAddress().equals(req.toAddress())
                && existing.getAsset().equals(req.asset())
                && existing.getAmount() == req.amount();

        if (!matches) {
            throw new IdempotencyConflictException("same Idempotency-Key cannot be used with a different request body");
        }

        return existing;
    }

    @Transactional(readOnly = true)
    public Withdrawal get(UUID id) {
        return withdrawalRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("withdrawal not found: " + id));
    }

    @Transactional(readOnly = true)
    public List<PolicyAuditLog> getPolicyAudits(UUID withdrawalId) {
        return policyAuditLogRepository.findByWithdrawalIdOrderByCreatedAtAsc(withdrawalId);
    }

    private ChainType parseChainType(String chainType) {
        if (chainType == null || chainType.isBlank()) {
            return ChainType.EVM;
        }

        try {
            return ChainType.valueOf(chainType.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new InvalidRequestException("invalid chainType: " + chainType);
        }
    }
}
