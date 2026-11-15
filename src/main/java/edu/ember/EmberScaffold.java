package edu.ember;

import edu.ember.common.util.AppInfo;

/** Runs the Phase 0 scaffold banner until the server and client entry points are added. @concept packages, command-line entry point */
public final class EmberScaffold {
    private EmberScaffold() {
        throw new AssertionError("No instances");
    }

    public static void main(String[] args) {
        System.out.println(AppInfo.NAME + " | " + AppInfo.TAGLINE);
        System.out.println("Phase 0 scaffold is ready.");
    }
}
