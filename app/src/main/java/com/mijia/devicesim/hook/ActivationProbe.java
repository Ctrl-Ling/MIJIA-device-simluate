/*
 * Author: Ctrl-Ling
 * Last Modified By: Ctrl-Ling
 * Last Modified At: 2026-11-10 02:28:13 +08:00
 */
package com.mijia.devicesim.hook;

/**
 * 激活探针。
 *
 * <p>大白话：模块装上没有 ≠ 钩子在跑。要证明后者，最可靠的办法是自己给自己挂一个钩子 ——
 * 一个正常情况下必然返回 false 的小函数，被框架改成 true，界面看到 true 就说明
 * LSPosed 已经启用本模块、且本模块在自己的作用域里。
 *
 * <p>为什么不直接用 {@code Class.forName("de.robv.android.xposed.XposedBridge")}：
 * 类存在只说明机器上装了框架，说明不了作用域和开关，界面上会骗人。
 *
 * <p>为什么单独一个类而不是放在 {@link XposedEntry} 里：这个类会被界面进程加载，
 * 而 XposedEntry 的方法签名里带 Xposed 类型（compileOnly，运行时不存在）。
 * 分开以后界面进程碰到的类里一个 Xposed 符号都没有，不会踩到 ART 的校验失败。
 */
public final class ActivationProbe {

    private ActivationProbe() {
    }

    /** 界面侧调用：true = 框架真的在给本模块下钩子。*/
    public static boolean isActive() {
        return probe(1);
    }

    /** 钩子目标：没人改它的时候恒为 false。*/
    static boolean probe(int seed) {
        return seed == 0;
    }
}
