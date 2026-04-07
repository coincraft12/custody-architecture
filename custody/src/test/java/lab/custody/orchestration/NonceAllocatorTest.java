package lab.custody.orchestration;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * NonceAllocator 단위 테스트.
 *
 * 검증 항목:
 *  1. 동일 주소에 대해 순차적으로 증가하는 nonce 반환
 *  2. 서로 다른 주소는 독립된 nonce 카운터 사용
 *  3. 첫 호출은 nonce 0 반환
 */
class NonceAllocatorTest {

    @Test
    void reserve_firstCall_returnsZero() {
        NonceAllocator allocator = new NonceAllocator();

        long nonce = allocator.reserve("0xfrom");

        assertThat(nonce).isEqualTo(0L);
    }

    @Test
    void reserve_consecutiveCalls_returnsIncrementingNonces() {
        NonceAllocator allocator = new NonceAllocator();

        assertThat(allocator.reserve("0xaddr")).isEqualTo(0L);
        assertThat(allocator.reserve("0xaddr")).isEqualTo(1L);
        assertThat(allocator.reserve("0xaddr")).isEqualTo(2L);
    }

    @Test
    void reserve_differentAddresses_haveIndependentCounters() {
        NonceAllocator allocator = new NonceAllocator();

        assertThat(allocator.reserve("0xaddr1")).isEqualTo(0L);
        assertThat(allocator.reserve("0xaddr2")).isEqualTo(0L);
        assertThat(allocator.reserve("0xaddr1")).isEqualTo(1L);
        assertThat(allocator.reserve("0xaddr2")).isEqualTo(1L);
    }

    @Test
    void reserve_manyAllocations_noncesAreContiguous() {
        NonceAllocator allocator = new NonceAllocator();

        for (int i = 0; i < 100; i++) {
            assertThat(allocator.reserve("0xbulk")).isEqualTo((long) i);
        }
    }
}
