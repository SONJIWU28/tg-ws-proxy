package com.tgwsproxy.ui.compose

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size as GeoSize
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tgwsproxy.ui.model.ProxyScreenTab
import com.tgwsproxy.ui.model.ProxySettings
import com.tgwsproxy.ui.model.ProxyUiState

// ─────────────────────────────────────────────────────────────
// Палитра — соответствие C.* из HTML-прототипа
// ─────────────────────────────────────────────────────────────
private val Bg          = Color(0xFF08080F)
private val BgCard      = Color(0x0AFFFFFF)   // rgba(255,255,255,0.04)
private val BgCard2     = Color(0x10FFFFFF)   // rgba(255,255,255,0.065)
private val BorderCol   = Color(0x14FFFFFF)   // rgba(255,255,255,0.08)
private val BorderViolet= Color(0x408B5CF6)   // rgba(139,92,246,0.25)
private val TextMain    = Color(0xFFF0EEFF)
private val TextMid     = Color(0x8CF0EEFF)   // 0.55
private val TextLow     = Color(0x4DF0EEFF)   // 0.30
private val Violet      = Color(0xFF8B5CF6)
private val VioletDeep  = Color(0xFF4C1D95)
private val Cyan        = Color(0xFF22D3EE)
private val CyanDeep    = Color(0xFF0E7490)
private val Red         = Color(0xFFF43F5E)
private val Green       = Color(0xFF10B981)
private val Amber       = Color(0xFFF59E0B)

// ─────────────────────────────────────────────────────────────
// Корневой экран
// ─────────────────────────────────────────────────────────────
@Composable
fun ProxyNativeScreen(
    state: ProxyUiState,
    onTabChange: (ProxyScreenTab) -> Unit,
    onSettingsChange: (ProxySettings) -> Unit,
    onGenerateSecret: () -> Unit,
    onStartStop: () -> Unit,
    onRestart: () -> Unit,
    onAddToTelegram: () -> Unit,
    onCopyProxy: () -> Unit
) {
    MaterialTheme {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Bg
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                AmbientBackground()
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .windowInsetsPadding(WindowInsets.systemBars)
                ) {
                    Header()
                    TopTabs(
                        active = state.activeTab,
                        onSelect = onTabChange
                    )
                    AnimatedContent(
                        targetState = state.activeTab,
                        transitionSpec = {
                            (fadeIn(tween(220)) togetherWith fadeOut(tween(140)))
                        },
                        label = "tab"
                    ) { tab ->
                        when (tab) {
                            ProxyScreenTab.Main -> MainTab(
                                state, onStartStop, onRestart, onAddToTelegram, onCopyProxy
                            )
                            ProxyScreenTab.Settings -> SettingsTab(
                                state.settings, onSettingsChange, onGenerateSecret
                            )
                        }
                    }
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────
// Верхний бар: логотип-бейдж с glow + две иконки (Share, Settings)
// ─────────────────────────────────────────────────────────────
@Composable
private fun Header() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Лого с glow-тенью: shadow до clip
            Box(
                modifier = Modifier
                    .shadow(
                        elevation = 12.dp,
                        shape = RoundedCornerShape(10.dp),
                        ambientColor = Violet,
                        spotColor = Violet
                    )
                    .size(34.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Brush.linearGradient(listOf(Violet, VioletDeep))),
                contentAlignment = Alignment.Center
            ) {
                PowerIcon(size = 16.dp, color = Cyan, strokeWidth = 2f)
            }
            Spacer(Modifier.width(10.dp))
            Column {
                Text(
                    "MTProto",
                    color = TextMain,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    letterSpacing = (-0.2).sp,
                    fontFamily = FontFamily.SansSerif
                )
                Text(
                    "PROXY",
                    color = TextLow,
                    fontWeight = FontWeight.Medium,
                    fontSize = 10.sp,
                    letterSpacing = 1.6.sp,
                    fontFamily = FontFamily.SansSerif
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            HeaderIconButton { ShareIcon(size = 18.dp, color = TextMid) }
            HeaderIconButton { SettingsGearIcon(size = 18.dp, color = TextMid) }
        }
    }
}

@Composable
private fun HeaderIconButton(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0x0FFFFFFF)),
        contentAlignment = Alignment.Center
    ) { content() }
}

