package io.github.checkselfy.bakemechanisms.stasis;

import io.github.checkselfy.bakemechanisms.marking.Cuboid;
import io.github.checkselfy.bakemechanisms.marking.FarmRegistry.MarkedFarm;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Objects;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;

/** Immutable, versioned appearance persisted with the snapshot, independent of later config changes. */
public record FillerStyle(int version, Front front, Pattern pattern, int patchSize, Palette palette) {
    public enum Front { AUTO, NORTH, SOUTH, EAST, WEST }
    public enum Pattern { BANDS, PANELS }

    public record Palette(Material shell, Material shade, Material core, Material accent,
                          Material face, Material faceGlass) {
        public Palette {
            for (var material : new Material[]{shell,shade,core,accent,face,faceGlass}) {
                Objects.requireNonNull(material,"filler material");
                String name = material.name();
                if (name.startsWith("LEGACY_") || !(glass(material) || name.endsWith("_CONCRETE") || name.endsWith("_TERRACOTTA")
                        || material == Material.TERRACOTTA))
                    throw new IllegalArgumentException("Небезопасный материал заполнителя: " + material
                            + ". Разрешены стекло, бетон и терракота; slime blocks и блоки с механиками не поддерживаются.");
            }
            if (!glass(shell) || !glass(shade) || !glass(faceGlass))
                throw new IllegalArgumentException("Оболочка и наружный слой лица заполнителя должны быть стеклом.");
        }
        private static boolean glass(Material material) {
            return material == Material.GLASS || material.name().endsWith("_STAINED_GLASS");
        }
    }

    public static final Palette DEFAULT_PALETTE = new Palette(Material.LIME_STAINED_GLASS,
            Material.GREEN_STAINED_GLASS,Material.LIME_TERRACOTTA,Material.GREEN_CONCRETE,
            Material.BLACK_CONCRETE,Material.BLACK_STAINED_GLASS);
    public static final FillerStyle DEFAULT = new FillerStyle(1,Front.AUTO,Pattern.BANDS,3,DEFAULT_PALETTE);
    public static final FillerStyle LEGACY = new FillerStyle(0,Front.NORTH,Pattern.BANDS,3,DEFAULT_PALETTE);

    public FillerStyle {
        if (version != 0 && version != 1) throw new IllegalArgumentException("Неизвестная версия рисунка заполнителя: " + version);
        Objects.requireNonNull(front,"front"); Objects.requireNonNull(pattern,"pattern"); Objects.requireNonNull(palette,"palette");
        if (patchSize < 1 || patchSize > 64) throw new IllegalArgumentException("Размер узора заполнителя должен быть от 1 до 64.");
    }

    public FillerStyle forRegion(Cuboid region) {
        if (front != Front.AUTO) return this;
        long width = (long) region.max().x()-region.min().x()+1;
        long depth = (long) region.max().z()-region.min().z()+1;
        return new FillerStyle(version,width >= depth ? Front.SOUTH : Front.EAST,pattern,patchSize,palette);
    }

    /** Each output inventory has one vote, including a double chest. Vertical-only outputs have no side vote. */
    public FillerStyle forFarm(MarkedFarm farm) {
        if (front != Front.AUTO) return this;
        var preferred = forRegion(farm.mechanism()).front();
        var votes = new EnumMap<Front,Integer>(Front.class);
        for (var output : farm.outputs()) {
            long x = output.blocks().stream().mapToLong(block -> block.x()).sum();
            long z = output.blocks().stream().mapToLong(block -> block.z()).sum();
            int count = output.blocks().size();
            var min = farm.mechanism().min(); var max = farm.mechanism().max();
            var distances = new EnumMap<Front,Long>(Front.class);
            distances.put(Front.WEST, (long) min.x()*count-x);
            distances.put(Front.EAST, x-(long) max.x()*count);
            distances.put(Front.NORTH, (long) min.z()*count-z);
            distances.put(Front.SOUTH, z-(long) max.z()*count);
            long largest = distances.values().stream().mapToLong(Long::longValue).max().orElse(0);
            if (largest <= 0) continue;
            var side = distances.get(preferred)==largest ? preferred : distances.entrySet().stream()
                    .filter(entry -> entry.getValue()==largest).findFirst().orElseThrow().getKey();
            votes.merge(side,1,Integer::sum);
        }
        int most = votes.values().stream().mapToInt(Integer::intValue).max().orElse(0);
        var selected = votes.getOrDefault(preferred,0)==most ? preferred : votes.entrySet().stream()
                .filter(entry -> entry.getValue()==most).findFirst().orElseThrow().getKey();
        return new FillerStyle(version,selected,pattern,patchSize,palette);
    }

    public static FillerStyle fromConfig(ConfigurationSection config) {
        if (config == null) return DEFAULT;
        if (config.contains("patch-size") && !config.isInt("patch-size"))
            throw new IllegalArgumentException("Размер узора заполнителя должен быть целым числом.");
        var p = DEFAULT_PALETTE;
        return new FillerStyle(1,
                Front.valueOf(config.getString("front","AUTO").toUpperCase(Locale.ROOT)),
                Pattern.valueOf(config.getString("pattern","BANDS").toUpperCase(Locale.ROOT)),
                config.getInt("patch-size",3),
                new Palette(material(config,"shell",p.shell()),material(config,"shade",p.shade()),
                        material(config,"core",p.core()),material(config,"accent",p.accent()),
                        material(config,"face",p.face()),material(config,"face-glass",p.faceGlass())));
    }

    private static Material material(ConfigurationSection config,String role,Material fallback) {
        return Material.valueOf(config.getString("palette."+role,fallback.name()).toUpperCase(Locale.ROOT));
    }
}
