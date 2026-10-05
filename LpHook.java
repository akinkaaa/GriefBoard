package ru.griefboard;

import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.model.user.User;

import java.util.UUID;

/** Отдельный класс, чтобы плагин не падал, если LuckPerms не установлен. */
final class LpHook {
    private LpHook() {}

    static String primaryGroup(UUID id) {
        LuckPerms lp = LuckPermsProvider.get();
        User user = lp.getUserManager().getUser(id);
        return user == null ? null : user.getPrimaryGroup();
    }
}
