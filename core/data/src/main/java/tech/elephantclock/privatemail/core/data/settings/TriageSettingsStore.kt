package tech.elephantclock.privatemail.core.data.settings

import android.content.Context
import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.Serializer
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.dataStoreFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import tech.elephantclock.privatemail.core.model.AccountId
import java.io.InputStream
import java.io.OutputStream

/**
 * The persistent triage settings (T42/T43; replaces `InMemoryTriageSettings` in the app): one
 * JSON file in DataStore holding [AccountPrefs] per account. Reads are flows, writes are atomic
 * read-modify-write updates. A corrupt file is replaced with the defaults, which are the safe
 * ones (label only, nothing protected is lost because protection comes from the Sent folder).
 *
 * Exactly one instance per process ([get]): DataStore refuses two stores on one file.
 */
class TriageSettingsStore(
    private val data: DataStore<TriagePrefs>,
) {
    val all: Flow<TriagePrefs> = data.data

    fun account(id: AccountId): Flow<AccountPrefs> = data.data.map { it.of(id) }.distinctUntilChanged()

    suspend fun get(id: AccountId): AccountPrefs = data.data.first().of(id)

    suspend fun update(
        id: AccountId,
        transform: (AccountPrefs) -> AccountPrefs,
    ): AccountPrefs {
        var result = AccountPrefs()
        data.updateData { prefs ->
            result = transform(prefs.of(id))
            prefs.copy(accounts = prefs.accounts + (id.value to result))
        }
        return result
    }

    /** For an account that is removed. */
    suspend fun remove(id: AccountId) {
        data.updateData { it.copy(accounts = it.accounts - id.value) }
    }

    private fun TriagePrefs.of(id: AccountId) = accounts[id.value] ?: AccountPrefs()

    companion object {
        const val FILE_NAME = "triage_settings.json"

        @Volatile private var instance: TriageSettingsStore? = null

        /** The process-wide store in the app's files directory (excluded from backups like everything else). */
        fun get(context: Context): TriageSettingsStore =
            instance ?: synchronized(this) {
                instance ?: TriageSettingsStore(
                    create(CoroutineScope(Dispatchers.IO + SupervisorJob())) {
                        context.applicationContext.dataStoreFile(FILE_NAME)
                    },
                ).also { instance = it }
            }

        /** A DataStore over [file]; tests pass a temporary file and their own scope. */
        fun create(
            scope: CoroutineScope,
            file: () -> java.io.File,
        ): DataStore<TriagePrefs> =
            DataStoreFactory.create(
                serializer = PrefsSerializer,
                corruptionHandler = ReplaceFileCorruptionHandler { TriagePrefs() },
                scope = scope,
                produceFile = file,
            )
    }
}

internal object PrefsSerializer : Serializer<TriagePrefs> {
    private val json =
        Json {
            ignoreUnknownKeys = true
            // Unknown enum values (a newer version wrote them) fall back to the field's default.
            coerceInputValues = true
        }

    override val defaultValue = TriagePrefs()

    override suspend fun readFrom(input: InputStream): TriagePrefs =
        try {
            json.decodeFromString(TriagePrefs.serializer(), input.readBytes().decodeToString())
        } catch (e: SerializationException) {
            throw CorruptionException("triage settings are unreadable", e)
        } catch (e: IllegalArgumentException) {
            throw CorruptionException("triage settings are invalid", e)
        }

    override suspend fun writeTo(
        t: TriagePrefs,
        output: OutputStream,
    ) {
        output.write(json.encodeToString(TriagePrefs.serializer(), t).encodeToByteArray())
    }
}
