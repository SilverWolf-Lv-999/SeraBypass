package io.github.seraphina.test;

public class TransTarget {

    public static int targetRemovedField = 0;
    private static int targetModifiedField = 1;

    public static void targetRemoved() {

    }

    public static void targetAdded() {
        try {
            TransTarget.class.getDeclaredMethod("targetNew");
            TransTarget.class.getDeclaredField("targetAddedField");
            TransTarget.class.getField("targetModifiedField");
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static void targetModify() {
        System.out.println("1");
    }
}
