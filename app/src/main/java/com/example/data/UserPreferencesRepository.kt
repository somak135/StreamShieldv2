package com.example.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "streamshield_prefs")

data class ShieldPreferencesState(
    val darkMode: Boolean = true,
    val amoledBlackMode: Boolean = false,
    val adBlockEnabled: Boolean = true,
    val sponsorSkipEnabled: Boolean = true,
    val backgroundPlayEnabled: Boolean = true,
    val autoPipOnLeave: Boolean = true,
    val playbackSpeed: Float = 1.0f,
    val rememberSpeedAcrossVideos: Boolean = true,
    /** When on, nothing is added to the watch history. */
    val historyPaused: Boolean = false,
    /** Move on to the next video / song when one ends (also in the background and in PiP). */
    val autoplayEnabled: Boolean = true,
    /** Daily automatic backup into a folder the user picked (a persisted document-tree URI). */
    val autoBackupEnabled: Boolean = false,
    val autoBackupFolder: String = "",
    val lastAutoBackupAt: Long = 0L,
    /** Diagnostics: a short note with the start time each time a video starts. */
    val showStartTime: Boolean = true,
    /** Smart downloads (music only) size in MB; 0 = off. */
    val smartSizeMb: Int = 1024,
    /** Remembered download quality: video height, 0 = audio only, -1 = ask each time. */
    val downloadVideoQuality: Int = -1,
    val downloadMusicQuality: Int = -1,
    val lastSmartRefreshAt: Long = 0L,
    val smartSummary: String = "",
    /** Starred subscriptions (channel ids): an extra boost in the Home tab's "For you". */
    val starredChannels: Set<String> = emptySet(),
    /** Ads removed from YouTube responses or skipped in the player. */
    val adsBlockedCount: Long = 0L,
    /** Ad / tracker network requests refused. */
    val requestsBlockedCount: Long = 0L
)

class UserPreferencesRepository(private val context: Context) {

    private object Keys {
        val DARK_MODE = booleanPreferencesKey("dark_mode")
        val AMOLED_BLACK = booleanPreferencesKey("amoled_black")
        val ADBLOCK_ENABLED = booleanPreferencesKey("adblock_enabled")
        val SPONSOR_SKIP = booleanPreferencesKey("sponsor_skip")
        val BACKGROUND_PLAY = booleanPreferencesKey("background_play")
        val AUTO_PIP = booleanPreferencesKey("auto_pip")
        val PLAYBACK_SPEED = floatPreferencesKey("playback_speed")
        val REMEMBER_SPEED = booleanPreferencesKey("remember_speed")
        val HISTORY_PAUSED = booleanPreferencesKey("history_paused")
        val AUTOPLAY = booleanPreferencesKey("autoplay")
        /** The original app's "Autoplay next video" (PiP/background only); used as the default of [AUTOPLAY] until that is set. */
        val LEGACY_AUTO_ADVANCE = booleanPreferencesKey("auto_advance")
        val AUTO_BACKUP = booleanPreferencesKey("auto_backup")
        val AUTO_BACKUP_FOLDER = stringPreferencesKey("auto_backup_folder")
        val LAST_AUTO_BACKUP = longPreferencesKey("last_auto_backup")
        val SHOW_START_TIME = booleanPreferencesKey("show_start_time")
        val SMART_SIZE_MB = intPreferencesKey("smart_size_mb")
        val DOWNLOAD_VIDEO_QUALITY = intPreferencesKey("download_video_quality")
        val DOWNLOAD_MUSIC_QUALITY = intPreferencesKey("download_music_quality")
        val LAST_SMART_REFRESH = longPreferencesKey("last_smart_refresh")
        val SMART_SUMMARY = stringPreferencesKey("smart_summary")
        val STARRED_CHANNELS = stringSetPreferencesKey("starred_channels")
        // v2 keys: the v1 counters were seeded with made-up numbers, so they are not carried over.
        val ADS_BLOCKED = longPreferencesKey("ads_blocked_count_v2")
        val REQUESTS_BLOCKED = longPreferencesKey("requests_blocked_count_v2")
    }

