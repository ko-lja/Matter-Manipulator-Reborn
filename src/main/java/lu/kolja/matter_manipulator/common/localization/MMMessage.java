package lu.kolja.matter_manipulator.common.localization;

import lombok.Getter;

import java.util.Locale;

public enum MMMessage implements LocalizationEnum {
    COORD("Stored %s at %s."),
    ANCHOR_PINNED("Paste anchor pinned at %s."),
    ANCHOR_FOLLOWING("The paste follows the crosshair."),
    SELECTION_MOVED("Selection moved %s %s."),
    SELECTION_RESIZED("Selection is now %s x %s x %s."),
    SELECTION_MIN_SIZE("The selection is already one block thick on that side."),
    FLOATING_DISTANCE("Floating distance: %s."),
    SLOT("Stored %s as %s."),
    BUILD_STARTED("Queued %s operations."),
    BUILD_ACTIVE_STATUS("A Matter Manipulator %s job is still active (%s): %s of %s operations left; %s changed, %s "
            + "skipped, %s already in place; last change %s ticks ago; last result: %s."),
    BUILD_STATE_WORKING("working"),
    BUILD_STATE_WAITING("repaying the block budget a MOVE group overdrew, %s more ticks"),
    BUILD_STATE_PREPARING("preparing MOVE group %4$s of %5$s, %1$s of %2$s blocks ready, commit weight %3$s"),
    BUILD_STATE_COMMITTING("MOVE group %3$s of %4$s ready to commit, %1$s blocks, commit weight %2$s"),
    BUILD_RESULT_NONE("none yet"),
    BUILD_RESULT_CHANGED("changed"),
    BUILD_RESULT_UNCHANGED("already in place"),
    BUILD_RESULT_SKIPPED("skipped (%s)"),
    PLANNING_BUSY("Too soon after your last request. Try again in a moment."),
    ACTION_WAITING("Still preparing your last request."),
    ACTION_CANCELLED("Dropped your waiting request: the manipulator, its settings or your dimension changed."),
    COMPILING_SOURCE("Compiling copy plan: inspecting source %s / %s"),
    COMPILING_VERIFY("Compiling copy plan: verifying source %s / %s"),
    COMPILING_PLAN("Compiling copy plan: resolving build plan %s / %s"),
    BUILD_CONFLICT("Another active Matter Manipulator job overlaps this selection."),
    BUILD_COMPLETE("Matter manipulation complete: %s changed, %s skipped."),
    BUILD_UNCHANGED("%s positions already held the planned block and were left untouched."),
    BUILD_SKIPPED("Skipped: %s."),
    STATUS_UNCHANGED("%s positions are already as planned."),
    STATUS_SKIPPED("%s positions will be skipped: %s."),
    EXCHANGE_NEEDS_REMOVE("Exchanging replaces blocks: it needs a tier that can remove blocks."),
    MOVE_DESTINATION_BLOCKED("Cannot move: the destination %s is blocked (%s)."),
    BUILD_DIMENSION_LEFT("Build stopped: you left the dimension it was planned in (%s changed, %s skipped)."),
    BUILD_TOOL_LOST("Build stopped: the Matter Manipulator that started it is no longer in your inventory (%s "
            + "changed, %s skipped)."),
    BUILD_TOOL_DUPLICATE("Build stopped: you carry more than one copy of the Matter Manipulator that started it (%s "
            + "changed, %s skipped)."),
    BUILD_PLAYER_DIED("Build stopped: you died (%s changed, %s skipped)."),
    MOVE_GROUP_HEAVY("%s connected blocks must move together in one server tick (commit weight %s, one tick normally "
            + "commits up to %s), which may briefly stall the server."),
    MOVE_GROUP_TOO_LARGE("%s blocks stay in place: they are connected into groups that must move together, and the "
            + "largest has %s blocks (server limit maxAtomicMoveGroup: %s)."),
    MOVE_ROLLBACK_FAILED("Move stopped: a failed move near %s could not be fully undone, see the server log for the "
            + "affected blocks (%s changed, %s skipped)."),
    COPY_MISSING_COMPONENTS("Missing copied components: %s"),
    CRAFT_NOTHING_MISSING("All required materials are already available."),
    FLUX_NETWORK_GONE("Flux device placed disconnected: the copied network no longer exists."),
    FLUX_NETWORK_DENIED("Flux device placed disconnected: you cannot access the network %s."),
    CRAFT_NO_NETWORK("The linked AE2 network is unavailable or out of range."),
    CRAFT_REQUEST_ACTIVE("A missing-material crafting request is already active."),
    CRAFT_REQUEST_STARTED("Calculating %s separate AE2 crafting jobs."),
    CRAFT_JOB_SUBMITTED("Requested %sx %s from AE2."),
    CRAFT_JOB_FAILED("Could not request %sx %s: %s."),
    CRAFT_REQUEST_COMPLETE("AE2 requests complete: %s submitted, %s failed."),
    CRAFT_FAILURE_CALCULATION("matter_manipulator.message.craft_failure.calculation", "crafting calculation failed"),
    CRAFT_FAILURE_INCOMPLETE_PLAN("matter_manipulator.message.craft_failure.incomplete_plan",
            "the full amount cannot be crafted with the available patterns and ingredients"),
    CRAFT_FAILURE_NO_CPU("matter_manipulator.message.craft_failure.no_cpu", "no crafting CPU is available"),
    CRAFT_FAILURE_NO_SUITABLE_CPU("matter_manipulator.message.craft_failure.no_suitable_cpu",
            "no suitable crafting CPU is available"),
    CRAFT_FAILURE_CPU_BUSY("matter_manipulator.message.craft_failure.cpu_busy", "the selected crafting CPU is busy"),
    CRAFT_FAILURE_CPU_OFFLINE("matter_manipulator.message.craft_failure.cpu_offline",
            "the selected crafting CPU is offline"),
    CRAFT_FAILURE_CPU_TOO_SMALL("matter_manipulator.message.craft_failure.cpu_too_small",
            "the crafting CPU is too small"),
    CRAFT_FAILURE_MISSING_INGREDIENT("matter_manipulator.message.craft_failure.missing_ingredient",
            "an ingredient became unavailable before submission"),
    CRAFT_FAILURE_UNKNOWN("matter_manipulator.message.craft_failure.unknown", "an unknown AE2 crafting error occurred"),
    MISSING_COORDS("Mark the required coordinates first."),
    REGION_TOO_LARGE("Selection region is too large: %s blocks (limit %s)."),
    REGION_NOT_LOADED("Part of the selection is in unloaded chunks."),
    OUT_OF_RANGE("A marked coordinate is farther than this tier's %s-block range."),
    BLUEPRINT_CAPTURING("Capturing blueprint \"%s\"..."),
    BLUEPRINT_SAVED("Saved blueprint \"%s\" with %s blocks."),
    BLUEPRINT_PAYLOADS_DROPPED("Left out %s mod payloads that could not be exported; see the server log."),
    BLUEPRINT_COPY_ONLY("Switch to Copy mode to save a blueprint."),
    BLUEPRINT_SAVE_PENDING("Your last blueprint is still being saved."),
    BLUEPRINT_BUSY("The server is still working on your last blueprint. Try again in a moment."),
    BLUEPRINT_NAME_INVALID("Give the blueprint a name of 1 to %s characters."),
    BLUEPRINT_TOO_MANY_BLOCKS("This selection has more different blocks than a blueprint can hold (%s)."),
    BLUEPRINT_TOO_LARGE("This blueprint would be %s bytes; the server accepts at most %s bytes."),
    BLUEPRINT_UNREADABLE("This blueprint could not be read back: %s"),
    BLUEPRINT_CLIENT_QUOTA("This blueprint needs more memory to read than a client allows (%s MiB of NBT); save a "
            + "smaller region."),
    BLUEPRINT_FILE_SAVED("Blueprint written to %s."),
    BLUEPRINT_FILE_FAILED("Could not write the blueprint file: %s"),
    BLUEPRINT_ALREADY_IN_LIBRARY("\"%s\" is already in your library (%s)."),
    BLUEPRINT_FILE_UNREADABLE("Could not read the blueprint file %s: %s"),
    BLUEPRINT_NONE("Choose a blueprint first."),
    BLUEPRINT_NOT_UPLOADED("The server does not have this blueprint yet; it asked your library for it."),
    BLUEPRINT_NOT_IN_LIBRARY("The server needs a blueprint that is not in your library (%s)."),
    BLUEPRINT_UNKNOWN_SKIPPED("Skipped %s blueprint blocks that are unknown to this game or cannot be placed."),
    BLUEPRINT_LOADED("Loaded blueprint \"%s\"."),
    BLUEPRINT_REJECTED("The server refused the blueprint: %s"),
    BLUEPRINT_UPLOAD_TOO_LARGE("it is larger than %s bytes."),
    BLUEPRINT_UPLOAD_TOO_SOON("wait a second between uploads."),
    BLUEPRINT_UPLOAD_REFUSED("\"%s\" is %s bytes; this server accepts blueprints of at most %s bytes."),
    BLUEPRINT_WRITTEN("Wrote blueprint \"%s\" to the item."),
    BLUEPRINT_READ("Sending blueprint \"%s\" to your library."),
    BLUEPRINT_ITEM_BLANK("This blueprint is blank. Hold a Blueprint mode manipulator with a blueprint in your off "
            + "hand to write it."),
    BLUEPRINT_ITEM_WRITTEN("Sneak and use to copy \"%s\" into your blueprint library."),
    BLUEPRINT_ITEM_FETCHING("The server is fetching this blueprint from your library; use the item again in a moment."),
    BLUEPRINT_ITEM_TOO_SOON("Wait a second between uses of Blueprint items."),
    BLUEPRINT_STORE_FAILED("Could not store the blueprint in this world: %s"),
    BLUEPRINT_STORE_MISSING("This world holds no copy of blueprint \"%s\"."),
    BLUEPRINT_STORE_UNREADABLE("Could not read blueprint \"%s\" from this world: %s"),
    BLUEPRINT_CLIPBOARD_COPIED("Copied \"%s\" to the clipboard (%s characters)."),
    BLUEPRINT_CLIPBOARD_COPY_TOO_LONG("\"%s\" would be %s characters as text; a blueprint string holds at most %s. "
            + "Share the file instead."),
    BLUEPRINT_CLIPBOARD_SAVED("Saved %s from the clipboard to your library."),
    BLUEPRINT_CLIPBOARD_NOT_BLUEPRINT("The clipboard holds no blueprint string (one starts with %s)."),
    BLUEPRINT_CLIPBOARD_TOO_LONG("The clipboard holds %s characters; a blueprint string holds at most %s."),
    BLUEPRINT_CLIPBOARD_CORRUPT("The blueprint string on the clipboard is damaged: %s"),
    BLUEPRINT_CLIPBOARD_TOO_LARGE("The blueprint string on the clipboard unpacks to more than %s bytes."),
    BLUEPRINT_CLIPBOARD_UNREADABLE("The blueprint on the clipboard cannot be used: %s"),
    IMMOVABLE_BLOCK("%s cannot be manipulated (unbreakable or tagged matter_manipulator:immovable)."),
    UNKNOWN_BLOCK("Unknown block %s: the mod that added it may be missing. Pick the slot again."),
    AIR_NEEDS_REMOVE("An air slot removes blocks: it needs a tier that can remove and a remove mode other than None."),
    UNPLACEABLE_BLOCK("%s cannot be placed: %s."),
    COMPANION_WITHOUT_PRIMARY("%s is only placed together with the other half that carries its cost. Pick the lower "
            + "half (doors, tall plants) or the head (beds)."),
    COPY_SKIPPED("Skipped %s source blocks that cannot be reproduced by placement: %s."),
    COPY_SKIPPED_ENTRY("%s x %s"),
    COPY_SKIPPED_COMPANION("a two-block half whose other half is outside the copy"),
    COPY_ORIENTATION_SKIPPED("Skipped %s source blocks that have no state for the rotated or mirrored orientation."),
    COPY_ORIENTATION_APPROXIMATED("Placed %s blocks in their closest orientation: they have no exact state for the "
            + "rotated or mirrored one."),
    MOVE_ORIENTATION("Cannot move: %s at %s has no exact state for the rotated or mirrored orientation. A copy "
            + "places it in the closest one."),
    NOTHING_PLACEABLE("The selected region contains no blocks the manipulator can place."),
    REFUSED_LEFT_OUT("Left out %s blocks: %s."),
    AIR_CABLE("The cable slot cannot be air."),
    PICK_AIR("Air cannot be picked."),
    UNSUPPORTED_TIER("This manipulator tier cannot use %s mode."),
    EMPTY_SLOT("No block is configured for %s."),
    NO_ENERGY("Not enough FE."),
    STORAGE_FAILURE("Matter manipulation stopped: storage integration %s failed (%s changed, %s skipped). Every item "
            + "whose whereabouts are known was returned; the failed storage was not used again."),
    STORAGE_UNCERTAIN("Storage integration %3$s failed mid-transfer: up to %1$sx %2$s may have moved and could not "
            + "be verified. Those items were not refunded, to avoid duplication. Check that storage."),
    MOVE_ENERGY("This move needs %s FE to finish safely; the tool has %s FE."),
    PICK_MISSING("Look at a block to pick it."),
    CABLE_PATH_FOUND("Cable path found in %s ms: %s blocks, %s searched."),
    CABLE_PATH_FAILED("No cable path found after %s ms: %s searched (%s)."),
    CABLE_ROUTE_POINT("Route point %s stored at %s; %s cable blocks."),
    CABLE_ROUTE_SAME("That position is already the end of the route."),
    CABLE_ROUTE_TOO_MANY_POINTS("The cable route already has the maximum of %s points; clear it to start a new one."),
    CABLE_ROUTE_MISSING("Select at least two cable route points first."),
    CABLE_ROUTE_TOO_LARGE("Cable route exceeds the %s block operation limit."),
    CABLE_ROUTE_NOT_LOADED("Part of the cable route is in unloaded chunks."),
    CABLE_ROUTE_REFUSED_AT("%s at %s, %s, %s"),
    NOT_ON_SERVER_LEVEL("Not on a server level."),
    MARKS_IN_OTHER_DIMENSION("Marks are in a different dimension."),
    NOT_A_MANIPULATOR("Not a matter manipulator."),
    SELECTION_OVER_LIMIT("Selection is too large: more than %s operations."),
    SELECTION_TOO_LARGE("Selection is too large: %s"),
    SELECTION_TOO_LARGE_OPERATIONS("Selection is too large: %s operations."),
    CABLE_ROUTE_NOT_AXIS_ALIGNED("Cable route contains a non-axis-aligned segment."),
    PICK_REPLACE_WHITELIST("Pick a replace whitelist block first."),
    NO_MOVABLE_BLOCKS("The selected region contains no movable blocks.");

    @Getter(onMethod_ = @Override)
    private final String translationKey;
    @Getter(onMethod_ = @Override)
    private final String englishText;

    MMMessage(String englishText) {
        this(null, englishText);
    }

    MMMessage(String translationKey, String englishText) {
        this.translationKey = translationKey != null
                ? translationKey
                : "matter_manipulator.message." + name().toLowerCase(Locale.ROOT);
        this.englishText = englishText;
    }
}
