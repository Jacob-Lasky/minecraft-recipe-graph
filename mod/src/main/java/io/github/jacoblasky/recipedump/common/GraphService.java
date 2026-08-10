package io.github.jacoblasky.recipedump.common;

import java.io.File;
import java.io.FileInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;

import io.github.jacoblasky.recipedump.graph.GraphJsonReader;
import io.github.jacoblasky.recipedump.graph.RecipeGraph;

/**
 * Holds the one loaded graph, and loads it without freezing the game.
 *
 * OFF THE MAIN THREAD BECAUSE THE NUMBER SAYS SO. Re-measured with `mod/tools/heap-gate.sh`
 * on Java 8 under a compacting collector: 5.47 s to read the 115.8 MB oracle, 45.3 MB
 * retained. Five seconds of a frozen client is not a slow load, it is a hang -- Forge's
 * watchdog aside, the player cannot tell it from a crash. So the read happens on a daemon
 * thread and the UI asks {@link #progress} what to draw.
 *
 * REPORT PROGRESS, NOT THE ABSENCE OF A PROBLEM. The skill is explicit about this and the
 * dump learned it the hard way: an in-game message reading "the game stays playable" was
 * removed because the player can see the game is running, so the reassurance is noise that
 * reads as an apology for a defect. This says "reading graph.json, 62%", and when there is
 * no graph it says which paths it tried.
 *
 * ONE GRAPH PER PROCESS. There is exactly one pack, so a second copy would be 45 MB spent to
 * hold the same answer. `JeiBridge` already assumes this -- it caches a `StackIndex` against
 * the identity of the graph it was built for -- so a service handing out different instances
 * would silently rebuild that index on every plan.
 *
 * STATE IS READ FROM THE RENDER THREAD AND WRITTEN FROM THE LOADER, so every field crossing
 * that boundary is `volatile`. They are written once each, in the order a reader can rely on:
 * `graph` before the publication below, so a thread that sees READY sees a fully built graph.
 * DO NOT reorder those two assignments; the happens-before is the whole synchronisation here
 * and there is no lock to fall back on.
 *
 * WHAT A TRANSITION IS MADE OF IS PUBLISHED AS ONE OBJECT, not as a run of separate volatile
 * writes, and #291 is why. The state, the counter that says the state is new, and the detail
 * that explains it used to be three assignments in a row. Ordering them `state` last-but-one
 * and `generation++` last bought exactly one direction of the guarantee -- a reader that saw a
 * new NUMBER saw everything behind it -- and left the other direction open: a reader that saw a
 * new STATE could still read the OLD number, because the bump had not happened yet. That is not
 * a theoretical window. A reader spinning on `state()` and reading `generation()` the moment it
 * changed observed the torn pair 739 times in 2,000 loads ON AN IDLE HOST.
 *
 * The single write closes both directions at once: there is no instant at which the state and
 * the counter disagree, because there is no instant at which only one of them has been written.
 * DO NOT split this back into separate fields to save an allocation. It is one small object per
 * TRANSITION -- a handful per session, none per frame -- and every read stays allocation-free.
 *
 * THIS IS THE SECOND TIME THIS REPOSITORY HAS PAID FOR TWO UNORDERED FIELDS, and the first fix
 * is the shape of this one: `client.jei.JeiBridge` holds the graph and its stack index as one
 * object behind one volatile field, because once the index began being built on THIS class's
 * loader thread, two fields let the client thread see the new graph beside the old index and
 * every key resolved -- to the wrong item. Same seam, same thread pair, same answer. The note
 * on {@link #graph} already forbids handing those two out separately; this extends the rule to
 * the fields describing the transition itself.
 */
public final class GraphService {

    /** What the planner can say about the graph right now. */
    public enum State {
        /** No load attempted yet. */
        IDLE,
        /** A load is running; see {@link #progress}. */
        LOADING,
        /** {@link #graph} is usable. */
        READY,
        /** No file at any candidate path. {@link GraphService#detail()} says where it looked. */
        MISSING,
        /** A file was found and could not be read. {@link GraphService#detail()} says why. */
        FAILED
    }

