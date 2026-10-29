package com.shopflow.app;

import android.content.Intent;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.GravityCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.drawerlayout.widget.DrawerLayout;

import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.navigation.NavigationView;
import com.shopflow.app.models.CartItem;
import com.shopflow.app.models.CustomerSession;
import com.shopflow.app.models.PaymentResult;
import com.shopflow.app.models.ShoppingCart;
import com.shopflow.app.models.ShoppingHistoryItem;
import com.shopflow.app.network.ApiCallback;
import com.shopflow.app.network.ApiClient;

import java.text.NumberFormat;
import java.util.List;
import java.util.Locale;

/**
 * Main customer dashboard for ShopFlow.
 * Server-authoritative: displays real-time active shopping sessions, dynamic cart contents,
 * authoritative payment status, exit permissions, and shopping history directly from the backend.
 */
public class MainActivity extends BaseActivity implements NavigationView.OnNavigationItemSelectedListener {

    private static final int ACTIVE_POLL_INTERVAL_MS = 1500;
    private static final int IDLE_POLL_INTERVAL_MS = 3500;

    private DrawerLayout drawerLayout;
    private NavigationView navigationView;
    private SessionManager sessionManager;
    private ApiClient apiClient;

    private String customerId = "";

    // Real-Time Polling & Synchronization
    private final Handler pollHandler = new Handler(Looper.getMainLooper());
    private boolean isPollingActive = false;
    private boolean isSyncInProgress = false;
    private CustomerSession currentSession = null;
    private ShoppingCart currentCart = null;
    private long lastSyncTimestamp = 0L;

    // Reference to open BottomSheets for dynamic live updates
    private BottomSheetDialog activeCartSheetDialog;
    private View activeCartSheetView;

    // Status Indicator Views (Phase 15)
    private LinearLayout layoutLiveStatusPill;
    private View viewLiveStatusDot;
    private TextView tvLiveStatusText;

    // Header / Profile Views
    private TextView tvHomeAvatarInitials;
    private TextView tvGreeting;
    private TextView tvSubtitle;
    private TextView tvCustomerIdBadge;

    // Shopping Status Card Views
    private LinearLayout layoutShoppingActive;
    private LinearLayout layoutShoppingInactive;
    private TextView tvSessionId;
    private TextView tvSessionStartTime;
    private TextView tvStatusCartCount;
    private MaterialButton btnViewCartStatus;

    // Payment Status Card Views
    private View cardPaymentStatus;
    private LinearLayout layoutPaymentPending;
    private LinearLayout layoutPaymentCompleted;
    private TextView tvPaymentCartCount;
    private TextView tvPaymentAmount;
    private TextView tvPaymentCompletedAmount;
    private MaterialButton btnReviewPay;

    // Statistics Views
    private TextView tvStatVisits;
    private TextView tvStatSpent;
    private TextView tvStatItems;

    // Cart Preview Views
    private LinearLayout layoutCartPreviewActive;
    private LinearLayout layoutCartPreviewEmpty;
    private LinearLayout llCartPreviewItemsContainer;
    private TextView tvPreviewItemCount;
    private TextView tvCartMoreItems;
    private TextView tvCartPreviewTotal;
    private MaterialButton btnViewCartPreview;

    // Last Visit Views
    private View cardLastVisit;
    private TextView tvLastVisitDate;
    private TextView tvLastVisitSummary;

    // Drawer Header Views
    private TextView tvNavAvatarInitials;
    private TextView tvNavCustomerName;
    private TextView tvNavCustomerId;
    private TextView tvNavCustomerEmail;

