package com.artillexstudios.axinventoryrestore.discord;

import com.artillexstudios.axapi.reflection.ClassUtils;
import com.artillexstudios.axapi.utils.ItemBuilder;
import com.artillexstudios.axapi.utils.StringUtils;
import com.artillexstudios.axinventoryrestore.AxInventoryRestore;
import com.artillexstudios.axinventoryrestore.backups.BackupData;
import com.artillexstudios.axinventoryrestore.discord.preview.InventoryPreview;
import com.artillexstudios.axinventoryrestore.discord.preview.TextureManager;
import com.artillexstudios.axinventoryrestore.pending.PendingRestoreService;
import com.artillexstudios.axinventoryrestore.queue.Priority;
import com.artillexstudios.axinventoryrestore.utils.DateUtils;
import com.artillexstudios.axinventoryrestore.utils.JDAEmbedBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Activity;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.components.buttons.Button;
import net.dv8tion.jda.api.requests.restaction.MessageCreateAction;
import net.dv8tion.jda.api.utils.FileUpload;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static com.artillexstudios.axinventoryrestore.AxInventoryRestore.DISCORD;
import static com.artillexstudios.axinventoryrestore.AxInventoryRestore.LANG;

public class DiscordAddon extends ListenerAdapter {
    private static final Logger log = LoggerFactory.getLogger(DiscordAddon.class);
    private JDA jda = null;

