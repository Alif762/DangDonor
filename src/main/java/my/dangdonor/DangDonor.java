package my.dangdonor;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

public class DangDonor extends JavaPlugin implements CommandExecutor, Listener {

    private static final int SIZE = 36;
    private static final int CLAIM_SLOT = 31;   // bottom-middle
    private static final int INFO_SLOT = 4;     // top-middle
    private static final int PREVIEW_START = 9;
    private static final int MAX_PREVIEW = 18;

    private static class KitHolder implements InventoryHolder {
        @Override
        public Inventory getInventory() {
            return null;
        }
    }

    private static class KitItem {
        final Material mat;
        final int amount;

        KitItem(Material mat, int amount) {
            this.mat = mat;
            this.amount = amount;
        }
    }

    private File dataFile;
    private YamlConfiguration data;

    @Override
    public void onEnable() {
        // Default settings, saved to plugins/DangDonor/config.yml
        getConfig().addDefault("group", "donor");
        getConfig().addDefault("prefix", "&d[Donor] &r");
        getConfig().addDefault("prefix-priority", 60);
        getConfig().addDefault("kit-cooldown-hours", 24);
        getConfig().addDefault("gui-title", "&8[ &dDonor Kit &8]");
        getConfig().addDefault("kit-items", Arrays.asList(
                "GOLDEN_APPLE 8",
                "COOKED_BEEF 32",
                "EXPERIENCE_BOTTLE 16",
                "ENDER_PEARL 4",
                "DIAMOND 3"));
        getConfig().addDefault("messages.not-donor", "&cThis kit is for donors only.");
        getConfig().addDefault("messages.kit-wait", "&cYour donor kit is ready in &f%time%&c.");
        getConfig().addDefault("messages.kit-claimed", "&aYou claimed your daily donor kit! Thank you for supporting us!");
        getConfig().addDefault("messages.no-permission", "&cYou don't have permission.");
        getConfig().options().copyDefaults(true);
        saveConfig();

        getDataFolder().mkdirs();
        dataFile = new File(getDataFolder(), "data.yml");
        data = YamlConfiguration.loadConfiguration(dataFile);

        Bukkit.getPluginManager().registerEvents(this, this);
        if (getCommand("dkit") != null) getCommand("dkit").setExecutor(this);
        if (getCommand("donor") != null) getCommand("donor").setExecutor(this);
    }

    private String color(String s) {
        return ChatColor.translateAlternateColorCodes('&', s);
    }

    private String msg(String key) {
        return color(getConfig().getString("messages." + key, ""));
    }

    private void saveData() {
        try {
            data.save(dataFile);
        } catch (Exception e) {
            getLogger().warning("Could not save data.yml: " + e.getMessage());
        }
    }

    private void console(String command) {
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
    }

    private boolean validName(String name) {
        return name.matches("[A-Za-z0-9_.]{3,20}");
    }

    // ---------- Commands ----------
    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (cmd.getName().equalsIgnoreCase("dkit")) {
            if (!(sender instanceof Player p)) {
                sender.sendMessage("Only players can use this command.");
                return true;
            }
            openMenu(p);
            return true;
        }

        // /donor ...
        if (!sender.hasPermission("dangdonor.admin")) {
            sender.sendMessage(msg("no-permission"));
            return true;
        }
        if (args.length == 0) {
            usage(sender);
            return true;
        }

