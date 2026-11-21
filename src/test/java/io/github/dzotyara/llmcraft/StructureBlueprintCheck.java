package io.github.dzotyara.llmcraft;

import net.minecraft.util.math.BlockPos;

import java.util.Collections;

final class StructureBlueprintCheck {
    private static final String BASE = """
            {"version":1,"name":"Pavilion","operations":[
              {"op":"fill","from":[0,0,0],"to":[3,3,3],"block":"quartz_block"},
              {"op":"carve","from":[0,1,1],"to":[3,2,2]},
              {"op":"fill","from":[1,3,1],"to":[1,3,1],"block":"blue_concrete"}
            ]}
            """;
    private static final String BIG_FILL = """
            {"op":"fill","from":[0,0,0],"to":[23,23,23],"block":"stone"}
            """;
    private static final String CARVE = """
            {"op":"carve","from":[0,1,0],"to":[23,23,23]}
            """;

    public static void main(String[] args) {
        run();
    }

    static void run() {
        var blueprint = StructureBlueprint.parse(BASE);
        expect(blueprint.name().equals("Pavilion"), "Name survives parsing");
        expect(blueprint.blockIds().size() == 48, "Carving opens the middle");
        expect(!blueprint.blockIds().containsKey(new BlockPos(0, 1, 1)), "Open span");
        expect(blueprint.blockIds().get(new BlockPos(1, 3, 1)).equals("blue_concrete"), "Later fill replaces prior block");
        expect(blueprint.blockIds().equals(StructureBlueprint.parse(blueprint.json()).blockIds()), "Geometry round trip");
        try {
            blueprint.blockIds().clear();
            throw new AssertionError("Compiled geometry is mutable");
        } catch (UnsupportedOperationException expected) {
            // The same geometry is used by preview and placement.
        }

        var slab = StructureBlueprint.parse(blueprint(BIG_FILL + "," + CARVE));
        expect(slab.blockIds().size() == 24 * 24, "Occupied limit uses final geometry after carving");

        reject(BASE.replace("quartz_block", "command_block"));
        reject(BASE.replace("quartz_block", "minecraft:quartz_block"));
        reject(BASE.replace("quartz_block", "quartz_block[foo=bar]"));
        reject(BASE.replace("\"version\":1", "\"version\":2"));
        reject(BASE.replace("\"version\":1,", ""));
        reject(BASE.replace("\"version\":1", "\"version\":1,\"version\":1"));
        reject(BASE.replace("\"op\":\"fill\"", "\"op\":\"fill\",\"op\":\"fill\""));
        reject(BASE.replace("\"op\":\"fill\"", "\"script\":\"fill\""));
        reject(BASE.replace("\"op\":\"fill\"", "\"op\":\"prefab\""));
        reject(BASE.replace("\"op\":\"carve\"", "\"op\":\"carve\",\"block\":\"stone\""));
        reject(BASE.replace(",\"block\":\"quartz_block\"", ""));
        reject(BASE.replace("[3,3,3]", "[24,3,3]"));
        reject(BASE.replace("[3,3,3]", "[-1,3,3]"));
        reject(BASE.replace("[3,3,3]", "[3.0,3,3]"));
        reject(BASE.replace("[3,3,3]", "[3,3,3,3]"));
        reject(BASE.replace("[3,3,3]", "[\"3\",3,3]"));
        reject(BASE.replace("[0,0,0]", "[4,0,0]"));
        reject(BASE.replace("Pavilion", "Pa\nvilion"));
        reject(BASE.replace("Pavilion", "Pa\\'vilion"));
        reject(BASE + " {}");
        reject(BASE + " ".repeat(StructureBlueprint.MAX_JSON_LENGTH));
        reject(blueprint(BIG_FILL));
        reject(blueprint(CARVE));
        reject(blueprint(BIG_FILL + "," + BIG_FILL + "," + CARVE));
        reject(blueprint(String.join(",", Collections.nCopies(129, CARVE))));
    }

    private static String blueprint(String operations) {
        return "{\"version\":1,\"name\":\"Test\",\"operations\":[" + operations + "]}";
    }

    private static void expect(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void reject(String json) {
        try {
            StructureBlueprint.parse(json);
        } catch (IllegalArgumentException expected) {
            return;
        }
        throw new AssertionError("Expected parser to reject input.");
    }
}
