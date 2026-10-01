package com.example.maceupgrade;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.enchantment.EnchantItemEvent;
import org.bukkit.event.enchantment.PrepareItemEnchantEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

public class MaceUpgrade extends JavaPlugin implements Listener {

    // Index = number of kills (0 to 5). Kills above 5 stay at the level for 5.
    //                                    kills: 0  1  2  3  4  5
    private static final int[] DENSITY_LEVEL = {0, 1, 2, 3, 4, 5};
    private static final int[] WIND_BURST_LEVEL = {0, 0, 0, 1, 2, 2};

    // Shockwave settings (change these to tune it)
    private static final int SHOCKWAVE_KILLS = 3;        // kills needed to unlock
    // Gets stronger with every level.        kills: 0    1    2    3    4    5
    private static final double[] SHOCKWAVE_RADIUS   = {0,   0,   0,   3.5, 4.5, 6.0}; // blocks
    private static final double[] SHOCKWAVE_STRENGTH = {0,   0,   0,   1.1, 1.6, 2.4}; // sideways push
    private static final double[] SHOCKWAVE_LIFT     = {0,   0,   0,   0.4, 0.5, 0.8}; // upward push
    private static final long SHOCKWAVE_COOLDOWN_MS = 1500;

    // Sword ability settings (change these to tune them)
    private static final double SONIC_BOOM_RANGE = 15.0;      // blocks
    private static final double SONIC_BOOM_DAMAGE = 6.0;      // 6 = 3 hearts (ignores armor and Protection)
    // Cooldown of each ability, in seconds
    private static final int SONIC_BOOM_COOLDOWN_SECONDS = 12;
    private static final int STRENGTH_COOLDOWN_SECONDS = 15;
    private static final int LIFE_DRAIN_COOLDOWN_SECONDS = 25;
    private static final int VANISH_COOLDOWN_SECONDS = 30;
    private static final int CHAOS_COOLDOWN_SECONDS = 15;   // also used for the 4 mini abilities if an operator picks one directly

    // Pure Strength settings
    private static final int STRENGTH_AMPLIFIER = 2;          // 2 = Strength III
    private static final int STRENGTH_SECONDS = 5;

    // Life Drain settings
    private static final double LIFE_DRAIN_RADIUS = 10.0;      // blocks
    private static final double LIFE_DRAIN_DAMAGE = 6.0;       // 6 = 3 hearts taken from each target
    private static final double LIFE_DRAIN_MAX_GOLDEN = 20.0;  // max golden hearts you can get from extra healing (20 = 10 hearts)

    // Vanish settings
    private static final int VANISH_SECONDS = 10;
    private static final EquipmentSlot[] VANISH_SLOTS = {
            EquipmentSlot.HAND, EquipmentSlot.OFF_HAND,
            EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET
    };

    // Chaos Surge (ability 5) picks one of 4 smaller abilities at random.
    // Mini ability: Gravity Pull
    private static final double GRAVITY_PULL_RADIUS = 8.0;     // blocks
    private static final double GRAVITY_PULL_STRENGTH = 1.6;   // how hard they are yanked toward you
    private static final double GRAVITY_PULL_LIFT = 0.3;       // small upward lift so they slide in easily

    // Mini ability: Lightning Strike (hits where you are aiming)
    private static final double LIGHTNING_RANGE = 20.0;        // how far you can aim
    private static final double LIGHTNING_RADIUS = 3.0;        // blocks around the strike
    private static final double LIGHTNING_DAMAGE = 6.0;        // 6 = 3 hearts

    // Mini ability: Flame Wave
    private static final double FLAME_WAVE_RADIUS = 5.0;       // blocks
    private static final int FLAME_WAVE_SECONDS = 5;           // how long they burn

    // Mini ability: Frost Nova
    private static final double FROST_NOVA_RADIUS = 6.0;       // blocks
    private static final int FROST_NOVA_SECONDS = 3;           // how long they are slowed
    private static final int FROST_NOVA_AMPLIFIER = 2;         // 2 = Slowness III

    // Abilities picked at random on right-click
    private static final String[] MAIN_ABILITIES = {"sonicboom", "strength", "lifedrain", "vanish", "chaos"};

    // Every ability an operator can choose with /swordability
    private static final List<String> ALL_ABILITIES = List.of(
            "sonicboom", "strength", "lifedrain", "vanish", "chaos",
            "gravitypull", "lightning", "flamewave", "frostnova");