    val preferencesFlow: Flow<ShieldPreferencesState> = context.dataStore.data.map { prefs ->
        ShieldPreferencesState(
            darkMode = prefs[Keys.DARK_MODE] ?: true,
            amoledBlackMode = prefs[Keys.AMOLED_BLACK] ?: false,
            adBlockEnabled = prefs[Keys.ADBLOCK_ENABLED] ?: true,
            sponsorSkipEnabled = prefs[Keys.SPONSOR_SKIP] ?: true,
            backgroundPlayEnabled = prefs[Keys.BACKGROUND_PLAY] ?: true,
            autoPipOnLeave = prefs[Keys.AUTO_PIP] ?: true,
            playbackSpeed = (prefs[Keys.PLAYBACK_SPEED] ?: 1.0f).coerceIn(0.25f, 3.0f),
            rememberSpeedAcrossVideos = prefs[Keys.REMEMBER_SPEED] ?: true,
            historyPaused = prefs[Keys.HISTORY_PAUSED] ?: false,
            autoplayEnabled = prefs[Keys.AUTOPLAY] ?: prefs[Keys.LEGACY_AUTO_ADVANCE] ?: true,
            autoBackupEnabled = prefs[Keys.AUTO_BACKUP] ?: false,
            autoBackupFolder = prefs[Keys.AUTO_BACKUP_FOLDER] ?: "",
            lastAutoBackupAt = prefs[Keys.LAST_AUTO_BACKUP] ?: 0L,
            showStartTime = prefs[Keys.SHOW_START_TIME] ?: true,
            smartSizeMb = prefs[Keys.SMART_SIZE_MB] ?: 1024,
            downloadVideoQuality = prefs[Keys.DOWNLOAD_VIDEO_QUALITY] ?: -1,
            downloadMusicQuality = prefs[Keys.DOWNLOAD_MUSIC_QUALITY] ?: -1,
            lastSmartRefreshAt = prefs[Keys.LAST_SMART_REFRESH] ?: 0L,
            smartSummary = prefs[Keys.SMART_SUMMARY] ?: "",
            starredChannels = prefs[Keys.STARRED_CHANNELS] ?: emptySet(),
            adsBlockedCount = prefs[Keys.ADS_BLOCKED] ?: 0L,
            requestsBlockedCount = prefs[Keys.REQUESTS_BLOCKED] ?: 0L
        )
    }

    suspend fun setDarkMode(enabled: Boolean) {
        context.dataStore.edit { it[Keys.DARK_MODE] = enabled }
    }

    suspend fun setAmoledBlackMode(enabled: Boolean) {
        context.dataStore.edit { it[Keys.AMOLED_BLACK] = enabled }
    }

