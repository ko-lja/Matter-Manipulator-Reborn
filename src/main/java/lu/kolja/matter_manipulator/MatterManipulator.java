package lu.kolja.matter_manipulator;

import lu.kolja.matter_manipulator.client.ClientMatterManipulator;
import lu.kolja.matter_manipulator.common.config.MMConfig;
import lu.kolja.matter_manipulator.common.integration.IntegrationDispatcher;
import lu.kolja.matter_manipulator.common.network.MMNetwork;
import lu.kolja.matter_manipulator.common.registry.ModCreativeTabs;
import lu.kolja.matter_manipulator.common.registry.ModItems;
import net.minecraft.resources.Identifier;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;
import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

@Mod(MatterManipulator.MOD_ID)
public class MatterManipulator {

    public static final String MOD_ID = "matter_manipulator";
    public static final Logger LOGGER = LogUtils.getLogger();

    public MatterManipulator(IEventBus modBus, ModContainer container) {
        ModItems.register(modBus);
        modBus.addListener(ModItems::registerCapabilities);
        ModCreativeTabs.register(modBus);
        modBus.addListener(IntegrationDispatcher::commonSetup);
        MMConfig.register(container);
        modBus.addListener(MMNetwork::register);

        if (FMLEnvironment.getDist() == Dist.CLIENT) {
            ClientMatterManipulator.init(modBus);
        }
    }

    public static Identifier id(String path) {
        return id(MOD_ID, path);
    }

    public static Identifier id(String namespace, String path) {
        return Identifier.fromNamespaceAndPath(namespace, path);
    }
}
