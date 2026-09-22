package com.alcyone.jarvis;

import java.util.ArrayList;
import java.util.List;

/**
 * Main Empirical Verification and Stress Harness for Milestone 2 (Perception & Caching).
 * Runs full stress suites against PerceptionCache, Tag Classification, Radius Clamping,
 * and Entity Payload Schemas.
 */
public class Milestone2EmpiricalHarness {

    public static void main(String[] args) {
        System.out.println("================================================================================");
        System.out.println("   MILESTONE 2 EMPIRICAL VERIFICATION & STRESS HARNESS");
        System.out.println("   Target: Surroundings Perception Engine & PerceptionCache");
        System.out.println("================================================================================");
        System.out.println();

        List<String> passedTests = new ArrayList<>();
        List<String> failedTests = new ArrayList<>();
        long totalStart = System.currentTimeMillis();

        // ---------------------------------------------------------------------
        // PART 1: PERCEPTION CACHE CONCURRENCY, TTL & DISPLACEMENT SUITE
        // ---------------------------------------------------------------------
        System.out.println(">>> [PART 1] Running PerceptionCache Stress & Invariant Tests...");

        runTest(PerceptionCacheStressTest.testConsecutiveCallsWithin500ms(), passedTests, failedTests);
        runTest(PerceptionCacheStressTest.testTtlExpiryAfter500ms(), passedTests, failedTests);
        runTest(PerceptionCacheStressTest.testDisplacementInvalidation(), passedTests, failedTests);
        runTest(PerceptionCacheStressTest.testMultiThreadedConcurrencyStress(), passedTests, failedTests);
        runTest(PerceptionCacheStressTest.testVirtualThreadBlast(), passedTests, failedTests);
        runTest(PerceptionCacheStressTest.testDefensiveDeepCopyIsolation(), passedTests, failedTests);
        runTest(PerceptionCacheStressTest.testBoundaryAndNullSafety(), passedTests, failedTests);

        System.out.println();

        // ---------------------------------------------------------------------
        // PART 2: TAG CLASSIFICATION, RADIUS CLAMPING & SCHEMA SUITE
        // ---------------------------------------------------------------------
        System.out.println(">>> [PART 2] Running Classification, Clamping & Schema Tests...");

        runTest(TagClassificationAndSchemaTest.testBlockCategoryEnumContract(), passedTests, failedTests);
        runTest(TagClassificationAndSchemaTest.testRadiusClampingInvariants(), passedTests, failedTests);
        runTest(TagClassificationAndSchemaTest.testPerceptionPayloadSchemaContract(), passedTests, failedTests);

        System.out.println();
        System.out.println("================================================================================");
        System.out.println("   MILESTONE 2 EMPIRICAL VERIFICATION SUMMARY");
        System.out.println("================================================================================");
        System.out.printf("Total Suites Run: %d | Passed: %d | Failed: %d | Total Duration: %d ms%n",
                (passedTests.size() + failedTests.size()), passedTests.size(), failedTests.size(),
                (System.currentTimeMillis() - totalStart));
        System.out.println();

        if (failedTests.isEmpty()) {
            System.out.println(">>> FINAL VERDICT: [APPROVE]");
            System.out.println(">>> All Milestone 2 perception caching, TTL, displacement, and schema invariants PASSED.");
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

    private static void runTest(PerceptionCacheStressTest.TestResult r, List<String> passed, List<String> failed) {
        printResult(r.testName, r.passed, r.details, r.durationMs, passed, failed);
    }

    private static void runTest(TagClassificationAndSchemaTest.TestResult r, List<String> passed, List<String> failed) {
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
