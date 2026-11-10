/*
 * Author: Ctrl-Ling
 * Last Modified By: Ctrl-Ling
 * Last Modified At: 2026-11-09 22:05:00 +08:00
 *
 * Xposed / LSPosed API 桩（compileOnly，绝不进 apk）。
 * 签名严格照 api/src/de/robv/android/xposed/XC_MethodHook.java 抄：
 *   - MethodHookParam.method 是 Member（不是 Method），要拿返回类型得自己强转；
 *   - beforeHookedMethod/afterHookedMethod 是 protected，跨匿名类不能互调。
 */
package de.robv.android.xposed;

import java.lang.reflect.Member;

public abstract class XC_MethodHook {

    public XC_MethodHook() {
    }

    public XC_MethodHook(int priority) {
    }

    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
    }

    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
    }

    public static class MethodHookParam {
        /** The hooked method/constructor. 注意是 Member。*/
        public Member method;
        /** The {@code this} reference for an instance method, {@code null} for static methods. */
        public Object thisObject;
        /** Arguments to the method call. */
        public Object[] args;

        private Object result;
        private Throwable throwable;

        public MethodHookParam() {
        }

        public Throwable getThrowable() {
            return throwable;
        }

        public Object getResult() {
            return result;
        }

        public void setResult(Object result) {
            this.result = result;
            this.throwable = null;
        }

        public void setThrowable(Throwable throwable) {
            this.throwable = throwable;
            this.result = null;
        }
    }

    public static class Unhook {
        private final XC_MethodHook callback;

        public Unhook(XC_MethodHook callback) {
            this.callback = callback;
        }

        public XC_MethodHook getCallback() {
            return callback;
        }

        public void unhook() {
        }
    }
}
