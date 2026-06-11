package com.blockdisplay.plugin;

import java.util.List;
import java.util.Map;

public class ModelData {
    public Content content;

    public static class Content {
        public String version;
        public String type;
        public int project_id;
        public List<String> passengers;
        public List<String> hitbox;
        public Datapack datapack;
    }

    public static class Datapack {
        public Map<String, Map<String, List<String>>> anim_keyframes;
    }

    public boolean hasPassengers() {
        return content != null && content.passengers != null && !content.passengers.isEmpty();
    }

    public boolean hasAnimations() {
        return content != null && content.datapack != null
                && content.datapack.anim_keyframes != null
                && !content.datapack.anim_keyframes.isEmpty();
    }

    /** Names of all animations this model ships with (usually just "default"). */
    public java.util.Set<String> getAnimationNames() {
        return hasAnimations() ? content.datapack.anim_keyframes.keySet() : java.util.Set.of();
    }

    /**
     * The animation to play when none was chosen: "default" if present, otherwise the first
     * name in sorted order — so a model whose only animation has a custom name still plays.
     */
    public String defaultAnimName() {
        if (!hasAnimations()) return null;
        if (content.datapack.anim_keyframes.containsKey("default")) return "default";
        return content.datapack.anim_keyframes.keySet().stream().sorted().findFirst().orElse(null);
    }

    /** The keyframes of one animation, or null if the name doesn't exist. */
    public Map<String, List<String>> getAnimation(String name) {
        return hasAnimations() && name != null ? content.datapack.anim_keyframes.get(name) : null;
    }

    public boolean hasHitbox() {
        return content != null && content.hitbox != null && !content.hitbox.isEmpty();
    }
}
