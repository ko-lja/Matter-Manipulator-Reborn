package lu.kolja.matter_manipulator.common.build;

import lu.kolja.matter_manipulator.common.build.BlockCost.Companion;
import lu.kolja.matter_manipulator.common.build.BlockCost.Reason;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.SolidBucketItem;
import net.minecraft.world.level.block.AbstractCauldronBlock;
import net.minecraft.world.level.block.AttachedStemBlock;
import net.minecraft.world.level.block.BeehiveBlock;
import net.minecraft.world.level.block.CactusBlock;
import net.minecraft.world.level.block.CaveVines;
import net.minecraft.world.level.block.CocoaBlock;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.NetherWartBlock;
import net.minecraft.world.level.block.PitcherCropBlock;
import net.minecraft.world.level.block.SaplingBlock;
import net.minecraft.world.level.block.SnifferEggBlock;
import net.minecraft.world.level.block.StemBlock;
import net.minecraft.world.level.block.SugarCaneBlock;
import net.minecraft.world.level.block.SweetBerryBushBlock;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.CauldronBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.BubbleColumnBlock;
import net.minecraft.world.level.block.CandleBlock;
import net.minecraft.world.level.block.CandleCakeBlock;
import net.minecraft.world.level.block.ComposterBlock;
import net.minecraft.world.level.block.EndGatewayBlock;
import net.minecraft.world.level.block.EndPortalBlock;
import net.minecraft.world.level.block.FlowerPotBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.MultifaceBlock;
import net.minecraft.world.level.block.NetherPortalBlock;
import net.minecraft.world.level.block.PinkPetalsBlock;
import net.minecraft.world.level.block.RespawnAnchorBlock;
import net.minecraft.world.level.block.SeaPickleBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.TallSeagrassBlock;
import net.minecraft.world.level.block.TurtleEggBlock;
import net.minecraft.world.level.block.VineBlock;
import net.minecraft.world.level.block.piston.MovingPistonBlock;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.piston.PistonHeadBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.PistonType;
import net.minecraft.world.level.block.state.properties.SlabType;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

final class BlockCostRules {

    private BlockCostRules() {}

    static BlockCost resolve(BlockState state) {
        if (state.isAir()) {
            return BlockCost.unsupported(Reason.AIR);
        }
        var denied = denial(state);
        if (denied != null) {
            return BlockCost.unsupported(denied);
        }
        var companion = companion(state);
        if (companion != null) {
            return companion;
        }
        var block = state.getBlock();
        if (block instanceof FlowerPotBlock pot && pot.getPotted() != Blocks.AIR) {
            return itemsOf(pot.getEmptyPot().asItem(), 1, pot.getPotted().asItem(), 1);
        }
        if (block instanceof CandleCakeBlock) {
            var candle = Derived.CANDLE_BY_CAKE.get(block);
            return candle == null ? BlockCost.unsupported(Reason.NO_ITEM) : itemsOf(Items.CAKE, 1, candle.asItem(), 1);
        }
        var item = Derived.ITEM_BY_BLOCK.getOrDefault(block, block.asItem());
        if (item == Items.AIR) {
            return BlockCost.unsupported(Reason.NO_ITEM);
        }
        if (item instanceof SolidBucketItem) {
            return BlockCost.unsupported(Reason.BUCKET_ITEM);
        }
        int count = count(state);
        if (count <= 0) {
            return BlockCost.unsupported(Reason.INVALID_STATE);
        }
        if (block instanceof RespawnAnchorBlock) {
            return itemsOf(item, 1, Items.GLOWSTONE, state.getValue(RespawnAnchorBlock.CHARGE));
        }
        return BlockCost.items(List.of(new ItemStack(item, count)));
    }

    private static Reason denial(BlockState state) {
        var block = state.getBlock();
        if (block instanceof LiquidBlock || block instanceof BubbleColumnBlock) {
            return Reason.FLUID;
        }
        if (block instanceof BaseFireBlock) {
            return Reason.FIRE;
        }
        if (block instanceof NetherPortalBlock || block instanceof EndPortalBlock || block instanceof EndGatewayBlock) {
            return Reason.PORTAL;
        }
        if (block instanceof MovingPistonBlock) {
            return Reason.MOVING_PISTON;
        }
        if (block instanceof TallSeagrassBlock) {
            return Reason.NO_FAITHFUL_COST;
        }
        if (block instanceof AbstractCauldronBlock && !(block instanceof CauldronBlock)) {
            return Reason.NO_FAITHFUL_COST;
        }
        if (isProgressedGrowth(state)) {
            return Reason.GROWTH_STAGE;
        }
        if (block instanceof ComposterBlock && state.getValue(ComposterBlock.LEVEL) > 0) {
            return Reason.NO_FAITHFUL_COST;
        }
        return null;
    }

    static boolean isProgressedGrowth(BlockState state) {
        var block = state.getBlock();
        if (block instanceof CropBlock crop) {
            return crop.getAge(state) > 0;
        }
        if (block instanceof AttachedStemBlock) {
            return true;
        }
        if (block instanceof CaveVines) {
            return state.getValue(CaveVines.BERRIES);
        }
        if (block instanceof SaplingBlock) {
            return state.getValue(SaplingBlock.STAGE) > 0;
        }
        if (block instanceof TurtleEggBlock) {
            return state.getValue(TurtleEggBlock.HATCH) > 0;
        }
        if (block instanceof SnifferEggBlock) {
            return state.getValue(SnifferEggBlock.HATCH) > 0;
        }
        if (block instanceof BeehiveBlock) {
            return state.getValue(BeehiveBlock.HONEY_LEVEL) > 0;
        }
        var age = growthAge(block);
        return age != null && state.getValue(age) > 0;
    }