    private NamespacedKey abilityKey;
    private NamespacedKey playerAbilityKey;
    private NamespacedKey killsKey;
    private final Map<UUID, Long> lastShockwave = new HashMap<>();
    private final Map<UUID, Long> lastAbility = new HashMap<>();
    private final Map<UUID, BukkitTask> vanishTasks = new HashMap<>();

    @Override
    public void onEnable() {
        killsKey = new NamespacedKey(this, "mace_kills");
        abilityKey = new NamespacedKey(this, "chosen_ability");
        playerAbilityKey = new NamespacedKey(this, "player_ability");
        getServer().getCommandMap().register("maceupgrade", new SwordAbilityCommand());
        getServer().getPluginManager().registerEvents(this, this);
    }

    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player killer = event.getEntity().getKiller();
        if (killer == null) return;

        ItemStack mace = killer.getInventory().getItemInMainHand();
        if (mace.getType() != Material.MACE) return;

        ItemMeta meta = mace.getItemMeta();
        if (meta == null) return;

        // Kill count is saved on the mace itself
        PersistentDataContainer data = meta.getPersistentDataContainer();
        int kills = data.getOrDefault(killsKey, PersistentDataType.INTEGER, 0) + 1;
        data.set(killsKey, PersistentDataType.INTEGER, kills);

        int index = Math.min(kills, DENSITY_LEVEL.length - 1);
        applyLevel(meta, Enchantment.DENSITY, DENSITY_LEVEL[index]);
        applyLevel(meta, Enchantment.WIND_BURST, WIND_BURST_LEVEL[index]);
        applyLevel(meta, Enchantment.MENDING, 1);
        applyLevel(meta, Enchantment.UNBREAKING, 3);

        mace.setItemMeta(meta);

        killer.sendMessage("Your mace now has " + kills + " kill" + (kills == 1 ? "" : "s")
                + " (Density " + DENSITY_LEVEL[index]
                + (WIND_BURST_LEVEL[index] > 0 ? ", Wind Burst " + WIND_BURST_LEVEL[index] : "")
                + ")");