// ─────────────────────────────────────────────────────────────
// Кастомный TabBar (pill-переключатель с градиентом на активном)
// ─────────────────────────────────────────────────────────────
@Composable
private fun TopTabs(active: ProxyScreenTab, onSelect: (ProxyScreenTab) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0x0AFFFFFF))
            .border(1.dp, Color(0x12FFFFFF), RoundedCornerShape(12.dp))
            .padding(3.dp)
    ) {
        TabPill(
            text = "Главная",
            selected = active == ProxyScreenTab.Main,
            modifier = Modifier.weight(1f)
        ) { onSelect(ProxyScreenTab.Main) }
        TabPill(
            text = "Настройки",
            selected = active == ProxyScreenTab.Settings,
            modifier = Modifier.weight(1f)
        ) { onSelect(ProxyScreenTab.Settings) }
    }
}

@Composable
private fun TabPill(
    text: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val bgMod = if (selected) {
        Modifier
            .shadow(
                elevation = 6.dp,
                shape = RoundedCornerShape(9.dp),
                ambientColor = Violet,
                spotColor = Violet
            )
            .background(
                Brush.linearGradient(listOf(Violet.copy(alpha = 0.75f), VioletDeep.copy(alpha = 0.85f))),
                RoundedCornerShape(9.dp)
            )
    } else Modifier

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(9.dp))
            .then(bgMod)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
            .padding(vertical = 9.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text,
            color = if (selected) TextMain else TextLow,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.6.sp,
            fontFamily = FontFamily.SansSerif
        )
    }
}

