package com.shopflow.app;

import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.provider.Settings;

public class NetworkUtils {

    /**
     * Checks whether the device is currently connected to an active network with internet capability.
     *
     * @param context Application or Activity context.
     * @return true if internet is available, false otherwise.
     */
    public static boolean isInternetConnected(Context context) {
        if (context == null) {
            return false;
        }

        ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) {
            return false;
        }

        Network activeNetwork = cm.getActiveNetwork();
        if (activeNetwork == null) {
            return false;
        }

        NetworkCapabilities capabilities = cm.getNetworkCapabilities(activeNetwork);
        if (capabilities == null) {
            return false;
        }

        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
    }

    /**
     * Opens system network or Wi-Fi settings so the user can easily enable connectivity.
     *
     * @param context Calling context.
     */
    public static void openNetworkSettings(Context context) {
        if (context == null) return;

        Intent intent = new Intent(Settings.ACTION_WIFI_SETTINGS);
        try {
            context.startActivity(intent);
        } catch (Exception e) {
            try {
                context.startActivity(new Intent(Settings.ACTION_WIRELESS_SETTINGS));
            } catch (Exception ex) {
                try {
                    context.startActivity(new Intent(Settings.ACTION_SETTINGS));
                } catch (Exception ignored) {
                }
            }
        }
    }
}
