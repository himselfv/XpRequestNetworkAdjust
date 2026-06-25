package xyz.fcampbell.xposed_template;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

public class Module implements IXposedHookLoadPackage {

    @Override
    public void handleLoadPackage(final LoadPackageParam lpparam) throws Throwable {
        // Skip fundamental core system hooks to keep device stability
        if ("android".equals(lpparam.packageName) || "com.android.systemui".equals(lpparam.packageName)) {
            return;
        }

        try {
            XposedHelpers.findAndHookMethod(
                "android.net.NetworkRequest$Builder", 
                lpparam.classLoader, 
                "addCapability", 
                int.class, 
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        int capability = (int) param.args[0];
                        
                        // 15 = NET_CAPABILITY_NOT_VPN
                        if (capability == 15) { 
                            XposedBridge.log("[" + lpparam.packageName + "] Stripped NOT_VPN capability request.");
                            param.setResult(param.thisObject); // Skip system execution safely
                        }
                    }
                }
            );
        } catch (Throwable t) {
            // Some apps may not have standard classloaders initialized yet
        }
    }
}
