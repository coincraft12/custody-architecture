package lab.custody.adapter;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameterName;
import org.web3j.protocol.core.methods.response.EthGetBalance;
import org.web3j.protocol.core.methods.response.EthGetTransactionByHash;
import org.web3j.protocol.core.methods.response.EthGetTransactionReceipt;
import org.web3j.protocol.core.methods.response.TransactionReceipt;
import org.web3j.utils.Numeric;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.Optional;

@RestController
@RequiredArgsConstructor
@RequestMapping("/evm")
@ConditionalOnProperty(prefix = "custody.chain", name = "mode", havingValue = "rpc")
public class EvmWalletController {

    private static final BigDecimal WEI_IN_ETH = new BigDecimal("1000000000000000000");

    private final Web3j web3j;
    private final EvmRpcAdapter evmRpcAdapter;

    @Value("${custody.evm.rpc-url}")
    private String rpcUrl;

    @GetMapping("/wallet")
    public WalletResponse wallet() throws IOException {
        EthGetBalance balanceResponse = web3j.ethGetBalance(
                evmRpcAdapter.senderAddress(),
                DefaultBlockParameterName.LATEST
        ).send();

        if (balanceResponse.hasError()) {
            throw new IllegalStateException("Failed to fetch wallet balance from RPC: " + balanceResponse.getError().getMessage());
        }

        BigInteger balanceWei = balanceResponse.getBalance();
        return new WalletResponse(
                "rpc",
                evmRpcAdapter.chainId(),
                maskRpcUrl(rpcUrl),
                evmRpcAdapter.senderAddress(),
                balanceWei.toString(),
                toEthString(balanceWei)
        );
    }

    @GetMapping("/tx/{txHash}")
    public TxLookupResponse transaction(@PathVariable String txHash) throws IOException {
        EthGetTransactionByHash txResponse = web3j.ethGetTransactionByHash(txHash).send();
        if (txResponse.hasError()) {
            throw new IllegalStateException("Failed to lookup transaction by hash: " + txResponse.getError().getMessage());
        }

        EthGetTransactionReceipt receiptResponse = web3j.ethGetTransactionReceipt(txHash).send();
        if (receiptResponse.hasError()) {
            throw new IllegalStateException("Failed to fetch transaction receipt: " + receiptResponse.getError().getMessage());
        }

        Optional<TransactionReceipt> receiptOpt = receiptResponse.getTransactionReceipt();
        boolean seen = txResponse.getTransaction().isPresent() || receiptOpt.isPresent();

        return new TxLookupResponse(txHash, seen, receiptOpt.map(this::toReceipt).orElse(null));
    }

    @GetMapping("/tx/{txHash}/wait")
    @ResponseStatus(HttpStatus.OK)
    public TxWaitResponse waitForReceipt(
            @PathVariable String txHash,
            @RequestParam(defaultValue = "30000") long timeoutMs,
            @RequestParam(defaultValue = "1500") long pollMs
    ) throws IOException {
        long start = System.currentTimeMillis();
        while (System.currentTimeMillis() - start <= timeoutMs) {
            EthGetTransactionReceipt receiptResponse = web3j.ethGetTransactionReceipt(txHash).send();
            if (receiptResponse.hasError()) {
                throw new IllegalStateException("Failed to fetch transaction receipt: " + receiptResponse.getError().getMessage());
            }

            Optional<TransactionReceipt> receipt = receiptResponse.getTransactionReceipt();
            if (receipt.isPresent()) {
                return new TxWaitResponse(txHash, toReceipt(receipt.get()), false);
            }

            try {
                Thread.sleep(Math.max(pollMs, 100L));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while waiting for transaction receipt", e);
            }
        }

        return new TxWaitResponse(txHash, null, true);
    }

    private TxReceiptResponse toReceipt(TransactionReceipt receipt) {
        return new TxReceiptResponse(
                receipt.getStatus(),
                Numeric.toHexStringWithPrefixSafe(receipt.getBlockNumber()),
                Numeric.toHexStringWithPrefixSafe(receipt.getGasUsed())
        );
    }

    private static String toEthString(BigInteger balanceWei) {
        return new BigDecimal(balanceWei)
                .divide(WEI_IN_ETH, 18, RoundingMode.DOWN)
                .stripTrailingZeros()
                .toPlainString();
    }

    private static String maskRpcUrl(String url) {
        if (url == null || url.isBlank()) {
            return "";
        }
        return url.replaceAll("([?&](?:apikey|api_key|token|access_token)=)[^&]+", "$1***");
    }

    public record WalletResponse(
            String mode,
            long chainId,
            String rpc,
            String address,
            String balanceWei,
            String balanceEth
    ) {
    }

    public record TxLookupResponse(
            String txHash,
            boolean seen,
            TxReceiptResponse receipt
    ) {
    }

    public record TxWaitResponse(
            String txHash,
            TxReceiptResponse receipt,
            boolean timeout
    ) {
    }

    public record TxReceiptResponse(
            String status,
            String blockNumber,
            String gasUsed
    ) {
    }
}
