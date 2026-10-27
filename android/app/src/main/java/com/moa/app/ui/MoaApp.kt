package com.moa.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.moa.app.BuildConfig

private enum class Destination(val label: String, val symbol: String) {
    HOME("우리 가족", "🏠"), PHOTOS("사진", "📷"), GAMES("게임", "🎲")
}

@Composable
fun MoaApp(homeViewModel: HomeViewModel = viewModel()) {
    var selected by rememberSaveable { mutableStateOf(Destination.HOME.name) }
    val connection by homeViewModel.connectionState.collectAsStateWithLifecycle()

    Scaffold(
        bottomBar = {
            NavigationBar {
                Destination.entries.forEach { destination ->
                    NavigationBarItem(
                        selected = selected == destination.name,
                        onClick = { selected = destination.name },
                        icon = { Text(destination.symbol) },
                        label = { Text(destination.label) },
                    )
                }
            }
        },
    ) { insets ->
        Column(
            modifier = Modifier.fillMaxSize().padding(insets)
                .verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Text("MOA", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
            when (Destination.valueOf(selected)) {
                Destination.HOME -> {
                    Text("따로 있어도, 함께", style = MaterialTheme.typography.titleLarge)
                    Text("우리 다섯의 사진과 이야기가 모일 공간이에요.")
                    FeatureCard("가족 사진", "일상의 한 장을 함께 나눌 앨범을 준비하고 있어요.")
                    FeatureCard("가족 게임", "각자 편한 시간에 이어 할 게임을 준비하고 있어요.")
                    ConnectionCard(connection, homeViewModel::checkConnection)
                }
                Destination.PHOTOS -> FeatureCard(
                    "우리 가족 앨범", "아직 사진 공유 기능을 준비 중이에요.",
                )
                Destination.GAMES -> FeatureCard(
                    "우리 가족 게임방", "아직 게임 기능을 준비 중이에요.",
                )
            }
        }
    }
}

@Composable
private fun FeatureCard(title: String, description: String) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(description, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@Composable
private fun ConnectionCard(state: ConnectionState, onCheck: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("서비스 연결", style = MaterialTheme.typography.titleMedium)
            Text(when (state) {
                ConnectionState.IDLE -> "서버와 연결할 수 있는지 확인해 보세요."
                ConnectionState.CHECKING -> "연결을 확인하고 있어요."
                ConnectionState.CONNECTED -> "서버에 연결됐어요."
                ConnectionState.UNAVAILABLE -> "연결하지 못했어요. 잠시 후 다시 시도해 주세요."
            })
            if (BuildConfig.DEBUG) {
                Text(BuildConfig.API_BASE_URL, style = MaterialTheme.typography.bodySmall)
            }
            Button(
                onClick = onCheck,
                enabled = state != ConnectionState.CHECKING,
                contentPadding = PaddingValues(horizontal = 24.dp, vertical = 14.dp),
            ) { Text("연결 확인") }
        }
    }
}
