package com.marketplace.deliverymerchant

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.media.RingtoneManager
import android.net.Uri
import android.os.Bundle
import android.os.Vibrator
import android.text.InputType
import android.text.TextUtils
import android.util.LruCache
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentChange
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

// ───────────────────────── Design tokens ─────────────────────────
private val GREEN = Color.parseColor("#1E7B3A")
private val GREEN_DARK = Color.parseColor("#145A2A")
private val GREEN_SOFT = Color.parseColor("#E8F5EC")
private val ORANGE = Color.parseColor("#FF5A1F")
private val ORANGE_SOFT = Color.parseColor("#FFF1EA")
private val YELLOW_SOFT = Color.parseColor("#FFF8DB")
private val INK = Color.parseColor("#1F2937")
private val GREY = Color.parseColor("#6B7280")
private val LINE = Color.parseColor("#E5E7EB")
private val BG = Color.parseColor("#F6F7F8")
private val RED = Color.parseColor("#D93025")
private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

@Suppress("DEPRECATION")
class MainActivity : Activity() {

    private val auth by lazy { FirebaseAuth.getInstance() }
    private val db by lazy { FirebaseFirestore.getInstance() }
    private val io = Executors.newFixedThreadPool(2)
    private val imageCache = LruCache<String, Bitmap>(40)

    // Shell views
    private lateinit var headerHost: LinearLayout
    private lateinit var bodyHost: FrameLayout
    private lateinit var navBar: LinearLayout
    private lateinit var topInset: View
    private lateinit var bottomInset: View

    // Merchant state
    private var merchantId = ""
    private var shopName = ""
    private var shopOpen = true
    private var shopDoc: DocumentSnapshot? = null

    // Live data
    private var orders = listOf<DocumentSnapshot>()
    private var products = listOf<DocumentSnapshot>()
    private var ordersListener: ListenerRegistration? = null
    private var productsListener: ListenerRegistration? = null
    private var firstOrdersSnapshot = true

    // UI state
    private var currentTab = "orders"
    private var orderFilter = "new"
    private var menuQuery = ""
    private var ordersRender: (() -> Unit)? = null
    private var menuRender: (() -> Unit)? = null

