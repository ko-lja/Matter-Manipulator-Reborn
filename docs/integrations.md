# Adding an integration

An integration tells Matter Manipulator how to copy, move and rebuild your mod's blocks: which block-entity settings
to capture and restore, how they turn with the selection, what items a block costs, how your cables route, and where
to take items from. The API is `lu.kolja.matter_manipulator.api` (common) and `lu.kolja.matter_manipulator.api.client`
(client only). It does not promise binary compatibility between versions.

## Registering

Register from your mod constructor, in a class that only loads when Matter Manipulator is present:

```java
if (ModList.get().isLoaded("matter_manipulator")) {
    MyMatterManipulatorIntegration.register();
}
```

```java
public final class MyMatterManipulatorIntegration {
    static final Identifier MACHINE = Identifier.fromNamespaceAndPath("mymod", "machine");

    static void register() {
        Integrations.register(registrar -> {
            registrar.block(MACHINE, new MachineIntegration(), MY_MACHINE.get());
            registrar.cable(PIPE_ID, new PipeIntegration(), state -> state.getBlock() instanceof MyPipeBlock);
            registrar.placementCost(COST_ID, new MyCost(), MY_SLAB.get());
            registrar.storage(STORAGE_ID, new MyNetworkStorage());
            registrar.crafting(CRAFTING_ID, new MyCrafting());
        });
    }
}
```

- The callback runs during Matter Manipulator's common setup, so call `DeferredHolder.get()` inside it. Registration
  closes after that; do not keep the registrar.
- Arguments are always ID, handler, selector. A selector is either `Block...` or a `Predicate<BlockState>`. A predicate
  must depend on the state only, because its answer is cached per state.
- IDs are unique per kind. Your payload is stored under the ID, so a block and a cable handler for the same block
  share one payload when they share an ID.
- Every handler whose selector matches runs, in registration order.
- Register client previews with `ClientIntegrations.register(registrar -> registrar.preview(id, handler, selector))`
  from client-only code, for example behind `FMLEnvironment.getDist() == Dist.CLIENT` in the constructor.

| You need | Implement |
| --- | --- |
| Settings captured from the block entity and restored, turned with the selection | `BlockIntegration` |
| A different item cost, nothing else | `PlacementCostIntegration` |
| Cable mode to place and connect your cable | `CableIntegration`, under the same ID as the `BlockIntegration` |
| Items taken from and returned to a linked network | `ItemStorageIntegration` |
| Missing items crafted on request | `CraftingIntegration` |
| Correct preview models | `ClientPreviewIntegration` |

Most hooks have a default that does nothing, so override only what you need. The default `apply` rejects, so implement
it whenever you capture something.

## Block integrations

```java
class MachineIntegration implements BlockIntegration {
    @Override
    public CompoundTag capture(IntegrationContext.Capture context) {
        return context.blockEntity() instanceof MyMachine machine ? machine.exportSettings() : new CompoundTag();
    }

    @Override
    public ApplyResult apply(IntegrationContext.Apply context, CompoundTag payload) {
        if (!(context.blockEntity() instanceof MyMachine machine)) {
            return ApplyResult.REJECTED_UNCHANGED;
        }
        machine.importSettings(payload);
        machine.setChanged();
        return ApplyResult.APPLIED;
    }

    @Override
    public CompoundTag transform(IntegrationContext.Transform context, CompoundTag payload) {
        var side = Direction.byName(payload.getStringOr("output_side", ""));
        if (side != null) {
            payload.putString("output_side", context.transform().transform(side).getName());
        }
        return payload;
    }
}
```

Capture settings, not inventories, energy, owners or network identities. An empty tag means nothing to capture.
Hooks get their own copy of the payload and must return a non-null result.

`apply` returns one of:

- `APPLIED`: the payload is installed.
- `REJECTED_UNCHANGED`, or `context.reject(reason)` to say why: nothing was installed. If every handler rejects a
  freshly placed block, it is removed again and its cost refunded.
- `PARTIAL_RESOURCES_SETTLED`: part was installed, and every unused item was returned with `context.refund(stack)`
  first.

An exception from `apply` counts as an unknown result and nothing is refunded, so check before you change anything.
Payloads can come from edited blueprint files: validate ranges, IDs and positions.

`context.purpose()` says what you are applying to:

- `STANDARD`, `COPY_TEMPLATE`: a block entity that was just created.
- `MOVE_TRANSFER`: a moved block whose complete NBT is already loaded. Only check it and return `REJECTED_UNCHANGED`
  on a mismatch (the move then rolls back). Never call `context.refund` here.
- `RECONFIGURE`: an existing block of the same type whose settings differ. It is only reused if `canReconfigure`
  returns `true`. Nothing was paid for it, so change settings only, never contents.

Turning and mirroring: core turns the block state. Turn the directions in your payload in `transform`, offsets with
`DirectionTransform.transformOffset`. Override `transformState` when your orientation is not in the state (return
`null` to refuse), and `transformBlockEntity` plus `transformsBlockEntity` when a moved block entity's NBT stores
directions.

Other hooks, all optional:

- `captureExchange`: the fields EXCHANGE keeps when it swaps the block.
- `portable`: strip world-specific values (network IDs) before a blueprint is saved.
- `placementRequirements`: the item cost, see below.
- `beforeRemove`: hand the items the block holds to `context.dropSink()` and clear them. Return `true` from
  `dropCarriesContents` when the block's own drop stores its contents.
