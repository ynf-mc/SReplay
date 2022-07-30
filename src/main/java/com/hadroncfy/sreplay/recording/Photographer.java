package com.hadroncfy.sreplay.recording;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import com.hadroncfy.sreplay.SReplayMod;
import com.hadroncfy.sreplay.config.TextRenderer;
import com.hadroncfy.sreplay.mixin.PlayerManagerAccessor;
import com.hadroncfy.sreplay.mixin.ThreadedAnvilChunkStorageAccessor;
import com.hadroncfy.sreplay.recording.param.OptionManager;
import com.mojang.authlib.GameProfile;

import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.network.MessageType;
import net.minecraft.network.NetworkSide;
import net.minecraft.network.Packet;
import net.minecraft.network.packet.s2c.play.ChunkLoadDistanceS2CPacket;
import net.minecraft.network.packet.s2c.play.PlayerListS2CPacket;
import net.minecraft.network.packet.s2c.play.PlayerRespawnS2CPacket;
import net.minecraft.network.packet.s2c.play.PlayerListS2CPacket.Action;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.ServerTask;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.network.ServerPlayerInteractionManager;
import net.minecraft.server.world.ServerChunkManager;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.LiteralText;
import net.minecraft.text.MutableText;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.ChunkSectionPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.registry.Registry;
import net.minecraft.util.registry.RegistryKey;
import net.minecraft.world.GameMode;
import net.minecraft.world.World;
import net.minecraft.world.biome.source.BiomeAccess;
import net.minecraft.world.dimension.DimensionType;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import static com.hadroncfy.sreplay.config.TextRenderer.render;
import static com.hadroncfy.sreplay.command.SReplayCommand.getSenderUUID;

public class Photographer extends ServerPlayerEntity implements ISizeLimitExceededListener {
    public static final String MCPR = ".mcpr";
    public static final OptionManager PARAM_MANAGER = new OptionManager(RecordingOption.class);
    private static final String RAW_SUBDIR = "raw";
    private static final GameMode MODE = GameMode.SPECTATOR;
    private static final Logger LOGGER = LogManager.getLogger();
    private static final UUID NIL = new UUID(0, 0);
    private final RecordingOption rparam;
    private int reconnectCount = 0;
    private long lastTablistUpdateTime;
    private HackyClientConnection connection;
    private Recorder recorder;
    private final File outputDir;
    private final List<Entity> trackedPlayers = new ArrayList<>();
    private int currentWatchDistance;
    private boolean userPaused = false;

    private String recordingFileName, saveFileName;

    public Photographer(MinecraftServer server, ServerWorld world, GameProfile profile,
            ServerPlayerInteractionManager im, File outputDir, RecordingOption param) {
        super(server, world, profile, im);
        currentWatchDistance = server.getPlayerManager().getViewDistance();
        rparam = param;
        this.outputDir = outputDir;
    }

    public Photographer(MinecraftServer server, ServerWorld world, GameProfile profile,
            ServerPlayerInteractionManager im, File outputDir) {
        this(server, world, profile, im, outputDir, new RecordingOption());
    }

    public static Photographer create(String name, MinecraftServer server, RegistryKey<World> dim, Vec3d pos, File outputDir,
            RecordingOption param) {
        GameProfile profile = new GameProfile(PlayerEntity.getOfflinePlayerUuid(name), name);
        ServerWorld world = server.getWorld(dim);
        ServerPlayerInteractionManager im = new ServerPlayerInteractionManager(world);
        Photographer ret = new Photographer(server, world, profile, im, outputDir, param);
        ret.setPosition(pos.x, pos.y, pos.z);
        ((PlayerManagerAccessor) server.getPlayerManager()).getSaveHandler().savePlayerData(ret);
        return ret;
    }

    public static Photographer create(String name, MinecraftServer server, RegistryKey<World> dim, Vec3d pos,
            File outputDir) {
        return create(name, server, dim, pos, outputDir, RecordingOption
                .createDefaultRecordingParam(SReplayMod.getConfig(), server.getPlayerManager().getViewDistance()));
    }

