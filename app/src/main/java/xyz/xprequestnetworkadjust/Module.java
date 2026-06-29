package xyz.xprequestnetworkadjust;

import android.net.NetworkRequest;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

public class Module implements IXposedHookLoadPackage {

    /** NET_CAPABILITY_NOT_VPN = 15 */
    private static final int CAP_NOT_VPN = 15;

    @Override
    public void handleLoadPackage(final LoadPackageParam lpparam) throws Throwable {
        // ---- App-level hooks: intercept NetworkRequest.Builder in target apps ----
        if (!"android".equals(lpparam.packageName)) {
            hookNetworkRequestBuilder(lpparam);
        }

        // ---- System-level hooks: intercept ConnectivityManager in system server ----
        if ("android".equals(lpparam.packageName)) {
            hookConnectivityManager(lpparam);
        }
    }

    /**
     * Hook NetworkRequest.Builder methods in app processes.
     * Strips NOT_VPN capability before it gets set on the request.
     */
    private void hookNetworkRequestBuilder(LoadPackageParam lpparam) {
        final String cls = "android.net.NetworkRequest$Builder";
        try {
            if (classExists(cls, lpparam.classLoader)) {
                // Hook addCapability — strip NOT_VPN when explicitly requested
                XposedHelpers.findAndHookMethod(cls, lpparam.classLoader, "addCapability",
                        int.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        int cap = (int) param.args[0];
                        if (cap == CAP_NOT_VPN) {
                            NetLog.w("[" + lpparam.packageName + "] addCapability("
                                    + NetLog.capabilityName(cap) + ") -> stripped");
                            param.setResult(param.thisObject);
                        } else {
                            NetLog.d("[" + lpparam.packageName + "] addCapability("
                                    + NetLog.capabilityName(cap) + ")");
                        }
                    }
                });

                // Hook removeCapability — log when NOT_VPN is removed (indicates app didn't want VPN)
                XposedHelpers.findAndHookMethod(cls, lpparam.classLoader, "removeCapability",
                        int.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        int cap = (int) param.args[0];
                        if (cap == CAP_NOT_VPN) {
                            NetLog.i("[" + lpparam.packageName + "] removeCapability("
                                    + NetLog.capabilityName(cap) + ") -> app does NOT want VPN");
                        }
                        NetLog.d("[" + lpparam.packageName + "] removeCapability("
                                + NetLog.capabilityName(cap) + ")");
                    }
                });

                // Hook addTransportType — log transport preferences
                XposedHelpers.findAndHookMethod(cls, lpparam.classLoader, "addTransportType",
                        int.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        int transport = (int) param.args[0];
                        NetLog.d("[" + lpparam.packageName + "] addTransportType(" + transport + ")");
                    }
                });

                // Hook build() — dump the final NetworkRequest object
                XposedHelpers.findAndHookMethod(cls, lpparam.classLoader, "build",
                        NetworkRequest.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        // Log the final request with its current state
                        Object builder = param.thisObject;
                        int[] reqs = XposedHelpers.getIntField(builder, "requirements");
                        int[] matching = XposedHelpers.getIntField(builder, "matchingCapabilities");
                        int[] trans = XposedHelpers.getIntField(builder, "transports");
                        String caps = NetLog.bitmaskToString(
                                (reqs != null ? reqs[0] : 0));
                        NetLog.i("[" + lpparam.packageName + "] NetworkRequest.build() -> caps="
                                + caps);
                    }
                });
            }
        } catch (Throwable t) {
            NetLog.e("Failed to hook NetworkRequest.Builder in " + lpparam.packageName + ": " + t);
        }
    }

    /**
     * Hook ConnectivityManager in the system server process.
     * Intercepts requestNetwork() and registerNetworkCallback() calls before
     * they reach ConnectivityService, stripping NOT_VPN from any NetworkRequest.
     */
    private void hookConnectivityManager(LoadPackageParam lpparam) throws Throwable {
        String cmClass = "android.app.ContextImpl$InnerNetworkCallbackManager";
        String cmAltClass = "android.net.ConnectivityManager";

        // ---- Hook: ConnectivityManager.requestNetwork(NetworkRequest, ...) ----
        hookCmMethod(cmAltClass, lpparam.classLoader, "requestNetwork",
                NetworkRequest.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        NetworkRequest req = (NetworkRequest) param.args[0];
                        if (req != null) {
                            modifyNetworkRequest(req);
                        }
                    }
                });

        // ---- Hook: ConnectivityManager.registerNetworkCallback(NetworkRequest, ...) ----
        hookCmMethod(cmAltClass, lpparam.classLoader, "registerNetworkCallback",
                NetworkRequest.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        NetworkRequest req = (NetworkRequest) param.args[0];
                        if (req != null) {
                            modifyNetworkRequest(req);
                        }
                    }
                });

        // ---- Hook: ConnectivityManager.unregisterNetworkCallback(NetworkCallback) ----
        // (logging only, no modification needed)
    }

    /**
     * Helper: try to hook a ConnectivityManager method by name and signature.
     * Uses reflection to find the right overloaded variant.
     */
    private void hookCmMethod(String className, ClassLoader classLoader,
                              String methodName, Object... paramTypes) {
        try {
            XposedHelpers.findAndHookMethod(className, classLoader, methodName,
                    paramTypes, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    int callingUid = -1;
                    try {
                        callingUid = (int) XposedHelpers.callStaticMethod(
                                XposedHelpers.findClass("android.os.Process", classLoader),
                                "getCallingUid");
                    } catch (Throwable t) {
                        // Silently ignore — caller UID is informational
                    }
                    String pkg = "unknown";
                    try {
                        String pmClass = "android.app.ContextImpl";
                        Object activityThread = XposedHelpers.callStaticMethod(
                                XposedHelpers.findClass("android.app.ActivityThread", classLoader),
                                "currentActivityThread");
                        Object ctx = XposedHelpers.callMethod(activityThread, "getContext");
                        Object pm = XposedHelpers.callMethod(ctx, "getPackageManager");
                        Object ai = XposedHelpers.callMethod(pm, "getApplicationInfo",
                                "unknown", 0, callingUid);
                        pkg = (String) XposedHelpers.getObjectField(ai, "packageName");
                    } catch (Throwable t) {
                        // Silently ignore
                    }
                    NetLog.i("ConnectivityManager." + methodName + " called by uid="
                            + callingUid + " pkg=" + pkg);
                }
            });
        } catch (NoSuchMethodError | NoSuchMethodException e) {
            NetLog.w("Method " + methodName + " not found in " + className + ": " + e.getMessage());
        }
    }

    /**
     * Modify a NetworkRequest to strip NOT_VPN capability.
     * Uses reflection to mutate the internal requirements field.
     */
    private void modifyNetworkRequest(NetworkRequest req) {
        try {
            int requirements = XposedHelpers.getIntField(req, "requirements");
            int matchingCaps = XposedHelpers.getIntField(req, "matchingCapabilities");

            // NOT_VPN is bit 15
            int notVpnBit = 1 << CAP_NOT_VPN;

            if ((requirements & notVpnBit) != 0) {
                NetLog.i("Stripped NOT_VPN from requirements (was " + requirements + ")");
                requirements &= ~notVpnBit;
                XposedHelpers.setIntField(req, "requirements", requirements);
            }
            if ((matchingCaps & notVpnBit) != 0) {
                NetLog.i("Stripped NOT_VPN from matchingCapabilities (was " + matchingCaps + ")");
                matchingCaps &= ~notVpnBit;
                XposedHelpers.setIntField(req, "matchingCapabilities", matchingCaps);
            }
        } catch (Throwable t) {
            NetLog.e("Failed to modify NetworkRequest: " + t);
        }
    }

    private boolean classExists(String className, ClassLoader classLoader) {
        try {
            Class.forName(className, false, classLoader);
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }
}
