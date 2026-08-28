package dev.headroom.runtime;

import dev.headroom.core.ChunkCoordinate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;

/**
 * Bounded ownership of Paper plugin chunk tickets.
 */
public final class WarmLeaseRegistry implements AutoCloseable {

    private final Plugin plugin;
    private final int maximumChunks;
    private final int timeoutTicks;
    private final Map<ChunkCoordinate, Lease> leases = new HashMap<>();
    private final Map<LeaseOwner, Set<ChunkCoordinate>> ownerIndex = new HashMap<>();

    public WarmLeaseRegistry(Plugin plugin, int maximumChunks, int timeoutTicks) {
        this.plugin = plugin;
        this.maximumChunks = maximumChunks;
        this.timeoutTicks = timeoutTicks;
    }

    public boolean acquire(
            World world,
            ChunkCoordinate coordinate,
            LeaseOwner owner,
            long currentTick) {
        requirePrimaryThread();
        if (!world.getUID().equals(coordinate.worldId())) {
            throw new IllegalArgumentException("World does not match chunk coordinate");
        }

        Lease lease = this.leases.get(coordinate);
        if (lease == null) {
            if (this.leases.size() >= this.maximumChunks) {
                return false;
            }
            world.addPluginChunkTicket(coordinate.x(), coordinate.z(), this.plugin);
            lease = new Lease(world);
            this.leases.put(coordinate, lease);
        }

        lease.ownerExpiry.put(owner, currentTick + this.timeoutTicks);
        this.ownerIndex.computeIfAbsent(owner, ignored -> new HashSet<>()).add(coordinate);
        return true;
    }

    public boolean isHeldBy(ChunkCoordinate coordinate, LeaseOwner owner) {
        Lease lease = this.leases.get(coordinate);
        return lease != null && lease.ownerExpiry.containsKey(owner);
    }

    public void renewOwner(LeaseOwner owner, long currentTick) {
        requirePrimaryThread();
        Set<ChunkCoordinate> coordinates = this.ownerIndex.get(owner);
        if (coordinates == null) {
            return;
        }
        long expiry = currentTick + this.timeoutTicks;
        for (ChunkCoordinate coordinate : coordinates) {
            Lease lease = this.leases.get(coordinate);
            if (lease != null && lease.ownerExpiry.containsKey(owner)) {
                lease.ownerExpiry.put(owner, expiry);
            }
        }
    }

    public void releaseOwner(LeaseOwner owner) {
        requirePrimaryThread();
        Set<ChunkCoordinate> coordinates = this.ownerIndex.remove(owner);
        if (coordinates == null) {
            return;
        }
        for (ChunkCoordinate coordinate : List.copyOf(coordinates)) {
            removeOwner(coordinate, owner);
        }
    }

    public void releasePlayer(UUID playerId) {
        requirePrimaryThread();
        List<LeaseOwner> matching = this.ownerIndex.keySet().stream()
                .filter(owner -> owner.playerId().equals(playerId))
                .toList();
        for (LeaseOwner owner : matching) {
            releaseOwner(owner);
        }
    }

    public Set<LeaseOwner> expire(long currentTick) {
        requirePrimaryThread();
        Set<LeaseOwner> expiredOwners = new HashSet<>();
        List<ChunkCoordinate> emptyLeases = new ArrayList<>();

        for (Map.Entry<ChunkCoordinate, Lease> entry : this.leases.entrySet()) {
            Iterator<Map.Entry<LeaseOwner, Long>> owners =
                    entry.getValue().ownerExpiry.entrySet().iterator();
            while (owners.hasNext()) {
                Map.Entry<LeaseOwner, Long> owner = owners.next();
                if (owner.getValue() <= currentTick) {
                    expiredOwners.add(owner.getKey());
                    owners.remove();
                    Set<ChunkCoordinate> indexed = this.ownerIndex.get(owner.getKey());
                    if (indexed != null) {
                        indexed.remove(entry.getKey());
                        if (indexed.isEmpty()) {
                            this.ownerIndex.remove(owner.getKey());
                        }
                    }
                }
            }
            if (entry.getValue().ownerExpiry.isEmpty()) {
                emptyLeases.add(entry.getKey());
            }
        }

        for (ChunkCoordinate coordinate : emptyLeases) {
            removeLease(coordinate);
        }
        return Set.copyOf(expiredOwners);
    }

    public int leaseCount() {
        return this.leases.size();
    }

    public int ownerCount() {
        return this.ownerIndex.size();
    }

    @Override
    public void close() {
        requirePrimaryThread();
        for (ChunkCoordinate coordinate : List.copyOf(this.leases.keySet())) {
            removeLease(coordinate);
        }
        this.ownerIndex.clear();
    }

    private void removeOwner(ChunkCoordinate coordinate, LeaseOwner owner) {
        Lease lease = this.leases.get(coordinate);
        if (lease == null) {
            return;
        }
        lease.ownerExpiry.remove(owner);
        if (lease.ownerExpiry.isEmpty()) {
            removeLease(coordinate);
        }
    }

    private void removeLease(ChunkCoordinate coordinate) {
        Lease lease = this.leases.remove(coordinate);
        if (lease == null) {
            return;
        }
        try {
            lease.world.removePluginChunkTicket(coordinate.x(), coordinate.z(), this.plugin);
        } catch (IllegalStateException exception) {
            this.plugin.getLogger().fine(
                    () -> "Could not remove warm ticket for " + coordinate + ": " + exception.getMessage());
        }
        for (LeaseOwner owner : lease.ownerExpiry.keySet()) {
            Set<ChunkCoordinate> indexed = this.ownerIndex.get(owner);
            if (indexed != null) {
                indexed.remove(coordinate);
                if (indexed.isEmpty()) {
                    this.ownerIndex.remove(owner);
                }
            }
        }
    }

    private static void requirePrimaryThread() {
        if (!Bukkit.isPrimaryThread()) {
            throw new IllegalStateException("Warm leases may only be changed on the server thread");
        }
    }

    private static final class Lease {

        private final World world;
        private final Map<LeaseOwner, Long> ownerExpiry = new HashMap<>();

        private Lease(World world) {
            this.world = world;
        }
    }
}
