package com.moa.app.data

import org.json.JSONObject

data class RacePlayer(val member: Member, val position: Int, val rank: Int, val rollsToday: Int, val totalPoints: Int)
data class RaceRoll(
    val id: Long, val member: Member, val weekStart: String, val day: String, val dice: Int, val event: String,
    val movement: Int, val position: Int, val createdAt: String,
)
data class RaceWinner(val member: Member, val position: Int, val rank: Int, val points: Int)
data class RaceLastWeek(val weekStart: String, val results: List<RaceWinner>)
data class RaceGame(
    val weekStart: String, val weekEnd: String, val today: String, val serverTime: String, val dailyLimit: Int,
    val remainingRolls: Int, val players: List<RacePlayer>, val recentRolls: List<RaceRoll>, val lastWeek: RaceLastWeek?,
)
data class RaceOutcome(val game: RaceGame, val roll: RaceRoll)

internal fun JSONObject.raceRoll() = RaceRoll(getLong("id"), getJSONObject("member").member(), getString("weekStart"),
    getString("day"), getInt("dice"), getString("event"), getInt("movement"), getInt("position"), getString("createdAt"))

internal fun JSONObject.raceGame(): RaceGame {
    val players = getJSONArray("players")
    val recent = getJSONArray("recentRolls")
    val last = optJSONObject("lastWeek")
    return RaceGame(getString("weekStart"), getString("weekEnd"), getString("today"), getString("serverTime"), getInt("dailyLimit"),
        getInt("remainingRolls"), List(players.length()) { index -> players.getJSONObject(index).let {
            RacePlayer(it.getJSONObject("member").member(), it.getInt("position"), it.getInt("rank"), it.getInt("rollsToday"), it.getInt("totalPoints"))
        } }, List(recent.length()) { recent.getJSONObject(it).raceRoll() }, last?.let {
            val results = it.getJSONArray("results")
            RaceLastWeek(it.getString("weekStart"), List(results.length()) { index -> results.getJSONObject(index).let { item ->
                RaceWinner(item.getJSONObject("member").member(), item.getInt("position"), item.getInt("rank"), item.getInt("points"))
            } })
        })
}
