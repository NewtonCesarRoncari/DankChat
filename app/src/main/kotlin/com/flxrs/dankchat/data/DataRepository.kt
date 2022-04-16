package com.flxrs.dankchat.data

import android.util.Log
import com.flxrs.dankchat.data.api.ApiManager
import com.flxrs.dankchat.data.api.dto.HelixUserDto
import com.flxrs.dankchat.data.api.dto.UserFollowsDto
import com.flxrs.dankchat.data.twitch.badge.toBadgeSets
import com.flxrs.dankchat.data.twitch.emote.EmoteManager
import com.flxrs.dankchat.data.twitch.emote.GenericEmote
import com.flxrs.dankchat.data.twitch.emote.ThirdPartyEmoteType
import com.flxrs.dankchat.utils.extensions.measureTimeAndLog
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import kotlin.system.measureTimeMillis

class DataRepository @Inject constructor(
    private val apiManager: ApiManager,
    private val emoteManager: EmoteManager,
    private val recentUploadsRepository: RecentUploadsRepository,
) {
    private val emotes = ConcurrentHashMap<String, MutableStateFlow<List<GenericEmote>>>()
    private var loadedGlobalEmotes = false

    sealed class ServiceEvent {
        object Shutdown : ServiceEvent()
    }

    private val commandsChannel = Channel<ServiceEvent>(Channel.BUFFERED)
    val commands = commandsChannel.receiveAsFlow()

    fun getEmotes(channel: String): StateFlow<List<GenericEmote>> = emotes.getOrPut(channel) { MutableStateFlow(emptyList()) }

    suspend fun loadChannelData(channel: String, oAuth: String, channelId: String? = null, loadThirdPartyData: Set<ThirdPartyEmoteType>, forceReload: Boolean = false) {
        return withContext(Dispatchers.Default) {
            emotes.putIfAbsent(channel, MutableStateFlow(emptyList()))
            val id = channelId ?: apiManager.getUserIdByName(oAuth, channel) ?: return@withContext
            launch { loadChannelBadges(oAuth, channel, id) }
            launch {
                measureTimeMillis {
                    load3rdPartyEmotes(channel, id, loadThirdPartyData, forceReload)
                }.let { Log.i(TAG, "Loaded 3rd party emotes for #$channel in $it ms") }
            }
        }
    }

    suspend fun getUser(oAuth: String, userId: String): HelixUserDto? = apiManager.getUser(oAuth, userId)
    suspend fun getUserIdByName(oAuth: String, name: String): String? = apiManager.getUserIdByName(oAuth, name)
    suspend fun getUserFollows(oAuth: String, fromId: String, toId: String): UserFollowsDto? = apiManager.getUsersFollows(oAuth, fromId, toId)
    suspend fun blockUser(oAuth: String, targetUserId: String): Boolean = apiManager.blockUser(oAuth, targetUserId)
    suspend fun unblockUser(oAuth: String, targetUserId: String): Boolean = apiManager.unblockUser(oAuth, targetUserId)

    suspend fun uploadMedia(file: File): String? {
        val upload = apiManager.uploadMedia(file)
        if (upload != null) {
            recentUploadsRepository.addUpload(upload)
        }

        return upload?.imageLink
    }

    suspend fun loadGlobalBadges(oAuth: String) = withContext(Dispatchers.Default) {
        measureTimeAndLog(TAG, "global badges") {
            val badges = when {
                oAuth.isBlank() -> apiManager.getGlobalBadgesFallback()?.toBadgeSets()
                else            -> apiManager.getGlobalBadges(oAuth)?.toBadgeSets() ?: apiManager.getGlobalBadgesFallback()?.toBadgeSets()
            }
            badges?.let { emoteManager.setGlobalBadges(it) }
        }
    }

    suspend fun loadDankChatBadges() = withContext(Dispatchers.Default) {
        measureTimeMillis {
            apiManager.getDankChatBadges()?.let { emoteManager.setDankChatBadges(it) }
        }.let { Log.i(TAG, "Loaded DankChat badges in $it ms") }
    }

    // TODO refactor to flow/observe pattern
    suspend fun setEmotesForSuggestions(channel: String) {
        emotes.putIfAbsent(channel, MutableStateFlow(emptyList()))
        emotes[channel]?.value = emoteManager.getEmotes(channel)
    }

    suspend fun loadUserStateEmotes(globalEmoteSetIds: List<String>, followerEmoteSetIds: Map<String, List<String>>) {
        emoteManager.loadUserStateEmotes(globalEmoteSetIds, followerEmoteSetIds)
    }

    suspend fun sendShutdownCommand() {
        commandsChannel.send(ServiceEvent.Shutdown)
    }

    private suspend fun loadChannelBadges(oAuth: String, channel: String, id: String) {
        measureTimeAndLog(TAG, "channel badges for #$id") {
            val badges = when {
                oAuth.isBlank() -> apiManager.getChannelBadgesFallback(id)?.toBadgeSets()
                else            -> apiManager.getChannelBadges(oAuth, id)?.toBadgeSets() ?: apiManager.getChannelBadgesFallback(id)?.toBadgeSets()
            }
            badges?.let { emoteManager.setChannelBadges(channel, it) }
        }
    }

    private suspend fun load3rdPartyEmotes(channel: String, id: String, loadThirdPartyData: Set<ThirdPartyEmoteType>, forceReload: Boolean) = coroutineScope {
        launchWhenEnabled(ThirdPartyEmoteType.FrankerFaceZ, loadThirdPartyData) {
            apiManager.getFFZChannelEmotes(id)?.let { emoteManager.setFFZEmotes(channel, it) }
        } ?: emoteManager.clearFFZEmotes()
        launchWhenEnabled(ThirdPartyEmoteType.BetterTTV, loadThirdPartyData) {
            apiManager.getBTTVChannelEmotes(id)?.let { emoteManager.setBTTVEmotes(channel, it) }
        } ?: emoteManager.clearBTTVEmotes()
        launchWhenEnabled(ThirdPartyEmoteType.SevenTV, loadThirdPartyData) {
            apiManager.getSevenTVChannelEmotes(id)?.let { emoteManager.setSevenTVEmotes(channel, it) }
        } ?: emoteManager.clearSevenTVEmotes()

        if (forceReload || !loadedGlobalEmotes) {
            launchWhenEnabled(ThirdPartyEmoteType.FrankerFaceZ, loadThirdPartyData) {
                apiManager.getFFZGlobalEmotes()?.let { emoteManager.setFFZGlobalEmotes(it) }
            }
            launchWhenEnabled(ThirdPartyEmoteType.BetterTTV, loadThirdPartyData) {
                apiManager.getBTTVGlobalEmotes()?.let { emoteManager.setBTTVGlobalEmotes(it) }
            }
            launchWhenEnabled(ThirdPartyEmoteType.SevenTV, loadThirdPartyData) {
                apiManager.getSevenTVGlobalEmotes()?.let { emoteManager.setSevenTVGlobalEmotes(it) }
            }
        }

        loadedGlobalEmotes = true
    }

    private fun CoroutineScope.launchWhenEnabled(type: ThirdPartyEmoteType, loadThirdPartyData: Set<ThirdPartyEmoteType>, block: suspend () -> Unit): Job? = when (type) {
        in loadThirdPartyData -> launch { block() }
        else                  -> null
    }

    companion object {
        private val TAG = DataRepository::class.java.simpleName
    }
}