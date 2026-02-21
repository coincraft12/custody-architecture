package lab.custody.orchestration;

import lab.custody.adapter.ChainAdapter;
import lab.custody.adapter.ChainAdapterRouter;
import lab.custody.adapter.EvmRpcAdapter;
import lab.custody.domain.policy.PolicyAuditLog;
import lab.custody.domain.policy.PolicyAuditLogRepository;
import lab.custody.domain.txattempt.TxAttempt;
import lab.custody.domain.txattempt.TxAttemptStatus;
import lab.custody.domain.withdrawal.ChainType;
import lab.custody.domain.withdrawal.Withdrawal;
import lab.custody.domain.withdrawal.WithdrawalRepository;
import lab.custody.domain.withdrawal.WithdrawalStatus;
import lab.custody.orchestration.policy.PolicyDecision;
import lab.custody.orchestration.policy.PolicyEngine;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class WithdrawalService {

    private final WithdrawalRepository withdrawalRepository;
    private final AttemptService attemptService;
    private final PolicyEngine policyEngine;
    private final PolicyAuditLogRepository policyAuditLogRepository;
    private final ChainAdapterRouter router;
    
    @Autowired(required = false)
    private ApprovalService approvalService;
    
    @Autowired(required = false)
    private LedgerService ledgerService;
    
    @Autowired(required = false)
    private ConfirmationTracker confirmationTracker;

    @Transactional
    public Withdrawal createOrGet(String idempotencyKey, CreateWithdrawalRequest req) {
        ChainType chainType = parseChainType(req.chainType());
        return withdrawalRepository.findByIdempotencyKey(idempotencyKey)
                .map(existing -> validateIdempotentRequest(existing, chainType, req))
                .orElseGet(() -> createAndBroadcast(idempotencyKey, chainType, req));
    }

    private Withdrawal createAndBroadcast(String idempotencyKey, ChainType chainType, CreateWithdrawalRequest req) {
        Withdrawal saved = withdrawalRepository.save(Withdrawal.requested(
                idempotencyKey,
                chainType,
                req.fromAddress(),
                req.toAddress(),
                req.asset(),
                req.amount()
        ));
        if (ledgerService != null) {
            saved = ledgerService.saveWithdrawal(saved);
        }

        PolicyDecision decision = policyEngine.evaluate(req);
        policyAuditLogRepository.save(PolicyAuditLog.of(saved.getId(), decision.allowed(), decision.reason()));

        if (!decision.allowed()) {
            saved.transitionTo(WithdrawalStatus.W0_POLICY_REJECTED);
            if (ledgerService != null) {
                return ledgerService.saveWithdrawal(saved);
            }
            return withdrawalRepository.save(saved);
        }

        // Approval stage (currently auto-approved). If ApprovalService is not present, default to approved.
        boolean approved = approvalService == null || approvalService.requestApproval(saved);
        if (!approved) {
            saved.transitionTo(WithdrawalStatus.W0_POLICY_REJECTED);
            if (ledgerService != null) {
                return ledgerService.saveWithdrawal(saved);
            }
            return withdrawalRepository.save(saved);
        }

        TxAttempt attempt = attemptService.createAttempt(saved.getId(), req.fromAddress(), resolveInitialNonce(chainType, req.fromAddress()));
        broadcastAttempt(saved, attempt);

        // persist latest state via ledger if available
        if (ledgerService != null) {
            ledgerService.saveAttempt(attempt);
            saved = ledgerService.saveWithdrawal(saved);
        } else {
            // fallback: ensure withdrawal persisted
            withdrawalRepository.save(saved);
        }

        // start async confirmation tracking if available
        if (confirmationTracker != null) {
            confirmationTracker.startTracking(attempt);
        }

        return saved;
    }

    private long resolveInitialNonce(ChainType chainType, String fromAddress) {
        ChainAdapter adapter = router.resolve(chainType);
        if (adapter instanceof EvmRpcAdapter rpcAdapter) {
            return rpcAdapter.getPendingNonce(fromAddress).longValue();
        }
        return 0L;
    }

    private void broadcastAttempt(Withdrawal withdrawal, TxAttempt attempt) {
        ChainAdapter.BroadcastResult result = router.resolve(withdrawal.getChainType()).broadcast(
                new ChainAdapter.BroadcastCommand(
                        withdrawal.getId(),
                        withdrawal.getFromAddress(),
                        withdrawal.getToAddress(),
                        withdrawal.getAsset(),
                        withdrawal.getAmount(),
                        attempt.getNonce(),
                        attempt.getMaxPriorityFeePerGas(),
                        attempt.getMaxFeePerGas()
                )
        );

        attempt.setTxHash(result.txHash());
        attempt.transitionTo(TxAttemptStatus.BROADCASTED);
        withdrawal.transitionTo(WithdrawalStatus.W6_BROADCASTED);
        // saving of attempt/wdl is handled by LedgerService by caller
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
