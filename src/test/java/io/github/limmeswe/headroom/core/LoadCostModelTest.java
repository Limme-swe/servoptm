package io.github.limmeswe.headroom.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class LoadCostModelTest {

    @Test
    void learnsLatencyAndFailurePenaltyByRegion() {
        LoadCostModel model = new LoadCostModel(32);
        ChunkKey chunk = new ChunkKey(UUID.randomUUID(), 4, 4);
        assertEquals(1.0, model.predictedCost(chunk));

        for (int index = 0; index < 8; index++) {
            model.recordSuccess(chunk, 25.0);
        }
        double slowCost = model.predictedCost(chunk);
        assertTrue(slowCost > 1.0);

        model.recordFailure(chunk);
        assertTrue(model.predictedCost(chunk) > slowCost);
    }

    @Test
    void cacheRemainsBounded() {
        LoadCostModel model = new LoadCostModel(32);
        UUID world = UUID.randomUUID();
        for (int region = 0; region < 100; region++) {
            model.recordSuccess(new ChunkKey(world, region * 32, 0), 5.0);
        }
        assertEquals(32, model.trackedRegions());
    }
}
