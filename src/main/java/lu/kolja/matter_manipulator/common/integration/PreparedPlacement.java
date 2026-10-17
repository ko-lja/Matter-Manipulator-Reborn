package lu.kolja.matter_manipulator.common.integration;

import lu.kolja.matter_manipulator.api.ItemRequirement;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record PreparedPlacement(
        CompoundTag payloads,
        Map<ResourceLocation, List<ItemStack>> consumedByIntegration,
        List<ItemRequirement> missingRequirements
) {
    public PreparedPlacement {
        payloads = payloads == null ? new CompoundTag() : payloads.copy();
        var consumedCopy = new LinkedHashMap<ResourceLocation, List<ItemStack>>();
        consumedByIntegration.forEach((id, stacks) -> consumedCopy.put(id, stacks.stream().map(ItemStack::copy).toList()));
        consumedByIntegration = Map.copyOf(consumedCopy);
        missingRequirements = missingRequirements == null ? List.of() : List.copyOf(missingRequirements);
    }
}
