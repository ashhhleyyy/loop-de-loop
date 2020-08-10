package io.github.restioson.loopdeloop.game;

import io.github.restioson.loopdeloop.LoopDeLoop;
import io.github.restioson.loopdeloop.game.map.LoopDeLoopHoop;
import io.github.restioson.loopdeloop.game.map.LoopDeLoopMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import net.gegy1000.plasmid.game.GameWorld;
import net.gegy1000.plasmid.game.event.*;
import net.gegy1000.plasmid.game.player.JoinResult;
import net.gegy1000.plasmid.game.rule.GameRule;
import net.gegy1000.plasmid.game.rule.RuleResult;
import net.gegy1000.plasmid.util.ItemStackBuilder;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.s2c.play.TitleS2CPacket;
import net.minecraft.scoreboard.AbstractTeam;
import net.minecraft.scoreboard.ServerScoreboard;
import net.minecraft.scoreboard.Team;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.LiteralText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;

import javax.annotation.Nullable;
import java.util.*;

public final class LoopDeLoopActive {
    private final GameWorld gameWorld;
    private final LoopDeLoopMap map;
    private final LoopDeLoopConfig config;
    private final Set<ServerPlayerEntity> participants;
    private final List<String> finished;
    private final LoopDeLoopSpawnLogic spawnLogic;
    private final LoopDeLoopTimerBar timerBar = new LoopDeLoopTimerBar();
    private final Object2ObjectMap<ServerPlayerEntity, LoopDeLoopPlayer> player_states;
    @Nullable
    private ServerPlayerEntity lastCompleter;
    @Nullable
    Team team;
    private long closeTime = -1;
    private long finishTime = -1;
    private long startTime = -1;

    private LoopDeLoopActive(GameWorld gameWorld, LoopDeLoopMap map, LoopDeLoopConfig config, Set<ServerPlayerEntity> participants) {
        this.gameWorld = gameWorld;
        this.map = map;
        this.config = config;
        this.participants = new HashSet<>(participants);
        this.finished = new ArrayList<>();
        this.spawnLogic = new LoopDeLoopSpawnLogic(gameWorld, map);
        this.player_states = new Object2ObjectOpenHashMap<>();
    }

    public static void open(GameWorld gameWorld, LoopDeLoopMap map, LoopDeLoopConfig config) {
        Set<ServerPlayerEntity> participants = gameWorld.getPlayers();
        LoopDeLoopActive active = new LoopDeLoopActive(gameWorld, map, config, participants);

        gameWorld.newGame(game -> {
            game.setRule(GameRule.ALLOW_CRAFTING, RuleResult.DENY);
            game.setRule(GameRule.ALLOW_PORTALS, RuleResult.DENY);
            game.setRule(GameRule.ALLOW_PVP, RuleResult.DENY);
            game.setRule(GameRule.BLOCK_DROPS, RuleResult.DENY);
            game.setRule(GameRule.FALL_DAMAGE, RuleResult.DENY);
            game.setRule(GameRule.ENABLE_HUNGER, RuleResult.DENY);

            game.on(GameOpenListener.EVENT, active::onOpen);
            game.on(GameCloseListener.EVENT, active::onClose);

            game.on(OfferPlayerListener.EVENT, player -> JoinResult.ok());
            game.on(PlayerAddListener.EVENT, active::addPlayer);
            game.on(PlayerRemoveListener.EVENT, active::removePlayer);

            game.on(GameTickListener.EVENT, active::tick);

            game.on(PlayerDamageListener.EVENT, active::onPlayerDamage);
            game.on(PlayerDeathListener.EVENT, active::onPlayerDeath);
        });
    }

    private void onOpen() {
        ServerWorld world = this.gameWorld.getWorld();
        ServerScoreboard scoreboard = world.getScoreboard();

        Team oldTeam = scoreboard.getTeam("loopdeloop");
        if (oldTeam != null) {
            scoreboard.removeTeam(oldTeam);
        }

        this.team = scoreboard.addTeam("loopdeloop");
        this.team.setCollisionRule(AbstractTeam.CollisionRule.NEVER);
        for (ServerPlayerEntity player : this.participants) {
            scoreboard.addPlayerToTeam(player.getEntityName(), this.team);
            this.spawnParticipant(player);
        }

        long time = world.getTime();
        this.startTime = time - (time % 20) + (4 * 20) + 19;
        this.finishTime = this.startTime + (this.config.timeLimit * 20);
    }

    private void onClose() {
        this.gameWorld.getWorld().getScoreboard().removeTeam(this.team);
        this.timerBar.close();
    }

