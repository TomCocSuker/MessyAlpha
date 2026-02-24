package com.example.messenger

import kotlinx.coroutines.suspendCancellableCoroutine
import org.xmtp.android.library.SignedData
import org.xmtp.android.library.SignerType
import org.xmtp.android.library.SigningKey
import org.xmtp.android.library.libxmtp.IdentityKind
import org.xmtp.android.library.libxmtp.PublicIdentity
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * A helper class to wrap WalletConnect V2 Modal responses into an XMTP SigningKey
 * @param connectedWalletAddress The EOA string returned by WalletConnect session approval
 * @param requestSignature A callback where you call WalletConnect's `request` method to ask the app (e.g. MetaMask) to sign Personal_Sign
 */
class WalletConnectSigner(
    private val connectedWalletAddress: String
) : SigningKey {

    override val publicIdentity: PublicIdentity = PublicIdentity(
        IdentityKind.ETHEREUM,
        connectedWalletAddress
    )

    override val type: SignerType = SignerType.EOA
    
    override var chainId: Long? = null
    override var blockNumber: Long? = null

    override suspend fun sign(message: String): SignedData {
        val signatureBytes = WalletConnectManager.signMessage(message)
        
        return SignedData(
            rawData = signatureBytes,
            publicKey = null,
            authenticatorData = null,
            clientDataJson = null
        )
    }
}
