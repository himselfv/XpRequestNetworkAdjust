package xyz.xprequestnetworkadjust;

import de.robv.android.xposed.XposedBridge;

/**
 * Structured logging utility for network interception hooks.
 * All output goes through XposedBridge.log for visibility in logcat.
 */
public final class NetLog {
    private static final String TAG = "XpReqNet";

    private NetLog() {}

    public static void d(String msg) {
        XposedBridge.log("[" + TAG + "] " + msg);
    }

    public static void i(String msg) {
        XposedBridge.log("[" + TAG + " I] " + msg);
    }

    public static void w(String msg) {
        XposedBridge.log("[" + TAG + " W] " + msg);
    }

    public static void e(String msg) {
        XposedBridge.log("[" + TAG + " E] " + msg);
    }

    /**
     * Format a NetworkRequest's capabilities into a readable string.
     * Handles null safely.
     */
    public static String capabilitiesToString(int[] transports, int[] requirements, int[] matchingCapabilities) {
        StringBuilder sb = new StringBuilder();
        sb.append("transports=").append(transportsToString(transports));
        sb.append(" reqs=").append(capabilitiesToString(requirements));
        sb.append(" match=").append(capabilitiesToString(matchingCapabilities));
        return sb.toString();
    }

    private static String transportsToString(int[] arr) {
        if (arr == null) return "[]";
        StringBuilder sb = new StringBuilder("[").append(arr[0]);
        for (int i = 1; i < arr.length; i++) sb.append(',').append(arr[i]);
        return sb.toString();
    }

    private static String capabilitiesToString(int[] caps) {
        if (caps == null) return "[]";
        StringBuilder sb = new StringBuilder("[").append(caps[0]);
        for (int i = 1; i < caps.length; i++) sb.append(',').append(caps[i]);
        return sb.toString();
    }

    /**
     * Human-readable capability name.
     */
    public static String capabilityName(int cap) {
        switch (cap) {
            case 0:  return "NONE";
            case 1:  return "TEMPORARY";
            case 2:  return "NOT_SUSPECT";
            case 3:  return "CONNECTED";
            case 4:  return "SUPPLICANT";
            case 5:  return "PROVISIONAL";
            case 6:  return "INTERNET";
            case 7:  return "NOT_RESTRICTED";
            case 8:  return "TRUSTED";
            case 9:  return "NOT_VPN";
            case 10: return "VALIDATED";
            case 11: return "NOT_ROAMING";
            case 12: return "FOREGROUND";
            case 13: return "NOT_CONGESTED";
            case 14: return "NOT_SUSPECT2";
            case 15: return "NOT_VPN2";
            case 16: return "NOT_METERED";
            case 17: return "INTERNET_GW_NOT_FULL";
            case 18: return "NOT_EXPIRED";
            default: return "UNKNOWN(" + cap + ")";
        }
    }

    /**
     * Convert capability bitmask to human-readable names.
     */
    public static String bitmaskToString(int mask) {
        if (mask == 0) return "NONE";
        StringBuilder sb = new StringBuilder();
        // Iterate over known capability values
        for (int cap = 0; cap <= 30; cap++) {
            if ((mask & (1 << cap)) != 0) {
                if (sb.length() > 0) sb.append('&');
                sb.append(capabilityName(cap));
            }
        }
        return sb.toString();
    }
}
