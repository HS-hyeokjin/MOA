package com.moa.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.ImageLoader
import coil3.compose.SubcomposeAsyncImage
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import com.moa.app.data.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private enum class Destination(val label: String, val symbol: String) {
    HOME("우리 가족", "🏠"), PHOTOS("사진", "📷"), GAMES("게임", "🎲")
}

@Composable
fun MoaApp(model: MoaViewModel = viewModel()) {
    val state by model.state.collectAsStateWithLifecycle()
    var selected by rememberSaveable { mutableStateOf(Destination.HOME.name) }
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    val loader = remember { ImageLoader.Builder(context).components {
        add(OkHttpNetworkFetcherFactory(callFactory = { model.repository.client }))
    }.build() }
    DisposableEffect(loader) { onDispose { loader.shutdown() } }
    LaunchedEffect(state.message) {
        state.message?.let { message ->
            snackbar.showSnackbar(message)
            if (model.state.value.message == message) model.message(null)
        }
    }
    LaunchedEffect(state.session?.member?.id) { selected = Destination.HOME.name }
    if (state.starting) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    val session = state.session
    if (session == null) {
        Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { padding -> LoginScreen(state, model, Modifier.padding(padding)) }
        return
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(5)) { uris ->
        if (uris.isNotEmpty()) { model.select(uris); selected = Destination.PHOTOS.name }
    }
    val pickPhotos = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
    Scaffold(snackbarHost = { SnackbarHost(snackbar) }, bottomBar = {
        NavigationBar {
            Destination.entries.forEach { destination ->
                NavigationBarItem(selected = selected == destination.name, onClick = { selected = destination.name },
                    icon = { Text(destination.symbol) }, label = { Text(destination.label) })
            }
        }
    }) { insets ->
        Column(Modifier.fillMaxSize().padding(insets)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("MOA", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                    Text("${session.member.displayName}님, 반가워요", style = MaterialTheme.typography.bodyMedium)
                }
                TextButton(onClick = model::logout,
                    enabled = !state.loginBusy && !state.uploading && state.downloading == null && state.busyPosts.isEmpty()) { Text("로그아웃") }
            }
            when (Destination.valueOf(selected)) {
                Destination.HOME -> HomeScreen(state, loader, model, { selected = Destination.PHOTOS.name }, pickPhotos)
                Destination.PHOTOS -> AlbumScreen(state, loader, model, pickPhotos)
                Destination.GAMES -> Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("우리 가족 게임방", style = MaterialTheme.typography.headlineSmall)
                    Text("게임은 다음 버전에서 만나요. 지금은 사진으로 일상을 나눠 보세요.")
                }
            }
        }
    }
}

