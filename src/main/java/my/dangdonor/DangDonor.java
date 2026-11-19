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
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.Arrays;
import java.util.Map;

public class DangDonor extends JavaPlugin implements CommandExecutor {

    private File dataFile;
    private YamlConfiguration data;

    @Override
    public void onEnable() {
        // Default settings, saved to plugins/DangDonor/config.yml
        getConfig().addDefault("group", "donor");
        getConfig().addDefault("prefix", "&d[Donor] &r");
        getConfig().addDefault("prefix-priority", 60);
        getConfig().addDefault("kit-cooldown-hours", 24);
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
            claimKit(p);
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

    // ---------- Daily kit ----------
    private void claimKit(Player p) {
        if (!p.hasPermission("dangdonor.kit")) {
            p.sendMessage(msg("not-donor"));
            return;
        }

        long cooldown = (long) (getConfig().getDouble("kit-cooldown-hours", 24) * 3_600_000L);
        long last = data.getLong("last." + p.getUniqueId(), 0L);
        long left = last + cooldown - System.currentTimeMillis();
        if (left > 0) {
            p.sendMessage(msg("kit-wait").replace("%time%", timeText(left)));
            return;
        }

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
            int max = m.getMaxStackSize();
            while (amount > 0) {
                int n = Math.min(amount, max);
                Map<Integer, ItemStack> rest = p.getInventory().addItem(new ItemStack(m, n));
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
