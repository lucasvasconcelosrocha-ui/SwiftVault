package com.swiftvault.backup.cloud.gdrive

import android.accounts.Account
import android.accounts.AccountManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import com.swiftvault.backup.engine.SecurityManager

/**
 * Enterprise-grade OAuth 2.0 Manager for official Google Drive integration.
 * Complies with Google OAuth 2.0 RFC 6749 and Android security standards.
 * Never requests or stores the user's Google password.
 */
class OAuthManager(private val context: Context) {

    private val prefs = context.getSharedPreferences("rodin_gdrive_auth", Context.MODE_PRIVATE)
    private val gson = Gson()

    companion object {
        const val DRIVE_FILE_SCOPE = "https://www.googleapis.com/auth/drive.file"
        const val DRIVE_METADATA_SCOPE = "https://www.googleapis.com/auth/drive.metadata.readonly"
        val REQUIRED_SCOPES = listOf(DRIVE_FILE_SCOPE, DRIVE_METADATA_SCOPE)

        private const val PREF_ACCESS_TOKEN = "access_token"
        private const val PREF_REFRESH_TOKEN = "refresh_token"
        private const val PREF_ENC_ACCESS_TOKEN = "enc_access_token"
        private const val PREF_ENC_REFRESH_TOKEN = "enc_refresh_token"
        private const val PREF_EXPIRY_TIME = "expiry_time"
        private const val PREF_ACCOUNT_EMAIL = "account_email"
        private const val PREF_ACCOUNT_NAME = "account_name"
    }

    init {
        migrateCredentialsToKeystore()
    }

    private fun migrateCredentialsToKeystore() {
        val plainAccess = prefs.getString(PREF_ACCESS_TOKEN, null)
        val plainRefresh = prefs.getString(PREF_REFRESH_TOKEN, null)
        if (!plainAccess.isNullOrBlank()) {
            try {
                val encAccess = SecurityManager.encryptHardwareString(plainAccess)
                val encRefresh = if (!plainRefresh.isNullOrBlank()) SecurityManager.encryptHardwareString(plainRefresh) else null
                prefs.edit()
                    .putString(PREF_ENC_ACCESS_TOKEN, encAccess)
                    .apply { if (encRefresh != null) putString(PREF_ENC_REFRESH_TOKEN, encRefresh) }
                    .remove(PREF_ACCESS_TOKEN)
                    .remove(PREF_REFRESH_TOKEN)
                    .apply()
            } catch (_: Exception) {}
        }
    }

    private fun getDecryptedAccessToken(): String? {
        val enc = prefs.getString(PREF_ENC_ACCESS_TOKEN, null)
        if (!enc.isNullOrBlank()) {
            return try {
                SecurityManager.decryptHardwareString(enc)
            } catch (_: Exception) {
                null
            }
        }
        val plain = prefs.getString(PREF_ACCESS_TOKEN, null)
        if (!plain.isNullOrBlank()) {
            migrateCredentialsToKeystore()
            return plain
        }
        return null
    }