    private void addPlayer(ServerPlayerEntity player) {
        if (!this.participants.contains(player)) {
            this.spawnSpectator(player);
        }
        this.timerBar.addPlayer(player);
    }

    private void removePlayer(ServerPlayerEntity player) {
        this.participants.remove(player);
    }

    // thx https://stackoverflow.com/a/6810409/4871468
    private static String ordinal(int i) {
        String[] suffixes = new String[]{"th", "st", "nd", "rd", "th", "th", "th", "th", "th", "th"};
        switch (i % 100) {
            case 11:
            case 12:
            case 13:
                return i + "th";
            default:
                return i + suffixes[i % 10];
        }
    }

    private void tick() {
        ServerWorld world = this.gameWorld.getWorld();
        long time = world.getTime();

        if (this.closeTime > 0) {
            if (time >= this.closeTime) {
                gameWorld.closeWorld();
            }
            return;
        }

        if (this.startTime > time) {
            this.tickStartWaiting(time);
            return;
        }

        if (time > this.finishTime || this.participants.isEmpty()) {
            this.tickEndWaiting(time);
            return;
        }

        this.timerBar.update(this.finishTime - time, config.timeLimit * 20);
        this.tickHoops(time);
    }

    private void tickStartWaiting(long time) {
        float sec_f = (this.startTime - time) / 20.0f;

        if (sec_f > 1) {
            for (ServerPlayerEntity player : this.participants) {
                LoopDeLoopPlayer state = this.player_states.computeIfAbsent(player, p -> new LoopDeLoopPlayer());

                if (state.lastPos == null) {
                    state.lastPos = player.getPos();
                    continue;
                }

                player.teleport(state.lastPos.x, state.lastPos.y, state.lastPos.z);
            }
        }

        int sec = (int) Math.floor(sec_f) - 1;

        if ((this.startTime - time) % 20 == 0) {
            if (sec > 0) {
                this.broadcastTitle(new LiteralText(Integer.toString(sec)).formatted(Formatting.BOLD));
                this.broadcastSound(SoundEvents.BLOCK_NOTE_BLOCK_HARP, 1.0F);
            } else {
                this.broadcastTitle(new LiteralText("Go!").formatted(Formatting.BOLD));
                this.broadcastSound(SoundEvents.BLOCK_NOTE_BLOCK_HARP, 2.0F);
            }
        }
    }

    private void tickEndWaiting(long time) {
        for (ServerPlayerEntity player : this.participants) {
            player.setGameMode(GameMode.SPECTATOR);
        }

        this.closeTime = time + (5 * 20);
        this.broadcastWin();
    }

    private void tickHoops(long time) {
        Iterator<ServerPlayerEntity> iterator = this.participants.iterator();
        while (iterator.hasNext()) {
            ServerPlayerEntity player = iterator.next();
            LoopDeLoopPlayer state = this.player_states.get(player);
            int nextHoop = state.lastHoop + 1;

            if (nextHoop >= this.map.hoops.size()) {
                iterator.remove();
                this.finished.add(player.getEntityName());
                int idx = this.finished.size();
                player.sendMessage(new LiteralText("You finished in " + ordinal(idx) + " place!"), true);
                player.playSound(SoundEvents.ENTITY_PLAYER_LEVELUP, SoundCategory.PLAYERS, 1.0F, 1.0F);
                player.setGameMode(GameMode.SPECTATOR);
                this.lastCompleter = player;
                continue;
            }

            LoopDeLoopHoop hoop = this.map.hoops.get(nextHoop);

            int lastHoopZ;
            int lastHoopX;
            if (state.lastHoop == -1) {
                lastHoopZ = -5;
                lastHoopX = 0;
            } else {
                BlockPos last = this.map.hoops.get(state.lastHoop).centre;
                lastHoopZ = last.getZ();
                lastHoopX = last.getX();
            }

            Vec3d end = player.getPos();

            double yMax = ((double) this.config.maxYVariation / 2) + 30;
            boolean outOfBounds = player.getZ() > hoop.centre.getZ() ||
                    player.getZ() < lastHoopZ - 10 ||
                    player.getY() < 75 - yMax ||
                    player.getY() > 75 + yMax ||
                    player.getY() < 0 ||
                    player.getX() > Math.max(hoop.centre.getX(), lastHoopX) + 30 ||
                    player.getX() < Math.min(hoop.centre.getX(), lastHoopX) - 30;

            if (hoop.intersectsSegment(state.lastPos, end)) {
                player.playSound(SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP, SoundCategory.PLAYERS, 1.0F, 1.0F);
                giveRocket(player, 1);
                state.lastHoop += 1;
            } else if ((time - state.lastFailTp > 5) && outOfBounds) {
                this.failHoop(player, state, time);
            }

            state.lastPos = player.getPos();
        }
    }

