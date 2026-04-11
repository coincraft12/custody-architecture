package lab.custody.adapter;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 1-4-4: BroadcastRejectedException "nonce too low" 파싱 단위 테스트.
 *
 * RPC 에러 메시지에서 "nonce too low" 패턴을 감지하는 로직을 검증한다.
 * WithdrawalService / RetryReplaceService의 자동 재예약 트리거 분기 조건이다.
 */
class BroadcastRejectedExceptionTest {

    @Test
    void isNonceTooLow_true_whenMessageContainsNonceTooLow() {
        assertThat(new BroadcastRejectedException("nonce too low").isNonceTooLow()).isTrue();
    }

    @Test
    void isNonceTooLow_true_whenRpcWrapsMessage() {
        assertThat(new BroadcastRejectedException(
                "EVM RPC rejected transaction: nonce too low").isNonceTooLow()).isTrue();
    }

    @Test
    void isNonceTooLow_true_caseInsensitive() {
        assertThat(new BroadcastRejectedException("Nonce Too Low").isNonceTooLow()).isTrue();
        assertThat(new BroadcastRejectedException("NONCE TOO LOW").isNonceTooLow()).isTrue();
    }

    @Test
    void isNonceTooLow_false_forOtherErrors() {
        assertThat(new BroadcastRejectedException("insufficient funds for gas").isNonceTooLow()).isFalse();
        assertThat(new BroadcastRejectedException("gas price too low to replace").isNonceTooLow()).isFalse();
        assertThat(new BroadcastRejectedException("invalid sender").isNonceTooLow()).isFalse();
    }

    @Test
    void isNonceTooLow_false_whenMessageIsNull() {
        assertThat(new BroadcastRejectedException(null).isNonceTooLow()).isFalse();
    }
}
