package edu.ember.test;

/** Zero-dependency test runner; phase tests are registered as features are implemented. @concept command-line args, methods */
public final class EmberTestSuite {
    private int passed;
    private int failed;

    private EmberTestSuite() {
    }

    public static void main(String[] args) {
        EmberTestSuite suite = new EmberTestSuite();
        suite.run();
    }

    private void run() {
        System.out.println("Ember tests: " + passed + " passed, " + failed + " failed.");
        if (failed != 0) {
            throw new AssertionError("Test suite failed");
        }
    }
}