// ─────────────────────────────────────────────────────────────
// ГЛАВНАЯ ВКЛАДКА
// ─────────────────────────────────────────────────────────────
@Composable
private fun MainTab(
    state: ProxyUiState,
    onStartStop: () -> Unit,
    onRestart: () -> Unit,
    onAddToTelegram: () -> Unit,
    onCopyProxy: () -> Unit
) {
    val accent = when {
        state.connecting -> Amber
        state.running    -> Cyan
        else             -> Violet
    }
    val dotColor = when {
        state.connecting -> Amber
        state.running    -> Green
        else             -> TextLow
    }
    val bgTint = when {
        state.connecting -> Color(0x14F59E0B)   // amber 0.08
        state.running    -> Color(0x1422D3EE)   // cyan  0.08
        else             -> Color(0x128B5CF6)   // violet 0.07
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // ── Hero: радиальный градиент, схема сети, Orb, статус, сервер ──
        item {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .border(1.dp, accent.copy(alpha = 0.2f), RoundedCornerShape(20.dp))
                    .drawBehind {
                        // radial-gradient at 50% 0%
                        drawCircle(
                            brush = Brush.radialGradient(
                                colors = listOf(bgTint, Color.Transparent),
                                center = Offset(size.width * 0.5f, 0f),
                                radius = size.width * 0.9f
                            ),
                            radius = size.width * 0.9f,
                            center = Offset(size.width * 0.5f, 0f)
                        )
                    }
            ) {
                // Фоновая схема сети (за орбом)
                AnimatedNetworkGrid(accent = accent)

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 26.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    PowerOrb(
                        running = state.running,
                        connecting = state.connecting,
                        accent = accent,
                        onTap = onStartStop
                    )
                    StatusLabel(label = state.statusLabel, dot = dotColor)
                    ServerChip(text = state.settings.dcIp, dot = dotColor)
                }
            }
        }

        // ── Ряд быстрых действий ──
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                QuickActionButton(
                    title = "Рестарт",
                    icon = { RefreshIcon(size = 17.dp, color = if (state.running) Violet else TextLow) },
                    accent = state.running,
                    enabled = state.running,
                    onClick = onRestart,
                    modifier = Modifier.weight(1f)
                )
                QuickActionButton(
                    title = "В Telegram",
                    icon = { TelegramIcon(size = 17.dp, color = Cyan) },
                    accent = true,
                    enabled = true,
                    onClick = onAddToTelegram,
                    modifier = Modifier.weight(1f),
                    accentColor = Cyan
                )
                QuickActionButton(
                    title = "Скопировать",
                    icon = { CopyIcon(size = 17.dp, color = TextMid) },
                    accent = false,
                    enabled = true,
                    onClick = onCopyProxy,
                    modifier = Modifier.weight(1f)
                )
            }
        }

        // ── Метрики (при running) ──
        if (state.running) {
            item {
                Card {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(0.dp)
                    ) {
                        Metric("Порт", state.settings.port, Modifier.weight(1f))
                        Metric("Хост", state.settings.host, Modifier.weight(1f))
                        Metric("DC", state.settings.dcIp.substringBefore(':'), Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────
// ВКЛАДКА НАСТРОЙКИ
// ─────────────────────────────────────────────────────────────
@Composable
private fun SettingsTab(
    settings: ProxySettings,
    onSettingsChange: (ProxySettings) -> Unit,
    onGenerateSecret: () -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            CardTitled(title = "Подключение", icon = { ArrowRightIcon(size = 13.dp, color = Violet) }) {
                AppField(
                    label = "Сервер / DC",
                    value = settings.dcIp,
                    mono = true,
                    copyable = true
                ) { onSettingsChange(settings.copy(dcIp = it)) }
            }
        }
        item {
            CardTitled(title = "Локальный прокси", icon = { MonitorIcon(size = 13.dp, color = Cyan) }) {
                AppField(label = "Хост", value = settings.host, mono = true) {
                    onSettingsChange(settings.copy(host = it))
                }
                Spacer(Modifier.height(4.dp))
                AppField(
                    label = "Порт",
                    value = settings.port,
                    mono = true,
                    keyboardType = KeyboardType.Number
                ) { onSettingsChange(settings.copy(port = it)) }
            }
        }
        item {
            CardTitled(title = "Безопасность", icon = { KeyIcon(size = 13.dp, color = Violet) }) {
                AppField(
                    label = "MTProto Secret",
                    value = settings.secret,
                    mono = true,
                    copyable = true
                ) { onSettingsChange(settings.copy(secret = it)) }
                Spacer(Modifier.height(8.dp))
                GradientButton(
                    text = "Сгенерировать Secret",
                    onClick = onGenerateSecret
                )
            }
        }
        item {
            CardTitled(title = "Маршрутизация", icon = { CloudIcon(size = 13.dp, color = Cyan) }) {
                SettingSwitchRow(
                    title = "Cloudflare Proxy",
                    subtitle = "Маршрут через CF CDN",
                    value = settings.cfProxyEnabled
                ) { onSettingsChange(settings.copy(cfProxyEnabled = it)) }
                AnimatedVisibility(visible = settings.cfProxyEnabled) {
                    Column {
                        Spacer(Modifier.height(10.dp))
                        AppField(
                            label = "CF Домен",
                            value = settings.cfProxyDomain,
                            placeholder = "example.workers.dev"
                        ) { onSettingsChange(settings.copy(cfProxyDomain = it)) }
                    }
                }
            }
        }
        item {
            CardTitled(title = "Производительность", icon = { BatteryIcon(size = 13.dp, color = Amber) }) {
                SettingSwitchRow(
                    title = "Экономия батареи",
                    subtitle = "Пауза при выкл. экране",
                    value = settings.batterySaver
                ) { onSettingsChange(settings.copy(batterySaver = it)) }
            }
        }
        item { Spacer(Modifier.height(4.dp)) }
    }
}

// ─────────────────────────────────────────────────────────────
// PowerOrb: три кольца + градиентная сфера с пульсом
// ─────────────────────────────────────────────────────────────
@Composable
private fun PowerOrb(
    running: Boolean,
    connecting: Boolean,
    accent: Color,
    onTap: () -> Unit
) {
    val orbColor = when {
        connecting -> Amber
        running    -> Cyan
        else       -> Violet
    }
    val transition = rememberInfiniteTransition(label = "orb")
    val pulse by transition.animateFloat(
        initialValue = 0.97f,
        targetValue = 1.05f,
        animationSpec = infiniteRepeatable(
            animation = tween(if (running) 2500 else 3000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )
    val glowAlpha by transition.animateFloat(
        initialValue = 0.15f,
        targetValue = 0.45f,
        animationSpec = infiniteRepeatable(
            animation = tween(if (running) 2500 else 3000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "glowAlpha"
    )
    // Вращающееся кольцо-спиннер (активно только во время connecting)
    val spinnerAngle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "spinner"
    )

    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()

    Box(
        modifier = Modifier.size(200.dp),
        contentAlignment = Alignment.Center
    ) {
        // Внешнее кольцо
        Box(
            modifier = Modifier
                .size(186.dp)
                .clip(CircleShape)
                .border(1.dp, orbColor.copy(alpha = 0.12f), CircleShape)
        )
        // Внутреннее кольцо
        Box(
            modifier = Modifier
                .size(158.dp)
                .clip(CircleShape)
                .border(1.dp, orbColor.copy(alpha = 0.22f), CircleShape)
        )
        // Наружное glow-свечение
        Canvas(modifier = Modifier.size(180.dp)) {
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(orbColor.copy(alpha = glowAlpha), Color.Transparent),
                    center = center,
                    radius = size.minDimension / 2f
                ),
                radius = size.minDimension / 2f
            )
        }
        // Спиннер при подключении: дуга-хвост + яркая голова, вращается вокруг орба
        if (connecting) {
            Canvas(modifier = Modifier.size(150.dp)) {
                val stroke = 3.dp.toPx()
                val inset = stroke / 2f
                val tlx = inset
                val tly = inset
                val w = size.width - stroke
                val h = size.height - stroke
                // Полупрозрачный хвост (длина 110°)
                drawArc(
                    color = orbColor.copy(alpha = 0.22f),
                    startAngle = spinnerAngle,
                    sweepAngle = 110f,
                    useCenter = false,
                    topLeft = Offset(tlx, tly),
                    size = GeoSize(w, h),
                    style = Stroke(width = stroke, cap = StrokeCap.Round)
                )
                // Яркая голова (короткая дуга 28° на конце)
                drawArc(
                    color = orbColor,
                    startAngle = spinnerAngle + 82f,
                    sweepAngle = 28f,
                    useCenter = false,
                    topLeft = Offset(tlx, tly),
                    size = GeoSize(w, h),
                    style = Stroke(width = stroke, cap = StrokeCap.Round)
                )
            }
        }
        // Основная сфера
        Box(
            modifier = Modifier
                .scale(if (pressed) 0.93f else pulse)
                .size(130.dp)
                .clip(CircleShape)
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            orbColor.copy(alpha = 0.35f),
                            when {
                                connecting -> Color(0xFF78350F).copy(alpha = 0.55f)
                                running    -> CyanDeep.copy(alpha = 0.65f)
                                else       -> VioletDeep.copy(alpha = 0.55f)
                            }
                        )
                    )
                )
                .border(2.dp, orbColor.copy(alpha = 0.35f), CircleShape)
                .clickable(
                    interactionSource = interaction,
                    indication = null,
                    onClick = onTap
                ),
            contentAlignment = Alignment.Center
        ) {
            // Inner glow сверху
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(orbColor.copy(alpha = 0.25f), Color.Transparent),
                        center = Offset(size.width * 0.5f, size.height * 0.3f),
                        radius = size.minDimension * 0.7f
                    )
                )
            }
            PowerIcon(size = 36.dp, color = orbColor, strokeWidth = 2f)
        }
    }
}

