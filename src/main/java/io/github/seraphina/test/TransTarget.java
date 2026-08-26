package io.github.seraphina.test;

public class TransTarget {
    public static void targetRemoved() {

    }

    public static void targetAdded() {
        try {
            TransTarget.class.getDeclaredMethod("targetNew");
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static void targetModify() {
        System.out.println("1");
    }
}
