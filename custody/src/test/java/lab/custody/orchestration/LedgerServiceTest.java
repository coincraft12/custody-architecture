package lab.custody.orchestration;

import lab.custody.domain.ledger.LedgerEntry;
import lab.custody.domain.ledger.LedgerEntryRepository;
import lab.custody.domain.ledger.LedgerEntryType;
import lab.custody.domain.txattempt.TxAttemptRepository;
import lab.custody.domain.withdrawal.ChainType;
import lab.custody.domain.withdrawal.Withdrawal;
import lab.custody.domain.withdrawal.WithdrawalRepository;
import lab.custody.domain.withdrawal.WithdrawalStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LedgerServiceTest {

    @Mock
    private WithdrawalRepository withdrawalRepository;

    @Mock
    private TxAttemptRepository txAttemptRepository;

    @Mock
    private LedgerEntryRepository ledgerEntryRepository;

    private LedgerService ledgerService;

    @BeforeEach
    void setUp() {
        ledgerService = new LedgerService(withdrawalRepository, txAttemptRepository, ledgerEntryRepository);
    }

    @Test
    void settle_afterReserve_recordsSettleAndTransitionsToCompleted() {
        Withdrawal withdrawal = finalizedWithdrawal();

        when(ledgerEntryRepository.existsByWithdrawalIdAndType(withdrawal.getId(), LedgerEntryType.RESERVE)).thenReturn(true);
        when(ledgerEntryRepository.existsByWithdrawalIdAndType(withdrawal.getId(), LedgerEntryType.SETTLE)).thenReturn(false);
        when(ledgerEntryRepository.save(any(LedgerEntry.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(withdrawalRepository.save(withdrawal)).thenReturn(withdrawal);

        Withdrawal settled = ledgerService.settle(withdrawal);

        assertThat(settled.getStatus()).isEqualTo(WithdrawalStatus.W10_COMPLETED);
        verify(ledgerEntryRepository).save(any(LedgerEntry.class));
        verify(withdrawalRepository).save(withdrawal);
    }

    @Test
    void settle_withoutReserve_throwsAndDoesNotWriteSettleEntry() {
        Withdrawal withdrawal = finalizedWithdrawal();

        when(ledgerEntryRepository.existsByWithdrawalIdAndType(withdrawal.getId(), LedgerEntryType.RESERVE)).thenReturn(false);

        assertThatThrownBy(() -> ledgerService.settle(withdrawal))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("cannot settle before reserve");

        verify(ledgerEntryRepository, never()).save(any(LedgerEntry.class));
        verify(withdrawalRepository, never()).save(any(Withdrawal.class));
    }

    @Test
    void settle_whenAlreadySettled_throwsAndPreventsDuplicateSettle() {
        Withdrawal withdrawal = finalizedWithdrawal();

        when(ledgerEntryRepository.existsByWithdrawalIdAndType(withdrawal.getId(), LedgerEntryType.RESERVE)).thenReturn(true);
        when(ledgerEntryRepository.existsByWithdrawalIdAndType(withdrawal.getId(), LedgerEntryType.SETTLE)).thenReturn(true);

        assertThatThrownBy(() -> ledgerService.settle(withdrawal))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("settle already recorded");

        verify(ledgerEntryRepository, never()).save(any(LedgerEntry.class));
        verify(withdrawalRepository, never()).save(any(Withdrawal.class));
    }

    private Withdrawal finalizedWithdrawal() {
        Withdrawal withdrawal = Withdrawal.requested("idem-ledger-unit", ChainType.EVM, "0xfrom", "0xto", "ETH", 1L);
        ReflectionTestUtils.setField(withdrawal, "id", UUID.randomUUID());
        withdrawal.transitionTo(WithdrawalStatus.W8_SAFE_FINALIZED);
        return withdrawal;
    }
}
