package com.burnin.target

import android.content.Context
import com.burnin.target.net.Protocol

object DeviceRole {
    private const val PREFS = "app"
    private const val KEY_ROLE = "device_role"

    fun get(context: Context): String {
        val value = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_ROLE, Protocol.ROLE_ADJUSTMENT)
        return when (value) {
            Protocol.ROLE_REFERENCE -> Protocol.ROLE_REFERENCE
            else -> Protocol.ROLE_ADJUSTMENT
        }
    }

    fun set(context: Context, role: String) {
        val normalized = when (role) {
            Protocol.ROLE_REFERENCE -> Protocol.ROLE_REFERENCE
            else -> Protocol.ROLE_ADJUSTMENT
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_ROLE, normalized)
            .apply()
    }

    fun label(role: String): String =
        if (role == Protocol.ROLE_REFERENCE) "대조설비" else "조정설비"

    fun isReference(context: Context): Boolean =
        get(context) == Protocol.ROLE_REFERENCE
}
