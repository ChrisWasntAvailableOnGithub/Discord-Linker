package me.lianecx.discordlinker.network.adapters;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import express.http.RequestMethod;
import io.socket.client.AckWithTimeout;
import me.lianecx.discordlinker.DiscordLinker;
import me.lianecx.discordlinker.network.ChatType;
import me.lianecx.discordlinker.network.HasRequiredRoleResponse;
import me.lianecx.discordlinker.network.StatsUpdateEvent;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;

import java.io.IOException;
import java.math.BigInteger;
import java.security.SecureRandom;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

public class AdapterManager {

    private static final DiscordLinker PLUGIN = DiscordLinker.getPlugin();
    private int httpPort;
    private HttpAdapter httpAdapter;
    private WebSocketAdapter webSocketAdapter;

    public AdapterManager(String token, int httpPort) {
        this.httpPort = httpPort;
        webSocketAdapter = new WebSocketAdapter(Collections.singletonMap("token", token));
    }

    public AdapterManager(int httpPort) {
        this.httpPort = httpPort;
        httpAdapter = new HttpAdapter();
    }

    public void setHttpPort(int httpPort) {
        this.httpPort = httpPort;
    }

    public void startAll(Consumer<Boolean> callback) {
        // If adapters are already connected, disconnect them
        if(isWebSocketConnected()) webSocketAdapter.disconnect();
        if(isHttpConnected()) httpAdapter.disconnect();

        if(webSocketAdapter != null) webSocketAdapter.connect(callback);
        else if(httpAdapter != null) httpAdapter.connect(httpPort, callback);
    }

    public void stopAll() {
        if(isWebSocketConnected()) webSocketAdapter.disconnect();
        if(isHttpConnected()) httpAdapter.disconnect();
    }

    public void startHttp() {
        if(isHttpConnected()) httpAdapter.disconnect();
        else httpAdapter = new HttpAdapter();
        httpAdapter.connect(httpPort, bool -> {});

        webSocketAdapter = null;
    }

    public void stopHttp() {
        if(isHttpConnected()) httpAdapter.disconnect();
        httpAdapter = null;
    }

    /**
     * Connects to the websocket server with a verification code.
     * @param code     The verification code to connect with.
     * @param callback The callback to run when the connection is established or fails.
     */
    public void connectWebsocket(String code, Consumer<Boolean> callback) {
        if(isWebSocketConnected()) webSocketAdapter.disconnect();

        //Create random 32-character hex string
        String token = new BigInteger(130, new SecureRandom()).toString(16);
        Map<String, String> auth = new HashMap<>();
        auth.put("code", code);
        auth.put("token", token);

        WebSocketAdapter tempAdapter = new WebSocketAdapter(auth);

        //Set listeners
        tempAdapter.getSocket().on("auth-success", data -> {
            //Code is valid, set the adapter to the new one
            webSocketAdapter = tempAdapter;

            JsonObject dataObject = new JsonParser().parse(data[0].toString()).getAsJsonObject();

            //Save connection data
            JsonObject connJson = new JsonObject();
            connJson.addProperty("protocol", "websocket");
            connJson.addProperty("id", code.split(":")[0]);
            connJson.addProperty("token", token);
            connJson.add("channels", new JsonArray());
            if(dataObject.has("requiredRoleToJoin"))
                connJson.add("requiredRoleToJoin", dataObject.get("requiredRoleToJoin"));

            try {
                PLUGIN.updateConn(connJson);
                callback.accept(true);
            }
            catch(IOException err) {
                PLUGIN.getLogger().info(ChatColor.RED + "Failed to save connection data.");
                err.printStackTrace();

                webSocketAdapter.disconnect();
                startHttp();
                callback.accept(false);
            }
        });
        tempAdapter.connect(connected -> {
            //If connected, the bot will call auth-success above
            if(!connected) {
                tempAdapter.disconnect();
                callback.accept(false);
                //Connect to old websocket if it exists
                if(webSocketAdapter != null) webSocketAdapter.connect(bool -> {});
            }
        });
    }

    public void disconnectForce() {
        if(isWebSocketConnected()) {
            webSocketAdapter.send("disconnect-force", null);
            webSocketAdapter.disconnect();
            startHttp();
        }
        else if(isHttpConnected()) HttpAdapter.send(RequestMethod.GET, "/disconnect-force", JsonNull.INSTANCE);
        //No need to stop (disconnect) http server

        PLUGIN.deleteConn();
    }

    public boolean isWebSocketConnected() {
        return webSocketAdapter != null && webSocketAdapter.getSocket().connected();
    }

    public boolean isHttpConnected() {
        return httpAdapter != null;
    }

