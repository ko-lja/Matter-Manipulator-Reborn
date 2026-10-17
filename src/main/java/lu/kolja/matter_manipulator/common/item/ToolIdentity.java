package lu.kolja.matter_manipulator.common.item;

import lu.kolja.matter_manipulator.common.state.ToolState;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

public final class ToolIdentity {

    static final String TAG_ID = "ToolId";

    private ToolIdentity() {}

    public static @Nullable UUID get(ItemStack stack) {
        if (stack.isEmpty()) {
            return null;
        }
        var tag = ToolState.stackTag(stack);
        return tag.hasUUID(TAG_ID) ? tag.getUUID(TAG_ID) : null;
    }

    public static UUID bind(Player player, ItemStack tool) {
        var id = get(tool);
        if (id == null || carriedElsewhere(player, tool, id)) {
            id = UUID.randomUUID();
            var tag = ToolState.stackTag(tool);
            tag.putUUID(TAG_ID, id);
            ToolState.setStackTag(tool, tag);
        }
        return id;
    }

    public static ItemStack carried(Player player, UUID id) {
        var inventory = player.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            var stack = inventory.getItem(slot);
            if (id.equals(get(stack))) {
                return stack;
            }
        }
        var carried = player.containerMenu.getCarried();
        return id.equals(get(carried)) ? carried : ItemStack.EMPTY;
    }

    public static int copies(Player player, UUID id) {
        int count = id.equals(get(player.containerMenu.getCarried())) ? 1 : 0;
        var inventory = player.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            if (id.equals(get(inventory.getItem(slot)))) {
                count++;
            }
        }
        return count;
    }

    private static boolean carriedElsewhere(Player player, ItemStack tool, UUID id) {
        var carried = player.containerMenu.getCarried();
        if (carried != tool && id.equals(get(carried))) {
            return true;
        }
        var inventory = player.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            var stack = inventory.getItem(slot);
            if (stack != tool && id.equals(get(stack))) {
                return true;
            }
        }
        return false;
    }
}
