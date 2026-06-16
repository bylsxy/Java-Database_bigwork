package com.imagemanager;

/**
 * Plain JVM entry point for the shaded course-delivery JAR.
 * <p>
 * Keeping the manifest main class separate from JavaFX Application avoids the
 * Java launcher treating the JAR as a modular JavaFX application that requires
 * external JavaFX modules on the command line.
 */
public final class Launcher {

    private Launcher() {
    }

    public static void main(String[] args) {
        App.main(args);
    }
}
