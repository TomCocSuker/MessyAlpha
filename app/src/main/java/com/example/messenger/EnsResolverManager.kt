package com.example.messenger

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.web3j.ens.EnsResolver
import org.web3j.protocol.Web3j
import org.web3j.protocol.http.HttpService

/**
 * Singleton object responsible for resolving Ethereum Name Service (ENS) addresses
 * to 0x addresses, and vice versa. It uses a public Cloudflare Ethereum node.
 */
object EnsResolverManager {
    private const val TAG = "EnsResolverManager"
    
    // Cloudflare public ETH gateway
    private const val RPC_URL = "https://cloudflare-eth.com"
    private var web3j: Web3j? = null
    private var ensResolver: EnsResolver? = null
    
    // In-memory cache to prevent spamming the RPC node when scrolling chat lists
    private val reverseCache = mutableMapOf<String, String?>()
    private val forwardCache = mutableMapOf<String, String>()

    @Synchronized
    private fun getResolver(): EnsResolver {
        if (ensResolver == null) {
            web3j = Web3j.build(HttpService(RPC_URL))
            ensResolver = EnsResolver(web3j, 30L) // 30s cache locally within Web3j
        }
        return ensResolver!!
    }

    /**
     * Resolves an ENS name (like vitalik.eth) to a 0x address.
     * Returns null if it cannot be resolved.
     */
    suspend fun resolveName(ensName: String): String? = withContext(Dispatchers.IO) {
        val cleanName = ensName.trim().lowercase()
        if (!cleanName.endsWith(".eth")) {
            return@withContext null // Not an ENS name format we handle
        }

        forwardCache[cleanName]?.let { return@withContext it }

        try {
            Log.d(TAG, "Forward resolving: $cleanName")
            val address = getResolver().resolve(cleanName)
            if (!address.isNullOrBlank()) {
                forwardCache[cleanName] = address
                // Also prepopulate the reverse cache
                reverseCache[address.lowercase()] = cleanName
                return@withContext address
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to resolve ENS name $cleanName: ${e.message}")
        }
        return@withContext null
    }

    /**
     * Resolves a 0x address to an ENS name.
     * Returns null if no ENS name is configured.
     */
    suspend fun resolveAddress(address: String): String? = withContext(Dispatchers.IO) {
        val cleanAddress = address.trim().lowercase()
        
        if (reverseCache.containsKey(cleanAddress)) {
            return@withContext reverseCache[cleanAddress]
        }

        try {
            Log.d(TAG, "Reverse resolving: $cleanAddress")
            val name = getResolver().reverseResolve(cleanAddress)
            if (!name.isNullOrBlank()) {
                reverseCache[cleanAddress] = name
                return@withContext name
            }
        } catch (e: Exception) {
            // It's very common to fail if reverse resolution isn't set up. Use debug log.
            Log.d(TAG, "No ENS name found for $cleanAddress: ${e.message}")
        }
        
        // Cache misses to avoid repeatedly trying to resolve wallets without ENS
        reverseCache[cleanAddress] = null
        return@withContext null
    }

    /**
     * Clear the cache.
     */
    fun clearCache() {
        reverseCache.clear()
        forwardCache.clear()
    }
}
