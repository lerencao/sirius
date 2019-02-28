package org.starcoin.sirius.protocol.ethereum

import kotlinx.coroutines.runBlocking
import org.ethereum.core.CallTransaction
import org.ethereum.solidity.compiler.CompilationResult
import org.starcoin.sirius.core.Address
import org.starcoin.sirius.core.Hash
import org.starcoin.sirius.core.Receipt
import org.starcoin.sirius.crypto.CryptoKey
import org.starcoin.sirius.crypto.CryptoService
import org.starcoin.sirius.lang.hexToByteArray
import org.starcoin.sirius.protocol.Chain
import org.starcoin.sirius.protocol.ContractConstructArgs
import org.starcoin.sirius.protocol.EthereumTransaction
import org.starcoin.sirius.protocol.TxDeferred
import org.starcoin.sirius.protocol.ethereum.contract.EthereumHubContract
import org.starcoin.sirius.util.WithLogging
import org.web3j.crypto.WalletUtils
import java.io.File
import java.math.BigInteger
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong


abstract class EthereumBaseChain :
    Chain<EthereumTransaction, EthereumBlock, EthereumAccount> {

    companion object : WithLogging() {

        //TODO
        val defaultGasPrice: BigInteger
            get() = 1.toBigInteger()

        val defaultGasLimit: BigInteger
            get() = 7500000.toBigInteger()
    }

    protected val txDeferreds = ConcurrentHashMap<Hash, TxDeferred>()

    protected fun registerDeferred(txHash: Hash): TxDeferred {
        val deferred = TxDeferred(txHash)
        this.txDeferreds.put(txHash, deferred)
        return deferred
    }

    protected fun completeDeferred(receipt: Receipt): TxDeferred? {
        val deferred = this.txDeferreds[receipt.transactionHash]
        deferred?.complete(receipt)?.run { txDeferreds.remove(receipt.transactionHash) }
        return deferred
    }

    abstract fun callConstFunction(caller: CryptoKey, contractAddress: Address, data: ByteArray): ByteArray

    override fun loadContract(contractAddress: Address) = this.loadContract(contractAddress, loadContractMetadata().abi)

    override fun loadContract(address: Address, jsonInterface: String): EthereumHubContract {
        return EthereumHubContract(address, jsonInterface, this)
    }

    override fun deployContract(account: EthereumAccount, args: ContractConstructArgs): EthereumHubContract {
        val challengeContract = loadContractMetadata("solidity/ChallengeService")
        val challengeContractAddress = submitNewContract(account, challengeContract)
        LOG.info("Deploy ChallengeService Contract success, address: $challengeContractAddress")
        val contractMetadata = loadContractMetadata("solidity/SiriusService")
        val address = submitNewContract(account, contractMetadata, challengeContractAddress.toString(), args.toRLP())
        LOG.info("Deploy SiriusService Contract success, address: $address")
        return this.loadContract(address, contractMetadata.abi)
    }

    private fun submitNewContract(
        account: EthereumAccount,
        contractMetaData: CompilationResult.ContractMetadata,
        vararg constructorArgs: Any
    ): Address {
        val contract = CallTransaction.Contract(contractMetaData.abi)
        val constructor: CallTransaction.Function? = contract.constructor
        if (constructor == null && constructorArgs.isNotEmpty()) {
            throw RuntimeException("No constructor with params found")
        }
        val argsEncoded = if (constructor == null) ByteArray(0) else constructor.encodeArguments(*constructorArgs)
        val tx = EthereumTransaction(
            account.getNonce(),
            defaultGasPrice,
            defaultGasLimit,
            contractMetaData.bin.hexToByteArray() + argsEncoded
        )
        //TODO use coroutine
        runBlocking {
            submitTransaction(account, tx).await()
        }
        return tx.contractAddress!!
    }

    override fun submitTransaction(account: EthereumAccount, transaction: EthereumTransaction): TxDeferred {
        val hash = this.doSubmitTransaction(account, transaction)
        LOG.fine("submitTransaction account:${account.address} tx:$hash")
        return hash
    }

    protected abstract fun doSubmitTransaction(account: EthereumAccount, transaction: EthereumTransaction): TxDeferred


    override fun createAccount(key: CryptoKey): EthereumAccount {
        return EthereumAccount(key)
    }

    override fun createAccount(): EthereumAccount {
        return EthereumAccount()
    }

    override fun createAccount(keystore: File, password: String): EthereumAccount {
        val credentials = WalletUtils.loadCredentials(
            password, keystore
        )
        val cryptoKey = CryptoService.loadCryptoKey(credentials.ecKeyPair.privateKey.toByteArray())
        return EthereumAccount(cryptoKey, AtomicLong(this.getNonce(cryptoKey.address).longValueExact()))
    }

    override fun tryMiningCoin(account: EthereumAccount, amount: BigInteger): Boolean {
        return false
    }

    override fun start() {}
    override fun stop() {}
}
