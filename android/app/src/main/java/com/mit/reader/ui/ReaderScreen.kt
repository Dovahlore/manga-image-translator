package com.mit.reader.ui

import android.os.SystemClock
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import coil.compose.SubcomposeAsyncImage
import coil.compose.SubcomposeAsyncImageContent
import com.mit.reader.PageStatus
import com.mit.reader.ReaderApp
import com.mit.reader.ReaderViewModel
import com.mit.reader.data.ReadingMode
import kotlin.math.abs
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val MAX_ZOOM = 5f
private const val DOUBLE_TAP_ZOOM = 2.5f

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(bookId: String, onBack: () -> Unit) {
    val vm: ReaderViewModel = viewModel()
    val app = LocalContext.current.applicationContext as ReaderApp
    val scope = rememberCoroutineScope()

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
        // 记录阅读进度（下次打开跳到这一页）
        app.library.setReadingProgress(book.id, pagerState.currentPage)
    }

    val st = vm.pageStates[vm.currentPage]
    val status = st?.status

    // 沉浸模式（隐藏上下 UI）+ 跳页滑块
    var immersive by remember { mutableStateOf(false) }
    var sliderValue by remember { mutableFloatStateOf(vm.currentPage.toFloat()) }
    LaunchedEffect(pagerState.currentPage) {
        sliderValue = pagerState.currentPage.toFloat()
    }

    Scaffold(
        topBar = {
            if (!immersive) {
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
                        TextButton(onClick = { immersive = true }) { Text("全屏") }
                    },
                )
            }
        },
        bottomBar = {
            if (!immersive) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
                    if (book.pageCount > 1) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "${sliderValue.toInt() + 1}",
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(end = 8.dp),
                            )
                            Slider(
                                value = sliderValue,
                                onValueChange = { sliderValue = it },
                                onValueChangeFinished = {
                                    scope.launch {
                                        pagerState.scrollToPage(sliderValue.toInt().coerceIn(0, book.pageCount - 1))
                                    }
                                },
                                valueRange = 0f..(book.pageCount - 1).toFloat(),
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 4.dp),
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
                        ToggleViewButton(
                            enabled = status == PageStatus.DONE,
                            showOriginal = vm.showOriginal,
                            onToggle = { vm.toggleOriginal() },
                        )
                    }
                }
            }
        },
    ) { pad ->
        Box(Modifier.fillMaxSize().padding(if (immersive) PaddingValues(0.dp) else pad)) {
            HorizontalPager(
                state = pagerState,
                reverseLayout = book.mode == ReadingMode.MANGA,
                modifier = Modifier.fillMaxSize(),
            ) { i ->
                val pageState = vm.pageStates[i]
                // 该页已翻好时默认展示译文；用户点图/按钮切换原图
                val show = i == vm.currentPage && pageState?.status == PageStatus.DONE && !vm.showOriginal
                val file = if (show) pageState?.translatedFile else book.pageFiles[i]
                ZoomableImage(
                    model = file,
                    contentDescription = "第 ${i + 1} 页",
                    onSingleTap = { vm.toggleOriginal() },
                    pagerState = pagerState,
                    reverseLayout = book.mode == ReadingMode.MANGA,
                    pageCount = book.pageCount,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            if (immersive) {
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = Color.Black.copy(alpha = 0.5f),
                    modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
                ) {
                    TextButton(onClick = { immersive = false }) {
                        Text("退出全屏", color = Color.White)
                    }
                }
            }
        }
    }
}

