package io.github.checkselfy.bakemechanisms.stasis;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SnapshotPayloadTest {
    @Test void envelopeRoundTripsBlocksAndWholeEntityGroups() {
        var root = UUID.randomUUID();
        var child = UUID.randomUUID();
        var payload = new SnapshotPayload(new byte[] { 1, 2 }, List.of(
                new SnapshotPayload.EntityGroup(root, List.of(root, child), new byte[] { 3, 4 })));
        var read = SnapshotPayload.decode(payload.encode());
        assertArrayEquals(payload.blocks(), read.blocks());
        assertEquals(List.of(root, child), read.groups().getFirst().members());
        assertArrayEquals(new byte[] { 3, 4 }, read.groups().getFirst().data());
    }

    @Test void legacyBlockOnlySnapshotsRemainReadable() {
        var read = SnapshotPayload.decode(new byte[] { 1, 2, 3 });
        assertArrayEquals(new byte[] { 1, 2, 3 }, read.blocks());
        assertTrue(read.groups().isEmpty());
    }

    @Test void truncatedAndTrailingDataAreRejected() {
        var bytes = new SnapshotPayload(new byte[] { 1 }, List.of()).encode();
        assertThrows(IllegalStateException.class, () -> SnapshotPayload.decode(Arrays.copyOf(bytes, bytes.length - 1)));
        assertThrows(IllegalStateException.class, () -> SnapshotPayload.decode(Arrays.copyOf(bytes, bytes.length + 1)));
    }

    @Test void memberCannotAppearInTwoGroups() {
        var id = UUID.randomUUID();
        var group = new SnapshotPayload.EntityGroup(id, List.of(id), new byte[] { 1 });
        assertThrows(IllegalArgumentException.class, () -> new SnapshotPayload(new byte[] { 1 }, List.of(group, group)));
    }

    @Test void voidCellsRoundTripAndVersionOneRemainsReadable() {
        var position = new SnapshotPayload.VoidBlock(1, 2, 3);
        var payload = new SnapshotPayload(new byte[] { 1 }, List.of(), List.of(position));
        assertEquals(List.of(position), SnapshotPayload.decode(payload.encode()).voidBlocks());
        var empty = new SnapshotPayload(new byte[] { 1 }, List.of()).encode();
        var old = Arrays.copyOf(empty, empty.length - 4);
        java.nio.ByteBuffer.wrap(old).putInt(4, 1);
        assertTrue(SnapshotPayload.decode(old).voidBlocks().isEmpty());
        assertArrayEquals(new byte[] { 1 }, SnapshotPayload.decode(old).blocks());
    }

    @Test void duplicateAndNegativeVoidCellsAreRejected() {
        var position = new SnapshotPayload.VoidBlock(1, 2, 3);
        assertThrows(IllegalArgumentException.class, () -> new SnapshotPayload(new byte[] { 1 }, List.of(), List.of(position, position)));
        assertThrows(IllegalArgumentException.class, () -> new SnapshotPayload.VoidBlock(-1, 0, 0));
    }
}
