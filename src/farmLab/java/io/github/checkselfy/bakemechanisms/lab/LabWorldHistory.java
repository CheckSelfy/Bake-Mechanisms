package io.github.checkselfy.bakemechanisms.lab;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Durable record that lets {@code gradlew resetFarmLab} find every Bake Mechanisms registration made in any lab
 * world this server ever generated, not only the current ones. Lives next to, not inside, the lab's data folder,
 * which the reset deletes.
 *
 * <p>Format, one entry per line: {@code lab=<uuid>} for every generated lab world (only ever added), and
 * {@code server=<uuid>} for each world of the server that is not a lab world, rewritten on every start. A
 * registration in a world that is neither a current server world nor known at all is left over from a lab world
 * generated before this record existed.
 */
final class LabWorldHistory {
    static final String FILE = "BakeFarmLab-worlds.txt";

    private LabWorldHistory() { }

    static void record(Path file, Collection<UUID> labWorlds, Collection<UUID> serverWorlds) throws IOException {
        var labs = new LinkedHashSet<UUID>();
        if (Files.isRegularFile(file)) {
            for (var line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (line.startsWith("lab=")) labs.add(UUID.fromString(line.substring(4).trim()));
            }
        }
        labs.addAll(labWorlds);
        var text = new StringBuilder("# Written by BakeFarmLab for gradlew resetFarmLab; do not edit.\n");
        for (var lab : labs) text.append("lab=").append(lab).append('\n');
        for (var world : Set.copyOf(serverWorlds)) {
            if (!labs.contains(world)) text.append("server=").append(world).append('\n');
        }
        Files.createDirectories(file.getParent());
        var temporary = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(temporary, text, StandardCharsets.UTF_8);
        Files.move(temporary, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }
}
