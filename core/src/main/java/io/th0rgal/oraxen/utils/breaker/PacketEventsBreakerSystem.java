package io.th0rgal.oraxen.utils.breaker;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListener;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.DiggingAction;
import com.github.retrooper.packetevents.util.Vector3i;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerDigging;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerBlockBreakAnimation;
import io.th0rgal.oraxen.OraxenPlugin;
import io.th0rgal.oraxen.utils.SchedulerUtil;
import io.th0rgal.oraxen.utils.VersionUtil;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;

public class PacketEventsBreakerSystem extends BreakerSystem {
    private final PacketListener listener = new PacketListener() {
        @Override public void onPacketReceive(PacketReceiveEvent event) {
            if (!event.getPacketType().equals(PacketType.Play.Client.PLAYER_DIGGING)) return;
            final var wrapper = new WrapperPlayClientPlayerDigging(event);
            final Player player = event.getPlayer();
            if (player == null) return; // this should never happen to normal players... someone had a problem with it tho...

            final Vector3i pos = wrapper.getBlockPosition();
            BlockFace blockFace;
            try {
                blockFace = BlockFace.valueOf(wrapper.getBlockFace().name());
            } catch (IllegalArgumentException e) {
                OraxenPlugin.get().getLogger().warning("[PacketEvents] Failed to decode BlockFace: " + wrapper.getBlockFace().name());
                blockFace = BlockFace.UP;
            }
            // blockFace is assigned once in the try branch and once in the catch branch.
            // Only one of those ever actually executes, but javac's effectively-final check
            // doesn't reason about mutual exclusivity here, so blockFace itself can never be
            // captured by a lambda. Copy it into a true final immediately (the Folia branch
            // below already needed this same workaround via finalBlockFace).
            final BlockFace finalBlockFace = blockFace;

            final boolean startedDigging = wrapper.getAction() == DiggingAction.START_DIGGING;
            final boolean finishedDigging = wrapper.getAction() == DiggingAction.FINISHED_DIGGING;

            // PacketEvents invokes this listener from Netty. On Folia, reading block state from
            // that thread can crash CraftBlock#getType because no region world data is bound.
            if (VersionUtil.isFoliaServer()) {
                SchedulerUtil.runForEntity(player, () -> {
                    final World world = player.getWorld();
                    final Location location = new Location(world, pos.getX(), pos.getY(), pos.getZ());
                    SchedulerUtil.runAtLocation(location, () -> {
                        if (!world.isChunkLoaded(pos.getX() >> 4, pos.getZ() >> 4)) return;
                        final Block block = world.getBlockAt(pos.getX(), pos.getY(), pos.getZ());
                        handleEvent(player, block, location, finalBlockFace, world, () -> {}, startedDigging, finishedDigging);
                    });
                }, null);
                return;
            }

            // Non-Folia (Paper/Spigot): handleEvent() must not run on the Netty thread.
            // Deep inside it, mechanic checks (e.g. NoteBlockMechanicListener#isTriggered)
            // call CraftBlock#getType(), which can join() on a chunk future. If that happens
            // on the Netty thread at the exact moment the main thread is blocked on a
            // synchronous Netty channel write (this happens during player login), both
            // threads deadlock waiting on each other.
            //
            // IMPORTANT CAVEAT: dispatching to the main thread means event.setCancelled(true)
            // below runs *after* this packet has already been forwarded by PacketEvents,
            // since cancellation is only honored synchronously, during the same call that
            // received the packet. In practice, once this runs on a delay, digging can no
            // longer be reliably blocked through this code path. If Oraxen relies on this
            // cancel to stop players breaking protected/custom blocks, verify that explicitly
            // (try breaking a high-hardness custom block right after deploying this) before
            // trusting it in production. A fix that avoids deferring the cancel is possible,
            // but it means looking at what BreakerSystem#handleEvent and
            // NoteBlockMechanicListener#isTriggered actually do with the block.
            final Runnable dig = () -> {
                final World world = player.getWorld();
                if (!world.isChunkLoaded(pos.getX() >> 4, pos.getZ() >> 4)) return;
                final Block block = world.getBlockAt(pos.getX(), pos.getY(), pos.getZ());
                final Location location = block.getLocation();
                handleEvent(player, block, location, finalBlockFace, world, () -> event.setCancelled(true),
                        startedDigging, finishedDigging);
            };

            if (Bukkit.isPrimaryThread()) {
                dig.run();
            } else {
                Bukkit.getScheduler().runTask(OraxenPlugin.get(), dig);
            }
        }
    };

    @Override
    protected void sendBlockBreak(final Player player, final Location location, final int stage) {
        var wrapper = new WrapperPlayServerBlockBreakAnimation(
            player.getEntityId(),
            new Vector3i(location.getBlockX(), location.getBlockY(), location.getBlockZ()),
            (byte) stage
        );
        PacketEvents.getAPI().getPlayerManager().sendPacket(player, wrapper);
    }

    @Override
    public void registerListener() {
        PacketEvents.getAPI().getEventManager().registerListener(listener, PacketListenerPriority.LOW);
    }
}