        String group = getConfig().getString("group", "donor");
        switch (args[0].toLowerCase()) {
            case "setup" -> {
                console("lp creategroup " + group);
                console("lp group " + group + " meta setprefix "
                        + getConfig().getInt("prefix-priority", 60)
                        + " \"" + getConfig().getString("prefix", "&d[Donor] &r") + "\"");
                console("lp group " + group + " permission set dangdonor.kit true");
                sender.sendMessage(color("&aDonor group '&f" + group + "&a' is ready. Give it with &f/donor give <player>&a."));
            }
            case "give" -> {
                if (args.length < 2 || !validName(args[1])) {
                    sender.sendMessage(color("&eUsage: /donor give <player>"));
                    return true;
                }
                console("lp user " + args[1] + " parent add " + group);
                sender.sendMessage(color("&a" + args[1] + " is now a donor."));
            }
            case "remove" -> {
                if (args.length < 2 || !validName(args[1])) {
                    sender.sendMessage(color("&eUsage: /donor remove <player>"));
                    return true;
                }
                console("lp user " + args[1] + " parent remove " + group);
                sender.sendMessage(color("&a" + args[1] + " is no longer a donor."));
            }
            case "resetkit" -> {
                if (args.length < 2 || !validName(args[1])) {
                    sender.sendMessage(color("&eUsage: /donor resetkit <player>"));
                    return true;
                }
                OfflinePlayer op = Bukkit.getOfflinePlayer(args[1]);
                data.set("last." + op.getUniqueId(), null);
                saveData();
                sender.sendMessage(color("&aKit cooldown reset for " + args[1] + "."));
            }
            case "reload" -> {
                reloadConfig();
                sender.sendMessage(color("&aConfig reloaded."));
            }
            default -> usage(sender);
        }
        return true;
    }

    private void usage(CommandSender s) {
        s.sendMessage(color("&8--- &dDangDonor &8---"));
        s.sendMessage(color("&e/donor setup &7- create the donor group in LuckPerms"));
        s.sendMessage(color("&e/donor give <player> &7- give the donor rank"));
        s.sendMessage(color("&e/donor remove <player> &7- remove the donor rank"));
        s.sendMessage(color("&e/donor resetkit <player> &7- reset the kit cooldown"));
        s.sendMessage(color("&e/donor reload &7- reload the config"));
    }

    // ---------- Kit data ----------
    private List<KitItem> kitItems() {
        List<KitItem> out = new ArrayList<>();
        for (String line : getConfig().getStringList("kit-items")) {
            String[] parts = line.trim().split("\\s+");
            Material m = Material.matchMaterial(parts[0]);
            if (m == null || !m.isItem()) {
                getLogger().warning("Unknown kit item: " + parts[0]);
                continue;
            }
            int amount = 1;
            if (parts.length > 1) {
                try {
                    amount = Integer.parseInt(parts[1]);
                } catch (NumberFormatException e) {
                    getLogger().warning("Invalid amount in kit: " + line);
                    continue;
                }
            }
            if (amount > 0) out.add(new KitItem(m, amount));
        }
        return out;
    }

    private long remainingMs(Player p) {
        long cooldown = (long) (getConfig().getDouble("kit-cooldown-hours", 24) * 3_600_000L);
        long last = data.getLong("last." + p.getUniqueId(), 0L);
        return last + cooldown - System.currentTimeMillis();
    }

    // ---------- GUI ----------
    private void openMenu(Player p) {
        Inventory inv = Bukkit.createInventory(new KitHolder(), SIZE,
                color(getConfig().getString("gui-title", "&8[ &dDonor Kit &8]")));

        ItemStack pane = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta pm = pane.getItemMeta();
        pm.setDisplayName(" ");
        pane.setItemMeta(pm);
        for (int i = 0; i < SIZE; i++) inv.setItem(i, pane);

        // Title item
        inv.setItem(INFO_SLOT, button(Material.NETHER_STAR, "&d&lDaily Donor Kit",
                "&7Thank you for supporting us!"));

        // Kit preview
        List<KitItem> kit = kitItems();
        for (int i = 0; i < kit.size() && i < MAX_PREVIEW; i++) {
            KitItem k = kit.get(i);
            ItemStack it = new ItemStack(k.mat, Math.min(k.amount, k.mat.getMaxStackSize()));
            ItemMeta m = it.getItemMeta();
            m.setLore(List.of("", color("&7Amount: &fx" + k.amount)));
            m.addItemFlags(ItemFlag.values());
            it.setItemMeta(m);
            inv.setItem(PREVIEW_START + i, it);
        }

        // Claim button
        if (!p.hasPermission("dangdonor.kit")) {
            inv.setItem(CLAIM_SLOT, button(Material.BARRIER, "&c&lDONORS ONLY",
                    "&7Get a donor rank to claim this kit."));
        } else {
            long left = remainingMs(p);
            if (left > 0) {
                inv.setItem(CLAIM_SLOT, button(Material.RED_STAINED_GLASS_PANE, "&c&lON COOLDOWN",
                        "&7Ready in &f" + timeText(left)));
            } else {
                inv.setItem(CLAIM_SLOT, button(Material.LIME_STAINED_GLASS_PANE, "&a&lCLAIM KIT",
                        "&7Click to claim your daily kit."));
            }
        }

        p.openInventory(inv);
    }

    private ItemStack button(Material mat, String name, String lore) {
        ItemStack item = new ItemStack(mat);
        ItemMeta m = item.getItemMeta();
        m.setDisplayName(color(name));
        m.setLore(List.of(color(lore)));
        item.setItemMeta(m);
        return item;
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        Inventory top = e.getView().getTopInventory();
        if (!(top.getHolder() instanceof KitHolder)) return;
        e.setCancelled(true);
        if (e.getClickedInventory() != top) return;
        if (!(e.getWhoClicked() instanceof Player p)) return;

        if (e.getSlot() == CLAIM_SLOT) {
            claimKit(p);
            // Refresh the menu so the button shows the new state
            Bukkit.getScheduler().runTask(this, () -> openMenu(p));
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getView().getTopInventory().getHolder() instanceof KitHolder) e.setCancelled(true);
    }

    // ---------- Claim ----------
    private void claimKit(Player p) {
        if (!p.hasPermission("dangdonor.kit")) {
            p.sendMessage(msg("not-donor"));
            return;
        }

        long left = remainingMs(p);
        if (left > 0) {
            p.sendMessage(msg("kit-wait").replace("%time%", timeText(left)));
            return;
        }

        for (KitItem k : kitItems()) {
            int amount = k.amount;
            int max = k.mat.getMaxStackSize();
            while (amount > 0) {
                int n = Math.min(amount, max);
                Map<Integer, ItemStack> rest = p.getInventory().addItem(new ItemStack(k.mat, n));
                for (ItemStack r : rest.values()) {
                    p.getWorld().dropItemNaturally(p.getLocation(), r);
                }
                amount -= n;
            }
        }

        data.set("last." + p.getUniqueId(), System.currentTimeMillis());
        saveData();
        p.sendMessage(msg("kit-claimed"));
        p.playSound(p.getLocation(), "entity.player.levelup", 1f, 1f);
    }

    private String timeText(long ms) {
        long totalMinutes = Math.max(1, ms / 60_000L);
        long hours = totalMinutes / 60;
        long minutes = totalMinutes % 60;
        if (hours > 0) return hours + "h " + minutes + "m";
        return minutes + "m";
    }
            }