// ─────────────────────────────────────────────────────────────
// Анимированная схема сети: 4 угловых узла, линии идут между
// углами (а не к центру), обходя круг орба
// ─────────────────────────────────────────────────────────────
@Composable
private fun AnimatedNetworkGrid(accent: Color) {
    val transition = rememberInfiniteTransition(label = "net")
    val offset by transition.animateFloat(
        initialValue = 0f,
        targetValue = 32f,
        animationSpec = infiniteRepeatable(
            animation = tween(3500, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "dashOffset"
    )
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(320.dp)
    ) {
        val w = size.width
        val h = size.height
        val center = Offset(w * 0.5f, h * 0.5f)

        // Мягкий радиальный glow позади орба
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(accent.copy(alpha = 0.10f), Color.Transparent),
                center = center,
                radius = w * 0.55f
            ),
            radius = w * 0.55f,
            center = center
        )

        val corners = listOf(
            Offset(w * 0.15f, h * 0.20f),   // top-left
            Offset(w * 0.85f, h * 0.15f),   // top-right
            Offset(w * 0.25f, h * 0.85f),   // bottom-left
            Offset(w * 0.82f, h * 0.88f)    // bottom-right
        )
        // Рёбра между угловыми узлами (как в HTML-референсе: не через центр)
        val edges = listOf(
            0 to 1,   // верх
            2 to 3,   // низ
            0 to 2,   // лево
            1 to 3,   // право
            0 to 3,   // диагональ ↘
            1 to 2    // диагональ ↙
        )
        // Радиус "обхода" орба: линии, проходящие ближе чем orbRadius к центру,
        // разрываются на два сегмента, чтобы визуально не врезаться в сферу.
        val orbRadius = 95.dp.toPx()
        val dash = PathEffect.dashPathEffect(
            floatArrayOf(6.dp.toPx(), 10.dp.toPx()),
            offset.dp.toPx()
        )
        val lineColor = accent.copy(alpha = 0.32f)
        val strokeW = 1.dp.toPx()

        edges.forEach { (a, b) ->
            val p1 = corners[a]
            val p2 = corners[b]
            val segs = segmentsAvoidingCircle(p1, p2, center, orbRadius)
            segs.forEach { (s, e) ->
                drawLine(
                    color = lineColor,
                    start = s,
                    end = e,
                    strokeWidth = strokeW,
                    cap = StrokeCap.Round,
                    pathEffect = dash
                )
            }
        }

        // Точки углов (две концентрические — свечение + ядро)
        corners.forEach { c ->
            drawCircle(color = accent.copy(alpha = 0.25f), radius = 9.dp.toPx(), center = c)
            drawCircle(color = accent.copy(alpha = 0.85f), radius = 3.5.dp.toPx(), center = c)
        }
    }
}

