package lab.custody.adapter.bitcoin;

import lab.custody.adapter.Signer;
import lab.custody.domain.withdrawal.ChainType;
import lombok.extern.slf4j.Slf4j;
import org.bitcoinj.core.Address;
import org.bitcoinj.core.Coin;
import org.bitcoinj.core.ECKey;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.core.Transaction;
import org.bitcoinj.core.TransactionInput;
import org.bitcoinj.core.TransactionOutPoint;
import org.bitcoinj.core.TransactionOutput;
import org.bitcoinj.core.Sha256Hash;
import org.bitcoinj.crypto.TransactionSignature;
import org.bitcoinj.params.MainNetParams;
import org.bitcoinj.params.RegTestParams;
import org.bitcoinj.params.TestNet3Params;
import org.bitcoinj.script.Script;
import org.bitcoinj.script.ScriptBuilder;
import org.bitcoinj.script.ScriptPattern;
import org.web3j.crypto.RawTransaction;

import java.util.List;

/**
 * 19-8: Bitcoin transaction signer using bitcoinj.
 *
 * <p>Implements the generic {@link Signer} interface; however, because Bitcoin uses
 * a UTXO model rather than a flat byte-array signing approach, the primary entry
 * point is {@link #signTransaction(String, List, BitcoinRpcClient.Utxo)} which
 * builds a P2WPKH (native SegWit) transaction.
 *
 * <p>The {@link #signRaw(byte[])} method throws {@link UnsupportedOperationException}
 * because it cannot carry the UTXO metadata required for witness-script construction.
 *
 * <p>The address returned by {@link #getAddress()} is a bech32 P2WPKH address
 * on the configured network.
 */
@Slf4j
public class BitcoinSigner implements Signer {

    private final ECKey ecKey;
    private final NetworkParameters networkParams;
    private final String address;

    /**
     * @param wifPrivateKey WIF-encoded private key (starts with 'K', 'L', or '5' for mainnet,
     *                      'c' for testnet/regtest)
     * @param network       "mainnet", "testnet", or "regtest"
     */
    public BitcoinSigner(String wifPrivateKey, String network) {
        this.networkParams = resolveNetwork(network);
        this.ecKey = ECKey.fromPrivate(org.bitcoinj.core.DumpedPrivateKey.fromBase58(networkParams, wifPrivateKey).getKey().getPrivKeyBytes());
        // P2WPKH bech32 address
        this.address = Address.fromKey(networkParams, ecKey, Script.ScriptType.P2WPKH).toString();
        log.info("event=bitcoin_signer.initialized address={} network={}", address, network);
    }

    // ──────────────────────────────────────────────────────────────────────
    // Signer interface
    // ──────────────────────────────────────────────────────────────────────

    /** Not supported for Bitcoin — use {@link #signTransaction} instead. */
    @Override
    public byte[] signRaw(byte[] txBytes) {
        throw new UnsupportedOperationException("Use signTransaction for Bitcoin");
    }

    /** Legacy EVM sign — not supported on Bitcoin. */
    @Override
    @Deprecated
    public String sign(RawTransaction tx, long chainId) {
        throw new UnsupportedOperationException("Bitcoin does not use EVM RawTransaction signing");
    }

    @Override
    public String getAddress() {
        return address;
    }

    @Override
    public ChainType getChainType() {
        return ChainType.BITCOIN;
    }

    // ──────────────────────────────────────────────────────────────────────
    // Bitcoin-specific signing
    // ──────────────────────────────────────────────────────────────────────

    /**
     * Builds, signs, and serializes a P2WPKH transaction.
     *
     * @param toAddress   recipient bech32 or legacy address
     * @param amountSat   amount to send in satoshis
     * @param feeSat      miner fee in satoshis
     * @param changeAddress change output address (null or same as fromAddress = no change)
     * @param changeSat   change amount in satoshis (0 = no change output)
     * @param inputs      UTXOs to use as inputs
     * @return signed transaction as a lowercase hex string
     */
    public String signTransaction(
            String toAddress,
            long amountSat,
            long feeSat,
            String changeAddress,
            long changeSat,
            List<BitcoinRpcClient.Utxo> inputs
    ) {
        Transaction tx = new Transaction(networkParams);

        // Add recipient output
        Address recipient = Address.fromString(networkParams, toAddress);
        tx.addOutput(Coin.valueOf(amountSat), recipient);

        // Add change output if above dust threshold
        if (changeSat > 0 && changeAddress != null) {
            Address change = Address.fromString(networkParams, changeAddress);
            tx.addOutput(Coin.valueOf(changeSat), change);
        }

        // Add inputs
        for (BitcoinRpcClient.Utxo utxo : inputs) {
            TransactionOutPoint outPoint = new TransactionOutPoint(
                    networkParams,
                    utxo.vout(),
                    Sha256Hash.wrap(utxo.txid())
            );
            TransactionInput input = new TransactionInput(networkParams, tx, new byte[0], outPoint);
            tx.addInput(input);
        }

        // Sign each input with P2WPKH witness
        Address fromAddress = Address.fromKey(networkParams, ecKey, Script.ScriptType.P2WPKH);
        Script p2wpkhScript = ScriptBuilder.createP2WPKHOutputScript(ecKey);

        for (int i = 0; i < inputs.size(); i++) {
            BitcoinRpcClient.Utxo utxo = inputs.get(i);
            Coin inputValue = Coin.valueOf(utxo.amountSat());

            // P2WPKH scriptCode is the P2PKH equivalent of the witness program
            Script scriptCode = ScriptBuilder.createP2PKHOutputScript(ecKey);
            Sha256Hash sigHash = tx.hashForWitnessSignature(
                    i,
                    scriptCode,
                    inputValue,
                    Transaction.SigHash.ALL,
                    false
            );

            ECKey.ECDSASignature sig = ecKey.sign(sigHash);
            TransactionSignature txSig = new TransactionSignature(sig, Transaction.SigHash.ALL, false);

            tx.getInput(i).setScriptSig(ScriptBuilder.createEmpty());
            tx.getInput(i).setWitness(
                    org.bitcoinj.core.TransactionWitness.redeemP2WPKH(txSig, ecKey)
            );
        }

        return org.bitcoinj.core.Utils.HEX.encode(tx.bitcoinSerialize());
    }

    // ──────────────────────────────────────────────────────────────────────
    // Helpers
    // ──────────────────────────────────────────────────────────────────────

    private static NetworkParameters resolveNetwork(String network) {
        return switch (network.toLowerCase()) {
            case "mainnet" -> MainNetParams.get();
            case "testnet" -> TestNet3Params.get();
            default -> RegTestParams.get(); // regtest is the default
        };
    }
}
