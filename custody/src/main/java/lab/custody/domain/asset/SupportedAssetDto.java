package lab.custody.domain.asset;

public record SupportedAssetDto(
        String assetSymbol,
        String chainType,
        String contractAddress,
        int decimals,
        boolean isNative
) {
    public static SupportedAssetDto from(SupportedAsset asset) {
        return new SupportedAssetDto(
                asset.getAssetSymbol(),
                asset.getChainType(),
                asset.getContractAddress(),
                asset.getDecimals(),
                asset.isNative());
    }
}
