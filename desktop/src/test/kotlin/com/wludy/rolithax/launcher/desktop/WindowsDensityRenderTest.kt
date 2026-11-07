package com.wludy.rolithax.launcher.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.wludy.rolithax.launcher.shared.DaemonInstance
import com.wludy.rolithax.launcher.shared.DaemonInstanceExtra
import com.wludy.rolithax.launcher.shared.SystemMonitorEvent
import org.jetbrains.skia.EncodedImageFormat
import org.junit.Test
import java.io.File
import javax.swing.SwingUtilities
import kotlin.test.assertEquals

@OptIn(ExperimentalComposeUiApi::class)
class WindowsDensityRenderTest {
    @Test
    fun rendersAtNativePixelDensityForWindowsScales() {
        val samples = DesktopState(selectedId = 1, instancesLoaded = true, connections = listOf(ConnectionItem(1, "布局演示（示例数据）")), monitorConnected = true, systemStats = SystemMonitorEvent.Stats(18.2, 16384.0, 6144.0, 37.5, null), instances = listOf(
            DaemonInstance(1, "主世界", core = "paper-1.21.4.jar", java = "Java 21", basePath = "D:\\Servers\\survival", status = 2, extra = DaemonInstanceExtra(8)),
            DaemonInstance(2, "测试服", core = "vanilla-1.21.4.jar", java = "Java 21", status = 0),
            DaemonInstance(3, "模组服", core = "fabric-server.jar", java = "Java 21", status = 0),
        ))
        File("build/visual-check").mkdirs()
        for ((scale, dark) in listOf(1f to false, 1.25f to false, 1.5f to false, 1.5f to true)) {
            SwingUtilities.invokeAndWait {
                val width = (1120 * scale).toInt()
                val height = (700 * scale).toInt()
                val scene = ImageComposeScene(width, height, density = Density(scale), layoutDirection = LayoutDirection.Ltr) {
                    RolithaxWindowsTheme(dark) {
                        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                            Column(Modifier.padding(28.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                                Text("实例管理", style = MaterialTheme.typography.headlineSmall)
                                Text("界面验证 · 示例数据", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                InstanceBrowser(samples, { _, _ -> }, { _, _ -> }, {}, Modifier.weight(1f))
                            }
                        }
                    }
                }
                try {
                    scene.render(0).close()
                    val snapshot = scene.render(1_000_000_000)
                    assertEquals(width, snapshot.width)
                    assertEquals(height, snapshot.height)
                    File("build/visual-check/workspace-${(scale * 100).toInt()}-${if (dark) "dark" else "light"}.png").writeBytes(snapshot.encodeToData(EncodedImageFormat.PNG)!!.bytes)
                    snapshot.close()
                } finally {
                    scene.close()
                }
            }
        }
        for (dark in listOf(false, true)) {
            for (console in listOf(false, true)) {
                SwingUtilities.invokeAndWait {
                    val state = if (console) samples.copy(consoleInstance = samples.instances.first(), consoleConnected = true, consoleLines = listOf("[示例日志] 服务器输出将在此区域实时显示", "[Server thread/INFO]: Starting minecraft server version 1.21.4", "[Server thread/INFO]: Done! Type help for commands")) else samples
                    val scene = ImageComposeScene(1680, 1050, density = Density(1.5f), layoutDirection = LayoutDirection.Ltr) {
                        RolithaxWindowsTheme(dark) { DesktopLayoutFixture(state) }
                    }
                    try {
                        scene.render(0).close()
                        val snapshot = scene.render(1_000_000_000)
                        File("build/visual-check/desktop-${if (console) "console" else "overview"}-150-${if (dark) "dark" else "light"}.png").writeBytes(snapshot.encodeToData(EncodedImageFormat.PNG)!!.bytes)
                        snapshot.close()
                    } finally { scene.close() }
                }
            }
        }
    }
}
