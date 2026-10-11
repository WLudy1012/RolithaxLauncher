package com.wludy.rolithax.launcher.ui.console

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight

/**
 * 服务器日志 ANSI 原彩解析。
 *
 * 守护进程以 `-Dterminal.jline=false -Dterminal.ansi=true` 启动服务端时，
 * `ReceiveLog` 推送的是带 ANSI 转义序列（`\x1b[...m`）的原始日志；
 * 本解析器把转义序列转换为 Compose 颜色/字重，实现终端原彩显示。
 * 支持 16/256/真彩色前景与背景及粗体；其余转义序列（清行、光标移动等）一律丢弃，
 * 绝不把转义字符或其参数显示到界面。
 */

/** 一段已着色日志片段。color/background 为 null 表示继承默认控制台颜色。 */
data class AnsiSegment(
    val text: String,
    val color: Color? = null,
    val bold: Boolean = false,
    val background: Color? = null,
)

/** 解析单行日志（无跨行状态）；无 ANSI 序列时返回单段原文。 */
fun parseAnsiLog(line: String): List<AnsiSegment> =
    AnsiStreamParser().append(line).ifEmpty { listOf(AnsiSegment("")) }

/** 标准 16 色 ANSI 调色板（适配深色控制台背景）。 */
private fun ansiColor(index: Int, bright: Boolean): Color = when (index) {
    0 -> if (bright) Color(0xFFBFBFBF) else Color(0xFF7F7F7F) // 黑/灰
    1 -> if (bright) Color(0xFFFF8A80) else Color(0xFFEF5350) // 红
    2 -> if (bright) Color(0xFFB9F6CA) else Color(0xFF66BB6A) // 绿
    3 -> if (bright) Color(0xFFFFFF59) else Color(0xFFFFD54F) // 黄
    4 -> if (bright) Color(0xFF82B1FF) else Color(0xFF42A5F5) // 蓝
    5 -> if (bright) Color(0xFFEA80FC) else Color(0xFFAB47BC) // 品红
    6 -> if (bright) Color(0xFF80DEEA) else Color(0xFF26C6DA) // 青
    7 -> if (bright) Color(0xFFFFFFFF) else Color(0xFFE0E0E0) // 白
    else -> Color.Unspecified
}

/** xterm 256 色：0-15 沿用 16 色调色板，16-231 为 6x6x6 色立方，232-255 为灰阶。 */
private fun xterm256Color(index: Int): Color? = when (index) {
    in 0..7 -> ansiColor(index, bright = false)
    in 8..15 -> ansiColor(index - 8, bright = true)
    in 16..231 -> {
        val n = index - 16
        fun level(v: Int) = if (v == 0) 0 else 55 + v * 40
        Color(level(n / 36), level(n / 6 % 6), level(n % 6))
    }
    in 232..255 -> {
        val v = 8 + (index - 232) * 10
        Color(v, v, v)
    }
    else -> null
}

private fun rgbColor(r: Int?, g: Int?, b: Int?): Color? {
    if (r == null || g == null || b == null) return null
    return Color(r.coerceIn(0, 255), g.coerceIn(0, 255), b.coerceIn(0, 255))
}

/** 加粗字重。 */
val AnsiSegment.ansiFontWeight: FontWeight
    get() = if (bold) FontWeight.Bold else FontWeight.Normal

/**
 * 增量 ANSI 解析器：颜色状态与末尾未完成的转义序列跨 [append] 调用保留。
 * 生产路径（单行解析 [parseAnsiLog] 与远程控制台的跨消息状态）共用这一份实现。
 */
class AnsiStreamParser {
    private var pending = ""
    private var color: Color? = null
    private var background: Color? = null
    private var bold = false

    /**
     * 解析一块文本。换行等普通字符原样保留在片段中；
     * 只有末尾未完成的转义序列会被缓存到下次调用，普通文本不会被缓存，因此无需结束时 flush。
     */
    fun append(chunk: String): List<AnsiSegment> {
        val text = pending + chunk
        pending = ""
        val output = mutableListOf<AnsiSegment>()
        val buffer = StringBuilder()
        fun emit() {
            if (buffer.isNotEmpty()) {
                output += AnsiSegment(buffer.toString(), color, bold, background)
                buffer.clear()
            }
        }

        var i = 0
        while (i < text.length) {
            val ch = text[i]
            if (ch != ESC) {
                buffer.append(ch)
                i++
                continue
            }
            val end = escapeEnd(text, i)
            if (end == INCOMPLETE) {
                if (text.length - i > MAX_PENDING_ESCAPE) {
                    i++ // 异常超长序列：丢弃 ESC，其余按普通文本处理
                } else {
                    pending = text.substring(i)
                    i = text.length
                }
                continue
            }
            if (text[i + 1] == '[' && text[end - 1] == 'm') {
                emit()
                applySgr(text.substring(i + 2, end - 1))
            }
            i = end
        }
        emit()
        return output
    }