    private static IntegerProperty growthAge(Block block) {
        if (block instanceof PitcherCropBlock) {
            return PitcherCropBlock.AGE;
        }
        if (block instanceof StemBlock) {
            return StemBlock.AGE;
        }
        if (block instanceof NetherWartBlock) {
            return NetherWartBlock.AGE;
        }
        if (block instanceof CocoaBlock) {
            return CocoaBlock.AGE;
        }
        if (block instanceof SweetBerryBushBlock) {
            return SweetBerryBushBlock.AGE;
        }
        if (block instanceof CactusBlock) {
            return CactusBlock.AGE;
        }
        if (block instanceof SugarCaneBlock) {
            return SugarCaneBlock.AGE;
        }
        return null;
    }

    private static BlockCost companion(BlockState state) {
        var block = state.getBlock();
        if (state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)
                && state.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.UPPER) {
            return new Companion(Direction.DOWN, below -> below.is(block)
                    && below.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.LOWER);
        }
        if (state.hasProperty(BlockStateProperties.BED_PART)
                && state.getValue(BlockStateProperties.BED_PART) == BedPart.FOOT) {
            if (!state.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
                return BlockCost.unsupported(Reason.INVALID_STATE);
            }
            var facing = state.getValue(BlockStateProperties.HORIZONTAL_FACING);
            return new Companion(facing, head -> head.is(block)
                    && head.getValue(BlockStateProperties.BED_PART) == BedPart.HEAD
                    && head.getValue(BlockStateProperties.HORIZONTAL_FACING) == facing);
        }
        if (block instanceof PistonHeadBlock) {
            var facing = state.getValue(PistonHeadBlock.FACING);
            var base = state.getValue(PistonHeadBlock.TYPE) == PistonType.STICKY ? Blocks.STICKY_PISTON : Blocks.PISTON;
            return new Companion(facing.getOpposite(), candidate -> candidate.is(base)
                    && candidate.getValue(PistonBaseBlock.EXTENDED)
                    && candidate.getValue(PistonBaseBlock.FACING) == facing);
        }
        return null;
    }

    private static int count(BlockState state) {
        var block = state.getBlock();
        if (block instanceof SlabBlock) {
            return state.getValue(SlabBlock.TYPE) == SlabType.DOUBLE ? 2 : 1;
        }
        if (block instanceof CandleBlock) {
            return state.getValue(CandleBlock.CANDLES);
        }
        if (block instanceof SeaPickleBlock) {
            return state.getValue(SeaPickleBlock.PICKLES);
        }
        if (block instanceof TurtleEggBlock) {
            return state.getValue(TurtleEggBlock.EGGS);
        }
        if (block instanceof SnowLayerBlock) {
            return state.getValue(SnowLayerBlock.LAYERS);
        }
        if (block instanceof PinkPetalsBlock) {
            return state.getValue(PinkPetalsBlock.AMOUNT);
        }
        if (block instanceof MultifaceBlock) {
            int faces = 0;
            for (var direction : Direction.values()) {
                if (MultifaceBlock.hasFace(state, direction)) {
                    faces++;
                }
            }
            return faces;
        }
        if (block instanceof VineBlock) {
            int faces = 0;
            for (var property : VineBlock.PROPERTY_BY_DIRECTION.values()) {
                if (state.getValue(property)) {
                    faces++;
                }
            }
            return faces;
        }
        return 1;
    }

    private static BlockCost itemsOf(Item first, int firstCount, Item second, int secondCount) {
        if (first == Items.AIR || second == Items.AIR) {
            return BlockCost.unsupported(Reason.NO_ITEM);
        }
        var items = new ArrayList<ItemStack>(2);
        items.add(new ItemStack(first, firstCount));
        if (secondCount > 0) {
            items.add(new ItemStack(second, secondCount));
        }
        return BlockCost.items(items);
    }

    private static final class Derived {

        static final Map<Block, Item> ITEM_BY_BLOCK = Map.of(
                Blocks.KELP_PLANT, Items.KELP,
                Blocks.CAVE_VINES_PLANT, Items.GLOW_BERRIES,
                Blocks.WEEPING_VINES_PLANT, Items.WEEPING_VINES,
                Blocks.TWISTING_VINES_PLANT, Items.TWISTING_VINES,
                Blocks.BIG_DRIPLEAF_STEM, Items.BIG_DRIPLEAF,
                Blocks.BAMBOO_SAPLING, Items.BAMBOO
        );

        static final Map<Block, Block> CANDLE_BY_CAKE = candleByCake();

        private static Map<Block, Block> candleByCake() {
            var map = new IdentityHashMap<Block, Block>();
            for (var entry : BuiltInRegistries.BLOCK.entrySet()) {
                var block = entry.getValue();
                if (block instanceof CandleBlock candle && "minecraft".equals(entry.getKey().location().getNamespace())) {
                    map.put(CandleCakeBlock.byCandle(candle).getBlock(), block);
                }
            }
            return Map.copyOf(map);
        }
    }
}
