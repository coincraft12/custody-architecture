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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

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

    @InjectMocks WithdrawalService withdrawalService;

    @Test
    void sameIdempotencyKey_doesNotRebroadcast() {
        CreateWithdrawalRequest req = new CreateWithdrawalRequest("evm", "0xfrom", "0xto", "ETH", 1L);
        Withdrawal w = Withdrawal.requested("idem-1", ChainType.EVM, "0xfrom", "0xto", "ETH", 1L);

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
