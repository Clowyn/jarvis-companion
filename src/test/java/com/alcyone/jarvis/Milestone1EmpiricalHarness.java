package com.alcyone.jarvis;

import java.util.ArrayList;
import java.util.List;

public class Milestone1EmpiricalHarness {

    public static void main(String[] args) {
        System.out.println("================================================================================");
        System.out.println("   MILESTONE 1 EMPIRICAL VERIFICATION & STRESS HARNESS");
        System.out.println("================================================================================");
        System.out.println();

        List<String> passedTests = new ArrayList<>();
        List<String> failedTests = new ArrayList<>();
        long totalStart = System.currentTimeMillis();

        // ---------------------------------------------------------------------
        // PART 1: CHATHISTORY CONCURRENCY & MEMORY CAPPING STRESS SUITE
        // ---------------------------------------------------------------------
        System.out.println(">>> [PART 1] Running ChatHistory Concurrency & Invariant Stress Tests...");

        runTest(ChatHistoryStressTest.testMultiThreadedRapidWrites(), passedTests, failedTests);
        runTest(ChatHistoryStressTest.testVirtualThreadBlast(), passedTests, failedTests);
        runTest(ChatHistoryStressTest.testConcurrentReadersAndWriters(), passedTests, failedTests);
        runTest(ChatHistoryStressTest.testDynamicBufferSampling(), passedTests, failedTests);
        runTest(ChatHistoryStressTest.testIdMonotonicityAndOrdering(), passedTests, failedTests);
        runTest(ChatHistoryStressTest.testBoundaryAndEdgeCases(), passedTests, failedTests);

        System.out.println();

        // ---------------------------------------------------------------------
        // PART 2: THREADHELPER TIMEOUT, COMPLETION & CONCURRENCY INVARIANTS
        // ---------------------------------------------------------------------
        System.out.println(">>> [PART 2] Running ThreadHelper Invariant & Contract Tests...");

        runTest(ThreadHelperContractTest.testDirectNullServerGuards(), passedTests, failedTests);
        runTest(ThreadHelperContractTest.testMainThreadExecutionAndCompletion(), passedTests, failedTests);
        runTest(ThreadHelperContractTest.testTimeoutBehaviorUnderMainThreadDelay(), passedTests, failedTests);
        runTest(ThreadHelperContractTest.testExceptionUnwrapping(), passedTests, failedTests);
        runTest(ThreadHelperContractTest.testVirtualThreadHighConcurrency(), passedTests, failedTests);

        System.out.println();
        System.out.println("================================================================================");
        System.out.println("   EMPIRICAL VERIFICATION SUMMARY");
        System.out.println("================================================================================");
        System.out.printf("Total Suites Run: %d | Passed: %d | Failed: %d | Total Duration: %d ms%n",
                (passedTests.size() + failedTests.size()), passedTests.size(), failedTests.size(),
                (System.currentTimeMillis() - totalStart));
        System.out.println();

        if (failedTests.isEmpty()) {
            System.out.println(">>> FINAL VERDICT: [APPROVE]");
            System.out.println(">>> All Milestone 1 concurrency, memory capping, and thread invariants PASSED.");
            System.exit(0);
        } else {
            System.err.println(">>> FINAL VERDICT: [REJECT]");
            System.err.println(">>> Failed Tests:");
            for (String f : failedTests) {
                System.err.println("    - " + f);
            }
            System.exit(1);
        }
    }

    private static void runTest(ChatHistoryStressTest.TestResult r, List<String> passed, List<String> failed) {
        printResult(r.testName, r.passed, r.details, r.durationMs, passed, failed);
    }

    private static void runTest(ThreadHelperContractTest.TestResult r, List<String> passed, List<String> failed) {
        printResult(r.testName, r.passed, r.details, r.durationMs, passed, failed);
    }

    private static void printResult(String name, boolean ok, String details, long durationMs, List<String> passed, List<String> failed) {
        if (ok) {
            passed.add(name);
            System.out.printf("  [PASS] %-35s (%4d ms)%n", name, durationMs);
            System.out.println("         Details: " + details);
        } else {
            failed.add(name);
            System.err.printf("  [FAIL] %-35s (%4d ms)%n", name, durationMs);
            System.err.println("         Details: " + details);
        }
    }
}