    /**
     * One transition: what the service is saying, why, and the number that says it is new.
     *
     * IMMUTABLE AND REPLACED WHOLE. See the note on the class -- the three used to be separate
     * volatile fields, and a reader could catch a new state beside the previous count. Nothing
     * here may gain a setter: the atomicity IS the finality.
     */
    private static final class Publication {

        final State state;
        /** Why it is MISSING or FAILED, or "" otherwise. */
        final String detail;
        final long generation;

        Publication(State state, String detail, long generation) {
            this.state = state;
            this.detail = detail;
            this.generation = generation;
        }

        /** The next thing to say, one number later. */
        Publication next(State newState, String newDetail) {
            return new Publication(newState, newDetail, generation + 1L);
        }
    }

    private static final GraphService INSTANCE = new GraphService();

    private volatile RecipeGraph graph;
    private volatile Publication published = new Publication(State.IDLE, "", 0L);
    private volatile File source;
    private volatile long totalBytes;
    private volatile long readBytes;
    private Thread loader;

    private GraphService() {
    }

    public static GraphService get() {
        return INSTANCE;
    }

    public State state() {
        return published.state;
    }

    /**
     * How many times this service has had something new to say. Strictly increasing.
     *
     * A COUNTER RATHER THAN A LISTENER, for exactly the reason
     * {@link PlannerService#generation()} gives and NOT by symmetry with it: the subscriber is
     * an open GUI and the publisher is the
     * loader thread, so a callback would rebuild widgets off the client thread, which in 1.12.2
     * surfaces as a ConcurrentModificationException from inside a GUI and gets blamed on the
     * GUI. {@link #onLoad} is the callback that does exist, and it is deliberately not that: it
     * runs ON the loader thread, BEFORE READY is published, for work that must not touch a
     * screen. Anything that has to redraw asks this instead, once per client tick.
     *
     * #201 IS WHAT IT IS FOR, and it is worth saying what went wrong without it. The planner
     * window rebuilds when a counter moves, and the counter it watched was the PLAN's -- so a
     * window opened during the 5.47 s read had nothing to watch, because no plan can be started
     * before the graph lands and the plan counter therefore never moved. Every piece was
     * individually right and the window stayed on "loading" until the player closed it.
     *
     * BUMPED ON EVERY TRANSITION, not only on READY. MISSING and FAILED are different things to
     * draw than "reading graph.json, 62%", and a parse error five seconds in must move the
     * window off a progress bar that is never going to finish.
     *
     * IT MOVES IN THE SAME WRITE AS THE STATE IT DESCRIBES, so a reader that sees a new number
     * sees everything behind it AND a reader that sees a new state sees the number that goes
     * with it. It used to be only the first of those -- the bump was a separate volatile write
     * placed last -- and #291 is the second one going missing: a reader could act on a fresh
     * state with a stale count. See the note on this class for the measurement. DO NOT reopen
     * that gap by bumping this outside a {@link Publication}.
     *
     * STILL BUMPED AFTER {@link #graph}, which is a separate field and stays one: the graph is
     * only meaningful once the state says READY, so the one-directional guarantee is enough
     * there and folding it in would put a 45 MB reference in every transition object.
     */
    public long generation() {
        return published.generation;
    }

