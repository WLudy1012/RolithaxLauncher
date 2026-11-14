package com.wludy.rolithax.launcher.data.remote

import android.util.Base64
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

internal fun pairingEndpoint(input: String): HttpUrl? {
    val text = input.trim()
    if (!text.startsWith("http://", true) && !text.startsWith("https://", true)) return null
    val authority = text.substringAfter("://").substringBefore('/').substringBefore('?').substringBefore('#')
    if ('@' in authority || '\\' in text) return null
    val url = text.toHttpUrlOrNull() ?: return null
    if (url.username.isNotEmpty() || url.password.isNotEmpty() || url.query != null || url.fragment != null) return null
    return url
}

internal fun samePairingEndpoint(first: String, second: String): Boolean {
    val a = pairingEndpoint(first) ?: return false
    val b = pairingEndpoint(second) ?: return false
    return a.scheme == b.scheme && a.host == b.host && a.port == b.port &&
        a.encodedPath.trimEnd('/') == b.encodedPath.trimEnd('/')
}

/** 配对二维码载荷字段（与服务端插件 PairingPayload 对齐：v/u/c/e/s）。 */
data class PairingPayload(
    @SerializedName("v") val version: Int = 0,
    @SerializedName("u") val url: String? = null,
    @SerializedName("c") val code: String? = null,
    @SerializedName("e") val expiresAt: Long = 0,
    @SerializedName("s") val signature: String? = null,
)

/**
 * 解析 Daemon 扫码配对插件的二维码载荷：`mslxp1:` + Base64Url(JSON)。
 *
 * 仅做本地解码与基本校验（版本/字段/时效），签名校验由服务端在兑换时完成
 * ——客户端没有安装密钥，无法也不应自行验签。载荷原文仍需原样回传给 redeem 端点。
 */
object PairingPayloadCodec {

    const val PREFIX = "mslxp1:"

    fun decode(text: String?): PairingPayload? {
        val raw = text?.trim().orEmpty()
        if (!raw.startsWith(PREFIX)) return null
        val bytes = base64UrlDecode(raw.substring(PREFIX.length)) ?: return null
        val json = runCatching { String(bytes, Charsets.UTF_8) }.getOrNull() ?: return null
        return runCatching { Gson().fromJson(json, PairingPayload::class.java) }.getOrNull()
    }

    private fun base64UrlDecode(input: String): ByteArray? = runCatching {
        var s = input.replace('-', '+').replace('_', '/')
        when (s.length % 4) {
            2 -> s += "=="
            3 -> s += "="
        }
        Base64.decode(s, Base64.DEFAULT)
    }.getOrNull()
}
