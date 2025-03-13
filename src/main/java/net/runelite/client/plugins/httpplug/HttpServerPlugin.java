package net.runelite.client.plugins.httpplug;

import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.reactivex.rxjava3.annotations.Nullable;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.api.Point;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.http.api.RuneLiteAPI;
import org.pf4j.Extension;

import javax.inject.Inject;
import javax.inject.Provides;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicReference;

import static java.lang.Integer.parseInt;
import static net.runelite.api.Perspective.localToCanvas;

@Extension
@PluginDescriptor(
		name = "HTTP Server codeRicky"
)
@Slf4j
public class HttpServerPlugin extends Plugin {

	private static final int DEFAULT_PORT = 8080;
	private static final int MAX_DISTANCE = 1200;

	@Inject
	private Client client;

	@Inject
	private HttpServerConfig config;

	@Inject
	private ClientThread clientThread;

	private HttpServer server;

	@Provides
	private HttpServerConfig provideConfig(ConfigManager configManager) {
		return configManager.getConfig(HttpServerConfig.class);
	}

	@Override
	protected void startUp() throws Exception {
		server = HttpServer.create(new InetSocketAddress(DEFAULT_PORT), 0);
		server.createContext("/events", this::handleEvents);
		server.createContext("/npc", this::handleNPC);
		server.createContext("/objects", this::handleObjects);
		server.createContext("/post", this::handlePosts);
		server.setExecutor(Executors.newSingleThreadExecutor());
		server.start();
	}

	@Override
	protected void shutDown() throws Exception {
		if (server != null) {
			server.stop(1);
		}
	}

	@Subscribe
	public void onGameTick(GameTick tick) {
		// No significant logic here anymore.
	}

	public void handleNPC(HttpExchange exchange) throws IOException {
		JsonObject response = new JsonObject();
		Player player = client.getLocalPlayer();

		if (player == null) {
			sendJsonResponse(exchange, response);
			return;
		}

		int npcIndex = 0;
		for (NPC npc : client.getNpcs()) {
			if (npc != null && player.getLocalLocation().distanceTo(npc.getLocalLocation()) <= MAX_DISTANCE) {
				LocalPoint npcLocation = npc.getLocalLocation();
				Point canvasPoint = localToCanvas(client, npcLocation, player.getWorldLocation().getPlane());

				if (canvasPoint != null && canvasPoint.getX() > 1 && canvasPoint.getX() < MAX_DISTANCE &&
						canvasPoint.getY() > 1 && canvasPoint.getY() < MAX_DISTANCE) {
					String npcName = npc.getName() + "_" + npcIndex;
					String worldCoords = "(" + npc.getWorldLocation().getX() + ", " + npc.getWorldLocation().getY() + ")";
					String canvasCoords = Arrays.toString(new int[]{canvasPoint.getX(), canvasPoint.getY()});

					response.addProperty(npcName, canvasCoords + ", " + worldCoords);
				}
			}
			npcIndex++;
		}
		sendJsonResponse(exchange, response);
	}

	public void handleEvents(HttpExchange exchange) throws IOException {
		JsonObject response = new JsonObject();
		Player player = client.getLocalPlayer();

		if (player == null) {
			sendJsonResponse(exchange, response);
			return;
		}

		Actor interactingNpc = player.getInteracting();
		int npcHealth = 0;

		if (interactingNpc != null) {
			npcHealth = calculateNpcHealth(interactingNpc);
			response.addProperty("npc name", interactingNpc.getName());
			response.addProperty("npc health ", npcHealth);
		} else {
			response.addProperty("npc name", "null");
		}

		response.addProperty("animation", player.getAnimation());
		response.addProperty("animation pose", player.getPoseAnimation());
		response.addProperty("run energy", client.getEnergy());
		response.addProperty("game tick", client.getGameCycle());
		response.addProperty("health", client.getBoostedSkillLevel(Skill.HITPOINTS) + "/" + client.getRealSkillLevel(Skill.HITPOINTS));
		response.addProperty("interacting code", String.valueOf(player.getInteracting()));
		response.addProperty("MAX_DISTANCE", MAX_DISTANCE);

		JsonObject mouse = new JsonObject();
		mouse.addProperty("x", client.getMouseCanvasPosition().getX());
		mouse.addProperty("y", client.getMouseCanvasPosition().getY());
		response.add("mouse", mouse);

		JsonObject worldPoint = new JsonObject();
		WorldPoint worldLocation = player.getWorldLocation();
		worldPoint.addProperty("x", worldLocation.getX());
		worldPoint.addProperty("y", worldLocation.getY());
		worldPoint.addProperty("plane", worldLocation.getPlane());
		worldPoint.addProperty("regionID", worldLocation.getRegionID());
		worldPoint.addProperty("regionX", worldLocation.getRegionX());
		worldPoint.addProperty("regionY", worldLocation.getRegionY());
		response.add("worldPoint", worldPoint);

		JsonObject camera = new JsonObject();
		camera.addProperty("yaw", client.getCameraYaw());
		camera.addProperty("pitch", client.getCameraPitch());
		camera.addProperty("x", client.getCameraX());
		camera.addProperty("y", client.getCameraY());
		camera.addProperty("z", client.getCameraZ());
		camera.addProperty("x2", client.getCameraX2());
		camera.addProperty("y2", client.getCameraY2());
		camera.addProperty("z2", client.getCameraZ2());
		response.add("camera", camera);

		sendJsonResponse(exchange, response);
	}

	private int calculateNpcHealth(Actor npc) {
		int healthScale = npc.getHealthScale();
		int healthRatio = npc.getHealthRatio();

		if (healthRatio <= 0) {
			return 0;
		}

		int minHealth = 1;
		if (healthScale > 1 && healthRatio > 1) {
			minHealth = (healthScale * (healthRatio - 1) + healthScale - 2) / (healthScale - 1);
		}
		int maxHealth = (healthScale * healthRatio - 1) / (healthScale - 1);
		if (maxHealth > healthScale) {
			maxHealth = healthScale;
		}
		return (minHealth + maxHealth + 1) / 2;
	}

	public void handleObjects(HttpExchange exchange) throws IOException {
		JsonObject response = new JsonObject();
		Player player = client.getLocalPlayer();
		Scene scene = client.getScene();

		if (player == null || scene == null) {
			sendJsonResponse(exchange, response);
			return;
		}

		Tile[][][] tiles = scene.getTiles();
		int plane = client.getPlane();

		for (int x = 0; x < Constants.SCENE_SIZE; x++) {
			for (int y = 0; y < Constants.SCENE_SIZE; y++) {
				Tile tile = tiles[plane][x][y];
				if (tile != null) {
					List<String> objectData = renderGameObjects(tile, player);
					if (!objectData.isEmpty()) {
						response.addProperty("objects_" + x + "_" + y, String.valueOf(objectData));
					}
				}
			}
		}
		sendJsonResponse(exchange, response);
	}

	@SneakyThrows