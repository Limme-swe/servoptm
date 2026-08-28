package io.github.limmeswe.headroom.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class AdaptiveBudgetControllerTest {

    @Test
    void transitionsWithHysteresisAndDisablesWorkUnderCriticalLoad() {
        AdaptiveBudgetController controller = new AdaptiveBudgetController(
                new AdaptiveBudgetController.Config(80, 40, 38.0, 48.0, 55.0, 3, 2)
        );

        addSamples(controller, 80, 20.0);
        assertEquals(AdaptiveBudgetController.State.RECOVERING, controller.evaluate().state());
        assertEquals(AdaptiveBudgetController.State.RECOVERING, controller.evaluate().state());
        AdaptiveBudgetController.Decision healthy = controller.evaluate();
        assertEquals(AdaptiveBudgetController.State.HEALTHY, healthy.state());
        assertTrue(healthy.allowSpeculativeWork());

        addSamples(controller, 80, 65.0);
        assertEquals(AdaptiveBudgetController.State.CONSTRAINED, controller.evaluate().state());
        AdaptiveBudgetController.Decision critical = controller.evaluate();
        assertEquals(AdaptiveBudgetController.State.CRITICAL, critical.state());
        assertEquals(0.0, critical.workMultiplier());
        assertEquals(0.0, critical.extensionFraction());
        assertFalse(critical.allowSpeculativeWork());
    }

    private static void addSamples(AdaptiveBudgetController controller, int count, double value) {
        for (int index = 0; index < count; index++) {
            controller.observeTick(value);
        }
    }
}
