package com.phoneagent.safety

/**
 * Apps the agent must not act inside: banking, UPI, payments, password managers and
 * authenticators. Screenshots are usually blocked in these apps anyway; this list makes the
 * refusal explicit and explains it to the user.
 *
 * Entries are package names or package prefixes. Matching is by exact package, or by prefix
 * when the entry ends with `*`, so `com.bank.*` covers every flavour of one vendor's apps.
 */
class PackageDenylist(private val patterns: List<String>) {

    fun isDenied(packageName: String?): Boolean {
        if (packageName.isNullOrBlank()) return false
        return patterns.any { pattern -> matches(pattern, packageName) }
    }

    /** The entry that caused the denial, for the message shown to the user. */
    fun matchingEntry(packageName: String?): String? {
        if (packageName.isNullOrBlank()) return null
        return patterns.firstOrNull { matches(it, packageName) }
    }

    private fun matches(pattern: String, packageName: String): Boolean {
        val clean = pattern.trim()
        if (clean.isEmpty()) return false
        return when {
            clean.endsWith("*") -> packageName.startsWith(clean.dropLast(1))
            clean.contains("*") -> {
                val regex = Regex(
                    "^" + clean.split("*").joinToString(".*") { Regex.escape(it) } + "$",
                    RegexOption.IGNORE_CASE,
                )
                regex.matches(packageName)
            }
            else -> packageName.equals(clean, ignoreCase = true)
        }
    }

    companion object {
        /**
         * Default list. Deliberately built from well-known package names rather than guessed
         * ones; users can edit it in Settings. Anything not listed is allowed, and the
         * screenshot/secure-screen checks are the second line of defence.
         */
        val DEFAULT_PATTERNS = listOf(
            // Password managers and authenticators
            "com.lastpass.lpandroid",
            "com.agilebits.onepassword",
            "com.bitwarden.android",
            "com.x8bit.bitwarden",
            "com.dashlane",
            "com.keepersecurity.passwordmanager",
            "com.onepassword.android",
            "com.authy.authy",
            "com.google.android.apps.authenticator2",
            "com.microsoft.authenticator",
            "com.azure.authenticator",

            // Indian banking and UPI
            "com.sbi.*",
            "com.sbi.SBIFreedomPlus",
            "com.icicibank.*",
            "com.hdfcbank.*",
            "com.axis.mobile",
            "com.msf.kbank.mobile",
            "com.kotak.*",
            "com.pnb.*",
            "com.bankofbaroda.*",
            "com.csam.icici.bank.imobile",
            "net.one97.paytm",
            "com.phonepe.app",
            "com.google.android.apps.nbu.paisa.user",
            "in.org.npci.upiapp",
            "com.mobikwik_new",
            "com.freecharge.android",
            "com.bharatpe.app",
            "com.amazon.mShop.android.shopping.mpay",
            "com.dreamplug.androidapp",

            // International payments
            "com.paypal.android.p2pmobile",
            "com.venmo",
            "com.squareup.cash",
            "com.chase.sig.android",
            "com.wf.wellsfargomobile",
            "com.infonow.bofa",
            "com.citi.citimobile",
            "com.revolut.revolut",
            "com.monzo.android",

            // Crypto wallets
            "com.coinbase.android",
            "io.metamask",
            "com.binance.dev",
            "com.wazirx",
            "com.coinswitch.kuber",
        )
    }
}
