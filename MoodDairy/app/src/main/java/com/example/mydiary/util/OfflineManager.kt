package com.example.mydiary.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * 离线模式管理器
 * 监控网络状态并提供离线缓存支持
 * 
 * 需求：7.3
 */
class OfflineManager(private val context: Context) {
    
    private val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    
    /**
     * 检查当前是否有网络连接
     * 
     * @return true 如果有网络连接
     */
    fun isNetworkAvailable(): Boolean {
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }
    
    /**
     * 监听网络状态变化
     * 
     * @return Flow<Boolean> 网络状态流（true = 有网络，false = 无网络）
     */
    fun observeNetworkStatus(): Flow<Boolean> = callbackFlow {
        val networkCallback = object : ConnectivityManager.NetworkCallback() {
            private val networks = mutableSetOf<Network>()
            
            override fun onAvailable(network: Network) {
                networks.add(network)
                trySend(true)
            }
            
            override fun onLost(network: Network) {
                networks.remove(network)
                trySend(networks.isNotEmpty())
            }
            
            override fun onCapabilitiesChanged(
                network: Network,
                networkCapabilities: NetworkCapabilities
            ) {
                val hasInternet = networkCapabilities.hasCapability(
                    NetworkCapabilities.NET_CAPABILITY_INTERNET
                ) && networkCapabilities.hasCapability(
                    NetworkCapabilities.NET_CAPABILITY_VALIDATED
                )
                
                if (hasInternet) {
                    networks.add(network)
                } else {
                    networks.remove(network)
                }
                
                trySend(networks.isNotEmpty())
            }
        }
        
        val networkRequest = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        
        connectivityManager.registerNetworkCallback(networkRequest, networkCallback)
        
        // 发送初始状态
        trySend(isNetworkAvailable())
        
        awaitClose {
            connectivityManager.unregisterNetworkCallback(networkCallback)
        }
    }.distinctUntilChanged()
    
    /**
     * 获取网络类型
     * 
     * @return 网络类型描述
     */
    fun getNetworkType(): NetworkType {
        val network = connectivityManager.activeNetwork ?: return NetworkType.NONE
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return NetworkType.NONE
        
        return when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkType.WIFI
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkType.CELLULAR
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetworkType.ETHERNET
            else -> NetworkType.OTHER
        }
    }
    
    /**
     * 检查是否应该使用离线模式
     * 
     * @return true 如果应该使用离线模式
     */
    fun shouldUseOfflineMode(): Boolean {
        return !isNetworkAvailable()
    }
}

/**
 * 网络类型
 */
enum class NetworkType {
    NONE,       // 无网络
    WIFI,       // WiFi
    CELLULAR,   // 移动网络
    ETHERNET,   // 以太网
    OTHER       // 其他
}

/**
 * 离线缓存策略
 */
enum class CacheStrategy {
    /**
     * 仅网络：只从网络获取数据，不使用缓存
     */
    NETWORK_ONLY,
    
    /**
     * 仅缓存：只从缓存获取数据，不访问网络
     */
    CACHE_ONLY,
    
    /**
     * 缓存优先：先从缓存获取，如果缓存不存在则从网络获取
     */
    CACHE_FIRST,
    
    /**
     * 网络优先：先从网络获取，如果网络失败则从缓存获取
     */
    NETWORK_FIRST,
    
    /**
     * 缓存然后网络：先返回缓存数据，然后从网络获取最新数据
     */
    CACHE_THEN_NETWORK
}
