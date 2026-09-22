package com.alcyone.jarvis;

import com.alcyone.jarvis.gui.ItemStackHandlerContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.items.ItemStackHandler;

public class ItemStackHandlerContainerTest {

    public static class TestResult {
        public final String testName;
        public final boolean passed;
        public final String details;

        public TestResult(String testName, boolean passed, String details) {
            this.testName = testName;
            this.passed = passed;
            this.details = details;
        }
    }

    public static TestResult testContainerDelegation() {
        try {
            ItemStackHandler handler = new ItemStackHandler(27);
            ItemStackHandlerContainer container = new ItemStackHandlerContainer(handler, null);

            if (container.getContainerSize() != 27) {
                return new TestResult("testContainerDelegation", false, "Expected size 27 but got " + container.getContainerSize());
            }
            if (!container.isEmpty()) {
                return new TestResult("testContainerDelegation", false, "Expected container to be empty");
            }

            ItemStack stack = new ItemStack(Items.DIAMOND, 5);
            container.setItem(0, stack);

            if (container.isEmpty()) {
                return new TestResult("testContainerDelegation", false, "Expected container not to be empty");
            }
            if (container.getItem(0).getCount() != 5) {
                return new TestResult("testContainerDelegation", false, "Expected count 5 in container slot 0");
            }
            if (handler.getStackInSlot(0).getCount() != 5) {
                return new TestResult("testContainerDelegation", false, "Expected count 5 in handler slot 0");
            }

            ItemStack removed = container.removeItem(0, 2);
            if (removed.getCount() != 2) {
                return new TestResult("testContainerDelegation", false, "Expected removed count 2");
            }
            if (container.getItem(0).getCount() != 3) {
                return new TestResult("testContainerDelegation", false, "Expected remaining count 3");
            }
            if (handler.getStackInSlot(0).getCount() != 3) {
                return new TestResult("testContainerDelegation", false, "Expected remaining count 3 in handler");
            }

            container.clearContent();
            if (!container.isEmpty() || !handler.getStackInSlot(0).isEmpty()) {
                return new TestResult("testContainerDelegation", false, "Expected empty container after clear");
            }

            return new TestResult("testContainerDelegation", true, "Container perfectly delegates to ItemStackHandler");
        } catch (Exception e) {
            return new TestResult("testContainerDelegation", false, "Exception: " + e.getMessage());
        }
    }

    public static void main(String[] args) {
        TestResult res = testContainerDelegation();
        System.out.println("Result: " + res.testName + " passed=" + res.passed + " - " + res.details);
        if (!res.passed) {
            System.exit(1);
        }
    }
}
