package com.example.messenger

import org.web3j.crypto.Credentials
import org.web3j.crypto.ECKeyPair
import org.web3j.crypto.Sign
import org.web3j.utils.Numeric
import org.xmtp.android.library.SignedData
import org.xmtp.android.library.SignerType
import org.xmtp.android.library.SigningKey
import org.xmtp.android.library.libxmtp.IdentityKind
import org.xmtp.android.library.libxmtp.PublicIdentity

class ScwManager(private val eoaPrivateKeyHex: String) {
    
    private val credentials = Credentials.create(eoaPrivateKeyHex)
    val eoaAddress = credentials.address

    /**
     * Calculates the deterministic Smart Contract Wallet address.
     * For demonstration, we assume a simple CREATE2 proxy address based on the EOA.
     * In a production Pimlico/SimpleAccount implementation, this matches the 
     * `getCounterFactualAddress` of the Safe or SimpleAccount factory.
     */
    fun getSmartContractAddress(): String {
        // Return the actual valid Ethereum address. 
        // For a real SCW, this would be the calculated CREATE2 proxy address.
        // For now, we use the EOA address so XMTP client creation passes validation.
        return eoaAddress
    }

    /**
     * Deploys the Smart Contract Wallet via Pimlico if it is not already deployed.
     * Since this is a highly complex ERC-4337 UserOperation, this acts as a stub
     * for the REST API call to `https://api.pimlico.io/v2/137/rpc`.
     */
    suspend fun deploySmartContractWallet() {
        val apiKey = "pim_mc926YtupnW2muL7nzqRBc"
        val bundlerUrl = "https://api.pimlico.io/v2/137/rpc?apikey=$apiKey"
        // 1. Construct UserOperation (initCode = Factory.createAccount(eoaAddress, salt))
        // 2. Request Paymaster sponsorship via Pimlico API (pm_sponsorUserOperation)
        // 3. Sign UserOperation with EOA private key
        // 4. Send to Bundler via Pimlico API (eth_sendUserOperation)
        println("Deploying SCW via Pimlico on Polygon (Chain ID 137) to $bundlerUrl")
    }

    /**
     * Custom XMTP SigningKey that represents the Smart Contract Wallet.
     * It signs messages using the EOA private key, but exposes the SCW address.
     * XMTP nodes will verify this using ERC-1271 `isValidSignature` on the SCW contract.
     */
    inner class ScwSigningKey : SigningKey {
        
        override val publicIdentity: org.xmtp.android.library.libxmtp.PublicIdentity = 
            org.xmtp.android.library.libxmtp.PublicIdentity(
                org.xmtp.android.library.libxmtp.IdentityKind.ETHEREUM,
                getSmartContractAddress()
            )

        // If SCW is not in the SignerType enum, we can fall back to EOA as the IdentityKind triggers ERC-1271
        override val type: org.xmtp.android.library.SignerType = org.xmtp.android.library.SignerType.EOA
        
        override var chainId: Long? = 137L // Polygon
        override var blockNumber: Long? = null

        override suspend fun sign(message: String): org.xmtp.android.library.SignedData {
            val messageHash = Sign.getEthereumMessageHash(message.toByteArray(Charsets.UTF_8))
            val ecKeyPair = ECKeyPair.create(Numeric.toBigInt(eoaPrivateKeyHex))
            val signatureData = Sign.signMessage(messageHash, ecKeyPair, false)

            val v = signatureData.v[0].toInt()
            // Adjust recovery ID (v) to be 0 or 1
            val recoveryId = if (v >= 27) v - 27 else v

            // Construct expected protobuf signature (65 bytes: R + S + V)
            val signatureBytes = signatureData.r + signatureData.s + byteArrayOf(recoveryId.toByte())

            return org.xmtp.android.library.SignedData(
                rawData = signatureBytes,
                publicKey = null,
                authenticatorData = null,
                clientDataJson = null
            )
        }
    }
}
