package lu.kolja.matter_manipulator.common.registry;

import lu.kolja.matter_manipulator.MatterManipulator;
import lu.kolja.matter_manipulator.common.energy.StackEnergyStorage;
import lu.kolja.matter_manipulator.common.item.BlueprintItem;
import lu.kolja.matter_manipulator.common.item.ManipulatorItem;
import lu.kolja.matter_manipulator.common.manipulator.ManipulatorTier;
import net.minecraft.world.item.Item;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

public final class ModItems {

    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(MatterManipulator.MOD_ID);

    public static final Map<DeferredItem<Item>, String> ENGLISH_NAMES = new LinkedHashMap<>();

    public static final DeferredItem<Item> PROTOTYPE = item(
            "prototype_matter_manipulator", "Prototype Matter Manipulator",
            () -> new ManipulatorItem(ManipulatorTier.PROTOTYPE, new Item.Properties().stacksTo(1))
    );
    public static final DeferredItem<Item> MKI = item(
            "matter_manipulator_mki", "Matter Manipulator MKI",
            () -> new ManipulatorItem(ManipulatorTier.MKI, new Item.Properties().stacksTo(1))
    );
    public static final DeferredItem<Item> MKII = item(
            "matter_manipulator_mkii", "Matter Manipulator MKII",
            () -> new ManipulatorItem(ManipulatorTier.MKII, new Item.Properties().stacksTo(1))
    );
    public static final DeferredItem<Item> MKIII = item(
            "matter_manipulator_mkiii", "Matter Manipulator MKIII",
            () -> new ManipulatorItem(ManipulatorTier.MKIII, new Item.Properties().stacksTo(1))
    );
    public static final DeferredItem<Item> BLUEPRINT = item(
            "blueprint", "Blueprint",
            () -> new BlueprintItem(new Item.Properties().stacksTo(1))
    );

    private ModItems() {}

    private static DeferredItem<Item> item(String id, String englishName, Supplier<Item> factory) {
        var object = ITEMS.register(id, factory);
        ENGLISH_NAMES.put(object, englishName);
        return object;
    }

    public static void register(IEventBus bus) {
        ITEMS.register(bus);
    }

    public static void registerCapabilities(RegisterCapabilitiesEvent event) {
        event.registerItem(Capabilities.EnergyStorage.ITEM,
                (stack, context) -> new StackEnergyStorage(stack, ((ManipulatorItem) stack.getItem()).tier()),
                PROTOTYPE.get(), MKI.get(), MKII.get(), MKIII.get());
    }
}