    // ───────────────────────── Lifecycle ─────────────────────────
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = GREEN
        title = "Delivery Merchant"

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
        }
        topInset = View(this).apply { setBackgroundColor(GREEN) }
        root.addView(topInset, lp(MATCH, 0))
        headerHost = vbox()
        root.addView(headerHost, lp(MATCH, WRAP))
        bodyHost = FrameLayout(this)
        root.addView(bodyHost, lp(MATCH, 0, 1f))
        navBar = hbox().apply {
            setBackgroundColor(Color.WHITE)
            elevation = dp(8).toFloat()
        }
        root.addView(navBar, lp(MATCH, dp(60)))
        bottomInset = View(this).apply { setBackgroundColor(Color.WHITE) }
        root.addView(bottomInset, lp(MATCH, 0))

        root.setOnApplyWindowInsetsListener { _, insets ->
            topInset.layoutParams = topInset.layoutParams.apply { height = insets.systemWindowInsetTop }
            bottomInset.layoutParams = bottomInset.layoutParams.apply { height = insets.systemWindowInsetBottom }
            insets
        }
        setContentView(root)

        val user = auth.currentUser
        if (user != null && !user.isAnonymous) {
            showLoading("Loading your shop…")
            resolveMerchant(user.uid)
        } else {
            if (user?.isAnonymous == true) auth.signOut()
            showLogin()
        }
    }

    override fun onDestroy() {
        stopListeners()
        io.shutdown()
        super.onDestroy()
    }

    // ───────────────────────── Helpers ─────────────────────────
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun lp(
        w: Int, h: Int, weight: Float = 0f,
        l: Int = 0, t: Int = 0, r: Int = 0, b: Int = 0
    ) = LinearLayout.LayoutParams(w, h, weight).apply { setMargins(dp(l), dp(t), dp(r), dp(b)) }

    private fun shape(fill: Int, radius: Int = 12, stroke: Int = 0): GradientDrawable =
        GradientDrawable().apply {
            setColor(fill)
            cornerRadius = dp(radius).toFloat()
            if (stroke != 0) setStroke(dp(1), stroke)
        }

    private fun ripple(bg: GradientDrawable) =
        RippleDrawable(ColorStateList.valueOf(Color.argb(40, 0, 0, 0)), bg, null)

    private fun tv(label: String, size: Float = 14f, color: Int = INK, bold: Boolean = false) =
        TextView(this).apply {
            this.text = label
            textSize = size
            setTextColor(color)
            if (bold) typeface = Typeface.DEFAULT_BOLD
        }

    private fun vbox() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private fun hbox() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    private fun card(pad: Int = 14) = vbox().apply {
        setPadding(dp(pad), dp(pad), dp(pad), dp(pad))
        background = shape(Color.WHITE, 16, LINE)
    }

    private fun cta(label: String, color: Int = ORANGE, onClick: () -> Unit) =
        tv(label, 15f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = ripple(shape(color, 14))
            isClickable = true
            setOnClickListener { onClick() }
        }

    private fun ghostButton(label: String, color: Int = GREEN, onClick: () -> Unit) =
        tv(label, 15f, color, true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(13), dp(16), dp(13))
            background = ripple(shape(Color.WHITE, 14, color))
            isClickable = true
            setOnClickListener { onClick() }
        }

    private fun callButton(
        label: String,
        known: String,
        emptyMsg: String = "Number not available.",
        lookup: (((String) -> Unit) -> Unit)? = null
    ) = tv(label, 18f, GREEN, true).apply {
        gravity = Gravity.CENTER
        contentDescription = if (label.contains("🛵")) "Call rider" else "Call customer"
        setPadding(dp(14), dp(8), dp(14), dp(8))
        background = ripple(shape(Color.WHITE, 24, GREEN))
        isClickable = true
        setOnClickListener {
            if (known.isNotBlank()) dial(known)
            else if (lookup != null) lookup { p -> if (p.isBlank()) toast(emptyMsg) else dial(p) }
            else toast(emptyMsg)
        }
    }

    private fun inputField(
        hint: String,
        value: String = "",
        type: Int = InputType.TYPE_CLASS_TEXT,
        lines: Int = 1
    ) = EditText(this).apply {
        this.hint = hint
        setText(value)
        inputType = type or (if (lines > 1) InputType.TYPE_TEXT_FLAG_MULTI_LINE else 0)
        textSize = 15f
        setTextColor(INK)
        setHintTextColor(Color.parseColor("#9CA3AF"))
        background = shape(Color.WHITE, 12, LINE)
        setPadding(dp(14), dp(13), dp(14), dp(13))
        if (lines > 1) {
            minLines = lines
            maxLines = lines + 3
            gravity = Gravity.TOP or Gravity.START
        }
    }

    private fun roundClip(v: View, radiusDp: Int) {
        val r = dp(radiusDp).toFloat()
        v.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, r)
            }
        }
        v.clipToOutline = true
    }

    private fun sectionTitle(page: LinearLayout, label: String) {
        page.addView(tv(label, 18f, INK, true), lp(MATCH, WRAP, 0f, 0, 18, 0, 8))
    }

    private fun kv(label: String, value: String, bold: Boolean = false, color: Int = INK): View {
        val row = hbox().apply { setPadding(0, dp(3), 0, dp(3)) }
        row.addView(tv(label, if (bold) 15f else 14f, if (bold) INK else GREY, bold), lp(0, WRAP, 1f))
        row.addView(tv(value, if (bold) 15f else 14f, color, bold))
        return row
    }

    private fun money(v: Double): String =
        "₱" + if (v % 1.0 == 0.0) String.format(Locale.US, "%,.0f", v)
        else String.format(Locale.US, "%,.2f", v)

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    private fun num(d: DocumentSnapshot, field: String, def: Double = 0.0): Double =
        (d.get(field) as? Number)?.toDouble() ?: def

    private fun dial(phone: String) {
        try {
            startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(phone.trim()))))
        } catch (_: Exception) {
            toast("No phone app available.")
        }
    }

    private fun loadImage(url: String, target: ImageView) {
        if (url.isBlank()) return
        target.tag = url
        imageCache.get(url)?.let { target.setImageBitmap(it); return }
        io.execute {
            try {
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.connectTimeout = 8000
                conn.readTimeout = 8000
                val opts = BitmapFactory.Options().apply { inSampleSize = 2 }
                val bmp = conn.inputStream.use { BitmapFactory.decodeStream(it, null, opts) }
                if (bmp != null) {
                    imageCache.put(url, bmp)
                    runOnUiThread { if (target.tag == url) target.setImageBitmap(bmp) }
                }
            } catch (_: Exception) {
            }
        }
    }

    private fun newPage(): LinearLayout {
        val sv = ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(BG)
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val page = vbox().apply { setPadding(dp(16), dp(14), dp(16), dp(24)) }
        sv.addView(page, ViewGroup.LayoutParams(MATCH, WRAP))
        bodyHost.removeAllViews()
        bodyHost.addView(sv, FrameLayout.LayoutParams(MATCH, MATCH))
        return page
    }

    // ───────────────────────── Order helpers ─────────────────────────
    private fun stage(status: String): String = when (status.lowercase(Locale.US)) {
        "placed", "pending" -> "new"
        "accepted", "confirmed", "preparing" -> "cooking"
        "ready", "picked_up", "pickedup", "on_the_way", "out_for_delivery", "delivering", "in_transit" -> "ready"
        else -> "history"
    }

    private fun statusText(status: String): String = when (status.lowercase(Locale.US)) {
        "placed", "pending" -> "NEW"
        "accepted", "confirmed" -> "ACCEPTED"
        "preparing" -> "PREPARING"
        "ready" -> "READY"
        "picked_up", "pickedup", "on_the_way", "out_for_delivery", "delivering", "in_transit" -> "ON THE WAY"
        "delivered", "completed" -> "DELIVERED"
        "cancelled", "canceled", "rejected" -> "CANCELLED"
        else -> status.uppercase(Locale.US)
    }

    private fun statusColor(status: String): Int = when (stage(status)) {
        "new" -> ORANGE
        "cooking" -> Color.parseColor("#E8A200")
        "ready" -> GREEN
        else -> if (statusText(status) == "CANCELLED") RED else GREY
    }

    private fun createdMillis(d: DocumentSnapshot): Long =
        ((d.get("createdAt") as? Timestamp)?.seconds ?: (System.currentTimeMillis() / 1000)) * 1000

    private fun ago(d: DocumentSnapshot): String {
        val mins = (System.currentTimeMillis() - createdMillis(d)) / 60000
        return when {
            mins < 1 -> "Just now"
            mins < 60 -> "$mins min ago"
            mins < 24 * 60 -> "${mins / 60} h ago"
            else -> SimpleDateFormat("MMM d, h:mm a", Locale.getDefault()).format(Date(createdMillis(d)))
        }
    }

    // ───────────────────────── Auth / merchant resolution ─────────────────────────
    private fun showLoading(msg: String) {
        headerHost.visibility = View.GONE
        navBar.visibility = View.GONE
        val box = vbox().apply { gravity = Gravity.CENTER }
        box.addView(tv("🏪", 48f))
        box.addView(tv(msg, 15f, GREY), lp(WRAP, WRAP, 0f, 0, 8, 0, 0))
        bodyHost.removeAllViews()
        bodyHost.addView(box, FrameLayout.LayoutParams(MATCH, MATCH))
    }

    private fun showLogin() {
        stopListeners()
        headerHost.visibility = View.GONE
        navBar.visibility = View.GONE
        val page = newPage()
        page.setPadding(0, 0, 0, dp(24))

        val hero = vbox().apply {
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(48), dp(24), dp(40))
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(GREEN_DARK, GREEN)
            ).apply {
                val r = dp(28).toFloat()
                cornerRadii = floatArrayOf(0f, 0f, 0f, 0f, r, r, r, r)
            }
        }
        hero.addView(tv("🏪", 46f))
        hero.addView(tv("Merchant Portal", 26f, Color.WHITE, true))
        hero.addView(
            tv("Manage orders, menu and your shop", 14f, Color.argb(215, 255, 255, 255)),
            lp(WRAP, WRAP, 0f, 0, 6, 0, 0)
        )
        page.addView(hero, lp(MATCH, WRAP))

        val form = card(18)
        page.addView(form, lp(MATCH, WRAP, 0f, 16, -18, 16, 0))
        form.addView(tv("Welcome back 👋", 20f, INK, true))
        form.addView(tv("Sign in with your merchant account.", 13f, GREY), lp(WRAP, WRAP, 0f, 0, 2, 0, 8))
        val email = inputField("Email address", "", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS)
        val pass = inputField("Password", "", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        form.addView(email, lp(MATCH, WRAP, 0f, 0, 8, 0, 0))
        form.addView(pass, lp(MATCH, WRAP, 0f, 0, 8, 0, 0))

        var busy = false
        val btn = cta("Sign in") {}
        form.addView(btn, lp(MATCH, WRAP, 0f, 0, 16, 0, 0))
        btn.setOnClickListener {
            if (busy) return@setOnClickListener
            val e = email.text.toString().trim()
            val p = pass.text.toString()
            if (e.isBlank() || p.isBlank()) {
                toast("Enter your email and password.")
                return@setOnClickListener
            }
            busy = true
            btn.text = "Signing in…"
            btn.alpha = 0.6f
            auth.signInWithEmailAndPassword(e, p)
                .addOnSuccessListener {
                    val uid = auth.currentUser?.uid
                    if (uid == null) {
                        deny("Could not verify your account.")
                    } else {
                        showLoading("Loading your shop…")
                        resolveMerchant(uid)
                    }
                }
                .addOnFailureListener {
                    busy = false
                    btn.text = "Sign in"
                    btn.alpha = 1f
                    toast("Login failed: ${it.message}")
                }
        }
        form.addView(
            tv("Forgot password?", 13f, GREEN, true).apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(14), 0, dp(4))
                setOnClickListener {
                    val e = email.text.toString().trim()
                    if (e.isBlank()) toast("Type your email above first.")
                    else auth.sendPasswordResetEmail(e)
                        .addOnSuccessListener { toast("Reset link sent to $e") }
                        .addOnFailureListener { toast("Could not send: ${it.message}") }
                }
            },
            lp(MATCH, WRAP)
        )
    }

    private fun deny(msg: String) {
        auth.signOut()
        showLogin()
        toast(msg)
    }

    private fun resolveMerchant(uid: String) {
        // 1) Find the shop this account manages. 2) Fall back to the original demo shop id.
        db.collection("merchants").whereEqualTo("managerUid", uid).limit(1).get()
            .addOnSuccessListener { r ->
                val doc = r.documents.firstOrNull()
                if (doc != null) enterShop(doc, uid) else fallbackMerchant(uid)
            }
            .addOnFailureListener { fallbackMerchant(uid) }
    }

    private fun fallbackMerchant(uid: String) {
        db.collection("merchants").document("merchant_demo").get()
            .addOnSuccessListener { d ->
                if (d.exists() && d.getString("managerUid") == uid) enterShop(d, uid)
                else deny("This account is not linked to a merchant.")
            }
            .addOnFailureListener { deny("Merchant verification failed: ${it.message}") }
    }

    private fun enterShop(doc: DocumentSnapshot, uid: String) {
        if (doc.getString("managerUid") != uid || doc.getBoolean("active") != true) {
            deny("This shop is not active. Contact the marketplace admin.")
            return
        }
        applyShopDoc(doc)
        startListeners()
        showTab("orders")
    }

    private fun applyShopDoc(doc: DocumentSnapshot) {
        shopDoc = doc
        merchantId = doc.id
        shopName = doc.getString("name") ?: "My Shop"
        shopOpen = (doc.get("isOpen") as? Boolean) ?: (doc.get("open") as? Boolean) ?: true
    }

    private fun logout() {
        stopListeners()
        auth.signOut()
        orders = emptyList()
        products = emptyList()
        showLogin()
    }

    // ───────────────────────── Live listeners ─────────────────────────
    private fun startListeners() {
        stopListeners()
        firstOrdersSnapshot = true

        ordersListener = db.collection("orders").whereEqualTo("merchantId", merchantId)
            .addSnapshotListener { snap, err ->
                if (err != null) {
                    toast("Live orders failed: ${err.message}")
                    return@addSnapshotListener
                }
                if (snap == null) return@addSnapshotListener
                if (!firstOrdersSnapshot) {
                    val fresh = snap.documentChanges.count {
                        it.type == DocumentChange.Type.ADDED &&
                            stage(it.document.getString("status") ?: "") == "new"
                    }
                    if (fresh > 0) alertNewOrder(fresh)
                }
                firstOrdersSnapshot = false
                orders = snap.documents.sortedByDescending { createdMillis(it) }
                refreshNav()
                ordersRender?.invoke()
            }

        productsListener = db.collection("merchants").document(merchantId).collection("products")
            .addSnapshotListener { snap, err ->
                if (err != null) {
                    toast("Could not load menu: ${err.message}")
                    return@addSnapshotListener
                }
                products = snap?.documents.orEmpty().sortedBy { (it.getString("name") ?: "").lowercase(Locale.US) }
                menuRender?.invoke()
            }
    }

    private fun stopListeners() {
        ordersListener?.remove()
        ordersListener = null
        productsListener?.remove()
        productsListener = null
        ordersRender = null
        menuRender = null
    }

    private fun alertNewOrder(count: Int) {
        toast(if (count == 1) "🔔 New order received!" else "🔔 $count new orders!")
        try {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            RingtoneManager.getRingtone(this, uri)?.play()
        } catch (_: Exception) {
        }
        try {
            (getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator)
                ?.vibrate(longArrayOf(0, 400, 200, 400), -1)
        } catch (_: Exception) {
        }
    }

    // ───────────────────────── Shell: header + nav ─────────────────────────
    private fun showTab(tab: String) {
        currentTab = tab
        ordersRender = null
        menuRender = null
        refreshHeader()
        refreshNav()
        when (tab) {
            "orders" -> showOrdersTab()
            "menu" -> showMenuTab()
            else -> showShopTab()
        }
    }

    private fun refreshHeader() {
        headerHost.removeAllViews()
        headerHost.visibility = View.VISIBLE
        val bar = hbox().apply {
            setBackgroundColor(GREEN)
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        val col = vbox()
        col.addView(tv(shopName, 19f, Color.WHITE, true).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        })
        col.addView(
            tv(if (shopOpen) "● Accepting orders" else "● Not accepting orders", 12f, Color.argb(215, 255, 255, 255))
        )
        bar.addView(col, lp(0, WRAP, 1f))
        bar.addView(
            tv(if (shopOpen) "OPEN" else "CLOSED", 13f, if (shopOpen) GREEN else RED, true).apply {
                setPadding(dp(16), dp(8), dp(16), dp(8))
                background = shape(Color.WHITE, 20)
                isClickable = true
                setOnClickListener { setShopOpen(!shopOpen) }
            }
        )
        headerHost.addView(bar, ViewGroup.LayoutParams(MATCH, WRAP))
    }

    private fun setShopOpen(open: Boolean) {
        val previous = shopOpen
        shopOpen = open
        refreshHeader()
        db.collection("merchants").document(merchantId).update("isOpen", open)
            .addOnSuccessListener {
                toast(if (open) "Your shop is now OPEN to customers." else "Your shop is now CLOSED.")
                if (currentTab == "shop") showShopTab()
            }
            .addOnFailureListener {
                shopOpen = previous
                refreshHeader()
                toast("Could not update status: ${it.message}")
            }
    }

    private fun refreshNav() {
        navBar.visibility = View.VISIBLE
        navBar.removeAllViews()
        val newCount = orders.count { stage(it.getString("status") ?: "") == "new" }
        val items = listOf(
            Triple("orders", "🧾", "Orders"),
            Triple("menu", "🍽️", "Menu"),
            Triple("shop", "🏪", "Shop")
        )
        for ((key, icon, label) in items) {
            val selected = key == currentTab
            val col = vbox().apply {
                gravity = Gravity.CENTER
                isClickable = true
                setOnClickListener { showTab(key) }
            }
            val iconWrap = FrameLayout(this)
            iconWrap.addView(tv(icon, 20f).apply { alpha = if (selected) 1f else 0.55f })
            if (key == "orders" && newCount > 0) {
                iconWrap.addView(
                    tv("$newCount", 10f, Color.WHITE, true).apply {
                        gravity = Gravity.CENTER
                        minimumWidth = dp(16)
                        setPadding(dp(3), 0, dp(3), 0)
                        background = shape(ORANGE, 8)
                    },
                    FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP or Gravity.END)
                )
            }
            col.addView(iconWrap)
            col.addView(tv(label, 11f, if (selected) GREEN else GREY, selected))
            navBar.addView(col, lp(0, MATCH, 1f))
        }
    }

    // ───────────────────────── ORDERS TAB ─────────────────────────
    private fun showOrdersTab() {
        val page = newPage()
        ordersRender = { renderOrders(page) }
        renderOrders(page)
    }

    private fun statCard(icon: String, value: String, label: String): View {
        val c = vbox().apply {
            gravity = Gravity.CENTER
            background = shape(Color.WHITE, 14, LINE)
            setPadding(dp(8), dp(12), dp(8), dp(12))
        }
        c.addView(tv(icon, 18f))
        c.addView(tv(value, 17f, INK, true))
        c.addView(tv(label, 11f, GREY))
        return c
    }

    private fun renderOrders(page: LinearLayout) {
        page.removeAllViews()

        // Dashboard
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        val todayStart = cal.timeInMillis
        val today = orders.filter { createdMillis(it) >= todayStart }
        val todayCount = today.count { statusText(it.getString("status") ?: "") != "CANCELLED" }
        val sales = today.filter { statusText(it.getString("status") ?: "") == "DELIVERED" }
            .sumOf { num(it, "subtotal", num(it, "total")) }
        val newCount = orders.count { stage(it.getString("status") ?: "") == "new" }

        val stats = hbox()
        stats.addView(statCard("📦", "$todayCount", "Orders today"), lp(0, WRAP, 1f, 0, 0, 6, 0))
        stats.addView(statCard("💰", money(sales), "Sales today"), lp(0, WRAP, 1f, 3, 0, 3, 0))
        stats.addView(statCard("🔔", "$newCount", "New orders"), lp(0, WRAP, 1f, 6, 0, 0, 0))
        page.addView(stats, lp(MATCH, WRAP))

        // Filter chips
        fun count(st: String) = orders.count { stage(it.getString("status") ?: "") == st }
        val filters = listOf(
            "new" to "New (${count("new")})",
            "cooking" to "Preparing (${count("cooking")})",
            "ready" to "Ready (${count("ready")})",
            "history" to "History"
        )
        val scroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        val row = hbox()
        scroll.addView(row)
        for ((key, label) in filters) {
            val sel = key == orderFilter
            row.addView(
                tv(label, 13f, if (sel) Color.WHITE else INK, sel).apply {
                    setPadding(dp(16), dp(9), dp(16), dp(9))
                    background = shape(if (sel) GREEN else Color.WHITE, 20, if (sel) GREEN else LINE)
                    isClickable = true
                    setOnClickListener { orderFilter = key; renderOrders(page) }
                },
                lp(WRAP, WRAP, 0f, 0, 0, 8, 0)
            )
        }
        page.addView(scroll, lp(MATCH, WRAP, 0f, 0, 14, 0, 12))

        val list = orders.filter { stage(it.getString("status") ?: "") == orderFilter }
            .let { if (orderFilter == "history") it.take(50) else it }
        if (list.isEmpty()) {
            val (icon, title, sub) = when (orderFilter) {
                "new" -> Triple("😴", "No new orders", "New orders will pop up here with a sound alert.")
                "cooking" -> Triple("🍳", "Nothing being prepared", "Accepted orders show up here.")
                "ready" -> Triple("🛵", "No orders waiting", "Ready orders wait here for a rider.")
                else -> Triple("🧾", "No past orders yet", "Completed and cancelled orders appear here.")
            }
            val empty = vbox().apply {
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(dp(16), dp(36), dp(16), dp(24))
            }
            empty.addView(tv(icon, 48f))
            empty.addView(tv(title, 17f, INK, true), lp(WRAP, WRAP, 0f, 0, 8, 0, 0))
            empty.addView(tv(sub, 13f, GREY).apply { gravity = Gravity.CENTER }, lp(WRAP, WRAP, 0f, 0, 4, 0, 0))
            page.addView(empty)
            return
        }
        list.forEach { page.addView(orderCard(it), lp(MATCH, WRAP, 0f, 0, 0, 0, 12)) }
    }

    private fun orderCard(d: DocumentSnapshot): View {
        val status = d.getString("status") ?: "placed"
        val st = stage(status)
        val c = card(14)

        // Header
        val top = hbox()
        top.addView(tv("#${d.id.take(6).uppercase(Locale.US)}", 16f, INK, true))
        top.addView(tv("  ·  ${ago(d)}", 12f, GREY), lp(0, WRAP, 1f))
        top.addView(tv(statusText(status), 11f, Color.WHITE, true).apply {
            setPadding(dp(10), dp(4), dp(10), dp(4))
            background = shape(statusColor(status), 10)
        })
        c.addView(top)

        // Customer + call
        val cust = hbox().apply { setPadding(0, dp(10), 0, dp(2)) }
        val custCol = vbox()
        custCol.addView(tv("👤 ${d.getString("customerName") ?: "Customer"}", 15f, INK, true))
        custCol.addView(
            tv("📍 ${d.getString("deliveryAddress") ?: "No address"}", 12f, GREY).apply {
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
            }
        )
        cust.addView(custCol, lp(0, WRAP, 1f, 0, 0, 8, 0))
        cust.addView(callButton("📞", d.getString("customerPhone").orEmpty(), "Customer's number is missing."))
        c.addView(cust)

        // Items
        c.addView(View(this).apply { setBackgroundColor(LINE) }, lp(MATCH, 1, 0f, 0, 10, 0, 8))
        (d.get("items") as? List<*>)?.forEach {
            val mp = it as? Map<*, *> ?: return@forEach
            val q = (mp["quantity"] as? Number)?.toInt() ?: 1
            c.addView(kv("$q × ${mp["name"] ?: "Item"}", money((mp["lineTotal"] as? Number)?.toDouble() ?: 0.0)))
        }

        // Customer note
        val note = d.getString("merchantNote").orEmpty()
        if (note.isNotBlank()) {
            c.addView(
                tv("📝 $note", 13f, INK, true).apply {
                    setPadding(dp(12), dp(10), dp(12), dp(10))
                    background = shape(YELLOW_SOFT, 10)
                },
                lp(MATCH, WRAP, 0f, 0, 8, 0, 0)
            )
        }

        // Totals
        c.addView(View(this).apply { setBackgroundColor(LINE) }, lp(MATCH, 1, 0f, 0, 8, 0, 6))
        c.addView(kv("Payment", d.getString("paymentMethod") ?: "COD"))
        val disc = num(d, "discount")
        if (disc > 0) c.addView(kv("Customer promo", "-" + money(disc), false, GREEN))
        c.addView(kv("Order total", money(num(d, "total")), true))

        // Actions
        val orderId = d.id
        when (status.lowercase(Locale.US)) {
            "placed", "pending" -> {
                val actions = hbox()
                actions.addView(ghostButton("Reject", RED) { confirmReject(orderId) }, lp(0, WRAP, 1f, 0, 0, 8, 0))
                actions.addView(cta("Accept") { updateStatus(orderId, "accepted") }, lp(0, WRAP, 2f))
                c.addView(actions, lp(MATCH, WRAP, 0f, 0, 12, 0, 0))
            }
            "accepted", "confirmed" ->
                c.addView(cta("Start preparing", GREEN) { updateStatus(orderId, "preparing") }, lp(MATCH, WRAP, 0f, 0, 12, 0, 0))
            "preparing" ->
                c.addView(cta("Mark as ready") { updateStatus(orderId, "ready") }, lp(MATCH, WRAP, 0f, 0, 12, 0, 0))
            else -> {
                if (st == "ready") {
                    val rid = d.getString("riderId").orEmpty()
                    val r = hbox().apply { setPadding(0, dp(12), 0, 0) }
                    if (rid.isBlank()) {
                        r.addView(tv("⏳ Waiting for a rider to pick up…", 13f, GREY, true), lp(0, WRAP, 1f))
                    } else {
                        r.addView(
                            tv(
                                if (status.lowercase(Locale.US) == "ready") "🛵 Rider assigned" else "🛵 Rider is on the way",
                                13f, GREEN, true
                            ),
                            lp(0, WRAP, 1f)
                        )
                        r.addView(
                            callButton("🛵📞", d.getString("riderPhone").orEmpty(), "Rider's number isn't available.") { cb ->
                                db.collection("riders").document(rid).get()
                                    .addOnSuccessListener { cb(it.getString("phone") ?: it.getString("phoneNumber") ?: "") }
                                    .addOnFailureListener { cb("") }
                            }
                        )
                    }
                    c.addView(r)
                }
            }
        }
        return c
    }

    private fun confirmReject(orderId: String) {
        AlertDialog.Builder(this)
            .setTitle("Reject this order?")
            .setMessage("The customer will see it as cancelled.")
            .setPositiveButton("Reject") { _, _ -> updateStatus(orderId, "cancelled") }
            .setNegativeButton("Keep", null)
            .show()
    }

    private fun updateStatus(orderId: String, newStatus: String) {
        if (auth.currentUser == null) { showLogin(); return }
        db.collection("orders").document(orderId).update("status", newStatus)
            .addOnSuccessListener {
                toast(
                    when (newStatus) {
                        "accepted" -> "Order accepted ✓"
                        "preparing" -> "Preparing started"
                        "ready" -> "Marked ready for pickup"
                        else -> "Order $newStatus"
                    }
                )
            }
            .addOnFailureListener { toast("Update failed: ${it.message}") }
    }

    // ───────────────────────── MENU TAB ─────────────────────────
    private fun showMenuTab() {
        val page = newPage()
        menuRender = { renderMenu(page) }
        renderMenu(page)
    }

    private fun renderMenu(page: LinearLayout) {
        page.removeAllViews()

        val top = hbox()
        val search = inputField("Search your menu", menuQuery)
        top.addView(search, lp(0, WRAP, 1f, 0, 0, 8, 0))
        top.addView(
            tv("+ Add", 14f, Color.WHITE, true).apply {
                setPadding(dp(18), dp(14), dp(18), dp(14))
                background = ripple(shape(ORANGE, 12))
                isClickable = true
                setOnClickListener { showProductDialog(null) }
            }
        )
        page.addView(top, lp(MATCH, WRAP))

        val available = products.count { it.getBoolean("available") == true }
        page.addView(
            tv("${products.size} items  ·  $available available", 12f, GREY),
            lp(WRAP, WRAP, 0f, 0, 10, 0, 8)
        )

        val listHost = vbox()
        page.addView(listHost, lp(MATCH, WRAP))

        fun fill() {
            listHost.removeAllViews()
            val q = menuQuery.trim()
            val items = products.filter { q.isBlank() || (it.getString("name") ?: "").contains(q, true) }
            if (items.isEmpty()) {
                val empty = vbox().apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                    setPadding(dp(16), dp(36), dp(16), dp(24))
                }
                empty.addView(tv("🍽️", 48f))
                empty.addView(
                    tv(if (products.isEmpty()) "Your menu is empty" else "No matches", 17f, INK, true),
                    lp(WRAP, WRAP, 0f, 0, 8, 0, 0)
                )
                empty.addView(
                    tv(if (products.isEmpty()) "Tap “+ Add” to create your first item." else "Try another name.", 13f, GREY),
                    lp(WRAP, WRAP, 0f, 0, 4, 0, 0)
                )
                listHost.addView(empty)
                return
            }
            items.forEach { listHost.addView(productRow(it), lp(MATCH, WRAP, 0f, 0, 0, 0, 10)) }
        }
        fill()

        search.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {
                menuQuery = s?.toString().orEmpty()
                fill()
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })
    }

    private fun productRow(d: DocumentSnapshot): View {
        val name = d.getString("name") ?: "Unnamed"
        val price = num(d, "price")
        val desc = d.getString("description").orEmpty()
        val img = d.getString("imageUrl").orEmpty()
        val avail = d.getBoolean("available") == true

        val row = hbox().apply {
            background = ripple(shape(Color.WHITE, 16, LINE))
            setPadding(dp(12), dp(12), dp(12), dp(12))
            isClickable = true
            setOnClickListener { showProductDialog(d) }
        }
        val thumb = FrameLayout(this).apply { setBackgroundColor(GREEN_SOFT) }
        roundClip(thumb, 12)
        thumb.addView(tv("🍽️", 24f).apply { gravity = Gravity.CENTER }, FrameLayout.LayoutParams(MATCH, MATCH))
        val iv = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        thumb.addView(iv, FrameLayout.LayoutParams(MATCH, MATCH))
        loadImage(img, iv)
        row.addView(thumb, lp(dp(64), dp(64), 0f, 0, 0, 12, 0))

        val col = vbox().apply { alpha = if (avail) 1f else 0.55f }
        col.addView(tv(name, 15f, INK, true))
        if (desc.isNotBlank()) {
            col.addView(tv(desc, 12f, GREY).apply {
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            })
        }
        col.addView(tv(money(price), 14f, GREEN, true), lp(WRAP, WRAP, 0f, 0, 2, 0, 0))
        row.addView(col, lp(0, WRAP, 1f))

        val right = vbox().apply { gravity = Gravity.CENTER_HORIZONTAL }
        val sw = Switch(this).apply { isChecked = avail }
        sw.setOnCheckedChangeListener { _, checked ->
            db.collection("merchants").document(merchantId).collection("products").document(d.id)
                .update("available", checked)
                .addOnFailureListener { toast("Could not update: ${it.message}") }
        }
        right.addView(sw)
        right.addView(tv(if (avail) "In stock" else "Sold out", 10f, if (avail) GREEN else RED, true))
        row.addView(right)
        return row
    }

    private fun showProductDialog(existing: DocumentSnapshot?) {
        val form = vbox().apply { setPadding(dp(20), dp(8), dp(20), dp(8)) }
        val nameF = inputField("Item name", existing?.getString("name") ?: "", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS)
        val priceF = inputField(
            "Price (₱)",
            existing?.let { num(it, "price").let { p -> if (p % 1.0 == 0.0) p.toInt().toString() else p.toString() } } ?: "",
            InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        )
        val descF = inputField("Description (optional)", existing?.getString("description") ?: "", InputType.TYPE_CLASS_TEXT, 2)
        val imgF = inputField("Image URL (optional, https://…)", existing?.getString("imageUrl") ?: "", InputType.TYPE_TEXT_VARIATION_URI)
        val availSw = Switch(this).apply {
            text = "Available to order  "
            isChecked = existing?.getBoolean("available") ?: true
        }
        form.addView(nameF, lp(MATCH, WRAP, 0f, 0, 6, 0, 0))
        form.addView(priceF, lp(MATCH, WRAP, 0f, 0, 8, 0, 0))
        form.addView(descF, lp(MATCH, WRAP, 0f, 0, 8, 0, 0))
        form.addView(imgF, lp(MATCH, WRAP, 0f, 0, 8, 0, 0))
        form.addView(availSw, lp(MATCH, WRAP, 0f, 0, 12, 0, 4))

        val scroll = ScrollView(this)
        scroll.addView(form)

        val builder = AlertDialog.Builder(this)
            .setTitle(if (existing == null) "Add menu item" else "Edit menu item")
            .setView(scroll)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save", null)
        if (existing != null) builder.setNeutralButton("Delete", null)
        val dialog = builder.create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = nameF.text.toString().trim()
                val price = priceF.text.toString().toDoubleOrNull()
                if (name.isBlank()) { toast("Enter an item name."); return@setOnClickListener }
                if (price == null || price < 0) { toast("Enter a valid price."); return@setOnClickListener }

                val data = hashMapOf<String, Any>(
                    "name" to name,
                    "price" to price,
                    "description" to descF.text.toString().trim(),
                    "imageUrl" to imgF.text.toString().trim(),
                    "available" to availSw.isChecked
                )
                val col = db.collection("merchants").document(merchantId).collection("products")
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
                val onOk = {
                    dialog.dismiss()
                    toast(if (existing == null) "Item added" else "Item updated")
                }
                val onFail = { e: Exception ->
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = true
                    toast("Save failed: ${e.message}")
                }
                if (existing == null) {
                    col.add(data)
                        .addOnSuccessListener { onOk() }
                        .addOnFailureListener { onFail(it) }
                } else {
                    col.document(existing.id).update(data)
                        .addOnSuccessListener { onOk() }
                        .addOnFailureListener { onFail(it) }
                }
            }
            if (existing != null) {
                dialog.getButton(AlertDialog.BUTTON_NEUTRAL).apply {
                    setTextColor(RED)
                    setOnClickListener {
                        AlertDialog.Builder(this@MainActivity)
                            .setTitle("Delete “${existing.getString("name") ?: "item"}”?")
                            .setMessage("This removes it from your menu for all customers.")
                            .setPositiveButton("Delete") { _, _ ->
                                db.collection("merchants").document(merchantId)
                                    .collection("products").document(existing.id).delete()
                                    .addOnSuccessListener { dialog.dismiss(); toast("Item deleted") }
                                    .addOnFailureListener { toast("Delete failed: ${it.message}") }
                            }
                            .setNegativeButton("Cancel", null)
                            .show()
                    }
                }
            }
        }
        dialog.show()
    }

    // ───────────────────────── SHOP TAB ─────────────────────────
    private fun showShopTab() {
        val page = newPage()
        val d = shopDoc

        // Status card
        val status = card(16)
        val sr = hbox()
        val sc = vbox()
        sc.addView(tv(if (shopOpen) "Your shop is OPEN" else "Your shop is CLOSED", 17f, if (shopOpen) GREEN else RED, true))
        sc.addView(
            tv(
                if (shopOpen) "Customers can place orders right now."
                else "Customers can see your menu but can't order.", 12f, GREY
            )
        )
        sr.addView(sc, lp(0, WRAP, 1f))
        val sw = Switch(this).apply { isChecked = shopOpen }
        sw.setOnCheckedChangeListener { _, checked ->
            if (checked != shopOpen) setShopOpen(checked)
        }
        sr.addView(sw)
        status.addView(sr)
        page.addView(status, lp(MATCH, WRAP))

        sectionTitle(page, "Shop profile")
        page.addView(
            tv("This is what customers see in the customer app.", 12f, GREY),
            lp(WRAP, WRAP, 0f, 0, -4, 0, 8)
        )
        val nameF = inputField("Shop name", d?.getString("name") ?: shopName, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS)
        val descF = inputField("Short description", d?.getString("description") ?: "", InputType.TYPE_CLASS_TEXT, 2)
        val phoneF = inputField("Shop phone number", d?.getString("phone") ?: d?.getString("phoneNumber") ?: "", InputType.TYPE_CLASS_PHONE)
        val feeF = inputField(
            "Delivery fee (₱)",
            d?.let { num(it, "deliveryFee", 40.0).toInt().toString() } ?: "40",
            InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        )
        val etaF = inputField("Prep/delivery time, e.g. 25-35 min", d?.getString("eta") ?: "")
        val catF = inputField("Category: food or convenience", d?.getString("category") ?: "food")
        val imgF = inputField("Cover image URL (optional)", d?.getString("imageUrl") ?: "", InputType.TYPE_TEXT_VARIATION_URI)
        listOf(nameF, descF, phoneF, feeF, etaF, catF, imgF).forEach {
            page.addView(it, lp(MATCH, WRAP, 0f, 0, 0, 0, 8))
        }
        page.addView(
            cta("Save shop profile", GREEN) {
                val name = nameF.text.toString().trim()
                val fee = feeF.text.toString().toDoubleOrNull()
                val cat = catF.text.toString().trim().lowercase(Locale.US)
                if (name.isBlank()) { toast("Shop name is required."); return@cta }
                if (fee == null || fee < 0) { toast("Enter a valid delivery fee."); return@cta }
                val updates = hashMapOf<String, Any>(
                    "name" to name,
                    "description" to descF.text.toString().trim(),
                    "phone" to phoneF.text.toString().trim(),
                    "deliveryFee" to fee,
                    "eta" to etaF.text.toString().trim().ifBlank { "25-35 min" },
                    "category" to cat.ifBlank { "food" },
                    "imageUrl" to imgF.text.toString().trim()
                )
                db.collection("merchants").document(merchantId).update(updates)
                    .addOnSuccessListener {
                        db.collection("merchants").document(merchantId).get().addOnSuccessListener { fresh ->
                            applyShopDoc(fresh)
                            refreshHeader()
                            toast("Shop profile saved")
                        }
                    }
                    .addOnFailureListener { toast("Save failed: ${it.message}") }
            },
            lp(MATCH, WRAP, 0f, 0, 6, 0, 0)
        )

        sectionTitle(page, "Account")
        val acc = card(14)
        acc.addView(kv("Signed in as", auth.currentUser?.email ?: "—"))
        acc.addView(kv("Shop ID", merchantId))
        page.addView(acc, lp(MATCH, WRAP, 0f, 0, 0, 0, 10))
        page.addView(
            ghostButton("Change password") {
                val email = auth.currentUser?.email
                if (email.isNullOrBlank()) toast("No email on this account.")
                else auth.sendPasswordResetEmail(email)
                    .addOnSuccessListener { toast("Reset link sent to $email") }
                    .addOnFailureListener { toast("Could not send: ${it.message}") }
            }
        )
        page.addView(ghostButton("Log out", RED) { logout() }, lp(MATCH, WRAP, 0f, 0, 10, 0, 0))
        page.addView(
            tv("Delivery Marketplace · Merchant app", 11f, GREY).apply { gravity = Gravity.CENTER },
            lp(MATCH, WRAP, 0f, 0, 20, 0, 0)
        )
    }
}
