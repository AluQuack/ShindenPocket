package com.example.shindenpocket.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.shindenpocket.ui.theme.PocketPalette

/** Ikony czterech zakładek dolnej nawigacji rysowane na `Canvas`. */
@Composable
private fun TabSymbol(index: Int, selected: Boolean) {
    val tint = if (selected) PocketPalette.primary else PocketPalette.onSurfaceVariant
    Canvas(Modifier.size(22.dp)) {
        val k = size.minDimension / 24f
        val stroke = Stroke(width = 1.9f * k)
        when (index) {
            0 -> {
                val roof = Path().apply {
                    moveTo(2.5f * k, 11f * k)
                    lineTo(12f * k, 2.5f * k)
                    lineTo(21.5f * k, 11f * k)
                    lineTo(19.5f * k, 11f * k)
                    lineTo(19.5f * k, 21f * k)
                    lineTo(4.5f * k, 21f * k)
                    lineTo(4.5f * k, 11f * k)
                    close()
                }
                drawPath(roof, tint, style = if (selected) Fill else stroke)
                if (selected) {
                    drawRoundRect(
                        color = PocketPalette.surface,
                        topLeft = Offset(10f * k, 14f * k),
                        size = Size(4f * k, 7f * k),
                        cornerRadius = CornerRadius(1.2f * k, 1.2f * k)
                    )
                }
            }
            1 -> {
                val leftPane = Path().apply {
                    moveTo(3.5f * k, 4.5f * k)
                    lineTo(11f * k, 5.8f * k)
                    lineTo(11f * k, 20f * k)
                    lineTo(3.5f * k, 18.7f * k)
                    close()
                }
                val rightPane = Path().apply {
                    moveTo(20.5f * k, 4.5f * k)
                    lineTo(13f * k, 5.8f * k)
                    lineTo(13f * k, 20f * k)
                    lineTo(20.5f * k, 18.7f * k)
                    close()
                }
                drawPath(leftPane, tint, style = if (selected) Fill else stroke)
                drawPath(rightPane, tint, style = if (selected) Fill else stroke)
            }
            2 -> {
                drawRoundRect(
                    color = tint,
                    topLeft = Offset(3.5f * k, 5.5f * k),
                    size = Size(17f * k, 15.5f * k),
                    cornerRadius = CornerRadius(3f * k, 3f * k),
                    style = if (selected) Fill else stroke
                )
                drawLine(
                    color = tint,
                    start = Offset(8f * k, 3f * k),
                    end = Offset(8f * k, 6.5f * k),
                    strokeWidth = 2f * k
                )
                drawLine(
                    color = tint,
                    start = Offset(16f * k, 3f * k),
                    end = Offset(16f * k, 6.5f * k),
                    strokeWidth = 2f * k
                )
                val innerColor = if (selected) PocketPalette.surface else tint
                drawLine(
                    color = innerColor,
                    start = Offset(4.5f * k, 10f * k),
                    end = Offset(19.5f * k, 10f * k),
                    strokeWidth = 1.6f * k
                )
                drawCircle(innerColor, radius = 1.25f * k, center = Offset(8.5f * k, 14.5f * k))
                drawCircle(innerColor, radius = 1.25f * k, center = Offset(12f * k, 14.5f * k))
                drawCircle(innerColor, radius = 1.25f * k, center = Offset(15.5f * k, 14.5f * k))
            }
            else -> {
                drawCircle(
                    color = tint,
                    radius = 3.8f * k,
                    center = Offset(12f * k, 7.2f * k),
                    style = if (selected) Fill else stroke
                )
                if (selected) {
                    val body = Path().apply {
                        moveTo(4.5f * k, 20.5f * k)
                        cubicTo(4.5f * k, 14.5f * k, 19.5f * k, 14.5f * k, 19.5f * k, 20.5f * k)
                        close()
                    }
                    drawPath(body, tint, style = Fill)
                } else {
                    drawArc(
                        color = tint,
                        startAngle = 190f,
                        sweepAngle = 160f,
                        useCenter = false,
                        topLeft = Offset(4.5f * k, 13f * k),
                        size = Size(15f * k, 11f * k),
                        style = stroke
                    )
                }
            }
        }
    }
}

/** Dolna nawigacja z fioletowym wskaźnikiem aktywnej zakładki. */
@Composable
internal fun PocketNavigationBar(selectedTab: Int, onSelectTab: (Int) -> Unit) {
    val titles = listOf("Start", "Biblioteka", "Nowe Odcinki", "Konto")
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = PocketPalette.surface,
        border = BorderStroke(0.5.dp, PocketPalette.outlineVariant),
        shadowElevation = 16.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.navigationBars)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(66.dp)
                    .padding(horizontal = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                titles.forEachIndexed { index, title ->
                    val selected = index == selectedTab
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .selectable(selected = selected, role = Role.Tab) { onSelectTab(index) }
                            .semantics { contentDescription = title }
                    ) {
                        Column(
                            modifier = Modifier.fillMaxSize(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            TabSymbol(index, selected)
                            Spacer(Modifier.height(5.dp))
                            Text(
                                text = title,
                                color = if (selected) PocketPalette.primary else PocketPalette.onSurfaceVariant,
                                fontSize = 11.sp,
                                lineHeight = 13.sp,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        if (selected) {
                            Box(
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .fillMaxWidth(0.55f)
                                    .height(3.dp)
                                    .clip(CircleShape)
                                    .background(PocketPalette.primary)
                            )
                        }
                    }
                }
            }
        }
    }
}