@Composable
private fun LoginScreen(state: MoaState, model: MoaViewModel, modifier: Modifier) {
    var code by remember { mutableStateOf("") }
    Column(modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(28.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Spacer(Modifier.height(36.dp))
        Text("MOA", style = MaterialTheme.typography.displayMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        Text("따로 있어도, 함께", style = MaterialTheme.typography.headlineSmall)
        Text("우리 다섯만의 앨범에 초대합니다.\n받은 초대코드로 시작해 보세요.", style = MaterialTheme.typography.bodyLarge)
        OutlinedTextField(value = code, onValueChange = { code = it.take(64) }, label = { Text("초대코드") },
            singleLine = true, enabled = !state.loginBusy, modifier = Modifier.fillMaxWidth(),
            visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, autoCorrectEnabled = false))
        OutlinedTextField(value = state.serverUrl, onValueChange = model::server, label = { Text("가족 서버 주소") },
            placeholder = { Text("https://api.example.com") }, supportingText = { Text("가족 서버를 설정한 사람이 알려준 주소예요.") },
            singleLine = true, enabled = !state.loginBusy, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
        Button(onClick = { model.login(code) }, enabled = code.isNotBlank() && state.serverUrl.isNotBlank() && !state.loginBusy,
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
            if (state.loginBusy) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp) else Text("우리 가족 앨범 시작하기")
        }
        Text("초대코드는 한 번만 사용할 수 있어요. 로그인한 휴대폰에서는 다음에도 바로 들어올 수 있어요.")
    }
}

@Composable
private fun HomeScreen(state: MoaState, loader: ImageLoader, model: MoaViewModel, onAlbum: () -> Unit, onPick: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Text("우리의 오늘이 모이는 곳", style = MaterialTheme.typography.headlineSmall)
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("오늘은 어떤 하루였나요?", style = MaterialTheme.typography.titleLarge)
                Text("밥 한 끼, 산책, 아기의 웃음.\n사진 한 장으로 소식을 전해 보세요.")
                Button(onClick = onPick, enabled = !state.uploading) { Text("사진 올리기") }
            }
        }
        if (state.members.isNotEmpty()) {
            Text("함께하는 다섯 명", style = MaterialTheme.typography.titleMedium)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(state.members, key = Member::id) { member ->
                    Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
                        Text(member.displayName, Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("최근 가족 소식", modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
            TextButton(onClick = onAlbum) { Text("앨범 보기") }
        }
        if (state.loading && state.posts.isEmpty()) LinearProgressIndicator(Modifier.fillMaxWidth())
        else if (state.posts.isEmpty()) Text(if (state.feedLoaded) "아직 올라온 사진이 없어요. 첫 사진을 남겨 보세요." else "서버 연결을 확인한 뒤 소식을 새로고침해 주세요.")
        state.posts.firstOrNull()?.let { post ->
            Card(Modifier.fillMaxWidth().clickable(onClick = onAlbum)) {
                post.photos.firstOrNull()?.let { photo -> FamilyImage(state.session!!, photo.thumbnailUrl, loader,
                    Modifier.fillMaxWidth().height(220.dp), ContentScale.Crop) }
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${post.author.displayName} · ${date(post.createdAt)}", style = MaterialTheme.typography.labelLarge)
                    if (post.caption.isNotBlank()) Text(post.caption)
                }
            }
        }
        OutlinedButton(onClick = { model.refresh() }, enabled = !state.loading && state.busyPosts.isEmpty()) { Text("소식 새로고침") }
    }
}

@Composable
private fun AlbumScreen(state: MoaState, loader: ImageLoader, model: MoaViewModel, onPick: () -> Unit) {
    val session = state.session ?: return
    var viewing by remember { mutableStateOf<Pair<Post, Int>?>(null) }
    var deleting by remember { mutableStateOf<Post?>(null) }
    LazyColumn(Modifier.fillMaxSize().imePadding(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("가족 앨범", modifier = Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall)
                Button(onClick = onPick, enabled = !state.uploading) { Text("사진 선택") }
            }
        }
        if (state.selected.isNotEmpty()) item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("가족에게 나눌 사진 ${state.selected.size}장", style = MaterialTheme.typography.titleMedium)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(state.selected, key = { it.toString() }) { uri ->
                            SubcomposeAsyncImage(model = uri, imageLoader = loader, contentDescription = "선택한 사진",
                                modifier = Modifier.size(96.dp).clip(RoundedCornerShape(12.dp)), contentScale = ContentScale.Crop)
                        }
                    }
                    OutlinedTextField(value = state.caption, onValueChange = model::caption, label = { Text("한 줄 소식") },
                        supportingText = { Text("${state.caption.length}/500 · JPG·PNG, 한 장에 10MB까지") },
                        enabled = !state.uploading, modifier = Modifier.fillMaxWidth(), maxLines = 5)
                    if (state.uploading) {
                        LinearProgressIndicator(progress = { state.uploadProgress }, modifier = Modifier.fillMaxWidth())
                        Text(if (state.uploadProgress < 1f) "사진을 보내고 있어요 · ${(state.uploadProgress * 100).toInt()}%" else "사진을 앨범에 담고 있어요…")
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = model::upload, enabled = !state.uploading && !state.loading) { Text(if (state.uploading) "공유 중…" else "가족에게 공유") }
                        TextButton(onClick = { model.select(emptyList()) }, enabled = !state.uploading) { Text("취소") }
                    }
                    Text("전송에 실패해도 선택한 사진은 남아 있어요. 다시 공유할 수 있어요.", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        item {
            TextButton(onClick = { model.refresh() }, enabled = !state.loading && !state.uploading && state.busyPosts.isEmpty()) { Text("새로고침") }
            if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (!state.loading && state.posts.isEmpty()) Text(if (state.feedLoaded) "첫 번째 가족 사진을 올려 보세요." else "사진을 불러오지 못했어요. 새로고침으로 다시 시도해 주세요.")
        }
        items(state.posts, key = Post::id) { post ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(post.author.displayName, style = MaterialTheme.typography.titleMedium)
                            Text(date(post.createdAt), style = MaterialTheme.typography.bodySmall)
                        }
                        if (post.author.id == session.member.id) TextButton(onClick = { deleting = post },
                            enabled = post.id !in state.busyPosts && !state.loading) { Text("삭제") }
                    }
                    if (post.caption.isNotBlank()) Text(post.caption, style = MaterialTheme.typography.bodyLarge)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(post.photos.size) { index ->
                            FamilyImage(session, post.photos[index].thumbnailUrl, loader,
                                Modifier.width(260.dp).height(220.dp).clip(RoundedCornerShape(14.dp)).clickable { viewing = post to index }, ContentScale.Crop)
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        OutlinedButton(onClick = { model.like(post) }, enabled = post.id !in state.busyPosts && !state.loading) {
                            Text("${if (post.likedByMe) "♥" else "♡"} 좋아요 ${post.likeCount}")
                        }
                        Text("사진 ${post.photos.size}장 · 눌러서 크게 보기", modifier = Modifier.weight(1f).padding(start = 12.dp), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        if (state.nextCursor != null) item {
            OutlinedButton(onClick = { model.refresh(more = true) }, enabled = !state.loading && state.busyPosts.isEmpty(),
                modifier = Modifier.fillMaxWidth()) { Text("이전 사진 더 보기") }
        }
    }
    deleting?.let { post ->
        AlertDialog(onDismissRequest = { deleting = null }, title = { Text("사진을 삭제할까요?") },
            text = { Text("이 게시물의 사진 ${post.photos.size}장과 하트가 삭제돼요. 다운로드해 둔 사진은 남아 있어요.") },
            confirmButton = { TextButton(onClick = { model.delete(post); deleting = null }) { Text("삭제") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("취소") } })
    }
    viewing?.let { (post, index) ->
        if (state.posts.none { it.id == post.id }) LaunchedEffect(post.id) { viewing = null }
        else PhotoViewer(session, post, index, loader, state.downloading, model::download) { viewing = null }
    }
}

