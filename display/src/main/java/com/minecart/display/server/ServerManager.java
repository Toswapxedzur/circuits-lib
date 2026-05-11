package com.minecart.display.server;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.files.FileHandle;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Persists a server list to {@code data/servers.json} and runs async TCP "is it alive" pings.
 *
 * <p>{@link #pingAsync(ServerEntry)} updates {@link ServerEntry#status} from a background worker; UI
 * mutations (e.g. relabelling a row) must be marshalled back via {@link com.badlogic.gdx.Application#postRunnable}.
 * The status field itself is {@code volatile} so the render thread always sees the latest value.
 */
public final class ServerManager {

    private static final String PATH = "data/servers.json";
    private static final int PING_TIMEOUT_MS = 1500;
    private static final Type LIST_TYPE = new TypeToken<List<ServerEntry>>() {}.getType();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final ExecutorService executor =
            Executors.newFixedThreadPool(4, r -> {
                Thread t = new Thread(r, "server-ping");
                t.setDaemon(true);
                return t;
            });

    private final List<ServerEntry> servers = new ArrayList<>();

    public ServerManager() {
        load();
    }

    public List<ServerEntry> list() {
        return servers;
    }

    public boolean add(String name, String host, int port) {
        if (name == null || name.isBlank() || host == null || host.isBlank()) return false;
        if (port < 1 || port > 65535) return false;
        servers.add(new ServerEntry(name.trim(), host.trim(), port));
        save();
        return true;
    }

    public void remove(ServerEntry entry) {
        if (servers.remove(entry)) {
            save();
        }
    }

    /**
     * Marks the entry as {@link ServerEntry.Status#CHECKING} immediately, then on the worker thread
     * tries a TCP connect with {@link #PING_TIMEOUT_MS} timeout. Updates status to ACTIVE/INACTIVE.
     */
    public void pingAsync(ServerEntry entry) {
        entry.status = ServerEntry.Status.CHECKING;
        executor.submit(() -> {
            boolean active;
            try (Socket s = new Socket()) {
                s.connect(new InetSocketAddress(entry.host, entry.port), PING_TIMEOUT_MS);
                active = true;
            } catch (Exception ignored) {
                active = false;
            }
            entry.status = active ? ServerEntry.Status.ACTIVE : ServerEntry.Status.INACTIVE;
        });
    }

    public void pingAll() {
        for (ServerEntry e : servers) {
            pingAsync(e);
        }
    }

    public void shutdown() {
        executor.shutdownNow();
    }

    private void load() {
        FileHandle f = Gdx.files.local(PATH);
        if (!f.exists()) return;
        try {
            String json = f.readString("UTF-8");
            List<ServerEntry> parsed = GSON.fromJson(json, LIST_TYPE);
            if (parsed != null) {
                servers.clear();
                for (ServerEntry e : parsed) {
                    if (e == null) continue;
                    e.status = ServerEntry.Status.UNKNOWN;
                    servers.add(e);
                }
            }
        } catch (Exception ex) {
            Gdx.app.error("ServerManager", "Failed to read " + PATH, ex);
        }
    }

    private void save() {
        FileHandle f = Gdx.files.local(PATH);
        try {
            f.parent().mkdirs();
            f.writeString(GSON.toJson(servers, LIST_TYPE), false, "UTF-8");
        } catch (Exception ex) {
            Gdx.app.error("ServerManager", "Failed to write " + PATH, ex);
        }
    }
}
