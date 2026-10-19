package com.purush.freeairtv;

/** One live channel from channels.json. */
final class Channel {
    /** Stable key for settings and "last watched"; survives a change of stream URL. */
    final String id;
    final String name;
    /** Tamil name, shown under the English one; may be empty. */
    final String tamil;
    final String group;
    final String url;

    Channel(String id, String name, String tamil, String group, String url) {
        this.id = id;
        this.name = name;
        this.tamil = tamil;
        this.group = group;
        this.url = url;
    }
}
