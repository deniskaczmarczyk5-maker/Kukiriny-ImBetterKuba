package pl.imbetterkuba.kukirin;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.*;

public final class KukirinPlugin extends JavaPlugin implements Listener, TabExecutor {

    private static final String PERM = "imbetterkuba.kukirin";
    private static final String TAG = "kukirin_entity";
    private static final int[] MODEL_SLOTS = {10, 11, 12, 13, 14, 15, 16};
    private static final int[] UP_SLOTS = {29, 30, 31, 32};
    private static final int RIDE_SLOT = 40;

    record Model(String id, String name, double maxKmh, double accelKmhS, Material icon, String itemModel) {}
    record Stats(double maxKmh, double accel, double brake, double turn) {}

    enum Up {
        BATTERY("battery", "§eBateria", Material.COPPER_INGOT, "§7+ prędkość maksymalna"),
        CONTROLLER("controller", "§bController", Material.REDSTONE, "§7+ przyspieszenie"),
        DUAL("dual", "§6Dual (2 silniki)", Material.IRON_BLOCK, "§7+ prędkość i przyspieszenie"),
        WHEELIE("wheelie-bar", "§aWheelie bar", Material.STICK, "§7+ hamowanie i skręcanie");

        final String id, name, desc;
        final Material icon;

        Up(String id, String name, Material icon, String desc) {
            this.id = id; this.name = name; this.icon = icon; this.desc = desc;
        }
    }

    static final class Gui implements InventoryHolder {
        Inventory inv;
        @Override public Inventory getInventory() { return inv; }
    }

    static final class Session {
        Player p;
        ArmorStand seat;
        ItemDisplay model;
        TextDisplay dash;
        double speed;      // blocks/tick
        float yaw;
        Scoreboard oldBoard, board;
        Objective obj;
        String lastLine;
    }

    private final Map<String, Model> models = new LinkedHashMap<>();
    private final Map<UUID, Session> sessions = new HashMap<>();
    private NamespacedKey keyModel;

    // ------------------------------------------------------------ lifecycle

    @Override
    public void onEnable() {
        saveDefaultConfig();
        keyModel = new NamespacedKey(this, "model");
        loadModels();
        getCommand("kukirin").setExecutor(this);
        getCommand("kukirin").setTabCompleter(this);
        Bukkit.getPluginManager().registerEvents(this, this);
        Bukkit.getScheduler().runTaskTimer(this, this::tickAll, 1L, 1L);
    }

    @Override
    public void onDisable() {
        for (Session s : new ArrayList<>(sessions.values())) stop(s);
    }

    private void loadModels() {
        models.clear();
        ConfigurationSection sec = getConfig().getConfigurationSection("models");
        if (sec == null) return;
        for (String id : sec.getKeys(false)) {
            ConfigurationSection m = sec.getConfigurationSection(id);
            Material icon = Material.matchMaterial(m.getString("icon", "BLAZE_ROD"));
            if (icon == null) icon = Material.BLAZE_ROD;
            models.put(id, new Model(id, m.getString("name", id), m.getDouble("max-speed", 40),
                    m.getDouble("acceleration", 7), icon, m.getString("item-model", "")));
        }
    }

