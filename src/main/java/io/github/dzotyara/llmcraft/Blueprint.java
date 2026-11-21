package io.github.dzotyara.llmcraft;

import com.google.gson.Gson;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;

import java.io.IOException;
import java.io.StringReader;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

record Blueprint(String name, Kind kind, String base, String action, int power) {
    private static final Gson GSON = new Gson();
    private static final Set<String> FIELDS = Set.of("name", "kind", "base", "action", "power");
    static final Set<String> ITEM_BASES = Set.of("axe", "sword", "rod");
    static final Set<String> ITEM_ACTIONS = Set.of("shock", "lift", "heal");
    static final Set<String> MATERIALS = Set.of("stone_bricks", "quartz_block", "prismarine", "blackstone");

    enum Kind { item, structure }

    static final Map<String, Blueprint> PRESETS = Map.of(
            "Lightning Hammer", new Blueprint("Lightning Hammer", Kind.item, "axe", "shock", 3),
            "Gravity Rod", new Blueprint("Gravity Rod", Kind.item, "rod", "lift", 3),
            "Floating Tower", new Blueprint("Floating Tower", Kind.structure, "prismarine", "tower", 4)
    );

    static final String INSTRUCTIONS = """
            Generate one JSON object for the Minecraft mod LLMCraft. Follow one of these examples:
            {"name":"Lightning Hammer","kind":"item","base":"axe","action":"shock","power":3}
            {"name":"Floating Tower","kind":"structure","base":"prismarine","action":"tower","power":4}
            Name: 1 to 48 characters. Power: integer 1 to 5.
            For item: base axe/sword/rod; action shock/lift/heal. Abilities trigger on right click.
            For structure: base stone_bricks/quartz_block/prismarine/blackstone; action tower/arch.
            Choose the nearest supported option. Return only JSON, no markdown or commentary.
            """;

    static Blueprint parse(String json) {
        if (json.length() > 2048) throw new IllegalArgumentException("Blueprint is too large.");
        try (var reader = new JsonReader(new StringReader(json))) {
            var object = new HashMap<String, String>();
            reader.beginObject();
            while (reader.hasNext()) {
                String key = reader.nextName();
                JsonToken expected = key.equals("power") ? JsonToken.NUMBER : JsonToken.STRING;
                if (reader.peek() != expected || object.putIfAbsent(key, reader.nextString()) != null) {
                    throw new IllegalArgumentException();
                }
            }
            reader.endObject();
            if (reader.peek() != JsonToken.END_DOCUMENT) throw new IllegalArgumentException();
            if (!object.keySet().equals(FIELDS)) throw new IllegalArgumentException();
            String name = object.get("name").strip();
            Kind kind = Kind.valueOf(object.get("kind"));
            String base = object.get("base");
            String action = object.get("action");
            String power = object.get("power");
            if (!power.matches("[1-5]")) {
                throw new IllegalArgumentException();
            }
            boolean supported = switch (kind) {
                case item -> ITEM_BASES.contains(base) && ITEM_ACTIONS.contains(action);
                case structure -> MATERIALS.contains(base) && Set.of("tower", "arch").contains(action);
            };
            if (!supported || name.isEmpty() || name.length() > 48
                    || name.codePoints().anyMatch(Character::isISOControl)) {
                throw new IllegalArgumentException();
            }
            return new Blueprint(name, kind, base, action, Integer.parseInt(power));
        } catch (IOException | IllegalStateException | IllegalArgumentException e) {
            throw new IllegalArgumentException("The model returned an invalid blueprint.", e);
        }
    }

    String json() {
        return GSON.toJson(this);
    }
}
