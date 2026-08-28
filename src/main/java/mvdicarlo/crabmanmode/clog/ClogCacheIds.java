package mvdicarlo.crabmanmode.clog;

/**
 * Cache enum/struct/param and script ids for the collection log. RuneLite has
 * no named constants for any of these; sources: WikiSync and RuneProfile
 * plugin source, chisel.weirdgloop.org cache browser.
 */
public final class ClogCacheIds {
    /** Enum listing the five top-level tab struct ids (Bosses..Other). */
    public static final int ENUM_TOP_LEVEL_TABS = 2102;

    /** Struct param on a tab: display name (string). */
    public static final int PARAM_TAB_NAME = 682;

    /** Struct param on a tab: enum id listing the tab's page struct ids. */
    public static final int PARAM_TAB_PAGES_ENUM = 683;

    /** Struct param on a page: display name (string), e.g. "Zulrah". */
    public static final int PARAM_PAGE_NAME = 689;

    /** Struct param on a page: enum id whose int values are the page's item ids. */
    public static final int PARAM_PAGE_ITEMS_ENUM = 690;

    /**
     * Enum mapping outdated clog item ids to their canonical replacements
     * (e.g. used satchels to their clog variants). Remove keys, add values.
     */
    public static final int ENUM_ITEM_REPLACEMENTS = 3721;

    /** clientscript fired (post) when the collection log interface is built. */
    public static final int SCRIPT_CLOG_SETUP = 7797;

    /**
     * clientscript fired (pre) once per obtained clog entry after the search
     * toggle is activated; args[1] = item id, args[2] = quantity.
     */
    public static final int SCRIPT_CLOG_TRANSMIT = 4100;

    /** clientscript that resets the collection log view (closes search). */
    public static final int SCRIPT_CLOG_RESET = 2240;

    /** clientscript fired (post) when a collection log page is drawn. */
    public static final int SCRIPT_CLOG_DRAW_LIST = 2731;

    /**
     * Legacy GE search build script used by bronzeman-style plugins; fires
     * alongside ScriptID.GE_ITEM_SEARCH (752). Both are handled, idempotently.
     */
    public static final int SCRIPT_GE_SEARCH_BUILD_LEGACY = 751;

    private ClogCacheIds() {
    }
}
