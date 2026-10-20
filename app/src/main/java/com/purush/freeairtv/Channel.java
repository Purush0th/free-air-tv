package com.purush.freeairtv;

/** One live channel from channels.json. */
final class Channel {
    /** Primary key: settings and "last watched" are stored by it, so it never changes or is reused. */
    final String id;
    final String name;
    /** The name in the channel's own language and script, shown under the English one; may be empty. */
    final String locale;
    final String group;
    final String url;
    /** BCP 47 language tag, such as "ta-IN" or "hi-IN"; channels are grouped by it in Settings. */
    final String lang;
    /** False when the channel is soft-deleted ("isActive": false): kept for its id, never shown. */
    final boolean active;

    Channel(String id, String name, String locale, String group, String url, String lang, boolean active) {
        this.id = id;
        this.name = name;
        this.locale = locale;
        this.group = group;
        this.url = url;
        this.lang = lang;
        this.active = active;
    }
}
