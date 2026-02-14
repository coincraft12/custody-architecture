package lab.custody.orchestration;

import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class NonceAllocator {

    // fromAddress별로 nonce를 증가시키는 단순 allocator (실습용)
    private final ConcurrentHashMap<String, AtomicLong> nextNonce = new ConcurrentHashMap<>();

    public long reserve(String fromAddress) {
        return nextNonce.computeIfAbsent(fromAddress, k -> new AtomicLong(0)).getAndIncrement();
    }
}
