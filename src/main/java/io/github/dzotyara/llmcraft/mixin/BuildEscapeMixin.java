package io.github.dzotyara.llmcraft.mixin;

import io.github.dzotyara.llmcraft.BuildPreview;
import io.github.dzotyara.llmcraft.StudioClient;
import net.minecraft.client.Keyboard;
import net.minecraft.client.MinecraftClient;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Keyboard.class)
public abstract class BuildEscapeMixin {
    @Inject(method = "onKey", at = @At("HEAD"), cancellable = true)
    private void llmcraft$cancelPlacement(long window, int key, int scancode, int action, int modifiers,
                                          CallbackInfo callback) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (window == client.getWindow().getHandle() && key == GLFW.GLFW_KEY_ESCAPE
                && action == GLFW.GLFW_PRESS && client.currentScreen == null
                && (BuildPreview.cancel() || StudioClient.cancelGeneration(client))) {
            callback.cancel();
        }
    }
}
