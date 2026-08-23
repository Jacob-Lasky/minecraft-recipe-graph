package io.github.jacoblasky.recipedump.client;

import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

/**
 * The clock that stops a parked plan waiting forever. #321 §2
 *
 * <h2>WHY A TICK HANDLER AT ALL, WHEN EVERY OTHER DECISION HERE IS EVENT-DRIVEN</h2>
 *
 * `PlannerStock.hold` parks a plan until a reply lands. If the reply never lands there is no
 * further event of any kind -- no packet, no keypress, no window -- so nothing can notice. That is
 * exactly how the planner came to show IDLE for a whole session with no explanation: the state was
 * unreachable by anything except the passage of time, and nothing here was watching time.
 *
 * A tick is the only clock a client has. One comparison per second is the smallest thing that can
 * observe "we asked five seconds ago and were ignored".
 *
 * <h2>A SEPARATE CLASS RATHER THAN A METHOD ON `PlannerStock`, and not for tidiness</h2>
 *
 * `PlannerStock.install()` is called by twenty-odd unit tests and by `Ae2PlacedProbeShot` from the
 * SERVER thread. Registering a Forge event handler inside it would register one per test, register
 * one from a server-thread probe, and put `MinecraftForge.EVENT_BUS` on the class-init path of
 * every one of those callers. So the registration lives here, has exactly one production caller
 * (`ClientProxy.init`), and the thing it drives stays a plain static method that a test can call
 * with its own clock.
 *
 * <h2>ONE CHECK A SECOND, NOT ONE A TICK</h2>
 *
 * The deadline is five seconds. Twenty comparisons a second to resolve a five-second deadline is
 * nineteen more than the answer needs, and this runs for the whole session on every client. The
 * cost of the coarser clock is that the give-up can land up to a second late, which is invisible
 * against a five-second wait.
 *
 * <h2>CLIENT TICK, NOT RENDER TICK</h2>
 *
 * `PlannerStock.releaseIfUnanswered` RUNS A PLAN, and a plan is started on the client thread and
 * handed to `PlannerService`'s worker. `TickEvent.ClientTickEvent` is the client thread;
 * `RenderTickEvent` fires mid-frame, where starting a solve would be doing work inside somebody
 * else's draw. `ClientProxy.applyStockSnapshot` schedules the ordinary reply onto the same thread
 * for the same reason, so the timeout path and the reply path release a plan from the same place.
 */
public final class StockTimeout {

    /** Client ticks between checks. Twenty a second, so this is once a second. */
    private static final int TICKS_PER_CHECK = 20;

    /**
     * Whether the handler is registered.
     *
     * REGISTRATION IS IDEMPOTENT AND HAS TO BE. `install` has one production caller today; a second
     * one -- another proxy, a reload, a test that decides to exercise this -- would otherwise
     * double-register and run the check twice per tick, which is harmless and is exactly the kind
     * of harmless that hides a third registration.
     */
    private static boolean installed;

    private int ticks;

    private StockTimeout() {
    }

    /** Start watching for an unanswered ask. Called once, from {@code ClientProxy.init}. */
    public static synchronized void install() {
        if (installed) {
            return;
        }
        installed = true;
        MinecraftForge.EVENT_BUS.register(new StockTimeout());
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        // END rather than START, matching every other tick subscriber in this mod: a plan started
        // at END runs against a world the tick has finished mutating.
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        if (++ticks < TICKS_PER_CHECK) {
            return;
        }
        ticks = 0;
        PlannerStock.releaseIfUnanswered();
    }
}