/** 切换译文/原图按钮（简单开关）。 */
@Composable
private fun ToggleViewButton(
    enabled: Boolean,
    showOriginal: Boolean,
    onToggle: () -> Unit,
) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = Color.Transparent,
        contentColor = if (enabled) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        modifier = Modifier.clickable(enabled = enabled, onClick = onToggle),
    ) {
        Text(
            text = if (showOriginal) "看译文" else "看原图",
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
}

/**
 * 可缩放的图片：
 * - 双指捏合放大/缩小，放大后单指拖动平移（1x 下单指横滑仍留给 pager 翻页）
 * - 单击 → 切换原文/译文；双击 → 放大 / 恢复原始大小
 */
@Composable
private fun ZoomableImage(
    model: Any?,
    contentDescription: String?,
    onSingleTap: () -> Unit,
    pagerState: PagerState,
    reverseLayout: Boolean,
    pageCount: Int,
    modifier: Modifier = Modifier,
) {
    val scale = remember { mutableFloatStateOf(1f) }
    val offset = remember { mutableStateOf(Offset.Zero) }
    val viewport = remember { mutableStateOf(IntSize.Zero) }
    val fittedSize = remember { mutableStateOf(IntSize.Zero) }
    val viewConfig = LocalViewConfiguration.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current

    // 单击/双击判定：单击延迟到双击超时后再触发，双击放大
    var tapSeq by remember { mutableIntStateOf(0) }
    var lastTapAt by remember { mutableLongStateOf(0L) }
    var pendingSingleTap by remember { mutableStateOf(false) }
    val currentOnSingleTap by rememberUpdatedState(onSingleTap)

    fun toggleZoom() {
        scale.floatValue = if (scale.floatValue > 1f) 1f else DOUBLE_TAP_ZOOM
        offset.value = Offset.Zero
    }

    fun registerTap(pos: Offset) {
        val now = SystemClock.uptimeMillis()
        // 双击只看时间间隔，不比对两次点击位置（手指有抖动，位置判定太严格会误伤）
        val isDouble = lastTapAt != 0L && now - lastTapAt <= viewConfig.doubleTapTimeoutMillis
        if (isDouble) {
            lastTapAt = 0L
            pendingSingleTap = false
            toggleZoom()
        } else {
            lastTapAt = now
            pendingSingleTap = true
            tapSeq++
        }
    }
    val currentRegisterTap by rememberUpdatedState<(Offset) -> Unit>(::registerTap)

    LaunchedEffect(tapSeq) {
        if (pendingSingleTap) {
            delay(viewConfig.doubleTapTimeoutMillis)
            if (pendingSingleTap) {
                pendingSingleTap = false
                lastTapAt = 0L   // 单击完成后清掉，避免下一次点击被误判成双击
                currentOnSingleTap()
            }
        }
    }

    Box(
        modifier = modifier
            .onGloballyPositioned { viewport.value = it.size }
            .clipToBounds(),
        contentAlignment = Alignment.Center,
    ) {
        SubcomposeAsyncImage(
            model = model,
            contentDescription = contentDescription,
            contentScale = ContentScale.FillBounds,
            modifier = Modifier
                .graphicsLayer {
                    scaleX = scale.floatValue
                    scaleY = scale.floatValue
                    translationX = offset.value.x
                    translationY = offset.value.y
                }
                .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val downPos = down.position
                    var totalMove = 0f
                    var lastPos = downPos
                    var maxPointers = 1
                    var overscrollX = 0f
                    var lastVelocity = Offset.Zero
                    var lastEventTime = 0L
                    do {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.count { it.pressed }
                        if (pressed > maxPointers) maxPointers = pressed
                        // 只有还有手指按着才算移动；抬起那一下 calculateCentroid 会返回 (0,0)，会误判成大位移
                        if (pressed > 0) {
                            val centroid = event.calculateCentroid()
                            totalMove += (centroid - lastPos).getDistance()
                            lastPos = centroid
                        }
                        // 只在双指捏合、或已经放大后的单指拖动时接管手势；
                        // 1x 下单指横滑不消费，交给 pager 翻页。
                        if (pressed >= 2 || scale.floatValue > 1f) {
                            val panChange = event.calculatePan()
                            // 记录平移速度（松手后做惯性滑动用）
                            val nowMs = SystemClock.uptimeMillis()
                            if (lastEventTime != 0L) {
                                val dt = ((nowMs - lastEventTime).coerceAtLeast(1L)) / 1000f
                                lastVelocity = Offset(panChange.x / dt, panChange.y / dt)
                            }
                            lastEventTime = nowMs
                            // 只有双指才改缩放；单指 calculateZoom 是「手指到屏幕原点的距离比」，
                            // 会随手指位置漂移导致缩放乱跳（也是拖动像有阻力的元凶）
                            val newScale = if (pressed >= 2) {
                                (scale.floatValue * event.calculateZoom()).coerceIn(1f, MAX_ZOOM)
                            } else {
                                scale.floatValue
                            }
                            scale.floatValue = newScale

                            if (pressed == 1 && newScale > 1f) {
                                // 放大后的单指平移：水平滑到边缘继续外滑 → 累计翻页距离；垂直始终平移
                                val maxX = ((fittedSize.value.width * newScale - viewport.value.width) / 2f).coerceAtLeast(0f)
                                val maxY = ((fittedSize.value.height * newScale - viewport.value.height) / 2f).coerceAtLeast(0f)
                                val panX = panChange.x
                                val panY = panChange.y
                                val atLeft = offset.value.x <= -maxX + 0.5f
                                val atRight = offset.value.x >= maxX - 0.5f
                                val newX = if ((atLeft && panX < 0f) || (atRight && panX > 0f)) {
                                    overscrollX += panX
                                    offset.value.x
                                } else {
                                    overscrollX = 0f
                                    (offset.value.x + panX).coerceIn(-maxX, maxX)
                                }
                                val newY = (offset.value.y + panY).coerceIn(-maxY, maxY)
                                offset.value = Offset(newX, newY)
                            } else {
                                offset.value = clampOffset(offset.value + panChange, newScale, fittedSize.value, viewport.value)
                            }
                            event.changes.forEach { it.consume() }
                        }
                    } while (event.changes.any { it.pressed })

                    // 单指、几乎没移动 → 判定为一次点击
                    if (maxPointers == 1 && totalMove < viewConfig.touchSlop) {
                        currentRegisterTap(downPos)
                    }

                    // 放大后滑到边缘继续滑 → 松手翻页
                    if (maxPointers == 1 && scale.floatValue > 1f && viewport.value.width > 0) {
                        val threshold = viewport.value.width * 0.12f
                        if (abs(overscrollX) > threshold) {
                            val forward = if (reverseLayout) overscrollX > 0f else overscrollX < 0f
                            val target = (pagerState.currentPage + if (forward) 1 else -1)
                                .coerceIn(0, pageCount - 1)
                            scale.floatValue = 1f
                            offset.value = Offset.Zero
                            if (target != pagerState.currentPage) {
                                val t = target
                                scope.launch { pagerState.animateScrollToPage(t) }
                            }
                        }
                    } else if (maxPointers == 1 && scale.floatValue > 1f && lastVelocity.getDistance() > 500f) {
                        // 松手后按速度惯性滑动（放大浏览大图更省劲）
                        val v0 = lastVelocity
                        scope.launch {
                            var v = v0
                            while (v.getDistance() > 25f) {
                                offset.value = clampOffset(
                                    offset.value + Offset(v.x * 0.016f, v.y * 0.016f),
                                    scale.floatValue,
                                    fittedSize.value,
                                    viewport.value,
                                )
                                v = Offset(v.x * 0.92f, v.y * 0.92f)
                                delay(16)
                            }
                        }
                    }
                }
            },
        ) {
            val intrinsic = painter.intrinsicSize
            val vp = viewport.value
            if (intrinsic.width > 0f && intrinsic.height > 0f && vp.width > 0 && vp.height > 0) {
                val factor = minOf(vp.width / intrinsic.width, vp.height / intrinsic.height)
                val w = (intrinsic.width * factor).toInt().coerceAtLeast(1)
                val h = (intrinsic.height * factor).toInt().coerceAtLeast(1)
                fittedSize.value = IntSize(w, h)
                SubcomposeAsyncImageContent(
                    modifier = Modifier.size(with(density) { w.toDp() }, with(density) { h.toDp() }),
                )
            } else {
                SubcomposeAsyncImageContent()
            }
        }
    }
}

/** 把平移量限制在图片放大后刚好不脱出视口的范围（按图片实际 fit 尺寸算，1x 时强制归零）。 */
private fun clampOffset(o: Offset, scale: Float, fitted: IntSize, viewport: IntSize): Offset {
    if (fitted == IntSize.Zero || viewport == IntSize.Zero) return Offset.Zero
    val maxX = ((fitted.width * scale - viewport.width) / 2f).coerceAtLeast(0f)
    val maxY = ((fitted.height * scale - viewport.height) / 2f).coerceAtLeast(0f)
    return Offset(o.x.coerceIn(-maxX, maxX), o.y.coerceIn(-maxY, maxY))
}
