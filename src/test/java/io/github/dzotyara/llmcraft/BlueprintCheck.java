package io.github.dzotyara.llmcraft;

import java.nio.file.Files;

public final class BlueprintCheck {
    public static void main(String[] args) throws Exception {
        StructureBlueprintCheck.run();
        OllamaRuntimeCheck.run();
        for (Blueprint preset : Blueprint.PRESETS.values()) {
            expect(preset.equals(Blueprint.parse(preset.json())), "Preset round trip: " + preset.name());
        }
        Blueprint hammer = Blueprint.PRESETS.get("Lightning Hammer");
        String json = hammer.json();
        reject(json.replace("\"power\":3", "\"power\":6"));
        reject(json.replace("\"power\":3", "\"power\":\"3\""));
        reject(json.replace("\"shock\"", "\"execute\""));
        reject(json.replace("\"axe\"", "\"prismarine\""));
        reject(json.replace("\"kind\"", "\"command\""));
        reject(json + " trailing text");
        reject(json.replace("\"power\":3", "\"power\":3,\"power\":4"));
        reject(json.replace("\"power\":3", "power:3"));
        reject("[]");

        var directory = Files.createTempDirectory("llmcraft-library-check");
        String id = null;
        String structureId = null;
        try {
            var library = new Library<>(directory, Blueprint::parse, Blueprint::name, Blueprint::json);
            id = library.save(hammer);
            expect(hammer.equals(library.entries().get(id)), "Saved blueprint is immediately available");
            var reloaded = new Library<>(directory, Blueprint::parse, Blueprint::name, Blueprint::json);
            expect(hammer.equals(reloaded.entries().get(id)), "Saved blueprint survives a reload");
            var structure = StructureBlueprint.parse("""
                    {"version":1,"name":"Gate","operations":[
                        {"op":"fill","from":[0,0,0],"to":[2,2,0],"block":"stone"},
                        {"op":"carve","from":[1,0,0],"to":[1,1,0]}
                    ]}
                    """);
            var structuresDirectory = directory.resolve("structures");
            var structures = new Library<>(structuresDirectory, StructureBlueprint::parse,
                    StructureBlueprint::name, StructureBlueprint::json);
            structureId = structures.save(structure);
            var structuresReloaded = new Library<>(structuresDirectory, StructureBlueprint::parse,
                    StructureBlueprint::name, StructureBlueprint::json);
            expect(structure.blockIds().equals(structuresReloaded.entries().get(structureId).blockIds()),
                    "Generated geometry survives a reload");
        } finally {
            if (id != null) Files.deleteIfExists(directory.resolve(id + ".json"));
            if (structureId != null) Files.deleteIfExists(directory.resolve("structures").resolve(structureId + ".json"));
            Files.deleteIfExists(directory.resolve("structures"));
            Files.deleteIfExists(directory);
        }
    }

    private static void reject(String json) {
        try {
            Blueprint.parse(json);
        } catch (IllegalArgumentException e) {
            return;
        }
        throw new AssertionError("Accepted invalid blueprint: " + json);
    }

    private static void expect(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
