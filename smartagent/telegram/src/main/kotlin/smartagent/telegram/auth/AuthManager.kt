package smartagent.telegram.auth

import java.util.concurrent.ConcurrentHashMap

class AuthManager(private val authKey: String) {

    private val authorized = ConcurrentHashMap.newKeySet<Long>()
    private val pendingAuth = ConcurrentHashMap.newKeySet<Long>()

    fun isAuthorized(chatId: Long): Boolean = authorized.contains(chatId)

    fun isPendingAuth(chatId: Long): Boolean = pendingAuth.contains(chatId)

    fun requestAuth(chatId: Long) { pendingAuth.add(chatId) }

    fun tryAuthorize(chatId: Long, key: String): Boolean {
        pendingAuth.remove(chatId)
        return if (key.trim() == authKey) {
            authorized.add(chatId)
            true
        } else {
            false
        }
    }
}