- `removalRefusal(state)`, `moveRefusal(capture)`: a reason to refuse breaking or moving the block, or `null`.
- `moveGroupPeers`: positions that must move in the same group as this block.
- `relocateBlockEntity` with `relocatesBlockEntity`: rewrite absolute positions in a moved block entity's NBT.
- `createsTransferredBlockEntity` with `createTransferredBlockEntity`, and `afterTransfer`: for block entities that
  cannot simply load moved NBT.
- `additionalChangedPositions`: other blocks `apply` changes, so they are protection-checked first.
- `context.afterTick(work)` in `apply`: work that must wait until the next server tick.

Links to other blocks in the selection belong in the payload as offsets (`peer - pos`), never as absolute positions.
`context.inSelection(pos)` on a capture says whether a peer was captured too.

## Item costs

Core prices every state first (a double slab costs two slabs, a door's upper half costs nothing).
`placementRequirements` adjusts that price:

- `PlacementRequirements.defaults()`: keep the core cost.
- `additional(stack)`: add items.
- `replaceWith(stack)`: use these items instead.
- `unsupported(reason)`: the block cannot be placed.

Each of these also takes an `ItemRequirement`, which can accept more than one stack: `exact`, `withFallback`, `anyOf`,
`tag` (call it while placing, never during registration), `ignoringNbt` (any data components), and
`withVariants(filter)`. A stack taken as a variant loses its data components, so the filter must refuse stacks whose
data is worth something.

For a block that only needs a different price, register a `PlacementCostIntegration` instead of a block integration.

Items installed into a block, such as upgrades, are paid for during placement:

```java
@Override
public PlacementPreparationPlan preparation(IntegrationContext.Preparation context, CompoundTag payload) {
    if (!payload.contains("upgrade")) {
        return PlacementPreparationPlan.unchanged(payload);
    }
    var ops = ServerLifecycleHooks.getCurrentServer().registryAccess().createSerializationContext(NbtOps.INSTANCE);
    var plan = PlacementPreparationPlan.builder(payload);
    var upgrade = payload.read("upgrade", ItemStack.OPTIONAL_CODEC, ops).orElse(ItemStack.EMPTY);
    plan.component(ItemRequirement.exact(upgrade), (prepared, acquired) -> {
        if (acquired.isEmpty()) {
            prepared.remove("upgrade");
        } else {
            prepared.store("upgrade", ItemStack.CODEC, ops, acquired);
        }
    });
    return plan.build();
}
```

The plan must not touch the world or inventories. Core acquires each component and calls the update with the stack
it got, or `ItemStack.EMPTY`. `apply` then receives the prepared payload. `component(...)` returns a component that
takes child components, which are skipped when the parent is missing.

## Cables

```java
class PipeIntegration extends MachineIntegration implements CableIntegration {
    @Override
    public CablePlacementData prepare(IntegrationContext.Cable context) {
        var payload = context.payload(PIPE_ID);
        int mask = 0;
        for (var side : context.lineDirections()) {
            mask |= 1 << side.ordinal();
        }
        payload.putInt("connections", mask);
        return CablePlacementData.afterPath(payload);
    }

    @Override
    public boolean canReuse(IntegrationContext.CableReuse context) {
        return context.existing().getBlock() == context.target().getBlock();
    }
}
```

Register it with both `registrar.block` and `registrar.cable` under one ID. `duringPlacement(payload)` applies while
the block is placed; `afterPath(payload)` waits until the whole route stands. `canReuse` decides whether an existing
cable can stay; it is also asked on the client, so only read. `routeRefusal` names positions your cable cannot stand
at.

## Item storage

```java
class MyNetworkStorage implements ItemStorageIntegration {
    @Override
    public ItemStack extract(IntegrationContext.Storage context, ItemStack requested) {
        // take up to requested from the network linked to context.player() / context.tool()
        return remainder; // what could NOT be taken
    }

    @Override
    public ItemStack insert(IntegrationContext.Storage context, ItemStack offered) {
        return remainder; // what could NOT be stored
    }
}
```

Both return the part that was not moved, never null. Override `available` with a read-only count for the material
preview, and `variants` to list stored stacks of an item with any data components. Throw `StorageUnchangedException`
only before your first change (for example when the network cannot be found): the provider is skipped. Any other
exception stops the operation, and the stack handed to your provider is neither refunded nor given out.

## Crafting

`request(context)` returns `true` once it has accepted the request for `context.demands()`; `false` tries the next
provider. A provider that keeps requests across ticks overrides `tick()`, cancels everything in `serverStopping()`, and
drops one player's requests in `playerLoggedOut(uuid)`.

## Client previews

```java
ClientIntegrations.register(registrar -> registrar.preview(MACHINE, new ClientPreviewIntegration() {
    @Override
    public ModelData create(ClientIntegrationContext.CapturedPreview context) {
        return ModelData.builder().with(MY_COLOR, context.payload(MACHINE).getIntOr("color", 0)).build();
    }
}, MY_MACHINE.get()));
```

`create` serves saved blocks and cable templates, `fromWorld` live COPY and MOVE sources. Return `ModelData.EMPTY` to
leave the block to vanilla. The result is built into a cached mesh, so it must describe a static look, and positions
are preview-local. At `PreviewDetail.STATIC` never construct a block entity. Override `renderStatic` for blocks that
have no baked model, and `previewIcon` to choose the item shown for a captured block.

## Checklist

- Every Matter Manipulator reference sits in classes loaded only behind `ModList.get().isLoaded("matter_manipulator")`,
  and client code only on the client.
- IDs are namespaced and stable; selectors are as narrow as possible.
- Capture, apply and every turn and mirror give back the same block.
- Item costs are explicit, and partial installs refund what they did not use.
- Your mod starts without Matter Manipulator installed.
