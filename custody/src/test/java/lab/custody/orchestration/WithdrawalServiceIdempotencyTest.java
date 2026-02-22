package lab.custody.orchestration;

import lab.custody.adapter.ChainAdapter;
import lab.custody.adapter.ChainAdapterRouter;
import lab.custody.domain.policy.PolicyAuditLogRepository;
import lab.custody.domain.txattempt.TxAttemptRepository;
import lab.custody.domain.withdrawal.ChainType;
import lab.custody.domain.withdrawal.Withdrawal;
import lab.custody.domain.withdrawal.WithdrawalRepository;
import lab.custody.orchestration.policy.PolicyDecision;
import lab.custody.orchestration.policy.PolicyEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WithdrawalServiceIdempotencyTest {

    @Mock WithdrawalRepository withdrawalRepository;
    @Mock AttemptService attemptService;
    @Mock PolicyEngine policyEngine;
    @Mock PolicyAuditLogRepository policyAuditLogRepository;
    @Mock TxAttemptRepository txAttemptRepository;
    @Mock ChainAdapterRouter router;
    @Mock ChainAdapter adapter;
    @Mock TransactionTemplate transactionTemplate;

    WithdrawalService withdrawalService;

    @BeforeEach
    void setUp() {
        // Create WithdrawalService with required dependencies
        // Optional dependencies (approvalService, ledgerService, confirmationTracker) will be null
        withdrawalService = new WithdrawalService(
                withdrawalRepository,
                attemptService,
                policyEngine,
                policyAuditLogRepository,
                router,
                transactionTemplate
        );

        when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(null);
        });
    }

    @Test
    void sameIdempotencyKey_doesNotRebroadcast() {
        CreateWithdrawalRequest req = new CreateWithdrawalRequest("evm", "0xfrom", "0xto", "ETH", new java.math.BigDecimal("1"));
        // 1 ETH in wei
        long oneEthWei = 1000000000000000000L;
        Withdrawal w = Withdrawal.requested("idem-1", ChainType.EVM, "0xfrom", "0xto", "ETH", oneEthWei);

        when(withdrawalRepository.findByIdempotencyKey("idem-1")).thenReturn(Optional.empty(), Optional.of(w));
        when(withdrawalRepository.save(any())).thenReturn(w);
        when(policyEngine.evaluate(req)).thenReturn(PolicyDecision.allow());
        when(attemptService.createAttempt(any(), any(), anyLong())).thenReturn(
                lab.custody.domain.txattempt.TxAttempt.created(UUID.randomUUID(), 1, "0xfrom", 0, true)
        );
        when(router.resolve(ChainType.EVM)).thenReturn(adapter);
        when(adapter.broadcast(any())).thenReturn(new ChainAdapter.BroadcastResult("0xtx", true));

        withdrawalService.createOrGet("idem-1", req);
        withdrawalService.createOrGet("idem-1", req);

        verify(adapter, times(1)).broadcast(any());
    }
}
