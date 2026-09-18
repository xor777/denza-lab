package dev.denza.apps.feature.hud

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.graphics.drawable.Icon
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.RemoteViews
import android.widget.TextView
import androidx.core.graphics.createBitmap
import java.io.ByteArrayOutputStream
import java.lang.reflect.Field
import java.util.Locale
import kotlin.math.roundToInt

class YandexNotificationArtworkListener : NotificationListenerService() {
    override fun onListenerConnected() {
        super.onListenerConnected()
        HudNotificationArtworkRuntime.setListenerConnected(true)
        if (!HudNotificationArtworkRuntime.isFeatureEnabled()) return
        val active = runCatching { activeNotifications.orEmpty() }
            .getOrElse { error ->
                HudNotificationArtworkRuntime.clear(null, "active-notifications:${error.shortName()}")
                emptyArray()
            }
            .filter { it.packageName == YANDEX_PACKAGE }
            .sortedByDescending { it.postTime }
        val found = active.any(::process)
        if (!found) {
            HudNotificationArtworkRuntime.clear(null, "no-active-yandex-artwork")
            HudNotificationGuidanceRuntime.clear()
        }
    }

    override fun onListenerDisconnected() {
        HudNotificationArtworkRuntime.clear(null, "listener-disconnected")
        HudNotificationGuidanceRuntime.clear()
        HudNotificationArtworkRuntime.setListenerConnected(false)
        HudNotificationAccessCoordinator.ensureAccess(this) {
            if (
                HudGuidanceSettings.isEnabled(this) &&
                HudNotificationAccessCoordinator.isAccessEnabled(this)
            ) {
                requestRebind(
                    ComponentName(this, YandexNotificationArtworkListener::class.java),
                )
            }
        }
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        if (
            !HudNotificationArtworkRuntime.isFeatureEnabled() ||
            sbn?.packageName != YANDEX_PACKAGE
        ) {
            return
        }
        process(sbn)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        if (sbn?.packageName == YANDEX_PACKAGE) {
            HudNotificationArtworkRuntime.clear(sbn.key, "notification-removed")
            HudNotificationGuidanceRuntime.clear()
        }
        super.onNotificationRemoved(sbn)
    }

    override fun onDestroy() {
        HudNotificationArtworkRuntime.clear(null, "listener-destroyed")
        HudNotificationGuidanceRuntime.clear()
        HudNotificationArtworkRuntime.setListenerConnected(false)
        super.onDestroy()
    }

    /** The last rejection reason that reached logcat; the same reason is not said twice in a row. */
    private var lastRejectDetail: String? = null

    private fun process(sbn: StatusBarNotification): Boolean {
        val result = runCatching {
            YandexRemoteViewsArtworkExtractor.extract(this, sbn.notification)
        }.getOrElse { error ->
            YandexArtworkExtraction(null, "extract:${error.shortName()}")
        }
        val capturedAtMs = SystemClock.uptimeMillis()
        val guidanceUpdated = result.guidanceFields?.let { fields ->
            HudNotificationGuidanceRuntime.update(fields, capturedAtMs)
        } == true
        if (result.detail == "no-remote-views") {
            HudNotificationGuidanceRuntime.clear()
        }
        val png = result.png
        if (png == null) {
            HudNotificationArtworkRuntime.reject(sbn.key, result.detail)
            if (result.detail != lastRejectDetail) {
                lastRejectDetail = result.detail
                Log.i(
                    TAG,
                    "artwork rejected key=${sbn.key} reason=${result.detail} " +
                        "backgroundGuidance=$guidanceUpdated",
                )
            }
            return guidanceUpdated
        }
        lastRejectDetail = null
        HudNotificationArtworkRuntime.update(
            notificationKey = sbn.key,
            png = png,
            capturedAtMs = capturedAtMs,
        )
        return true
    }

    private fun Throwable.shortName(): String =
        javaClass.simpleName.ifEmpty { "error" }

    companion object {
        private const val TAG = "DenzaHudArtwork"
    }
}

private const val YANDEX_PACKAGE = "ru.yandex.yandexnavi"

internal data class YandexArtworkExtraction(
    val png: ByteArray?,
    val detail: String,
    val guidanceFields: YandexNotificationGuidanceFields? = null,
)

