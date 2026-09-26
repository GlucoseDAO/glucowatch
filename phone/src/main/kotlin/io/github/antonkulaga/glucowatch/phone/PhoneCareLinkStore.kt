package io.github.antonkulaga.glucowatch.phone

import android.content.Context
import glucowatch.core.CareLinkLogin
import glucowatch.core.CareLinkToken

/** Rotating tokens stay in private storage; neither BuildConfig nor a copied LinkAccount contains them. */
class PhoneCareLinkStore(context: Context) : CareLinkLogin {
    private val prefs = context.applicationContext.getSharedPreferences("carelink", Context.MODE_PRIVATE)

    override fun load(): CareLinkToken? = CareLinkToken.decode(prefs.getString("token", null))

    override fun replace(old: CareLinkToken, new: CareLinkToken) = synchronized(lock) {
        if (load()?.refreshToken == old.refreshToken) check(prefs.edit().putString("token", new.encode()).commit())
        Unit
    }

    fun save(token: CareLinkToken?) = synchronized(lock) {
        check(prefs.edit().putString("token", token?.encode()).commit())
    }

    fun take(): CareLinkToken? = synchronized(lock) {
        load()?.also { save(null) }
    }

    private companion object { val lock = Any() }
}
