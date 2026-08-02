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
 * `graph` before `state`, so a thread that sees READY sees a fully built graph. DO NOT
 * reorder those two assignments; the happens-before is the whole synchronisation here and
 * there is no lock to fall back on.
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
        /** No file at any candidate path. {@link #detail} says where it looked. */
        MISSING,
        /** A file was found and could not be read. {@link #detail} says why. */
        FAILED
    }

    private static final GraphService INSTANCE = new GraphService();

    private volatile RecipeGraph graph;
    private volatile State state = State.IDLE;
    private volatile String detail = "";
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
        return state;
    }

    /**
     * The loaded graph, or null unless {@link #state} is READY.
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
        return detail;
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
        if (state != State.LOADING || total <= 0L) {
            return -1.0f;
        }
        return Math.min(1.0f, (float) ((double) readBytes / (double) total));
    }

    /** One line for a player: what is happening, or what went wrong. */
    public String describe() {
        switch (state) {
            case READY:
                return "graph ready: " + graph.keyCount() + " keys, "
                        + graph.recipes().count() + " recipes";
            case LOADING:
                float p = progress();
                return p < 0.0f
                        ? "reading " + name()
                        : "reading " + name() + ", " + (int) (p * 100.0f) + "%";
            case MISSING:
                return "no graph.json. " + detail;
            case FAILED:
                return "could not read " + name() + ": " + detail;
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
        if (newListener != null && loaded != null && state == State.READY) {
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
     * Returns immediately. Callers poll {@link #state}; nothing here blocks a caller, because
     * the one caller that must never block is the render thread.
     */
    public synchronized void startLoad(File configDir) {
        if (state == State.LOADING || state == State.READY) {
            return;
        }
        File file = GraphSource.locate(configDir);
        if (file == null) {
            state = State.MISSING;
            detail = GraphSource.describeSearch(configDir);
            return;
        }
        source = file;
        totalBytes = file.length();
        readBytes = 0L;
        detail = "";
        state = State.LOADING;
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

    /** Drop the graph and forget how it went, so the next {@link #startLoad} really reloads. */
    public synchronized void reset() {
        state = State.IDLE;
        graph = null;
        detail = "";
        source = null;
        totalBytes = 0L;
        readBytes = 0L;
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
                // `graph` BEFORE `state`: a reader that sees READY must see the graph. See
                // the note on the class.
                graph = loaded;
                notifyLoaded(loaded);
                state = State.READY;
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
                detail = "graph loaded; a listener threw: " + listenerBroke;
            }
        }

        private void fail(Throwable e) {
            graph = null;
            detail = e.getClass().getSimpleName()
                    + (e.getMessage() == null ? "" : ": " + e.getMessage());
            state = State.FAILED;
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
