package io.github.nicochanyo.sonypods;

import android.util.Log;

import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

/**
 * LibXposed API 102 hook adapter and diagnostic logger.
 */
public final class XposedCompat {
    private static final String LOG_TAG = "SonyPodsColorOS";
    private static final int MAX_LOG_ENTRIES = 120;
    private static final ArrayDeque<String> LOG_RING = new ArrayDeque<>(MAX_LOG_ENTRIES);
    private static volatile XposedModule sModule;
    private static volatile boolean sVerboseDebug;

    private XposedCompat() {
    }

    public static void init(XposedModule module) {
        sModule = module;
    }

    public static XposedModule getModule() {
        return sModule;
    }

    public static void setVerboseDebug(boolean verbose) {
        sVerboseDebug = verbose;
    }

    public static boolean isVerboseDebug() {
        return sVerboseDebug;
    }

    public static void log(String text) {
        appendRing(text);
        XposedModule m = sModule;
        if (m != null) {
            try {
                m.log(Log.INFO, LOG_TAG, text);
            } catch (Throwable ignored) {
            }
        }
        Log.i(LOG_TAG, text);
    }

    public static void debug(String text) {
        if (!sVerboseDebug) {
            return;
        }
        log("[DBG] " + text);
    }

    private static synchronized void appendRing(String text) {
        if (text == null) {
            return;
        }
        String ts = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(new Date());
        if (LOG_RING.size() >= MAX_LOG_ENTRIES) {
            LOG_RING.removeFirst();
        }
        LOG_RING.addLast(ts + " " + text);
    }

    public static synchronized String dumpRecentLogs() {
        if (LOG_RING.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (String line : LOG_RING) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(line);
        }
        return sb.toString();
    }

    public static final class MethodHookParam {
        public Executable method;
        public Object thisObject;
        public Object[] args;

        private Object result;
        private Throwable throwable;
        boolean returnEarly;
        private Map<String, Object> extras;

        public Object getResult() {
            return result;
        }

        public void setResult(Object result) {
            this.result = result;
            this.throwable = null;
            this.returnEarly = true;
        }

        public Throwable getThrowable() {
            return throwable;
        }

        public boolean hasThrowable() {
            return throwable != null;
        }

        public void setThrowable(Throwable throwable) {
            this.throwable = throwable;
            this.result = null;
            this.returnEarly = true;
        }

        void setProceedResult(Object result) {
            this.result = result;
            this.throwable = null;
        }

        void setProceedThrowable(Throwable throwable) {
            this.throwable = throwable;
            this.result = null;
        }

        public synchronized Object getObjectExtra(String key) {
            return extras != null ? extras.get(key) : null;
        }

        public synchronized void setObjectExtra(String key, Object value) {
            if (extras == null) {
                extras = new HashMap<>();
            }
            extras.put(key, value);
        }
    }

