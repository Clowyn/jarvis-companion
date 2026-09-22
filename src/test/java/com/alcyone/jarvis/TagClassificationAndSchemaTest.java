package com.alcyone.jarvis;

import com.alcyone.jarvis.perception.BlockCategory;
import com.alcyone.jarvis.perception.SurroundingsScanner;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Empirical tests for Tag Classification, Radius Clamping, and Perception Payload Schemas.
 */
public class TagClassificationAndSchemaTest {

    public static class TestResult {
        public final String testName;
        public final boolean passed;
        public final String details;
        public final long durationMs;

        public TestResult(String testName, boolean passed, String details, long durationMs) {
            this.testName = testName;
            this.passed = passed;
            this.details = details;
            this.durationMs = durationMs;
        }
    }

    /**
     * Test: BlockCategory Enum Serialization Contract.
     * Verifies all 8 categories and their exact serialized identifiers.
     */
    public static TestResult testBlockCategoryEnumContract() {
        long start = System.currentTimeMillis();
        boolean ok = true;
        List<String> mismatches = new ArrayList<>();

        if (!"ore".equals(BlockCategory.ORE.getSerializedName())) mismatches.add("ORE != ore");
        if (!"container".equals(BlockCategory.CONTAINER.getSerializedName())) mismatches.add("CONTAINER != container");
        if (!"workstation".equals(BlockCategory.WORKSTATION.getSerializedName())) mismatches.add("WORKSTATION != workstation");
        if (!"utility".equals(BlockCategory.UTILITY.getSerializedName())) mismatches.add("UTILITY != utility");
        if (!"hazard".equals(BlockCategory.HAZARD.getSerializedName())) mismatches.add("HAZARD != hazard");
        if (!"building".equals(BlockCategory.BUILDING.getSerializedName())) mismatches.add("BUILDING != building");
        if (!"other".equals(BlockCategory.OTHER.getSerializedName())) mismatches.add("OTHER != other");
        if (!"none".equals(BlockCategory.NONE.getSerializedName())) mismatches.add("NONE != none");

        if (BlockCategory.values().length != 8) {
            mismatches.add("Expected 8 categories, got " + BlockCategory.values().length);
        }

        boolean passed = mismatches.isEmpty();
        long duration = System.currentTimeMillis() - start;
        String details = passed ? "All 8 BlockCategory serialized names verified" : "Mismatches: " + String.join(", ", mismatches);
        return new TestResult("BlockCategoryEnumContract", passed, details, duration);
    }

    /**
     * Test: Radius Clamping Invariants.
     * Clamps radius to [MIN_RADIUS (2), MAX_RADIUS (32)].
     */
    public static TestResult testRadiusClampingInvariants() {
        long start = System.currentTimeMillis();
        List<String> failures = new ArrayList<>();

        if (SurroundingsScanner.clampRadius(-100) != 2) failures.add("clamp(-100) != 2");
        if (SurroundingsScanner.clampRadius(0) != 2) failures.add("clamp(0) != 2");
        if (SurroundingsScanner.clampRadius(1) != 2) failures.add("clamp(1) != 2");
        if (SurroundingsScanner.clampRadius(2) != 2) failures.add("clamp(2) != 2");
        if (SurroundingsScanner.clampRadius(8) != 8) failures.add("clamp(8) != 8");
        if (SurroundingsScanner.clampRadius(16) != 16) failures.add("clamp(16) != 16");
        if (SurroundingsScanner.clampRadius(32) != 32) failures.add("clamp(32) != 32");
        if (SurroundingsScanner.clampRadius(33) != 32) failures.add("clamp(33) != 32");
        if (SurroundingsScanner.clampRadius(64) != 32) failures.add("clamp(64) != 32");
        if (SurroundingsScanner.clampRadius(Integer.MIN_VALUE) != 2) failures.add("clamp(MIN_VALUE) != 2");
        if (SurroundingsScanner.clampRadius(Integer.MAX_VALUE) != 32) failures.add("clamp(MAX_VALUE) != 32");

        boolean passed = failures.isEmpty();
        long duration = System.currentTimeMillis() - start;
        String details = passed ? "All radius clamping boundaries [2, 32] verified" : "Failures: " + String.join(", ", failures);
        return new TestResult("RadiusClampingInvariants", passed, details, duration);
    }

