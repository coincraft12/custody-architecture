package lab.custody.domain.asset;

public class AssetNotSupportedException extends RuntimeException {

    public AssetNotSupportedException(String chainType, String assetSymbol) {
        super("Asset not supported: " + assetSymbol + " on " + chainType);
    }
}
