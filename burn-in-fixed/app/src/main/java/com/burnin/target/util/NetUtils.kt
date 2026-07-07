package com.burnin.target.util

import java.net.Inet4Address
import java.net.NetworkInterface

object NetUtils {
    /** Wi-Fi 등 로컬 네트워크의 사이트 로컬 IPv4 주소를 찾는다. 측정 기기에서 입력할 주소. */
    fun localIpv4(): String? {
        return try {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .firstOrNull { it.isSiteLocalAddress }
                ?.hostAddress
        } catch (e: Exception) {
            null
        }
    }
}
