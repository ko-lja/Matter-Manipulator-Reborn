package lu.kolja.matter_manipulator.common.registry;

import lu.kolja.matter_manipulator.MatterManipulator;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModCreativeTabs {

    public static final DeferredRegister<CreativeModeTab> TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, MatterManipulator.MOD_ID);

    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> MAIN = TABS.register(
            "main",
            () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup.matter_manipulator"))
                    .icon(() -> new ItemStack(ModItems.MKI.get()))
                    .displayItems((parameters, output) -> {
                        output.accept(ModItems.PROTOTYPE.get());
                        output.accept(ModItems.MKI.get());
                        output.accept(ModItems.MKII.get());
                        output.accept(ModItems.MKIII.get());
                        output.accept(ModItems.BLUEPRINT.get());
                    })
                    .build()
    );

    private ModCreativeTabs() {}

    public static void register(IEventBus bus) {
        TABS.register(bus);
    }
}
