package lab.custody.adapter;

import org.web3j.protocol.Web3j;

import java.util.List;

/**
 * 4-3: 다중 RPC 프로바이더 풀.
 *
 * <p>primary URL + 선택적 fallback URL 목록을 보유한다.
 * {@link EvmRpcAdapter}에서 RPC 호출 실패 시 순서대로 다음 프로바이더를 시도하는 데 사용한다.
 * {@link #shutdown()}을 호출하면 모든 Web3j 인스턴스가 종료된다.
 */
public class EvmRpcProviderPool {

    private final List<String> urls;
    private final List<Web3j> instances;

    public EvmRpcProviderPool(List<String> urls, List<Web3j> instances) {
        if (urls.isEmpty() || instances.isEmpty()) {
            throw new IllegalArgumentException("EvmRpcProviderPool requires at least one provider");
        }
        this.urls = List.copyOf(urls);
        this.instances = List.copyOf(instances);
    }

    /** 기본(primary) 프로바이더 반환 — broadcast() 등 멱등하지 않은 작업에 사용. */
    public Web3j primary() {
        return instances.get(0);
    }

    /** 기본 프로바이더 URL */
    public String primaryUrl() {
        return urls.get(0);
    }

    /** 인덱스로 프로바이더 조회 */
    public Web3j get(int index) {
        return instances.get(index);
    }

    /** 인덱스로 URL 조회 — 로깅에 사용 (4-3-5) */
    public String getUrl(int index) {
        return urls.get(index);
    }

    /** 전체 프로바이더 수 */
    public int size() {
        return instances.size();
    }

    /** 모든 Web3j 연결 종료 */
    public void shutdown() {
        instances.forEach(Web3j::shutdown);
    }
}