    private boolean checkForRecordingFileDupe(String name) {
        for (Photographer p : listFakes(getServer())) {
            if (p.saveFileName.equals(name) && p.outputDir.equals(outputDir)) {
                return true;
            }
        }
        return false;
    }

    private String genRecordingFileName(String name) {
        if (!checkForRecordingFileDupe(name)) {
            return name;
        } else {
            int i = 0;
            while (checkForRecordingFileDupe(name + "_" + i++))
                ;
            return name + "_" + i;
        }
    }

    public String getSaveName() {
        return reconnectCount == 0 ? saveFileName : saveFileName + "_" + reconnectCount;
    }

    public void setSaveName(String name) {
        reconnectCount = 0;
        saveFileName = name;
    }

    private static int getChebyshevDistance(ChunkPos pos, int x, int z) {
        int i = pos.x - x;
        int j = pos.z - z;
        return Math.max(Math.abs(i), Math.abs(j));
    }

    private void reloadChunks(int oldDistance, int newDistance) {
        ServerChunkManager chunkManager = getServerWorld().getChunkManager();
        ThreadedAnvilChunkStorageAccessor acc = (ThreadedAnvilChunkStorageAccessor) chunkManager.threadedAnvilChunkStorage;
        ChunkSectionPos pos = this.getWatchedSection();
        int x0 = pos.getSectionX();
        int z0 = pos.getSectionZ();
        int r = oldDistance > newDistance ? oldDistance : newDistance;
        for (int x = x0 - r; x <= x0 + r; x++) {
            for (int z = z0 - r; z <= z0 + r; z++) {
                ChunkPos pos1 = new ChunkPos(x, z);
                int d = getChebyshevDistance(pos1, x0, z0);
                acc.sendWatchPackets2(this, pos1, new Packet[2], d <= oldDistance && d > newDistance,
                        d > oldDistance && d <= newDistance);
            }
        }
    }

    private void connect() throws IOException {
        recordingFileName = genRecordingFileName(getSaveName());

        final File raw = new File(outputDir, RAW_SUBDIR);
        if (!raw.exists()) {
            raw.mkdirs();
        }
        recorder = new Recorder(getGameProfile(), server, this::getWeather, new File(raw, recordingFileName), rparam);
        connection = new HackyClientConnection(NetworkSide.CLIENTBOUND, recorder);

        recorder.setOnSizeLimitExceededListener(this);
        recorder.start();
        lastTablistUpdateTime = System.currentTimeMillis();

        userPaused = false;

        setHealth(20.0F);
        removed = false;
        trackedPlayers.clear();
        server.getPlayerManager().onPlayerConnect(connection, this);
        syncParams();
        interactionManager.setGameMode(MODE);// XXX: is this correct?
        getServerWorld().getChunkManager().updatePosition(this);

        int d = this.server.getPlayerManager().getViewDistance();
        if (d != this.rparam.watchDistance) {
            recorder.onPacket(new ChunkLoadDistanceS2CPacket(this.rparam.watchDistance));
            this.reloadChunks(d, this.rparam.watchDistance);
            this.currentWatchDistance = this.rparam.watchDistance;
        }
    }

    public void syncParams() {
        if (!recorder.isStopped()) {
            recorder.syncParam();
            updatePause();
        }
    }

    public void connect(String saveName) throws IOException {
        reconnectCount = 0;
        saveFileName = saveName;
        connect();
    }

    @Override
    public void tick() {
        if (getServer().getTicks() % 10 == 0) {
            networkHandler.syncWithPlayerPosition();
            getServerWorld().getChunkManager().updatePosition(this);
        }
        super.tick();
        super.playerTick();

        final long now = System.currentTimeMillis();
        if (!recorder.isStopped() && now - lastTablistUpdateTime >= 1000) {
            lastTablistUpdateTime = now;
            if (!recorder.isSoftPaused()) {
                server.getPlayerManager().sendToAll(new PlayerListS2CPacket(Action.UPDATE_DISPLAY_NAME, this));
            }
        }
    }

