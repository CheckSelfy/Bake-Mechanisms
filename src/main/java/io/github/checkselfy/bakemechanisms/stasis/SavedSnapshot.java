package io.github.checkselfy.bakemechanisms.stasis;

import io.github.checkselfy.bakemechanisms.marking.FarmRegistry.MarkedFarm;
import io.github.checkselfy.bakemechanisms.marking.FarmRegistry.State;
import java.util.Objects;

public record SavedSnapshot(MarkedFarm farm, byte[] data) {
    public SavedSnapshot {
        Objects.requireNonNull(farm, "farm");
        if (farm.state() != State.STASIS) throw new IllegalArgumentException("Снимок требует STASIS.");
        data = Objects.requireNonNull(data, "data").clone();
        if (data.length == 0) throw new IllegalArgumentException("Пустой снимок.");
    }

    @Override public byte[] data() { return data.clone(); }
}
