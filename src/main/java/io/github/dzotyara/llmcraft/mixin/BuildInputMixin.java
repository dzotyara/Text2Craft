package io.github.dzotyara.llmcraft.mixin;

import io.github.dzotyara.llmcraft.BuildPreview;
import net.minecraft.client.MinecraftClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(MinecraftClient.class)
public abstract class BuildInputMixin {
    @Inject(method = "doAttack", at = @At("HEAD"), cancellable = true)
    private void llmcraft$placeStructure(CallbackInfoReturnable<Boolean> callback) {
        if (BuildPreview.interceptAttack((MinecraftClient) (Object) this)) callback.setReturnValue(false);
    }

    @Inject(method = "handleBlockBreaking", at = @At("HEAD"), cancellable = true)
    private void llmcraft$pauseBlockBreaking(boolean breaking, CallbackInfo callback) {
        if (breaking && BuildPreview.blockBreaking()) callback.cancel();
    }

    @Inject(method = "doItemUse", at = @At("HEAD"), cancellable = true)
    private void llmcraft$preventHammerThrow(CallbackInfo callback) {
        if (BuildPreview.blocksItemUse((MinecraftClient) (Object) this)) callback.cancel();
    }
}
