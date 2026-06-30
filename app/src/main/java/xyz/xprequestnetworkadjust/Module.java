package xyz.xprequestnetworkadjust;

import android.net.NetworkRequest;
import android.net.NetworkCapabilities;

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
    	try {
	        // ---- App-level hooks: intercept NetworkRequest.Builder in target apps ----
	        if (!"android".equals(lpparam.packageName)) {
	            hookNetworkRequestBuilder(lpparam);
	            hookNetworkRequest(lpparam);
	        }

	        // ---- System-level hooks: intercept ConnectivityService in system server ----
	        if ("android".equals(lpparam.packageName)) {
	            if (!hookConnectivityService(lpparam))
	            	NetLog.w("No ConnectivityService to hook in system_server!");
	            if (!hookNetworkRequest(lpparam))
	            	NetLog.w("No NetworkRequest to hook in system_server!");
	            if (!hookNetworkAgentInfo(lpparam))
	            	NetLog.w("No NetworkAgentInfo to hook in system_server!");
	        }
        } catch (Throwable t) {
            NetLog.e("Uncaught error in handleLoadPackage in " + lpparam.packageName + ": " + t);
        }
    }



    private boolean classExists(String className, ClassLoader classLoader) {
		/* Extra debug
        try {
	        Class<?> networkRequestClass = XposedHelpers.findClass(
	            className,
	            classLoader
	        );
	        NetLog.w("Reflected " + className + " via XposedHelpers.");
	    } catch (Exception e) {
	        NetLog.w("Failed to reflect " + className + " via XposedHelpers: " + e.getMessage());
	    }
	    */
        try {
            Class.forName(className, false, classLoader);
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

	//Wraps findAndHookMethod with try-catch for error logging.
    private void tryFindAndHookMethod(String className, ClassLoader classLoader,
    	String methodName, Object... paramTypesAndCallback) {
        try {
            XposedHelpers.findAndHookMethod(className, classLoader, methodName,
            	paramTypesAndCallback);
        } catch (Throwable e) {
            NetLog.w("Method " + methodName + " not found in " + className + ": " + e.getMessage());
        }
    }



    /**
     * Hook NetworkRequest.Builder methods in app processes.
     * Strips NOT_VPN capability before it gets set on the request.
     */
    private boolean hookNetworkRequestBuilder(LoadPackageParam lpparam) {
        final String cls = "android.net.NetworkRequest$Builder";
        if (!classExists(cls, lpparam.classLoader)) return false; //No NetworkRequest.Builder class used in this package.

        // Hook addCapability — strip NOT_VPN when explicitly requested
        tryFindAndHookMethod(cls, lpparam.classLoader, "addCapability",
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
        tryFindAndHookMethod(cls, lpparam.classLoader, "removeCapability",
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
        tryFindAndHookMethod(cls, lpparam.classLoader, "addTransportType",
                int.class, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                int transport = (int) param.args[0];
                NetLog.d("[" + lpparam.packageName + "] addTransportType(" + transport + ")");
            }
        });

        // Hook build() — dump the final NetworkRequest object
        tryFindAndHookMethod(cls, lpparam.classLoader, "build",
                NetworkRequest.class, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                // Log the final request with its current state
                Object builder = param.thisObject;
                int reqs = XposedHelpers.getIntField(builder, "requirements");
                int matching = XposedHelpers.getIntField(builder, "matchingCapabilities");
                int trans = XposedHelpers.getIntField(builder, "transports");
                String caps = NetLog.bitmaskToString(reqs);
                NetLog.i("[" + lpparam.packageName + "] NetworkRequest.build() -> caps="
                        + caps);
            }
        });

		return true;
    }

	//Hooks the static final NetworkRequest constructor.
	//Server-side, ConnectivityService also reforms NetworkRequest out of its parts
	//and it also has to be adjusted in certain cases.
    private boolean hookNetworkRequest(LoadPackageParam lpparam) {
        final String cls = "android.net.NetworkRequest";
        if (!classExists(cls, lpparam.classLoader)) return false; //No NetworkRequest class used in this package.
        //NetLog.w("Hooking NetworkRequest...");

        try {
			XposedHelpers.findAndHookConstructor(
			    cls, lpparam.classLoader,
			    NetworkCapabilities.class,
			    int.class, //legacyType
			    int.class, //networkRequestId
			    "android.net.NetworkRequest$Type",
			    new XC_MethodHook() {
			        @Override
			        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
		                NetLog.i("NetworkRequest constructor intercepted!");
			        	modifyNetworkCapsForRequest(param.args[0]);
			        }
			    }
			);
        } catch (Throwable e) {
            NetLog.w("Constructor not found in " + cls + ": " + e.getMessage());
        }

		return true;
    }


    /**
     * Hook ConnectivityService in the system server process.
     * Intercepts requestNetwork() and registerNetworkCallback() calls, stripping NOT_VPN from any NetworkRequest.
     */
    private boolean hookConnectivityService(LoadPackageParam lpparam) throws Throwable {
        String cmAltClass = "com.android.server.ConnectivityService";
        if (!classExists(cmAltClass, lpparam.classLoader)) return false;

        tryFindAndHookMethod(cmAltClass, lpparam.classLoader, "requestNetwork",
        		NetworkCapabilities.class,
    			"android.os.Messenger",
    			int.class,
    			"android.os.IBinder",
    			int.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    	NetLog.i("ConnectivityService.requestNetwork intercepted");
                    	modifyNetworkCapsForRequest(param.args[0]);
                    }
                });

        tryFindAndHookMethod(cmAltClass, lpparam.classLoader, "listenForNetwork",
        		NetworkCapabilities.class,
    			"android.os.Messenger",
    			"android.os.IBinder",
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    	NetLog.i("ConnectivityService.listenForNetwork intercepted");
                        modifyNetworkCapsForRequest(param.args[0]);
                    }
                });

		//VPN connections for some reason often lack INTERNET. Hook and add that capability
        tryFindAndHookMethod(cmAltClass, lpparam.classLoader, "handleRegisterNetworkAgent",
			    "com.android.server.connectivity.NetworkAgentInfo",
				"android.net.INetworkMonitor",
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    	NetLog.i("ConnectivityService.handleRegisterNetworkAgent intercepted");
                    	modifyNetworkCapsForNetworkAgentInfo(param.args[0]);
                    }
                });

        tryFindAndHookMethod(cmAltClass, lpparam.classLoader, "updateCapabilities",
			    int.class, // network score or similar arg depending on version
			    "com.android.server.connectivity.NetworkAgentInfo", // The target network info object
			    "android.net.NetworkCapabilities", // The incoming updated capabilities
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    	NetLog.i("ConnectivityService.updateCapabilities intercepted");
                    	modifyNetworkCapsForNetwork((NetworkCapabilities)(param.args[2]));
                    }
                });

		return true;
    }


	//Hook NetworkAgentInfo to add/remove certain params from the network descriptions
    private boolean hookNetworkAgentInfo(LoadPackageParam lpparam) {
        final String cls = "com.android.server.connectivity.NetworkAgentInfo";
        if (!classExists(cls, lpparam.classLoader)) return false; //No NetworkRequest class used in this package.
        //NetLog.w("Hooking NetworkRequest...");

        try {
			Class<?> naiClass = XposedHelpers.findClass(
			    cls, 
			    lpparam.classLoader
			);
			XposedBridge.hookAllConstructors(
				naiClass,
			    new XC_MethodHook() {
			        @Override
			        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
		                NetLog.i("NetworkAgentInfo constructor intercepted!");
		                modifyNetworkCapsForNetworkAgentInfo(param.thisObject);
			        }
			    }
			);
        } catch (Throwable e) {
            NetLog.w("Constructor not found in " + cls + ": " + e.getMessage());
        }

		tryFindAndHookMethod(cls, lpparam.classLoader, "getCurrentScore",
			boolean.class,
			new XC_MethodHook() {
			    @Override
			    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
			    	NetLog.i("NetworkAgentInfo.getCurrentScore intercepted!");
/*
			        final int SCORE_OVERRIDE = 1000; // 100 beats Wi-Fi (60) and Cellular (50)
			        // Retrieve the capabilities from this specific instance
			        NetworkCapabilities nc = (NetworkCapabilities) XposedHelpers.getObjectField(
			            param.thisObject,
			            "networkCapabilities"
			        );
			        // If this is our patched VPN, force it to win the score match
			        if (nc != null && nc.hasTransport(NetworkCapabilities.TRANSPORT_VPN) && ((int)param.getResult() < SCORE_OVERRIDE)) {
			            param.setResult(SCORE_OVERRIDE);
			            NetLog.i("Patched NetworkAgentInfo.getCurrentScore := " + SCORE_OVERRIDE + ".");
			        }
*/
			    }
		});

		return true;
    }


    private void modifyNetworkCapsForRequest(Object req) {
		//When NetworkCapabilities is null in requestNetwork, it assigns TRACK_DEFAULT type and default caps instead
		//and we'll have to rely on NetworkRequest() constructor hooking to change those a bit downstream.
        if (req == null) {
        	NetLog.w("NetworkCapabilities is null");
        	return;
        }
        try {
        	long currentMask = XposedHelpers.getLongField(req, "mNetworkCapabilities");
        	long modifiedMask = currentMask & ~(1L << NetworkCapabilities.NET_CAPABILITY_NOT_VPN);
        	XposedHelpers.setLongField(req, "mNetworkCapabilities", modifiedMask);
        	NetLog.i("Stripped NOT_VPN from capabilities (was " + currentMask + ", now " + modifiedMask + ")");
        } catch (Throwable t) {
            NetLog.e("Failed to modify NetworkCapabilities: " + t);
        }
    }


    private void modifyNetworkCapsForNetworkAgentInfo(Object nai) {
        if (nai == null) {
        	NetLog.w("modifyNetworkCapsForNetworkAgentInfo: NetworkAgentInfo is null");
        	return;
        }
        NetworkCapabilities nc = (NetworkCapabilities) XposedHelpers.getObjectField(nai, "networkCapabilities");
		modifyNetworkCapsForNetwork(nc);
		
/*
		//Set explicitlySelected
		if (nc != null && nc.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
			Object networkMisc = XposedHelpers.getObjectField(nai, "networkMisc");
    		XposedHelpers.setBooleanField(networkMisc, "explicitlySelected", true);
			NetLog.i("Patched networkMisc.explicitlySelected := true.");
    	}
*/

    }

    private void modifyNetworkCapsForNetwork(NetworkCapabilities nc) {
        if (nc == null) {
        	NetLog.w("modifyNetworkCapsForNetwork: NetworkCapabilities is null");
        	return;
        }
        try {
            // Check if this network agent is explicitly marked as a VPN transport
            if (nc.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
	        	long currentMask = XposedHelpers.getLongField(nc, "mNetworkCapabilities");
	        	long modifiedMask = currentMask
	        		| (1L << NetworkCapabilities.NET_CAPABILITY_INTERNET)
	        		| (1L << NetworkCapabilities.NET_CAPABILITY_VALIDATED)
	        		;
	        	XposedHelpers.setLongField(nc, "mNetworkCapabilities", modifiedMask);
                NetLog.i("Patched VPN capabilities: += INTERNET, VALIDATED. (was " + currentMask + ", now " + modifiedMask + ")");
            }
        } catch (Throwable t) {
            NetLog.e("Failed to modify NetworkCapabilities: " + t);
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



	//Dynamic lookup with reflection
	//Use this to discover the exact param schemes in your OS version. Normally not called.
	private void signatureLookup(LoadPackageParam lpparam) throws Throwable {
	    try {
	        Class<?> connectivityServiceClass = XposedHelpers.findClass(
	            "com.android.server.ConnectivityService",
	            lpparam.classLoader
	        );
	        
	        // Loop through all declared methods
	        for (java.lang.reflect.Method method : connectivityServiceClass.getDeclaredMethods()) {
	            if (method.getName().equals("requestNetwork") || method.getName().equals("listenForNetwork")) {
	                
	                // Build a readable parameter list
	                StringBuilder params = new StringBuilder();
	                for (Class<?> paramType : method.getParameterTypes()) {
	                    params.append(paramType.getName()).append(", ");
	                }
	                
	                XposedBridge.log("Hook Target Found -> " + method.getName() + "(" + params.toString() + ")");
	            }
	        }
	    } catch (Exception e) {
	        XposedBridge.log("Failed to reflect ConnectivityService: " + e.getMessage());
	    }
	}
}
