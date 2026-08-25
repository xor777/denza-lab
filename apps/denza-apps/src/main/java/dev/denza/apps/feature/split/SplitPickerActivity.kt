package dev.denza.apps.feature.split

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Parcel
import android.os.ResultReceiver
import android.util.Log
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.core.view.WindowCompat
import java.util.concurrent.Executors

internal data class SplitPickerApp(
    val packageName: String,
    val label: String,
    val icon: Bitmap?,
)

/**
 * A pane-neutral picker host. Its current native root, not its Activity class, defines the pane.
 * Two independent tasks of this same component may coexist in the two firmware roots.
 */
class SplitPickerActivity : ComponentActivity() {
    private var message by mutableStateOf("")
    private var recoveryNotice by mutableStateOf("")
    private var busy by mutableStateOf(false)

    /**
     * Правка W9 (v20 D4): чей запуск сейчас в полёте. Селект на этой машине занимает 3-4.7 с, и
     * пикер обязан показывать честное ожидание (1.13.2): крутилку на выбранной плитке и
     * пригашенную сетку - тап не выглядит проигнорированным. Сбрасывается итогом операции.
     */
    private var busyPackage by mutableStateOf<String?>(null)
    // U6, 1.13.2: a picker that comes back to an already-scanned catalog shows the apps at once.
    // "Загружаю приложения…" is honest only while there is genuinely nothing to show yet.
    private var apps by mutableStateOf(SplitPickerAppCatalog.cached().orEmpty())
    private var catalogLoadInFlight = false
    private var catalogReloadPending = false
    private var catalogLoading by mutableStateOf(apps.isEmpty())

