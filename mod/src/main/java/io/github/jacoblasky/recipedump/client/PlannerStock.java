package io.github.jacoblasky.recipedump.client;

import io.github.jacoblasky.recipedump.common.ScenarioSource;
import io.github.jacoblasky.recipedump.common.ae2.StockSnapshot;
import net.minecraft.client.Minecraft;
import net.minecraft.nbt.NBTTagCompound;

/**
 * The join between {@link LiveStock} and the planner: ask when a plan is wanted, plan when the
 * reply lands, and answer for {@link ScenarioSource#HAVE} in between.
 *
 * <h2>Why this exists as its own class</h2>
 *
 * Every piece of the stock path already existed and none of them touched: the server read the
 * grid, the packets carried it, `LiveStock` could hold it and `ScenarioSource.HAVE` could
 * report it -- and nothing called any of it, so a plan was priced as though the player owned
 * nothing while a caveat said stock "is not read yet". #191. `LiveStock`'s own javadoc had the
 * design written down -- "the planner should request when it opens and plan when the reply
 * lands" -- and this is that sentence, as code, in one place rather than spread across the
 * proxy, the window and the keybind.
 *
 * <h2>THE PLAN WAITS FOR THE READ RATHER THAN RUNNING TWICE</h2>
 *
 * The tempting shape is to plan immediately and re-plan when the reply arrives. It is wrong on
 * cost: pricing is cached per SCENARIO, and a scenario with stock is a different scenario, so
 * planning before the reply means paying `Cost.estimate` twice on every open -- and it is the
 * dearest part of a plan by a wide margin. It is also wrong on what the player sees, because
 * the first answer would be the confidently-wrong "you own none of this" plan that
 * {@link ScenarioSource} exists to prevent, shown for as long as the solve takes.
 *
 * WAITING IS SAFE BECAUSE A REPLY IS GUARANTEED. `StockRequestMessage.Handler` answers every
 * path, including every refusal -- "a refusal is a reply, not a dropped packet" -- over the
 * ordinary reliable channel. The one case with no server to answer is a client that is not
 * connected to one, which {@link #canAsk} checks for rather than hanging on: the screenshot
 * harness is exactly that case, and it plans immediately with the caveat, as it did before.
 *
 * <h2>Single-threaded, like {@code NodeActionsHolder}</h2>
 *
 * Every caller is the client thread: the planner opening, the node menu re-planning, and the
 * packet handler AFTER `ClientProxy` has scheduled it back onto that thread. Nothing here is
 * synchronised and it would be misleading if it were, because a volatile field would imply a
 * cross-thread contract that nothing enforces. The one field a worker thread reads is
 * `LiveStock`'s own snapshot, through the reader, and that is a plain reference read of an
 * immutable object.
 */
public final class PlannerStock {

    /**
     * The plan that is waiting on a reply, or null.
     *
     * ONE, AND A NEWER QUESTION REPLACES AN OLDER ONE. Two outstanding questions means the
     * player asked for something else while the first read was in flight, and answering the
     * one they have moved on from would throw away the one they are looking at --
     * `PlannerService.plan` refuses a second solve while one is running, so the stale answer
     * would win by arriving first.
     */
    private static Runnable waiting;

    private PlannerStock() {
    }

    /**
     * Start answering for {@link ScenarioSource#HAVE}. Called once, from {@code ClientProxy}.
     *
     * ON THE CLIENT AND NOT IN `CommonProxy.preInit` beside the pin store, even though the
     * source is common: the snapshot lives on the client because the read is a round trip, so
     * the only side that can answer is this one. A server that plans for itself one day
     * installs its own reader straight off `Ae2Stock`, with no packet in the middle.
     */
    public static void install() {
        ScenarioSource.HAVE.readBy(new ScenarioSource.Reader() {
            @Override
            public ScenarioSource.Status status() {
                return statusOf(LiveStock.latest());
            }
        });
    }