/**
 * Возвращает отрезки исходной линии (p1..p2), из которых вырезан
 * проход через круг (center, radius). Если линия не пересекает круг —
 * возвращается единственный отрезок как есть.
 */
private fun segmentsAvoidingCircle(
    p1: Offset,
    p2: Offset,
    center: Offset,
    radius: Float
): List<Pair<Offset, Offset>> {
    val dx = p2.x - p1.x
    val dy = p2.y - p1.y
    val fx = p1.x - center.x
    val fy = p1.y - center.y
    val a = dx * dx + dy * dy
    val b = 2f * (fx * dx + fy * dy)
    val c = fx * fx + fy * fy - radius * radius
    val disc = b * b - 4f * a * c
    if (disc < 0f || a == 0f) return listOf(p1 to p2)
    val sq = kotlin.math.sqrt(disc)
    val t1 = (-b - sq) / (2f * a)
    val t2 = (-b + sq) / (2f * a)
    // Оба t должны попасть в [0..1], иначе линия не пересекает круг на участке
    val inside1 = t1 in 0f..1f
    val inside2 = t2 in 0f..1f
    if (!inside1 && !inside2) return listOf(p1 to p2)
    val result = mutableListOf<Pair<Offset, Offset>>()
    val enter = Offset(p1.x + dx * t1, p1.y + dy * t1)
    val exit  = Offset(p1.x + dx * t2, p1.y + dy * t2)
    if (inside1) result.add(p1 to enter)
    if (inside2) result.add(exit to p2)
    return result
}

// ─────────────────────────────────────────────────────────────
// Статус-лейбл и чип с IP
// ─────────────────────────────────────────────────────────────
@Composable
private fun StatusLabel(label: String, dot: Color) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Glow dot
        Box(modifier = Modifier.size(12.dp), contentAlignment = Alignment.Center) {
            Canvas(modifier = Modifier.size(12.dp)) {
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(dot.copy(alpha = 0.7f), Color.Transparent),
                        radius = size.minDimension / 2f
                    )
                )
            }
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(dot)
            )
        }
        Text(
            label,
            color = TextMain,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            fontFamily = FontFamily.SansSerif
        )
    }
}

@Composable
private fun ServerChip(text: String, dot: Color) {
    Row(
        modifier = Modifier
            .clip(CircleShape)
            .background(Color(0x40000000))
            .padding(horizontal = 14.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(dot)
        )
        Text(
            text,
            color = TextMid,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace
        )
    }
}

// ─────────────────────────────────────────────────────────────
// Кнопки быстрых действий (квадратные, с pressable-scale)
// ─────────────────────────────────────────────────────────────
@Composable
private fun QuickActionButton(
    title: String,
    icon: @Composable () -> Unit,
    accent: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    accentColor: Color = Violet
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()

    val (bg, border) = when {
        !enabled -> Color(0x0AFFFFFF) to BorderCol
        accent   -> accentColor.copy(alpha = 0.15f) to accentColor.copy(alpha = 0.32f)
        else     -> Color(0x0DFFFFFF) to BorderCol
    }

    Column(
        modifier = modifier
            .scale(if (pressed) 0.94f else 1f)
            .clip(RoundedCornerShape(14.dp))
            .background(bg)
            .border(1.dp, border, RoundedCornerShape(14.dp))
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                onClick = onClick
            )
            .padding(vertical = 12.dp, horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        icon()
        Text(
            title,
            color = if (enabled) TextMid else TextLow,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
            fontFamily = FontFamily.SansSerif
        )
    }
}

// ─────────────────────────────────────────────────────────────
// Карточки и поля
// ─────────────────────────────────────────────────────────────
@Composable
private fun Card(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(BgCard)
            .border(1.dp, BorderCol, RoundedCornerShape(16.dp))
            .padding(horizontal = 16.dp, vertical = 14.dp),
        content = content
    )
}

@Composable
private fun CardTitled(
    title: String,
    icon: @Composable () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    Card {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp),
            modifier = Modifier.padding(bottom = 12.dp)
        ) {
            icon()
            Text(
                title.uppercase(),
                color = TextMid,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.3.sp,
                fontFamily = FontFamily.SansSerif
            )
        }
        content()
    }
}

@Composable
private fun Metric(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            value,
            color = TextMain,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            maxLines = 1
        )
        Spacer(Modifier.height(3.dp))
        Text(
            label,
            color = TextLow,
            fontSize = 10.sp,
            fontFamily = FontFamily.SansSerif
        )
    }
}