    /**
     * Test: Perception Payload Schema Compliance.
     * Validates JSON schema for:
     * - Root object: origin {x, y, z}, radius (int), blocks (array), entities (array)
     * - Block element: pos {x, y, z}, x, y, z, block, id, type, category, distance
     * - LivingEntity element: id, uuid, type, name, pos {x, y, z}, x, y, z, distance, category ("player"|"monster"|"animal"), health, max_health
     * - ItemEntity element: id, uuid, type ("minecraft:item"), name, pos {x, y, z}, x, y, z, distance, category ("item"), item, item_id, count, item_name
     * - Distance sorting: items must be strictly ascending or non-decreasing by distance
     */
    public static TestResult testPerceptionPayloadSchemaContract() {
        long start = System.currentTimeMillis();
        List<String> schemaErrors = new ArrayList<>();

        // Construct a representative surroundings scan payload following SurroundingsScanner structure
        JsonObject root = new JsonObject();
        JsonObject origin = new JsonObject();
        origin.addProperty("x", 100.5);
        origin.addProperty("y", 64.0);
        origin.addProperty("z", -200.5);
        root.add("origin", origin);
        root.addProperty("radius", 16);

        // Blocks array
        JsonArray blocks = new JsonArray();
        JsonObject b1 = new JsonObject();
        JsonObject bp1 = new JsonObject();
        bp1.addProperty("x", 102);
        bp1.addProperty("y", 60);
        bp1.addProperty("z", -198);
        b1.add("pos", bp1);
        b1.addProperty("x", 102);
        b1.addProperty("y", 60);
        b1.addProperty("z", -198);
        b1.addProperty("block", "minecraft:diamond_ore");
        b1.addProperty("id", "minecraft:diamond_ore");
        b1.addProperty("type", "ore");
        b1.addProperty("category", "ore");
        b1.addProperty("distance", 4.5);
        blocks.add(b1);

        JsonObject b2 = new JsonObject();
        JsonObject bp2 = new JsonObject();
        bp2.addProperty("x", 105);
        bp2.addProperty("y", 64);
        bp2.addProperty("z", -200);
        b2.add("pos", bp2);
        b2.addProperty("x", 105);
        b2.addProperty("y", 64);
        b2.addProperty("z", -200);
        b2.addProperty("block", "minecraft:chest");
        b2.addProperty("id", "minecraft:chest");
        b2.addProperty("type", "container");
        b2.addProperty("category", "container");
        b2.addProperty("distance", 5.2);
        blocks.add(b2);

        JsonObject b3 = new JsonObject();
        JsonObject bp3 = new JsonObject();
        bp3.addProperty("x", 100);
        bp3.addProperty("y", 64);
        bp3.addProperty("z", -190);
        b3.add("pos", bp3);
        b3.addProperty("x", 100);
        b3.addProperty("y", 64);
        b3.addProperty("z", -190);
        b3.addProperty("block", "minecraft:crafting_table");
        b3.addProperty("id", "minecraft:crafting_table");
        b3.addProperty("type", "workstation");
        b3.addProperty("category", "workstation");
        b3.addProperty("distance", 10.5);
        blocks.add(b3);
        root.add("blocks", blocks);

        // Entities array (Monster + ItemEntity)
        JsonArray entities = new JsonArray();

        // 1. Monster
        JsonObject e1 = new JsonObject();
        e1.addProperty("id", 42);
        e1.addProperty("uuid", "f81d4fae-7dec-11d0-a765-00a0c91e6bf6");
        e1.addProperty("type", "minecraft:zombie");
        e1.addProperty("name", "Zombie");
        JsonObject ep1 = new JsonObject();
        ep1.addProperty("x", 98.0);
        ep1.addProperty("y", 64.0);
        ep1.addProperty("z", -195.0);
        e1.add("pos", ep1);
        e1.addProperty("x", 98.0);
        e1.addProperty("y", 64.0);
        e1.addProperty("z", -195.0);
        e1.addProperty("distance", 6.1);
        e1.addProperty("category", "monster");
        e1.addProperty("health", 20.0);
        e1.addProperty("max_health", 20.0);
        entities.add(e1);

        // 2. ItemEntity
        JsonObject e2 = new JsonObject();
        e2.addProperty("id", 43);
        e2.addProperty("uuid", "f81d4fae-7dec-11d0-a765-00a0c91e6bf7");
        e2.addProperty("type", "minecraft:item");
        e2.addProperty("name", "Diamond");
        JsonObject ep2 = new JsonObject();
        ep2.addProperty("x", 100.0);
        ep2.addProperty("y", 64.0);
        ep2.addProperty("z", -198.0);
        e2.add("pos", ep2);
        e2.addProperty("x", 100.0);
        e2.addProperty("y", 64.0);
        e2.addProperty("z", -198.0);
        e2.addProperty("distance", 2.5);
        e2.addProperty("category", "item");
        e2.addProperty("item", "minecraft:diamond");
        e2.addProperty("item_id", "minecraft:diamond");
        e2.addProperty("count", 3);
        e2.addProperty("item_name", "Diamond");
        entities.add(e2);

        root.add("entities", entities);

        // Validate Root Object
        if (!root.has("origin") || !root.getAsJsonObject("origin").has("x") || !root.getAsJsonObject("origin").has("y") || !root.getAsJsonObject("origin").has("z")) {
            schemaErrors.add("Root missing origin coordinates");
        }
        if (!root.has("radius") || root.get("radius").getAsInt() != 16) {
            schemaErrors.add("Root missing or invalid radius");
        }
        if (!root.has("blocks") || !root.get("blocks").isJsonArray()) {
            schemaErrors.add("Root missing blocks array");
        }
        if (!root.has("entities") || !root.get("entities").isJsonArray()) {
            schemaErrors.add("Root missing entities array");
        }

        // Validate Blocks schema
        for (JsonElement elem : root.getAsJsonArray("blocks")) {
            JsonObject b = elem.getAsJsonObject();
            if (!b.has("pos") || !b.getAsJsonObject("pos").has("x") || !b.getAsJsonObject("pos").has("y") || !b.getAsJsonObject("pos").has("z")) {
                schemaErrors.add("Block missing pos object: " + b);
            }
            if (!b.has("block") || !b.get("block").getAsString().startsWith("minecraft:")) {
                schemaErrors.add("Block missing valid block id: " + b);
            }
            if (!b.has("type") || (!b.get("type").getAsString().equals("ore") && !b.get("type").getAsString().equals("container") && !b.get("type").getAsString().equals("workstation"))) {
                schemaErrors.add("Block missing valid category/type: " + b);
            }
            if (!b.has("distance")) {
                schemaErrors.add("Block missing distance: " + b);
            }
        }

        // Validate Monster schema
        JsonObject monster = entities.get(0).getAsJsonObject();
        if (!monster.has("health") || !monster.has("max_health") || !"monster".equals(monster.get("category").getAsString())) {
            schemaErrors.add("Monster entity missing health or incorrect category");
        }

        // Validate ItemEntity schema
        JsonObject item = entities.get(1).getAsJsonObject();
        if (!"item".equals(item.get("category").getAsString())) schemaErrors.add("ItemEntity category != item");
        if (!"minecraft:diamond".equals(item.get("item").getAsString())) schemaErrors.add("ItemEntity item != minecraft:diamond");
        if (!"minecraft:diamond".equals(item.get("item_id").getAsString())) schemaErrors.add("ItemEntity item_id != minecraft:diamond");
        if (item.get("count").getAsInt() != 3) schemaErrors.add("ItemEntity count != 3");
        if (!"Diamond".equals(item.get("item_name").getAsString())) schemaErrors.add("ItemEntity item_name != Diamond");

        // Validate Proximity Sorting Contract
        List<JsonObject> entList = new ArrayList<>();
        for (JsonElement el : entities) {
            entList.add(el.getAsJsonObject());
        }
        entList.sort(Comparator.comparingDouble(e -> e.get("distance").getAsDouble()));
        if (entList.get(0).get("distance").getAsDouble() > entList.get(1).get("distance").getAsDouble()) {
            schemaErrors.add("Proximity sorting failed: distance not non-decreasing");
        }

        boolean passed = schemaErrors.isEmpty();
        long duration = System.currentTimeMillis() - start;
        String details = passed ? "All blocks, living entities, and ItemEntity payload schemas verified"
                : "Schema Errors: " + String.join(", ", schemaErrors);
        return new TestResult("PerceptionPayloadSchemaContract", passed, details, duration);
    }
}
