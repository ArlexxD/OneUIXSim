
package io.github.soclear.oneuix.hook.systemui.statusbar

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import io.github.libxposed.api.XposedModule
import io.github.soclear.oneuix.common.Package
import io.github.soclear.oneuix.hook.util.afterAttachTry
import io.github.soclear.oneuix.hook.util.xlog
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min
import kotlin.math.roundToInt

@SuppressLint("StaticFieldLeak", "PrivateApi")
object WifiSignal {

    private val trackedWifiSignalViews: MutableSet<ImageView> =
        Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap()))

    private val wifiDrawables: MutableMap<ImageView, OneUi85WifiDrawable> =
        Collections.synchronizedMap(WeakHashMap())

    private val bypassedViews: MutableSet<ImageView> =
        Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap()))

    private val resourceExclamationState: MutableMap<ImageView, Boolean> =
        Collections.synchronizedMap(WeakHashMap())

    private val networkCallbackRegistered = AtomicBoolean(false)

    private val LEVEL_REGEX =
        Regex("(?:_|signal|level)([0-4])(?:_|$)", RegexOption.IGNORE_CASE)

    private val EXCLAMATION_KEYWORDS = listOf(
        "no_internet",
        "no_network",
        "unvalidated",
        "exclamation",
        "partially",
        "warning",
    )

    private val BYPASS_ICON_KEYWORDS = listOf(
        "error",
        "off",
        "disabled",
        "disconnected",
    )

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var currentWifiLevel: Int = 4

    @Volatile
    private var isWifiUnvalidated: Boolean = false

    context(xposedModule: XposedModule)
    fun apply() = afterAttachTry {

        try {
            classLoader
                .loadClass(
                    "com.android.systemui.statusbar.pipeline.wifi.ui.view.ModernStatusBarWifiView"
                )
                .declaredMethods
                .filter { it.name == "constructAndBind" }
                .forEach { method ->

                    xposedModule.hook(method).intercept { chain ->

                        val result = chain.proceed()
                        val view = result as? View ?: return@intercept result

                        val context =
                            (chain.args.getOrNull(0) as? Context) ?: view.context

                        appContext = context.applicationContext ?: context

                        registerNetworkCallback(appContext)
                        updateWifiLevelFromSystem()

                        val signalView = findWifiSignalImageView(view)

                        if (signalView != null) {
                            trackedWifiSignalViews.add(signalView)
                            applyDrawableToView(signalView)
                        }

                        result
                    }
                }

        } catch (t: Throwable) {
            xlog("WifiSignal: constructAndBind hook failed", t)
        }

        try {
            val setImageResourceMethod =
                ImageView::class.java.getDeclaredMethod(
                    "setImageResource",
                    Int::class.javaPrimitiveType
                )

            xposedModule.hook(setImageResourceMethod).intercept { chain ->

                val imageView = chain.thisObject as? ImageView

                if (imageView == null || imageView !in trackedWifiSignalViews) {
                    return@intercept chain.proceed()
                }

                val resId = chain.args[0] as? Int
                    ?: return@intercept chain.proceed()

                if (resId == 0) {
                    bypassedViews.add(imageView)
                    resourceExclamationState.remove(imageView)
                    return@intercept chain.proceed()
                }

                val resName = runCatching {
                    imageView.resources.getResourceEntryName(resId)
                }.getOrNull()

                if (resName != null && isBypassWifiIcon(resName)) {
                    bypassedViews.add(imageView)
                    resourceExclamationState.remove(imageView)
                    return@intercept chain.proceed()
                }

                bypassedViews.remove(imageView)

                if (resName != null) {
                    resourceExclamationState[imageView] =
                        isExclamationWifiIcon(resName)
                }

                val parsedLevel = resName?.let(::parseLevelFromResourceName)

                if (parsedLevel != null) {
                    currentWifiLevel = parsedLevel
                } else {
                    updateWifiLevelFromSystem()
                }

                imageView.setImageDrawable(getOrUpdateDrawable(imageView))
                null
            }

            val setImageDrawableMethod =
                ImageView::class.java.getDeclaredMethod(
                    "setImageDrawable",
                    Drawable::class.java
                )

            xposedModule.hook(setImageDrawableMethod).intercept { chain ->

                val imageView = chain.thisObject as? ImageView

                if (imageView == null || imageView !in trackedWifiSignalViews) {
                    return@intercept chain.proceed()
                }

                val incoming = chain.args[0] as? Drawable

                if (
                    incoming == null ||
                    incoming is OneUi85WifiDrawable ||
                    imageView in bypassedViews
                ) {
                    return@intercept chain.proceed()
                }

                updateWifiLevelFromSystem()

                val newArgs = chain.args.toTypedArray()
                newArgs[0] = getOrUpdateDrawable(imageView)

                chain.proceed(newArgs)
            }

            val setImageLevelMethod =
                ImageView::class.java.getDeclaredMethod(
                    "setImageLevel",
                    Int::class.javaPrimitiveType
                )

            xposedModule.hook(setImageLevelMethod).intercept { chain ->

                val imageView = chain.thisObject as? ImageView

                if (imageView == null || imageView !in trackedWifiSignalViews) {
                    return@intercept chain.proceed()
                }

                val result = chain.proceed()

                if (imageView !in bypassedViews) {
                    val level = (chain.args[0] as? Int)
                        ?.coerceIn(0, 4)
                        ?: return@intercept result

                    currentWifiLevel = level
                    refreshAllViews()
                }

                result
            }

        } catch (t: Throwable) {
            xlog("WifiSignal: ImageView hook failed", t)
        }
    }

    @SuppressLint("MissingPermission")
    context(xposedModule: XposedModule)
    private fun registerNetworkCallback(context: Context?) {

        if (
            context == null ||
            !networkCallbackRegistered.compareAndSet(false, true)
        ) return

        val cm = context.getSystemService(ConnectivityManager::class.java)
            ?: return

        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()

        runCatching {

            cm.registerNetworkCallback(
                request,
                object : ConnectivityManager.NetworkCallback() {

                    override fun onCapabilitiesChanged(
                        network: Network,
                        networkCapabilities: NetworkCapabilities,
                    ) {

                        isWifiUnvalidated =
                            !networkCapabilities.hasCapability(
                                NetworkCapabilities.NET_CAPABILITY_VALIDATED
                            )

                        extractWifiLevel(context, networkCapabilities)?.let { level ->
                            currentWifiLevel = level
                        }

                        refreshAllViews()
                    }

                    override fun onLost(network: Network) {
                        isWifiUnvalidated = false
                        updateWifiLevelFromSystem()
                        refreshAllViews()
                    }
                }
            )

        }.onFailure {
            networkCallbackRegistered.set(false)
            xlog("WifiSignal: registerNetworkCallback failed", it)
        }
    }

    @SuppressLint("MissingPermission")
    private fun updateWifiLevelFromSystem() {

        val context = appContext ?: return

        val cm = context.getSystemService(ConnectivityManager::class.java)
            ?: return

        val activeNetwork = runCatching {
            cm.activeNetwork
        }.getOrNull() ?: return

        val capabilities = runCatching {
            cm.getNetworkCapabilities(activeNetwork)
        }.getOrNull() ?: return

        if (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            return
        }

        isWifiUnvalidated =
            !capabilities.hasCapability(
                NetworkCapabilities.NET_CAPABILITY_VALIDATED
            )

        extractWifiLevel(context, capabilities)?.let { level ->
            currentWifiLevel = level
        }
    }

    private fun extractWifiLevel(
        context: Context,
        capabilities: NetworkCapabilities,
    ): Int? {

        val wifiManager = context.getSystemService(WifiManager::class.java)
            ?: return null

        val rssiFromInfo = (capabilities.transportInfo as? WifiInfo)?.rssi

        val rssi = when {
            rssiFromInfo != null && rssiFromInfo > -127 -> rssiFromInfo

            capabilities.signalStrength !=
                NetworkCapabilities.SIGNAL_STRENGTH_UNSPECIFIED ->
                capabilities.signalStrength

            else -> return null
        }

        val maxLevel = wifiManager.maxSignalLevel.coerceAtLeast(1)

        val rawLevel = wifiManager.calculateSignalLevel(rssi)
            .coerceIn(0, maxLevel)

        return ((rawLevel * 4f) / maxLevel)
            .roundToInt()
            .coerceIn(0, 4)
    }

    private fun isExclamationWifiIcon(resName: String): Boolean {
        val lower = resName.lowercase()
        return EXCLAMATION_KEYWORDS.any { lower.contains(it) }
    }

    private fun isBypassWifiIcon(resName: String): Boolean {
        val lower = resName.lowercase()
        return BYPASS_ICON_KEYWORDS.any { lower.contains(it) }
    }

    private fun parseLevelFromResourceName(resName: String): Int? =
        LEVEL_REGEX.find(resName)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?.coerceIn(0, 4)

    private fun refreshAllViews() {
        val views = synchronized(trackedWifiSignalViews) {
            trackedWifiSignalViews.toList()
        }

        views.forEach { applyDrawableToView(it) }
    }

    private fun applyDrawableToView(imageView: ImageView) {

        if (Looper.myLooper() != Looper.getMainLooper()) {
            imageView.post {
                applyDrawableToView(imageView)
            }
            return
        }

        if (imageView in bypassedViews) return

        val drawable = getOrUpdateDrawable(imageView)

        if (imageView.drawable !== drawable) {
            imageView.setImageDrawable(drawable)
        } else {
            drawable.invalidateSelf()
            imageView.invalidate()
        }
    }

    private fun getOrUpdateDrawable(
        imageView: ImageView
    ): OneUi85WifiDrawable {

        val drawable = synchronized(wifiDrawables) {
            wifiDrawables.getOrPut(imageView) {
                OneUi85WifiDrawable(
                    imageView.resources.displayMetrics.density
                )
            }
        }

        drawable.levelValue = currentWifiLevel

        drawable.showExclamation =
            resourceExclamationState[imageView] ?: isWifiUnvalidated

        return drawable
    }

    @SuppressLint("DiscouragedApi")
    private fun findWifiSignalImageView(root: View): ImageView? {

        val directId = root.resources.getIdentifier(
            "wifi_signal",
            "id",
            Package.SYSTEMUI
        )

        if (directId != 0) {
            root.findViewById<ImageView>(directId)?.let {
                return it
            }
        }

        return findWifiSignalRecursive(root)
    }

    private fun findWifiSignalRecursive(view: View): ImageView? {

        if (view is ImageView && view.id != View.NO_ID) {

            val name = runCatching {
                view.resources.getResourceEntryName(view.id)
            }.getOrNull()

            if (name != null) {
                val lower = name.lowercase()

                if (
                    lower == "wifi_signal" ||
                    (lower.contains("wifi") && lower.contains("signal"))
                ) {
                    return view
                }
            }
        }

        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                findWifiSignalRecursive(view.getChildAt(i))?.let {
                    return it
                }
            }
        }

        return null
    }

    /**
     * One UI 8.5-inspired Wi-Fi icon.
     *
     * Only the visual geometry is customized here.
     * Signal calculation and network behavior remain unchanged.
     */
    private class OneUi85WifiDrawable(
        private val density: Float,
    ) : Drawable() {

        @Volatile
        var levelValue: Int = 4

        @Volatile
        var showExclamation: Boolean = false

        private var tintList: ColorStateList? = null
        private var drawableAlpha: Int = 255
        private var activeColorFilter: ColorFilter? = null

        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }

        private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
        }

        private val arcRect = RectF()
        private val badgeRect = RectF()

        // Slightly larger footprint for better visibility in the S22 status bar.
        override fun getIntrinsicWidth(): Int =
            (18.0f * density).roundToInt()

        override fun getIntrinsicHeight(): Int =
            (14.6f * density).roundToInt()

        override fun draw(canvas: Canvas) {

            val b = bounds
            if (b.isEmpty) return

            val w = b.width().toFloat()
            val h = b.height().toFloat()

            // More vertical room for taller, more open Wi-Fi curves.
            val padLeft = w * 0.07f
            val padRight = w * 0.13f
            val padTop = h * 0.045f
            val padBottom = h * 0.075f

            val uw = w - padLeft - padRight
            val uh = h - padTop - padBottom

            if (uw <= 0f || uh <= 0f) return

            val topY = b.top + padTop
            val bottomY = b.bottom - padBottom

            // Slightly left of center to leave room for the exclamation mark.
            val cx = b.left + padLeft + uw * 0.47f

            val scaleBase = min(uh, uw * 0.82f)

            // Slightly thicker strokes for clearer rendering at status-bar size.
            val strokeW = (scaleBase * 0.18f)
                .coerceAtLeast(1.5f * density)

            val dotRadius = strokeW * 0.64f

            // Lowered center gives the upper arcs more vertical height.
            val cy = bottomY - dotRadius

            val maxR3ByHeight =
                (cy - topY - strokeW * 0.55f)

            val maxR3ByWidth =
                (uw * 0.50f - strokeW * 0.60f) / 0.707f

            val r3 = min(maxR3ByHeight, maxR3ByWidth)
                .coerceAtLeast(dotRadius + strokeW)

            // Balanced spacing between the three arcs.
            val gap = (
                (r3 - dotRadius - 2.35f * strokeW) / 3f
            ).coerceAtLeast(strokeW * 0.38f)

            val r1 = dotRadius + gap + strokeW / 2f
            val r2 = r1 + strokeW + gap
            val r3Final = r2 + strokeW + gap

            val baseColor = tintList?.let {
                it.getColorForState(state, it.defaultColor)
            } ?: Color.WHITE

            val activeAlpha =
                (Color.alpha(baseColor) * drawableAlpha) / 255

            val inactiveAlpha =
                (activeAlpha * 0.36f).roundToInt()

            fillPaint.color = baseColor
            fillPaint.colorFilter = activeColorFilter

            // Wi-Fi origin dot.
            fillPaint.alpha =
                if (levelValue >= 1) activeAlpha else inactiveAlpha

            canvas.drawCircle(
                cx,
                cy,
                dotRadius,
                fillPaint
            )

            arcPaint.color = baseColor
            arcPaint.colorFilter = activeColorFilter
            arcPaint.strokeWidth = strokeW

            val radii = floatArrayOf(
                r1,
                r2,
                r3Final
            )

            for (i in radii.indices) {

                val r = radii[i]

                arcPaint.alpha =
                    if (levelValue >= i + 2) activeAlpha else inactiveAlpha

                arcRect.set(
                    cx - r,
                    cy - r,
                    cx + r,
                    cy + r
                )

                // Preserve the opening on the right for the "!" indicator.
                val sweep = when {
                    !showExclamation -> 88f
                    i == 0 -> 66f
                    i == 1 -> 72f
                    else -> 88f
                }

                canvas.drawArc(
                    arcRect,
                    -134f,
                    sweep,
                    false,
                    arcPaint
                )
            }

            // No-internet / unvalidated indicator.
            if (showExclamation) {

                val exW = strokeW * 0.82f
                val exDotR = exW * 0.52f

                val exCx = b.left + padLeft + uw * 0.90f

                val exDotCy = bottomY - exDotR

                val exBarBottom =
                    exDotCy - exDotR - strokeW * 0.52f

                val exBarTop =
                    (bottomY - uh * 0.50f)
                        .coerceAtMost(exBarBottom - exW)

                fillPaint.alpha = activeAlpha

                badgeRect.set(
                    exCx - exW / 2f,
                    exBarTop,
                    exCx + exW / 2f,
                    exBarBottom
                )

                canvas.drawRoundRect(
                    badgeRect,
                    exW / 2f,
                    exW / 2f,
                    fillPaint
                )

                canvas.drawCircle(
                    exCx,
                    exDotCy,
                    exDotR,
                    fillPaint
                )
            }
        }

        override fun setAlpha(alpha: Int) {
            drawableAlpha = alpha
            invalidateSelf()
        }

        override fun setColorFilter(colorFilter: ColorFilter?) {
            activeColorFilter = colorFilter
            invalidateSelf()
        }

        override fun setTintList(tint: ColorStateList?) {
            tintList = tint
            invalidateSelf()
        }

        override fun isStateful(): Boolean =
            tintList?.isStateful == true

        override fun onStateChange(state: IntArray): Boolean {
            if (tintList != null) {
                invalidateSelf()
                return true
            }
            return false
        }

        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int =
            PixelFormat.TRANSLUCENT
    }
}
