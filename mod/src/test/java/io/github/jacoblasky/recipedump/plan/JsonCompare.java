package io.github.jacoblasky.recipedump.plan;

import java.util.Map;
import java.util.TreeSet;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

/**
 * Structural JSON comparison for the golden plan fixtures.
 *
 * NEVER COMPARE THESE FILES AS TEXT. Measured across the fixture set, 1,966 of 6,012 doubles
 * format differently between Python's `repr()` and Java's `Double.toString()` and ZERO of them
 * differ in value -- `1e+22` against `1.0E22`, `0.1` against `0.1`, trailing zeroes appearing
 * and vanishing. A textual diff therefore fails about a third of the time on a completely
 * correct port, and the natural response to that is to "fix" working code until the text
 * matches, which is how a port acquires a bug that the fixtures then freeze.
 *
 * Three rules, each of which a lazier comparison would get wrong:
 *
 *   NUMBERS compare numerically, under exact `==` on the double value. Not with a tolerance:
 *   the fixtures are a contract, and a port that is close is a port that is wrong.
 *
 *   ARRAY ORDER MATTERS. Five of the result's lists are `Counter.most_common()` output and
 *   that order is the contract; a set-like comparison would pass a `HashMap`-backed port.
 *
 *   OBJECT KEY ORDER DOES NOT. JSON objects are unordered, gson emits in insertion order, and
 *   Python's dict order has nothing to do with either. Only the lists carry meaning.
 *
 * An ABSENT key is not a null one, because Python omits rather than nulls and the fixtures
 * freeze which keys are present.
 */
final class JsonCompare {

    private JsonCompare() {
    }

    static boolean equal(JsonElement expected, JsonElement actual) {
        return describe(expected, actual) == null;
    }

    /**
     * Null when the two agree, otherwise a one-line reason NAMING THE PATH.
     *
     * The path is the whole value of this over `assertEquals` on two 12,000-line strings: the
     * largest fixture in the set is `machines.json` at 12,888 lines, and "expected ... but
     * was ..." on that is unreadable. `tree.children[0].need: 4 != 5` is a minute's work.
     */
    static String describe(JsonElement expected, JsonElement actual) {
        return compare("", expected, actual);
    }

    private static String compare(String path, JsonElement expected, JsonElement actual) {
        if (expected == null || actual == null || expected.isJsonNull() || actual.isJsonNull()) {
            boolean bothNull = (expected == null || expected.isJsonNull())
                    && (actual == null || actual.isJsonNull());
            return bothNull ? null : at(path, render(expected), render(actual));
        }
        if (expected.isJsonObject() && actual.isJsonObject()) {
            return compareObjects(path, expected.getAsJsonObject(), actual.getAsJsonObject());
        }
        if (expected.isJsonArray() && actual.isJsonArray()) {
            return compareArrays(path, expected.getAsJsonArray(), actual.getAsJsonArray());
        }
        if (expected.isJsonPrimitive() && actual.isJsonPrimitive()) {
            return comparePrimitives(path, expected.getAsJsonPrimitive(),
                    actual.getAsJsonPrimitive());
        }
        return at(path, render(expected), render(actual));
    }

    private static String compareObjects(String path, JsonObject expected, JsonObject actual) {
        // Sorted so a fixture with several differences reports the same one every run; an
        // unstable message turns one failure into what looks like several.
        TreeSet<String> names = new TreeSet<String>();
        for (Map.Entry<String, JsonElement> entry : expected.entrySet()) {
            names.add(entry.getKey());
        }
        for (Map.Entry<String, JsonElement> entry : actual.entrySet()) {
            names.add(entry.getKey());
        }
        for (String name : names) {
            String child = path.isEmpty() ? name : path + "." + name;
            if (!expected.has(name)) {
                return at(child, "<absent>", render(actual.get(name)));
            }
            if (!actual.has(name)) {
                return at(child, render(expected.get(name)), "<absent>");
            }
            String why = compare(child, expected.get(name), actual.get(name));
            if (why != null) {
                return why;
            }
        }
        return null;
    }

    private static String compareArrays(String path, JsonArray expected, JsonArray actual) {
        if (expected.size() != actual.size()) {
            return at(path, expected.size() + " entries", actual.size() + " entries");
        }
        for (int i = 0; i < expected.size(); i++) {
            String why = compare(path + "[" + i + "]", expected.get(i), actual.get(i));
            if (why != null) {
                return why;
            }
        }
        return null;
    }

    private static String comparePrimitives(String path, JsonPrimitive expected,
                                            JsonPrimitive actual) {
        if (expected.isNumber() && actual.isNumber()) {
            double a = expected.getAsDouble();
            double b = actual.getAsDouble();
            // `Double.compare` rather than `==`, so two infinities agree and so NaN does not
            // silently equal itself out of the comparison. Exact, with no tolerance.
            return Double.compare(a, b) == 0 ? null : at(path, render(expected), render(actual));
        }
        if (expected.isBoolean() && actual.isBoolean()) {
            return expected.getAsBoolean() == actual.getAsBoolean()
                    ? null : at(path, render(expected), render(actual));
        }
        if (expected.isString() && actual.isString()) {
            return expected.getAsString().equals(actual.getAsString())
                    ? null : at(path, render(expected), render(actual));
        }
        // A number against a string is a type mismatch and never a formatting difference.
        return at(path, render(expected), render(actual));
    }

    private static String at(String path, String expected, String actual) {
        return (path.isEmpty() ? "<root>" : path) + ": expected " + expected
                + " but was " + actual;
    }

    private static String render(JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return "null";
        }
        if (element.isJsonObject()) {
            return "an object";
        }
        if (element.isJsonArray()) {
            return element.getAsJsonArray().size() + " entries";
        }
        return element.toString();
    }
}