    /**
     * 1.13.1, U6: whether this picker has ever actually been on screen.
     *
     * A restored pair creates two pickers and immediately covers both with applications. Composing
     * the grid for them - a hundred tiles with a bitmap each - costs the main thread of this process
     * exactly as much as if the user were looking at it, and during an open that thread is the one
     * drawing the waiting window. A covered picker paints one cheap frame and nothing else; the
     * grid is built by the first real `onResume`.
     */
    private var revealed by mutableStateOf(false)
    private val lifecycleHandler by lazy { Handler(mainLooper) }
    private var stoppedTaskId: Int? = null
    private var noticeReceiverRegistered = false
    private var packageReceiverRegistered = false
    private val noticeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != SplitPickerNotice.ACTION_CHANGED) return
            recoveryNotice = intent.getStringExtra(SplitPickerNotice.EXTRA_MESSAGE).orEmpty()
        }
    }
    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action !in PACKAGE_CHANGE_ACTIONS) return
            // This receiver may well run before the cache's own, and a reload that read the old
            // list would put the removed app straight back on screen (1.4.2, 1.5.6).
            SplitLaunchCatalogCache.invalidate()
            refreshCatalogAsync()
            reportUninstall(intent)
        }
    }
    private val stoppedReporter = Runnable {
        val stoppedId = stoppedTaskId ?: return@Runnable
        stoppedTaskId = null
        sendCommand(
            method = SplitCommandContract.METHOD_PICKER_HIDDEN,
            extras = Bundle().apply {
                putInt(SplitCommandContract.EXTRA_PICKER_TASK_ID, stoppedId)
            },
        )
    }

    private val resultReceiver by lazy {
        object : ResultReceiver(Handler(mainLooper)) {
            override fun onReceiveResult(resultCode: Int, resultData: Bundle?) {
                busy = false
                busyPackage = null
                message = resultData?.getString(SplitCommandContract.RESULT_ERROR).orEmpty()
            }
        }
    }

    /**
     * A Parcel round-trip produces the framework ResultReceiver proxy, so the provider boundary
     * never needs to deserialize the anonymous receiver implementation.
     */
    private val remoteResultReceiver by lazy {
        val parcel = Parcel.obtain()
        try {
            resultReceiver.writeToParcel(parcel, 0)
            parcel.setDataPosition(0)
            ResultReceiver.CREATOR.createFromParcel(parcel)
        } finally {
            parcel.recycle()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        recoveryNotice = SplitPickerNotice.current(this)
        val nativeBackgroundBlur = SplitPickerWindowStyle.apply(this)
        setContent {
            SplitPickerTheme {
                SplitPickerScreen(
                    apps = apps,
                    revealed = revealed,
                    catalogLoading = catalogLoading,
                    busy = busy,
                    busyPackage = busyPackage,
                    message = message.ifBlank { recoveryNotice },
                    nativeBackgroundBlur = nativeBackgroundBlur,
                    onSelect = ::select,
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        if (!noticeReceiverRegistered) {
            ContextCompat.registerReceiver(
                this,
                noticeReceiver,
                IntentFilter(SplitPickerNotice.ACTION_CHANGED),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            noticeReceiverRegistered = true
        }
        if (!packageReceiverRegistered) {
            ContextCompat.registerReceiver(
                this,
                packageReceiver,
                IntentFilter().apply {
                    PACKAGE_CHANGE_ACTIONS.forEach(::addAction)
                    addDataScheme("package")
                },
                ContextCompat.RECEIVER_EXPORTED,
            )
            packageReceiverRegistered = true
        }
        recoveryNotice = SplitPickerNotice.current(this)
        refreshCatalogAsync()
    }

    override fun onResume() {
        super.onResume()
        revealed = true
        lifecycleHandler.removeCallbacks(stoppedReporter)
        stoppedTaskId = null
        // A picker can resume with the same Activity instance after its covered app was dismissed.
        // Report every real resume; coordinator reconciliation is idempotent and identity-checked.
        sendCommand(
            method = SplitCommandContract.METHOD_PICKER_VISIBLE,
            extras = Bundle().apply {
                putInt(SplitCommandContract.EXTRA_PICKER_TASK_ID, taskId)
            },
        )
    }

    override fun onStop() {
        if (noticeReceiverRegistered) {
            unregisterReceiver(noticeReceiver)
            noticeReceiverRegistered = false
        }
        if (packageReceiverRegistered) {
            unregisterReceiver(packageReceiver)
            packageReceiverRegistered = false
        }
        super.onStop()
        if (!isChangingConfigurations) {
            stoppedTaskId = taskId
            lifecycleHandler.removeCallbacks(stoppedReporter)
            lifecycleHandler.postDelayed(stoppedReporter, PICKER_HIDDEN_SETTLE_MS)
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        runCatching {
            SplitCommandContract.call(
                this,
                SplitCommandContract.METHOD_DIVIDER_RESIZED,
                Bundle.EMPTY,
            )
        }.onFailure { error ->
            Log.w(TAG, "Failed to report split divider resize", error)
        }
    }

    /**
     * Contract 1.5.6: the catalog list is only half of it - a pane that still remembers the
     * uninstalled package has to give itself back to its picker.
     *
     * Only a real uninstall qualifies. The removal half of an update carries `EXTRA_REPLACING` and
     * the package comes straight back, so treating it as a removal would drop a live selection for
     * no reason (1.11.2).
     */
    private fun reportUninstall(intent: Intent?) {
        if (intent?.action != Intent.ACTION_PACKAGE_REMOVED) return
        if (intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) return
        val removed = intent.data?.schemeSpecificPart?.takeIf(String::isNotBlank) ?: return
        // Through the provider like everything else: this Activity is its own process, and the
        // coordinator lives in exactly one of them (invariant 12).
        sendCommand(
            method = SplitCommandContract.METHOD_PACKAGE_REMOVED,
            extras = Bundle().apply {
                putString(SplitCommandContract.EXTRA_PACKAGE_NAME, removed)
            },
        )
    }

    private fun refreshCatalogAsync() {
        if (catalogLoadInFlight) {
            catalogReloadPending = true
            return
        }
        catalogLoadInFlight = true
        catalogReloadPending = false
        if (apps.isEmpty()) catalogLoading = true
        CATALOG_EXECUTOR.execute {
            val loaded = SplitPickerAppCatalog.load(applicationContext)
            runOnUiThread {
                catalogLoadInFlight = false
                if (!isDestroyed) {
                    apps = loaded
                    catalogLoading = false
                    if (catalogReloadPending) refreshCatalogAsync()
                }
            }
        }
    }

    private fun select(packageName: String) {
        if (busy) return
        busy = true
        busyPackage = packageName
        message = ""
        // Only on screen. The persisted notice belongs to whoever published it, and clearing it
        // here would put a synchronous preferences write on the thread that has to answer the tap
        // within a second (U6); the operation clears it on its own outcome.
        recoveryNotice = ""
        val delivered = sendCommand(
            method = SplitCommandContract.METHOD_SELECT,
            extras = Bundle().apply {
                putInt(SplitCommandContract.EXTRA_PICKER_TASK_ID, taskId)
                putString(SplitCommandContract.EXTRA_PACKAGE_NAME, packageName)
                putParcelable(SplitCommandContract.EXTRA_RESULT_RECEIVER, remoteResultReceiver)
            },
        )
        if (!delivered) {
            busy = false
            busyPackage = null
        }
    }

    private fun sendCommand(method: String, extras: Bundle): Boolean = runCatching {
        val error = SplitCommandContract.call(this, method, extras)
        check(error == null) { error.orEmpty() }
        true
    }.getOrElse {
        message = "Denza Apps не отвечает"
        Toast.makeText(applicationContext, message, Toast.LENGTH_LONG).show()
        false
    }

    private companion object {
        const val TAG = "DenzaSplitPicker"
        const val PICKER_HIDDEN_SETTLE_MS = 500L
        val PACKAGE_CHANGE_ACTIONS = SplitLaunchCatalogCache.PACKAGE_CHANGE_ACTIONS
        val CATALOG_EXECUTOR = Executors.newSingleThreadExecutor()
    }
}

internal object SplitPickerWindowStyle {
    fun apply(activity: ComponentActivity): Boolean {
        val window = activity.window
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.setBackgroundDrawable(ColorDrawable(AndroidColor.TRANSPARENT))
        window.statusBarColor = AndroidColor.TRANSPARENT
        window.navigationBarColor = AndroidColor.TRANSPARENT

        return runCatching {
            val unionWindowManager = Class.forName("android.view.UnionWindowManager")
            val manager = unionWindowManager
                .getMethod("getInstance", Context::class.java)
                .invoke(null, activity)
            val attributes = unionWindowManager
                .getMethod(
                    "setUnionWindowBackgroundBlur",
                    WindowManager.LayoutParams::class.java,
                    Boolean::class.javaPrimitiveType,
                )
                .invoke(manager, window.attributes, true) as WindowManager.LayoutParams
            window.attributes = attributes
        }.onFailure { error ->
            Log.w(TAG, "Native BYD window blur is unavailable; using gradient fallback", error)
        }.isSuccess
    }

    private const val TAG = "DenzaSplitPicker"
}

internal object SplitPickerAppCatalog {
    fun load(context: Context): List<SplitPickerApp> =
        SplitLaunchCatalogCache.apps(context) { scan(context) }

    /** The already-scanned list, so a re-created picker paints its grid on the first frame. */
    fun cached(): List<SplitPickerApp>? = SplitLaunchCatalogCache.cachedApps()

    private fun scan(context: Context): List<SplitPickerApp> {
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val seen = HashSet<String>()
        return context.packageManager
            .queryIntentActivities(launcher, PackageManager.GET_META_DATA)
            .mapNotNull { info ->
                val activityInfo = info.activityInfo ?: return@mapNotNull null
                val packageName = activityInfo.packageName
                if (
                    !SplitPickerVisibilityPolicy.isLauncherVisible(
                        packageName = packageName,
                        activityName = activityInfo.name,
                        showInAppList = readShowInAppList(context, packageName),
                    ) ||
                    !seen.add(packageName)
                ) {
                    return@mapNotNull null
                }
                SplitPickerApp(
                    packageName = packageName,
                    label = info.loadLabel(context.packageManager).toString(),
                    icon = runCatching {
                        info.loadIcon(context.packageManager).toBitmap(144, 144)
                    }.getOrNull(),
                )
            }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER, SplitPickerApp::label))
    }

    @Suppress("DEPRECATION")
    private fun readShowInAppList(context: Context, packageName: String): Boolean? {
        val packageInfo = runCatching {
            context.packageManager.getPackageInfo(
                packageName,
                PackageManager.GET_META_DATA or PackageManager.GET_PROVIDERS,
            )
        }.getOrNull() ?: return null

        packageInfo.applicationInfo?.metaData
            ?.takeIf { it.containsKey(SHOW_IN_APP_LIST) }
            ?.get(SHOW_IN_APP_LIST)
            ?.toString()
            ?.let(SplitPickerVisibilityPolicy::parseShowInAppList)
            ?.let { return it }

        val authorities = packageInfo.providers
            .orEmpty()
            .asSequence()
            .filter { provider ->
                provider.name?.endsWith(DYNA_CONFIG_PROVIDER_SUFFIX) == true ||
                    provider.authority
                        ?.split(';')
                        ?.any { it.endsWith(DYNA_CONFIG_PROVIDER_SUFFIX) } == true
            }
            .flatMap { it.authority.orEmpty().split(';').asSequence() }
            .map(String::trim)
            .filter(String::isNotBlank)
            .distinct()

        for (authority in authorities) {
            val value = runCatching {
                context.contentResolver.query(
                    Uri.parse("content://$authority"),
                    arrayOf(SHOW_IN_APP_LIST),
                    null,
                    null,
                    null,
                )?.use { cursor ->
                    val index = cursor.getColumnIndex(SHOW_IN_APP_LIST)
                    if (index < 0 || !cursor.moveToFirst() || cursor.isNull(index)) null
                    else SplitPickerVisibilityPolicy.parseShowInAppList(cursor.getString(index))
                }
            }.getOrNull()
            if (value != null) return value
        }
        return null
    }

    private const val SHOW_IN_APP_LIST = "ShowInAppList"
    private const val DYNA_CONFIG_PROVIDER_SUFFIX = "DynaConfigContentProvider"
}

@Composable
private fun SplitPickerTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            background = Background,
            surface = Background,
            onSurface = PrimaryText,
            primary = Accent,
        ),
        content = content,
    )
}

