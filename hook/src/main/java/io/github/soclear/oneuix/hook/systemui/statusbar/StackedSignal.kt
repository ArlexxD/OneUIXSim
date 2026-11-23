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
import android.os.Looper
import android.telephony.ServiceState
import android.telephony.SignalStrength
import android.telephony.SubscriptionManager
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import io.github.libxposed.api.XposedModule
import io.github.soclear.oneuix.common.Package
import io.github.soclear.oneuix.hook.util.afterAttachTry
import io.github.soclear.oneuix.hook.util.reflect
import io.github.soclear.oneuix.hook.util.xlog
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt

@SuppressLint("StaticFieldLeak", "PrivateApi")
object StackedSignal {
    private data class MobileViewMeta(
        val slot: Int,
        val subId: Int?,
        val signalView: ImageView?,
    )

    private val trackedMobileViews: MutableMap<View, MobileViewMeta> =
        Collections.synchronizedMap(WeakHashMap())
    private val trackedSignalViews: MutableMap<ImageView, View> =
        Collections.synchronizedMap(WeakHashMap())
    private val signalDrawables: MutableMap<ImageView, StackedSignalDrawable> =
        Collections.synchronizedMap(WeakHashMap())
    private val hiddenMobileViews: MutableSet<View> =
        Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap()))

    private val activeSlotSubIds = ConcurrentHashMap<Int, Int>()
    private val slotLevels = ConcurrentHashMap<Int, Int>()
    private val telephonyManagersBySubId = ConcurrentHashMap<Int, TelephonyManager>()
    private val registeredCallbacks = ConcurrentHashMap<Int, TelephonyCallback>()

    @Volatile
    private var appContext: Context? = null

    context(xposedModule: XposedModule)
    fun apply() = afterAttachTry {
        try {
            classLoader
                .loadClass("com.android.systemui.statusbar.pipeline.mobile.ui.view.ModernStatusBarMobileView")
                .declaredMethods
                .filter { it.name == "constructAndBind" }
                .forEach { method ->
                    xposedModule.hook(method).intercept { chain ->
                        val result = chain.proceed()
                        val view = result as? View ?: return@intercept result
                        val context = (chain.args.getOrNull(0) as? Context) ?: view.context
                        appContext = context.applicationContext ?: context

                        val viewModel = chain.args.getOrNull(3) ?: return@intercept result
                        val subId = getMobileViewModelSubId(viewModel)
                        val slot = subId
                            ?.let(SubscriptionManager::getSlotIndex)
                            ?.takeIf { it >= 0 }
                            ?: getMobileViewModelSlot(viewModel)
                            ?: return@intercept result

                        if (slot in 0..1) {
                            if (subId != null) {
                                activeSlotSubIds[slot] = subId
                                registerSlotCallback(appContext, slot, subId)
                                updateSlotLevel(slot, subId)
                            }
                            val signalView = findSignalImageView(view)
                            trackedMobileViews[view] = MobileViewMeta(slot, subId, signalView)
                            if (signalView != null) {
                                trackedSignalViews[signalView] = view
                            }
                            refreshAllViews()
                        }
                        result
                    }
                }
        } catch (t: Throwable) {
            xlog("StackedSignal: constructAndBind hook failed", t)
        }

        try {
            val modernStatusBarViewClass = classLoader.loadClass(
                "com.android.systemui.statusbar.pipeline.shared.ui.view.ModernStatusBarView"
            )
            val setVisibleStateMethod = modernStatusBarViewClass.getDeclaredMethod(
                "setVisibleState",
                Int::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType
            )
            xposedModule.hook(setVisibleStateMethod).intercept { chain ->
                val view = chain.thisObject as? View
                val meta = view?.let { trackedMobileViews[it] }
                val result = if (meta != null && shouldHideSlot(meta.slot)) {
                    val newArgs = chain.args.toTypedArray()
                    newArgs[0] = 2 // STATE_HIDDEN
                    chain.proceed(newArgs)
                } else {
                    chain.proceed()
                }
                if (view != null && meta != null) {
                    applyViewState(view, meta)
                }
                result
            }

            val isIconVisibleMethod = modernStatusBarViewClass.getDeclaredMethod("isIconVisible")
            xposedModule.hook(isIconVisibleMethod).intercept { chain ->
                val meta = (chain.thisObject as? View)?.let { trackedMobileViews[it] }
                if (meta != null && shouldHideSlot(meta.slot)) {
                    false
                } else {
                    chain.proceed()
                }
            }
        } catch (t: Throwable) {
            xlog("StackedSignal: visibility hook failed", t)
        }

        try {
            val setImageDrawableMethod = ImageView::class.java.getDeclaredMethod(
                "setImageDrawable",
                Drawable::class.java
            )
            xposedModule.hook(setImageDrawableMethod).intercept { chain ->
                val imageView = chain.thisObject as? ImageView
                val mobileView = imageView?.let { trackedSignalViews[it] }
                    ?: return@intercept chain.proceed()

                if (chain.args[0] is StackedSignalDrawable) {
                    return@intercept chain.proceed()
                }

                refreshAllSlotLevels()
                val meta = trackedMobileViews[mobileView]
                if (meta != null && isDualSimActive() && !shouldHideSlot(meta.slot)) {
                    val newArgs = chain.args.toTypedArray()
                    newArgs[0] = getOrUpdateDrawable(imageView)
                    chain.proceed(newArgs)
                } else {
                    chain.proceed()
                }
            }

            val setImageResourceMethod = ImageView::class.java.getDeclaredMethod(
                "setImageResource",
                Int::class.javaPrimitiveType
            )
            xposedModule.hook(setImageResourceMethod).intercept { chain ->
                val imageView = chain.thisObject as? ImageView
                val mobileView = imageView?.let { trackedSignalViews[it] }
                    ?: return@intercept chain.proceed()

                refreshAllSlotLevels()
                val meta = trackedMobileViews[mobileView]
                if (meta != null && isDualSimActive() && !shouldHideSlot(meta.slot)) {
                    imageView.setImageDrawable(getOrUpdateDrawable(imageView))
                    null
                } else {
                    chain.proceed()
                }
            }

            val setImageLevelMethod = ImageView::class.java.getDeclaredMethod(
                "setImageLevel",
                Int::class.javaPrimitiveType
            )
            xposedModule.hook(setImageLevelMethod).intercept { chain ->
                val imageView = chain.thisObject as? ImageView
                if (imageView == null || imageView !in trackedSignalViews) {
                    return@intercept chain.proceed()
                }
                val result = chain.proceed()
                refreshAllSlotLevels()
                refreshAllViews()
                result
            }
        } catch (t: Throwable) {
            xlog("StackedSignal: ImageView hook failed", t)
        }
    }

    private fun getPrimarySlot(): Int {
        val dataSubId = runCatching { SubscriptionManager.getDefaultDataSubscriptionId() }.getOrNull()
        if (dataSubId != null && SubscriptionManager.isValidSubscriptionId(dataSubId)) {
            val slot = SubscriptionManager.getSlotIndex(dataSubId)
            if (slot in 0..1) return slot
        }
        return 0
    }

    @SuppressLint("MissingPermission")
    private fun isDualSimActive(): Boolean {
        val sm = appContext?.getSystemService(SubscriptionManager::class.java)
        val activeCount = runCatching { sm?.activeSubscriptionInfoList?.size }.getOrNull()
        if (activeCount != null) return activeCount >= 2
        return activeSlotSubIds.containsKey(0) && activeSlotSubIds.containsKey(1)
    }

    private fun shouldHideSlot(slot: Int): Boolean {
        if (!isDualSimActive()) return false
        return slot != getPrimarySlot()
    }

    private fun refreshAllViews() {
        val entries = synchronized(trackedMobileViews) { trackedMobileViews.entries.toList() }
        entries.forEach { (view, meta) ->
            applyViewState(view, meta)
        }
    }

    private fun applyViewState(view: View, meta: MobileViewMeta) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            view.post { applyViewState(view, meta) }
            return
        }

        if (shouldHideSlot(meta.slot)) {
            hiddenMobileViews.add(view)
            view.visibility = View.GONE
        } else {
            if (hiddenMobileViews.remove(view)) {
                view.visibility = View.VISIBLE
            }
            if (isDualSimActive()) {
                val signalView = meta.signalView ?: return
                val drawable = getOrUpdateDrawable(signalView)
                if (signalView.drawable !== drawable) {
                    signalView.setImageDrawable(drawable)
                } else {
                    drawable.invalidateSelf()
                    signalView.invalidate()
                }
            }
        }
    }

    private fun getOrUpdateDrawable(imageView: ImageView): StackedSignalDrawable {
        val drawable = synchronized(signalDrawables) {
            signalDrawables.getOrPut(imageView) {
                StackedSignalDrawable(imageView.resources.displayMetrics.density)
            }
        }
        drawable.topLevel = slotLevels[0] ?: 0
        drawable.bottomLevel = slotLevels[1] ?: 0
        return drawable
    }

    @SuppressLint("MissingPermission")
    private fun registerSlotCallback(context: Context?, slot: Int, subId: Int) {
        if (context == null || !SubscriptionManager.isValidSubscriptionId(subId)) return
        if (registeredCallbacks.containsKey(subId)) return
        val tm = getTelephonyManager(context, subId) ?: return
        val callback = SlotTelephonyCallback(slot, subId)
        runCatching {
            tm.registerTelephonyCallback(context.mainExecutor, callback)
            registeredCallbacks[subId] = callback
        }.onFailure {
            xlog("StackedSignal: registerTelephonyCallback failed for slot $slot", it)
        }
    }

    private class SlotTelephonyCallback(
        private val slot: Int,
        private val subId: Int,
    ) : TelephonyCallback(),
        TelephonyCallback.SignalStrengthsListener,
        TelephonyCallback.ServiceStateListener,
        TelephonyCallback.ActiveDataSubscriptionIdListener {

        override fun onSignalStrengthsChanged(signalStrength: SignalStrength) {
            updateSlotLevel(slot, subId, signalStrength)
            refreshAllViews()
        }

        override fun onServiceStateChanged(serviceState: ServiceState) {
            updateSlotLevel(slot, subId, null, serviceState)
            refreshAllViews()
        }

        override fun onActiveDataSubscriptionIdChanged(subId: Int) {
            refreshAllSlotLevels()
            refreshAllViews()
        }
    }

    private fun refreshAllSlotLevels() {
        activeSlotSubIds.forEach { (slot, subId) ->
            updateSlotLevel(slot, subId)
        }
    }

    @SuppressLint("MissingPermission")
    private fun updateSlotLevel(
        slot: Int,
        subId: Int,
        signalStrength: SignalStrength? = null,
        serviceState: ServiceState? = null,
    ) {
        val tm = getTelephonyManager(appContext, subId) ?: return
        val state = (serviceState ?: runCatching { tm.serviceState }.getOrNull())?.state
        val level = when (state) {
            ServiceState.STATE_OUT_OF_SERVICE,
            ServiceState.STATE_POWER_OFF,
            ServiceState.STATE_EMERGENCY_ONLY -> 0
            else -> ((signalStrength ?: runCatching { tm.signalStrength }.getOrNull())?.level ?: 0)
                .coerceIn(0, 4)
        }
        slotLevels[slot] = level
    }

    private fun getTelephonyManager(context: Context?, subId: Int): TelephonyManager? {
        if (context == null || !SubscriptionManager.isValidSubscriptionId(subId)) return null
        return telephonyManagersBySubId.computeIfAbsent(subId) {
            context.getSystemService(TelephonyManager::class.java)
                ?.createForSubscriptionId(subId)
                ?: return@computeIfAbsent null
        }
    }

    @SuppressLint("DiscouragedApi")
    private fun findSignalImageView(root: View): ImageView? {
        val directId = root.resources.getIdentifier("mobile_signal", "id", Package.SYSTEMUI)
        if (directId != 0) {
            root.findViewById<ImageView>(directId)?.let { return it }
        }
        return findSignalImageViewRecursive(root)
    }

    private fun findSignalImageViewRecursive(view: View): ImageView? {
        if (view is ImageView && view.id != View.NO_ID) {
            val name = runCatching { view.resources.getResourceEntryName(view.id) }.getOrNull()
            if (name != null && name.contains("signal", ignoreCase = true)) {
                return view
            }
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                findSignalImageViewRecursive(view.getChildAt(i))?.let { return it }
            }
        }
        return null
    }

    private fun getMobileViewModelSlot(viewModel: Any): Int? {
        runCatching {
            val commonImpl = viewModel.reflect["commonImpl"]!!
            val slot = commonImpl.reflect["slotId"] as Int
            if (slot >= 0) return slot
        }
        runCatching {
            val subId = viewModel.reflect.call("getSubscriptionId") as Int
            val slot = SubscriptionManager.getSlotIndex(subId)
            if (slot >= 0) return slot
        }
        return null
    }

    private fun getMobileViewModelSubId(viewModel: Any): Int? =
        runCatching { viewModel.reflect.call("getSubscriptionId") as Int }
            .getOrNull()
            ?.takeIf(SubscriptionManager::isValidSubscriptionId)

    private class StackedSignalDrawable(
        private val density: Float,
    ) : Drawable() {
        @Volatile
        var topLevel: Int = 0

        @Volatile
        var bottomLevel: Int = 0

        private var tintList: ColorStateList? = null
        private var drawableAlpha: Int = 255
        private var activeColorFilter: ColorFilter? = null

        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }
        private val rect = RectF()
        private val topRatios = floatArrayOf(0.32f, 0.54f, 0.77f, 1.0f)

        override fun getIntrinsicWidth(): Int = (15.5f * density).roundToInt()
        override fun getIntrinsicHeight(): Int = (13.5f * density).roundToInt()

        override fun draw(canvas: Canvas) {
            val b = bounds
            if (b.isEmpty) return

            val w = b.width().toFloat()
            val h = b.height().toFloat()
            val padX = w * 0.04f
            val padY = h * 0.06f
            val uw = w - 2f * padX
            val uh = h - 2f * padY

            val barW = uw / 6.16f
            val gapX = barW * 0.72f
            val radius = barW / 2f

            val dotH = (barW * 1.18f).coerceAtMost(uh * 0.24f)
            val gapY = (barW * 0.65f).coerceAtMost(uh * 0.12f)
            val topMaxH = (uh - dotH - gapY).coerceAtLeast(barW)

            val topBaseY = b.top + padY + topMaxH
            val dotTopY = topBaseY + gapY
            val dotBottomY = dotTopY + dotH

            val baseColor = tintList?.getColorForState(state, tintList!!.defaultColor) ?: Color.WHITE
            val activeAlpha = (Color.alpha(baseColor) * drawableAlpha) / 255
            val inactiveAlpha = (activeAlpha * 0.36f).roundToInt()

            paint.colorFilter = activeColorFilter

            for (i in 0 until 4) {
                val left = b.left + padX + i * (barW + gapX)
                val right = left + barW

                // SIM 1 (barras em cima)
                val barH = (topMaxH * topRatios[i]).coerceAtLeast(barW)
                paint.color = baseColor
                paint.alpha = if (topLevel > i) activeAlpha else inactiveAlpha
                rect.set(left, topBaseY - barH, right, topBaseY)
                canvas.drawRoundRect(rect, radius, radius, paint)

                // SIM 2 (pontos por baixo)
                paint.color = baseColor
                paint.alpha = if (bottomLevel > i) activeAlpha else inactiveAlpha
                rect.set(left, dotTopY, right, dotBottomY)
                canvas.drawRoundRect(rect, radius, radius, paint)
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

        override fun isStateful(): Boolean = tintList?.isStateful == true

        override fun onStateChange(state: IntArray): Boolean {
            if (tintList != null) {
                invalidateSelf()
                return true
            }
            return false
        }

        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }
}