    // Periodic polling runnable
    private final Runnable pollRunnable = new Runnable() {
        @Override
        public void run() {
            if (!isPollingActive) return;
            syncActiveSession();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        sessionManager = new SessionManager(this);
        apiClient = ApiClient.getInstance();

        // Retrieve customer ID from intent or session manager
        String intentCustId = getIntent().getStringExtra("CUSTOMER_ID");
        if (intentCustId != null && !intentCustId.isEmpty()) {
            customerId = intentCustId;
        } else {
            customerId = sessionManager.getCustomerId();
        }

        initViews();
        setupWindowInsets();
        setupListeners();
        setupDrawerHeader();
        bindCachedUiData();

        // Handle back press to close drawer if open
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (drawerLayout != null && drawerLayout.isDrawerOpen(GravityCompat.START)) {
                    drawerLayout.closeDrawer(GravityCompat.START);
                } else {
                    finish();
                }
            }
        });
    }

    private void setupWindowInsets() {
        if (drawerLayout == null) return;
        ViewCompat.setOnApplyWindowInsetsListener(drawerLayout, (v, windowInsets) -> {
            Insets insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars());

            View appBar = findViewById(R.id.appBarLayout);
            if (appBar != null) {
                appBar.setPadding(0, insets.top, 0, 0);
            }

            View scrollView = findViewById(R.id.nestedScrollView);
            if (scrollView != null) {
                scrollView.setPadding(
                        scrollView.getPaddingLeft(),
                        scrollView.getPaddingTop(),
                        scrollView.getPaddingRight(),
                        insets.bottom
                );
            }

            if (navigationView != null && navigationView.getHeaderCount() > 0) {
                View navHeader = navigationView.getHeaderView(0);
                if (navHeader != null) {
                    int extraTop = (int) (16 * getResources().getDisplayMetrics().density);
                    navHeader.setPadding(
                            navHeader.getPaddingLeft(),
                            insets.top + extraTop,
                            navHeader.getPaddingRight(),
                            navHeader.getPaddingBottom()
                    );
                }
            }

            return windowInsets;
        });
        ViewCompat.requestApplyInsets(drawerLayout);
    }

    @Override
    protected void onResume() {
        super.onResume();
        setupDrawerHeader();
        startPolling();
        syncCustomerHistoryAndStats();
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopPolling();
    }

    @Override
    protected void onStop() {
        super.onStop();
        stopPolling();
        apiClient.cancelCallsWithTag("active-session");
    }

    @Override
    protected void onDestroy() {
        stopPolling();
        if (activeCartSheetDialog != null && activeCartSheetDialog.isShowing()) {
            activeCartSheetDialog.dismiss();
        }
        super.onDestroy();
    }

    @Override
    protected void onNetworkRestored() {
        super.onNetworkRestored();
        updateStatusIndicator("SYNCING...", R.color.primary, true);
        startPolling();
        syncCustomerHistoryAndStats();
    }

    @Override
    protected void onNetworkLost() {
        super.onNetworkLost();
        updateStatusIndicator("OFFLINE", R.color.error, false);
        stopPolling();
    }

    private void initViews() {
        drawerLayout = findViewById(R.id.drawerLayout);
        navigationView = findViewById(R.id.navigationView);

        // Status Indicator
        layoutLiveStatusPill = findViewById(R.id.layoutLiveStatusPill);
        viewLiveStatusDot = findViewById(R.id.viewLiveStatusDot);
        tvLiveStatusText = findViewById(R.id.tvLiveStatusText);

        // Header / Greeting
        tvHomeAvatarInitials = findViewById(R.id.tvHomeAvatarInitials);
        tvGreeting = findViewById(R.id.tvGreeting);
        tvSubtitle = findViewById(R.id.tvSubtitle);
        tvCustomerIdBadge = findViewById(R.id.tvCustomerIdBadge);

        // Shopping Status
        layoutShoppingActive = findViewById(R.id.layoutShoppingActive);
        layoutShoppingInactive = findViewById(R.id.layoutShoppingInactive);
        tvSessionId = findViewById(R.id.tvSessionId);
        tvSessionStartTime = findViewById(R.id.tvSessionStartTime);
        tvStatusCartCount = findViewById(R.id.tvStatusCartCount);
        btnViewCartStatus = findViewById(R.id.btnViewCartStatus);

        // Payment Status
        cardPaymentStatus = findViewById(R.id.cardPaymentStatus);
        layoutPaymentPending = findViewById(R.id.layoutPaymentPending);
        layoutPaymentCompleted = findViewById(R.id.layoutPaymentCompleted);
        tvPaymentCartCount = findViewById(R.id.tvPaymentCartCount);
        tvPaymentAmount = findViewById(R.id.tvPaymentAmount);
        tvPaymentCompletedAmount = findViewById(R.id.tvPaymentCompletedAmount);
        btnReviewPay = findViewById(R.id.btnReviewPay);

        // Statistics
        tvStatVisits = findViewById(R.id.tvStatVisits);
        tvStatSpent = findViewById(R.id.tvStatSpent);
        tvStatItems = findViewById(R.id.tvStatItems);

        // Cart Preview
        layoutCartPreviewActive = findViewById(R.id.layoutCartPreviewActive);
        layoutCartPreviewEmpty = findViewById(R.id.layoutCartPreviewEmpty);
        llCartPreviewItemsContainer = findViewById(R.id.llCartPreviewItemsContainer);
        tvPreviewItemCount = findViewById(R.id.tvPreviewItemCount);
        tvCartMoreItems = findViewById(R.id.tvCartMoreItems);
        tvCartPreviewTotal = findViewById(R.id.tvCartPreviewTotal);
        btnViewCartPreview = findViewById(R.id.btnViewCartPreview);

        // Last Visit
        cardLastVisit = findViewById(R.id.cardLastVisit);
        tvLastVisitDate = findViewById(R.id.tvLastVisitDate);
        tvLastVisitSummary = findViewById(R.id.tvLastVisitSummary);
    }

    private void setupListeners() {
        ImageView btnMenu = findViewById(R.id.btnMenu);
        if (btnMenu != null) {
            btnMenu.setOnClickListener(v -> {
                if (drawerLayout != null) {
                    drawerLayout.openDrawer(GravityCompat.START);
                }
            });
        }

        View btnNotifications = findViewById(R.id.btnNotifications);
        if (btnNotifications != null) {
            btnNotifications.setOnClickListener(v -> showNotificationDialog());
        }

        if (navigationView != null) {
            navigationView.setNavigationItemSelectedListener(this);
        }

        if (btnViewCartStatus != null) {
            btnViewCartStatus.setOnClickListener(v -> openCartSheet());
        }
        if (btnViewCartPreview != null) {
            btnViewCartPreview.setOnClickListener(v -> openCartSheet());
        }
        if (btnReviewPay != null) {
            btnReviewPay.setOnClickListener(v -> openCartSheet());
        }

        if (cardLastVisit != null) {
            cardLastVisit.setOnClickListener(v -> openHistorySheet());
        }
    }

    private void setupDrawerHeader() {
        if (navigationView == null) return;
        View headerView = navigationView.getHeaderView(0);
        if (headerView == null) return;

        tvNavAvatarInitials = headerView.findViewById(R.id.tvNavAvatarInitials);
        tvNavCustomerName = headerView.findViewById(R.id.tvNavCustomerName);
        tvNavCustomerId = headerView.findViewById(R.id.tvNavCustomerId);
        tvNavCustomerEmail = headerView.findViewById(R.id.tvNavCustomerEmail);

        String firstName = sessionManager.getFirstName();
        String lastName = sessionManager.getLastName();
        String fullName = sessionManager.getFullName();
        String custId = customerId.isEmpty() ? sessionManager.getCustomerId() : customerId;
        String email = sessionManager.getUserEmail();

        String initials = "";
        if (!firstName.isEmpty()) initials += firstName.substring(0, 1).toUpperCase();
        if (!lastName.isEmpty()) initials += lastName.substring(0, 1).toUpperCase();
        if (initials.isEmpty()) initials = "SF";

        if (tvNavAvatarInitials != null) tvNavAvatarInitials.setText(initials);
        if (tvNavCustomerName != null) tvNavCustomerName.setText(fullName.isEmpty() ? "Valued Customer" : fullName);
        if (tvNavCustomerId != null) tvNavCustomerId.setText("Customer ID: " + custId);
        if (tvNavCustomerEmail != null) tvNavCustomerEmail.setText(email);
    }

    /**
     * Binds identity and cached customer statistics so screen starts instantly
     * without displaying fake demonstration defaults.
     */
    private void bindCachedUiData() {
        String firstName = sessionManager.getFirstName();
        String lastName = sessionManager.getLastName();
        String custId = customerId.isEmpty() ? sessionManager.getCustomerId() : customerId;

        String initials = "";
        if (!firstName.isEmpty()) initials += firstName.substring(0, 1).toUpperCase();
        if (!lastName.isEmpty()) initials += lastName.substring(0, 1).toUpperCase();
        if (initials.isEmpty()) initials = "SF";

        if (tvHomeAvatarInitials != null) tvHomeAvatarInitials.setText(initials);
        if (tvGreeting != null) tvGreeting.setText("Hi, " + (firstName.isEmpty() ? "Shopper" : firstName) + " 👋");
        if (tvSubtitle != null) tvSubtitle.setText("Welcome back!");
        if (tvCustomerIdBadge != null) tvCustomerIdBadge.setText("Customer ID: " + custId);

        // Bind cached customer statistics
        int visits = sessionManager.getTotalVisits();
        float spent = sessionManager.getTotalSpent();
        int items = sessionManager.getItemsPurchased();

        if (tvStatVisits != null) tvStatVisits.setText(String.valueOf(visits));
        if (tvStatSpent != null) {
            NumberFormat nf = NumberFormat.getCurrencyInstance(new Locale("en", "IN"));
            nf.setMaximumFractionDigits(0);
            tvStatSpent.setText(nf.format(spent));
        }
        if (tvStatItems != null) tvStatItems.setText(String.valueOf(items));

        // Bind cached last visit
        String lastDate = sessionManager.getLastVisitDate();
        int lastItems = sessionManager.getLastVisitItems();
        float lastAmount = sessionManager.getLastVisitAmount();

        if (tvLastVisitDate != null) {
            tvLastVisitDate.setText(lastDate.isEmpty() ? "No visits yet" : lastDate);
        }
        if (tvLastVisitSummary != null) {
            if (lastDate.isEmpty() && lastItems == 0) {
                tvLastVisitSummary.setText("0 items purchased • ₹0.00");
            } else {
                NumberFormat nf = NumberFormat.getCurrencyInstance(new Locale("en", "IN"));
                tvLastVisitSummary.setText(lastItems + " items purchased • " + nf.format(lastAmount));
            }
        }

        // Initially show inactive until authoritative backend response arrives
        bindInactiveShoppingUi();
    }

    // =========================================================================
    // REAL-TIME POLLING & SYNCHRONIZATION (Phase 4, 5, 10, 15, 17)
    // =========================================================================

    private void startPolling() {
        if (isPollingActive) return;
        isPollingActive = true;
        syncActiveSession();
    }

    private void stopPolling() {
        isPollingActive = false;
        pollHandler.removeCallbacks(pollRunnable);
    }

    private void scheduleNextPoll(long delayMs) {
        if (!isPollingActive) return;
        pollHandler.removeCallbacks(pollRunnable);
        pollHandler.postDelayed(pollRunnable, delayMs);
    }

    /**
     * Polls the backend for active shopping session:
     * GET /api/shopping/customer/<customer_id>/active-session
     */
    private void syncActiveSession() {
        if (!isNetworkAvailable()) {
            updateStatusIndicator("OFFLINE", R.color.error, false);
            return;
        }

        if (isSyncInProgress) {
            scheduleNextPoll(ACTIVE_POLL_INTERVAL_MS);
            return;
        }

        isSyncInProgress = true;
        String custId = customerId.isEmpty() ? sessionManager.getCustomerId() : customerId;

        apiClient.getActiveShoppingSession(custId, new ApiCallback<CustomerSession>() {
            @Override
            public void onSuccess(CustomerSession session) {
                isSyncInProgress = false;
                lastSyncTimestamp = System.currentTimeMillis();

                if (session != null && session.isActive()) {
                    boolean sessionChanged = currentSession == null
                            || !session.getSessionId().equals(currentSession.getSessionId())
                            || session.getItemCount() != currentSession.getItemCount()
                            || session.getTotal() != currentSession.getTotal()
                            || !session.getPaymentStatus().equals(currentSession.getPaymentStatus())
                            || session.isExitAllowed() != currentSession.isExitAllowed();

                    currentSession = session;
                    currentCart = session.getCart();

                    // Cache in SessionManager
                    sessionManager.saveActiveSessionSnapshot(
                            session.getSessionId(),
                            session.getItemCount(),
                            (float) session.getTotal(),
                            session.getFormattedStartTime(),
                            session.getStatus(),
                            session.getPaymentStatus(),
                            session.isExitAllowed()
                    );

                    bindActiveShoppingUi(session);

                    // Update live indicator
                    if (session.isPaid()) {
                        updateStatusIndicator("PAID • Exit Allowed", R.color.success, true);
                    } else if (session.isPendingPayment()) {
                        updateStatusIndicator("LIVE • Cart Synced", R.color.primary, true);
                    } else {
                        updateStatusIndicator("ACTIVE", R.color.primary, true);
                    }

                    // Dynamically refresh open bottom sheet if active
                    if (activeCartSheetDialog != null && activeCartSheetDialog.isShowing() && activeCartSheetView != null) {
                        refreshCartSheetViewData(activeCartSheetView, session, currentCart);
                    }

                    // Continue fast polling while shopping is active
                    scheduleNextPoll(ACTIVE_POLL_INTERVAL_MS);

                } else {
                    // Check if previous session just completed
                    if (currentSession != null && currentSession.isActive()) {
                        Toast.makeText(MainActivity.this, "Shopping session completed! Thank you for visiting.", Toast.LENGTH_LONG).show();
                        syncCustomerHistoryAndStats();
                    }

                    currentSession = null;
                    currentCart = null;
                    sessionManager.clearActiveSessionSnapshot();

                    bindInactiveShoppingUi();
                    updateStatusIndicator("NOT SHOPPING", R.color.text_muted, false);

                    // Idle polling interval
                    scheduleNextPoll(IDLE_POLL_INTERVAL_MS);
                }
            }

            @Override
            public void onError(String errorMessage, int statusCode) {
                isSyncInProgress = false;
                if (statusCode == -1) {
                    updateStatusIndicator("OFFLINE", R.color.error, false);
                } else {
                    updateStatusIndicator("SYNC RETRY", R.color.bio_amber, false);
                }
                scheduleNextPoll(IDLE_POLL_INTERVAL_MS);
            }
        });
    }

    private void updateStatusIndicator(String statusText, int colorResId, boolean isLive) {
        if (tvLiveStatusText != null) {
            tvLiveStatusText.setText(statusText);
            tvLiveStatusText.setTextColor(ContextCompat.getColor(this, colorResId));
        }
        if (viewLiveStatusDot != null) {
            int dotColor = ContextCompat.getColor(this, colorResId);
            viewLiveStatusDot.setBackgroundTintList(ColorStateList.valueOf(dotColor));
        }
    }

    // =========================================================================
    // UI BINDING — SERVER-AUTHORITATIVE (Phase 6)
    // =========================================================================

    private void bindActiveShoppingUi(@NonNull CustomerSession session) {
        if (layoutShoppingActive != null) layoutShoppingActive.setVisibility(View.VISIBLE);
        if (layoutShoppingInactive != null) layoutShoppingInactive.setVisibility(View.GONE);

        if (tvSessionId != null) {
            tvSessionId.setText("Session ID: " + session.getSessionId());
        }
        if (tvSessionStartTime != null) {
            tvSessionStartTime.setText("Started at " + session.getFormattedStartTime());
        }
        if (tvStatusCartCount != null) {
            int count = session.getItemCount();
            tvStatusCartCount.setText(count + (count == 1 ? " item in cart" : " items in cart"));
        }

        // Payment Card UI
        if (cardPaymentStatus != null) cardPaymentStatus.setVisibility(View.VISIBLE);
        boolean isPaid = session.isPaid();
        String formattedTotal = session.getFormattedTotal();

        if (isPaid) {
            if (layoutPaymentPending != null) layoutPaymentPending.setVisibility(View.GONE);
            if (layoutPaymentCompleted != null) layoutPaymentCompleted.setVisibility(View.VISIBLE);
            if (tvPaymentCompletedAmount != null) tvPaymentCompletedAmount.setText(formattedTotal);
        } else {
            if (layoutPaymentPending != null) layoutPaymentPending.setVisibility(View.VISIBLE);
            if (layoutPaymentCompleted != null) layoutPaymentCompleted.setVisibility(View.GONE);
            if (tvPaymentCartCount != null) {
                int count = session.getItemCount();
                tvPaymentCartCount.setText(count + (count == 1 ? " item in your cart" : " items in your cart"));
            }
            if (tvPaymentAmount != null) tvPaymentAmount.setText(formattedTotal);
        }

        // Cart Preview UI
        ShoppingCart cart = session.getCart();
        if (cart != null && !cart.isEmpty()) {
            if (layoutCartPreviewActive != null) layoutCartPreviewActive.setVisibility(View.VISIBLE);
            if (layoutCartPreviewEmpty != null) layoutCartPreviewEmpty.setVisibility(View.GONE);

            if (tvPreviewItemCount != null) {
                int count = cart.getItemCount();
                tvPreviewItemCount.setText(count + (count == 1 ? " item" : " items"));
            }
            if (tvCartPreviewTotal != null) {
                tvCartPreviewTotal.setText(cart.getFormattedTotal());
            }

            // Dynamically populate items in preview
            populateCartPreviewItems(cart.getItems());
        } else {
            if (layoutCartPreviewActive != null) layoutCartPreviewActive.setVisibility(View.GONE);
            if (layoutCartPreviewEmpty != null) layoutCartPreviewEmpty.setVisibility(View.VISIBLE);
            if (tvPreviewItemCount != null) tvPreviewItemCount.setText("0 items");
            if (llCartPreviewItemsContainer != null) llCartPreviewItemsContainer.removeAllViews();
            if (tvCartMoreItems != null) tvCartMoreItems.setVisibility(View.GONE);
        }
    }

    private void bindInactiveShoppingUi() {
        if (layoutShoppingActive != null) layoutShoppingActive.setVisibility(View.GONE);
        if (layoutShoppingInactive != null) layoutShoppingInactive.setVisibility(View.VISIBLE);

        if (cardPaymentStatus != null) cardPaymentStatus.setVisibility(View.GONE);

        if (layoutCartPreviewActive != null) layoutCartPreviewActive.setVisibility(View.GONE);
        if (layoutCartPreviewEmpty != null) layoutCartPreviewEmpty.setVisibility(View.VISIBLE);
        if (tvPreviewItemCount != null) tvPreviewItemCount.setText("0 items");

        if (llCartPreviewItemsContainer != null) llCartPreviewItemsContainer.removeAllViews();
        if (tvCartMoreItems != null) tvCartMoreItems.setVisibility(View.GONE);
    }

    private void populateCartPreviewItems(List<CartItem> items) {
        if (llCartPreviewItemsContainer == null) return;
        llCartPreviewItemsContainer.removeAllViews();

        LayoutInflater inflater = LayoutInflater.from(this);
        int maxPreview = 3;
        int displayCount = Math.min(items.size(), maxPreview);

        for (int i = 0; i < displayCount; i++) {
            CartItem item = items.get(i);
            View itemView = inflater.inflate(R.layout.item_cart_preview, llCartPreviewItemsContainer, false);

            TextView tvName = itemView.findViewById(R.id.tvPreviewItemName);
            TextView tvSubtotal = itemView.findViewById(R.id.tvPreviewItemSubtotal);

            String nameDisplay = item.getName();
            if (item.getQuantity() > 1) {
                nameDisplay = item.getName() + " (" + item.getQuantity() + "x)";
            }
            tvName.setText(nameDisplay);
            tvSubtotal.setText(item.getFormattedSubtotal());

            llCartPreviewItemsContainer.addView(itemView);
        }

        if (tvCartMoreItems != null) {
            int remaining = items.size() - maxPreview;
            if (remaining > 0) {
                tvCartMoreItems.setVisibility(View.VISIBLE);
                tvCartMoreItems.setText("+" + remaining + " more items");
            } else {
                tvCartMoreItems.setVisibility(View.GONE);
            }
        }
    }

    // =========================================================================
    // CART BOTTOM SHEET & PAYMENT (Phase 7, 8, 9, 10)
    // =========================================================================

    private void openCartSheet() {
        activeCartSheetDialog = new BottomSheetDialog(this);
        activeCartSheetView = getLayoutInflater().inflate(R.layout.layout_cart_sheet, null);
        activeCartSheetDialog.setContentView(activeCartSheetView);

        TextView tvSheetSessionId = activeCartSheetView.findViewById(R.id.tvSheetSessionId);
        ProgressBar pbCartSheetLoading = activeCartSheetView.findViewById(R.id.pbCartSheetLoading);
        LinearLayout layoutCartSheetEmpty = activeCartSheetView.findViewById(R.id.layoutCartSheetEmpty);
        LinearLayout layoutCartSheetContent = activeCartSheetView.findViewById(R.id.layoutCartSheetContent);
        MaterialButton btnSheetPay = activeCartSheetView.findViewById(R.id.btnSheetPay);

        // If no active session, show empty state immediately
        if (currentSession == null || !currentSession.isActive()) {
            if (tvSheetSessionId != null) tvSheetSessionId.setText("No Session");
            if (layoutCartSheetContent != null) layoutCartSheetContent.setVisibility(View.GONE);
            if (layoutCartSheetEmpty != null) layoutCartSheetEmpty.setVisibility(View.VISIBLE);
            if (btnSheetPay != null) {
                btnSheetPay.setEnabled(false);
                btnSheetPay.setText("No Active Cart");
            }
            activeCartSheetDialog.show();
            return;
        }

        // Show session ID and display loading while fetching latest cart
        if (tvSheetSessionId != null) tvSheetSessionId.setText(currentSession.getSessionId());
        if (pbCartSheetLoading != null) pbCartSheetLoading.setVisibility(View.VISIBLE);
        if (layoutCartSheetContent != null) layoutCartSheetContent.setVisibility(View.GONE);
        if (layoutCartSheetEmpty != null) layoutCartSheetEmpty.setVisibility(View.GONE);

        // First present current cached session data immediately if available
        if (currentCart != null && !currentCart.isEmpty()) {
            refreshCartSheetViewData(activeCartSheetView, currentSession, currentCart);
            if (pbCartSheetLoading != null) pbCartSheetLoading.setVisibility(View.GONE);
            if (layoutCartSheetContent != null) layoutCartSheetContent.setVisibility(View.VISIBLE);
        }

        activeCartSheetDialog.show();

        // Refresh live cart from backend
        apiClient.getCart(currentSession.getSessionId(), new ApiCallback<ShoppingCart>() {
            @Override
            public void onSuccess(ShoppingCart cart) {
                currentCart = cart;
                if (pbCartSheetLoading != null) pbCartSheetLoading.setVisibility(View.GONE);

                if (activeCartSheetDialog != null && activeCartSheetDialog.isShowing() && activeCartSheetView != null) {
                    refreshCartSheetViewData(activeCartSheetView, currentSession, cart);
                }
            }

            @Override
            public void onError(String errorMessage, int statusCode) {
                if (pbCartSheetLoading != null) pbCartSheetLoading.setVisibility(View.GONE);
                if (currentCart == null || currentCart.isEmpty()) {
                    if (layoutCartSheetEmpty != null) layoutCartSheetEmpty.setVisibility(View.VISIBLE);
                    if (layoutCartSheetContent != null) layoutCartSheetContent.setVisibility(View.GONE);
                    Toast.makeText(MainActivity.this, "Unable to refresh cart: " + errorMessage, Toast.LENGTH_SHORT).show();
                }
            }
        });
    }

    private void refreshCartSheetViewData(View sheetView, CustomerSession session, ShoppingCart cart) {
        if (sheetView == null || session == null) return;

        TextView tvSheetSessionId = sheetView.findViewById(R.id.tvSheetSessionId);
        ProgressBar pbCartSheetLoading = sheetView.findViewById(R.id.pbCartSheetLoading);
        LinearLayout layoutCartSheetEmpty = sheetView.findViewById(R.id.layoutCartSheetEmpty);
        LinearLayout layoutCartSheetContent = sheetView.findViewById(R.id.layoutCartSheetContent);
        LinearLayout llSheetCartItems = sheetView.findViewById(R.id.llSheetCartItems);
        LinearLayout layoutSheetSubtotal = sheetView.findViewById(R.id.layoutSheetSubtotal);
        TextView tvSheetSubtotal = sheetView.findViewById(R.id.tvSheetSubtotal);
        LinearLayout layoutSheetTax = sheetView.findViewById(R.id.layoutSheetTax);
        TextView tvSheetTaxLabel = sheetView.findViewById(R.id.tvSheetTaxLabel);
        TextView tvSheetTax = sheetView.findViewById(R.id.tvSheetTax);
        TextView tvSheetTotal = sheetView.findViewById(R.id.tvSheetTotal);
        MaterialButton btnSheetPay = sheetView.findViewById(R.id.btnSheetPay);

        if (tvSheetSessionId != null) tvSessionId.setText(session.getSessionId());

        if (cart == null || cart.isEmpty()) {
            if (layoutCartSheetContent != null) layoutCartSheetContent.setVisibility(View.GONE);
            if (layoutCartSheetEmpty != null) layoutCartSheetEmpty.setVisibility(View.VISIBLE);
            if (btnSheetPay != null) {
                btnSheetPay.setEnabled(false);
                btnSheetPay.setText("Cart is Empty");
            }
            return;
        }

        if (layoutCartSheetContent != null) layoutCartSheetContent.setVisibility(View.VISIBLE);
        if (layoutCartSheetEmpty != null) layoutCartSheetEmpty.setVisibility(View.GONE);

        // Populate cart items
        if (llSheetCartItems != null) {
            llSheetCartItems.removeAllViews();
            LayoutInflater inflater = LayoutInflater.from(this);

            for (CartItem item : cart.getItems()) {
                View itemView = inflater.inflate(R.layout.item_cart_sheet, llSheetCartItems, false);

                TextView tvName = itemView.findViewById(R.id.tvCartItemName);
                TextView tvQtyRate = itemView.findViewById(R.id.tvCartItemQuantityRate);
                TextView tvSubtotal = itemView.findViewById(R.id.tvCartItemSubtotal);

                tvName.setText(item.getName());
                tvQtyRate.setText("Qty: " + item.getQuantity() + " × " + item.getFormattedUnitPrice());
                tvSubtotal.setText(item.getFormattedSubtotal());

                llSheetCartItems.addView(itemView);
            }
        }

        // Subtotal row
        if (layoutSheetSubtotal != null) {
            layoutSheetSubtotal.setVisibility(View.VISIBLE);
            if (tvSheetSubtotal != null) tvSheetSubtotal.setText(cart.getFormattedSubtotal());
        }

        // Tax row
        if (cart.getTax() > 0 && layoutSheetTax != null) {
            layoutSheetTax.setVisibility(View.VISIBLE);
            if (tvSheetTaxLabel != null) {
                int percent = (int) (cart.getTaxRate() * 100);
                tvSheetTaxLabel.setText("Tax (" + percent + "%)");
            }
            if (tvSheetTax != null) tvSheetTax.setText(cart.getFormattedTax());
        } else if (layoutSheetTax != null) {
            layoutSheetTax.setVisibility(View.GONE);
        }

        // Total
        if (tvSheetTotal != null) {
            tvSheetTotal.setText(cart.getFormattedTotal());
        }

        // Configure Payment Button (Phase 8 & 9)
        if (btnSheetPay != null) {
            if (session.isPaid()) {
                btnSheetPay.setText("✓ Paid • Exit Permitted");
                btnSheetPay.setEnabled(false);
                btnSheetPay.setBackgroundTintList(ColorStateList.valueOf(ContextCompat.getColor(this, R.color.success)));
            } else {
                btnSheetPay.setEnabled(true);
                btnSheetPay.setText("Pay " + cart.getFormattedTotal() + " Now");
                btnSheetPay.setBackgroundTintList(ColorStateList.valueOf(ContextCompat.getColor(this, R.color.primary)));

                btnSheetPay.setOnClickListener(v -> executeSessionPayment(session, btnSheetPay));
            }
        }
    }

    /**
     * Executes server-authoritative payment for the active shopping session.
     * POST /api/shopping/session/<session_id>/payment
     */
    private void executeSessionPayment(@NonNull CustomerSession session, @NonNull MaterialButton btnPay) {
        if (!isNetworkAvailable()) {
            Toast.makeText(this, "Internet connection required for payment.", Toast.LENGTH_SHORT).show();
            return;
        }

        btnPay.setEnabled(false);
        btnPay.setText("Processing Payment with Server...");

        apiClient.payForSession(session.getSessionId(), "APP", new ApiCallback<PaymentResult>() {
            @Override
            public void onSuccess(PaymentResult result) {
                if (result.isSuccess() && "PAID".equalsIgnoreCase(result.getPaymentStatus())) {
                    Toast.makeText(
                            MainActivity.this,
                            "Payment Successful! You may now exit the store.",
                            Toast.LENGTH_LONG
                    ).show();

                    btnPay.setText("✓ Paid • Exit Permitted");
                    btnPay.setEnabled(false);
                    btnPay.setBackgroundTintList(ColorStateList.valueOf(ContextCompat.getColor(MainActivity.this, R.color.success)));

                    // Trigger immediate session sync to update all UI
                    syncActiveSession();
                    syncCustomerHistoryAndStats();
                } else {
                    btnPay.setEnabled(true);
                    btnPay.setText("Pay Now");
                    String msg = result.getMessage().isEmpty() ? "Payment was not authorized" : result.getMessage();
                    Toast.makeText(MainActivity.this, "Payment Failed: " + msg, Toast.LENGTH_LONG).show();
                }
            }

            @Override
            public void onError(String errorMessage, int statusCode) {
                btnPay.setEnabled(true);
                btnPay.setText("Pay Now");
                Toast.makeText(MainActivity.this, "Payment error: " + errorMessage, Toast.LENGTH_LONG).show();
            }
        });
    }

    // =========================================================================
    // SHOPPING HISTORY & CUSTOMER STATISTICS (Phase 11)
    // =========================================================================

    private void openHistorySheet() {
        BottomSheetDialog dialog = new BottomSheetDialog(this);
        View sheetView = getLayoutInflater().inflate(R.layout.layout_history_sheet, null);
        dialog.setContentView(sheetView);

        ProgressBar pbLoading = sheetView.findViewById(R.id.pbHistoryLoading);
        LinearLayout layoutEmpty = sheetView.findViewById(R.id.layoutHistoryEmpty);
        LinearLayout llContainer = sheetView.findViewById(R.id.llHistoryItemsContainer);

        if (pbLoading != null) pbLoading.setVisibility(View.VISIBLE);
        if (layoutEmpty != null) layoutEmpty.setVisibility(View.GONE);
        if (llContainer != null) llContainer.removeAllViews();

        dialog.show();

        String custId = customerId.isEmpty() ? sessionManager.getCustomerId() : customerId;
        apiClient.getShoppingHistory(custId, new ApiCallback<List<ShoppingHistoryItem>>() {
            @Override
            public void onSuccess(List<ShoppingHistoryItem> historyList) {
                if (pbLoading != null) pbLoading.setVisibility(View.GONE);

                if (historyList == null || historyList.isEmpty()) {
                    if (layoutEmpty != null) layoutEmpty.setVisibility(View.VISIBLE);
                } else {
                    if (layoutEmpty != null) layoutEmpty.setVisibility(View.GONE);
                    if (llContainer != null) {
                        LayoutInflater inflater = LayoutInflater.from(MainActivity.this);
                        for (ShoppingHistoryItem item : historyList) {
                            View itemView = inflater.inflate(R.layout.item_history, llContainer, false);

                            TextView tvDate = itemView.findViewById(R.id.tvHistoryItemDate);
                            TextView tvStatus = itemView.findViewById(R.id.tvHistoryItemStatus);
                            TextView tvSummary = itemView.findViewById(R.id.tvHistoryItemSummary);
                            TextView tvTotal = itemView.findViewById(R.id.tvHistoryItemTotal);

                            tvDate.setText(item.getFormattedDate());
                            tvStatus.setText(item.getStatus().toUpperCase());
                            tvSummary.setText(item.getSummaryText());
                            tvTotal.setText(item.getFormattedTotal());

                            llContainer.addView(itemView);
                        }
                    }
                    updateCustomerStatsFromHistory(historyList);
                }
            }

            @Override
            public void onError(String errorMessage, int statusCode) {
                if (pbLoading != null) pbLoading.setVisibility(View.GONE);
                if (layoutEmpty != null) layoutEmpty.setVisibility(View.VISIBLE);
                Toast.makeText(MainActivity.this, "Unable to load history: " + errorMessage, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void syncCustomerHistoryAndStats() {
        if (!isNetworkAvailable()) return;
        String custId = customerId.isEmpty() ? sessionManager.getCustomerId() : customerId;

        apiClient.getShoppingHistory(custId, new ApiCallback<List<ShoppingHistoryItem>>() {
            @Override
            public void onSuccess(List<ShoppingHistoryItem> historyList) {
                if (historyList != null && !historyList.isEmpty()) {
                    updateCustomerStatsFromHistory(historyList);
                }
            }

            @Override
            public void onError(String errorMessage, int statusCode) {
                // Silently keep cached stats
            }
        });
    }

    private void updateCustomerStatsFromHistory(List<ShoppingHistoryItem> historyList) {
        if (historyList == null) return;

        int totalVisits = historyList.size();
        float totalSpent = 0.0f;
        int itemsPurchased = 0;

        for (ShoppingHistoryItem h : historyList) {
            totalSpent += h.getTotal();
            itemsPurchased += h.getItemCount();
        }

        // Cache in SessionManager
        sessionManager.saveCustomerStats(totalVisits, totalSpent, itemsPurchased);

        // Update dashboard statistics
        if (tvStatVisits != null) tvStatVisits.setText(String.valueOf(totalVisits));
        if (tvStatSpent != null) {
            NumberFormat nf = NumberFormat.getCurrencyInstance(new Locale("en", "IN"));
            nf.setMaximumFractionDigits(0);
            tvStatSpent.setText(nf.format(totalSpent));
        }
        if (tvStatItems != null) tvStatItems.setText(String.valueOf(itemsPurchased));

        // Update Last Visit Card with most recent session
        if (!historyList.isEmpty()) {
            ShoppingHistoryItem latest = historyList.get(0);
            sessionManager.saveLastVisit(latest.getFormattedDate(), latest.getItemCount(), (float) latest.getTotal());

            if (tvLastVisitDate != null) tvLastVisitDate.setText(latest.getFormattedDate());
            if (tvLastVisitSummary != null) {
                NumberFormat nf = NumberFormat.getCurrencyInstance(new Locale("en", "IN"));
                tvLastVisitSummary.setText(latest.getItemCount() + " items purchased • " + nf.format(latest.getTotal()));
            }
        }
    }

    // =========================================================================
    // NAVIGATION & DIALOGS
    // =========================================================================

    @Override
    public boolean onNavigationItemSelected(@NonNull MenuItem item) {
        int id = item.getItemId();

        if (id == R.id.nav_home) {
            if (drawerLayout != null) drawerLayout.closeDrawer(GravityCompat.START);
            return true;
        } else if (id == R.id.nav_cart) {
            if (drawerLayout != null) drawerLayout.closeDrawer(GravityCompat.START);
            openCartSheet();
            return true;
        } else if (id == R.id.nav_history) {
            if (drawerLayout != null) drawerLayout.closeDrawer(GravityCompat.START);
            openHistorySheet();
            return true;
        } else if (id == R.id.nav_payments) {
            if (drawerLayout != null) drawerLayout.closeDrawer(GravityCompat.START);
            openCartSheet();
            return true;
        } else if (id == R.id.nav_account) {
            if (drawerLayout != null) drawerLayout.closeDrawer(GravityCompat.START);
            startActivity(new Intent(MainActivity.this, AccountActivity.class));
            return true;
        } else if (id == R.id.nav_settings) {
            if (drawerLayout != null) drawerLayout.closeDrawer(GravityCompat.START);
            startActivity(new Intent(MainActivity.this, SettingsActivity.class));
            return true;
        } else if (id == R.id.nav_help) {
            if (drawerLayout != null) drawerLayout.closeDrawer(GravityCompat.START);
            showHelpDialog();
            return true;
        } else if (id == R.id.nav_logout) {
            if (drawerLayout != null) drawerLayout.closeDrawer(GravityCompat.START);
            handleLogout();
            return true;
        }

        return false;
    }

    private void showNotificationDialog() {
        String statusMsg;
        if (currentSession != null && currentSession.isActive()) {
            statusMsg = "• Shopping session active: #" + currentSession.getSessionId() + "\n"
                    + "• " + currentSession.getItemCount() + " items currently tracked.\n"
                    + "• Payment status: " + currentSession.getPaymentStatus();
        } else {
            statusMsg = "• No active shopping session.\n• Ready to shop at any ShopFlow retail store.";
        }

        new AlertDialog.Builder(this)
                .setTitle("Notifications")
                .setMessage(statusMsg)
                .setPositiveButton("Close", null)
                .show();
    }

    private void showHelpDialog() {
        new AlertDialog.Builder(this)
                .setTitle("How ShopFlow Works")
                .setMessage("1. Walk In: Look into the entrance camera to link your registered face.\n\n"
                        + "2. Pick & Shop: Intelligent cameras and sensors track items you take automatically.\n\n"
                        + "3. Review & Pay: Confirm your cart and pay seamlessly inside the app.\n\n"
                        + "4. Walk Out: Once paid, you may exit freely with zero checkout lines!")
                .setPositiveButton("Understood", null)
                .show();
    }

    private void handleLogout() {
        new AlertDialog.Builder(this)
                .setTitle("Log Out")
                .setMessage("Are you sure you want to log out of ShopFlow?")
                .setPositiveButton("Log Out", (dialog, which) -> {
                    stopPolling();
                    sessionManager.clearSession();
                    Intent intent = new Intent(MainActivity.this, LoginActivity.class);
                    intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                    startActivity(intent);
                    finish();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }
}