package lab.custody.orchestration;

import lab.custody.adapter.ChainAdapter;
import lab.custody.adapter.ChainAdapterRouter;
import lab.custody.domain.nonce.NonceReservation;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class WithdrawalServiceIdempotencyTest {

    @Mock WithdrawalRepository withdrawalRepository;
    @Mock AttemptService attemptService;
    @Mock PolicyEngine policyEngine;
    @Mock PolicyAuditLogRepository policyAuditLogRepository;
    @Mock TxAttemptRepository txAttemptRepository;
    @Mock ChainAdapterRouter router;
    @Mock NonceAllocator nonceAllocator;
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
                nonceAllocator,
                transactionTemplate,
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry()
        );

        when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0, TransactionCallback.class);
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
        NonceReservation reservation = NonceReservation.reserve(ChainType.EVM, "0xfrom", 0L, UUID.randomUUID(), null);
        ReflectionTestUtils.setField(reservation, "id", UUID.randomUUID());
        when(nonceAllocator.reserve(ChainType.EVM, "0xfrom", w.getId())).thenReturn(reservation);
        when(attemptService.createAttempt(any(), any(), anyLong())).thenReturn(
                lab.custody.domain.txattempt.TxAttempt.created(UUID.randomUUID(), 1, "0xfrom", 0, true)
        );
        when(router.resolve(ChainType.EVM)).thenReturn(adapter);
        when(adapter.broadcast(any())).thenReturn(new ChainAdapter.BroadcastResult("0xtx", true));
        when(nonceAllocator.commit(any(), any())).thenReturn(reservation);

        withdrawalService.createOrGet("idem-1", req);
        withdrawalService.createOrGet("idem-1", req);

        verify(adapter, times(1)).broadcast(any());
    }

    @Test
    void sameIdempotencyKey_parallelRequests_onlyBroadcastOnce() throws Exception {
        CreateWithdrawalRequest req = new CreateWithdrawalRequest("evm", "0xfrom", "0xto", "ETH", new java.math.BigDecimal("1"));
        long oneEthWei = 1000000000000000000L;
        AtomicReference<Withdrawal> storedWithdrawal = new AtomicReference<>();

        when(withdrawalRepository.findByIdempotencyKey("idem-race-1"))
                .thenAnswer(invocation -> Optional.ofNullable(storedWithdrawal.get()));
        when(withdrawalRepository.save(any())).thenAnswer(invocation -> {
            Withdrawal saved = invocation.getArgument(0);
            if (storedWithdrawal.compareAndSet(null, saved)) {
                ReflectionTestUtils.setField(saved, "id", UUID.randomUUID());
                Thread.sleep(50L);
            }
            return storedWithdrawal.get();
        });
        when(policyEngine.evaluate(req)).thenReturn(PolicyDecision.allow());
        when(nonceAllocator.reserve(eq(ChainType.EVM), eq("0xfrom"), any())).thenAnswer(invocation -> {
            UUID withdrawalId = invocation.getArgument(2);
            NonceReservation reservation = NonceReservation.reserve(ChainType.EVM, "0xfrom", 0L, withdrawalId, null);
            ReflectionTestUtils.setField(reservation, "id", UUID.randomUUID());
            return reservation;
        });
        when(attemptService.createAttempt(any(), any(), anyLong())).thenReturn(
                lab.custody.domain.txattempt.TxAttempt.created(UUID.randomUUID(), 1, "0xfrom", 0, true)
        );
        when(router.resolve(ChainType.EVM)).thenReturn(adapter);
        when(adapter.broadcast(any())).thenReturn(new ChainAdapter.BroadcastResult("0xtx-race", true));
        when(nonceAllocator.commit(any(), any())).thenAnswer(invocation -> {
            UUID reservationId = invocation.getArgument(0);
            NonceReservation committed = NonceReservation.reserve(ChainType.EVM, "0xfrom", 0L, storedWithdrawal.get().getId(), null);
            ReflectionTestUtils.setField(committed, "id", reservationId);
            ReflectionTestUtils.setField(committed, "status", lab.custody.domain.nonce.NonceReservationStatus.COMMITTED);
            ReflectionTestUtils.setField(committed, "attemptId", invocation.getArgument(1));
            return committed;
        });

        int parallelCalls = 10;
        CountDownLatch ready = new CountDownLatch(parallelCalls);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(parallelCalls);

        try {
            List<Future<Withdrawal>> futures = java.util.stream.IntStream.range(0, parallelCalls)
                    .mapToObj(i -> executor.submit(() -> {
                        ready.countDown();
                        assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
                        return withdrawalService.createOrGet("idem-race-1", req);
                    }))
                    .toList();

            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            List<Withdrawal> results = new java.util.ArrayList<>();
            for (Future<Withdrawal> future : futures) {
                results.add(future.get(5, TimeUnit.SECONDS));
            }

            assertThat(results).hasSize(parallelCalls);
            assertThat(results).extracting(Withdrawal::getId).containsOnly(storedWithdrawal.get().getId());
            assertThat(results).extracting(Withdrawal::getAmount).containsOnly(oneEthWei);
            verify(adapter, times(1)).broadcast(any());
            verify(attemptService, times(1)).createAttempt(any(), any(), anyLong());
        } finally {
            executor.shutdownNow();
        }
    }
}
