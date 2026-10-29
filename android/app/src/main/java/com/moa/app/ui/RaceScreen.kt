package com.moa.app.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.moa.app.data.*
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.delay

@Composable
internal fun RaceScreen(state: MoaState, model: MoaViewModel) {
    val session = state.session ?: return
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner, model) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) model.refreshRace() }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    val game = state.race
    LaunchedEffect(game?.serverTime) {
        if (game != null) {
            val midnight = LocalDate.parse(game.today).plusDays(1).atStartOfDay(ZoneId.of("Asia/Seoul")).toInstant()
            delay(Duration.between(Instant.parse(game.serverTime), midnight).toMillis().coerceAtLeast(0) + 1_000)
            model.refreshRace()
        }
    }
    var rules by rememberSaveable { mutableStateOf(false) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Text("가족 운빨 달리기", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text("하루 세 번, 우리 가족 중 누가 가장 멀리 갈까요?", Modifier.padding(top = 8.dp))
        }
        if (state.raceLoading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        if (state.raceError != null) item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(state.raceError)
                    if (state.racePending) Text("다시 누르면 보내던 주사위 결과를 확인해요. 기회는 한 번만 사용돼요.")
                }
            }
        }
        if (game == null) {
            item {
                Text(if (state.raceLoading) "가족의 말을 불러오고 있어요…" else "서버에 연결하면 달리기를 시작할 수 있어요.")
                OutlinedButton(onClick = model::refreshRace, enabled = !state.raceLoading) { Text("다시 불러오기") }
            }
        } else {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                    Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("${shortDay(game.weekStart)} ~ ${shortDay(LocalDate.parse(game.weekStart).plusDays(6).toString())}", style = MaterialTheme.typography.labelLarge)
                        AnimatedContent(targetState = state.lastRoll?.id, label = "주사위 결과") { id ->
                            val roll = state.lastRoll?.takeIf { it.id == id }
                            Text(roll?.let { listOf("⚀", "⚁", "⚂", "⚃", "⚄", "⚅")[it.dice - 1] } ?: "🎲",
                                style = MaterialTheme.typography.displayLarge)
                        }
                        state.lastRoll?.let { roll ->
                            Text(roll.resultText(), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            if (roll.day != game.today) Text("${shortDay(roll.day)}에 보낸 요청의 결과예요.", style = MaterialTheme.typography.bodySmall)
                        } ?: Text("버튼 한 번이면 출발 준비 끝!")
                        Button(onClick = model::rollRace,
                            enabled = !state.raceLoading && !state.raceRolling && !state.loginBusy && (game.remainingRolls > 0 || state.racePending),
                            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                            Text(when {
                                state.raceRolling -> "결과를 확인하고 있어요…"
                                state.racePending -> "보내던 주사위 결과 확인"
                                game.remainingRolls == 0 -> "오늘은 여기까지!"
                                else -> "주사위 굴리기 · ${game.remainingRolls} / ${game.dailyLimit}회"
                            })
                        }
                        if (state.raceRolling) LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text(if (game.remainingRolls == 0) "한국 시간 자정에 3회가 다시 생겨요." else "다른 가족을 기다리지 않고 굴릴 수 있어요.",
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("이번 주 달리기", style = MaterialTheme.typography.titleLarge)
                        Text("일요일 밤 최종 거리로 순위를 정해요.", style = MaterialTheme.typography.bodySmall)
                    }
                    TextButton(onClick = model::refreshRace, enabled = !state.raceLoading && !state.raceRolling) { Text("새로고침") }
                }
            }
            val trackLength = maxOf(30, (game.players.maxOfOrNull { it.position } ?: 0) + 6)
            items(game.players, key = { "player-${it.member.id}" }) { player ->
                PlayerTrack(player, session.member.id, trackLength, game.dailyLimit)
            }
            game.lastWeek?.let { last ->
                item {
                    Card {
                        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("지난주 결과 · ${shortDay(last.weekStart)} 시작", style = MaterialTheme.typography.titleMedium)
                            last.results.forEach { winner ->
                                Text("${winner.rank}위 ${winner.member.displayName} · ${winner.position}칸 · +${winner.points}P")
                            }
                            Text("포인트가 누적 기록에 반영됐어요.", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            item { Text("가족의 발자국", style = MaterialTheme.typography.titleLarge) }
            if (game.recentRolls.isEmpty()) item { Text("아직 출발한 가족이 없어요. 먼저 굴려 보세요!") }
            items(game.recentRolls, key = { "roll-${it.id}" }) { roll ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("${roll.member.displayName} · ${roll.resultText()}", style = MaterialTheme.typography.bodyMedium)
                    Text(raceTime(roll.createdAt), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    HorizontalDivider(Modifier.padding(top = 8.dp))
                }
            }
        }
        item {
            TextButton(onClick = { rules = !rules }) { Text(if (rules) "게임 방법 접기" else "게임 방법 보기") }
            if (rules) Text("① 하루 3번, 주사위 숫자만큼 전진해요.\n② 80%는 일반 이동, 10%는 두 배 전진, 10%는 숫자와 관계없이 뒤로 3칸! 출발선 뒤로는 가지 않아요.\n③ 한국 시간 자정에 기회가 충전돼요. 남은 기회는 이월되지 않아요.\n④ 월요일 0시에 새 경기! 지난주 1·2·3위는 100·60·30P를 받아요.\n⑤ 동점이면 같은 순위와 포인트를 받아요. 공동 1위 두 명 다음은 3위예요. 참여한 가족만 순위에 들어가요.\n⑥ 포인트는 가족 게임 기록이에요.",
                style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun PlayerTrack(player: RacePlayer, myId: String, trackLength: Int, dailyLimit: Int) {
    val colors = listOf(Color(0xFF7756B8), Color(0xFFB84D7A), Color(0xFF287DA8), Color(0xFFB87523), Color(0xFF35836A))
    val ids = listOf("me", "sister", "brother-in-law", "mom", "dad")
    val color = colors[ids.indexOf(player.member.id).coerceAtLeast(0)]
    val fraction by animateFloatAsState(player.position.toFloat() / trackLength, label = "말 이동")
    Card(colors = CardDefaults.cardColors(containerColor = if (player.member.id == myId) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (player.rank == 0) "출발 전" else "${player.rank}위", color = color, style = MaterialTheme.typography.labelLarge)
                Text(player.member.displayName + if (player.member.id == myId) " (나)" else "", Modifier.weight(1f).padding(horizontal = 12.dp), fontWeight = FontWeight.Bold)
                Text("${player.position}칸", fontWeight = FontWeight.Bold)
            }
            BoxWithConstraints(Modifier.fillMaxWidth().height(40.dp)) {
                Canvas(Modifier.fillMaxSize()) {
                    drawLine(color.copy(alpha = 0.25f), Offset(20.dp.toPx(), center.y), Offset(size.width - 20.dp.toPx(), center.y), strokeWidth = 6.dp.toPx())
                }
                Box(Modifier.offset(x = (maxWidth - 40.dp) * fraction.coerceIn(0f, 1f)).size(40.dp).background(color, CircleShape), contentAlignment = Alignment.Center) {
                    Text(player.member.displayName.take(1), color = Color.White, fontWeight = FontWeight.Bold)
                }
            }
            Text("오늘 ${player.rollsToday} / ${dailyLimit}회 사용 · 누적 ${player.totalPoints}P", style = MaterialTheme.typography.bodySmall)
        }
    }
}

private fun RaceRoll.resultText(): String = when (event) {
    "DOUBLE" -> "${dice} × 2! ${movement}칸 전진"
    "BACK" -> if (movement == 0) "뒤로 3칸! 출발선에서 멈췄어요" else "뒤로 3칸! ${-movement}칸 후퇴"
    else -> "${dice}이 나왔어요! ${movement}칸 전진"
}
private fun shortDay(value: String): String = LocalDate.parse(value).format(DateTimeFormatter.ofPattern("M/d"))
private fun raceTime(value: String): String = DateTimeFormatter.ofPattern("M/d HH:mm").withZone(ZoneId.of("Asia/Seoul")).format(Instant.parse(value))