@Composable
private fun SplitPickerScreen(
    apps: List<SplitPickerApp>,
    revealed: Boolean,
    catalogLoading: Boolean,
    busy: Boolean,
    busyPackage: String?,
    message: String,
    nativeBackgroundBlur: Boolean,
    onSelect: (String) -> Unit,
) {
    Surface(modifier = Modifier.fillMaxSize(), color = Color.Transparent) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .background(if (nativeBackgroundBlur) NativeBlurOverlay else FallbackBackground),
        ) {
            val columnCount = if (maxWidth >= WidePaneMinimumWidth) 4 else 2
            // Правка W7 волны 7. Contract scenario 16: панель рисует свои декорации сама, и её
            // окно честно сообщает НУЛЕВОЙ статусбарный inset - нативный drag control BYD висит
            // ПОВЕРХ панели, не двигая её. Один statusBarsPadding() (f166d42) прижимал заголовок
            // к самому верху, где он сливался с drag control («раньше было не так»: до f166d42
            // стоял жёсткий top=42dp). Зазор - максимум из реального inset (полноэкранный пикер,
            // оставленный Back'ом, 1.6.3: жёсткие 42dp там резали заголовок) и собственного
            // воздуха панели, вместе с top-паддингом заголовка дающего прежние 42 dp.
            val statusBarInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = maxOf(statusBarInset, HeaderMinTopClearance)),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // Правка W7(б): заголовок один и тот же и в покое, и во время запуска. Строка
                // «Открываю …» дублировала крутилку на выбранной плитке и пригашение сетки -
                // владелец: «как будто бы лишняя» - и дёргала верхнюю зону панели.
                Text(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp, bottom = 14.dp),
                    text = message.ifBlank { "Выберите приложение" },
                    color = if (message.isBlank()) PrimaryText else Warning,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Normal,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!revealed) {
                    // One cheap frame for a picker nobody is looking at yet (1.13.1).
                    Spacer(Modifier.weight(1f))
                } else if (catalogLoading || apps.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            text = if (catalogLoading) {
                                "Загружаю приложения…"
                            } else {
                                "Приложения не найдены"
                            },
                            color = SecondaryText,
                            textAlign = TextAlign.Center,
                        )
                    }
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(columnCount),
                        modifier = Modifier
                            .width((columnCount * GridCellWidthDp).dp)
                            .weight(1f),
                        contentPadding = PaddingValues(vertical = 16.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalArrangement = Arrangement.spacedBy(32.dp),
                    ) {
                        items(apps, key = SplitPickerApp::packageName) { app ->
                            Box(
                                modifier = Modifier.fillMaxWidth(),
                                contentAlignment = Alignment.Center,
                            ) {
                                SplitPickerTile(
                                    app = app,
                                    enabled = !busy,
                                    launching = busy && app.packageName == busyPackage,
                                    onClick = { onSelect(app.packageName) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SplitPickerTile(
    app: SplitPickerApp,
    enabled: Boolean,
    launching: Boolean,
    onClick: () -> Unit,
) {
    val bitmap = remember(app.packageName, app.icon) { app.icon?.asImageBitmap() }
    Column(
        modifier = Modifier
            .size(width = 128.dp, height = 132.dp)
            .alpha(if (enabled || launching) 1f else 0.38f)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(top = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Правка W9 (v20 D4): выбранная плитка носит крутилку весь запуск - остальная сетка
        // пригашена, и тап не выглядит проигнорированным.
        Box(contentAlignment = Alignment.Center) {
            if (bitmap != null) {
                Image(
                    painter = BitmapPainter(bitmap),
                    contentDescription = null,
                    modifier = Modifier.size(72.dp),
                    contentScale = ContentScale.Fit,
                )
            } else {
                Icon(
                    imageVector = Icons.Outlined.Apps,
                    contentDescription = null,
                    modifier = Modifier.size(72.dp),
                    tint = SecondaryText,
                )
            }
            if (launching) {
                CircularProgressIndicator(
                    modifier = Modifier.size(64.dp),
                    color = Accent,
                    strokeWidth = 3.dp,
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(
            modifier = Modifier
                .fillMaxWidth()
                .height(40.dp),
            text = app.label,
            color = PrimaryText,
            fontSize = 16.sp,
            fontWeight = FontWeight.Normal,
            lineHeight = 18.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}

private const val GridCellWidthDp = 170

/**
 * Правка W7: собственный воздух панели над заголовком. Вместе с top-паддингом заголовка (12 dp)
 * даёт прежние 42 dp там, где окно панели сообщает нулевой статусбарный inset.
 */
private val HeaderMinTopClearance = 30.dp
private val WidePaneMinimumWidth = 820.dp
private val Background = Color(0xFF363940)
private val NativeBlurOverlay = Brush.verticalGradient(listOf(Color.Transparent, Color.Transparent))
private val FallbackBackground = Brush.verticalGradient(
    colorStops = arrayOf(
        0f to Color(0xFF19203A),
        0.48f to Color(0xFF11182B),
        1f to Color(0xFF070E1D),
    ),
)
private val PrimaryText = Color(0xFFF2F5F7)
private val SecondaryText = Color(0xFF98A1A8)
private val Accent = Color(0xFF52D5C7)
private val Warning = Color(0xFFFFB4A9)
