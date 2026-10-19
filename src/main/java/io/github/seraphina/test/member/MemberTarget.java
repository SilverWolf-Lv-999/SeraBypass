package io.github.seraphina.test.member;

public class MemberTarget {
    public static String staticMarker = "initial";

    public int existingInt = 11;
    public long existingLong = 13L;

    public int baseValue() {
        return existingInt;
    }
}
