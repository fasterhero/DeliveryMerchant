
package com.marketplace.deliverymerchant

import android.app.Activity
import android.os.Bundle
import android.graphics.Color
import android.text.InputType
import android.view.ViewGroup
import android.widget.*
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import android.app.AlertDialog
import com.google.firebase.firestore.ListenerRegistration

class MainActivity : Activity() {

    private val auth by lazy { FirebaseAuth.getInstance() }
    private val db by lazy { FirebaseFirestore.getInstance() }

    private val merchantId = "merchant_demo"

    private lateinit var content: LinearLayout
    private lateinit var accountLabel: TextView
    private lateinit var ordersList: LinearLayout
    private lateinit var productsList: LinearLayout
    
    private var ordersListener: ListenerRegistration? = null

    private var loading = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Do not continue using the old anonymous test account.
        if (auth.currentUser?.isAnonymous == true) {
            auth.signOut()
        }

        if (auth.currentUser != null) {
            showOrdersScreen()
        } else {
            showLoginScreen()
        }
    }

    private fun setupContent() {
        val scroll = ScrollView(this)

        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 32)
        }

        scroll.addView(content)
        setContentView(scroll)
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

    private fun showMessage(text: String) {
        Toast.makeText(
            this,
            text,
            Toast.LENGTH_LONG
        ).show()
    }

    private fun showLoginScreen() {
        setupContent()

        heading("Delivery Merchant", 28f)
        heading("Merchant Login", 22f)

        content.addView(TextView(this).apply {
            text = "Sign in with your registered merchant account."
            textSize = 15f
            setTextColor(Color.DKGRAY)
        })

        val emailInput = EditText(this).apply {
    hint = "Email address"
    inputType = InputType.TYPE_CLASS_TEXT or
        InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
    setSingleLine(true)
}

        content.addView(emailInput)

        val passwordInput = EditText(this).apply {
    hint = "Password"
    inputType = InputType.TYPE_CLASS_TEXT or
        InputType.TYPE_TEXT_VARIATION_PASSWORD
    setSingleLine(true)
}

        content.addView(passwordInput)

        val loginButton = Button(this).apply {
            text = "Sign In"
        }
        content.addView(loginButton)

        loginButton.setOnClickListener {
            val email = emailInput.text.toString().trim()
            val password = passwordInput.text.toString()

            if (email.isBlank() || password.isBlank()) {
                showMessage("Enter your email and password.")
                return@setOnClickListener
            }

            loginButton.isEnabled = false
            loginButton.text = "Signing in..."

            auth.signInWithEmailAndPassword(email, password)
                .addOnSuccessListener {
                    val uid = auth.currentUser?.uid

                    if (uid == null) {
                        auth.signOut()
                        showLoginScreen()
                        showMessage("Could not verify your account.")
                    } else {
                        verifyMerchant(uid)
                    }
                }
                .addOnFailureListener { error ->
                    loginButton.isEnabled = true
                    loginButton.text = "Sign In"
                    showMessage(
                        "Login failed: ${error.message}"
                    )
                }
        }
    }

    private fun verifyMerchant(uid: String) {
        db.collection("merchants")
            .document(merchantId)
            .get()
            .addOnSuccessListener { document ->
                val managerUid = document.getString("managerUid")
                val active = document.getBoolean("active") == true

                if (managerUid == uid && active) {
                    showOrdersScreen()
                } else {
                    auth.signOut()
                    showLoginScreen()
                    showMessage(
                        "This account is not authorized for this merchant."
                    )
                }
            }
            .addOnFailureListener { error ->
                auth.signOut()
                showLoginScreen()
                showMessage(
                    "Merchant verification failed: ${error.message}"
                )
            }
    }

    private fun showOrdersScreen() {
        setupContent()

        heading("Delivery Merchant", 28f)
        heading("Demo Food Shop", 21f)

        accountLabel = TextView(this).apply {
            text = "Signed in: ${auth.currentUser?.email ?: "Merchant"}"
            textSize = 14f
            setTextColor(Color.DKGRAY)
            setPadding(0, 8, 0, 12)
        }
        content.addView(accountLabel)

        addButton("Refresh orders") {
            loadOrders()
        }

        addButton("Log Out") {
            auth.signOut()
            showLoginScreen()
        }

heading("Product Management")

addButton("Add New Product") {
    showProductDialog()
}

productsList = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
}
content.addView(productsList)