    suspend fun setAdBlockEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.ADBLOCK_ENABLED] = enabled }
    }

    suspend fun setSponsorSkipEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.SPONSOR_SKIP] = enabled }
    }

    suspend fun setBackgroundPlayEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.BACKGROUND_PLAY] = enabled }
    }

    suspend fun setAutoPipOnLeave(enabled: Boolean) {
        context.dataStore.edit { it[Keys.AUTO_PIP] = enabled }
    }

    suspend fun setPlaybackSpeed(speed: Float) {
        // Snap to 0.05 steps; round (not truncate) so 1.15f does not become 1.10.
        val clamped = ((speed * 20f).roundToInt() / 20f).coerceIn(0.25f, 3.0f)
        context.dataStore.edit { it[Keys.PLAYBACK_SPEED] = clamped }
    }

    suspend fun setRememberSpeed(enabled: Boolean) {
        context.dataStore.edit { it[Keys.REMEMBER_SPEED] = enabled }
    }

    suspend fun setAutoBackup(enabled: Boolean, folder: String?) {
        context.dataStore.edit { prefs ->
            prefs[Keys.AUTO_BACKUP] = enabled
            if (folder != null) prefs[Keys.AUTO_BACKUP_FOLDER] = folder
        }
    }

    suspend fun setLastAutoBackup(at: Long) {
        context.dataStore.edit { it[Keys.LAST_AUTO_BACKUP] = at }
    }

    /**
     * User-facing settings for a backup file. Device-specific values (block counters, the backup
     * folder permission) are not included.
     */
    fun toBackupJson(p: ShieldPreferencesState): JSONObject = JSONObject()
        .put("darkMode", p.darkMode)
        .put("amoledBlackMode", p.amoledBlackMode)
        .put("adBlockEnabled", p.adBlockEnabled)
        .put("sponsorSkipEnabled", p.sponsorSkipEnabled)
        .put("backgroundPlayEnabled", p.backgroundPlayEnabled)
        .put("autoPipOnLeave", p.autoPipOnLeave)
        .put("playbackSpeed", p.playbackSpeed.toDouble())
        .put("rememberSpeedAcrossVideos", p.rememberSpeedAcrossVideos)
        .put("historyPaused", p.historyPaused)
        .put("autoplayEnabled", p.autoplayEnabled)
        .put("showStartTime", p.showStartTime)
        .put("smartSizeMb", p.smartSizeMb)
        .put("downloadVideoQuality", p.downloadVideoQuality)
        .put("downloadMusicQuality", p.downloadMusicQuality)
        .put("starredChannels", JSONArray(p.starredChannels.sorted()))

    /** Applies settings from a backup; keys missing from the file are left unchanged. */
    suspend fun restoreFromBackup(json: JSONObject) {
        context.dataStore.edit { prefs ->
            fun bool(name: String, key: Preferences.Key<Boolean>) {
                if (json.has(name)) prefs[key] = json.optBoolean(name)
            }
            bool("darkMode", Keys.DARK_MODE)
            bool("amoledBlackMode", Keys.AMOLED_BLACK)
            bool("adBlockEnabled", Keys.ADBLOCK_ENABLED)
            bool("sponsorSkipEnabled", Keys.SPONSOR_SKIP)
            bool("backgroundPlayEnabled", Keys.BACKGROUND_PLAY)
            bool("autoPipOnLeave", Keys.AUTO_PIP)
            bool("rememberSpeedAcrossVideos", Keys.REMEMBER_SPEED)
            bool("historyPaused", Keys.HISTORY_PAUSED)
            bool("autoplayEnabled", Keys.AUTOPLAY)
            bool("showStartTime", Keys.SHOW_START_TIME)
            fun int(name: String, key: Preferences.Key<Int>) {
                if (json.has(name)) prefs[key] = json.optInt(name)
            }
            int("smartSizeMb", Keys.SMART_SIZE_MB)
            int("downloadVideoQuality", Keys.DOWNLOAD_VIDEO_QUALITY)
            int("downloadMusicQuality", Keys.DOWNLOAD_MUSIC_QUALITY)
            json.optJSONArray("starredChannels")?.let { array ->
                prefs[Keys.STARRED_CHANNELS] = (0 until array.length()).map { array.optString(it) }.filter { it.isNotBlank() }.toSet()
            }
            if (json.has("playbackSpeed")) {
                prefs[Keys.PLAYBACK_SPEED] = json.optDouble("playbackSpeed", 1.0).toFloat().coerceIn(0.25f, 3.0f)
            }
        }
    }

    suspend fun setAutoplay(enabled: Boolean) {
        context.dataStore.edit { it[Keys.AUTOPLAY] = enabled }
    }

    suspend fun setSmartSize(mb: Int) {
        context.dataStore.edit { it[Keys.SMART_SIZE_MB] = mb.coerceAtLeast(0) }
    }

    /** [height]: video height, 0 = audio only, -1 = ask each time. */
    suspend fun setDownloadQuality(music: Boolean, height: Int) {
        context.dataStore.edit { it[if (music) Keys.DOWNLOAD_MUSIC_QUALITY else Keys.DOWNLOAD_VIDEO_QUALITY] = height }
    }

    suspend fun setSmartResult(at: Long, summary: String) {
        context.dataStore.edit { prefs ->
            prefs[Keys.LAST_SMART_REFRESH] = at
            prefs[Keys.SMART_SUMMARY] = summary
        }
    }

    suspend fun setStarred(channelId: String, starred: Boolean) {
        context.dataStore.edit { prefs ->
            val current = prefs[Keys.STARRED_CHANNELS] ?: emptySet()
            prefs[Keys.STARRED_CHANNELS] = if (starred) current + channelId else current - channelId
        }
    }

    suspend fun setShowStartTime(enabled: Boolean) {
        context.dataStore.edit { it[Keys.SHOW_START_TIME] = enabled }
    }

    suspend fun setHistoryPaused(paused: Boolean) {
        context.dataStore.edit { it[Keys.HISTORY_PAUSED] = paused }
    }

    /** Adds counts accumulated in memory; called in batches to avoid a disk write per event. */
    suspend fun addBlockedCounts(ads: Long, requests: Long) {
        if (ads == 0L && requests == 0L) return
        context.dataStore.edit { prefs ->
            prefs[Keys.ADS_BLOCKED] = (prefs[Keys.ADS_BLOCKED] ?: 0L) + ads
            prefs[Keys.REQUESTS_BLOCKED] = (prefs[Keys.REQUESTS_BLOCKED] ?: 0L) + requests
        }
    }

    suspend fun resetBlockedCounts() {
        context.dataStore.edit { prefs ->
            prefs[Keys.ADS_BLOCKED] = 0L
            prefs[Keys.REQUESTS_BLOCKED] = 0L
        }
    }
}
