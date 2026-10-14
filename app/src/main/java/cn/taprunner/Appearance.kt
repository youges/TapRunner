package cn.taprunner

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color as AndroidColor
import android.view.ContextThemeWrapper
import android.widget.*
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import androidx.core.graphics.ColorUtils
import kotlinx.coroutines.flow.MutableStateFlow

enum class ThemeMode(val title: String) { SYSTEM("跟随系统"), LIGHT("白天"), DARK("暗夜") }
data class Appearance(val mode: ThemeMode = ThemeMode.SYSTEM, val accent: Int = 0xFF5367E8.toInt())
data class AppPalette(val dark: Boolean, val background: Int, val surface: Int, val raised: Int, val text: Int, val muted: Int, val accent: Int, val onAccent: Int, val accentSoft: Int, val border: Int)

class AppearanceStore(context: Context) {
    private val preferences = context.getSharedPreferences("appearance", Context.MODE_PRIVATE)
    val state = MutableStateFlow(Appearance(
        runCatching { ThemeMode.valueOf(preferences.getString("mode", "SYSTEM")!!) }.getOrDefault(ThemeMode.SYSTEM),
        preferences.getInt("accent", 0xFF5367E8.toInt())
    ))
    fun update(mode: ThemeMode = state.value.mode, accent: Int = state.value.accent) {
        val value = Appearance(mode, accent or 0xFF000000.toInt())
        preferences.edit { putString("mode", value.mode.name); putInt("accent", value.accent) }
        state.value = value
    }
    fun palette(context: Context): AppPalette = palette(state.value, context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES)
    fun viewContext(context: Context): Context {
        val dark = palette(context).dark
        val configuration = Configuration(context.resources.configuration).apply {
            uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
        }
        return ContextThemeWrapper(context.createConfigurationContext(configuration), if (dark) R.style.Theme_TapRunner_Dark else R.style.Theme_TapRunner_Light)
    }
    companion object {
        val accents = listOf("靛蓝" to 0xFF5367E8.toInt(), "青绿" to 0xFF087F75.toInt(), "紫罗兰" to 0xFF8352C5.toInt(), "暖橙" to 0xFFC05C25.toInt())
        fun parseAccent(text: String): Int? = text.trim().removePrefix("#").takeIf { it.matches(Regex("[0-9a-fA-F]{6}")) }?.toLongOrNull(16)?.toInt()?.or(0xFF000000.toInt())
        fun palette(value: Appearance, systemDark: Boolean): AppPalette {
            val dark = value.mode == ThemeMode.DARK || (value.mode == ThemeMode.SYSTEM && systemDark)
            val background = if (dark) 0xFF101521.toInt() else 0xFFF5F7FC.toInt()
            val surface = if (dark) 0xFF1A2232.toInt() else AndroidColor.WHITE
            val accent = if (dark) ColorUtils.blendARGB(value.accent, AndroidColor.WHITE, .28f) else value.accent
            val onAccent = if (ColorUtils.calculateContrast(AndroidColor.WHITE, accent) >= ColorUtils.calculateContrast(AndroidColor.BLACK, accent)) AndroidColor.WHITE else AndroidColor.BLACK
            return AppPalette(dark, background, surface, if (dark) 0xFF232D40.toInt() else 0xFFEDF1FA.toInt(), if (dark) 0xFFE7EDF8.toInt() else 0xFF1C263A.toInt(), if (dark) 0xFFB0BCD2.toInt() else 0xFF65738B.toInt(), accent, onAccent, ColorUtils.blendARGB(surface, accent, if (dark) .16f else .09f), if (dark) 0xFF35425A.toInt() else 0xFFDCE3EF.toInt())
        }
    }
}

@Composable fun TapRunnerTheme(content: @Composable () -> Unit) {
    val appearance by TapApp.instance.appearance.state.collectAsState()
    val palette = AppearanceStore.palette(appearance, isSystemInDarkTheme())
    val colors = if (palette.dark) darkColorScheme() else lightColorScheme()
    MaterialTheme(colorScheme = colors.copy(
        primary=Color(palette.accent), onPrimary=Color(palette.onAccent), primaryContainer=Color(palette.accentSoft), onPrimaryContainer=Color(palette.text),
        secondary=Color(palette.accent), secondaryContainer=Color(palette.raised), onSecondaryContainer=Color(palette.text),
        background=Color(palette.background), onBackground=Color(palette.text), surface=Color(palette.surface), onSurface=Color(palette.text),
        surfaceVariant=Color(palette.raised), onSurfaceVariant=Color(palette.muted), outline=Color(palette.border), surfaceContainer=Color(palette.surface), surfaceContainerLow=Color(palette.background), surfaceContainerLowest=Color(palette.background), surfaceContainerHigh=Color(palette.raised), surfaceContainerHighest=Color(palette.raised)
    ), shapes=Shapes(small=RoundedCornerShape(12.dp), medium=RoundedCornerShape(18.dp), large=RoundedCornerShape(24.dp)), content=content)
}

/** Update existing native controls without replacing a form or its input. */
fun tintNativeTree(view: View, palette: AppPalette) {
    when (view) {
        is EditText -> { view.setTextColor(palette.text); view.setHintTextColor(palette.muted); view.backgroundTintList=android.content.res.ColorStateList.valueOf(palette.accent) }
        is CheckBox -> { view.setTextColor(palette.text); view.buttonTintList=android.content.res.ColorStateList.valueOf(palette.accent) }
        is Button -> { view.setTextColor(palette.text); view.backgroundTintList=android.content.res.ColorStateList.valueOf(palette.raised) }
        is TextView -> if(view.tag!="fixed")view.setTextColor(if (view.tag=="onAccent") palette.onAccent else palette.text)
    }
    if (view is ViewGroup) for (index in 0 until view.childCount) tintNativeTree(view.getChildAt(index), palette)
}
