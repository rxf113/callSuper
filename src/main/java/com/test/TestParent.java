package com.test;

import com.rxf113.MustCallSuper;

/**
 * 触发processor的测试类
 *
 * @author rxf113
 */
public abstract class TestParent {

    @MustCallSuper
    public void m1(String s) {
        System.out.println("Must be invoked");
    }

    public abstract void m2(String s);

    @MustCallSuper
    public void m3() {
        System.out.println("Must be invoked");
    }
}
