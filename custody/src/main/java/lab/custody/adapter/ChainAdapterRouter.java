package lab.custody.adapter;

import lab.custody.domain.withdrawal.ChainType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 12-2-2 / 17-0: ChainAdapterRouter — 설정 기반 어댑터 선택 확장.
 *
 * <p>기본 동작: Spring이 등록한 {@link ChainAdapter} 빈 목록을 {@link ChainAdapter#getChainType()} 기준으로
 * 자동 매핑한다.
 *
 * <p>17-0: bitcoin / tron / solana @Value 추가 (evm/bft와 동일 패턴).
 */
@Component
@Slf4j
public class ChainAdapterRouter {

    private final Map<ChainType, ChainAdapter> adaptersByChainType;

    public ChainAdapterRouter(
            List<ChainAdapter> adapters,
            @Value("${custody.chain-adapter.evm:}") String evmAdapterName,
            @Value("${custody.chain-adapter.bft:}") String bftAdapterName,
            @Value("${custody.chain-adapter.bitcoin:}") String bitcoinAdapterName,
            @Value("${custody.chain-adapter.tron:}") String tronAdapterName,
            @Value("${custody.chain-adapter.solana:}") String solanaAdapterName
    ) {
        // 1단계: 자동 매핑 (기존 동작 유지)
        Map<ChainType, ChainAdapter> autoMap = adapters.stream()
                .collect(Collectors.toUnmodifiableMap(
                        ChainAdapter::getChainType,
                        Function.identity(),
                        (left, right) -> {
                            throw new IllegalStateException(
                                    "Multiple adapters found for chain type: " + left.getChainType());
                        }
                ));

        // 2단계: Bean 이름 인덱스 구성 (대소문자 무관)
        Map<String, ChainAdapter> bySimpleClassName = new HashMap<>();
        for (ChainAdapter adapter : adapters) {
            String simpleName = adapter.getClass().getSimpleName();
            bySimpleClassName.put(simpleName.toLowerCase(), adapter);
            String beanName = Character.toLowerCase(simpleName.charAt(0)) + simpleName.substring(1);
            bySimpleClassName.put(beanName.toLowerCase(), adapter);
        }

        // 3단계: 설정값 오버라이드 적용
        Map<ChainType, ChainAdapter> mutableMap = new HashMap<>(autoMap);
        applyOverride(mutableMap, bySimpleClassName, ChainType.EVM, evmAdapterName);
        applyOverride(mutableMap, bySimpleClassName, ChainType.BFT, bftAdapterName);
        applyOverride(mutableMap, bySimpleClassName, ChainType.BITCOIN, bitcoinAdapterName);
        applyOverride(mutableMap, bySimpleClassName, ChainType.TRON, tronAdapterName);
        applyOverride(mutableMap, bySimpleClassName, ChainType.SOLANA, solanaAdapterName);

        this.adaptersByChainType = Map.copyOf(mutableMap);

        adaptersByChainType.forEach((chainType, adapter) ->
                log.info("event=chain_adapter_router.registered chainType={} adapter={}",
                        chainType, adapter.getClass().getSimpleName()));
    }

    private static void applyOverride(
            Map<ChainType, ChainAdapter> target,
            Map<String, ChainAdapter> byName,
            ChainType chainType,
            String configuredName
    ) {
        if (configuredName == null || configuredName.isBlank()) {
            return;
        }
        ChainAdapter overrideAdapter = byName.get(configuredName.toLowerCase());
        if (overrideAdapter == null) {
            log.warn("event=chain_adapter_router.override_not_found chainType={} configured={}",
                    chainType, configuredName);
            return;
        }
        ChainAdapter previous = target.put(chainType, overrideAdapter);
        log.info("event=chain_adapter_router.override_applied chainType={} configured={} previous={}",
                chainType,
                overrideAdapter.getClass().getSimpleName(),
                previous != null ? previous.getClass().getSimpleName() : "none");
    }

    // Needed for log.warn in static context
    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(ChainAdapterRouter.class);

    public ChainAdapter resolve(ChainType type) {
        return java.util.Optional.ofNullable(adaptersByChainType.get(type))
                .orElseThrow(() -> new IllegalArgumentException("No adapter registered for chain type: " + type));
    }
}
