package app.nya.smsforward.node.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import app.nya.smsforward.node.BuildConfig
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

private const val REPOSITORY = "stevennight/nyasmsforward-client-node"
private const val API_URL = "https://api.github.com/repos/$REPOSITORY/releases/latest"
private const val DOWNLOAD_PREFIX = "https://github.com/$REPOSITORY/releases/download/"
private const val MAX_METADATA_BYTES = 2L * 1024 * 1024
private const val MAX_CHECKSUM_BYTES = 1024L * 1024
private const val MAX_APK_BYTES = 256L * 1024 * 1024

/** A release asset selected only after its name and URL have been checked. */
data class ApkUpdate(
    val currentVersion: String,
    val version: String,
    val releaseName: String,
    val releaseNotes: String,
    val apkName: String,
    val apkUrl: String,
    val checksumName: String,
    val checksumUrl: String,
)

sealed interface UpdateCheck {
    data object Idle : UpdateCheck
    data object Checking : UpdateCheck
    data class UpToDate(val currentVersion: String) : UpdateCheck
    data class Available(val update: ApkUpdate) : UpdateCheck
    data class Failed(val message: String) : UpdateCheck
}

sealed interface InstallResult {
    data object Started : InstallResult
    data object PermissionRequired : InstallResult
    data class Failed(val message: String) : InstallResult
}

@Serializable
private data class GitHubRelease(
    val tag_name: String = "",
    val name: String? = null,
    val body: String? = null,
    val draft: Boolean = false,
    val prerelease: Boolean = false,
    val assets: List<GitHubAsset> = emptyList(),
)

@Serializable
private data class GitHubAsset(
    val name: String = "",
    val browser_download_url: String = "",
    val size: Long = 0,
)

private val json = Json { ignoreUnknownKeys = true }

/** Checks and installs the signed APK published by this repository's release workflow. */
object NodeUpdater {
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .callTimeout(90, TimeUnit.SECONDS)
        .build()

    fun check(currentVersion: String): UpdateCheck {
        val current = parseStableVersion(currentVersion)
            ?: return UpdateCheck.Failed("当前版本号无效：$currentVersion")
        return try {
            val release = getRelease()
            if (release.draft || release.prerelease) return UpdateCheck.Failed("GitHub 返回了草稿或预发行版本")
            val latest = parseStableVersion(release.tag_name.removePrefix("v"))
                ?: return UpdateCheck.Failed("Release 标签不是稳定版本：${release.tag_name}")
            if (latest <= current) return UpdateCheck.UpToDate(currentVersion)

            val version = latest.text
            val apkName = "${BuildConfig.APK_NAME_PREFIX}_${version}.apk" // each edition updates only to its own APK
            val checksumName = "$apkName.sha256"
            val apk = release.assets.singleOrNull { it.name == apkName }
                ?: return UpdateCheck.Failed("最新 Release 没有兼容的 APK：$apkName")
            val checksum = release.assets.singleOrNull { it.name == checksumName }
                ?: return UpdateCheck.Failed("最新 Release 没有校验文件：$checksumName")
            if (!trustedAsset(apk, release.tag_name, apkName) || !trustedAsset(checksum, release.tag_name, checksumName)) {
                return UpdateCheck.Failed("Release 资产地址不属于受信任的仓库")
            }
            if (apk.size <= 0 || apk.size > MAX_APK_BYTES) return UpdateCheck.Failed("APK 大小不符合安全限制")
            UpdateCheck.Available(
                ApkUpdate(
                    currentVersion = currentVersion,
                    version = version,
                    releaseName = release.name?.trim().orEmpty().ifEmpty { release.tag_name },
                    releaseNotes = release.body?.trim()?.take(4_000).orEmpty(),
                    apkName = apkName,
                    apkUrl = apk.browser_download_url,
                    checksumName = checksumName,
                    checksumUrl = checksum.browser_download_url,
                ),
            )
        } catch (e: Exception) {
            UpdateCheck.Failed(e.message ?: "检查更新失败")
        }
    }