    // ------------------------------------------------------------ command

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player p)) { sender.sendMessage("Tylko dla graczy."); return true; }
        if (!p.hasPermission(PERM)) { p.sendMessage("§cNie masz uprawnień."); return true; }
        if (args.length == 0 || args[0].equalsIgnoreCase("gui")) { openGui(p); return true; }
        if (args[0].equalsIgnoreCase("ride")) { toggleRide(p); return true; }
        p.sendMessage("§cUżycie: /kukirin <gui|ride>");
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String l, String[] a) {
        return a.length == 1 ? List.of("gui", "ride") : List.of();
    }

    // ------------------------------------------------------------ stats / data

    private Model selected(Player p) {
        String id = p.getPersistentDataContainer().get(keyModel, PersistentDataType.STRING);
        Model m = id == null ? null : models.get(id);
        return m != null ? m : models.values().iterator().next();
    }

    private NamespacedKey upKey(Up u) { return new NamespacedKey(this, "up_" + u.id); }

    private int level(Player p, Up u) {
        return p.getPersistentDataContainer().getOrDefault(upKey(u), PersistentDataType.INTEGER, 0);
    }

    private int maxLevel(Up u) {
        return getConfig().getInt("upgrades." + u.id + ".max-level",
                (u == Up.BATTERY || u == Up.CONTROLLER) ? 3 : 1);
    }

    private double up(Up u, String key, double def) {
        return getConfig().getDouble("upgrades." + u.id + "." + key, def);
    }

    private Stats stats(Player p) {
        Model m = selected(p);
        int bat = level(p, Up.BATTERY), ctl = level(p, Up.CONTROLLER),
                dual = level(p, Up.DUAL), wb = level(p, Up.WHEELIE);
        double max = m.maxKmh() + bat * up(Up.BATTERY, "speed-per-level", 5) + dual * up(Up.DUAL, "speed-bonus", 15);
        double acc = m.accelKmhS() + ctl * up(Up.CONTROLLER, "accel-per-level", 3) + dual * up(Up.DUAL, "accel-bonus", 4);
        double brake = 1 + wb * up(Up.WHEELIE, "brake-bonus", 0.5);
        double turn = 1 + wb * up(Up.WHEELIE, "turn-bonus", 0.3);
        return new Stats(max, acc, brake, turn);
    }

    // ------------------------------------------------------------ GUI

    private void openGui(Player p) {
        Gui holder = new Gui();
        holder.inv = Bukkit.createInventory(holder, 45, "§8Kukirin - wybór hulajnogi");
        p.openInventory(holder.inv);
        refreshGui(p, holder.inv);
    }

    private void refreshGui(Player p, Inventory inv) {
        inv.clear();
        ItemStack pane = named(Material.GRAY_STAINED_GLASS_PANE, " ", List.of());
        for (int i = 0; i < inv.getSize(); i++) inv.setItem(i, pane);

        Model sel = selected(p);
        int i = 0;
        for (Model m : models.values()) {
            if (i >= MODEL_SLOTS.length) break;
            ItemStack it = modelItem(m);
            ItemMeta meta = it.getItemMeta();
            boolean on = m.id().equals(sel.id());
            meta.setDisplayName((on ? "§a§l" : "§f") + m.name());
            meta.setLore(List.of("§7Max: §f" + (int) m.maxKmh() + " km/h",
                    "§7Przyspieszenie: §f" + m.accelKmhS(),
                    "", on ? "§aWybrana" : "§eKliknij, aby wybrać"));
            meta.setEnchantmentGlintOverride(on);
            meta.addItemFlags(ItemFlag.values());
            it.setItemMeta(meta);
            inv.setItem(MODEL_SLOTS[i++], it);
        }

        for (Up u : Up.values()) {
            int lvl = level(p, u), max = maxLevel(u);
            inv.setItem(UP_SLOTS[u.ordinal()], named(u.icon, u.name,
                    List.of(u.desc, "§7Poziom: §f" + lvl + "§7/§f" + max, "", "§eKliknij, aby zmienić poziom")));
        }

        Stats st = stats(p);
        inv.setItem(RIDE_SLOT, named(Material.LIME_DYE, "§a§lWsiądź na " + sel.name(),
                List.of("§7Max: §f" + (int) st.maxKmh() + " km/h", "§7Przyspieszenie: §f" + String.format("%.1f", st.accel()),
                        "", "§7Zsiadasz klawiszem Shift")));
    }

    private ItemStack named(Material mat, String name, List<String> lore) {
        ItemStack it = new ItemStack(mat);
        ItemMeta meta = it.getItemMeta();
        meta.setDisplayName(name);
        meta.setLore(lore);
        meta.addItemFlags(ItemFlag.values());
        it.setItemMeta(meta);
        return it;
    }

    private ItemStack modelItem(Model m) {
        ItemStack it = new ItemStack(m.icon());
        if (!m.itemModel().isEmpty()) {
            NamespacedKey k = NamespacedKey.fromString(m.itemModel());
            if (k != null) {
                ItemMeta meta = it.getItemMeta();
                meta.setItemModel(k);
                it.setItemMeta(meta);
            }
        }
        return it;
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getView().getTopInventory().getHolder() instanceof Gui)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p)) return;
        if (e.getClickedInventory() != e.getView().getTopInventory()) return;
        if (!p.hasPermission(PERM)) { p.closeInventory(); return; }

        int slot = e.getSlot();
        List<Model> list = new ArrayList<>(models.values());
        for (int i = 0; i < MODEL_SLOTS.length && i < list.size(); i++) {
            if (slot == MODEL_SLOTS[i]) {
                p.getPersistentDataContainer().set(keyModel, PersistentDataType.STRING, list.get(i).id());
                refreshGui(p, e.getView().getTopInventory());
                return;
            }
        }
        for (Up u : Up.values()) {
            if (slot == UP_SLOTS[u.ordinal()]) {
                int next = (level(p, u) + 1) % (maxLevel(u) + 1);
                p.getPersistentDataContainer().set(upKey(u), PersistentDataType.INTEGER, next);
                refreshGui(p, e.getView().getTopInventory());
                return;
            }
        }
        if (slot == RIDE_SLOT) {
            p.closeInventory();
            toggleRide(p);
        }
    }

    // ------------------------------------------------------------ riding

    private void toggleRide(Player p) {
        Session old = sessions.get(p.getUniqueId());
        if (old != null) { stop(old); return; }
        if (p.isInsideVehicle()) { p.sendMessage("§cNajpierw zsiądź z pojazdu."); return; }
        start(p);
    }

    private void start(Player p) {
        Model m = selected(p);
        Location loc = p.getLocation();
        World w = loc.getWorld();
        Session s = new Session();
        s.p = p;
        s.yaw = loc.getYaw();

        s.seat = w.spawn(loc, ArmorStand.class, a -> {
            a.setVisible(false);
            a.setInvulnerable(true);
            a.setSilent(true);
            a.setBasePlate(false);
            a.setPersistent(false);
            a.addScoreboardTag(TAG);
            a.setRotation(loc.getYaw(), 0);
        });

        s.model = w.spawn(loc, ItemDisplay.class, d -> {
            d.setItemStack(modelItem(m));
            d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
            d.setTeleportDuration(1);
            d.setPersistent(false);
            d.addScoreboardTag(TAG);
            d.setTransformation(new Transformation(new Vector3f(0, 0.6f, 0), new AxisAngle4f(),
                    new Vector3f(1.8f), new AxisAngle4f()));
        });

        // "Wyświetlacz" na środku kierownicy - widoczny tylko dla jeżdżącego gracza
        s.dash = w.spawn(loc, TextDisplay.class, d -> {
            d.setVisibleByDefault(false);
            d.setBillboard(Display.Billboard.CENTER);
            d.setTeleportDuration(1);
            d.setText("§a§l0 §7km/h");
            d.setBackgroundColor(Color.fromARGB(110, 0, 0, 0));
            d.setPersistent(false);
            d.addScoreboardTag(TAG);
            d.setTransformation(new Transformation(new Vector3f(), new AxisAngle4f(),
                    new Vector3f(0.5f), new AxisAngle4f()));
        });
        p.showEntity(this, s.dash);

        // "Prawy dolny róg" - w czystym vanillu najbliżej jest sidebar po prawej
        s.oldBoard = p.getScoreboard();
        s.board = Bukkit.getScoreboardManager().getNewScoreboard();
        s.obj = s.board.registerNewObjective("kukirin", "dummy", "§6§lKukirin");
        s.obj.setDisplaySlot(DisplaySlot.SIDEBAR);
        Stats st = stats(p);
        s.obj.getScore("§f" + m.name()).setScore(3);
        s.obj.getScore("§7Max: §f" + (int) st.maxKmh() + " km/h").setScore(2);
        p.setScoreboard(s.board);

        s.seat.addPassenger(p);
        sessions.put(p.getUniqueId(), s);
        p.sendMessage("§aWsiadłeś na §f" + m.name() + "§a. W = gaz, S = hamulec, Shift = zsiadanie.");
    }

    private void stop(Session s) {
        sessions.remove(s.p.getUniqueId());
        if (s.seat != null) s.seat.remove();
        if (s.model != null) s.model.remove();
        if (s.dash != null) s.dash.remove();
        if (s.p.isOnline() && s.oldBoard != null) s.p.setScoreboard(s.oldBoard);
    }

    private void tickAll() {
        for (Session s : new ArrayList<>(sessions.values())) {
            if (!s.p.isOnline() || s.seat.isDead() || !s.seat.getPassengers().contains(s.p)) {
                stop(s);
                continue;
            }
            tick(s);
        }
    }

    private void tick(Session s) {
        Player p = s.p;
        Stats st = stats(p);
        Input in = p.getCurrentInput();

        double max = st.maxKmh() / 72.0;     // km/h -> blocks/tick
        double acc = st.accel() / 1440.0;    // km/h/s -> blocks/tick^2

        if (in.isForward()) s.speed = Math.min(max, s.speed + acc);
        else if (in.isBackward()) s.speed = Math.max(0, s.speed - acc * 4 * st.brake());
        else s.speed = Math.max(0, s.speed - 0.004);
        if (s.speed > max) s.speed = Math.max(max, s.speed - 0.01);

        // kierunek jazdy podąża za kamerą (im szybciej, tym wolniej skręca)
        float target = p.getLocation().getYaw();
        float diff = ((target - s.yaw + 540f) % 360f) - 180f;
        float turn = (float) (Math.max(2.5, 14 - s.speed * 14) * st.turn());
        s.yaw += Math.max(-turn, Math.min(turn, diff));

        Location loc = s.seat.getLocation();
        Vector dir = new Vector(-Math.sin(Math.toRadians(s.yaw)), 0, Math.cos(Math.toRadians(s.yaw)));
        double y = s.seat.getVelocity().getY();

        if (s.speed > 0.01) {
            Block front = loc.clone().add(dir.clone().multiply(0.6 + s.speed)).getBlock();
            if (!front.isPassable()) {
                Block up1 = front.getRelative(BlockFace.UP);
                Block up2 = up1.getRelative(BlockFace.UP);
                if (up1.isPassable() && up2.isPassable() && s.seat.isOnGround()) y = 0.45; // wjazd na 1 blok
                else s.speed *= 0.4;                                                       // ściana
            }
        }

        Vector v = dir.clone().multiply(s.speed);
        v.setY(y);
        s.seat.setVelocity(v);
        s.seat.setRotation(s.yaw, 0);

        Location ml = s.seat.getLocation();
        ml.setYaw(s.yaw);
        ml.setPitch(0);
        s.model.teleport(ml);

        // wyświetlacz na środku kierownicy (przed kamerą, trochę niżej)
        float py = p.getLocation().getYaw();
        Vector fwd = new Vector(-Math.sin(Math.toRadians(py)), 0, Math.cos(Math.toRadians(py)));
        Location dl = p.getEyeLocation().add(fwd.multiply(0.75)).add(0, -0.6, 0);
        s.dash.teleport(dl);

        int kmh = (int) Math.round(s.speed * 72.0);
        s.dash.setText("§a§l" + kmh + " §7km/h");

        // sidebar (prawy bok ekranu)
        String line = "§7Prędkość: §a" + kmh + " km/h";
        if (!line.equals(s.lastLine)) {
            if (s.lastLine != null) s.board.resetScores(s.lastLine);
            s.obj.getScore(line).setScore(1);
            s.lastLine = line;
        }
    }

    // ------------------------------------------------------------ misc events

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        Session s = sessions.get(e.getPlayer().getUniqueId());
        if (s != null) stop(s);
    }

    @EventHandler
    public void onStandManipulate(PlayerArmorStandManipulateEvent e) {
        if (e.getRightClicked().getScoreboardTags().contains(TAG)) e.setCancelled(true);
    }
}