    /** 连接断开或进程重启时调用：丢弃未完成的序列并恢复默认样式。 */
    fun reset() {
        pending = ""
        color = null
        background = null
        bold = false
    }

    private fun applySgr(body: String) {
        val tokens = body.split(';')
        var idx = 0
        while (idx < tokens.size) {
            val token = tokens[idx++]
            if (token.contains(':')) {
                applyColonSgr(token.split(':'))
                continue
            }
            val code = if (token.isEmpty()) 0 else token.toIntOrNull() ?: continue
            when (code) {
                0 -> {
                    color = null
                    background = null
                    bold = false
                }
                1 -> bold = true
                22 -> bold = false
                39 -> color = null
                49 -> background = null
                in 30..37 -> color = ansiColor(code - 30, bright = false)
                in 90..97 -> color = ansiColor(code - 90, bright = true)
                in 40..47 -> background = ansiColor(code - 40, bright = false)
                in 100..107 -> background = ansiColor(code - 100, bright = true)
                38, 48 -> {
                    val resolved: Color?
                    when (tokens.getOrNull(idx)?.toIntOrNull()) {
                        5 -> {
                            resolved = tokens.getOrNull(idx + 1)?.toIntOrNull()?.let(::xterm256Color)
                            idx += 2
                        }
                        2 -> {
                            resolved = rgbColor(
                                tokens.getOrNull(idx + 1)?.toIntOrNull(),
                                tokens.getOrNull(idx + 2)?.toIntOrNull(),
                                tokens.getOrNull(idx + 3)?.toIntOrNull(),
                            )
                            idx += 4
                        }
                        else -> {
                            resolved = null
                            idx = tokens.size
                        }
                    }
                    if (resolved != null) {
                        if (code == 38) color = resolved else background = resolved
                    }
                }
            }
        }
    }

    /** 冒号分隔的扩展色写法：`38:5:n`、`38:2:[色彩空间]:r:g:b`。 */
    private fun applyColonSgr(parts: List<String>) {
        val code = parts[0].toIntOrNull()
        if (code != 38 && code != 48) return
        val resolved = when (parts.getOrNull(1)?.toIntOrNull()) {
            5 -> parts.getOrNull(2)?.toIntOrNull()?.let(::xterm256Color)
            2 -> {
                val rgb = parts.drop(2).takeLast(3)
                if (rgb.size == 3) rgbColor(rgb[0].toIntOrNull(), rgb[1].toIntOrNull(), rgb[2].toIntOrNull()) else null
            }
            else -> null
        }
        if (resolved != null) {
            if (code == 38) color = resolved else background = resolved
        }
    }

    private companion object {
        const val ESC = '\u001B'
        const val INCOMPLETE = -1
        const val MAX_PENDING_ESCAPE = 64

        /**
         * 返回 [start] 处转义序列的结束位置（不含）；序列未收完返回 [INCOMPLETE]。
         * 覆盖 CSI（`ESC [ ... final`）、OSC（`ESC ] ... BEL|ESC \`）与两字节/带中间字节的短序列。
         * 遇到非法字节时序列在该字节前结束，该字节仍按普通文本处理。
         */
        fun escapeEnd(text: String, start: Int): Int {
            val next = text.getOrNull(start + 1) ?: return INCOMPLETE
            var j = start + 2
            when (next) {
                '[' -> {
                    while (j < text.length) {
                        val c = text[j]
                        if (c in '@'..'~') return j + 1
                        if (c !in ' '..'?') return j
                        j++
                    }
                    return INCOMPLETE
                }
                ']' -> {
                    while (j < text.length) {
                        val c = text[j]
                        if (c == '\u0007') return j + 1
                        if (c == ESC) {
                            val after = text.getOrNull(j + 1) ?: return INCOMPLETE
                            return if (after == '\\') j + 2 else j
                        }
                        j++
                    }
                    return INCOMPLETE
                }
                else -> {
                    j = start + 1
                    while (j < text.length && text[j] in ' '..'/') j++
                    if (j >= text.length) return INCOMPLETE
                    return if (text[j] in '0'..'~') j + 1 else j
                }
            }
        }
    }
}
