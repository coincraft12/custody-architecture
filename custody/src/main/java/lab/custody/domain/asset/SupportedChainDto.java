package lab.custody.domain.asset;

public record SupportedChainDto(
        String chainType,
        String nativeAsset,
        boolean enabled
) {
    public static SupportedChainDto from(SupportedChain chain) {
        return new SupportedChainDto(chain.getChainType(), chain.getNativeAsset(), chain.isEnabled());
    }
}
