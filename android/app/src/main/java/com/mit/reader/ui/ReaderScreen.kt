package com.mit.reader.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.mit.reader.PageStatus
import com.mit.reader.ReaderApp
import com.mit.reader.ReaderViewModel
import com.mit.reader.data.ReadingMode

private const val MAX_ZOOM = 5f

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(bookId: String, onBack: () -> Unit) {
    val vm: ReaderViewModel = viewModel()
    val app = LocalContext.current.applicationContext as ReaderApp

    LaunchedEffect(bookId) {
        app.library.book(bookId)?.let(vm::load)
    }

    val book = vm.book ?: return
    val pagerState = rememberPagerState(
        initialPage = vm.currentPage,
        pageCount = { book.pageCount },
    )

    LaunchedEffect(pagerState.currentPage) {
        vm.setPage(pagerState.currentPage)
    }

    val st = vm.pageStates[vm.currentPage]
    val status = st?.status

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(book.title, maxLines = 1)
                        Text(
                            "${vm.currentPage + 1} / ${book.pageCount}  ·  ${if (book.mode == ReadingMode.MANGA) "日漫(右→左)" else "普通(左→右)"}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = "返回") }
                },
                actions = {
                    Text(
                        "自动后3页",
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.align(Alignment.CenterVertically),
                    )
                    Switch(
                        checked = vm.autoMode,
                        onCheckedChange = { vm.setAuto(it) },
                    )
                },
            )
        },
        bottomBar = {
            Row(
                Modifier.fillMaxWidth().padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(
                    onClick = { vm.translateCurrent() },
                    enabled = status != PageStatus.RUNNING,
                    modifier = Modifier.weight(1f),
                ) {
                    when (status) {
                        PageStatus.RUNNING -> {
                            CircularProgressIndicator(
                                Modifier.padding(end = 8.dp).size(18.dp),
                                strokeWidth = 2.dp,
                            )
                            Text("翻译中…")
                        }
                        PageStatus.FAILED -> Text("重试（${st?.error ?: ""}）")
                        PageStatus.DONE -> Text("重新翻译")
                        else -> Text("翻译本页")
                    }
                }
                OutlinedButton(
                    onClick = {},
                    enabled = status == PageStatus.DONE,
                    modifier = Modifier.pointerInput(status) {
                        if (status != PageStatus.DONE) return@pointerInput
                        detectTapGestures(
                            onPress = {
                                if (tryAwaitRelease()) {
                                    // 短按：固定切换 译文 ↔ 原图
                                    vm.toggleOriginal()
                                } else {
                                    // 长按：临时切到另一视图，松开即恢复
                                    vm.setPeek(true)
                                    tryAwaitRelease()
                                    vm.setPeek(false)
                                }
                            },
                        )
                    },
                ) {
                    // 默认展示译文，短按切回原图；长按临时切换
                    Text(if (vm.showOriginal) "看译文" else "看原图")
                }
            }
        },
    ) { pad ->
        Box(Modifier.fillMaxSize().padding(pad)) {
            HorizontalPager(
                state = pagerState,
                reverseLayout = book.mode == ReadingMode.MANGA,
                modifier = Modifier.fillMaxSize(),
            ) { i ->
                val pageState = vm.pageStates[i]
                // 该页已翻好时默认展示译文；用户短按/长按「看原图」才切回原文（含长按临时切换）
                val show = i == vm.currentPage && pageState?.status == PageStatus.DONE && !vm.effectiveShowOriginal
                val file = if (show) pageState?.translatedFile else book.pageFiles[i]
                ZoomableImage(
                    model = file,
                    contentDescription = "第 ${i + 1} 页",
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

/** 可双指捏合放大/缩小的图片；放大后单指拖动平移，1x 下单指横滑仍留给 pager 翻页。 */
@Composable
private fun ZoomableImage(
    model: Any?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
) {
    val scale = remember { mutableFloatStateOf(1f) }
    val offset = remember { mutableStateOf(Offset.Zero) }
    val viewport = remember { mutableStateOf(IntSize.Zero) }

    Box(
        modifier = modifier
            .onSizeChanged { viewport.value = it }
            .graphicsLayer {
                scaleX = scale.floatValue
                scaleY = scale.floatValue
                translationX = offset.value.x
                translationY = offset.value.y
                clip = true
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.count { it.pressed }
                        val zoomChange = event.calculateZoom()
                        val panChange = event.calculatePan()
                        val centroid = event.calculateCentroid()
                        // 只在双指捏合、或已经放大后的单指拖动时接管手势；
                        // 1x 下单指横滑不消费，交给 pager 翻页。
                        if (pressed >= 2 || scale.floatValue > 1f) {
                            val old = scale.floatValue
                            val next = (old * zoomChange).coerceIn(1f, MAX_ZOOM)
                            val ratio = if (old > 0f) next / old else 1f
                            val newOffset = centroid - (centroid - offset.value) * ratio + panChange
                            scale.floatValue = next
                            offset.value = clampOffset(newOffset, next, viewport.value)
                            event.changes.forEach { it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                }
            },
    ) {
        AsyncImage(
            model = model,
            contentDescription = contentDescription,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/** 把平移量限制在内容放大后刚好不脱出视口的范围（1x 时强制归零）。 */
private fun clampOffset(o: Offset, scale: Float, viewport: IntSize): Offset {
    if (viewport == IntSize.Zero) return Offset.Zero
    val maxX = viewport.width * (scale - 1f) / 2f
    val maxY = viewport.height * (scale - 1f) / 2f
    return Offset(o.x.coerceIn(-maxX, maxX), o.y.coerceIn(-maxY, maxY))
}
