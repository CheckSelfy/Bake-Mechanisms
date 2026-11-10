package io.github.checkselfy.bakemechanisms.stasis;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

public interface SnapshotStore {
    /** Must complete before any world mutation. */
    void save(SavedSnapshot snapshot) throws IOException;
    List<SavedSnapshot> loadActive() throws IOException;
    /** Deactivate only after successful restoration. Retain the binary snapshot for diagnostics. */
    void deactivate(UUID farmId) throws IOException;
}