internal object YandexRemoteViewsArtworkExtractor {
    private const val OUTPUT_SIZE = 192
    private const val CONTENT_SIZE = 168
    private const val MAX_SOURCE_SIZE = 512

    @Suppress("DEPRECATION")
    fun extract(context: Context, notification: Notification): YandexArtworkExtraction {
        val packageContext = runCatching {
            context.createPackageContext(YANDEX_PACKAGE, Context.CONTEXT_RESTRICTED)
        }.getOrElse { error ->
            return YandexArtworkExtraction(null, "package-context:${error.shortName()}")
        }
        val layouts = listOfNotNull(
            notification.bigContentView,
            notification.contentView,
            notification.headsUpContentView,
        )
        if (layouts.isEmpty()) {
            return YandexArtworkExtraction(null, "no-remote-views")
        }

        var lastApplyFailure: String? = null
        var lastGuidanceFields: YandexNotificationGuidanceFields? = null
        layouts.forEach { remoteViews ->
            val actionFields = YandexRemoteViewsActionExtractor.extract(
                packageContext,
                remoteViews,
            )
            val root = runCatching {
                val parent = FrameLayout(packageContext)
                remoteViews.apply(packageContext, parent)
            }.getOrElse { error ->
                lastApplyFailure = "remoteviews-apply:${error.shortName()}"
                if (YandexNotificationGuidanceParser.parse(actionFields) != null) {
                    return YandexArtworkExtraction(
                        png = null,
                        detail = lastApplyFailure!!,
                        guidanceFields = actionFields,
                    )
                }
                return@forEach
            }
            val guidanceFields = actionFields.merge(
                extractGuidanceFields(root, packageContext),
            )
            if (YandexNotificationGuidanceParser.parse(guidanceFields) != null) {
                lastGuidanceFields = guidanceFields
            }
            val png = runCatching {
                extractFromRoot(root, packageContext)
            }.getOrElse { error ->
                lastApplyFailure = "remoteviews-render:${error.shortName()}"
                null
            }
            if (png != null) {
                return YandexArtworkExtraction(
                    png = png,
                    detail = "notification",
                    guidanceFields = guidanceFields,
                )
            }
        }
        return YandexArtworkExtraction(
            png = null,
            detail = lastApplyFailure ?: "no-maneuver-candidate",
            guidanceFields = lastGuidanceFields,
        )
    }

    private fun extractGuidanceFields(
        root: View,
        packageContext: Context,
    ): YandexNotificationGuidanceFields {
        var fields = YandexNotificationGuidanceFields()
        walk(root) { view ->
            val name = resourceName(view, packageContext).lowercase(Locale.ROOT)
            val value = when (view) {
                is TextView -> view.text?.toString().orEmpty()
                is ImageView -> view.contentDescription?.toString().orEmpty()
                else -> ""
            }.trim()
            if (value.isEmpty()) return@walk
            fields = fields.withValue(name, value)
        }
        return fields
    }

    private fun extractFromRoot(root: View, packageContext: Context): ByteArray? {
        val candidates = buildList {
            walk(root) { view ->
                val image = view as? ImageView ?: return@walk
                val drawable = image.drawable ?: return@walk
                val rendered = renderCandidate(drawable) ?: return@walk
                add(
                    HudArtworkCandidate(
                        token = rendered.png,
                        resourceName = resourceName(image, packageContext),
                        width = rendered.width,
                        height = rendered.height,
                        opaqueRectangularBackground = rendered.opaqueRectangularBackground,
                    ),
                )
            }
        }
        return HudArtworkCandidatePolicy.select(candidates)?.token
    }

    private fun walk(view: View, visit: (View) -> Unit) {
        visit(view)
        val group = view as? ViewGroup ?: return
        for (index in 0 until group.childCount) {
            walk(group.getChildAt(index), visit)
        }
    }

    private fun resourceName(view: View, packageContext: Context): String {
        if (view.id == View.NO_ID) return ""
        return runCatching {
            packageContext.resources.getResourceEntryName(view.id)
        }.recoverCatching {
            view.resources.getResourceEntryName(view.id)
        }.getOrDefault("")
    }

