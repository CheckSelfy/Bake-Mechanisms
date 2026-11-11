package io.github.checkselfy.bakemechanisms.stasis;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.Map;

/** Versioned envelope around native structure NBT and native entity NBT, never parsed as NMS. */
public record SnapshotPayload(byte[] blocks, List<EntityGroup> groups, List<VoidBlock> voidBlocks) {
    private static final int MAGIC = 0x42414B45;

    public record VoidBlock(int x, int y, int z) {
        public VoidBlock {
            if (x < 0 || y < 0 || z < 0) throw new IllegalArgumentException("Отрицательное смещение блока.");
        }
    }

    public SnapshotPayload(byte[] blocks, List<EntityGroup> groups) { this(blocks, groups, List.of()); }

    public record EntityGroup(UUID rootId, List<UUID> members, byte[] data, Map<UUID, UUID> targets) {
        public EntityGroup(UUID rootId, List<UUID> members, byte[] data) { this(rootId, members, data, Map.of()); }
        public EntityGroup {
            members = List.copyOf(members);
            data = data.clone();
            targets = Map.copyOf(targets);
            if (members.isEmpty() || !members.contains(rootId) || members.stream().distinct().count() != members.size()
                    || data.length == 0) throw new IllegalArgumentException("Некорректная группа сущностей.");
            if (!members.containsAll(targets.keySet())) throw new IllegalArgumentException("Источник цели не входит в группу сущностей.");
        }
        @Override public byte[] data() { return data.clone(); }
    }

    public SnapshotPayload {
        blocks = blocks.clone();
        groups = List.copyOf(groups);
        voidBlocks = List.copyOf(voidBlocks);
        if (voidBlocks.stream().distinct().count() != voidBlocks.size()) throw new IllegalArgumentException("Повтор технического блока.");
        if (blocks.length == 0) throw new IllegalArgumentException("Пустой снимок блоков.");
        var ids = new java.util.HashSet<UUID>();
        for (var group : groups) for (var id : group.members()) {
            if (!ids.add(id)) throw new IllegalArgumentException("Сущность включена в снимок дважды.");
        }
    }

    @Override public byte[] blocks() { return blocks.clone(); }

    public byte[] encode() {
        try (var bytes = new ByteArrayOutputStream(); var out = new DataOutputStream(bytes)) {
            out.writeInt(MAGIC);
            out.writeInt(3);
            writeBytes(out, blocks);
            out.writeInt(groups.size());
            for (var group : groups) {
                writeId(out, group.rootId());
                out.writeInt(group.members().size());
                for (var id : group.members()) writeId(out, id);
                writeBytes(out, group.data());
                out.writeInt(group.targets().size());
                for (var target : new java.util.TreeMap<>(group.targets()).entrySet()) {
                    writeId(out, target.getKey());
                    writeId(out, target.getValue());
                }
            }
            out.writeInt(voidBlocks.size());
            for (var block : voidBlocks) {
                out.writeInt(block.x());
                out.writeInt(block.y());
                out.writeInt(block.z());
            }
            return bytes.toByteArray();
        } catch (IOException impossible) {
            throw new AssertionError(impossible);
        }
    }

    public static SnapshotPayload decode(byte[] data) {
        try (var input = new DataInputStream(new ByteArrayInputStream(data))) {
            // Compatibility with the structure-only snapshots created by #5.
            if (data.length < 4 || input.readInt() != MAGIC) return new SnapshotPayload(data, List.of());
            int version = input.readInt();
            if (version < 1 || version > 3) throw new IOException("Неизвестная версия снимка.");
            var blocks = readBytes(input);
            int count = count(input, 1);
            var groups = new ArrayList<EntityGroup>();
            for (int i = 0; i < count; i++) {
                var root = readId(input);
                int size = count(input, 16);
                var members = new ArrayList<UUID>();
                for (int j = 0; j < size; j++) members.add(readId(input));
                var bytes = readBytes(input);
                var targets = new java.util.LinkedHashMap<UUID, UUID>();
                if (version >= 3) {
                    int sizeTargets = count(input, 32);
                    for (int j = 0; j < sizeTargets; j++) {
                        if (targets.put(readId(input), readId(input)) != null) throw new IOException("Повтор источника цели.");
                    }
                }
                groups.add(new EntityGroup(root, members, bytes, targets));
            }
            var voidBlocks = new ArrayList<VoidBlock>();
            if (version >= 2) {
                int size = count(input, 12);
                for (int i = 0; i < size; i++) voidBlocks.add(new VoidBlock(input.readInt(), input.readInt(), input.readInt()));
            }
            if (input.available() != 0) throw new IOException("Лишние данные снимка.");
            return new SnapshotPayload(blocks, groups, voidBlocks);
        } catch (IOException | IllegalArgumentException error) {
            throw new IllegalStateException("Не удалось прочитать содержимое снимка.", error);
        }
    }

    private static int count(DataInputStream input, int minimumBytes) throws IOException {
        int count = input.readInt();
        if (count < 0 || count > input.available() / minimumBytes) throw new IOException("Некорректная длина снимка.");
        return count;
    }

    private static byte[] readBytes(DataInputStream input) throws IOException {
        return input.readNBytes(count(input, 1));
    }

    private static void writeBytes(DataOutputStream out, byte[] data) throws IOException {
        out.writeInt(data.length);
        out.write(data);
    }

    private static void writeId(DataOutputStream out, UUID id) throws IOException {
        out.writeLong(id.getMostSignificantBits());
        out.writeLong(id.getLeastSignificantBits());
    }

    private static UUID readId(DataInputStream input) throws IOException { return new UUID(input.readLong(), input.readLong()); }
}