    private static boolean isRealPlayer(Entity entity) {
        return entity.getClass() == ServerPlayerEntity.class;
    }

    @Override
    public void onStartedTracking(Entity entity) {
        super.onStartedTracking(entity);
        if (isRealPlayer(entity)) {
            trackedPlayers.add(entity);
            updatePause();
        }
    }

    @Override
    public void onStoppedTracking(Entity entity) {
        super.onStoppedTracking(entity);
        if (isRealPlayer(entity)) {
            trackedPlayers.remove(entity);
            updatePause();
        }
    }

    private void updatePause() {
        if (!recorder.isStopped()) {
            if (userPaused) {
                recorder.pauseRecording();
            } else if (rparam.autoPause) {
                final String name = getGameProfile().getName();
                if (trackedPlayers.isEmpty() && !recorder.isRecordingPaused()) {
                    recorder.pauseRecording();
                    server.getPlayerManager().broadcastChatMessage(render(SReplayMod.getFormats().autoPaused, name), MessageType.CHAT, NIL);
                }
                if (!trackedPlayers.isEmpty() && recorder.isRecordingPaused()) {
                    recorder.resumeRecording();
                    server.getPlayerManager().broadcastChatMessage(render(SReplayMod.getFormats().autoResumed, name), MessageType.CHAT, NIL);
                }
            } else {
                recorder.resumeRecording();
            }
        }
    }

    public void setPaused(boolean paused) {
        userPaused = paused;
        updatePause();
    }

    private static String timeToString(long ms) {
        final long sec = ms % 60;
        ms /= 60;
        final long min = ms % 60;
        ms /= 60;
        final long hour = ms;
        if (hour == 0) {
            return String.format("%d:%02d", min, sec);
        } else {
            return String.format("%d:%02d:%02d", hour, min, sec);
        }
    }

    @Override
    public Text getPlayerListName() {
        if (recorder.isStopped()) {
            return null;
        }
        long duration = recorder.getRecordedTime() / 1000;

        String time = timeToString(duration);
        if (rparam.timeLimit != -1) {
            time += "/" + timeToString(rparam.timeLimit);
        }

        String size = String.format("%.2f", recorder.getRecordedBytes() / 1024F / 1024F) + "M";
        if (rparam.sizeLimit != -1) {
            size += "/" + rparam.sizeLimit + "M";
        }
        MutableText ret = new LiteralText(getGameProfile().getName())
                .setStyle(Style.EMPTY.withItalic(true).withColor(Formatting.AQUA));

        ret.append(new LiteralText(" " + time).setStyle(Style.EMPTY.withItalic(false).withColor(Formatting.GREEN)))
                .append(new LiteralText(" " + size).setStyle(Style.EMPTY.withItalic(false).withColor(Formatting.GREEN)));
        return ret;
    }

    public void tp(RegistryKey<World> dim, double x, double y, double z) {
        if (!this.getServerWorld().getRegistryKey().equals(dim)) {
            ServerWorld oldMonde = server.getWorld(this.getServerWorld().getRegistryKey()), nouveau = server.getWorld(dim);
            oldMonde.removePlayer(this);
            removed = false;
            setWorld(nouveau);
            server.getPlayerManager().sendWorldInfo(this, nouveau);
            interactionManager.setWorld(nouveau);
            networkHandler.sendPacket(new PlayerRespawnS2CPacket(nouveau.getDimension(), dim, BiomeAccess.hashSeed(nouveau.getSeed()), this.interactionManager.getGameMode(), this.interactionManager.getPreviousGameMode(), nouveau.isDebugWorld(), nouveau.isFlat(), true));
            nouveau.onPlayerChangeDimension(this);
        }
        requestTeleport(x, y, z);
    }