    /**
     * The loaded graph, or null unless {@link #state()} is READY.
     *
     * NULL RATHER THAN AN EMPTY GRAPH, because an empty one answers `keyId(...) == -1` for
     * every key, which is indistinguishable from "loaded, and this item is not in it". One
     * hides a JEI menu entry correctly; the other hides it because nothing is loaded yet.
     *
     * THE SAME OBJECT EVERY CALL while the graph is unchanged, and a DIFFERENT one after a
     * reload. That is a contract, not an implementation detail: `JeiBridge.indexOf` rebuilds
     * a key-to-ItemStack index over JEI's ~35,000 stacks whenever the graph differs BY
     * IDENTITY, so a defensive copy or a fresh wrapper here rebuilds it every frame and the
     * planner drops to single-digit fps. Nothing about a getter says "must be identical",
     * which is exactly why `graphIdentityIsStableWhileTheGraphIsUnchanged` pins it.
     *
     * DO NOT ADD AN ACCESSOR FOR JEI'S STACK INDEX BESIDE THIS ONE. It reads as an obvious
     * convenience and it reintroduces a bug that has already been fixed once: the index and
     * the graph it was built for have to be published TOGETHER, and `JeiBridge` now keeps
     * them welded behind a single volatile pair for that reason. Two accessors here would
     * let a caller take a fresh graph and a stale index -- at which point every key resolves,
     * to the WRONG item, which is a plausible icon and a plausible recipe screen rather than
     * a visible failure. If something in `common` ever needs the index, ask for it to be
     * handed over already paired.
     */
    public RecipeGraph graph() {
        return graph;
    }

    /** Why it is MISSING or FAILED, or "" otherwise. Safe to show a player. */
    public String detail() {
        return published.detail;
    }

    public File source() {
        return source;
    }

    /**
     * 0.0 to 1.0 through the file, or -1 when there is nothing to report.
     *
     * BYTES READ, NOT WORK DONE, and the difference is worth being honest about: parsing is
     * not uniform across the file, so the bar moves unevenly and the last few per cent take
     * longer than the first. A smoothed or faked bar would be a nicer lie. The alternative --
     * counting recipes against a total nobody knows until the end -- cannot report anything
     * at all until it is too late to matter.
     */
    public float progress() {
        long total = totalBytes;
        if (published.state != State.LOADING || total <= 0L) {
            return -1.0f;
        }
        return Math.min(1.0f, (float) ((double) readBytes / (double) total));
    }

    /**
     * One line for a player: what is happening, or what went wrong.
     *
     * READ ONCE INTO A LOCAL, not field-by-field. Switching on `published.state` and then
     * reading `published.detail` would be two reads of a field the loader can replace between
     * them, which is the same defect this class was carrying before #291, reintroduced one
     * method down. `RecipeGraph` is read once for the same reason.
     */
    public String describe() {
        Publication now = published;
        switch (now.state) {
            case READY:
                RecipeGraph loaded = graph;
                // Only null if a `reset` landed between the two reads. `reset` is documented as
                // not for use during a load and has no caller outside the tests, so this is a
                // guard against a future one rather than a path anything takes today -- but a
                // planner that NPEs while drawing a status line is a worse answer than a stale
                // one.
                if (loaded == null) {
                    return "no graph loaded";
                }
                return "graph ready: " + loaded.keyCount() + " keys, "
                        + loaded.recipes().count() + " recipes";
            case LOADING:
                float p = progress();
                return p < 0.0f
                        ? "reading " + name()
                        : "reading " + name() + ", " + (int) (p * 100.0f) + "%";
            case MISSING:
                return "no graph.json. " + now.detail;
            case FAILED:
                return "could not read " + name() + ": " + now.detail;
            default:
                return "no graph loaded";
        }
    }

    private String name() {
        File file = source;
        return file == null ? GraphSource.FILE_NAME : file.getName();
    }

    /**
     * Called on the loader thread the moment a graph is installed, before READY is published.
     *
     * WHY A LISTENER AND NOT A DIRECT CALL. The one thing that wants to know today is
     * `client.jei.JeiBridge`, which builds a key-to-`ItemStack` index by walking JEI's whole
     * item list -- about 35,000 stacks. That walk has to happen OFF the render thread, and it
     * has to happen once rather than on the first menu open. But `common` may not name
     * anything under `client`: this class loads on a dedicated server, JEI does not exist
     * there, and `CommonSideSafetyTest` reads the bytes and fails the build for it. So the
     * client installs a listener and the common side calls it without knowing what it is.
     */
    public interface Listener {
        /** `graph` is fully built. Runs on the loader thread; do not touch the world. */
        void graphLoaded(RecipeGraph graph);
    }

