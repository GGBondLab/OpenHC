package io.github.gcbond.openhc

import android.content.Context
import androidx.compose.runtime.Immutable
import io.github.gcbond.openhc.core.common.identity.IdentityAssets
import io.github.gcbond.openhc.identity.IdentityJson
import io.github.gcbond.openhc.identity.IdentityResolution
import io.github.gcbond.openhc.identity.StoredText
import java.io.FileNotFoundException
import java.io.IOException

/**
 * 读车机的连接参数：安装包里的 `openhc-identity.json`（不进仓库，构建时打进安装包，见 app/build.gradle.kts）。
 * 缺少或不能用（例如有人把文件改坏了）时报出来，见 [IdentityResolution]。
 */
object IdentityLoader {

    @Immutable
    sealed interface Result {
        class Loaded(val assets: IdentityAssets) : Result
        object Missing : Result

        /** [reason] 不含密钥内容。 */
        class Invalid(val reason: String) : Result
    }

    @Volatile
    private var cached: Result? = null

    fun load(context: Context): Result {
        cached?.let { return it }
        val app = context.applicationContext ?: context
        return IdentityResolution.resolve(readBundled(app)).also { cached = it }
    }

    private fun readBundled(context: Context): StoredText = try {
        context.assets.open(IdentityAssets.ASSET_FILE_NAME).use { StoredText.Present(IdentityJson.decodeUtf8(IdentityJson.readLimited(it))) }
    } catch (e: FileNotFoundException) {
        StoredText.Absent
    } catch (e: IOException) {
        StoredText.Unreadable(e.message ?: e.javaClass.simpleName)
    }
}
