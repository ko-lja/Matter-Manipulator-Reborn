package lu.kolja.oreui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.sounds.SoundEvents;
import org.jetbrains.annotations.Nullable;

public final class OreInput {

    private final Runnable clickSound;
    private @Nullable Object hovered;
    private @Nullable Object pressed;

    public OreInput() {
        this(() -> Minecraft.getInstance().getSoundManager()
                .play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F)));
    }

    public OreInput(Runnable clickSound) {
        this.clickSound = clickSound;
    }

    public void hover(@Nullable Object id) {
        hovered = id;
    }

    public OreState state(Object id, boolean enabled) {
        if (!enabled) {
            return OreState.DISABLED;
        }
        if (pressed != null) {
            return pressed.equals(id) && pressed.equals(hovered) ? OreState.PRESSED : OreState.DEFAULT;
        }
        return id.equals(hovered) ? OreState.HOVER : OreState.DEFAULT;
    }

    public void press(@Nullable Object id) {
        hovered = id;
        pressed = id;
    }

    public boolean release(@Nullable Object id) {
        boolean clicked = pressed != null && pressed.equals(id);
        hovered = id;
        pressed = null;
        if (clicked) {
            clickSound.run();
        }
        return clicked;
    }
}
