package net.juligames.adventure.webui;

import com.hazelcast.core.HazelcastInstance;
import net.juligames.core.Core;
import net.juligames.core.adventure.AdventureCore;
import net.juligames.core.adventure.api.AdventureAPI;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.NoSuchElementException;
import java.util.Optional;

/**
 * @author Ture Bentzin
 * 21.02.2023
 */
public class WebUICoreAdapter {

    private static @Nullable Core core;
    private static @Nullable AdventureCore adventureCore;

    public static @NotNull Optional<TagResolver> compileResolver() {
        try {
            if (getAdventureCore() != null) {
                return Optional.of(getAdventureCore().getAdventureTagManager().getResolver());
            }
        }catch (Exception ignored) {
        }
        return Optional.empty();
    }

    @SuppressWarnings("UnstableApiUsage")
    public static @NotNull Core startCore(){
         core = new Core("webUI");
         adventureCore = new AdventureCore();
         adventureCore.start();
        //stopCore
        Runtime.getRuntime().addShutdownHook(new Thread(WebUICoreAdapter::stopCore));
         return core;
    }

    @SuppressWarnings("UnstableApiUsage")
    public static void stopCore(){
        if(core != null) core.stop();
        if (adventureCore != null) {
            adventureCore.dropApiService();
        }
    }

    @ApiStatus.Experimental
    public static boolean isCoreRunning(){
        if(core == null) return false;
        try {
            HazelcastInstance orThrow = core.getOrThrow();
            return orThrow != null;
        }catch (NoSuchElementException e){
            return false;
        }
    }

    public static @Nullable AdventureCore getAdventureCore() {
        return adventureCore;
    }

    public static @Nullable Core getCore() {
        return core;
    }

    public static @NotNull Optional<Core> getCoreOptional() {
        return Optional.ofNullable(getCore());
    }
}
