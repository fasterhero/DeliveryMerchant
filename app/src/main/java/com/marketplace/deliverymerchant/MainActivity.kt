package com.marketplace.deliverymerchant

import android.app.Activity
import android.os.Bundle
import android.graphics.Color
import android.view.Gravity
import android.widget.*
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore

class MainActivity : Activity() {

    private val auth by lazy { FirebaseAuth.getInstance() }
    private val db by lazy { FirebaseFirestore.getInstance() }

    private val merchantId = "merchant_demo"

    private lateinit var content: LinearLayout
    private lateinit var accountLabel: TextView
    private lateinit var ordersList: LinearLayout

    private var loading = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val scroll = ScrollView(this)

        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 32)
        }

        scroll.addView(content)
        setContentView(scroll)

        heading("Delivery Merchant", 28f)
        heading("Demo Food Shop", 20f)

        accountLabel = TextView(this).apply {
            textSize = 13f
            setTextColor(Color.DKGRAY)
            setPadding(0, 12, 0, 12)
        }
        content.addView(accountLabel)

        addButton("Refresh orders") {
            loadOrders()
        }

        heading("Incoming orders")

        ordersList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        content.addView(ordersList)

        signIn()
    }

    private fun heading(text: String, size: Float = 20f) {
        content.addView(TextView(this).apply {
            this.text = text
            textSize = size
            setTextColor(Color.rgb(35, 65, 55))
            setPadding(0, 18, 0, 10)
        })
    }

    private fun addButton(
        text: String,
        action: () -> Unit
    ) {
        content.addView(Button(this).apply {
            this.text = text
            setOnClickListener { action() }
        })
    }

    private fun listButton(
        parent: LinearLayout,
        text: String,
        action: () -> Unit
    ) {
        parent.addView(Button(this).apply {
            this.text = text
            setOnClickListener { action() }
        })
    }

    private fun showMessage(text: String) {
        Toast.makeText(
            this,
            text,
            Toast.LENGTH_LONG
        ).show()
    }

    private fun signIn() {
        val currentUser = auth.currentUser

        if (currentUser != null) {
            showAccount(currentUser.uid)
            return
        }

        accountLabel.text = "Signing in to Firebase..."

        auth.signInAnonymously()
            .addOnSuccessListener { result ->
                val uid = result.user?.uid

                if (uid != null) {
                    showAccount(uid)
                } else {
                    accountLabel.text = "Could not get account UID."
                }
            }
            .addOnFailureListener {
                accountLabel.text = "Sign-in failed."
                showMessage("Sign-in failed: ${it.message}")
            }
    }

    private fun showAccount(uid: String) {
        accountLabel.text =
            "Test account UID:\n$uid\n\n" +
            "Merchant ID: $merchantId"

        loadOrders()
    }

    private fun loadOrders() {
        if (loading) return

        if (auth.currentUser == null) {
            showMessage("Please wait for Firebase sign-in.")
            return
        }

        loading = true
        ordersList.removeAllViews()

        ordersList.addView(TextView(this).apply {
            text = "Loading orders..."
            textSize = 16f
        })

        db.collection("orders")
            .whereEqualTo("merchantId", merchantId)
            .get()
            .addOnSuccessListener { results ->
                loading = false
                ordersList.removeAllViews()

                if (results.isEmpty) {
                    ordersList.addView(TextView(this).apply {
                        text = "No orders found for Demo Food Shop."
                        textSize = 16f
                        setPadding(0, 12, 0, 12)
                    })
                    return@addOnSuccessListener
                }

                for (doc in results.documents) {
                    val orderId = doc.id
                    val customerName =
                        doc.getString("customerName") ?: "Customer"
                    val phone =
                        doc.getString("customerPhone") ?: "Not provided"
                    val address =
                        doc.getString("deliveryAddress") ?: "Not provided"
                    val status =
                        doc.getString("status") ?: "placed"
                    val payment =
                        doc.getString("paymentMethod") ?: "Unknown"
                    val total = doc.getDouble("total")
                        ?: doc.getLong("total")?.toDouble()
                        ?: 0.0

                    val card = LinearLayout(this).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(16, 12, 16, 16)
                        setBackgroundColor(Color.rgb(245, 247, 245))
                    }

                    card.addView(TextView(this).apply {
                        text = "Order ${orderId.take(8)}"
                        textSize = 19f
                        setTextColor(Color.rgb(35, 65, 55))
                    })

                    card.addView(TextView(this).apply {
                        text =
                            "Customer: $customerName\n" +
                            "Phone: $phone\n" +
                            "Address: $address\n" +
                            "Payment: $payment\n" +
                            "Total: ₱${total.toInt()}\n" +
                            "Status: ${status.uppercase()}"
                        textSize = 15f
                        setTextColor(Color.DKGRAY)
                        setPadding(0, 8, 0, 8)
                    })

                    val items = doc.get("items") as? List<*>
                    val itemLines = items.orEmpty().mapNotNull { item ->
                        val fields = item as? Map<*, *> ?: return@mapNotNull null
                        val name = fields["name"] as? String ?: "Item"
                        val quantity = (fields["quantity"] as? Number)?.toInt() ?: 1
                        "$name × $quantity"
                    }

                    card.addView(TextView(this).apply {
                        text = if (itemLines.isEmpty()) {
                            "Items: Details unavailable"
                        } else {
                            "Items:\n" + itemLines.joinToString("\n")
                        }
                        textSize = 15f
                        setTextColor(Color.DKGRAY)
                        setPadding(0, 4, 0, 8)
                    })

                    when (status) {
                        "placed" -> listButton(
                            card, "Accept order"
                        ) {
                            updateStatus(orderId, "accepted")
                        }

                        "accepted" -> listButton(
                            card, "Start preparing"
                        ) {
                            updateStatus(orderId, "preparing")
                        }

                        "preparing" -> listButton(
                            card, "Mark ready"
                        ) {
                            updateStatus(orderId, "ready")
                        }

                        "ready" -> card.addView(TextView(this).apply {
                            text = "Waiting for rider assignment."
                            textSize = 14f
                        })
                    }

                    val divider = TextView(this).apply {
                        text = ""
                        height = 20
                    }

                    ordersList.addView(card)
                    ordersList.addView(divider)
                }
            }
            .addOnFailureListener {
                loading = false
                ordersList.removeAllViews()
                showMessage(
                    "Could not load orders: ${it.message}"
                )
            }
    }

    private fun updateStatus(
        orderId: String,
        newStatus: String
    ) {
        db.collection("orders")
            .document(orderId)
            .update("status", newStatus)
            .addOnSuccessListener {
                showMessage("Order updated to $newStatus.")
                loadOrders()
            }
            .addOnFailureListener {
                showMessage("Update failed: ${it.message}")
            }
    }
}