    private volatile Listener listener;

    /**
     * Register the one thing to notify when a graph lands. Replaces any previous listener.
     *
     * ONE, not a list. There is one client and one JEI bridge, and a list would be a
     * registration order to reason about plus a way to leak a listener across a reload for a
     * subscriber that does not exist.
     */
    public void onLoad(Listener newListener) {
        this.listener = newListener;
        RecipeGraph loaded = graph;
        // Late registration still gets the callback. `ClientProxy.init` runs after
        // `CommonProxy.preInit` started the load, so on a fast disk the graph can be READY
        // before anything has subscribed -- and a listener that silently missed its one
        // event is a stack index that is never built.
        if (newListener != null && loaded != null && published.state == State.READY) {
            newListener.graphLoaded(loaded);
        }
    }

    /**
     * Start loading, if a load is not already running or finished.
     *
     * IDEMPOTENT, because more than one thing wants a graph -- the JEI keybind, the calculator
     * item, and eventually a server-side plan request -- and none of them should have to know
     * whether it is the first. Two concurrent reads would be 90 MB of transient garbage and
     * two 45 MB graphs, one of which gets dropped.
     *
     * Returns immediately. Callers poll {@link #state()}; nothing here blocks a caller, because
     * the one caller that must never block is the render thread.
     */
    public synchronized void startLoad(File configDir) {
        if (published.state == State.LOADING || published.state == State.READY) {
            return;
        }
        File file = GraphSource.locate(configDir);
        if (file == null) {
            published = published.next(State.MISSING, GraphSource.describeSearch(configDir));
            return;
        }
        source = file;
        totalBytes = file.length();
        readBytes = 0L;
        published = published.next(State.LOADING, "");
        loader = new Thread(new Loader(file), "mcrecipedump-graph-load");
        // A DAEMON, so a half-finished load cannot keep the JVM alive after the player quits.
        // Nothing downstream of a load is durable -- no file is written, no state is
        // published outside this object -- so abandoning one mid-read costs nothing.
        loader.setDaemon(true);
        // BELOW NORMAL. The load is several seconds of solid CPU and allocation on a machine
        // that is also rendering; it is not urgent enough to compete with the frame.
        loader.setPriority(Thread.MIN_PRIORITY);
        loader.start();
    }

    /**
     * Drop the graph and forget how it went, so the next {@link #startLoad} really reloads.
     *
     * IT DOES NOT STOP A LOAD THAT IS ALREADY RUNNING, and calling it during one is a defect
     * waiting to happen rather than a supported thing to do. The loader thread writes `graph`
     * and `published` without this monitor -- that is deliberate and documented on the class,
     * the happens-before is the whole synchronisation here -- so a `reset` mid-read is overwritten
     * seconds later by the load it was meant to cancel, and the service comes back READY with
     * a graph nobody asked for. Nothing in the mod does this today: every caller either runs
     * before a load has started or after one has settled. If a caller ever needs to cancel a
     * running read, this needs a load epoch the loader checks before it publishes, and NOT a
     * `synchronized` on `Loader.run`, which would hold the monitor across a 5.47 s read.
     */
    public synchronized void reset() {
        graph = null;
        source = null;
        totalBytes = 0L;
        readBytes = 0L;
        // FORWARD, NEVER BACK TO ZERO. `PlannerScreen.stamp` sums this with two other
        // counters and relies on every one of them only going UP -- a reset to zero could
        // land the sum back on a value an open window has already recorded as drawn, and the
        // window would then sit on a panel built from a graph that has been dropped. `next`
        // is the only way to write `published` and it can only count up, so that is now
        // structural rather than a rule each writer has to remember.
        published = published.next(State.IDLE, "");
    }