    /**
     * Create official Android account chooser intent for Google accounts
     */
    fun createChooseAccountIntent(): Intent {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            AccountManager.newChooseAccountIntent(
                null,
                null,
                arrayOf("com.google"),
                null,
                null,
                null,
                null
            )
        } else {
            @Suppress("DEPRECATION")
            AccountManager.newChooseAccountIntent(
                null,
                null,
                arrayOf("com.google"),
                false,
                null,
                null,
                null,
                null
            )
        }
    }

    /**
     * Authenticate and obtain OAuth 2.0 token using Android's native AccountManager
     */
    suspend fun authenticateWithAccount(account: Account, activity: android.app.Activity? = null): Result<String> = withContext(Dispatchers.IO) {
        try {
            val accountManager = AccountManager.get(context)
            val scopeString = "oauth2:" + REQUIRED_SCOPES.joinToString(" ")

            val future = accountManager.getAuthToken(account, scopeString, null, activity, null, null)
            val bundle: Bundle = future.result

            val errorMsg = bundle.getString(AccountManager.KEY_ERROR_MESSAGE)
            if (!errorMsg.isNullOrBlank() && errorMsg.contains("UnregisteredOnApiConsole", ignoreCase = true)) {
                return@withContext Result.failure(
                    GoogleCloudOAuthConfigurationException(
                        "Cliente OAuth Android não registrado no Google Cloud Console (UnregisteredOnApiConsole). Pacote: com.swiftvault.backup, SHA-1: 9C:12:FF:40:4F:39:52:D7:0A:2F:C3:04:67:30:0E:DD:4B:FC:BE:2C."
                    )
                )
            }

            val authToken = bundle.getString(AccountManager.KEY_AUTHTOKEN)
            if (!authToken.isNullOrBlank()) {
                val expiryTime = System.currentTimeMillis() + 3600_000 // 1 hour typical validity
                saveCredentials(authToken, null, expiryTime, account.name, account.name)
                markSessionExpired(false)
                Result.success(authToken)
            } else {
                val intent = bundle.getParcelable<Intent>(AccountManager.KEY_INTENT)
                if (intent != null) {
                    // Pre-record selected email so callback knows the target account
                    prefs.edit()
                        .putString(PREF_ACCOUNT_EMAIL, account.name)
                        .putString(PREF_ACCOUNT_NAME, account.name)
                        .apply()
                    Result.failure(AuthIntentRequiredException(intent))
                } else {
                    val rawErr = bundle.getString(AccountManager.KEY_ERROR_MESSAGE) ?: "Não foi possível obter o token de autorização do Google."
                    if (rawErr.contains("UnregisteredOnApiConsole", ignoreCase = true)) {
                        Result.failure(
                            GoogleCloudOAuthConfigurationException(
                                "Cliente OAuth Android não registrado no Google Cloud Console (UnregisteredOnApiConsole)."
                            )
                        )
                    } else {
                        Result.failure(IllegalStateException(rawErr))
                    }
                }
            }
        } catch (e: Exception) {
            val msg = e.message ?: ""
            val causeMsg = e.cause?.message ?: ""
            if (msg.contains("UnregisteredOnApiConsole", ignoreCase = true) || causeMsg.contains("UnregisteredOnApiConsole", ignoreCase = true)) {
                Result.failure(
                    GoogleCloudOAuthConfigurationException(
                        "Cliente OAuth Android não registrado no Google Cloud Console (UnregisteredOnApiConsole). Verifique o Client ID Android cadastrado no Google Cloud Console com o pacote 'com.swiftvault.backup' e a chave SHA-1 correspondente."
                    )
                )
            } else {
                Result.failure(e)
            }
        }
    }

    /**
     * Authenticate directly by Google Account email name
     */
    suspend fun authenticateWithAccountName(email: String, activity: android.app.Activity? = null): Result<String> {
        val account = Account(email.trim(), "com.google")
        return authenticateWithAccount(account, activity)
    }

    /**
     * Invalidate active cached token in both AccountManager and SharedPreferences
     */
    fun invalidateActiveToken() {
        val token = getDecryptedAccessToken()
        if (!token.isNullOrBlank()) {
            try {
                val accountManager = AccountManager.get(context)
                accountManager.invalidateAuthToken("com.google", token)
            } catch (_: Exception) {}
        }
        prefs.edit()
            .remove(PREF_ACCESS_TOKEN)
            .remove(PREF_ENC_ACCESS_TOKEN)
            .remove(PREF_EXPIRY_TIME)
            .putBoolean("session_expired", true)
            .apply()
    }

    private val refreshMutex = Mutex()

    /**
     * Get valid access token, auto-renewing if needed
     */
    suspend fun getValidAccessToken(): String? = withContext(Dispatchers.IO) {
        val token = getDecryptedAccessToken() ?: return@withContext null
        val expiry = prefs.getLong(PREF_EXPIRY_TIME, 0L)

        // Buffer 5 minutes before actual expiry
        if (isSessionExpired() || System.currentTimeMillis() > (expiry - 300_000)) {
            refreshMutex.withLock {
                val currentExpiry = prefs.getLong(PREF_EXPIRY_TIME, 0L)
                val currentToken = getDecryptedAccessToken()
                if (!isSessionExpired() && System.currentTimeMillis() <= (currentExpiry - 300_000) && !currentToken.isNullOrBlank()) {
                    return@withLock currentToken
                }
                val refreshed = forceRefreshTokenInternal()
                return@withLock refreshed.getOrNull()
            }
        } else {
            token
        }
    }

    /**
     * Force token refresh by invalidating cache in AccountManager and re-requesting from Google
     */
    suspend fun forceRefreshToken(): Result<String> = withContext(Dispatchers.IO) {
        refreshMutex.withLock {
            forceRefreshTokenInternal()
        }
    }

    private suspend fun forceRefreshTokenInternal(): Result<String> = withContext(Dispatchers.IO) {
        val email = prefs.getString(PREF_ACCOUNT_EMAIL, null)
            ?: return@withContext Result.failure(IllegalStateException("Conta desconectada."))

        try {
            val accountManager = AccountManager.get(context)
            val currentToken = prefs.getString(PREF_ACCESS_TOKEN, null)
            if (!currentToken.isNullOrBlank()) {
                accountManager.invalidateAuthToken("com.google", currentToken)
            }

            val account = Account(email.trim(), "com.google")
            val scopeString = "oauth2:" + REQUIRED_SCOPES.joinToString(" ")
            val future = accountManager.getAuthToken(account, scopeString, null, false, null, null)
            val bundle: Bundle = future.result

            val errorMsg = bundle.getString(AccountManager.KEY_ERROR_MESSAGE)
            if (!errorMsg.isNullOrBlank() && errorMsg.contains("UnregisteredOnApiConsole", ignoreCase = true)) {
                return@withContext Result.failure(
                    GoogleCloudOAuthConfigurationException(
                        "Cliente OAuth Android não registrado no Google Cloud Console (UnregisteredOnApiConsole)."
                    )
                )
            }

            val newToken = bundle.getString(AccountManager.KEY_AUTHTOKEN)
            if (!newToken.isNullOrBlank()) {
                val expiryTime = System.currentTimeMillis() + 3600_000
                saveCredentials(newToken, null, expiryTime, email, email)
                markSessionExpired(false)
                Result.success(newToken)
            } else {
                val intent = bundle.getParcelable<Intent>(AccountManager.KEY_INTENT)
                markSessionExpired(true)
                if (intent != null) {
                    Result.failure(AuthIntentRequiredException(intent))
                } else {
                    val rawErr = bundle.getString(AccountManager.KEY_ERROR_MESSAGE) ?: ""
                    if (rawErr.contains("UnregisteredOnApiConsole", ignoreCase = true)) {
                        Result.failure(
                            GoogleCloudOAuthConfigurationException(
                                "Cliente OAuth Android não registrado no Google Cloud Console (UnregisteredOnApiConsole)."
                            )
                        )
                    } else {
                        Result.failure(GoogleDriveSessionExpiredException("A sessão do Google Drive expirou. É necessário reconectar sua conta para continuar.", 401))
                    }
                }
            }
        } catch (e: Exception) {
            val msg = e.message ?: ""
            val causeMsg = e.cause?.message ?: ""
            if (msg.contains("UnregisteredOnApiConsole", ignoreCase = true) || causeMsg.contains("UnregisteredOnApiConsole", ignoreCase = true)) {
                Result.failure(
                    GoogleCloudOAuthConfigurationException(
                        "Cliente OAuth Android não registrado no Google Cloud Console (UnregisteredOnApiConsole). Pacote: com.swiftvault.backup, SHA-1: 9C:12:FF:40:4F:39:52:D7:0A:2F:C3:04:67:30:0E:DD:4B:FC:BE:2C."
                    )
                )
            } else {
                markSessionExpired(true)
                Result.failure(e)
            }
        }
    }

    /**
     * Check if token is expired or missing
     */
    fun isTokenExpired(): Boolean {
        val expiry = prefs.getLong(PREF_EXPIRY_TIME, 0L)
        return isSessionExpired() || System.currentTimeMillis() > (expiry - 60_000)
    }

    /**
     * Validate active token against Google's tokeninfo API
     */
    suspend fun validateActiveToken(): Result<Boolean> = withContext(Dispatchers.IO) {
        var token = getDecryptedAccessToken()
        if (token.isNullOrBlank()) {
            return@withContext Result.failure(IllegalStateException("Nenhum token configurado."))
        }

        // Auto-refresh before check if expired
        if (isTokenExpired()) {
            val renewed = forceRefreshToken()
            if (renewed.isSuccess) {
                token = renewed.getOrNull()
            }
        }

        if (token.isNullOrBlank()) {
            return@withContext Result.failure(IllegalStateException("Nenhum token válido disponível."))
        }

        try {
            val url = URL("https://www.googleapis.com/oauth2/v3/tokeninfo?access_token=$token")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 10000
            connection.readTimeout = 10000

            val responseCode = connection.responseCode
            if (responseCode == 200) {
                markSessionExpired(false)
                Result.success(true)
            } else {
                val errBody = try {
                    connection.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                } catch (e: Exception) { "" }

                // Invalidate invalid token so it cannot be used again
                invalidateActiveToken()

                val (friendlyMessage, technicalCode) = when (responseCode) {
                    400 -> {
                        if (errBody.contains("invalid_token") || errBody.contains("Invalid Value")) {
                            Pair("Token do Google Drive inválido ou revogado. Reconecte sua conta.", "HTTP 400 (invalid_token)")
                        } else {
                            Pair("Requisição de validação inválida da conta.", "HTTP 400")
                        }
                    }
                    401 -> Pair("Sessão do Google Drive não autorizada ou expirada. Reconecte sua conta.", "HTTP 401")
                    403 -> Pair("Acesso ao Google Drive restrito ou cota excedida.", "HTTP 403")
                    else -> Pair("Erro na validação do token com o Google.", "HTTP $responseCode")
                }
                Result.failure(GoogleDriveSessionExpiredException("$friendlyMessage ($technicalCode)", responseCode))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Refresh OAuth 2.0 Token using refresh token or AccountManager invalidation
     */
    suspend fun refreshAccessToken(): Result<String> = forceRefreshToken()

    /**
     * Disconnect account and revoke token with Google
     */
    suspend fun disconnect(): Result<Boolean> = withContext(Dispatchers.IO) {
        val token = getDecryptedAccessToken()
        if (!token.isNullOrBlank()) {
            try {
                val url = URL("https://oauth2.googleapis.com/revoke?token=$token")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.connectTimeout = 8000
                conn.responseCode
            } catch (e: Exception) {
                // Ignore network error on revocation
            }
        }
        clearCredentials()
        Result.success(true)
    }

    fun getConnectedAccountEmail(): String? = prefs.getString(PREF_ACCOUNT_EMAIL, null)

    fun isConnected(): Boolean {
        val token = getDecryptedAccessToken()
        val email = prefs.getString(PREF_ACCOUNT_EMAIL, null)
        return !token.isNullOrBlank() && !email.isNullOrBlank() && !isSessionExpired()
    }

    fun isSessionExpired(): Boolean = prefs.getBoolean("session_expired", false)

    fun markSessionExpired(expired: Boolean) {
        prefs.edit().putBoolean("session_expired", expired).apply()
    }

    fun getCachedRootFolderId(): String? = prefs.getString("cached_rodin_folder_id", null)

    fun setCachedRootFolderId(folderId: String?) {
        if (folderId != null) {
            prefs.edit().putString("cached_rodin_folder_id", folderId).apply()
        } else {
            prefs.edit().remove("cached_rodin_folder_id").apply()
        }
    }

    private fun saveCredentials(
        accessToken: String,
        refreshToken: String?,
        expiryTime: Long,
        email: String,
        name: String
    ) {
        val encAccess = SecurityManager.encryptHardwareString(accessToken)
        val encRefresh = if (!refreshToken.isNullOrBlank()) SecurityManager.encryptHardwareString(refreshToken) else null

        prefs.edit()
            .putString(PREF_ENC_ACCESS_TOKEN, encAccess)
            .apply { if (encRefresh != null) putString(PREF_ENC_REFRESH_TOKEN, encRefresh) }
            .remove(PREF_ACCESS_TOKEN)
            .remove(PREF_REFRESH_TOKEN)
            .putLong(PREF_EXPIRY_TIME, expiryTime)
            .putString(PREF_ACCOUNT_EMAIL, email)
            .putString(PREF_ACCOUNT_NAME, name)
            .putBoolean("session_expired", false)
            .apply()
    }

    private fun clearCredentials() {
        prefs.edit().clear().apply()
    }
}

class AuthIntentRequiredException(val intent: Intent) : Exception("Interação do usuário necessária para autenticação Google.")
class TokenExpiredOrRevokedException(msg: String) : Exception(msg)
class GoogleCloudOAuthConfigurationException(
    override val message: String = "Cliente OAuth não registrado no Google Cloud Console (UnregisteredOnApiConsole). Certifique-se de que o Client ID Android foi criado com o pacote 'com.swiftvault.backup' e a chave SHA-1 correspondente."
) : Exception(message)
class GoogleDriveSessionExpiredException(
    override val message: String = "A sessão do Google Drive expirou. É necessário reconectar sua conta para continuar.",
    val statusCode: Int = 401
) : Exception(message)
