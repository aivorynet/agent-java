package com.aivory.test;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Test application for AIVory Java Agent.
 * Generates various exception types to test exception capture and local variable extraction.
 *
 * Build first:
 *   ./gradlew build
 *
 * Run with:
 *   java -javaagent:build/libs/aivory-monitor-agent-java-1.0.0-SNAPSHOT.jar \
 *        -Daivory.api.key=test-key-123 \
 *        -Daivory.backend.url=ws://localhost:19999/ws/monitor/agent \
 *        -Daivory.debug=true \
 *        -cp build/classes/java/test com.aivory.test.TestApp
 */
public class TestApp {

    // Instance fields - these should be captured as "local variables"
    private String instanceName = "TestAppInstance";
    private int instanceCounter = 42;
    private List<String> instanceList = Arrays.asList("field1", "field2", "field3");

    public static void main(String[] args) throws Exception {
        System.out.println("===========================================");
        System.out.println("AIVory Java Agent Test Application");
        System.out.println("===========================================");

        // Wait for agent to connect
        System.out.println("Waiting for agent to connect...");
        Thread.sleep(3000);
        System.out.println("Starting exception tests...\n");

        // Create an instance to test non-static method interception
        TestApp app = new TestApp();

        // Generate test exceptions
        for (int i = 0; i < 3; i++) {
            System.out.println("--- Test " + (i + 1) + " ---");
            try {
                app.triggerException(i);
            } catch (Exception e) {
                System.out.println("Caught: " + e.getClass().getSimpleName() + " - " + e.getMessage());
            }
            System.out.println();
            Thread.sleep(3000);
        }

        // Test deeper call stack
        System.out.println("--- Deep Stack Test ---");
        try {
            app.level1();
        } catch (Exception e) {
            System.out.println("Caught: " + e.getClass().getSimpleName() + " - " + e.getMessage());
        }

        System.out.println("\n===========================================");
        System.out.println("Test complete. Check database for exceptions.");
        System.out.println("===========================================");

        // Keep running briefly to allow final messages to send
        Thread.sleep(2000);
    }

    // Deep call stack to test full stack trace capture
    void level1() { level2(); }
    void level2() { level3(); }
    void level3() { level4(); }
    void level4() { level5(); }
    void level5() { level6(); }
    void level6() { level7(); }
    void level7() { level8(); }
    void level8() { level9(); }
    void level9() { level10(); }
    void level10() {
        String deepVar = "deep-level-10";
        int depth = 10;
        throw new RuntimeException("Deep exception at level " + depth + ", var=" + deepVar);
    }

    void triggerException(int iteration) {
        // Create some local variables to capture
        String testVar = "test-value-" + iteration;
        int count = iteration * 10;
        List<String> items = Arrays.asList("apple", "banana", "cherry");
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("iteration", iteration);
        metadata.put("timestamp", System.currentTimeMillis());

        UserContext user = new UserContext("user-" + iteration, "test@example.com");

        // Trigger different exception types
        switch (iteration) {
            case 0:
                // NullPointerException
                String nullStr = null;
                System.out.println("Triggering NullPointerException...");
                nullStr.length(); // NPE here
                break;

            case 1:
                // IllegalArgumentException
                System.out.println("Triggering IllegalArgumentException...");
                throw new IllegalArgumentException("Invalid argument: testVar=" + testVar);

            case 2:
                // ArrayIndexOutOfBoundsException
                int[] arr = new int[3];
                System.out.println("Triggering ArrayIndexOutOfBoundsException...");
                arr[10] = 1; // ArrayIndexOutOfBounds here
                break;

            default:
                throw new RuntimeException("Unknown iteration: " + iteration);
        }
    }

    // Helper class to test object capture
    static class UserContext {
        private final String userId;
        private final String email;
        private boolean active = true;

        public UserContext(String userId, String email) {
            this.userId = userId;
            this.email = email;
        }

        @Override
        public String toString() {
            return "UserContext{userId='" + userId + "', email='" + email + "', active=" + active + "}";
        }
    }
}