    private final class Loader implements Runnable {

        private final File file;

        Loader(File file) {
            this.file = file;
        }

        @Override
        public void run() {
            InputStream in = null;
            try {
                in = new Counting(new FileInputStream(file));
                RecipeGraph loaded = GraphJsonReader.read(in, file.length());
                // `graph` BEFORE the publication: a reader that sees READY must see the graph.
                // See the note on the class.
                graph = loaded;
                notifyLoaded(loaded);
                // LAST, and as ONE write. `detail` is carried over rather than cleared because
                // `notifyLoaded` may just have put a broken listener's complaint in it, and
                // that is the one thing a successful load still has to say.
                Publication now = published;
                published = new Publication(State.READY, now.detail, now.generation + 1L);
            } catch (IOException e) {
                fail(e);
            } catch (RuntimeException e) {
                // A truncated or hand-edited file surfaces as a gson parse error, not an
                // IOException, and an unhandled throw on a daemon thread would leave the
                // state on LOADING for ever -- a progress bar that never finishes and never
                // says why, which is the worst of the three outcomes.
                fail(e);
            } catch (OutOfMemoryError e) {
                // Named rather than swallowed with the rest: 45 MB retained is fine in a
                // normal client and not in one already at its ceiling, and "out of memory"
                // tells the player to raise -Xmx where "could not read" sends them looking
                // at the file.
                fail(e);
            }
        }

        /**
         * BEFORE `state` GOES READY, and on this thread. The listener's work is the 35,000
         * stack walk described on {@link Listener}; running it after READY would race the
         * first frame that reads the graph, which is the frame this exists to keep fast.
         *
         * A THROWING LISTENER MUST NOT LOSE THE GRAPH. It is client code doing something
         * optional -- an index for a context menu -- and letting it turn a successful 5 s
         * load into FAILED would trade a missing menu entry for no planner at all.
         */
        private void notifyLoaded(RecipeGraph loaded) {
            Listener target = listener;
            if (target == null) {
                return;
            }
            try {
                target.graphLoaded(loaded);
            } catch (Throwable listenerBroke) {
                // NO BUMP. The state has not changed -- this is still the LOADING publication,
                // now carrying a note that `run` will hand on to the READY one. Counting it
                // would tell an open window to rebuild for something it cannot draw yet.
                Publication now = published;
                published = new Publication(now.state,
                        "graph loaded; a listener threw: " + listenerBroke, now.generation);
            }
        }

        private void fail(Throwable e) {
            graph = null;
            // A FAILURE MOVES THE COUNTER TOO, IN THE SAME WRITE AS THE STATE. A window opened
            // during the read is showing a progress bar for a load that has just stopped;
            // without the bump it would show that bar for the rest of the session, which is
            // #201's defect on its other branch. Without the two arriving TOGETHER a window
            // can see FAILED with the count it has already drawn and skip the rebuild, which
            // is #291.
            published = published.next(State.FAILED, e.getClass().getSimpleName()
                    + (e.getMessage() == null ? "" : ": " + e.getMessage()));
        }

        /** Counts bytes as they are consumed, so {@link #progress} has something to report. */
        private final class Counting extends FilterInputStream {

            Counting(InputStream in) {
                super(in);
            }

            @Override
            public int read() throws IOException {
                int b = super.read();
                if (b >= 0) {
                    readBytes++;
                }
                return b;
            }

            @Override
            public int read(byte[] b, int off, int len) throws IOException {
                int n = super.read(b, off, len);
                if (n > 0) {
                    readBytes += n;
                }
                return n;
            }

            @Override
            public long skip(long n) throws IOException {
                long skipped = super.skip(n);
                if (skipped > 0L) {
                    readBytes += skipped;
                }
                return skipped;
            }
        }
    }
}