@Composable
private fun FamilyImage(session: Session, path: String, loader: ImageLoader, modifier: Modifier, scale: ContentScale) {
    val context = LocalContext.current
    val request = remember(session.token, path) {
        ImageRequest.Builder(context).data(session.serverUrl + path)
            .httpHeaders(NetworkHeaders.Builder().set("Authorization", "Bearer ${session.token}").build())
            .diskCachePolicy(CachePolicy.DISABLED).build()
    }
    SubcomposeAsyncImage(model = request, imageLoader = loader, contentDescription = "가족이 공유한 사진", modifier = modifier,
        contentScale = scale, loading = { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(24.dp)) } },
        error = { Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant).padding(12.dp), contentAlignment = Alignment.Center) {
            Text("사진을 불러오지 못했어요.\n연결을 확인하고 다시 열어 주세요.")
        } })
}

@Composable
private fun PhotoViewer(session: Session, post: Post, start: Int, loader: ImageLoader, downloading: String?, onDownload: (Photo) -> Unit, onClose: () -> Unit) {
    val pager = rememberPagerState(initialPage = start) { post.photos.size }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("${pager.currentPage + 1} / ${post.photos.size}")
                    TextButton(onClick = onClose) { Text("닫기") }
                }
                HorizontalPager(state = pager, modifier = Modifier.weight(1f)) { index ->
                    FamilyImage(session, post.photos[index].previewUrl, loader, Modifier.fillMaxSize(), ContentScale.Fit)
                }
                Button(onClick = { onDownload(post.photos[pager.currentPage]) }, enabled = downloading == null,
                    modifier = Modifier.fillMaxWidth().padding(20.dp).heightIn(min = 52.dp)) {
                    Text(if (downloading != null) "원본 저장 중…" else "원본 다운로드")
                }
            }
        }
    }
}

private fun date(value: String): String = runCatching {
    DateTimeFormatter.ofPattern("yyyy. M. d. HH:mm").withZone(ZoneId.systemDefault()).format(Instant.parse(value))
}.getOrDefault("")
