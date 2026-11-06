package com.leslie.tvhome

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import java.util.concurrent.Executors

/** One app on the grid: a rounded 16:9 tile with the name under it when focused. */
class TileCell(ctx: Context, val tileW: Int, val tileH: Int) : FrameLayout(ctx) {
    val card = FrameLayout(ctx)
    val image = ImageView(ctx).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        setBackgroundColor(Color.argb(60, 255, 255, 255))
    }
    val label: TextView = ctx.text(15f, bold = true).apply {
        gravity = Gravity.CENTER
        maxLines = 1
        alpha = 0f
        ellipsize = android.text.TextUtils.TruncateAt.END
        setShadowLayer(dp(6).toFloat(), 0f, dp(1).toFloat(), Color.argb(140, 0, 0, 0))
    }
    private var jiggle: ObjectAnimator? = null
    /** Soft shadow under the card, only visible while it is lifted (focused). */
    private val shadow = ImageView(ctx).apply {
        setImageBitmap(shadowFor(ctx, tileW, tileH))
        alpha = 0f
    }
    private val shadowPad = dp(SHADOW_PAD)
    private val focusTween = Tween()
    private val bumpTween = Tween()
    /** The light glancing off a focused tile; only drawn while focused. */
    private val sheen = View(ctx).apply {
        background = android.graphics.drawable.GradientDrawable().apply {
            gradientType = android.graphics.drawable.GradientDrawable.RADIAL_GRADIENT
            colors = intArrayOf(Color.argb(78, 255, 255, 255), Color.argb(26, 255, 255, 255), Color.TRANSPARENT)
            gradientRadius = tileW * 0.62f
            setGradientCenter(0.5f, 0.5f)
        }
        alpha = 0f
        visibility = INVISIBLE
    }

    init {
        clipChildren = false
        clipToPadding = false
        isFocusable = true
        isClickable = true
        isLongClickable = true
        addView(shadow, LayoutParams(tileW + shadowPad * 2, tileH + shadowPad * 2))
        shadow.translationX = -shadowPad.toFloat()
        shadow.translationY = -shadowPad.toFloat()
        card.addView(image, LayoutParams(tileW, tileH))
        card.addView(sheen, LayoutParams(tileW, tileH))
        card.clipRounded(dp(11).toFloat())
        addView(card, LayoutParams(tileW, tileH))
        // The name hangs below the tile without taking layout space, so rows keep an exact pitch.
        addView(label, LayoutParams(tileW + dp(30), LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL))
        label.translationY = tileH + dp(17).toFloat()
    }

    /**
     * tvOS focus: the tile lifts towards the viewer (larger, a deep shadow under it), tilting in
     * from the direction of travel; with [tvos] a soft highlight glides across it as it settles,
     * light catching a glass surface. Driven by [Tween], so it plays even with animations off.
     */
    internal fun setFocusedLook(
        f: Boolean, fromDx: Int, fromDy: Int, showLabel: Boolean = true, tvos: Boolean = true,
        motion: FocusMotionPolicy.Target = FocusMotionPolicy.target(PerformancePolicy.effects(PerformancePolicy.BALANCED),
            !showLabel, false, tileW, tileW / 4, resources.displayMetrics.density),
    ) {
        bumpTween.cancel()
        translationX = 0f; translationY = 0f
        val s0 = card.scaleX
        val s1 = if (f) motion.scale else 1f
        // Draw the lifted tile (and its shadow) above its neighbours.
        translationZ = if (f) 1f else 0f
        card.cameraDistance = tileW * 14f
        val tilt = f && tvos && (fromDx != 0 || fromDy != 0)
        val rx0 = if (tilt) -fromDy * motion.tilt else card.rotationX
        val ry0 = if (tilt) fromDx * motion.tilt else card.rotationY
        val y0 = card.translationY
        val y1 = if (f) -motion.liftPx else 0f
        val z1 = if (f) dp(4).toFloat() else 0f
        val z0 = card.translationZ
        val sh0 = shadow.alpha
        val sh1 = if (f) 0.9f else 0f
        val shy0 = shadow.translationY
        val shy1 = -shadowPad + if (f) dp(18).toFloat() else 0f
        val l0 = label.alpha
        val l1 = if (f && showLabel) 1f else 0f
        val g0 = sheen.alpha
        val g1 = if (f && tvos) 1f else 0f
        if (f && tvos) {
            // The highlight enters from the side the focus came from and comes to rest near the top.
            sheen.translationX = -fromDx * tileW * 0.45f
            sheen.translationY = -tileH * 0.25f - fromDy * tileH * 0.3f
        }
        val gx0 = sheen.translationX
        val gy0 = sheen.translationY
        sheen.visibility = if (f && tvos || g0 > 0f) VISIBLE else INVISIBLE
        focusTween.start(if (f) motion.enterMs else motion.exitMs, if (f && motion.spring) Motion.spring else Motion.ease, end = {
            if (!f) sheen.visibility = INVISIBLE
        }) { p ->
            val e = p.coerceAtMost(1f)
            card.scaleX = lerp(s0, s1, p).coerceIn(1f, motion.maxScale); card.scaleY = card.scaleX
            card.translationY = lerp(y0, y1, e)
            card.rotationX = lerp(rx0, 0f, e)
            card.rotationY = lerp(ry0, 0f, e)
            card.translationZ = lerp(z0, z1, e)
            shadow.alpha = lerp(sh0, sh1, e)
            shadow.scaleX = card.scaleX; shadow.scaleY = card.scaleX
            shadow.translationY = lerp(shy0, shy1, e)
            label.alpha = lerp(l0, l1, e)
            sheen.alpha = lerp(g0, g1, e)
            if (f) { sheen.translationX = lerp(gx0, 0f, e); sheen.translationY = lerp(gy0, -tileH * 0.25f, e) }
        }
    }

    companion object {
        const val SHADOW_PAD = 30
        // 5/6-column Dock tiles have different sizes; one global bitmap
        // silently stretches the old shadow after switching layout. Cache by size.
        private val shadows = mutableMapOf<Pair<Int, Int>, android.graphics.Bitmap>()

        @Synchronized
        fun shadowFor(ctx: Context, w: Int, h: Int): android.graphics.Bitmap =
            shadows.getOrPut(w to h) {
                LiquidGlass.shadow(w, h, ctx.dp(11).toFloat(), ctx.dp(SHADOW_PAD))
            }
    }

    internal fun bump(dx: Int, dy: Int, rapid: Boolean) {
        if (rapid) return
        bumpTween.start(150, android.view.animation.LinearInterpolator()) { p ->
            val f = if (p < 0.5f) p * 2 else (1 - p) * 2
            translationX = dx * dp(3) * f
            translationY = dy * dp(2) * f
        }
    }

    internal fun resetMotion() {
        focusTween.cancel(); bumpTween.cancel(); setJiggle(false)
        translationX = 0f; translationY = 0f; translationZ = 0f
        card.scaleX = 1f; card.scaleY = 1f
        card.rotationX = 0f; card.rotationY = 0f
        card.translationY = 0f; card.translationZ = 0f
        shadow.alpha = 0f; label.alpha = 0f; sheen.alpha = 0f; sheen.visibility = INVISIBLE
    }

    fun setJiggle(on: Boolean) {
        jiggle?.cancel()
        jiggle = null
        card.rotation = 0f
        if (on) jiggle = ObjectAnimator.ofFloat(card, "rotation", -1.4f, 1.4f).apply {
            duration = 130
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            start()
        }
    }
}