        if (kills == SHOCKWAVE_KILLS) {
            killer.sendMessage("Shockwave unlocked! Your hits now knock nearby players back.");
        }
    }

    @EventHandler
    public void onHit(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player attacker)) return;

        ItemStack mace = attacker.getInventory().getItemInMainHand();
        if (mace.getType() != Material.MACE) return;

        ItemMeta meta = mace.getItemMeta();
        if (meta == null) return;

        int kills = meta.getPersistentDataContainer()
                .getOrDefault(killsKey, PersistentDataType.INTEGER, 0);
        if (kills < SHOCKWAVE_KILLS) return;

        int level = Math.min(kills, SHOCKWAVE_RADIUS.length - 1);

        // Cooldown so it can't be spammed
        long now = System.currentTimeMillis();
        Long last = lastShockwave.get(attacker.getUniqueId());
        if (last != null && now - last < SHOCKWAVE_COOLDOWN_MS) return;
        lastShockwave.put(attacker.getUniqueId(), now);

        Location center = event.getEntity().getLocation();
        World world = center.getWorld();

        world.spawnParticle(Particle.EXPLOSION, center.clone().add(0, 1, 0), 1);
        world.playSound(center, Sound.ENTITY_GENERIC_EXPLODE, 1.0f, 1.2f);

        for (LivingEntity target : world.getNearbyLivingEntities(center, SHOCKWAVE_RADIUS[level])) {
            // Skip the person swinging and the one who was hit (they already get normal knockback)
            if (target.equals(attacker) || target.equals(event.getEntity())) continue;

            Vector push = target.getLocation().toVector().subtract(center.toVector());
            push.setY(0);
            if (push.lengthSquared() < 0.0001) continue;

            push.normalize().multiply(SHOCKWAVE_STRENGTH[level]).setY(SHOCKWAVE_LIFT[level]);
            target.setVelocity(push);
        }
    }

    // ---- Sword abilities: right-click uses a random ability ----

    @EventHandler
    public void onSwordRightClick(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        if (event.getAction() != Action.RIGHT_CLICK_AIR
                && event.getAction() != Action.RIGHT_CLICK_BLOCK) return;

        ItemStack item = event.getItem();
        if (item == null || !item.getType().name().endsWith("_SWORD")) return;

        Player player = event.getPlayer();

        // An operator can override with /swordability. Otherwise use the ability this player has.
        String chosen = player.getPersistentDataContainer().get(abilityKey, PersistentDataType.STRING);
        if (chosen == null || !ALL_ABILITIES.contains(chosen)) {
            chosen = getOrAssignPlayerAbility(player);
        }

        // Each ability has its own cooldown
        long cooldown = abilityCooldownMs(chosen);
        long now = System.currentTimeMillis();
        Long last = lastAbility.get(player.getUniqueId());
        if (last != null && now - last < cooldown) {
            long secondsLeft = (cooldown - (now - last) + 999) / 1000;
            player.sendActionBar(Component.text("Ability ready in " + secondsLeft + "s"));
            return;
        }
        lastAbility.put(player.getUniqueId(), now);

        useAbility(player, chosen);
    }

    // The first sword a player crafts gives them a random ability
    @EventHandler
    public void onCraftSword(CraftItemEvent event) {
        ItemStack result = event.getCurrentItem();
        if (result == null || !result.getType().name().endsWith("_SWORD")) return;
        if (!(event.getWhoClicked() instanceof Player player)) return;

        getOrAssignPlayerAbility(player);
    }

    // Reads the ability saved on the player. If they have none yet, they get a
    // random one now and keep it for every sword they ever use (stone, diamond, netherite...).
    private String getOrAssignPlayerAbility(Player player) {
        PersistentDataContainer data = player.getPersistentDataContainer();
        String ability = data.get(playerAbilityKey, PersistentDataType.STRING);
        if (ability != null && ALL_ABILITIES.contains(ability)) {
            return ability;
        }

        ability = MAIN_ABILITIES[ThreadLocalRandom.current().nextInt(MAIN_ABILITIES.length)];
        data.set(playerAbilityKey, PersistentDataType.STRING, ability);
        player.sendMessage("Your sword ability is: " + abilityDisplayName(ability)
                + "! You keep it for every sword you use.");
        return ability;
    }

    private String abilityDisplayName(String ability) {
        return switch (ability) {
            case "sonicboom" -> "Sonic Boom";
            case "strength" -> "Pure Strength";
            case "lifedrain" -> "Life Drain";
            case "vanish" -> "Vanish";
            case "chaos" -> "Chaos Surge";
            default -> ability;
        };
    }

    // How long an ability has to recharge, in milliseconds
    private long abilityCooldownMs(String ability) {
        int seconds = switch (ability) {
            case "sonicboom" -> SONIC_BOOM_COOLDOWN_SECONDS;
            case "strength" -> STRENGTH_COOLDOWN_SECONDS;
            case "lifedrain" -> LIFE_DRAIN_COOLDOWN_SECONDS;
            case "vanish" -> VANISH_COOLDOWN_SECONDS;
            default -> CHAOS_COOLDOWN_SECONDS;
        };
        return seconds * 1000L;
    }

    // Runs an ability by its name
    private void useAbility(Player player, String name) {
        switch (name) {
            case "sonicboom" -> sonicBoom(player);
            case "strength" -> pureStrength(player);
            case "lifedrain" -> lifeDrain(player);
            case "vanish" -> vanish(player);
            case "chaos" -> chaosSurge(player);
            case "gravitypull" -> {
                player.sendMessage("Gravity Pull!");
                gravityPull(player, GRAVITY_PULL_RADIUS);
            }
            case "lightning" -> {
                player.sendMessage("Lightning Strike!");
                lightningStrike(player);
            }
            case "flamewave" -> {
                player.sendMessage("Flame Wave!");
                flameWave(player);
            }
            case "frostnova" -> {
                player.sendMessage("Frost Nova!");
                frostNova(player);
            }
        }
    }

    // Ability 1: Warden-style sonic boom
    private void sonicBoom(Player player) {
        player.sendMessage("Sonic Boom!");

        Location start = player.getEyeLocation();
        Vector direction = start.getDirection().normalize();
        World world = player.getWorld();

        world.playSound(start, Sound.ENTITY_WARDEN_SONIC_BOOM, 1.0f, 1.0f);

        Set<UUID> alreadyHit = new HashSet<>();

        // Same damage type as the Warden's sonic boom: ignores armor and enchantments like Protection
        DamageSource boomSource = DamageSource.builder(DamageType.SONIC_BOOM)
                .withCausingEntity(player)
                .withDirectEntity(player)
                .build();

        for (double distance = 1.0; distance <= SONIC_BOOM_RANGE; distance += 1.0) {
            Location point = start.clone().add(direction.clone().multiply(distance));

            // Stop when the beam hits a wall
            if (!point.getBlock().isPassable()) break;

            world.spawnParticle(Particle.SONIC_BOOM, point, 1, 0, 0, 0, 0);

            for (LivingEntity target : world.getNearbyLivingEntities(point, 1.0)) {
                if (target.equals(player)) continue;
                if (!alreadyHit.add(target.getUniqueId())) continue;

                target.damage(SONIC_BOOM_DAMAGE, boomSource);
                target.setVelocity(direction.clone().multiply(1.0).setY(0.3));
            }
        }
    }

    // Ability 2: Pure Strength (Strength III for a few seconds)
    private void pureStrength(Player player) {
        player.sendMessage("Pure Strength!");

        player.addPotionEffect(new PotionEffect(
                PotionEffectType.STRENGTH, STRENGTH_SECONDS * 20, STRENGTH_AMPLIFIER));

        Location center = player.getLocation().add(0, 1, 0);
        player.getWorld().spawnParticle(Particle.ANGRY_VILLAGER, center, 12, 0.5, 0.6, 0.5, 0);
        player.getWorld().playSound(center, Sound.ENTITY_IRON_GOLEM_ATTACK, 1.0f, 0.8f);
    }

    // Ability 3: Life Drain - take hearts from everyone nearby and heal yourself
    private void lifeDrain(Player player) {
        player.sendMessage("Life Drain!");

        Location center = player.getLocation().add(0, 1, 0);
        World world = player.getWorld();

        // Ignores armor and Protection, like the sonic boom
        DamageSource drainSource = DamageSource.builder(DamageType.SONIC_BOOM)
                .withCausingEntity(player)
                .withDirectEntity(player)
                .build();

        double gained = 0;
        for (LivingEntity target : world.getNearbyLivingEntities(center, LIFE_DRAIN_RADIUS)) {
            if (target.equals(player) || target instanceof ArmorStand) continue;
            if (target instanceof Player other
                    && (other.getGameMode() == GameMode.CREATIVE
                        || other.getGameMode() == GameMode.SPECTATOR)) continue;

            gained += Math.min(LIFE_DRAIN_DAMAGE, target.getHealth());
            target.damage(LIFE_DRAIN_DAMAGE, drainSource);
            world.spawnParticle(Particle.DAMAGE_INDICATOR,
                    target.getLocation().add(0, 1, 0), 6, 0.3, 0.4, 0.3, 0);
        }

        world.playSound(center, Sound.ENTITY_EVOKER_CAST_SPELL, 1.0f, 0.8f);

        if (gained <= 0) return;

        // Heal yourself with what was drained; extra turns into golden hearts
        double total = player.getHealth() + gained;
        double newHealth = Math.min(player.getMaxHealth(), total);
        player.setHealth(newHealth);

        double leftover = total - newHealth;
        if (leftover > 0) {
            player.setAbsorptionAmount(
                    Math.min(LIFE_DRAIN_MAX_GOLDEN, player.getAbsorptionAmount() + leftover));
        }

        world.spawnParticle(Particle.HEART, center, 8, 0.5, 0.6, 0.5, 0);
    }

    // Ability 4: Vanish - invisible, and your armor and held items are hidden from other players
    private void vanish(Player player) {
        player.sendMessage("Vanish!");

        // If one is already running, restart it
        BukkitTask old = vanishTasks.remove(player.getUniqueId());
        if (old != null) old.cancel();

        int duration = VANISH_SECONDS * 20;
        player.addPotionEffect(new PotionEffect(
                PotionEffectType.INVISIBILITY, duration, 0, false, false, true));

        Location center = player.getLocation().add(0, 1, 0);
        player.getWorld().spawnParticle(Particle.LARGE_SMOKE, center, 20, 0.4, 0.6, 0.4, 0.02);
        player.getWorld().playSound(center, Sound.ENTITY_ENDERMAN_TELEPORT, 0.7f, 1.4f);

        // Fake "empty" equipment that other players will see
        Map<EquipmentSlot, ItemStack> empty = new EnumMap<>(EquipmentSlot.class);
        for (EquipmentSlot slot : VANISH_SLOTS) {
            empty.put(slot, new ItemStack(Material.AIR));
        }

        // Keep re-sending the empty equipment every 2 ticks so it stays hidden
        BukkitTask task = new BukkitRunnable() {
            int ticksLeft = duration;

            @Override
            public void run() {
                boolean over = ticksLeft <= 0
                        || !player.isOnline()
                        || player.isDead()
                        || !player.hasPotionEffect(PotionEffectType.INVISIBILITY);
                if (over) {
                    cancel();
                    vanishTasks.remove(player.getUniqueId());
                    showEquipment(player);
                    return;
                }

                for (Player viewer : Bukkit.getOnlinePlayers()) {
                    if (viewer.equals(player)) continue;
                    viewer.sendEquipmentChange(player, empty);
                }
                ticksLeft -= 2;
            }
        }.runTaskTimer(this, 1L, 2L);

        vanishTasks.put(player.getUniqueId(), task);
    }

    // Show the player's real armor and items to everyone again
    private void showEquipment(Player player) {
        Map<EquipmentSlot, ItemStack> real = new EnumMap<>(EquipmentSlot.class);
        for (EquipmentSlot slot : VANISH_SLOTS) {
            ItemStack item = player.getInventory().getItem(slot);
            real.put(slot, item == null ? new ItemStack(Material.AIR) : item);
        }
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            if (viewer.equals(player)) continue;
            viewer.sendEquipmentChange(player, real);
        }
    }

    // Ability 5: Chaos Surge - picks one of 4 smaller abilities at random
    private void chaosSurge(Player player) {
        switch (ThreadLocalRandom.current().nextInt(4)) {
            case 0 -> {
                player.sendMessage("Chaos Surge: Gravity Pull!");
                gravityPull(player, GRAVITY_PULL_RADIUS);
            }
            case 1 -> {
                player.sendMessage("Chaos Surge: Lightning Strike!");
                lightningStrike(player);
            }
            case 2 -> {
                player.sendMessage("Chaos Surge: Flame Wave!");
                flameWave(player);
            }
            case 3 -> {
                player.sendMessage("Chaos Surge: Frost Nova!");
                frostNova(player);
            }
        }
    }

    // Mini ability: yank everyone nearby toward you
    private void gravityPull(Player player, double radius) {
        Location center = player.getLocation().add(0, 1, 0);
        World world = player.getWorld();

        for (LivingEntity target : world.getNearbyLivingEntities(center, radius)) {
            if (target.equals(player) || target instanceof ArmorStand) continue;
            if (target instanceof Player other
                    && (other.getGameMode() == GameMode.CREATIVE
                        || other.getGameMode() == GameMode.SPECTATOR)) continue;

            Vector pull = center.toVector().subtract(target.getLocation().toVector());
            if (pull.length() < 1.5) continue; // already right next to you

            pull.normalize().multiply(GRAVITY_PULL_STRENGTH).setY(GRAVITY_PULL_LIFT);
            target.setVelocity(pull);

            world.spawnParticle(Particle.PORTAL,
                    target.getLocation().add(0, 1, 0), 15, 0.3, 0.5, 0.3, 0.5);
        }

        world.spawnParticle(Particle.REVERSE_PORTAL, center, 40, 0.6, 0.8, 0.6, 0.1);
        world.playSound(center, Sound.ENTITY_ENDERMAN_TELEPORT, 1.0f, 0.6f);
    }

    // Mini ability: lightning hits where you are aiming and hurts everyone near it
    private void lightningStrike(Player player) {
        World world = player.getWorld();

        Location strike;
        RayTraceResult hit = player.rayTraceBlocks(LIGHTNING_RANGE);
        if (hit != null && hit.getHitPosition() != null) {
            strike = hit.getHitPosition().toLocation(world);
        } else {
            // Nothing in range: strike the ground at the end of your aim
            Location eye = player.getEyeLocation();
            strike = eye.clone().add(eye.getDirection().multiply(LIGHTNING_RANGE));
            strike.setY(world.getHighestBlockYAt(strike) + 1);
        }

        world.strikeLightningEffect(strike);

        for (LivingEntity target : world.getNearbyLivingEntities(strike, LIGHTNING_RADIUS)) {
            if (!isValidTarget(player, target)) continue;
            target.damage(LIGHTNING_DAMAGE, player);
        }
    }

    // Mini ability: ring of fire that sets everyone nearby on fire
    private void flameWave(Player player) {
        World world = player.getWorld();
        Location center = player.getLocation().add(0, 1, 0);

        for (LivingEntity target : world.getNearbyLivingEntities(center, FLAME_WAVE_RADIUS)) {
            if (!isValidTarget(player, target)) continue;
            target.setFireTicks(FLAME_WAVE_SECONDS * 20);
        }

        spawnRing(world, center, FLAME_WAVE_RADIUS, Particle.FLAME);
        world.playSound(center, Sound.ITEM_FIRECHARGE_USE, 1.0f, 0.8f);
    }

    // Mini ability: ring of ice that slows and freezes everyone nearby
    private void frostNova(Player player) {
        World world = player.getWorld();
        Location center = player.getLocation().add(0, 1, 0);

        for (LivingEntity target : world.getNearbyLivingEntities(center, FROST_NOVA_RADIUS)) {
            if (!isValidTarget(player, target)) continue;
            target.addPotionEffect(new PotionEffect(
                    PotionEffectType.SLOWNESS, FROST_NOVA_SECONDS * 20, FROST_NOVA_AMPLIFIER));
            target.setFreezeTicks(target.getMaxFreezeTicks());
        }

        spawnRing(world, center, FROST_NOVA_RADIUS, Particle.SNOWFLAKE);
        world.playSound(center, Sound.ENTITY_PLAYER_HURT_FREEZE, 1.0f, 0.8f);
    }

    // Who the abilities are allowed to hit
    private boolean isValidTarget(Player player, LivingEntity target) {
        if (target.equals(player) || target instanceof ArmorStand) return false;
        return !(target instanceof Player other
                && (other.getGameMode() == GameMode.CREATIVE
                    || other.getGameMode() == GameMode.SPECTATOR));
    }

    // A circle of particles around a point
    private void spawnRing(World world, Location center, double radius, Particle particle) {
        for (int i = 0; i < 36; i++) {
            double angle = Math.toRadians(i * 10);
            Location point = center.clone().add(Math.cos(angle) * radius, -0.5, Math.sin(angle) * radius);
            world.spawnParticle(particle, point, 2, 0.1, 0.3, 0.1, 0.01);
        }
    }

    // ---- Block other ways of enchanting the mace ----

    // Anvil: no combining two maces, no enchanted books on a mace.
    // Renaming and repairing (e.g. with breeze rods) still work.
    @EventHandler
    public void onPrepareAnvil(PrepareAnvilEvent event) {
        ItemStack first = event.getInventory().getFirstItem();
        ItemStack second = event.getInventory().getSecondItem();
        if (first == null || second == null) return;
        if (first.getType() != Material.MACE) return;

        if (second.getType() == Material.MACE || second.getType() == Material.ENCHANTED_BOOK) {
            event.setResult(null);
        }
    }

    // Enchanting table: no enchant options for maces
    @EventHandler
    public void onPrepareEnchant(PrepareItemEnchantEvent event) {
        if (event.getItem().getType() == Material.MACE) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onEnchant(EnchantItemEvent event) {
        if (event.getItem().getType() == Material.MACE) {
            event.setCancelled(true);
        }
    }

    private void applyLevel(ItemMeta meta, Enchantment enchantment, int level) {
        if (level > 0) {
            meta.addEnchant(enchantment, level, true);
        }
    }

    // ---- /swordability (operators only): override your own sword ability ----

    private class SwordAbilityCommand extends Command {

        SwordAbilityCommand() {
            super("swordability", "Override which ability you use with swords",
                    "/swordability <ability|random>", List.of());
        }

        @Override
        public boolean execute(CommandSender sender, String label, String[] args) {
            if (!sender.isOp()) {
                sender.sendMessage("Only operators can use this command.");
                return true;
            }
            if (!(sender instanceof Player player)) {
                sender.sendMessage("Only players can use this command.");
                return true;
            }
            if (args.length != 1) {
                player.sendMessage("Usage: /swordability <" + String.join("|", ALL_ABILITIES) + "|random>");
                return true;
            }

            String choice = args[0].toLowerCase();
            if (choice.equals("random")) {
                player.getPersistentDataContainer().remove(abilityKey);
                player.sendMessage("Override cleared. You use your own ability again.");
                return true;
            }
            if (!ALL_ABILITIES.contains(choice)) {
                player.sendMessage("Unknown ability. Choose: " + String.join(", ", ALL_ABILITIES) + ", random");
                return true;
            }

            player.getPersistentDataContainer().set(abilityKey, PersistentDataType.STRING, choice);
            player.sendMessage("Your swords now always use: " + choice + " (use /swordability random to go back to your own ability)");
            return true;
        }

        @Override
        public List<String> tabComplete(CommandSender sender, String alias, String[] args) {
            List<String> matches = new ArrayList<>();
            if (!sender.isOp() || args.length != 1) return matches;

            String typed = args[0].toLowerCase();
            for (String name : ALL_ABILITIES) {
                if (name.startsWith(typed)) matches.add(name);
            }
            if ("random".startsWith(typed)) matches.add("random");
            return matches;
        }
    }
}
