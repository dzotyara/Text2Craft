package io.github.dzotyara.llmcraft;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import net.minecraft.block.BlockState;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class StructureBlueprint {
    private static final int MAX_COORDINATE = 23;
    private static final int MAX_OPERATIONS = 128;
    private static final int MAX_GENERATED_OPERATIONS = 32;
    private static final int MAX_WORK = 32_768;
    private static final int MAX_BLOCKS = 4_096;
    static final int MAX_JSON_LENGTH = 16_384;
    private static final Gson GSON = new Gson();
    private static final List<String> MATERIALS = List.of(
            "stone", "cobblestone", "stone_bricks", "mossy_stone_bricks", "smooth_stone",
            "blackstone", "polished_blackstone_bricks", "deepslate_bricks", "obsidian",
            "quartz_block", "smooth_quartz", "prismarine", "prismarine_bricks", "dark_prismarine",
            "sandstone", "red_sandstone", "bricks", "mud_bricks", "terracotta",
            "oak_planks", "spruce_planks", "dark_oak_planks", "birch_planks", "cherry_planks",
            "oak_log", "spruce_log", "glass", "white_stained_glass", "blue_stained_glass",
            "purple_stained_glass", "white_concrete", "blue_concrete", "cyan_concrete",
            "black_concrete", "red_concrete", "gold_block", "copper_block", "amethyst_block",
            "glowstone", "sea_lantern", "ochre_froglight");

    static final String INSTRUCTIONS = """
            Design a Minecraft build from the player's prompt. Output only JSON with version 1,
            a short name, and an ordered operations array describing the blocks of the design.
            Each operation is {"op":"fill","from":[0,0,0],"to":[4,0,4],"block":"stone_bricks"}
            or {"op":"carve","from":[1,1,0],"to":[2,3,0]}.
            Coordinates are [x,y,z], y points up, all are integers from 0 through 23.
            From must be at or below to on each axis. Both corners are included.
            Fill adds or replaces blocks. Carve removes blocks from the design.
            For one block use the same coordinates for from and to. Start at ground level y=0
            and place the front at low z. Use boxes for walls, floors, roofs and other shapes.
            Capture the requested shape, colors, openings and distinctive features in the geometry.
            Group adjacent blocks of the same material into boxes. Aim for 4 to 20 operations for a
            simple request. Stop when the shape is complete. The hard limits are 32 operations,
            1 to 4096 final blocks, and 32768 total box volume including overlaps and carving.
            An example for "a white gate with a lantern" is:
            {"version":1,"name":"Lantern Gate","operations":[
              {"op":"fill","from":[0,0,0],"to":[4,4,0],"block":"quartz_block"},
              {"op":"carve","from":[1,0,0],"to":[3,2,0]},
              {"op":"fill","from":[2,4,0],"to":[2,4,0],"block":"sea_lantern"}
            ]}
            Design the player's requested shape and materials.
            Use only these block names:
            """ + String.join(", ", MATERIALS);

    private final String name;
    private final String json;
    private final Map<BlockPos, String> blockIds;
    private volatile Map<BlockPos, BlockState> resolvedBlocks;

    private StructureBlueprint(String name, List<Operation> operations, Map<BlockPos, String> blockIds) {
        this.name = name;
        this.json = GSON.toJson(Map.of("version", 1, "name", name, "operations", operations));
        this.blockIds = Map.copyOf(blockIds);
    }

    String name() {
        return name;
    }

    String json() {
        return json;
    }

    Map<BlockPos, BlockState> blocks() {
        var blocks = resolvedBlocks;
        if (blocks == null) {
            var states = new HashMap<String, BlockState>();
            var geometry = new HashMap<BlockPos, BlockState>();
            blockIds.forEach((pos, block) -> geometry.put(pos, states.computeIfAbsent(block,
                    id -> Registries.BLOCK.get(Identifier.ofVanilla(id)).getDefaultState())));
            blocks = Map.copyOf(geometry);
            resolvedBlocks = blocks;
        }
        return blocks;
    }

    Map<BlockPos, String> blockIds() {
        return blockIds;
    }

    static boolean isAllowed(BlockState state) {
        var block = state.getBlock();
        var id = Registries.BLOCK.getId(block);
        return id.getNamespace().equals("minecraft") && MATERIALS.contains(id.getPath())
                && state == block.getDefaultState();
    }

    private record Operation(String op, List<Integer> from, List<Integer> to, String block) {
    }

    static JsonObject schema() {
        var axis = Map.of("type", "integer", "minimum", 0, "maximum", MAX_COORDINATE);
        var position = Map.of("type", "array", "items", axis, "minItems", 3, "maxItems", 3);
        // Ollama's schema grammar follows property order. Match the examples in INSTRUCTIONS.
        var fillProperties = new LinkedHashMap<String, Object>();
        fillProperties.put("op", Map.of("enum", List.of("fill")));
        fillProperties.put("from", position);
        fillProperties.put("to", position);
        fillProperties.put("block", Map.of("type", "string", "enum", MATERIALS));
        var fill = Map.of("type", "object", "additionalProperties", false,
                "required", List.of("op", "from", "to", "block"),
                "properties", fillProperties);
        var carveProperties = new LinkedHashMap<>(fillProperties);
        carveProperties.put("op", Map.of("enum", List.of("carve")));
        carveProperties.remove("block");
        var carve = Map.of("type", "object", "additionalProperties", false,
                "required", List.of("op", "from", "to"),
                "properties", carveProperties);
        var blueprintProperties = new LinkedHashMap<String, Object>();
        blueprintProperties.put("version", Map.of("type", "integer", "enum", List.of(1)));
        blueprintProperties.put("name", Map.of("type", "string", "minLength", 1, "maxLength", 48));
        blueprintProperties.put("operations", Map.of("type", "array", "minItems", 1,
                "maxItems", MAX_GENERATED_OPERATIONS, "items", Map.of("anyOf", List.of(fill, carve))));
        return GSON.toJsonTree(Map.of("type", "object", "additionalProperties", false,
                "required", List.of("version", "name", "operations"),
                "properties", blueprintProperties)).getAsJsonObject();
    }

    static StructureBlueprint parse(String json) {
        if (json == null || json.length() > MAX_JSON_LENGTH) {
            throw new IllegalArgumentException("Blueprint exceeds the JSON size limit.");
        }
        validateStringSyntax(json);
        try (var reader = new JsonReader(new StringReader(json))) {
            var fields = new HashSet<String>();
            var operations = new ArrayList<Operation>();
            String name = null;
            reader.beginObject();
            while (reader.hasNext()) {
                switch (uniqueField(reader, fields)) {
                    case "version" -> {
                        if (integer(reader, 1) != 1) throw new IllegalArgumentException("Unsupported blueprint version.");
                    }
                    case "name" -> name = string(reader).strip();
                    case "operations" -> {
                        reader.beginArray();
                        while (reader.hasNext()) {
                            if (operations.size() == MAX_OPERATIONS) {
                                throw new IllegalArgumentException("Blueprint exceeds 128 operations.");
                            }
                            operations.add(readOperation(reader));
                        }
                        reader.endArray();
                    }
                    default -> throw new IllegalArgumentException("Unknown blueprint field.");
                }
            }
            reader.endObject();
            if (reader.peek() != JsonToken.END_DOCUMENT || fields.size() != 3) {
                throw new IllegalArgumentException("Blueprint requires version, name and operations.");
            }
            if (name == null || name.isEmpty() || name.length() > 48
                    || name.codePoints().anyMatch(Character::isISOControl)) {
                throw new IllegalArgumentException("Use a blueprint name of 1 to 48 characters.");
            }
            return new StructureBlueprint(name, operations, compile(operations));
        } catch (IOException | IllegalStateException e) {
            throw new IllegalArgumentException("Blueprint must be a single valid JSON object.", e);
        }
    }

    private static Operation readOperation(JsonReader reader) throws IOException {
        var fields = new HashSet<String>();
        String op = null;
        String block = null;
        List<Integer> from = null;
        List<Integer> to = null;
        reader.beginObject();
        while (reader.hasNext()) {
            switch (uniqueField(reader, fields)) {
                case "op" -> op = string(reader);
                case "block" -> block = string(reader);
                case "from" -> from = position(reader);
                case "to" -> to = position(reader);
                default -> throw new IllegalArgumentException("Unknown operation field.");
            }
        }
        reader.endObject();
        if (from == null || to == null || !("fill".equals(op) || "carve".equals(op))) {
            throw new IllegalArgumentException("Each operation needs fill or carve and both corners.");
        }
        if ("fill".equals(op) ? block == null || !MATERIALS.contains(block) : block != null) {
            throw new IllegalArgumentException("Fill requires an allowed block. Carve takes no block.");
        }
        for (int axis = 0; axis < 3; axis++) {
            if (from.get(axis) > to.get(axis)) {
                throw new IllegalArgumentException("Box corners must go from lower to higher coordinates.");
            }
        }
        return new Operation(op, from, to, block);
    }

    private static Map<BlockPos, String> compile(List<Operation> operations) {
        var blocks = new HashMap<BlockPos, String>();
        int work = 0;
        for (Operation operation : operations) {
            var from = operation.from();
            var to = operation.to();
            work += (to.get(0) - from.get(0) + 1) * (to.get(1) - from.get(1) + 1) * (to.get(2) - from.get(2) + 1);
            if (work > MAX_WORK) throw new IllegalArgumentException("Blueprint operations touch too many positions.");
            for (BlockPos pos : BlockPos.iterate(from.get(0), from.get(1), from.get(2),
                    to.get(0), to.get(1), to.get(2))) {
                if (operation.block() == null) blocks.remove(pos);
                else blocks.put(pos.toImmutable(), operation.block());
            }
        }
        if (blocks.isEmpty() || blocks.size() > MAX_BLOCKS) {
            throw new IllegalArgumentException("Blueprint must contain 1 to 4096 blocks after carving.");
        }
        return blocks;
    }

    private static List<Integer> position(JsonReader reader) throws IOException {
        reader.beginArray();
        var position = List.of(integer(reader, MAX_COORDINATE), integer(reader, MAX_COORDINATE),
                integer(reader, MAX_COORDINATE));
        reader.endArray();
        return position;
    }

    private static int integer(JsonReader reader, int maximum) throws IOException {
        if (reader.peek() == JsonToken.NUMBER) {
            String value = reader.nextString();
            if (value.matches("0|[1-9][0-9]?")) {
                int number = Integer.parseInt(value);
                if (number <= maximum) return number;
            }
        }
        throw new IllegalArgumentException("Expected an integer from 0 to " + maximum + ".");
    }

    private static String string(JsonReader reader) throws IOException {
        if (reader.peek() != JsonToken.STRING) throw new IllegalArgumentException("Expected a JSON string.");
        return reader.nextString();
    }

    private static String uniqueField(JsonReader reader, HashSet<String> fields) throws IOException {
        String key = reader.nextName();
        if (!fields.add(key)) throw new IllegalArgumentException("Duplicate field: " + key);
        return key;
    }

    private static void validateStringSyntax(String json) {
        // Gson 2.10's strict reader accepts raw controls and non-JSON escapes inside strings.
        boolean quoted = false;
        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '"') {
                quoted = !quoted;
            } else if (quoted && c == '\\') {
                if (++i == json.length() || "\"\\/bfnrtu".indexOf(json.charAt(i)) < 0) {
                    throw new IllegalArgumentException("Invalid JSON string escape.");
                }
            } else if (quoted && c < 0x20) {
                throw new IllegalArgumentException("Invalid control character inside JSON string.");
            }
        }
    }
}