loadProducts()

        heading("Incoming Orders")

        ordersList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        content.addView(ordersList)

        loadOrders()
    }

    private fun addListButton(
        parent: LinearLayout,
        text: String,
        action: () -> Unit
    ) {
        parent.addView(Button(this).apply {
            this.text = text
            setOnClickListener { action() }
        })
    }
    

private fun loadProducts() {
    if (auth.currentUser == null) {
        showLoginScreen()
        return
    }

    productsList.removeAllViews()

    db.collection("merchants")
        .document(merchantId)
        .collection("products")
        .get()
        .addOnSuccessListener { results ->
            productsList.removeAllViews()

            if (results.isEmpty) {
                productsList.addView(TextView(this).apply {
                    text = "No products found. Add your first product."
                    textSize = 15f
                    setPadding(0, 12, 0, 12)
                })
                return@addOnSuccessListener
            }

            for (doc in results.documents) {
                val productId = doc.id
                val name = doc.getString("name") ?: "Unnamed product"
                val price = doc.getDouble("price")
                    ?: doc.getLong("price")?.toDouble()
                    ?: 0.0
                val available = doc.getBoolean("available") == true

                val card = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(16, 12, 16, 16)
                    setBackgroundColor(Color.rgb(245, 247, 245))
                }

                card.addView(TextView(this).apply {
                    text = name
                    textSize = 19f
                    setTextColor(Color.rgb(35, 65, 55))
                })

                card.addView(TextView(this).apply {
                    text = "Price: ₱${"%.2f".format(price)}"
                    textSize = 16f
                })

                card.addView(TextView(this).apply {
                    text = if (available) {
                        "Availability: Available"
                    } else {
                        "Availability: Unavailable"
                    }
                    textSize = 14f
                    setPadding(0, 4, 0, 4)
                })

                addListButton(
                    card,
                    "Edit Product"
                ) {
                    showProductDialog(
                        productId = productId,
                        currentName = name,
                        currentPrice = price
                    )
                }

                addListButton(
                    card,
                    if (available) "Mark Unavailable"
                    else "Mark Available"
                ) {
                    db.collection("merchants")
                        .document(merchantId)
                        .collection("products")
                        .document(productId)
                        .update("available", !available)
                        .addOnSuccessListener {
                            showMessage("Product availability updated.")
                            loadProducts()
                        }
                        .addOnFailureListener { error ->
                            showMessage(
                                "Could not update product: ${error.message}"
                            )
                        }
                }

                productsList.addView(card)
                productsList.addView(TextView(this).apply {
                    height = 16
                })
            }
        }
        .addOnFailureListener { error ->
            showMessage(
                "Could not load products: ${error.message}"
            )
        }
}

private fun showProductDialog(
    productId: String? = null,
    currentName: String = "",
    currentPrice: Double = 0.0
) {
    val form = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(40, 8, 40, 8)
    }

    val nameInput = EditText(this).apply {
        hint = "Product name"
        setSingleLine(true)
        setText(currentName)
    }

    val priceInput = EditText(this).apply {
        hint = "Price in pesos"
        inputType = InputType.TYPE_CLASS_NUMBER or
            InputType.TYPE_NUMBER_FLAG_DECIMAL
        setSingleLine(true)

        if (productId != null) {
            setText(currentPrice.toString())
        }
    }

    form.addView(nameInput)
    form.addView(priceInput)

    val dialog = AlertDialog.Builder(this)
        .setTitle(
            if (productId == null) "Add Product"
            else "Edit Product"
        )
        .setView(form)
        .setNegativeButton("Cancel", null)
        .setPositiveButton("Save", null)
        .create()

    dialog.setOnShowListener {
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            .setOnClickListener {
                val name = nameInput.text.toString().trim()
                val price = priceInput.text.toString().toDoubleOrNull()

                if (name.isBlank()) {
                    showMessage("Enter a product name.")
                    return@setOnClickListener
                }

                if (price == null || price < 0) {
                    showMessage("Enter a valid price.")
                    return@setOnClickListener
                }

                val user = auth.currentUser

                if (user == null) {
                    dialog.dismiss()
                    showLoginScreen()
                    return@setOnClickListener
                }

                val products = db.collection("merchants")
                    .document(merchantId)
                    .collection("products")

                val saving = if (productId == null) {
                    products.add(
                        mapOf(
                            "name" to name,
                            "price" to price,
                            "available" to true
                        )
                    )
                } else {
                    products.document(productId).update(
                        mapOf(
                            "name" to name,
                            "price" to price
                        )
                    )
                }

                dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled =
                    false

                saving
                    .addOnSuccessListener {
                        dialog.dismiss()
                        showMessage(
                            if (productId == null) {
                                "Product added successfully."
                            } else {
                                "Product updated successfully."
                            }
                        )
                        loadProducts()
                    }
                    .addOnFailureListener { error ->
                        dialog.getButton(
                            AlertDialog.BUTTON_POSITIVE
                        ).isEnabled = true

                        showMessage(
                            "Save failed: ${error.message}"
                        )
                    }
            }
    }

    dialog.show()
}



