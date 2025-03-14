/*
 * Copyright 2019 Web3 Labs Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */
package org.web3j.evm

import com.google.common.io.Resources
import org.apache.tuweni.bytes.Bytes
import org.apache.tuweni.units.bigints.UInt256
import org.hyperledger.besu.cli.config.EthNetworkConfig
import org.hyperledger.besu.cli.config.NetworkName
import org.hyperledger.besu.config.GenesisConfig
import org.hyperledger.besu.datatypes.Address
import org.hyperledger.besu.datatypes.Hash
import org.hyperledger.besu.datatypes.Wei
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.parameters.BlockParameter
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.parameters.WithdrawalParameter
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.results.BlockResult
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.results.TransactionCompleteResult
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.results.TransactionHashResult
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.results.TransactionReceiptLogResult
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.results.TransactionReceiptResult
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.results.TransactionReceiptRootResult
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.results.TransactionReceiptStatusResult
import org.hyperledger.besu.ethereum.api.query.TransactionReceiptWithMetadata
import org.hyperledger.besu.ethereum.chain.BadBlockManager
import org.hyperledger.besu.ethereum.core.MiningConfiguration
import org.hyperledger.besu.ethereum.core.PrivacyParameters
import org.hyperledger.besu.ethereum.core.Transaction
import org.hyperledger.besu.ethereum.mainnet.MainnetProtocolSchedule
import org.hyperledger.besu.ethereum.mainnet.TransactionReceiptType
import org.hyperledger.besu.ethereum.rlp.RLP
import org.hyperledger.besu.ethereum.transaction.CallParameter
import org.hyperledger.besu.evm.internal.EvmConfiguration
import org.hyperledger.besu.evm.tracing.OperationTracer
import org.hyperledger.besu.metrics.noop.NoOpMetricsSystem
import org.slf4j.LoggerFactory
import org.web3j.evm.utils.TestAccountsConstants
import org.web3j.protocol.core.methods.response.AccessListObject
import org.web3j.protocol.core.methods.response.EthBlock
import org.web3j.protocol.core.methods.response.EthBlock.Withdrawal
import org.web3j.utils.Numeric
import java.math.BigInteger
import java.nio.charset.StandardCharsets
import java.util.Optional
import org.web3j.abi.datatypes.Address as wAddress
import org.web3j.protocol.core.methods.request.Transaction as wTransaction
import org.web3j.protocol.core.methods.response.TransactionReceipt as wTransactionReceipt

/**
 * Embedded Web3j Ethereum blockchain.
 */