    fun downloadAndInstall(context: Context, update: ApkUpdate): InstallResult {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
            context.startActivity(
                Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${context.packageName}"),
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            return InstallResult.PermissionRequired
        }
        return try {
            val tag = "v${update.version}"
            require(update.apkUrl == "$DOWNLOAD_PREFIX$tag/${update.apkName}") { "APK 下载地址不是预期的 Release 地址" }
            require(update.checksumUrl == "$DOWNLOAD_PREFIX$tag/${update.checksumName}") { "校验文件地址不是预期的 Release 地址" }
            val directory = File(context.cacheDir, "updates/${update.version}").apply { mkdirs() }
            val checksumText = downloadText(update.checksumUrl, MAX_CHECKSUM_BYTES)
            val expected = checksumFor(checksumText, update.apkName)
                ?: error("校验文件中没有找到 ${update.apkName}")
            val apkFile = File(directory, update.apkName)
            downloadFile(update.apkUrl, apkFile, MAX_APK_BYTES)
            if (sha256(apkFile) != expected) {
                apkFile.delete()
                error("APK SHA-256 校验失败")
            }
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apkFile)
            context.startActivity(
                Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                },
            )
            InstallResult.Started
        } catch (e: Exception) {
            InstallResult.Failed(e.message ?: "下载更新失败")
        }
    }

    private fun getRelease(): GitHubRelease {
        val request = Request.Builder()
            .url(API_URL)
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("User-Agent", "NyaSmsForward-Node-Updater")
            .build()
        http.newCall(request).execute().use { response ->
            require(response.isSuccessful) { "GitHub 返回 HTTP ${response.code}" }
            val bytes = response.body?.bytes() ?: error("GitHub 返回空响应")
            require(bytes.size <= MAX_METADATA_BYTES) { "Release 信息过大" }
            return json.decodeFromString<GitHubRelease>(bytes.decodeToString())
        }
    }

    private fun downloadText(url: String, maxBytes: Long): String {
        val request = Request.Builder().url(url).header("User-Agent", "NyaSmsForward-Node-Updater").build()
        http.newCall(request).execute().use { response ->
            require(response.isSuccessful) { "下载校验文件失败：HTTP ${response.code}" }
            val bytes = response.body?.bytes() ?: error("校验文件为空")
            require(bytes.size <= maxBytes) { "校验文件过大" }
            return bytes.decodeToString()
        }
    }

    private fun downloadFile(url: String, destination: File, maxBytes: Long) {
        val request = Request.Builder().url(url).header("User-Agent", "NyaSmsForward-Node-Updater").build()
        http.newCall(request).execute().use { response ->
            require(response.isSuccessful) { "下载 APK 失败：HTTP ${response.code}" }
            val body = response.body ?: error("APK 响应为空")
            require(body.contentLength() in 1..maxBytes) { "APK 大小不符合安全限制" }
            body.byteStream().use { input ->
                destination.outputStream().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var total = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        require(total <= maxBytes) { "APK 超过大小限制" }
                        output.write(buffer, 0, count)
                    }
                    require(total > 0) { "APK 为空" }
                }
            }
        }
    }

    private fun trustedAsset(asset: GitHubAsset, tag: String, name: String): Boolean =
        asset.name == name && asset.browser_download_url == "$DOWNLOAD_PREFIX$tag/$name"

    private fun checksumFor(text: String, fileName: String): String? = text.lineSequence()
        .map { it.trim() }
        .mapNotNull { line ->
            val fields = line.split(Regex("\\s+"), limit = 2)
            if (fields.size == 2 && fields[0].matches(Regex("(?i)[0-9a-f]{64}"))) {
                fields[0].lowercase() to fields[1].removePrefix("*")
            } else null
        }
        .firstOrNull { it.second == fileName }
        ?.first

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

internal data class StableVersion(val major: Int, val minor: Int, val patch: Int) : Comparable<StableVersion> {
    val text: String get() = "$major.$minor.$patch"

    override fun compareTo(other: StableVersion): Int =
        compareValuesBy(this, other, StableVersion::major, StableVersion::minor, StableVersion::patch)
}

internal fun parseStableVersion(value: String): StableVersion? {
    val match = Regex("^(\\d+)\\.(\\d+)\\.(\\d+)$").matchEntire(value) ?: return null
    val parts = match.groupValues.drop(1).map { it.toIntOrNull() ?: return null }
    return StableVersion(parts[0], parts[1], parts[2])
}