    private data class RenderedCandidate(
        val png: ByteArray,
        val width: Int,
        val height: Int,
        val opaqueRectangularBackground: Boolean,
    )

    private data class PixelBounds(
        val rect: Rect,
        val visibleFraction: Float,
    )

    private fun renderCandidate(drawable: Drawable): RenderedCandidate? {
        val width = drawable.intrinsicWidth
            .takeIf { it > 0 }
            ?.coerceAtMost(MAX_SOURCE_SIZE)
            ?: OUTPUT_SIZE
        val height = drawable.intrinsicHeight
            .takeIf { it > 0 }
            ?.coerceAtMost(MAX_SOURCE_SIZE)
            ?: OUTPUT_SIZE
        if (width < 1 || height < 1) return null

        val source = createBitmap(width, height)
        val sourceCanvas = Canvas(source)
        val oldBounds = Rect(drawable.bounds)
        try {
            drawable.setBounds(0, 0, width, height)
            drawable.draw(sourceCanvas)
        } catch (_: RuntimeException) {
            source.recycle()
            return null
        } finally {
            drawable.bounds = oldBounds
        }

        val pixels = visibleBounds(source)
        if (pixels == null) {
            source.recycle()
            return null
        }
        val fillsCanvas = pixels.rect.left == 0 &&
            pixels.rect.top == 0 &&
            pixels.rect.right == width &&
            pixels.rect.bottom == height &&
            pixels.visibleFraction >= 0.88f

        val output = createBitmap(OUTPUT_SIZE, OUTPUT_SIZE)
        val scale = minOf(
            CONTENT_SIZE.toFloat() / pixels.rect.width(),
            CONTENT_SIZE.toFloat() / pixels.rect.height(),
        )
        val targetWidth = (pixels.rect.width() * scale).roundToInt().coerceAtLeast(1)
        val targetHeight = (pixels.rect.height() * scale).roundToInt().coerceAtLeast(1)
        val targetLeft = (OUTPUT_SIZE - targetWidth) / 2f
        val targetTop = (OUTPUT_SIZE - targetHeight) / 2f
        Canvas(output).drawBitmap(
            source,
            pixels.rect,
            RectF(
                targetLeft,
                targetTop,
                targetLeft + targetWidth,
                targetTop + targetHeight,
            ),
            Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG),
        )
        source.recycle()
        whiten(output)

        val bytes = ByteArrayOutputStream()
        output.compress(Bitmap.CompressFormat.PNG, 100, bytes)
        output.recycle()
        return RenderedCandidate(
            png = bytes.toByteArray(),
            width = width,
            height = height,
            opaqueRectangularBackground = fillsCanvas,
        )
    }

    private fun visibleBounds(bitmap: Bitmap): PixelBounds? {
        var left = bitmap.width
        var top = bitmap.height
        var right = -1
        var bottom = -1
        var visible = 0
        val row = IntArray(bitmap.width)
        for (y in 0 until bitmap.height) {
            bitmap.getPixels(row, 0, bitmap.width, 0, y, bitmap.width, 1)
            for (x in row.indices) {
                if (Color.alpha(row[x]) == 0) continue
                visible++
                if (x < left) left = x
                if (x > right) right = x
                if (y < top) top = y
                if (y > bottom) bottom = y
            }
        }
        if (visible == 0) return null
        return PixelBounds(
            rect = Rect(left, top, right + 1, bottom + 1),
            visibleFraction = visible.toFloat() / (bitmap.width * bitmap.height),
        )
    }

    private fun whiten(bitmap: Bitmap) {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        for (index in pixels.indices) {
            val alpha = Color.alpha(pixels[index])
            if (alpha != 0) {
                pixels[index] = Color.argb(alpha, 255, 255, 255)
            }
        }
        bitmap.setPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
    }

    private fun Throwable.shortName(): String =
        javaClass.simpleName.ifEmpty { "error" }
}

