package com.example.messenger

import org.xmtp.android.library.SignedData
import org.xmtp.android.library.SignerType
import org.xmtp.android.library.SigningKey
import org.xmtp.android.library.libxmtp.IdentityKind
import org.xmtp.android.library.libxmtp.PublicIdentity

/**
 * A wrapper class implementing XMTP's [SigningKey].
 * This allows you to integrate a real wallet (like WalletConnect, MetaMask, etc.)
 * into the XMTP initialization flow.
 *
 * @param walletAddress The Ethereum address of the user.
 * @param signCallback A suspending function that takes the XMTP signature message
 *                     and returns the raw signature bytes from your external wallet.
 */
class WalletSigner(
    private val walletAddress: String,
    private val signCallback: suspend (String) -> ByteArray
) : SigningKey {

    override val publicIdentity: PublicIdentity = PublicIdentity(
        IdentityKind.ETHEREUM,
        walletAddress
    )

    override val type: SignerType = SignerType.EOA
    
    // Used for Smart Contract Wallets only 
    override var chainId: Long? = null
    override var blockNumber: Long? = null

    override suspend fun sign(message: String): SignedData {
        // Call the user-provided signing logic (e.g., WalletConnect signPersonalMessage)
        val signatureBytes = signCallback(message)
        
        return SignedData(
            rawData = signatureBytes,
            publicKey = null,
            authenticatorData = null,
            clientDataJson = null
        )
    }
}

/**
 * A wrapper class implementing XMTP's [SigningKey] for Smart Contract Wallets (ERC-4337 / EIP-1271).
 */
class ScwWalletSigner(
    private val smartContractAddress: String,
    private val scwChainId: Long,
    private val scwBlockNumber: Long? = null,
    private val signCallback: suspend (String) -> ByteArray
) : SigningKey {

    override val publicIdentity: PublicIdentity = PublicIdentity(
        IdentityKind.ETHEREUM,
        smartContractAddress
    )

    override val type: SignerType = SignerType.SCW
    
    override var chainId: Long? = scwChainId
    override var blockNumber: Long? = scwBlockNumber

    override suspend fun sign(message: String): SignedData {
        // This callback should trigger an EIP-1271 compliant signature from the SCW.
        val signatureBytes = signCallback(message)
        
        return SignedData(
            rawData = signatureBytes,
            publicKey = null,
            authenticatorData = null,
            clientDataJson = null
        )
    }
}