    public Recorder getRecorder() {
        return recorder;
    }

    @Override
    public void kill() {
        kill(true);
    }

    private void postKill() {
        if (networkHandler != null) {
            networkHandler.onDisconnected(new LiteralText("Killed"));
        }
    }

    public CompletableFuture<Void> kill(boolean async) {
        recorder.stop();
        if (!recorder.hasSaved()) {
            final File saveFile = new File(outputDir, getSaveName() + MCPR);
            CompletableFuture<Void> f = recorder
                    .saveRecording(saveFile, new RecordingSaveProgressBar(server, saveFile.getName())).thenRun(() -> {
                        server.getPlayerManager()
                                .broadcastChatMessage(TextRenderer.render(SReplayMod.getFormats().savedRecordingFile,
                                        getGameProfile().getName(), saveFile.getName()), MessageType.CHAT, NIL);
                    }).exceptionally(exception -> {
                        exception.printStackTrace();
                        server.getPlayerManager().broadcastChatMessage(
                                TextRenderer.render(SReplayMod.getFormats().failedToSaveRecordingFile,
                                        getGameProfile().getName(), exception.toString()),
                                MessageType.CHAT, NIL);
                        return null;
                    });
            if (!async) {
                f.join();
            }
        }
        return CompletableFuture.runAsync(this::postKill, async ? this::executeServerTask : this::executeNow);
    }

    @Override
    public boolean isDisconnected() {
        return false;
    }

    public void onSoftPause() {
        if (!recorder.isStopped()) {
            recorder.setSoftPaused();
        }
    }

    public void reconnect() {
        kill(true).thenRun(() -> {
            reconnectCount++;
            try {
                connect();
            } catch (IOException e) {
                server.getPlayerManager().broadcastChatMessage(
                        TextRenderer.render(SReplayMod.getFormats().failedToStartRecording, getGameProfile().getName()),
                        MessageType.CHAT, NIL);
                e.printStackTrace();
            }
        });
    }

    private void executeServerTask(Runnable r) {
        server.send(new ServerTask(server.getTicks(), r));
    }

    private void executeNow(Runnable r) {
        r.run();
    }

    @Override
    public void onSizeLimitExceeded(long size) {
        if (rparam.autoReconnect) {
            executeServerTask(this::reconnect);
        } else {
            executeServerTask(this::kill);
        }
    }

    public RecordingOption getRecordingParam() {
        return rparam;
    }

    public boolean isItemDisabled(){
        return this.rparam.ignoreItems;
    }

    public static void killAllFakes(MinecraftServer server, boolean async) {
        listFakes(server).forEach(p -> p.kill(async));
    }

    public static Collection<Photographer> listFakes(MinecraftServer server) {
        Collection<Photographer> ret = new ArrayList<>();
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            if (player instanceof Photographer) {
                ret.add((Photographer) player);
            }
        }
        return ret;
    }

    public static Photographer getFake(MinecraftServer server, String name) {
        ServerPlayerEntity player = server.getPlayerManager().getPlayer(name);
        if (player instanceof Photographer) {
            return (Photographer) player;
        }
        return null;
    }

    public static boolean checkForSaveFileDupe(MinecraftServer server, File outputDir, String name) {
        if (new File(outputDir, name + MCPR).exists()) {
            return true;
        }
        for (Photographer p : listFakes(server)) {
            if (p.outputDir.equals(outputDir) && p.getSaveName().equals(name)) {
                return true;
            }
        }
        return false;
    }

    public static int getRealViewDistance(ServerPlayerEntity player, int watchDistance) {
        if (player instanceof Photographer) {
            return ((Photographer) player).currentWatchDistance;
        } else {
            return watchDistance;
        }
    }

    private ForcedWeather getWeather() {
        if (world.isThundering()) {
            return ForcedWeather.THUNDER;
        } else if (world.isRaining()) {
            return ForcedWeather.RAIN;
        }
        return ForcedWeather.CLEAR;
    }
}