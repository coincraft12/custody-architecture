package lab.custody.adapter;

import lab.custody.domain.withdrawal.ChainType;

import java.math.BigInteger;

/**
 * 17-2: Chain-agnostic send request passed to {@link ChainAdapter#prepareSend(SendRequest)}.
 *
 * <p>{@code amountRaw} is expressed in the smallest indivisible unit of the asset
 * (e.g. wei for ETH, satoshi for BTC, sun for TRX, lamports for SOL).
 */
public record SendRequest(
        ChainType chainType,
        String asset,
        String toAddress,
        BigInteger amountRaw,
        String fromAddress,
        String idempotencyKey
) {
}
