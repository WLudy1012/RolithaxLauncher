package com.wludy.rolithax.launcher.ui.splash

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.wludy.rolithax.launcher.R
import com.wludy.rolithax.launcher.ui.theme.BrandSulfur
import kotlinx.coroutines.delay

/** 硫磺底色上的正文色：白色在该底色上对比度仅约 2.6:1，改用深色以保证可读。 */
private val OnBrandSulfur = Color(0xFF161A08)

/**
 * 应用内品牌开屏动画(承接系统 SplashScreen 的硫磺史莱姆黄绿背景，
 * 图标淡入 + 弹性缩放 + 应用名浮现)。
 */
@Composable
fun SplashScreen(onFinished: () -> Unit) {
    var started by remember { mutableStateOf(false) }

    val scale by animateFloatAsState(
        targetValue = if (started) 1f else 0.6f,
        animationSpec = tween(durationMillis = 700, easing = FastOutSlowInEasing),
        label = "logoScale",
    )
    val alpha by animateFloatAsState(
        targetValue = if (started) 1f else 0f,
        animationSpec = tween(durationMillis = 550),
        label = "logoAlpha",
    )

    LaunchedEffect(Unit) {
        started = true
        delay(1600)
        onFinished()
    }

    // 纯品牌底色铺满整屏（含状态栏/导航栏区域），内容居中，不会被系统栏遮挡
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BrandSulfur),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Image(
                painter = painterResource(R.drawable.slime_logo),
                contentDescription = "Rolithax",
                modifier = Modifier
                    .size(144.dp)
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        this.alpha = alpha
                    },
            )
            Spacer(Modifier.height(20.dp))
            Text(
                text = "Rolithax Launcher",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                color = OnBrandSulfur.copy(alpha = alpha),
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Minecraft Server Launcher X",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                // 次级文字压到约 4.7:1，既弱化又保持可读
                color = OnBrandSulfur.copy(alpha = 0.76f * alpha),
            )
        }
    }
}
