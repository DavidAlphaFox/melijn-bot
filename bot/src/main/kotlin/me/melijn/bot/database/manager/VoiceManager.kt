package me.melijn.bot.database.manager

import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import me.melijn.ap.injector.Inject
import me.melijn.bot.database.model.VoiceJoins
import me.melijn.bot.database.model.VoiceLeaves
import me.melijn.kordkommons.database.DriverManager
import me.melijn.kordkommons.logger.logger
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration

@Inject
class VoiceManager(val driverManager: DriverManager) {

    /**
     * Table of users in voice chat right now.
     * Used to calculate VC times, and as "pending time" if the VC commmands are used while the user is in VC
     */
    private val joinTimes = ConcurrentHashMap<Long, Instant>()
    private val logger = logger()

    fun insertJoinNow(guild: Long, channel: Long, member: Long) {
        if (joinTimes.put(member, Clock.System.now()) != null)
            logger.warn("$member joined a voice channel twice without leaving")

        transaction(driverManager.database) {
            VoiceJoins.insert {
                it[this.guildId] = guild
                it[this.channelId] = channel
                it[this.userId] = member
                it[this.timestamp] = Clock.System.now()
            }
        }
    }

    /**
     * Insert a leave event, returning how long the user spent in VC
     */
    fun insertLeaveNow(guild: Long, channel: Long, member: Long): Duration? {
        val joinTime = joinTimes.remove(member)
        val timeSpent = joinTime?.let { Clock.System.now() - it }

        transaction(driverManager.database) {
            VoiceLeaves.insert {
                it[this.guildId] = guild
                it[this.channelId] = channel
                it[this.userId] = member
                it[this.timestamp] = Clock.System.now()
                it[this.timeSpent] = timeSpent
            }
        }

        return timeSpent
    }

    fun getPersonalVoiceStatistics(guild: Long, member: Long): Duration {
        val duration = transaction(driverManager.database) {
            VoiceLeaves.slice(VoiceLeaves.timeSpent.sum())
                .select {
                    (VoiceLeaves.userId eq member) and (VoiceLeaves.guildId eq guild)
                }
                .firstOrNull()
                ?.get(VoiceLeaves.timeSpent.sum())
        }

        return (duration ?: Duration.ZERO) + (getTimeInVCRightNow(member) ?: Duration.ZERO)
    }

    fun getGuildStatistics(guild: Long) = transaction(driverManager.database) {
        VoiceLeaves.slice(VoiceLeaves.userId, VoiceLeaves.timeSpent.sum(), VoiceLeaves.timeSpent.max())
            .select {
                (VoiceLeaves.guildId eq guild) and (VoiceLeaves.timeSpent.isNotNull())
            }
            .orderBy(VoiceLeaves.timeSpent.sum() to SortOrder.DESC)
            .groupBy(VoiceLeaves.userId)
            .limit(9)
            .map { row ->
                val userId = row[VoiceLeaves.userId]
                val timeNow = getTimeInVCRightNow(userId) ?: Duration.ZERO
                GuildStatisticsEntry(
                    userId,
                    row[VoiceLeaves.timeSpent.sum()]?.plus(timeNow) ?: Duration.ZERO,
                    row[VoiceLeaves.timeSpent.max()] ?: Duration.ZERO
                )
            }
            .sortedByDescending { it.timeSpentTotal }
    }

    private fun getTimeInVCRightNow(member: Long) =
        this.joinTimes[member]?.let { Clock.System.now() - it }

    data class GuildStatisticsEntry(val userId: Long, val timeSpentTotal: Duration, val timeSpentLongest: Duration)

}