    public void sendChat(String message, ChatType type, String player) {
        JsonArray channels = PLUGIN.filterChannels(type);
        if(channels == null || channels.size() == 0) return;

        JsonObject chatJson = new JsonObject();
        chatJson.addProperty("type", type.getKey());
        chatJson.addProperty("player", player);
        chatJson.addProperty("message", ChatColor.stripColor(message));
        chatJson.add("channels", channels);
        chatJson.add("id", DiscordLinker.getConnJson().get("id"));

        if(isWebSocketConnected()) webSocketAdapter.send("chat", chatJson);
        else if(isHttpConnected()) {
            chatJson.add("ip", DiscordLinker.getConnJson().get("ip"));
            HttpAdapter.HttpResponse response = HttpAdapter.send(RequestMethod.POST, "/chat", chatJson);
            if(response == null) return;
            if(response.getStatus() == 403) PLUGIN.deleteConn(); //Bot could not find a valid connection to this server
        }
    }

    public void sendStatsUpdate(StatsUpdateEvent event) {
        JsonArray channels = PLUGIN.filterChannels(event);
        if(channels == null || channels.size() == 0) return;

        JsonObject statsJson = new JsonObject();
        statsJson.addProperty("event", event.getName());
        statsJson.add("channels", channels);
        statsJson.add("id", DiscordLinker.getConnJson().get("id"));
        if(event == StatsUpdateEvent.MEMBERS) statsJson.addProperty("members", Bukkit.getOnlinePlayers().size());

        if(isWebSocketConnected()) webSocketAdapter.send("update-stats-channels", statsJson);
        else if(isHttpConnected()) {
            statsJson.add("ip", DiscordLinker.getConnJson().get("ip"));
            HttpAdapter.HttpResponse response = HttpAdapter.send(RequestMethod.POST, "/update-stats-channels", statsJson);
            if(response == null) return;
            if(response.getStatus() == 403) PLUGIN.deleteConn(); //Bot could not find a valid connection to this server
        }
    }

    public void sendVerificationResponse(String code, UUID uuid) {
        JsonObject verifyJson = new JsonObject();
        verifyJson.addProperty("code", code);
        verifyJson.addProperty("uuid", uuid.toString());

        if(isWebSocketConnected()) webSocketAdapter.send("verify-response", verifyJson);
        else if(isHttpConnected()) HttpAdapter.send(RequestMethod.POST, "/verify/response", verifyJson);
    }

    public void hasRequiredRole(UUID uuid, Consumer<HasRequiredRoleResponse> callback) {
        JsonObject verifyJson = new JsonObject();
        verifyJson.addProperty("uuid", uuid.toString());
        verifyJson.add("id", DiscordLinker.getConnJson().get("id"));
        verifyJson.add("ip", DiscordLinker.getConnJson().get("ip"));

        if(isWebSocketConnected()) {
            webSocketAdapter.send("has-required-role", verifyJson, new AckWithTimeout(5000) {
                @Override
                public void onSuccess(Object... args) {
                    try {
                        JsonObject body = new JsonParser().parse(args[0].toString()).getAsJsonObject();
                        HasRequiredRoleResponse response = HasRequiredRoleResponse.valueOf(body.get("response").getAsString().toUpperCase());
                        callback.accept(response);
                    }
                    catch(Exception e) {
                        callback.accept(HasRequiredRoleResponse.ERROR);
                    }
                }

                @Override
                public void onTimeout() {
                    callback.accept(HasRequiredRoleResponse.ERROR);
                }
            });
        }
        else if(isHttpConnected()) {
            HttpAdapter.HttpResponse httpResponse = HttpAdapter.send(RequestMethod.POST, "/has-required-role", verifyJson);
            if(httpResponse == null || httpResponse.getStatus() >= 500) callback.accept(HasRequiredRoleResponse.ERROR);
            else {
                try {
                    HasRequiredRoleResponse response = HasRequiredRoleResponse.valueOf(httpResponse.getBody().get("response").getAsString().toUpperCase());
                    callback.accept(response);
                }
                catch(Exception e) {
                    callback.accept(HasRequiredRoleResponse.ERROR);
                }
            }
        }
    }

    public void verifyUser(Player player, int code) {
        JsonObject verifyJson = new JsonObject();
        verifyJson.addProperty("code", String.valueOf(code));
        verifyJson.addProperty("uuid", player.getUniqueId().toString());
        verifyJson.addProperty("username", player.getName());

        if(isWebSocketConnected()) webSocketAdapter.send("verify-user", verifyJson);
        else if(isHttpConnected()) HttpAdapter.send(RequestMethod.POST, "/verify-user", verifyJson);
    }
}
