package com.drinkcat.nekopost.system

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.Collator
import java.util.Locale

/** A launchable app the user may pick for notification forwarding. */
@Immutable
data class InstalledApp(val packageName: String, val label: String)

/**
 * Launcher apps, visible through the MAIN/LAUNCHER `<queries>` entry in the
 * manifest (no QUERY_ALL_PACKAGES). Sorted by label for Chinese readers.
 */
suspend fun Context.loadLauncherApps(): List<InstalledApp> = withContext(Dispatchers.IO) {
    val pm = packageManager
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    val collator = Collator.getInstance(Locale.SIMPLIFIED_CHINESE)
    pm.queryIntentActivities(intent, 0)
        .map { it.activityInfo }
        .filter { it.packageName != packageName }
        .distinctBy { it.packageName }
        .map { InstalledApp(it.packageName, it.applicationInfo.loadLabel(pm).toString()) }
        .sortedWith { a, b -> collator.compare(a.label, b.label) }
}

/** Loads app icons lazily; screenshot tests provide a loader that returns null (letter avatars). */
fun interface AppIconLoader {
    suspend fun load(packageName: String): ImageBitmap?
}

val LocalAppIconLoader = staticCompositionLocalOf<AppIconLoader> { AppIconLoader { null } }

class PackageIconLoader(private val context: Context) : AppIconLoader {
    private val cache = HashMap<String, ImageBitmap?>()

    override suspend fun load(packageName: String): ImageBitmap? {
        synchronized(cache) { if (cache.containsKey(packageName)) return cache[packageName] }
        val bitmap = withContext(Dispatchers.IO) {
            try {
                val drawable = context.packageManager.getApplicationIcon(packageName)
                val size = (40 * context.resources.displayMetrics.density).toInt().coerceAtLeast(1)
                val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
                drawable.setBounds(0, 0, size, size)
                drawable.draw(Canvas(bmp))
                bmp.asImageBitmap()
            } catch (e: Exception) {
                null
            }
        }
        synchronized(cache) { cache[packageName] = bitmap }
        return bitmap
    }
}

@Composable
fun rememberAppIcon(packageName: String): ImageBitmap? {
    val loader = LocalAppIconLoader.current
    val icon by produceState<ImageBitmap?>(initialValue = null, packageName, loader) {
        value = loader.load(packageName)
    }
    return icon
}