private fun loadOrders() {
    if (loading) return

    val uid = auth.currentUser?.uid

    if (uid == null) {
        ordersListener?.remove()
        ordersListener = null
        showLoginScreen()
        return
    }

    // Remove the previous live listener before creating another.
    ordersListener?.remove()
    ordersListener = null

    loading = true
    ordersList.removeAllViews()

    ordersList.addView(TextView(this).apply {
        text = "Loading orders..."
        textSize = 16f
    })

    db.collection("merchants")
        .document(merchantId)
        .get()
        .addOnSuccessListener { merchant ->
            if (
                auth.currentUser?.uid != uid ||
                merchant.getString("managerUid") != uid ||
                merchant.getBoolean("active") != true
            ) {
                loading = false
                ordersListener?.remove()
                ordersListener = null
                auth.signOut()
                showLoginScreen()
                showMessage("Merchant access could not be verified.")
                return@addOnSuccessListener
            }

            ordersListener = db.collection("orders")
                .whereEqualTo("merchantId", merchantId)
                .addSnapshotListener { results, error ->

                    if (error != null) {
                        loading = false
                        ordersList.removeAllViews()
                        showMessage(
                            "Live order updates failed: ${error.message}"
                        )
                        return@addSnapshotListener
                    }

                    if (results == null) {
                        loading = false
                        return@addSnapshotListener
                    }

                    loading = false
                    ordersList.removeAllViews()

                    if (results.isEmpty) {
                        ordersList.addView(TextView(this).apply {
                            text = "No orders yet. New orders will appear here automatically."
                            textSize = 15f
                            setPadding(0, 12, 0, 12)
                        })
                        return@addSnapshotListener
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
                                "Total: ₱${"%.2f".format(total)}\n" +
                                "Status: ${status.uppercase()}"
                            textSize = 15f
                            setTextColor(Color.DKGRAY)
                            setPadding(0, 8, 0, 8)
                        })

                        val items = doc.get("items") as? List<*>
                        val itemLines = items.orEmpty().mapNotNull { item ->
                            val fields = item as? Map<*, *>
                                ?: return@mapNotNull null
                            val name = fields["name"] as? String ?: "Item"
                            val quantity =
                                (fields["quantity"] as? Number)?.toInt() ?: 1

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
                            "placed" -> addListButton(
                                card, "Accept Order"
                            ) {
                                updateStatus(orderId, "accepted")
                            }

                            "accepted" -> addListButton(
                                card, "Start Preparing"
                            ) {
                                updateStatus(orderId, "preparing")
                            }

                            "preparing" -> addListButton(
                                card, "Mark Ready"
                            ) {
                                updateStatus(orderId, "ready")
                            }

                            "ready" -> card.addView(TextView(this).apply {
                                text = "Waiting for rider assignment."
                                textSize = 14f
                            })
                        }

                        ordersList.addView(card)
                        ordersList.addView(TextView(this).apply {
                            height = 20
                        })
                    }
                }
        }
        .addOnFailureListener { error ->
            loading = false
            ordersList.removeAllViews()
            showMessage(
                "Could not verify merchant: ${error.message}"
            )
        }
}


    private fun updateStatus(
        orderId: String,
        newStatus: String
    ) {
        val uid = auth.currentUser?.uid

        if (uid == null) {
            showLoginScreen()
            return
        }

        db.collection("orders")
            .document(orderId)
            .update("status", newStatus)
            .addOnSuccessListener {
                showMessage("Order updated to $newStatus.")
                loadOrders()
            }
            .addOnFailureListener { error ->
                showMessage(
                    "Update failed: ${error.message}"
                )
            }
    }
}