@Composable
private fun AppField(
    label: String,
    value: String,
    mono: Boolean = false,
    copyable: Boolean = false,
    placeholder: String = "",
    keyboardType: KeyboardType = KeyboardType.Text,
    onValueChange: (String) -> Unit
) {
    val context = LocalContext.current
    var justCopied by remember { mutableStateOf(false) }
    LaunchedEffect(justCopied) {
        if (justCopied) {
            kotlinx.coroutines.delay(1200)
            justCopied = false
        }
    }

    Column(modifier = Modifier.padding(bottom = 4.dp)) {
        Text(
            label.uppercase(),
            color = TextMid,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.8.sp,
            fontFamily = FontFamily.SansSerif
        )
        Spacer(Modifier.height(5.dp))
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(10.dp),
            textStyle = TextStyle(
                color = TextMain,
                fontSize = 13.sp,
                fontFamily = if (mono) FontFamily.Monospace else FontFamily.SansSerif
            ),
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            placeholder = { if (placeholder.isNotEmpty()) Text(placeholder, color = TextLow, fontSize = 13.sp) },
            trailingIcon = if (copyable) {
                {
                    IconButton(
                        onClick = {
                            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            cm.setPrimaryClip(ClipData.newPlainText(label, value))
                            justCopied = true
                            android.widget.Toast.makeText(context, "Скопировано", android.widget.Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.size(40.dp)
                    ) {
                        if (justCopied) {
                            CheckIcon(size = 16.dp, color = Cyan)
                        } else {
                            CopyIcon(size = 16.dp, color = TextMid)
                        }
                    }
                }
            } else null,
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = Color(0x0DFFFFFF),
                unfocusedContainerColor = Color(0x0DFFFFFF),
                focusedBorderColor = Violet.copy(alpha = 0.4f),
                unfocusedBorderColor = BorderCol,
                cursorColor = Cyan,
                focusedTextColor = TextMain,
                unfocusedTextColor = TextMain
            )
        )
    }
}

@Composable
private fun SettingSwitchRow(
    title: String,
    subtitle: String,
    value: Boolean,
    onChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = TextMain, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(2.dp))
            Text(subtitle, color = TextLow, fontSize = 11.sp)
        }
        Switch(
            checked = value,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = Violet,
                checkedBorderColor = Violet,
                uncheckedThumbColor = TextMid,
                uncheckedTrackColor = Color(0x1AFFFFFF),
                uncheckedBorderColor = BorderCol
            )
        )
    }
}

@Composable
private fun GradientButton(text: String, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .scale(if (pressed) 0.97f else 1f)
            .clip(RoundedCornerShape(12.dp))
            .background(
                Brush.linearGradient(
                    listOf(Violet.copy(alpha = 0.28f), VioletDeep.copy(alpha = 0.42f))
                )
            )
            .border(1.dp, Violet.copy(alpha = 0.35f), RoundedCornerShape(12.dp))
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text.uppercase(),
            color = Violet,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.8.sp,
            fontFamily = FontFamily.SansSerif
        )
    }
}

// ─────────────────────────────────────────────────────────────
// Амбиентный фон всего экрана — верхнее фиолетовое свечение
// ─────────────────────────────────────────────────────────────
@Composable
private fun AmbientBackground() {
    Canvas(modifier = Modifier.fillMaxSize()) {
        drawRect(Bg)
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(Violet.copy(alpha = 0.18f), Color.Transparent),
                center = Offset(size.width * 0.5f, size.height * 0.04f),
                radius = size.width * 0.75f
            ),
            radius = size.width * 0.65f,
            center = Offset(size.width * 0.5f, size.height * 0.04f)
        )
    }
}

// ─────────────────────────────────────────────────────────────
// ИКОНКИ — кастомный Canvas (чтобы не тянуть зависимости)
// ─────────────────────────────────────────────────────────────
@Composable
private fun PowerIcon(size: Dp, color: Color, strokeWidth: Float = 2f) {
    Canvas(modifier = Modifier.size(size)) {
        val sw = strokeWidth.dp.toPx()
        val cx = this.size.width / 2f
        val cy = this.size.height / 2f
        val r  = this.size.minDimension * 0.36f
        // Круг-дуга (открытая сверху)
        drawArc(
            color = color,
            startAngle = -60f,
            sweepAngle = 300f,
            useCenter = false,
            topLeft = Offset(cx - r, cy - r),
            size = GeoSize(r * 2, r * 2),
            style = Stroke(width = sw, cap = StrokeCap.Round)
        )
        // Вертикальная черта
        drawLine(
            color = color,
            start = Offset(cx, cy - this.size.minDimension * 0.44f),
            end   = Offset(cx, cy - this.size.minDimension * 0.08f),
            strokeWidth = sw,
            cap = StrokeCap.Round
        )
    }
}

