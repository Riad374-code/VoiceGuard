package com.guardvoice.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.guardvoice.R
import com.guardvoice.ui.model.RiskLevel
import com.guardvoice.ui.theme.GuardColors
import com.guardvoice.ui.theme.GuardRadius
import com.guardvoice.ui.theme.GuardSize
import com.guardvoice.ui.theme.GuardSpace

private const val PULSE_DURATION_MS = 1800
private const val RISK_SCORE_MAX = 100

@Composable
fun BrandHeader(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(GuardSpace.Medium),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ShieldMark()
        Column {
            Text(
                text = "GuardVoice",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = GuardColors.Ink
            )
            Text(
                text = "Consent-first call protection",
                style = MaterialTheme.typography.bodySmall,
                color = GuardColors.InkMuted
            )
        }
    }
}

@Composable
fun ShieldMark(modifier: Modifier = Modifier) {
    Image(
        painter = painterResource(R.drawable.guardvoice_logo),
        contentDescription = "GuardVoice",
        contentScale = ContentScale.Crop,
        modifier = modifier
            .size(GuardSize.BrandMark)
            .shadow(
                elevation = 10.dp,
                shape = RoundedCornerShape(GuardRadius.Medium),
                ambientColor = GuardColors.Navy.copy(alpha = 0.18f)
            )
            .clip(RoundedCornerShape(GuardRadius.Medium))
    )
}

@Composable
fun StatusPill(
    text: String,
    riskLevel: RiskLevel,
    modifier: Modifier = Modifier,
    isLive: Boolean = false
) {
    val transition = rememberInfiniteTransition(label = "status-pulse")
    val pulse by transition.animateFloat(
        initialValue = 0.82f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(
            animation = tween(PULSE_DURATION_MS),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse-scale"
    )
    Row(
        modifier = modifier
            .clip(CircleShape)
            .background(riskLevel.background)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .scale(if (isLive) pulse else 1f)
                .clip(CircleShape)
                .background(riskLevel.color)
        )
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = riskLevel.color
        )
    }
}

@Composable
fun AppSurface(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Box(
        modifier = modifier
            .shadow(
                elevation = 16.dp,
                shape = RoundedCornerShape(GuardRadius.Large),
                ambientColor = GuardColors.Navy.copy(alpha = 0.06f),
                spotColor = GuardColors.Navy.copy(alpha = 0.08f)
            )
            .clip(RoundedCornerShape(GuardRadius.Large))
            .background(GuardColors.Surface)
            .border(1.dp, GuardColors.Line, RoundedCornerShape(GuardRadius.Large))
            .padding(GuardSpace.Large)
    ) {
        content()
    }
}

@Composable
fun PrimaryAction(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Button(
        modifier = modifier.height(54.dp),
        onClick = onClick,
        shape = RoundedCornerShape(GuardRadius.Medium),
        colors = ButtonDefaults.buttonColors(
            containerColor = GuardColors.Navy,
            contentColor = GuardColors.Surface
        )
    ) {
        Text(text = text, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun SecondaryAction(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    OutlinedButton(
        modifier = modifier.height(54.dp),
        onClick = onClick,
        shape = RoundedCornerShape(GuardRadius.Medium),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = GuardColors.Ink)
    ) {
        Text(text = text, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun RiskMeter(
    score: Int,
    riskLevel: RiskLevel,
    modifier: Modifier = Modifier
) {
    val clampedScore = score.coerceIn(0, RISK_SCORE_MAX)
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom
        ) {
            Text(
                text = "Risk",
                style = MaterialTheme.typography.labelMedium,
                color = GuardColors.InkMuted
            )
            Text(
                text = "$clampedScore%",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Black,
                color = riskLevel.color
            )
        }
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(GuardSize.RiskMeterHeight)
                .clip(CircleShape)
                .background(GuardColors.SurfaceMuted)
        ) {
            val width = size.width * clampedScore / RISK_SCORE_MAX
            drawRoundRect(
                color = riskLevel.color,
                size = Size(width, size.height),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.height)
            )
        }
    }
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        modifier = modifier,
        text = text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Black,
        color = GuardColors.InkMuted,
        letterSpacing = 0.9.sp
    )
}

@Composable
fun TranscriptLine(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(GuardRadius.Medium))
            .background(GuardColors.SurfaceMuted)
            .padding(GuardSpace.Medium)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = GuardColors.Ink,
            maxLines = 4,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
fun BreathingWave(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "wave")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(PULSE_DURATION_MS), RepeatMode.Restart),
        label = "wave-phase"
    )
    Canvas(modifier = modifier.height(40.dp).fillMaxWidth()) {
        val centerY = size.height / 2
        val step = size.width / 18f
        for (index in 0..18) {
            val height = 8f + ((index + phase * 6f) % 6f) * 4f
            drawLine(
                color = GuardColors.Forest.copy(alpha = 0.24f + (index % 4) * 0.12f),
                start = Offset(index * step, centerY - height),
                end = Offset(index * step, centerY + height),
                strokeWidth = 5f,
                cap = StrokeCap.Round
            )
        }
    }
}

@Composable
fun OffsetBadge(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .offset(y = (-6).dp)
            .clip(CircleShape)
            .background(GuardColors.Ink)
            .padding(horizontal = 12.dp, vertical = 7.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = GuardColors.Surface,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
fun SmallDivider(modifier: Modifier = Modifier) {
    Spacer(
        modifier = modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(GuardColors.Line)
    )
}