class TileAdapter(
    private val repo: AppRepo,
    private val tileW: Int,
    private val tileH: Int,
    private val listener: Listener,
) : RecyclerView.Adapter<TileAdapter.VH>() {

    interface Listener {
        fun onTileFocused(pos: Int, cell: TileCell)
        fun onTileClicked(pos: Int, cell: TileCell)
        fun onTileLongPressed(pos: Int, cell: TileCell)
        /** Return true if the key was consumed (edge bumps, move mode). */
        fun onTileKey(pos: Int, cell: TileCell, keyCode: Int): Boolean
    }

    class VH(val cell: TileCell) : RecyclerView.ViewHolder(cell)

    var apps: MutableList<AppEntry> = mutableListOf()
        private set
    var movingPkg: String? = null
    private val loader = Executors.newFixedThreadPool(2)
    private var attached: RecyclerView? = null
    private var disposed = false

    /** Tiles in the dock (the first row) hide their name, which would sit on the glass edge. */
    var dockCount = 0

    /** Tilt-in and gliding highlight on focus ([Prefs.focusTvos]); otherwise only the zoom and shadow. */
    var tvosFocus = true
    internal var effects = PerformancePolicy.effects(PerformancePolicy.BALANCED)
    var gapPx = 0
    var rapidFocus = false
        private set

    /** Direction of the last D-pad move, used for the tilt-in. */
    var lastDx = 0
    var lastDy = 0

    internal fun applyEffects(value: PerformancePolicy.Effects, tvos: Boolean) {
        effects = value
        tvosFocus = tvos
        refreshFocus()
    }

    fun updateRapidFocus(rapid: Boolean) {
        if (rapidFocus == rapid) return
        rapidFocus = rapid
        refreshFocus()
    }

    private fun renderFocus(cell: TileCell, position: Int, dx: Int = 0, dy: Int = 0) {
        val motion = FocusMotionPolicy.target(effects, position in 0 until dockCount, rapidFocus, tileW, gapPx,
            cell.resources.displayMetrics.density)
        cell.setFocusedLook(cell.hasFocus(), dx, dy, showLabel = position >= dockCount, tvos = tvosFocus && !rapidFocus, motion = motion)
    }

    private fun refreshFocus() {
        val view = attached ?: return
        for (i in 0 until view.childCount) {
            val cell = view.getChildAt(i) as? TileCell ?: continue
            renderFocus(cell, view.getChildAdapterPosition(cell))
        }
    }

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) { attached = recyclerView }
    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) { attached = null }
    override fun onViewRecycled(holder: VH) { holder.cell.resetMotion(); holder.cell.tag = null }

    fun close() { disposed = true; loader.shutdownNow(); attached = null }

    /**
     * Updates only the tiles that changed, so a package event doesn't rebind
     * (and blank) the whole grid. [changed] is redrawn even if its entry is equal.
     */
    fun submit(list: List<AppEntry>, changed: String? = null) {
        val old = apps
        val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize() = old.size
            override fun getNewListSize() = list.size
            override fun areItemsTheSame(o: Int, n: Int) = old[o].pkg == list[n].pkg
            override fun areContentsTheSame(o: Int, n: Int) = old[o] == list[n] && list[n].pkg != changed
        })
        apps = list.toMutableList()
        diff.dispatchUpdatesTo(this)
    }

    fun move(from: Int, to: Int) {
        val a = apps.removeAt(from)
        apps.add(to, a)
        notifyItemMoved(from, to)
    }

    override fun getItemCount() = apps.size
    override fun getItemId(position: Int) = apps[position].pkg.hashCode().toLong()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val cell = TileCell(parent.context, tileW, tileH)
        cell.layoutParams = RecyclerView.LayoutParams(tileW, tileH)
        val vh = VH(cell)
        cell.setOnFocusChangeListener { _, f ->
            val p = vh.bindingAdapterPosition
            renderFocus(cell, p, lastDx, lastDy)
            if (f && p != RecyclerView.NO_POSITION) listener.onTileFocused(p, cell)
        }
        cell.setOnClickListener {
            vh.bindingAdapterPosition.takeIf { it >= 0 }?.let { listener.onTileClicked(it, cell) }
        }
        cell.setOnLongClickListener {
            vh.bindingAdapterPosition.takeIf { it >= 0 }?.let { listener.onTileLongPressed(it, cell) }
            true
        }
        cell.setOnKeyListener { _, code, e ->
            e.action == KeyEvent.ACTION_DOWN && vh.bindingAdapterPosition >= 0 &&
                listener.onTileKey(vh.bindingAdapterPosition, cell, code)
        }
        return vh
    }

    override fun onBindViewHolder(h: VH, position: Int) {
        val app = apps[position]
        val cell = h.cell
        cell.label.text = app.label
        val same = cell.tag == app.pkg
        cell.tag = app.pkg
        cell.setJiggle(app.pkg == movingPkg)
        renderFocus(cell, position)
        val cached = repo.cachedTile(app.pkg)
        if (cached != null) {
            cell.image.setImageBitmap(cached)
        } else {
            // Rebinding the same app keeps its old picture until the new one is ready.
            if (!same) cell.image.setImageDrawable(null)
            loader.execute {
                val bmp = repo.tile(app)
                cell.post { if (!disposed && cell.tag == app.pkg) cell.image.setImageBitmap(bmp) }
            }
        }
    }
}