@Composable
private fun RefreshIcon(size: Dp, color: Color) {
    Canvas(modifier = Modifier.size(size)) {
        val sw = 2.2.dp.toPx()
        val cx = this.size.width / 2f
        val cy = this.size.height / 2f
        val r  = this.size.minDimension * 0.34f
        drawArc(
            color = color,
            startAngle = 30f,
            sweepAngle = 240f,
            useCenter = false,
            topLeft = Offset(cx - r, cy - r),
            size = GeoSize(r * 2, r * 2),
            style = Stroke(width = sw, cap = StrokeCap.Round)
        )
        // Стрелочка сверху-справа
        val arrowX = cx + r * 0.86f
        val arrowY = cy - r * 0.5f
        drawLine(color, Offset(arrowX - r * 0.3f, arrowY - r * 0.3f), Offset(arrowX, arrowY), sw, StrokeCap.Round)
        drawLine(color, Offset(arrowX, arrowY), Offset(arrowX + r * 0.1f, arrowY - r * 0.4f), sw, StrokeCap.Round)
    }
}

@Composable
private fun TelegramIcon(size: Dp, color: Color) {
    // Бумажный самолётик: треугольник + складка
    Canvas(modifier = Modifier.size(size)) {
        val sw = 2.dp.toPx()
        val w = this.size.width
        val h = this.size.height
        val path = Path().apply {
            moveTo(w * 0.08f, h * 0.52f)
            lineTo(w * 0.92f, h * 0.10f)
            lineTo(w * 0.70f, h * 0.92f)
            lineTo(w * 0.48f, h * 0.62f)
            close()
        }
        drawPath(path, color = color)
        drawLine(
            color = Color.Black.copy(alpha = 0.18f),
            start = Offset(w * 0.48f, h * 0.62f),
            end = Offset(w * 0.92f, h * 0.10f),
            strokeWidth = sw
        )
    }
}

