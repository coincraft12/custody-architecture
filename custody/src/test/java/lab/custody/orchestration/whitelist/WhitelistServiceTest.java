package lab.custody.orchestration.whitelist;

import lab.custody.domain.whitelist.WhitelistAddress;
import lab.custody.domain.whitelist.WhitelistAddressRepository;
import lab.custody.domain.whitelist.WhitelistAuditLog;
import lab.custody.domain.whitelist.WhitelistAuditLogRepository;
import lab.custody.domain.whitelist.WhitelistStatus;
import lab.custody.domain.withdrawal.ChainType;
import lab.custody.orchestration.InvalidRequestException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WhitelistServiceTest {

    @Mock
    private WhitelistAddressRepository whitelistRepository;

    @Mock
    private WhitelistAuditLogRepository auditLogRepository;

    private WhitelistService whitelistService;

    @BeforeEach
    void setUp() {
        whitelistService = new WhitelistService(whitelistRepository, auditLogRepository);
        ReflectionTestUtils.setField(whitelistService, "defaultHoldHours", 48L);
        ReflectionTestUtils.setField(whitelistService, "staticWhitelistConfig", "");
    }

    @Test
    void register_normalizesAddress_defaultsRegisteredBy_andDefaultsChainTypeToEvm() {
        RegisterAddressRequest request = new RegisterAddressRequest("  0XABCD  ", null, null, "memo");

        when(whitelistRepository.findByAddressIgnoreCaseAndChainType("0xabcd", ChainType.EVM))
                .thenReturn(Optional.empty());
        when(whitelistRepository.save(any(WhitelistAddress.class))).thenAnswer(invocation -> invocation.getArgument(0));

        WhitelistAddress saved = whitelistService.register(request);

        assertThat(saved.getAddress()).isEqualTo("0xabcd");
        assertThat(saved.getChainType()).isEqualTo(ChainType.EVM);
        assertThat(saved.getRegisteredBy()).isEqualTo("unknown");
        assertThat(saved.getStatus()).isEqualTo(WhitelistStatus.REGISTERED);
    }

    @Test
    void approve_whenEntryIsRevoked_throwsInvalidRequest() {
        UUID id = UUID.randomUUID();
        WhitelistAddress entry = revokedEntry(id);

        when(whitelistRepository.findById(id)).thenReturn(Optional.of(entry));

        assertThatThrownBy(() -> whitelistService.approve(id, "approver"))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("REGISTERED");

        verify(whitelistRepository, never()).save(any(WhitelistAddress.class));
    }

    @Test
    void register_whenConcurrentDuplicateOccurs_translatesDataIntegrityViolation() {
        RegisterAddressRequest request = new RegisterAddressRequest("0xdup", "evm", "admin", "memo");

        when(whitelistRepository.findByAddressIgnoreCaseAndChainType("0xdup", ChainType.EVM))
                .thenReturn(Optional.empty());
        when(whitelistRepository.save(any(WhitelistAddress.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate"));

        assertThatThrownBy(() -> whitelistService.register(request))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("0xdup")
                .hasMessageContaining("EVM");
    }

    @Test
    void promoteHoldingToActive_activatesOnlyEligibleEntries() {
        WhitelistAddress ready = holdingEntry(UUID.randomUUID(), Instant.now().minusSeconds(10));
        WhitelistAddress future = holdingEntry(UUID.randomUUID(), Instant.now().plusSeconds(3600));

        when(whitelistRepository.findByStatusAndActiveAfterLessThanEqual(eq(WhitelistStatus.HOLDING), any(Instant.class)))
                .thenReturn(List.of(ready));
        when(whitelistRepository.save(any(WhitelistAddress.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(auditLogRepository.save(any(WhitelistAuditLog.class))).thenAnswer(invocation -> invocation.getArgument(0));

        whitelistService.promoteHoldingToActive();

        assertThat(ready.getStatus()).isEqualTo(WhitelistStatus.ACTIVE);
        assertThat(future.getStatus()).isEqualTo(WhitelistStatus.HOLDING);
        verify(whitelistRepository).save(ready);
    }

    private WhitelistAddress revokedEntry(UUID id) {
        WhitelistAddress entry = WhitelistAddress.register("0xrevoked", ChainType.EVM, "admin", "memo", 48L);
        ReflectionTestUtils.setField(entry, "id", id);
        entry.revoke("revoker");
        return entry;
    }

    private WhitelistAddress holdingEntry(UUID id, Instant activeAfter) {
        WhitelistAddress entry = WhitelistAddress.register("0xholding-" + id, ChainType.EVM, "admin", "memo", 0L);
        ReflectionTestUtils.setField(entry, "id", id);
        entry.approve("approver");
        ReflectionTestUtils.setField(entry, "activeAfter", activeAfter);
        return entry;
    }
}
