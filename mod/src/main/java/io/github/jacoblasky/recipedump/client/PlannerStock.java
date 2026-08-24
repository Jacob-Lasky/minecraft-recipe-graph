package io.github.jacoblasky.recipedump.client;

import io.github.jacoblasky.recipedump.common.ScenarioSource;
import io.github.jacoblasky.recipedump.common.ae2.StockSnapshot;
import net.minecraft.client.Minecraft;
import net.minecraft.nbt.NBTTagCompound;

/**
 * The join between {@link LiveStock} and the planner: ask when a plan is wanted, plan when the
 * reply lands, and answer for {@link ScenarioSource#HAVE} and {@link ScenarioSource#PLACED} in
 * between -- two inputs off one reply, because #320's grid-connected machines come back in the
 * same read as the items.
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
 * <h2>A REPLY IS NOT GUARANTEED, AND WAITING IS ONLY SAFE BECAUSE IT IS BOUNDED. #321</h2>
 *
 * WHAT THIS JAVADOC USED TO SAY WAS WRONG, and it was wrong in the direction that defended a
 * hang: "waiting is safe because a reply is guaranteed ... the one case with no server to answer
 * is a client that is not connected to one, which `canAsk` checks for". `canAsk` checks only that
 * a CONNECTION EXISTS. A client connected to a dedicated server that does not have this mod
 * installed never gets an answer, and 1.12.2 does not refuse that connection -- measured in the
 * decompiled source on #321: `Loader.checkRemoteModList` always returns true,
 * `NetworkModHolder.DefaultNetworkChecker.checkCompatible` explicitly accepts a client-side-extra
 * mod, and `NetworkDispatcher` then hands the unknown channel's payload to vanilla handling, which
 * drops it. No kick, no log, no error. DO NOT re-derive this; it is measured and it is on the
 * issue. (Commit 4dee2ea's release note asserts the handshake would refuse such a server. It does
 * not.)
 *
 * The consequence of believing the old sentence was that a parked plan stayed parked for the whole
 * session and the planner showed IDLE -- "nothing planned yet" -- with nothing anywhere saying why.
 * So the wait is now BOUNDED: {@link #releaseIfUnanswered} gives up after
 * {@link #REPLY_TIMEOUT_MILLIS} and plans anyway, with `NO_REPLY` in the caveat line naming the
 * server's mod list as the thing to go and check. What is still true is the narrower claim:
 * `StockRequestMessage.Handler` answers every path a SERVER RUNNING THIS MOD takes, refusals
 * included -- a refusal is a reply, not a dropped packet -- so on a server that has the mod the
 * timeout never fires. A client with no server at all is still `canAsk`'s case and still plans
 * immediately with the caveat; the screenshot harness is exactly that.
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

    /**
     * When the ask that {@link #waiting} is waiting on went out, in millis.
     *
     * SET ONCE PER ASK AND NOT ONCE PER QUESTION. A second question replaces the waiting plan
     * without sending a second ask -- see {@link #hold} -- so resetting this there would let a
     * player who reopens the planner every few seconds push the deadline back forever and never
     * see the timeout that exists for them.
     */
    private static long askedAt;

    /**
     * How long a plan waits for a reply before planning without one. #321
     *
     * FIVE SECONDS IS A JUDGEMENT AND IT IS DELIBERATELY GENEROUS AGAINST A ROUND TRIP, which is
     * milliseconds on a local server and well under a second on a remote one even with the
     * server-side tile-entity scan in the read (#320 phase 2). What it is really sized against is
     * the other error: a player staring at "nothing planned yet" learns nothing, so waiting longer
     * buys a marginally better chance of a slow answer at the cost of the thing this exists to
     * prevent.
     *
     * A LATE REPLY IS NOT LOST, which is what makes a short timeout safe. `accept` stores whatever
     * arrives whenever it arrives, so a reply landing after the give-up still feeds the NEXT plan;
     * the timeout costs one plan priced without stock, and `NO_REPLY` says so on that plan.
     */
    static final long REPLY_TIMEOUT_MILLIS = 5_000L;

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
        // BOTH READERS OFF THE ONE SNAPSHOT, AND INSTALLED TOGETHER. #320's placed machines
        // come back in the same reply as the items, so a second install site would be a second
        // thing to remember and the failure would be #191's exactly: the machines arriving,
        // being stored, and every machine still pricing as buildable.
        ScenarioSource.PLACED.readBy(new ScenarioSource.Reader() {
            @Override
            public ScenarioSource.Status status() {
                return placedStatusOf(LiveStock.latest());
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
            // `explanation` AND NOT `message`: this note lands on the detail panel, which sizes to
            // its content, so the reason's advice belongs here. The always-visible line takes the
            // short form through {@link #refusalSentence} -- see `Reason`'s header on why the two
            // strings exist and which surface each is budgeted for.
            return ScenarioSource.Status.unavailable(snapshot.reason().explanation());
        }
        return ScenarioSource.Status.available(snapshot.document());
    }

    /**
     * The same, for {@link ScenarioSource#PLACED}: what the grid's machines mean for a plan.
     *
     * A REFUSAL REPORTS THE REASON AND NEVER AN EMPTY GRID, which is the whole hazard this
     * method exists to avoid. The two are indistinguishable in the data -- both carry no
     * machines -- and they are opposite claims: "your terminal is out of power" is a lower
     * bound the player can act on, while "you own no machines" is a confident falsehood that
     * prices a Pulverizer you are standing next to as something to go and build. Same argument
     * as {@link #statusOf} one field over, and the same reason `StockSnapshot` refuses to have
     * an empty state that means both.
     *
     * THE SENTENCES ARE NOT `statusOf`'s, DELIBERATELY. `ScenarioSource.missingNotes` renders
     * one line per unread source, so two readers answering off one refused snapshot with the
     * same string would print the same sentence twice with nothing to tell the two inputs
     * apart. Each names the consequence for its own field -- own nothing, own no machines --
     * and keeps the action verbatim from the reason.
     */
    static ScenarioSource.Status placedStatusOf(StockSnapshot snapshot) {
        if (snapshot == null) {
            return ScenarioSource.Status.unavailable("your ME network has not been read yet, so"
                    + " no machine counts as owned -- open the planner again to ask");
        }
        if (!snapshot.isAvailable()) {
            return ScenarioSource.Status.unavailable(snapshot.reason().explanation()
                    + ", so no machine counts as owned");
        }
        return ScenarioSource.Status.available(snapshot.placedDocument());
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
        planWhenRead(plan, canAsk());
    }

    /**
     * The same, told whether there is anyone to ask rather than working it out.
     *
     * SPLIT FROM {@link #canAsk} SO A WHOLE PATH THROUGH HERE CAN BE EXERCISED, which
     * {@link #hold} on its own cannot give: `hold` is the decision, and a caller that wants to
     * prove its plan RAN has to get past the ask as well. #201's recovery is exactly that
     * caller -- `PlannerEntry.resumeWhenTheGraphLands` replays a target through this, and the
     * assertion worth making about it is that a plan for that target finished, not that a
     * boolean came back.
     *
     * `LiveStock.request` IS STILL BEHIND `hold`, so nothing here can put a megabyte of item
     * list on the wire that the one-argument form would not have. The only line this hides from
     * a caller is the connection check.
     */
    static void planWhenRead(Runnable plan, boolean canAsk) {
        if (hold(plan, canAsk)) {
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
        return hold(plan, canAsk, System.currentTimeMillis());
    }

    /**
     * The same, told what time it is, so the deadline can be asserted without sleeping.
     *
     * A TEST THAT PROVED THE TIMEOUT BY SLEEPING FIVE SECONDS would be a five-second test that
     * still could not prove the deadline is five seconds rather than four. The clock is an
     * argument for the same reason `canAsk` is one: everything worth getting wrong is on this side
     * of the thing that cannot be reached from a test.
     */
    static boolean hold(Runnable plan, boolean canAsk, long now) {
        if (!canAsk || LiveStock.isFresh()) {
            waiting = null;
            plan.run();
            return false;
        }
        boolean alreadyAsked = waiting != null;
        waiting = plan;
        if (!alreadyAsked) {
            askedAt = now;
        }
        return !alreadyAsked;
    }

    /**
     * Give up on a reply that is not coming, and plan without it. #321 §2
     *
     * Called from the client tick by {@link StockTimeout}. Does nothing at all until a plan has
     * been parked for longer than {@link #REPLY_TIMEOUT_MILLIS}, which on any server running this
     * mod never happens -- see the class note on why "a reply is guaranteed" was false.
     *
     * IT MINTS A REFUSAL RATHER THAN JUST RUNNING THE PLAN, and that ordering is the whole point:
     * `LiveStock.accept` goes FIRST, so by the time the plan runs, `ScenarioSource.HAVE` and
     * `PLACED` both answer `NO_REPLY` and the caveat line carries "the server did not answer --
     * this mod may not be installed on it". Running the plan first would produce the very screen
     * this replaces: a plan priced as though the player owns nothing, with the caveat saying
     * nobody has asked yet, when in fact we asked and were ignored.
     *
     * AND THE REFUSAL IS NOT FRESH, so the next plan asks again. A server that is merely slow
     * therefore recovers on the following plan with no further intervention.
     */
    static boolean releaseIfUnanswered(long now) {
        Runnable plan = waiting;
        if (plan == null || now - askedAt < REPLY_TIMEOUT_MILLIS) {
            return false;
        }
        waiting = null;
        LiveStock.accept(StockSnapshot.unavailable(StockSnapshot.Reason.NO_REPLY));
        plan.run();
        return true;
    }

    /** The tick's entry point. Its own clock, because a tick has no argument to carry one. */
    public static void releaseIfUnanswered() {
        releaseIfUnanswered(System.currentTimeMillis());
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
        askedAt = 0L;
    }

    /**
     * The one short sentence a refused read owes the player, or null. #321 §1
     *
     * <h2>WHY THIS EXISTS SEPARATELY FROM `statusOf`</h2>
     *
     * `statusOf` answers `ScenarioSource.HAVE` and its sentence goes into `missingNotes`, which
     * `PlanCaveats.detailLines` renders on a panel the player has to CLICK to reach. #321's first
     * defect is that the refusal REASON -- the only sentence that says what to go and do -- was
     * behind that click, while the always-visible line said `planned without: have` and nothing
     * else. A player standing at a wired ME Terminal with no wireless terminal on them read
     * "planned without: have" and had no way to know the answer was "pick up your wireless
     * terminal".
     *
     * SO THIS IS THE SHORT FORM, AND SHORT IS A HARD CONSTRAINT RATHER THAN A STYLE. The summary
     * line is wrapped to `PlannerWidgets.MAX_CAVEAT_LINES` and TRUNCATED past it, and the pointer
     * at the detail panel is the half that must never be the casualty (`PlanCaveats.POINTER` says
     * so). WHAT COUNTS AS SHORT IS NOT A CHARACTER COUNT: this sentence is one of four parts of the
     * composed line, the field list among them changes at runtime, and the wrap breaks on spaces so
     * three lines are worth less than three times the column budget. `Reason`'s own header carries
     * the measured proof of that. The only authority is
     * `PlanCaveatsTest.everyRefusalReasonFitsTheCaveatCapWithThePointerIntact`, which composes and
     * wraps the real line for every `Reason`.
     *
     * NULL WHEN THERE IS NOTHING SHORT AND ACTIONABLE TO SAY: nobody has asked yet (whose sentence
     * is long and already the note), the read succeeded, or no reader is installed at all. Only a
     * REFUSAL has a one-line cause, which is why only a refusal gets a line.
     */
    public static String refusalSentence() {
        StockSnapshot.Reason refused = refusedReason();
        return refused == null ? null : refused.message();
    }

    /**
     * The refusal the held snapshot carries, or null when nothing was refused. #331
     *
     * THE REASON RATHER THAN ITS SENTENCE, because a caller can need to tell refusals APART. The
     * only one so far is `PlanCaveats.stockRefusalLine`, which is silent for `NO_AE2`: a pack
     * without Applied Energistics has no ME network to fail to read, so a red line at the top of
     * every plan forever would be noise -- and noise at the top of the panel is how the line that
     * matters gets tuned out, which is `staleGraphWarning`'s own argument for staying silent on
     * `MATCHES`.
     *
     * {@link #refusalSentence} IS THIS PLUS `.message()` AND IS KEPT, rather than every caller
     * doing the null dance. It is the older half of the pair and the one the caveat line's
     * refusal-led form (#321) is written against; two ways to ask the same question is what
     * `PlanCaveats`'s missing `any()` note refuses, and this is not that -- one answers WHICH and
     * one answers WHAT TO SAY, and the second is derived from the first here so they cannot drift.
     */
    public static StockSnapshot.Reason refusedReason() {
        StockSnapshot held = LiveStock.latest();
        if (held == null || held.isAvailable()) {
            return null;
        }
        return held.reason();
    }

    /**
     * True when there is a server to ask.
     *
     * NOT REACHABLE FROM A TEST, which is why {@link #hold} and {@link #planWhenRead(Runnable,
     * boolean)} both take the answer as an argument. MEASURED rather than assumed, 2026-08-08
     * on this workspace's own test classpath: a JUnit case calling `Minecraft.getMinecraft()`
     * dies with `NoClassDefFoundError: org/lwjgl/opengl/OpenGLException` before the method's
     * first line, and so does anything that calls into this. RetroFuturaGradle puts the patched
     * Minecraft on the test classpath and does NOT put LWJGL's natives-bearing half there, so
     * the class links and its initialiser cannot run.
     *
     * The null check on `mc` is still worth its line for the same reason the connection check
     * is -- the screenshot harness runs with a client and no server.
     */
    static boolean canAsk() {
        Minecraft mc = Minecraft.getMinecraft();
        return mc != null && mc.getConnection() != null;
    }
}
