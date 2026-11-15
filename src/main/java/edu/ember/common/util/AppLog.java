package edu.ember.common.util;

import java.util.logging.Logger;

/** Provides named JDK loggers for Ember modules. @concept static methods, logging */
public final class AppLog {
    private AppLog() {
        throw new AssertionError("No instances");
    }

    public static Logger logger(Class<?> owner) {
        return Logger.getLogger(AppInfo.NAME + "." + owner.getName());
    }
}