class EmbeddedEthereum(
    private val configuration: Configuration,
    operationTracer: OperationTracer,
) {
    private val chain = InMemoryBesuChain(configuration, operationTracer)
    private val blockchainQueries = chain.blockchainQueries

    /**
     * Process an unsigned Web3j transaction object.
     *
     * The transaction will be signed by the matching key pair
     * for the provided "from" address in the transaction if it is
     * defined in the test accounts, otherwise will be signed with
     * the first test account key pair.
     *
     * @param web3jTransaction The web3j transaction
     * @return the transaction hash
     * @see TestAccountsConstants.TEST_ACCOUNTS
     */
    fun processTransaction(web3jTransaction: wTransaction): String {
        val transaction = web3jTransaction.toSignedBesuTx()
        chain.processTransaction(transaction)
        return transaction.hash.toHexString()
    }

    /**
     * Process a signed RLP encoded transaction.
     *
     * @param signedTransactionData The singed RLP encoded transaction data
     * @return the transaction hash
     */
    fun processTransaction(signedTransactionData: String): String {
        val transaction = Transaction.readFrom(RLP.input(Bytes.fromHexString(signedTransactionData)))
        chain.processTransaction(transaction)
        return transaction.hash.toHexString()
    }

    /**
     * Maps unsigned Web3J to Besu signed transaction.
     */
    private fun wTransaction.toSignedBesuTx(): Transaction {
        with(this) {
            val chainId = chainId?.toBigInteger()
                ?: chain.chainId
            val nonce = if (nonce == null) {
                getTransactionCount(wAddress(from)).toLong()
            } else {
                hexToULong(nonce)
            }
            val from = (from ?: "0x0").lowercase()

            // TODO should we add support for account 0x0 fake signature?
            return Transaction.builder()
                .gasLimit(transactionGasLimitOrDefault(this))
                .gasPrice(Wei.of(hexToULong(gasPrice)))
                .nonce(nonce)
                .sender(Address.fromHexString(from))
                .to(Address.fromHexString(to))
                .payload(Bytes.fromHexString(data))
                .value(Wei.of(UInt256.fromHexString(value ?: "0x0")))
                .chainId(chainId)
                .guessType()
                .signAndBuild(
                    TestAccountsConstants.TEST_ACCOUNTS[from] ?: TestAccountsConstants.TEST_ACCOUNTS.values.first(),
                )
        }
    }

    fun getTransactionCount(w3jAddress: wAddress): BigInteger {
        val count = blockchainQueries.getTransactionCount(w3jAddress.asBesu())
        return BigInteger.valueOf(count)
    }

    fun getTransactionReceipt(transactionHashParam: String): wTransactionReceipt? {
        val hash = Hash.fromHexStringLenient(transactionHashParam)

        val genesisConfig = if (configuration.genesisFileUrl === null) {
            val networkConfig = EthNetworkConfig.getNetworkConfig(NetworkName.DEV)
            networkConfig.genesisConfig
        } else {
            GenesisConfig.fromConfig(
                @Suppress("UnstableApiUsage")
                Resources.toString(
                    configuration.genesisFileUrl,
                    StandardCharsets.UTF_8,
                ),
            )
        }

        val configOptions = genesisConfig.withOverrides(InMemoryBesuChain.DEFAULT_GENESIS_OVERRIDES).getConfigOptions()

        val protocolSchedule = MainnetProtocolSchedule.fromConfig(
            configOptions,
            Optional.of(PrivacyParameters.DEFAULT),
            Optional.of(true),
            Optional.of(EvmConfiguration.DEFAULT),
            MiningConfiguration.newDefault(),
            BadBlockManager(),
            false,
            NoOpMetricsSystem(),
        )

        val result = blockchainQueries
            .transactionReceiptByTransactionHash(hash, protocolSchedule)
            .map { receipt -> getResult(receipt) }
            .orElse(null)
            ?: return null

        val blockHash = result.blockHash
        val blockNumber = result.blockNumber
        val contractAddress = result.contractAddress
        val cumulativeGasUsed = result.cumulativeGasUsed
        val from = result.from
        val gasUsed = result.gasUsed
        val effectiveGasPrice = result.effectiveGasPrice
        val logs = result.logs.map(this::mapTransactionReceiptLog)
        val logsBloom = result.logsBloom
        val to = result.to
        val transactionHash = result.transactionHash
        val transactionIndex = result.transactionIndex
        val root = if (result is TransactionReceiptRootResult) result.root else null
        val status = if (result is TransactionReceiptStatusResult) result.status else null

        return wTransactionReceipt(
            transactionHash,
            transactionIndex,
            blockHash,
            blockNumber,
            cumulativeGasUsed,
            gasUsed,
            contractAddress,
            root,
            status,
            from,
            to,
            logs,
            logsBloom,
            "",
            "",
            effectiveGasPrice,

        )
    }

    private fun mapTransactionReceiptLog(result: TransactionReceiptLogResult): org.web3j.protocol.core.methods.response.Log {
        val removed = result.isRemoved
        val logIndex = result.logIndex
        val transactionIndex = result.transactionIndex
        val transactionHash = result.transactionHash
        val blockHash = result.blockHash
        val blockNumber = result.blockNumber
        val address = result.address
        val data = result.data
        val type: String? = null // TODO?
        val topics = result.topics

        return org.web3j.protocol.core.methods.response.Log(
            removed,
            logIndex,
            transactionIndex,
            transactionHash,
            blockHash,
            blockNumber,
            address,
            data,
            type,
            topics,
        )
    }

    private fun getResult(receipt: TransactionReceiptWithMetadata): TransactionReceiptResult {
        return if (receipt.receipt.transactionReceiptType == TransactionReceiptType.ROOT) {
            TransactionReceiptRootResult(receipt)
        } else {
            TransactionReceiptStatusResult(receipt)
        }
    }

    fun ethCall(
        web3jTransaction: wTransaction,
    ): String {
        val result = chain.call(web3jTransaction.asCallParameter())
        // TODO handle errors/**/
        return result.get().result.output.toHexString()
    }

    fun estimateGas(
        web3jTransaction: wTransaction,
    ): String {
        val result = chain.call(web3jTransaction.asCallParameter())
        return longToHex(result.get().result.estimateGasUsedByTransaction)
    }

    fun ethBlockNumber(): String {
        return longToHex(blockchainQueries.headBlockNumber())
    }

    fun ethGetBalance(
        w3jAddress: org.web3j.abi.datatypes.Address,
        @Suppress("UNUSED_PARAMETER") defaultBlockParameter: String,
    ): String? {
        return blockchainQueries
            .accountBalance(Address.fromHexString(w3jAddress.value), blockchainQueries.headBlockNumber())
            .map(Wei::toHexString)
            .orElse(null)
    }

    fun ethBlockByHash(hash: String, fullTransactionObjects: Boolean): EthBlock.Block? {
        val maybeBlockResult = chain.ethBlockByHash(Hash.fromHexString(hash), fullTransactionObjects)
        return maybeBlockResult.map { br -> ethBlockByBlockResult(br, fullTransactionObjects) }.orElse(null)
    }

    fun ethBlockByNumber(blockNumber: String, fullTransactionObjects: Boolean): EthBlock.Block? {
        val maybeBlockResult = chain.ethBlockByNumber(hexToULong(blockNumber), fullTransactionObjects)
        return maybeBlockResult.map { br -> ethBlockByBlockResult(br, fullTransactionObjects) }.orElse(null)
    }

    private fun ethBlockFullTransactionObject(blockResult: BlockResult): List<EthBlock.TransactionObject> {
        return blockResult.transactions.map { it ->
            val tcr = it as TransactionCompleteResult
            EthBlock.TransactionObject(
                tcr.hash,
                tcr.nonce,
                tcr.blockHash,
                tcr.blockNumber,
                tcr.chainId,
                tcr.transactionIndex,
                tcr.from,
                tcr.to,
                tcr.value,
                tcr.gasPrice,
                tcr.gas,
                tcr.input,
                null, // TODO?
                null, // TODO?
                null, // TODO?
                tcr.r,
                tcr.s,
                hexToULong(tcr.v),
                tcr.yParity,
                tcr.type,
                tcr.maxFeePerGas,
                tcr.maxPriorityFeePerGas,
                tcr.accessList?.map {
                    AccessListObject(
                        it.address.toString(),
                        mutableListOf(it.storageKeys.toString()),
                    )
                },
            )
        }
    }

    private fun ethBlockTransactionHash(blockResult: BlockResult): List<EthBlock.TransactionHash> {
        return blockResult.transactions.map {
            val thr = it as TransactionHashResult
            EthBlock.TransactionHash(
                thr.hash,
            )
        }
    }

    private fun ethBlockByBlockResult(blockResult: BlockResult, fullTransactionObjects: Boolean): EthBlock.Block? {
        val transactionResults = if (fullTransactionObjects) {
            ethBlockFullTransactionObject(blockResult)
        } else {
            ethBlockTransactionHash(blockResult)
        }

        return EthBlock.Block(
            blockResult.number,
            blockResult.hash,
            blockResult.parentHash,
            blockResult.nonce,
            blockResult.sha3Uncles,
            blockResult.logsBloom,
            blockResult.transactionsRoot,
            blockResult.stateRoot,
            blockResult.receiptsRoot,
            null, // TODO?
            blockResult.miner,
            null, // TODO?
            blockResult.difficulty,
            blockResult.totalDifficulty,
            blockResult.extraData,
            blockResult.size,
            blockResult.gasLimit,
            blockResult.gasUsed,
            blockResult.timestamp,
            transactionResults,
            null, // TODO?
            null, // TODO?
            blockResult.baseFeePerGas,
            blockResult.withdrawalsRoot,
            blockResult.withdrawals?.map { withdrawalParameter ->
                toWithdrawal(withdrawalParameter)
            }?.toList(),
        )
    }

    private fun toWithdrawal(withdrawalParameter: WithdrawalParameter): Withdrawal {
        val withdrawal = withdrawalParameter.toWithdrawal()
        return Withdrawal(withdrawal.index.toString(), withdrawal.validatorIndex.toString(), withdrawal.address.toHexString(), withdrawal.amount.toHexString())
    }

    fun ethGetCode(w3jAddress: wAddress, defaultBlockParameter: String): String {
        val blockParameter = BlockParameter(defaultBlockParameter)
        val blockNumber: Optional<Long> = blockParameter.number
        val address = w3jAddress.asBesu()

        return when {
            blockNumber.isPresent -> {
                blockchainQueries.getCode(address, blockNumber.get())?.get()?.toString() ?: "0x"
            }
            blockParameter.isLatest -> {
                blockchainQueries.getCode(address, blockchainQueries.headBlockNumber())?.get()?.toString() ?: "0x"
            }
            blockParameter.isEarliest -> {
                blockchainQueries.getCode(address, 0)?.get()?.toString() ?: "0x"
            }
            else -> {
                // If block parameter is pending but there is no pending state so take latest
                blockchainQueries.getCode(address, blockchainQueries.headBlockNumber())?.get()?.toString() ?: "0x"
            }
        }
    }

    fun ethGetBlockTransactionCountByHash(hash: Hash): String {
        return Numeric.encodeQuantity(BigInteger.valueOf(blockchainQueries.getTransactionCount(hash).toLong()))
    }

    fun ethGetBlockTransactionCountByNumber(blockNumber: Long): String {
        return Numeric.encodeQuantity(
            BigInteger.valueOf(
                blockchainQueries.getTransactionCount(blockNumber).get().toLong(),
            ),
        )
    }

    companion object {
        private val LOG = LoggerFactory.getLogger(EmbeddedEthereum::class.java)

        private fun hexToULong(hex: String): Long {
            return UInt256.fromHexString(hex).toLong()
        }

        private fun longToHex(value: Long): String {
            return UInt256.valueOf(value).toShortHexString()
        }

        private fun transactionGasLimitOrDefault(web3jTransaction: org.web3j.protocol.core.methods.request.Transaction): Long {
            // TODO: Verify happy with default value..
            return if (web3jTransaction.gas == null) 10_000_000L else hexToULong(web3jTransaction.gas)
        }
    }
}

private fun wTransaction.asCallParameter(): CallParameter {
    with(this) {
        return CallParameter(
            Address.fromHexString(from),
            Address.fromHexString(to),
            Long.MAX_VALUE,
            Wei.ZERO,
            Wei.ZERO,
            Bytes.fromHexString(data),
        )
    }
}

private fun wAddress.asBesu(): Address {
    return Address.fromHexString(this.value)
}
