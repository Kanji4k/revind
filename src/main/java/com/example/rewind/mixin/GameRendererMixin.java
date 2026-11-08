package com.example.rewind.mixin;

import com.example.rewind.FrameGrabber;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {
    // End of GameRenderer.render: world + GUI are in the main render target.
    @Inject(method = "render", at = @At("TAIL"))
    private void rewind$frame(DeltaTracker dt, boolean tick, CallbackInfo ci) {
        FrameGrabber.onFrame();
    }
}