    private void failHoop(ServerPlayerEntity player, LoopDeLoopPlayer state, long time) {
        if (time - state.lastFailTp < 5) {
            return;
        }

        state.lastFailTp = time;
        giveRocket(player, 1);

        if (state.lastHoop == -1) {
            this.spawnLogic.spawnPlayer(player);
        } else {
            LoopDeLoopHoop lastHoop = this.map.hoops.get(state.lastHoop);
            Vec3d centre = Vec3d.ofCenter(lastHoop.centre);
            float radius = 2;

            double x = centre.x + MathHelper.nextDouble(player.getRandom(), -radius, radius);
            double y = centre.y + MathHelper.nextFloat(player.getRandom(), -radius, radius);
            double z = centre.z + 2;
            player.teleport(this.gameWorld.getWorld(), x, y, z, 0.0f, 0.0f);
            player.playSound(SoundEvents.ENTITY_VILLAGER_NO, SoundCategory.PLAYERS, 1.0F, 1.0F);
        }
    }

    private void broadcastWin() {
        Text message;

        if (this.finished.isEmpty()) {
            message = new LiteralText("The game ended, but nobody won!").formatted(Formatting.GOLD);
        } else {
            StringBuilder message_builder = new StringBuilder();
            message_builder.append("The game has ended!\n");

            for (int i = 0; i < 3 && i < this.finished.size(); i++) {
                String player = this.finished.get(i);
                message_builder.append("    ");
                message_builder.append(ordinal(i + 1));
                message_builder.append(" place - ");
                message_builder.append(player);
                message_builder.append("\n");
            }

            String message_string = message_builder.toString();
            message = new LiteralText(message_string).formatted(Formatting.GOLD);
        }

        this.broadcastMessage(message);
        this.broadcastSound(SoundEvents.ENTITY_VILLAGER_YES);
    }

    private boolean onPlayerDamage(ServerPlayerEntity player, DamageSource source, float amount) {
        if (source == DamageSource.FLY_INTO_WALL) {
            long time = this.gameWorld.getWorld().getTime();
            this.failHoop(player, this.player_states.computeIfAbsent(player, p -> new LoopDeLoopPlayer()), time);
        }
        return true;
    }

    private boolean onPlayerDeath(ServerPlayerEntity player, DamageSource source) {
        long time = this.gameWorld.getWorld().getTime();
        this.failHoop(player, this.player_states.computeIfAbsent(player, p -> new LoopDeLoopPlayer()), time);
        return true;
    }

    private void spawnParticipant(ServerPlayerEntity player) {
        this.spawnLogic.resetPlayer(player, GameMode.ADVENTURE);
        this.spawnLogic.spawnPlayer(player);

        ItemStack elytra = ItemStackBuilder.of(Items.ELYTRA)
                .setUnbreakable()
                .build();
        player.equipStack(EquipmentSlot.CHEST, elytra);

        giveRocket(player, this.config.startRockets);
    }

    private static void giveRocket(ServerPlayerEntity player, int n) {
        ItemStack rockets = new ItemStack(Items.FIREWORK_ROCKET, n);
        player.inventory.insertStack(rockets);
    }

    private void spawnSpectator(ServerPlayerEntity player) {
        this.spawnLogic.resetPlayer(player, GameMode.SPECTATOR);
        this.spawnLogic.spawnPlayer(player);
    }

    // TODO: extract common broadcast utils into plasmid
    private void broadcastMessage(Text message) {
        for (ServerPlayerEntity player : this.gameWorld.getPlayers()) {
            player.sendMessage(message, false);
        }
    }

    private void broadcastTitle(Text message) {
        for (ServerPlayerEntity player : this.gameWorld.getPlayers()) {
            TitleS2CPacket packet = new TitleS2CPacket(TitleS2CPacket.Action.TITLE, message, 1, 5,  3);
            player.networkHandler.sendPacket(packet);
        }
    }

    private void broadcastSound(SoundEvent sound, float pitch) {
        for (ServerPlayerEntity player : this.gameWorld.getPlayers()) {
            if (player.equals(this.lastCompleter)) {
                continue;
            }
            player.playSound(sound, SoundCategory.PLAYERS, 1.0F, pitch);
        }
    }

    private void broadcastSound(SoundEvent sound) {
        this.broadcastSound(sound, 1.0F);
    }
}
