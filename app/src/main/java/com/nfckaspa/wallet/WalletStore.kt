package com.nfckaspa.wallet

import android.content.Context

/**
 * Persists only non-secret wallet metadata.
 * The private key is never written here; signing still requires the NFC card
 * payload protected by Android Keystore and biometric authentication.
 */
internal object WalletStore {
    private const val PREFS = "kaspa_nfc_wallet"
    private const val KEY_ADDRESS = "tn10_wallet_address"

    fun loadAddress(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_ADDRESS, "")
            .orEmpty()

    fun saveAddress(context: Context, address: String) {
        require(address.isBlank() || address.startsWith("kaspatest:"))
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_ADDRESS, address)
            .apply()
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_ADDRESS)
            .apply()
    }
}
