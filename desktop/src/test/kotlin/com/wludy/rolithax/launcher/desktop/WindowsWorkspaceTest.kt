package com.wludy.rolithax.launcher.desktop

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.wludy.rolithax.launcher.shared.DaemonInstance
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.junit.Rule
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class WindowsWorkspaceTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun commandEnterSendsAndArrowUpRestoresHistory() {
        val sent = mutableListOf<String>()
        compose.setContent {
            RolithaxWindowsTheme {
                Surface {
                    Column(Modifier.fillMaxSize()) {
                        ConsoleContent(DesktopState(consoleInstance = DaemonInstance(1), consoleConnected = true), sent::add)
                    }
                }
            }
        }
        val input = compose.onNodeWithText("服务器命令")
        input.performClick().performTextInput("list")
        input.performKeyInput { pressKey(Key.Enter) }
        compose.runOnIdle { assertEquals(listOf("list"), sent) }
        input.performKeyInput { pressKey(Key.DirectionUp) }
        input.assertTextContains("list")
    }

    @Test
    fun instanceSearchSelectsMatchingServerAndOpensFiles() {
        var opened: Pair<Long, String>? = null
        compose.setContent {
            RolithaxWindowsTheme {
                Surface {
                    InstanceBrowser(DesktopState(selectedId = 1, instances = listOf(
                        DaemonInstance(1, "Creative", core = "paper.jar", status = 2),
                        DaemonInstance(2, "Survival 1.21", core = "fabric.jar", status = 0),
                    )), { instance, page -> opened = instance.id to page }, { _, _ -> }, {}, Modifier.fillMaxSize())
                }
            }
        }
        val snapshot = compose.onRoot().captureToImage()
        File("build/visual-check").mkdirs()
        File("build/visual-check/instance-browser.png").writeBytes(Image.makeFromBitmap(snapshot.asSkiaBitmap()).encodeToData(EncodedImageFormat.PNG)!!.bytes)
        compose.onNodeWithContentDescription("搜索名称、ID 或核心").performTextInput("Survival")
        compose.onNodeWithText("1 个实例").assertExists()
        compose.onNodeWithText("Creative", substring = true).assertDoesNotExist()
        compose.onNodeWithText("文件管理").performClick()
        compose.runOnIdle { assertEquals(2L to "files", opened) }
    }

    @Test
    fun statusFiltersAndActionsUseDaemonStatusCodes() {
        var action: Pair<Long, String>? = null
        compose.setContent {
            RolithaxWindowsTheme {
                Surface {
                    InstanceBrowser(DesktopState(selectedId = 1, instances = listOf(
                        DaemonInstance(1, "Running", status = 2), DaemonInstance(2, "Stopped", status = 0),
                    )), { _, _ -> }, { instance, value -> action = instance.id to value }, {}, Modifier.fillMaxSize())
                }
            }
        }
        compose.onNodeWithText("停止实例").performClick()
        compose.runOnIdle { assertEquals(1L to "stop", action) }
        compose.onNode(hasText("已停止") and SemanticsMatcher.expectValue(SemanticsProperties.Selected, false)).performClick()
        compose.onNodeWithText("Running", substring = true).assertDoesNotExist()
        compose.onNodeWithText("启动实例").performClick()
        compose.runOnIdle { assertEquals(2L to "start", action) }
    }

    @Test
    fun tabsExposeSelectionAndOpenRequestedWorkspace() {
        var page: String? = null
        compose.setContent { RolithaxWindowsTheme { WorkspaceTabs("details", true, { page = it }) } }
        compose.onNodeWithText("概览").assertIsSelected()
        compose.onNodeWithText("文件管理").assertIsNotSelected().performClick()
        compose.runOnIdle { assertEquals("files", page) }
    }

    @Test
    fun overviewOnlyEnablesServerActionsAfterConnecting() {
        var connect = false
        compose.setContent {
            RolithaxWindowsTheme { DesktopOverview(DesktopState(), {}, { connect = true }, {}, Modifier.fillMaxSize()) }
        }
        compose.onNodeWithText("查看实例").assertIsNotEnabled()
        compose.onNodeWithText("创建实例").assertIsNotEnabled()
        compose.onNodeWithText("添加连接").performClick()
        compose.runOnIdle { assertEquals(true, connect) }
    }

    @Test
    fun mainNavigationOpensInstancesAndConnectionManagement() {
        compose.setContent {
            RolithaxWindowsTheme {
                DesktopLayoutFixture(DesktopState(selectedId = 1, connections = listOf(ConnectionItem(1, "演示节点")), instances = listOf(DaemonInstance(1, "测试实例"))))
            }
        }
        compose.onNodeWithText("欢迎使用 Rolithax").assertExists()
        compose.onNodeWithText("实例管理").performClick()
        compose.onNodeWithContentDescription("搜索名称、ID 或核心").assertExists()
        compose.onNodeWithText("Daemon 连接").performClick()
        compose.onNodeWithText("当前会话 · 1").assertExists()
    }

    @Test
    fun consoleCommandRemainsVisibleInsideMainWindow() {
        compose.setContent {
            RolithaxWindowsTheme {
                DesktopLayoutFixture(DesktopState(selectedId = 1, connections = listOf(ConnectionItem(1, "演示节点")), consoleInstance = DaemonInstance(1, "测试实例"), consoleConnected = true))
            }
        }
        compose.onNodeWithText("服务器命令").assertIsDisplayed()
        compose.onNodeWithText("发送").assertIsDisplayed()
    }
}