private object YandexRemoteViewsActionExtractor {
    fun extract(
        packageContext: Context,
        remoteViews: RemoteViews,
    ): YandexNotificationGuidanceFields {
        return runCatching {
            val actionsField = RemoteViews::class.java.getDeclaredField("mActions")
            actionsField.isAccessible = true
            val actions = actionsField.get(remoteViews) as? Collection<*>
                ?: return YandexNotificationGuidanceFields()
            var fields = YandexNotificationGuidanceFields()
            actions.filterNotNull().forEach { action ->
                val actionFields = collectFields(action.javaClass)
                val viewId = actionFields.readNumber("viewId", action)?.toInt() ?: return@forEach
                val viewName = resourceName(packageContext, viewId)
                val methodName = actionFields.readString("methodName", action).orEmpty()
                val value = actionFields.readValue(action)
                if (methodName == "setText" && value is CharSequence) {
                    fields = fields.withValue(viewName, value.toString())
                } else if (
                    viewName == "primaryicontinted" &&
                    methodName.contains("Image", ignoreCase = true)
                ) {
                    val resourceId = when (value) {
                        is Number -> value.toInt()
                        is Icon -> if (value.type == Icon.TYPE_RESOURCE) value.resId else 0
                        else -> 0
                    }
                    if (resourceId != 0) {
                        fields = fields.copy(
                            maneuverResourceName = resourceName(packageContext, resourceId),
                        )
                    }
                }
            }
            fields
        }.getOrDefault(YandexNotificationGuidanceFields())
    }

    private fun collectFields(type: Class<*>): List<Field> = buildList {
        var current: Class<*>? = type
        while (current != null) {
            current.declaredFields.forEach { field ->
                runCatching { field.isAccessible = true }
                    .onSuccess { add(field) }
            }
            current = current.superclass
        }
    }

    private fun List<Field>.readNumber(name: String, target: Any): Number? =
        firstOrNull { it.name == name }
            ?.let { runCatching { it.get(target) as? Number }.getOrNull() }

    private fun List<Field>.readString(name: String, target: Any): String? =
        firstOrNull { it.name == name }
            ?.let { runCatching { it.get(target) as? String }.getOrNull() }

    private fun List<Field>.readValue(target: Any): Any? {
        firstOrNull { it.name == "value" }?.let { field ->
            runCatching { field.get(target) }.getOrNull()?.let { return it }
        }
        return firstNotNullOfOrNull { field ->
            if (
                field.name == "viewId" ||
                field.name == "methodName" ||
                field.type.isPrimitive
            ) {
                null
            } else {
                runCatching { field.get(target) }.getOrNull()
                    ?.takeIf { it is CharSequence || it is Number || it is Icon }
            }
        }
    }

    private fun resourceName(context: Context, id: Int): String =
        runCatching { context.resources.getResourceEntryName(id) }
            .getOrDefault("")
            .lowercase(Locale.ROOT)
}

private fun YandexNotificationGuidanceFields.withValue(
    resourceName: String,
    value: String,
): YandexNotificationGuidanceFields = when (resourceName.lowercase(Locale.ROOT)) {
    "primaryicontinted", "nextmaneuver" ->
        copy(maneuverDescription = maneuverDescription.ifEmpty { value })
    "titleview" -> copy(title = title.ifEmpty { value })
    "descriptionview" -> copy(description = description.ifEmpty { value })
    "remainingdistanceview" ->
        copy(remainingDistance = remainingDistance.ifEmpty { value })
    "remainingtimeview" -> copy(remainingTime = remainingTime.ifEmpty { value })
    "timeofarrivalview" -> copy(arrivalTime = arrivalTime.ifEmpty { value })
    else -> this
}

private fun YandexNotificationGuidanceFields.merge(
    rendered: YandexNotificationGuidanceFields,
): YandexNotificationGuidanceFields = YandexNotificationGuidanceFields(
    maneuverResourceName = maneuverResourceName.ifEmpty {
        rendered.maneuverResourceName
    },
    maneuverDescription = rendered.maneuverDescription.ifEmpty {
        maneuverDescription
    },
    title = rendered.title.ifEmpty { title },
    description = description.ifEmpty { rendered.description },
    remainingDistance = rendered.remainingDistance.ifEmpty { remainingDistance },
    remainingTime = rendered.remainingTime.ifEmpty { remainingTime },
    arrivalTime = rendered.arrivalTime.ifEmpty { arrivalTime },
)
