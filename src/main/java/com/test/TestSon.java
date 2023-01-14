package com.test;

/**
 * 触发processor的测试类
 * @author rxf113
 */
public class TestSon extends TestParent {

    @Override
    public void m1(String s) {
        super.m1(s);
        System.out.println("Son invoked m1");
    }

    @Override
    public void m2(String s) {

    }

    @Override
    public void m3() {
        super.m3();
        System.out.println("Son invoked m3");
    }
}
