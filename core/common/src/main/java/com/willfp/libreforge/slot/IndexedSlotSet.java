package com.willfp.libreforge.slot;

import com.willfp.libreforge.slot.impl.NumericSlotType;

import java.util.AbstractSet;
import java.util.Collection;
import java.util.Iterator;
import java.util.Set;
import java.util.Spliterator;
import java.util.function.Consumer;
import java.util.function.Predicate;

/** A registry snapshot with a fast scan for entities without player inventory slots. */
final class IndexedSlotSet extends AbstractSet<SlotType> {
    private final Set<SlotType> live;
    private volatile SlotType[] mobSlots;

    IndexedSlotSet(Set<SlotType> live) {
        this.live = live;
        this.mobSlots = live.stream()
                .filter(slot -> !(slot instanceof NumericSlotType))
                .toArray(SlotType[]::new);
    }

    SlotType[] mobSlots() {
        return mobSlots;
    }

    // Internal scans only. Public mutations must pass through this set.
    Iterable<SlotType> scanSlots() {
        return live;
    }

    @Override
    public int size() {
        return live.size();
    }

    @Override
    public boolean contains(Object slot) {
        return live.contains(slot);
    }

    @Override
    public void forEach(Consumer<? super SlotType> action) {
        live.forEach(action);
    }

    @Override
    public Spliterator<SlotType> spliterator() {
        return live.spliterator();
    }

    @Override
    public Iterator<SlotType> iterator() {
        var iterator = live.iterator();
        return new Iterator<>() {
            @Override
            public boolean hasNext() {
                return iterator.hasNext();
            }

            @Override
            public SlotType next() {
                return iterator.next();
            }

            @Override
            public void remove() {
                mobSlots = null;
                iterator.remove();
            }
        };
    }

    // Direct public mutations disable the index until the registry publishes a new set.
    @Override
    public boolean add(SlotType slot) {
        mobSlots = null;
        return live.add(slot);
    }

    @Override
    public boolean remove(Object slot) {
        mobSlots = null;
        return live.remove(slot);
    }

    @Override
    public boolean addAll(Collection<? extends SlotType> slots) {
        mobSlots = null;
        return live.addAll(slots);
    }

    @Override
    public boolean removeAll(Collection<?> slots) {
        mobSlots = null;
        return live.removeAll(slots);
    }

    @Override
    public boolean retainAll(Collection<?> slots) {
        mobSlots = null;
        return live.retainAll(slots);
    }

    @Override
    public boolean removeIf(Predicate<? super SlotType> filter) {
        mobSlots = null;
        return live.removeIf(filter);
    }

    @Override
    public void clear() {
        mobSlots = null;
        live.clear();
    }
}
