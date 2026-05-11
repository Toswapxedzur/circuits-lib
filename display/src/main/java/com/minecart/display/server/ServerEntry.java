package com.minecart.display.server;

/**
 * One configured remote server. {@link #status} is volatile because pings update it from a worker
 * thread and reads happen on the render thread.
 */
public final class ServerEntry {

    public enum Status {
        UNKNOWN,
        CHECKING,
        ACTIVE,
        INACTIVE
    }

    public String name;
    public String host;
    public int port;
    public volatile Status status = Status.UNKNOWN;

    public ServerEntry() {}

    public ServerEntry(String name, String host, int port) {
        this.name = name;
        this.host = host;
        this.port = port;
    }

    public String address() {
        return host + ":" + port;
    }
}