    public static abstract class XC_MethodHook {
        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
        }

        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
        }
    }

    public static XposedInterface.HookHandle hookMethod(Executable hookMethod, final XC_MethodHook callback) {
        XposedModule module = sModule;
        if (module == null || hookMethod == null || callback == null) {
            return null;
        }
        hookMethod.setAccessible(true);
        return module.hook(hookMethod)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(new XposedInterface.Hooker() {
                    @Override
                    public Object intercept(XposedInterface.Chain chain) throws Throwable {
                        MethodHookParam param = new MethodHookParam();
                        param.method = chain.getExecutable();
                        param.thisObject = chain.getThisObject();
                        List<Object> argList = chain.getArgs();
                        param.args = (argList != null && !argList.isEmpty())
                                ? argList.toArray(new Object[0])
                                : new Object[0];

                        callback.beforeHookedMethod(param);
                        if (!param.returnEarly) {
                            try {
                                Object res = param.args.length > 0
                                        ? chain.proceed(param.args)
                                        : chain.proceed();
                                param.setProceedResult(res);
                            } catch (Throwable t) {
                                param.setProceedThrowable(t);
                            }
                        }
                        callback.afterHookedMethod(param);
                        if (param.throwable != null) {
                            throw param.throwable;
                        }
                        return param.result;
                    }
                });
    }

    public static Set<XposedInterface.HookHandle> hookAllMethods(
            Class<?> hookClass,
            String methodName,
            XC_MethodHook callback) {
        Set<XposedInterface.HookHandle> handles = new HashSet<>();
        if (hookClass == null || methodName == null || callback == null) {
            return handles;
        }
        for (Method m : hookClass.getDeclaredMethods()) {
            if (methodName.equals(m.getName()) && !Modifier.isAbstract(m.getModifiers())) {
                XposedInterface.HookHandle h = hookMethod(m, callback);
                if (h != null) {
                    handles.add(h);
                }
            }
        }
        return handles;
    }

    public static Set<XposedInterface.HookHandle> hookAllConstructors(
            Class<?> hookClass,
            XC_MethodHook callback) {
        Set<XposedInterface.HookHandle> handles = new HashSet<>();
        if (hookClass == null || callback == null) {
            return handles;
        }
        for (Constructor<?> c : hookClass.getDeclaredConstructors()) {
            XposedInterface.HookHandle h = hookMethod(c, callback);
            if (h != null) {
                handles.add(h);
            }
        }
        return handles;
    }

    public static Class<?> findClass(String className, ClassLoader classLoader) {
        try {
            return Class.forName(className, false, classLoader);
        } catch (ClassNotFoundException e) {
            throw new RuntimeException(e);
        }
    }

    public static Class<?> findClassIfExists(String className, ClassLoader classLoader) {
        try {
            return Class.forName(className, false, classLoader);
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static XposedInterface.HookHandle findAndHookMethod(
            String className,
            ClassLoader classLoader,
            String methodName,
            Object... parameterTypesAndCallback) {
        return findAndHookMethod(findClass(className, classLoader), methodName, parameterTypesAndCallback);
    }

    public static XposedInterface.HookHandle findAndHookMethod(
            Class<?> clazz,
            String methodName,
            Object... parameterTypesAndCallback) {
        if (parameterTypesAndCallback == null || parameterTypesAndCallback.length == 0) {
            throw new IllegalArgumentException("Missing callback");
        }
        Object last = parameterTypesAndCallback[parameterTypesAndCallback.length - 1];
        if (!(last instanceof XC_MethodHook)) {
            throw new IllegalArgumentException("Last argument must be XC_MethodHook");
        }
        XC_MethodHook callback = (XC_MethodHook) last;
        int paramLen = parameterTypesAndCallback.length - 1;
        Class<?>[] paramTypes = new Class<?>[paramLen];
        ClassLoader cl = clazz.getClassLoader();
        for (int i = 0; i < paramLen; i++) {
            Object p = parameterTypesAndCallback[i];
            if (p instanceof Class<?>) {
                paramTypes[i] = (Class<?>) p;
            } else if (p instanceof String) {
                paramTypes[i] = findClass((String) p, cl);
            } else {
                throw new IllegalArgumentException("Unsupported parameter type: " + p);
            }
        }
        Method target = findMethodBestMatch(clazz, methodName, paramTypes);
        if (target == null) {
            throw new RuntimeException(new NoSuchMethodException(clazz.getName() + "#" + methodName));
        }
        return hookMethod(target, callback);
    }

    private static Method findMethodBestMatch(Class<?> clazz, String methodName, Class<?>[] paramTypes) {
        Class<?> c = clazz;
        while (c != null) {
            try {
                Method m = c.getDeclaredMethod(methodName, paramTypes);
                if (!Modifier.isAbstract(m.getModifiers())) {
                    return m;
                }
            } catch (NoSuchMethodException ignored) {
            }
            c = c.getSuperclass();
        }
        return null;
    }
}