@Composable
private fun CopyIcon(size: Dp, color: Color) {
    Canvas(modifier = Modifier.size(size)) {
        val sw = 1.8.dp.toPx()
        val w = this.size.width
        val h = this.size.height
        val rx = 2.dp.toPx()
        // Задний квадрат
        drawRoundRect(
            color = color,
            topLeft = Offset(w * 0.08f, h * 0.08f),
            size = GeoSize(w * 0.58f, h * 0.58f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(rx, rx),
            style = Stroke(width = sw)
        )
        // Передний квадрат
        drawRoundRect(
            color = color,
            topLeft = Offset(w * 0.36f, h * 0.36f),
            size = GeoSize(w * 0.58f, h * 0.58f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(rx, rx),
            style = Stroke(width = sw)
        )
    }
}

@Composable
private fun CheckIcon(size: Dp, color: Color) {
    Canvas(modifier = Modifier.size(size)) {
        val sw = 2.2.dp.toPx()
        val w = this.size.width
        val h = this.size.height
        drawLine(
            color = color,
            start = Offset(w * 0.18f, h * 0.52f),
            end = Offset(w * 0.42f, h * 0.76f),
            strokeWidth = sw,
            cap = StrokeCap.Round
        )
        drawLine(
            color = color,
            start = Offset(w * 0.42f, h * 0.76f),
            end = Offset(w * 0.84f, h * 0.28f),
            strokeWidth = sw,
            cap = StrokeCap.Round
        )
    }
}

@Composable
private fun ShareIcon(size: Dp, color: Color) {
    Canvas(modifier = Modifier.size(size)) {
        val sw = 2.dp.toPx()
        val w = this.size.width
        val h = this.size.height
        val r = w * 0.12f
        val p1 = Offset(w * 0.78f, h * 0.20f)
        val p2 = Offset(w * 0.22f, h * 0.50f)
        val p3 = Offset(w * 0.78f, h * 0.80f)
        // Линии
        drawLine(color, p2, p1, sw, StrokeCap.Round)
        drawLine(color, p2, p3, sw, StrokeCap.Round)
        // Кружки
        drawCircle(color, r, p1, style = Stroke(width = sw))
        drawCircle(color, r, p2, style = Stroke(width = sw))
        drawCircle(color, r, p3, style = Stroke(width = sw))
    }
}

@Composable
private fun SettingsGearIcon(size: Dp, color: Color) {
    Canvas(modifier = Modifier.size(size)) {
        val sw = 2.dp.toPx()
        val w = this.size.width
        val h = this.size.height
        val cx = w / 2f
        val cy = h / 2f
        // Центральный круг
        drawCircle(color, radius = w * 0.18f, center = Offset(cx, cy), style = Stroke(width = sw))
        // 8 "зубцов"
        val outer = w * 0.44f
        val inner = w * 0.28f
        for (i in 0 until 8) {
            val a = Math.toRadians((i * 45).toDouble())
            val x1 = cx + (Math.cos(a) * inner).toFloat()
            val y1 = cy + (Math.sin(a) * inner).toFloat()
            val x2 = cx + (Math.cos(a) * outer).toFloat()
            val y2 = cy + (Math.sin(a) * outer).toFloat()
            drawLine(color, Offset(x1, y1), Offset(x2, y2), sw, StrokeCap.Round)
        }
    }
}

@Composable
private fun KeyIcon(size: Dp, color: Color) {
    Canvas(modifier = Modifier.size(size)) {
        val sw = 2.dp.toPx()
        val w = this.size.width
        val h = this.size.height
        // Кольцо ключа
        drawCircle(color, radius = w * 0.22f, center = Offset(w * 0.3f, h * 0.6f), style = Stroke(width = sw))
        // Стержень
        drawLine(color, Offset(w * 0.48f, h * 0.55f), Offset(w * 0.92f, h * 0.55f), sw, StrokeCap.Round)
        // Зубцы
        drawLine(color, Offset(w * 0.92f, h * 0.55f), Offset(w * 0.92f, h * 0.75f), sw, StrokeCap.Round)
        drawLine(color, Offset(w * 0.78f, h * 0.55f), Offset(w * 0.78f, h * 0.70f), sw, StrokeCap.Round)
    }
}

@Composable
private fun CloudIcon(size: Dp, color: Color) {
    Canvas(modifier = Modifier.size(size)) {
        val sw = 2.dp.toPx()
        val w = this.size.width
        val h = this.size.height
        // Облако — две дуги
        drawCircle(color, radius = w * 0.22f, center = Offset(w * 0.35f, h * 0.55f), style = Stroke(width = sw))
        drawCircle(color, radius = w * 0.18f, center = Offset(w * 0.62f, h * 0.45f), style = Stroke(width = sw))
        drawLine(color, Offset(w * 0.2f, h * 0.78f), Offset(w * 0.85f, h * 0.78f), sw, StrokeCap.Round)
    }
}

@Composable
private fun BatteryIcon(size: Dp, color: Color) {
    Canvas(modifier = Modifier.size(size)) {
        val sw = 2.dp.toPx()
        val w = this.size.width
        val h = this.size.height
        drawRect(
            color = color,
            topLeft = Offset(w * 0.08f, h * 0.30f),
            size = GeoSize(w * 0.76f, h * 0.46f),
            style = Stroke(width = sw)
        )
        drawLine(color, Offset(w * 0.88f, h * 0.44f), Offset(w * 0.88f, h * 0.62f), sw, StrokeCap.Round)
    }
}

@Composable
private fun ArrowRightIcon(size: Dp, color: Color) {
    Canvas(modifier = Modifier.size(size)) {
        val sw = 2.5.dp.toPx()
        val w = this.size.width
        val h = this.size.height
        drawLine(color, Offset(w * 0.15f, h * 0.5f), Offset(w * 0.85f, h * 0.5f), sw, StrokeCap.Round)
        drawLine(color, Offset(w * 0.55f, h * 0.25f), Offset(w * 0.85f, h * 0.5f), sw, StrokeCap.Round)
        drawLine(color, Offset(w * 0.55f, h * 0.75f), Offset(w * 0.85f, h * 0.5f), sw, StrokeCap.Round)
    }
}

@Composable
private fun MonitorIcon(size: Dp, color: Color) {
    Canvas(modifier = Modifier.size(size)) {
        val sw = 2.5.dp.toPx()
        val w = this.size.width
        val h = this.size.height
        drawRect(
            color = color,
            topLeft = Offset(w * 0.08f, h * 0.15f),
            size = GeoSize(w * 0.84f, h * 0.55f),
            style = Stroke(width = sw)
        )
        drawLine(color, Offset(w * 0.33f, h * 0.88f), Offset(w * 0.67f, h * 0.88f), sw, StrokeCap.Round)
        drawLine(color, Offset(w * 0.5f, h * 0.70f), Offset(w * 0.5f, h * 0.88f), sw, StrokeCap.Round)
    }
}