    /**
     * What the held snapshot means for a plan.
     *
     * SEPARATE FROM THE READER SO IT CAN BE ASSERTED. The three answers are "nobody has asked",
     * "the server said no, and which no", and "here is what you own", and `LiveStock`'s own
     * test exists because the first two get conflated. Reporting a refusal as "not read yet"
     * would lose the only sentence that tells the player what to do about it.
     *
     * AN OLD SNAPSHOT IS STILL USED, deliberately. {@link LiveStock#isFresh} decides whether to
     * ASK again -- see {@link #planWhenRead} -- and not whether to believe what is held: a plan
     * priced against a thirty-second-old read of the network is close to right, and the
     * alternative on refusing is not "no answer" but the plan that assumes you own nothing,
     * which is the wrong answer this whole path exists to retire. Staleness is bounded by
     * asking before each plan rather than by discarding.
     */
    static ScenarioSource.Status statusOf(StockSnapshot snapshot) {
        if (snapshot == null) {
            return ScenarioSource.Status.unavailable("your ME network has not been read yet, so"
                    + " this plan assumes you own nothing -- open the planner again to ask");
        }
        if (!snapshot.isAvailable()) {
            return ScenarioSource.Status.unavailable(snapshot.reason().message());
        }
        return ScenarioSource.Status.available(snapshot.document());
    }

    /**
     * Run `plan` once the network has been read, which may be right now.
     *
     * Immediately when the held snapshot is fresh -- opening the planner twice in a row must
     * not cost two reads of a megabyte item list -- and immediately when there is nobody to
     * ask, so a disconnected client gets its plan with a caveat rather than nothing at all.
     * Otherwise the ask goes out and the reply runs this.
     */
    public static void planWhenRead(Runnable plan) {
        if (hold(plan, canAsk())) {
            LiveStock.request();
        }
    }

    /**
     * Decide whether `plan` waits. True when it is now waiting AND a read must be asked for;
     * false when it has already run or when an ask is already out.
     *
     * SPLIT FROM THE ASK SO THE DECISION CAN BE EXERCISED WITHOUT A NETWORK.
     * {@link LiveStock#request} writes to the server channel, which needs a connection to
     * write to -- so a test that reached it could only ever cover the branch that does not.
     * Everything worth getting wrong is above this line.
     *
     * A SECOND QUESTION DOES NOT SEND A SECOND ASK. Replacing the waiting plan is right -- the
     * player moved on -- but re-asking would put another megabyte of item list on the wire for
     * a reply that is already coming, which is the cost this whole class is arranged to avoid.
     * Safe to skip because a reply is guaranteed; see the class note.
     */
    static boolean hold(Runnable plan, boolean canAsk) {
        if (!canAsk || LiveStock.isFresh()) {
            waiting = null;
            plan.run();
            return false;
        }
        boolean alreadyAsked = waiting != null;
        waiting = plan;
        return !alreadyAsked;
    }

    /**
     * A reply landed: hold it, then answer the question that was waiting on it.
     *
     * ON THE CLIENT THREAD ALREADY -- {@code ClientProxy.applyStockSnapshot} schedules it, for
     * the reason the book sync does. Deserialising here rather than in the handler keeps
     * {@code StockReplyMessage} free of any decision about what a snapshot is for.
     *
     * A REPLY WITH NOTHING WAITING IS HELD AND NOT PLANNED ON. It means the read outlived the
     * question -- two asks in flight, or a window closed while one was out -- and starting a
     * solve nobody asked for would throw away whatever the player is looking at now.
     */
    public static void accept(NBTTagCompound payload) {
        LiveStock.accept(StockSnapshot.deserializeNBT(payload));
        Runnable plan = waiting;
        waiting = null;
        if (plan != null) {
            plan.run();
        }
    }

    /** Test seam: drop the outstanding question, so one test cannot leak into the next. */
    static void forgetWaiting() {
        waiting = null;
    }

    /**
     * True when there is a server to ask.
     *
     * NOT REACHABLE FROM A TEST, which is why {@link #hold} takes the answer as an argument:
     * loading `Minecraft` needs LWJGL and a JUnit classpath has none, so a method naming it
     * throws `NoClassDefFoundError` before its first line. The null check on `mc` is still
     * worth its line for the same reason the connection check is -- the screenshot harness
     * runs with a client and no server.
     */
    private static boolean canAsk() {
        Minecraft mc = Minecraft.getMinecraft();
        return mc != null && mc.getConnection() != null;
    }
}
