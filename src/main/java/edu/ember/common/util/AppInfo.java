package edu.ember.common.util;

/** Shared Ember product identity for UI, logs, and documentation. @concept constants, encapsulation */
public final class AppInfo {
    public static final String NAME = "Ember";
    public static final String TAGLINE = "Say it. Spark it. Let it fade.";

    private AppInfo() {
        throw new AssertionError("No instances");
    }
}
