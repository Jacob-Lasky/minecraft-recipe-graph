package io.github.jacoblasky.recipedump.common.net;

import io.github.jacoblasky.recipedump.common.PlanBook;

/**
 * The edits a client may ask the server to make to its {@link PlanBook}.
 *
 * ONE MESSAGE WITH A VERB rather than three messages, because the server has to re-sync after
 * any of them and a single handler is a single place to get that right.
 *
 * THE IDS ARE A WIRE CONTRACT AND MUST NOT BE RENUMBERED. They are written into a packet the
 * other side decodes by number, so reordering this enum silently turns one edit into another
 * -- a client asking to unstar something would star it instead. Add new verbs at the END with
 * the next free id. `PlanBookMessageTest` pins every number.
 */
public enum PlanBookEdit {

    ADD_FAVOURITE(0),
    REMOVE_FAVOURITE(1),
    /** Sets the wanted quantity. Zero removes the row -- see {@link PlanBook#setTodo}. */
    SET_TODO(2);

    private final int id;

    PlanBookEdit(int id) {
        this.id = id;
    }

    public int id() {
        return id;
    }

    /**
     * @return the verb with this id, or null when there is none.
     *
     * NULL RATHER THAN A THROW, because the caller is a packet decoder reading bytes off the
     * network. An unknown verb means a client on a different mod version, or a malicious one,
     * and neither should be able to take down the handler thread.
     */
    public static PlanBookEdit byId(int id) {
        for (PlanBookEdit edit : values()) {
            if (edit.id == id) {
                return edit;
            }
        }
        return null;
    }

    /** Apply this edit to a book. @return true when the book changed. */
    public boolean applyTo(PlanBook book, String key, long quantity) {
        switch (this) {
            case ADD_FAVOURITE:
                return book.addFavourite(key);
            case REMOVE_FAVOURITE:
                return book.removeFavourite(key);
            case SET_TODO:
                return book.setTodo(key, quantity);
            default:
                return false;
        }
    }
}
