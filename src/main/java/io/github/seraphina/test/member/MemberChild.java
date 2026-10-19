package io.github.seraphina.test.member;

public final class MemberChild extends MemberTarget {
    @Override
    public int baseValue() {
        return super.baseValue() + 100;
    }
}