    public DiscordAddon() {
        if (DISCORD.getString("token").isBlank()) return;
        JDABuilder jdaBuilder = JDABuilder.createDefault(DISCORD.getString("token"));
        jdaBuilder.setActivity(Activity.playing(DISCORD.getString("bot-activity", " ")));

        jda = jdaBuilder.build();
        try {
            jda.awaitReady();
            jda.addEventListener(this);
            TextureManager.init();
            Bukkit.getConsoleSender().sendMessage(StringUtils.formatToString("&#00FF00[AxInventoryRestore] Loaded discord module!"));
        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }

    public CompletableFuture<Boolean> sendRequest(Player requester, BackupData backupData) {
        TextChannel channel = jda.getTextChannelById(DISCORD.getString("channel-id"));
        if (channel == null) {
            Bukkit.getConsoleSender().sendMessage(StringUtils.formatToString("&#FF0000[AxInventoryRestore] Discord channel with id %s not found!".formatted(DISCORD.getString("channel-id"))));
            return CompletableFuture.completedFuture(false);
        }

        int id = AxInventoryRestore.getDatabase().addRestoreRequest(backupData.getId());

        Map<String, String> replacements = new HashMap<>();
        replacements.put("%player%", backupData.getPlayerName());
        replacements.put("%requester%", requester.getName());
        replacements.put("%date%", DateUtils.formatDate(backupData.getDate()));
        replacements.put("%category%", LANG.getString("categories." + backupData.getReason() + ".raw", "---"));
        replacements.put("%cause%", backupData.getCause() == null ? "---" : backupData.getCause());
        replacements.put("%location%", backupData.getLocation().getReadable());
        replacements.put("%target-server%", Optional.ofNullable(PendingRestoreService.getTargetServer()).orElse("---"));

        if (ClassUtils.INSTANCE.classExists("net.luckperms.api.LuckPerms")) {
            RegisteredServiceProvider<net.luckperms.api.LuckPerms> provider = Bukkit.getServicesManager().getRegistration(net.luckperms.api.LuckPerms.class);
            if (provider != null) {
                net.luckperms.api.LuckPerms api = provider.getProvider();
                net.luckperms.api.context.ImmutableContextSet set = api.getContextManager().getStaticContext();
                if (set.getAnyValue("server").isPresent()) {
                    String str = set.getAnyValue("server").get();
                    replacements.put("%server%", str);
                }
            }
        }

        CompletableFuture<Boolean> cf = new CompletableFuture<>();
        cf.orTimeout(10, TimeUnit.SECONDS);
        MessageEmbed embed = new JDAEmbedBuilder(DISCORD.getSection("prompt"), replacements).get();
        // the item list and picture are made async, the request is sent once they are ready
        InventoryPreview.create(backupData).thenAccept(preview -> {
            List<FileUpload> files = new ArrayList<>();
            MessageEmbed finalEmbed = embed;
            if (preview != null) {
                try {
                    finalEmbed = preview.apply(embed, files);
                } catch (Exception ex) {
                    files.clear();
                    log.warn("Failed to add the inventory preview to restore request #{}!", id, ex);
                }
            }

            MessageCreateAction action = channel.sendMessageEmbeds(finalEmbed);
            if (!files.isEmpty()) action = action.addFiles(files);
            action = action.addActionRow(
                Button.success("axir-accept:" + id, DISCORD.getString("messages.restore")),
                Button.danger("axir-deny:" + id, DISCORD.getString("messages.decline"))
            );

            // mentions inside embeds never ping, so they have to be in the content
            List<String> pingRoles = DISCORD.getList("ping-role-ids", List.of()).stream()
                    .map(String::valueOf)
                    .map(String::trim)
                    .filter(role -> !role.isEmpty())
                    .toList();
            if (!pingRoles.isEmpty()) {
                action = action.setContent(pingRoles.stream().map(role -> "<@&" + role + ">").collect(Collectors.joining(" ")))
                        .setAllowedMentions(EnumSet.noneOf(Message.MentionType.class))
                        .mentionRoles(pingRoles);
            }

            action.queue((message -> {
                if (!DISCORD.getBoolean("create-thread", true)) {
                    cf.complete(true);
                    return;
                }
                channel.createThreadChannel(DISCORD.getString("thread-name", "-"), message.getId()).queue(threadChannel -> {
                    cf.complete(true);
                });
            }), failure -> {
                log.warn("Failed to send restore request #{} to discord!", id, failure);
                cf.complete(false);
            });
        });
        return cf;
    }

    public ItemStack getRequestItem() {
        return ItemBuilder.create(DISCORD.getSection("request-restore")).get();
    }

    @Override
    public void onButtonInteraction(@NotNull ButtonInteractionEvent event) {
        final String componentId = event.getComponentId();
        final boolean accept;
        if (componentId.startsWith("axir-accept:")) accept = true;
        else if (componentId.startsWith("axir-deny:")) accept = false;
        else return;

        final int id;
        try {
            id = Integer.parseInt(componentId.substring(componentId.indexOf(':') + 1));
        } catch (NumberFormatException ex) {
            return;
        }

        if (event.getMember() == null) {
            event.reply("Something went wrong! member = null").setEphemeral(true).queue();
            return;
        }
        if (!event.getMember().hasPermission(Permission.valueOf(DISCORD.getString("required-permission", "ADMINISTRATOR")))) {
            event.reply(DISCORD.getString("messages.no-permission")).setEphemeral(true).queue();
            return;
        }

        final String status = accept ? "accepted" : "declined";
        try {
            // acknowledge first, discord only waits 3 seconds and the database might be slower
            event.deferReply(true).queue(interactionHook -> AxInventoryRestore.getThreadedQueue().submit(() -> {
                boolean handled = accept
                        ? AxInventoryRestore.getDatabase().grantRestoreRequest(id)
                        : AxInventoryRestore.getDatabase().declineRestoreRequest(id);
                if (!handled) {
                    interactionHook.sendMessage(DISCORD.getString("messages.already-handled")).setEphemeral(true).queue();
                    return;
                }

                MessageEmbed embed = event.getMessage().getEmbeds().get(0);
                event.getMessage().editMessageEmbeds(net.dv8tion.jda.api.EmbedBuilder.fromData(embed.toData())
                                .setAuthor(event.getUser().getName(), null, event.getUser().getAvatarUrl())
                                .setColor(Integer.parseInt(DISCORD.getString("messages." + status +"-color").replace("#", ""), 16)).build())
                        .queue();
                event.getMessage().editMessageComponents().queue();
                interactionHook.sendMessage((DISCORD.getString("messages." + status))).setEphemeral(true).queue();
            }, Priority.HIGH));
        } catch (Exception ex) {
            // ignore jda's spam if interaction fails
        }
    }
}
